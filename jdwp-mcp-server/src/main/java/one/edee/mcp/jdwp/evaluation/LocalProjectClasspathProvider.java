package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.InvocationRunner;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Computes a local-project classpath used as an additive fallback when remote classloader-based
 * discovery returns an incomplete view of the target VM's classpath (Tomcat / Spring Boot dev-tools
 * / custom URLClassLoaders that hide their JARs from {@code getURLs()}).
 *
 * <p>Three composable sources, evaluated in order. The result preserves insertion order so the
 * first source to contribute an entry wins. The caller is responsible for unioning the returned
 * entries with whatever the remote discovery produced — this provider never consults the target VM.
 * <ul>
 *   <li>{@code JDWP_EXTRA_CLASSPATH} env var, parsed by {@link File#pathSeparator}</li>
 *   <li>Filesystem scan of {@code target/classes} / {@code target/test-classes} under the project
 *       directory</li>
 *   <li>Maven {@code dependency:build-classpath} when the project root has a {@code pom.xml}</li>
 * </ul>
 *
 * <p>The project directory is the one the MCP client reports, when it reports one (Codex, see
 * {@link ClientWorkspace}), and the server's launch directory otherwise (Claude Code starts the
 * server in the project).
 *
 * <p>Discovery runs on a daemon executor. A new JDI connection starts a fresh discovery with
 * the directory captured at attach. A subsequent evaluation can start a replacement when the
 * client reports a different directory.
 *
 * <p>Registered as a Spring {@code @Service}, but the second constructor takes seams so the env
 * lookup and Maven invocation can be stubbed in tests without touching real environment variables
 * or shelling out.
 */
@Service
public class LocalProjectClasspathProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalProjectClasspathProvider.class);
    /** Environment variable callers can set to inject extra classpath entries verbatim. */
    private static final String ENV_NAME = "JDWP_EXTRA_CLASSPATH";
    /**
     * Filesystem-scan depth cap. Five levels covers reactor / module / sub-module layouts without
     * walking into Node / Gradle / IDE caches that an unbounded walk would otherwise stat-flood.
     */
    private static final int MAX_SCAN_DEPTH = 5;
    /**
     * Cold-cache {@code mvn} may download dependencies on first run, which routinely takes 1-3
     * minutes on a fresh machine. 180s covers the common case; a real timeout still logs a clear
     * WARN with the captured stdout so the user knows what to retry.
     */
    private static final int MAVEN_TIMEOUT_SECONDS = 180;
    /**
     * Directory names the filesystem scan must never descend into — VCS, IDE, package-manager
     * caches, and {@code target} itself (we look at it explicitly and never recurse). Without the
     * list a {@code target/} hit in {@code node_modules} would balloon I/O and yield junk entries.
     */
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".idea", ".vscode", "node_modules", ".gradle", ".mvn",
        "target" // skip target itself — we look at it explicitly, never recurse into it
    );

    /**
     * Per-source breakdown of the most recent discovery; exposed by {@code jdwp_diagnose}.
     *
     * @param envOverride number of entries contributed by {@code JDWP_EXTRA_CLASSPATH}
     * @param filesystem  number of {@code target/classes} / {@code target/test-classes} entries the
     *                    filesystem scan added on top of the env override
     * @param maven       number of entries Maven's {@code dependency:build-classpath} added on top
     *                    of env + filesystem (dedup'd against earlier sources)
     * @param all         insertion-ordered union of all contributing entries; never null
     * @param failure     diagnostic when a source failed, if any
     */
    public record Breakdown(int envOverride, int filesystem, int maven, Set<String> all,
                            @Nullable String failure) {}

    /**
     * Snapshot for the dispatcher, tool evaluations, and {@code jdwp_diagnose}. Reading the status
     * returns immediately, so a slow Maven run cannot stall event processing or diagnostics.
     * A null breakdown means discovery is pending when {@code running} is true, or that there is
     * no discovery for this directory yet. A completed breakdown may contain partial results
     * alongside a failure.
     */
    public record DiscoveryStatus(Path directory, boolean reportedByClient, boolean running,
                                  @Nullable Breakdown breakdown) {
        /** The diagnose tool can check the captured directory with a single filesystem probe. */
        public boolean hasPomAtRoot() {
            return hasPom(directory);
        }

        /** Returns a pending or failed discovery message, or {@code null} when neither applies. */
        public @Nullable String problem() {
            if (running) {
                return "Local classpath discovery is still running.";
            }
            return breakdown == null ? null : breakdown.failure();
        }
    }

    private record ProjectDirectory(Path path, boolean reportedByClient) {}

    /**
     * The directory, scan identity, and result observed together during compiler configuration.
     * The evaluator uses this snapshot to check publication and decide whether to reuse options.
     */
    record Snapshot(ProjectDirectory project, @Nullable Discovery scan, DiscoveryStatus status) {
        /**
         * Returns whether the snapshots refer to the same directory and scan. When local entries
         * are in use, also checks the result's identity to detect completion of a pending scan.
         */
        boolean sameInput(Snapshot other, boolean includeLocal) {
            return scan == other.scan && project.equals(other.project)
                && (!includeLocal || status.breakdown() == other.status.breakdown());
        }
    }

    /**
     * One background scan for a directory captured when the task was submitted. The daemon
     * publishes its completed breakdown through {@code ready} so the dispatcher and diagnose can
     * read it without waiting on the future. Tool evaluations and tests can wait on the future
     * for up to the Maven timeout. Replacing a discovery interrupts the old task and isolates its
     * result, even if that task finishes after the replacement is installed.
     */
    private final class Discovery {
        /** Root captured by the thread that requested the scan. */
        private final Path directory;
        /** Background computation that MCP workers can wait for. */
        private final FutureTask<Breakdown> task;
        /** Dedicated daemon that runs the scan and can be interrupted independently. */
        private final Thread worker;
        /** Completed result for non-waiting readers, written under the compiler publication gate. */
        private volatile @Nullable Breakdown ready;

        /** Prepares a discovery and its daemon. The caller starts it after publishing the scan. */
        private Discovery(Path directory) {
            this.directory = directory;
            this.task = new FutureTask<>(() -> {
                Breakdown breakdown;
                try {
                    breakdown = scan(directory);
                } catch (RuntimeException e) {
                    log.warn("[LocalClasspath] Discovery failed (cwd={})", directory, e);
                    breakdown = new Breakdown(0, 0, 0, Set.of(), "Local classpath discovery failed: " + e);
                }
                synchronized (LocalProjectClasspathProvider.this) {
                    ready = breakdown;
                }
                return breakdown;
            });
            // A filesystem read may still be blocked in the OS after cancellation. A separate
            // worker lets a replacement scan run while this one unwinds.
            this.worker = new Thread(task, "jdi-local-classpath");
            this.worker.setDaemon(true);
        }

        /**
         * Waits on a tool worker or test thread up to the Maven budget. Returns the breakdown,
         * or {@code null} on timeout, cancellation, or interruption. Wraps unexpected task
         * failure in {@link IllegalStateException}.
         */
        private @Nullable Breakdown await() {
            try {
                return task.get(MAVEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException | CancellationException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                throw new IllegalStateException("Local classpath discovery failed", e.getCause());
            }
        }
    }

    /** Directory the scan is anchored at when no client reports one; the server's launch directory in production. */
    private final Path launchDirectory;
    /** Source of the client-reported project directory; {@code null} when the client's is never consulted. */
    private final @Nullable ClientWorkspace clientWorkspace;
    /** Indirection for {@link System#getenv(String)} so tests can drive the env-override branch. */
    private final Function<String, @Nullable String> envLookup;
    /** Indirection for the Maven shell-out so tests can stub success/failure modes. */
    private final MavenRunner mavenRunner;
    /** Replaced on attach or directory change. The executor completes only its own task. */
    private volatile @Nullable Discovery discovery;
    /** Once bound, only a thread of the active VM can request or publish a scan. Guarded by this. */
    private boolean connectionScoped;
    /** Active VM identity, or {@code null} after teardown. Guarded by the provider monitor. */
    private @Nullable VirtualMachine owner;

    /**
     * Test seam: build the provider with explicit working directory, env-lookup function, and Maven
     * runner. Spring wires the no-seam constructor in production — tests use this one to drive every
     * code path without touching real environment variables or shelling out.
     */
    public LocalProjectClasspathProvider(Path workingDirectory,
                                         Function<String, @Nullable String> envLookup,
                                         MavenRunner mavenRunner) {
        this(workingDirectory, null, envLookup, mavenRunner);
    }

    /**
     * Test seam that also takes the {@link ClientWorkspace} whose reported directory overrides
     * {@code launchDirectory}.
     */
    public LocalProjectClasspathProvider(Path launchDirectory,
                                  @Nullable ClientWorkspace clientWorkspace,
                                  Function<String, @Nullable String> envLookup,
                                  MavenRunner mavenRunner) {
        this.launchDirectory = launchDirectory;
        this.clientWorkspace = clientWorkspace;
        this.envLookup = envLookup;
        this.mavenRunner = mavenRunner;
    }

    /**
     * Default Spring wiring: anchors at the directory the MCP client reports, falling back to the
     * JVM's launch directory, and uses the real process environment for the env-var lookup. The
     * {@link ProcessBuilderMavenRunner} is injected so Maven invocations go through the real
     * shell-out path in production.
     */
    @Autowired
    public LocalProjectClasspathProvider(ProcessBuilderMavenRunner mavenRunner, ClientWorkspace clientWorkspace) {
        // `user.dir` is documented as always-present on a normal JVM launch, but a sandboxed
        // / restricted launch can drop it. Defaulting to "." rather than letting Path.of(null)
        // NPE keeps the bean constructible — the scan then runs against the process's effective
        // working directory, which is the same place "." would resolve to anyway.
        this(Path.of(Objects.toString(System.getProperty("user.dir"), ".")), clientWorkspace, System::getenv,
            mavenRunner);
    }

    /**
     * Returns the local entries for tests that only need the merged set. Waits up to the Maven
     * budget through {@link #discoverWithBreakdown()}. Production callers use the status snapshot
     * or the tool evaluation wait instead.
     *
     * @return insertion-ordered, immutable set of local classpath entries
     */
    public Set<String> discover() {
        return discoverWithBreakdown().all();
    }

    /**
     * Project directory the next discovery is rooted at: the directory the MCP client reported, or
     * the launch directory when no client reported one. The dispatcher and diagnostics use
     * {@link #discoveryStatus()} to keep the displayed directory and discovery result together.
     */
    public Path getWorkingDirectory() {
        return projectDirectory().path();
    }

    private ProjectDirectory projectDirectory() {
        final Path reported = clientWorkspace == null ? null : clientWorkspace.reportedDirectory();
        return reported == null ? new ProjectDirectory(launchDirectory, false) : new ProjectDirectory(reported, true);
    }

    /** True when the MCP client reported the project directory. */
    public boolean isWorkingDirectoryReportedByClient() {
        return projectDirectory().reportedByClient();
    }

    /**
     * True when a regular, non-symlinked {@code pom.xml} sits at the working-directory root.
     * Uses the same check as Maven discovery and the diagnose snapshot so they agree on whether
     * the Maven source applies.
     */
    public boolean hasPomAtRoot() {
        return hasPom(getWorkingDirectory());
    }

    private static boolean hasPom(Path directory) {
        // NOFOLLOW_LINKS keeps a symlinked pom.xml from redirecting Maven discovery into an
        // arbitrary tree on the host. The filesystem scan also skips symlinked class directories.
        return Files.isRegularFile(directory.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS);
    }

    /** Reads the current project's discovery state without starting discovery or waiting for it. */
    public DiscoveryStatus discoveryStatus() {
        return statusFor(projectDirectory(), discovery);
    }

    /** Lets diagnose report the runner's cleanup capability without waiting for discovery. */
    public boolean mavenUsesDescendantPolling() {
        return mavenRunner.usesDescendantPolling();
    }

    /**
     * Returns status for the requested directory from the scan's published result. A scan for
     * another directory is treated as absent. This read never waits on the discovery task.
     */
    private static DiscoveryStatus statusFor(ProjectDirectory directory, @Nullable Discovery current) {
        if (current == null || !directory.path().equals(current.directory)) {
            return new DiscoveryStatus(directory.path(), directory.reportedByClient(), false, null);
        }
        final Breakdown breakdown = current.ready;
        return new DiscoveryStatus(directory.path(), directory.reportedByClient(), breakdown == null, breakdown);
    }

    /** Returns the completed breakdown for the current directory, if available. */
    public @Nullable Breakdown peekCachedBreakdown() {
        return discoveryStatus().breakdown();
    }

    /**
     * Reports whether the {@code JDWP_EXTRA_CLASSPATH} env var is present in the lookup, regardless
     * of whether it parses to any entries. The diagnose path uses this to distinguish three states:
     * <ul>
     *   <li>{@code (unset)} — the variable is absent from the environment</li>
     *   <li>{@code (set, no entries)} — set but blank or whitespace-only</li>
     *   <li>{@code (set)} — set with at least one entry</li>
     * </ul>
     * Operators chase a frustrating false-negative when "set-but-blank" is rendered the same as
     * "unset". Exposing presence separately fixes the conflation without touching the breakdown.
     */
    public boolean isEnvOverrideSet() {
        return envLookup.apply(ENV_NAME) != null;
    }

    /**
     * Reports whether the {@code JDWP_EXTRA_CLASSPATH} env var has any non-blank entries after
     * splitting on the host {@link File#pathSeparator}. Lets the diagnose path render the three-way
     * "set, no entries" state on a cold cache without triggering full discovery — the answer
     * depends only on the env var, not on the filesystem or Maven.
     */
    public boolean envOverrideHasEntries() {
        final String raw = envLookup.apply(ENV_NAME);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (String part : raw.split(Pattern.quote(File.pathSeparator), -1)) {
            if (!part.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts or joins discovery for the current directory and returns the completed breakdown.
     * Tests use this to inspect the results directly and can block for up to the Maven timeout.
     * Production diagnostics read {@link #discoveryStatus()} so the report stays responsive.
     */
    public Breakdown discoverWithBreakdown() {
        final Discovery current;
        synchronized (this) {
            current = currentDiscovery(getWorkingDirectory());
        }
        final Breakdown breakdown = current.await();
        if (breakdown == null) {
            throw new IllegalStateException("Local classpath discovery did not complete within the wait");
        }
        return breakdown;
    }

    /**
     * The thread establishing a JDI connection calls this before starting its event listener
     * and dispatcher. Captures the directory reported at attach and queues a fresh scan so a
     * reconnect sees the current project layout. Requests cancellation of any prior discovery
     * and returns without waiting for either scan to finish.
     */
    synchronized void startForConnection(VirtualMachine vm) {
        connectionScoped = true;
        owner = vm;
        replaceDiscovery(getWorkingDirectory());
    }

    /** Clears a stopped connection's scan immediately and interrupts its worker without joining it. */
    synchronized void stopForConnection(VirtualMachine vm) {
        if (owner == vm) {
            owner = null;
            reset();
        }
    }

    /**
     * The event dispatcher reads a snapshot without waiting. When the client reports a different
     * directory, an evaluation starts the replacement scan. Remote-only prewarming just reads.
     */
    synchronized @Nullable Snapshot snapshotFor(@Nullable VirtualMachine vm, boolean start) {
        if (connectionScoped && (owner == null || owner != vm)) {
            return null;
        }
        final ProjectDirectory project = projectDirectory();
        final Discovery current = start ? currentDiscovery(project.path()) : discovery;
        return new Snapshot(project, current, statusFor(project, current));
    }

    /**
     * An MCP invocation worker waits outside the evaluator and session monitors. Cancellation
     * wakes the wait, and the evaluator checks the connection again before publishing anything.
     */
    void awaitDiscovery(@Nullable VirtualMachine vm) {
        final Snapshot snapshot = snapshotFor(vm, true);
        final Discovery current = snapshot == null ? null : snapshot.scan();
        if (current != null && current.ready == null) {
            InvocationRunner.duringLocalClasspathWait(current::await);
        }
    }

    /**
     * Publishes compiler options on the dispatcher or an evaluation worker only while the
     * connection, directory, and any observed local result are current. Returns false to retry.
     */
    synchronized boolean publishIfCurrent(@Nullable VirtualMachine vm, Snapshot snapshot, boolean includeLocal,
                                          Runnable publish) {
        if ((connectionScoped && (owner == null || owner != vm)) || discovery != snapshot.scan()) {
            return false;
        }
        final ProjectDirectory project = snapshot.project();
        if (includeLocal && statusFor(project, discovery).breakdown() != snapshot.status().breakdown()) {
            return false;
        }
        final ClientWorkspace workspace = clientWorkspace;
        if (workspace == null) {
            publish.run();
            return true;
        }
        return workspace.publishIfCurrent(project.reportedByClient() ? project.path() : null, publish);
    }

    /**
     * Gets the current scan for the directory or starts a replacement without waiting.
     * Called under the provider monitor by evaluation or test threads.
     */
    private Discovery currentDiscovery(Path directory) {
        final Discovery current = discovery;
        if (current != null && directory.equals(current.directory)) {
            return current;
        }
        return replaceDiscovery(directory);
    }

    /**
     * Installs and returns a fresh scan under the provider monitor. The attach or evaluation
     * caller starts the dedicated worker and requests cancellation of the prior scan.
     */
    private Discovery replaceDiscovery(Path directory) {
        final Discovery previous = discovery;
        final Discovery next = new Discovery(directory);
        discovery = next;
        cancel(previous);
        next.worker.start();
        return next;
    }

    /** Runs on the discovery daemon and returns the combined entries and per-source counts. */
    private Breakdown scan(Path workingDirectory) {
        final Set<String> entries = new LinkedHashSet<>();
        addEnvOverride(entries);
        final int afterEnv = entries.size();
        addFilesystemScan(entries, workingDirectory);
        final int afterFs = entries.size();
        final String mavenFailure = addMavenDependencies(entries, workingDirectory);
        final int afterMaven = entries.size();
        final Breakdown breakdown = new Breakdown(
            afterEnv,
            afterFs - afterEnv,
            afterMaven - afterFs,
            // Wrap a defensive LinkedHashSet copy so the cached view is immutable but still
            // preserves insertion order — callers rely on containsExactly-style assertions and
            // Set.copyOf would drop order (it returns a hash-based immutable set).
            Collections.unmodifiableSet(new LinkedHashSet<>(entries)),
            mavenFailure
        );
        if (entries.isEmpty() && !Thread.currentThread().isInterrupted()) {
            // Required by the logging policy: ONE INFO line on empty result explaining the why.
            // NOFOLLOW_LINKS on every probe — keep the diagnostic readout consistent with the
            // "symlinks not followed" invariant used by every other filesystem probe in this class.
            final boolean pomPresent =
                Files.isRegularFile(workingDirectory.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS);
            log.info("[LocalClasspath] discover() found 0 entries — env={}, fs={}, maven={} (cwd={}, pom.xml={})",
                envLookup.apply(ENV_NAME) == null ? "unset" : "set-but-empty",
                Files.isDirectory(workingDirectory.resolve("target"), LinkOption.NOFOLLOW_LINKS)
                    ? "target/-present" : "no-target/",
                pomPresent ? "pom-present" : "no-pom",
                workingDirectory,
                pomPresent);
        }
        return breakdown;
    }

    /**
     * Tests use this to discard the current result without queuing a replacement. It interrupts
     * an active daemon task and returns without waiting for cleanup. Production teardown uses
     * {@link #stopForConnection(VirtualMachine)} to cancel work for the departing VM.
     */
    public synchronized void reset() {
        final Discovery previous = discovery;
        discovery = null;
        cancel(previous);
    }

    /**
     * Cancels the future immediately to wake waiters. A separate daemon interrupts the old
     * worker so an I/O close hook cannot block the replacement or teardown caller.
     */
    private static void cancel(@Nullable Discovery previous) {
        if (previous != null && previous.task.cancel(false)) {
            // Wake anyone awaiting the result now. Thread.interrupt() may run an I/O close hook
            // on its caller, so signal the worker from a daemon outside the discovery monitor.
            final Thread interrupter = new Thread(previous.worker::interrupt, "jdi-local-classpath-cancel");
            interrupter.setDaemon(true);
            interrupter.start();
        }
    }

    /**
     * Adds Maven-resolved dependency jars to {@code entries}. No-ops cleanly when no {@code pom.xml}
     * sits at the working-directory root. Logs successful contributions at INFO, including why
     * a successful run can contribute zero entries. Returns failures in the breakdown for diagnose.
     */
    private @Nullable String addMavenDependencies(Set<String> entries, Path workingDirectory) {
        if (Thread.currentThread().isInterrupted()) {
            return "Maven classpath discovery was interrupted.";
        }
        if (!hasPom(workingDirectory)) {
            log.debug("[LocalClasspath] No pom.xml under {} — skipping Maven source", workingDirectory);
            return null;
        }
        final List<String> command = buildMavenCommand(workingDirectory);
        final long startTime = System.currentTimeMillis();
        try {
            final List<String> jars = mavenRunner.run(command, workingDirectory, MAVEN_TIMEOUT_SECONDS);
            final int before = entries.size();
            entries.addAll(jars);
            final int added = entries.size() - before;
            final long elapsed = System.currentTimeMillis() - startTime;
            if (added == 0) {
                // A successful Maven run can have no runtime dependencies in scope or return only
                // entries already found by another source. Give operators that context for zero.
                log.info("[LocalClasspath] Maven contributed 0 dependency jars in {}ms "
                    + "(no runtime dependencies in scope or entries already present)", elapsed);
            } else {
                log.info("[LocalClasspath] Maven contributed {} dependency jars in {}ms", added, elapsed);
            }
            return null;
        } catch (ProcessBuilderMavenRunner.OutputReadException e) {
            entries.addAll(e.entries());
            log.warn("[LocalClasspath] Maven classpath output read failed (cwd={})", workingDirectory, e);
            return "Maven classpath discovery failed: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Maven classpath discovery was interrupted.";
        } catch (Exception e) {
            log.warn("[LocalClasspath] Maven dependency:build-classpath failed after {}ms (cwd={})",
                System.currentTimeMillis() - startTime, workingDirectory, e);
            return "Maven classpath discovery failed: " + e;
        }
    }

    /**
     * Builds the {@code dependency:build-classpath} command line, picking up the project's Maven
     * wrapper when available (see {@link #resolveMavenExecutable}). The production runner adds
     * the run-specific output path.
     */
    private static List<String> buildMavenCommand(Path workingDirectory) {
        final String executable = resolveMavenExecutable(workingDirectory);
        // `-q` keeps Maven CLI noise off the operator's terminal; the runner captures stdout to a
        // size-capped buffer so the WARN path can still surface diagnostic output on failure.
        // `-DincludeScope=runtime` matches what's actually on the app's runtime classpath.
        // We do NOT pass `-Dmdep.pathSeparator=:` — that flag forces a Unix separator on Windows and
        // would corrupt drive letters in jar paths. Letting Maven use the platform default and
        // splitting on File.pathSeparator on the read side keeps things consistent.
        return List.of(
            executable,
            "-q",
            "dependency:build-classpath",
            "-DincludeScope=runtime"
        );
    }

    /**
     * Picks the Maven executable in priority order:
     * <ul>
     *   <li>On Unix-likes (Linux/macOS): {@code mvnw} when executable, then PATH {@code mvn}.</li>
     *   <li>On Windows: {@code mvnw.cmd} when present, then PATH {@code mvn} (which Windows
     *       resolves to {@code mvn.cmd} via {@code PATHEXT}).</li>
     * </ul>
     * <p>The Unix-style {@code mvnw} probe is intentionally skipped on Windows: even though
     * {@link Files#isExecutable(Path)} may still report the Unix shell script as executable (the
     * Windows check is ACL-based, not extension-based), {@link ProcessBuilder} cannot invoke a
     * shell script directly on Windows and the launch would fail. {@code mvnw.cmd} is the only
     * sane wrapper choice there.
     * <p>{@code mvnw.cmd} cannot be probed with {@link Files#isExecutable(Path)} because the
     * NTFS executable bit isn't a thing — {@link Files#isRegularFile(Path, LinkOption...)} is the
     * right gate.
     */
    private static String resolveMavenExecutable(Path workingDirectory) {
        if (isWindows()) {
            final Path mvnwCmd = workingDirectory.resolve("mvnw.cmd");
            // NOFOLLOW_LINKS: a symlinked mvnw.cmd could point at a script outside the working
            // directory and redirect Maven execution there, defeating the "symlinks never
            // followed" invariant the rest of this class enforces.
            if (Files.isRegularFile(mvnwCmd, LinkOption.NOFOLLOW_LINKS)) {
                return mvnwCmd.toAbsolutePath().toString();
            }
            return "mvn";
        }
        final Path mvnw = workingDirectory.resolve("mvnw");
        // Same invariant: reject a symlinked wrapper outright. Files.isExecutable() FOLLOWS
        // symlinks, so we gate on isRegularFile(..., NOFOLLOW_LINKS) first; only when the entry
        // is a real file do we ask whether it is executable. (isExecutable then follows the link
        // to query the ACL, but at that point we have already proven it is not a link.)
        if (Files.isRegularFile(mvnw, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(mvnw)) {
            return mvnw.toAbsolutePath().toString();
        }
        return "mvn";
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    /**
     * Walks the working directory for {@code target/classes} / {@code target/test-classes} dirs and
     * adds each as an entry. Always emits one INFO line summarising the count and elapsed time so
     * operators can see whether the scan contributed or not without enabling DEBUG.
     */
    private static void addFilesystemScan(Set<String> entries, Path workingDirectory) {
        final long startTime = System.currentTimeMillis();
        final int before = entries.size();
        scanForClassDirs(workingDirectory, 0, entries);
        final int added = entries.size() - before;
        final long elapsed = System.currentTimeMillis() - startTime;
        if (added > 0) {
            log.info("[LocalClasspath] Filesystem scan contributed {} target/classes entries in {}ms",
                added, elapsed);
        } else {
            log.info("[LocalClasspath] Filesystem scan found 0 target/classes under {} (depth <= {}, {}ms)",
                workingDirectory, MAX_SCAN_DEPTH, elapsed);
        }
    }

    /**
     * Recursively probes {@code dir} (and descendants, up to {@link #MAX_SCAN_DEPTH}) for
     * {@code target/classes} / {@code target/test-classes}. Skips {@link #SKIP_DIRS} and
     * dot-prefixed directories. Tolerates per-directory failures (logged at WARN) rather than
     * aborting — a partial classpath beats no classpath.
     */
    private static void scanForClassDirs(Path dir, int depth, Set<String> entries) {
        // NOFOLLOW_LINKS everywhere: a symlink to another tree on the same filesystem could create
        // walk cycles or pull in jars from outside the project. The documented invariant is that
        // this scan never follows links — enforce it at every probe.
        if (Thread.currentThread().isInterrupted()
            || depth > MAX_SCAN_DEPTH || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // At each directory, probe for target/classes and target/test-classes BEFORE recursing — we
        // want a module's classes whether or not it has child modules.
        // NOFOLLOW_LINKS only governs the FINAL path component, so a single two-segment resolve like
        // dir.resolve("target/classes") would still follow a symlinked `target` — defeating the
        // invariant. Probe `target` itself first: isDirectory(NOFOLLOW) is false for a symlink, so a
        // linked `target` (pointing at a tree outside the project) is rejected here. Only once it is
        // confirmed to be a real directory do we resolve its leaves, where NOFOLLOW then guards the
        // last component (`classes` / `test-classes`).
        final Path target = dir.resolve("target");
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            final Path classes = target.resolve("classes");
            if (Files.isDirectory(classes, LinkOption.NOFOLLOW_LINKS)) {
                entries.add(classes.toString());
            }
            final Path testClasses = target.resolve("test-classes");
            if (Files.isDirectory(testClasses, LinkOption.NOFOLLOW_LINKS)) {
                entries.add(testClasses.toString());
            }
        }
        if (depth == MAX_SCAN_DEPTH) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            final Iterator<Path> paths = children.iterator();
            // Check before advancing the directory iterator, which may itself block on I/O.
            while (!Thread.currentThread().isInterrupted() && paths.hasNext()) {
                final Path child = paths.next();
                final String name = child.getFileName().toString();
                if (!name.startsWith(".") && !SKIP_DIRS.contains(name)) {
                    // scanForClassDirs checks the type without following symlinked directories.
                    scanForClassDirs(child, depth + 1, entries);
                }
            }
        } catch (AccessDeniedException e) {
            // Permission denied on a single directory is expected (CI agents, restricted dev envs).
            // Log at WARN so an operator sees it once, but never abort the whole scan.
            log.warn("[LocalClasspath] Permission denied listing {} — skipping subtree", dir);
        } catch (UncheckedIOException e) {
            // Files.list() is lazy: a permission failure hit while ITERATING entries (inside the
            // terminal forEach) surfaces as UncheckedIOException wrapping the real IOException, not
            // as a bare AccessDeniedException. Unwrap so a denied subtree still gets the accurate
            // "permission denied" message rather than the generic catch-all below.
            if (e.getCause() instanceof AccessDeniedException) {
                log.warn("[LocalClasspath] Permission denied listing {} — skipping subtree", dir);
            } else {
                log.warn("[LocalClasspath] Could not list {}: {}: {}",
                    dir, e.getClass().getSimpleName(), e.getMessage());
            }
        } catch (Exception e) {
            // Any other failure on a single directory is unexpected — log it visibly. We do NOT
            // throw: a partial classpath is better than no classpath, and the agent will see the
            // diagnose breakdown to understand what happened.
            log.warn("[LocalClasspath] Could not list {}: {}: {}",
                dir, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Parses {@link #ENV_NAME} (when set) on the host {@link File#pathSeparator} and adds each
     * non-blank, non-duplicate entry. Logs at INFO with the added count; at DEBUG when the variable
     * is unset or blank.
     */
    private void addEnvOverride(Set<String> entries) {
        final String raw = envLookup.apply(ENV_NAME);
        if (raw == null || raw.isBlank()) {
            log.debug("[LocalClasspath] {} is unset or blank — env override contributed 0 entries", ENV_NAME);
            return;
        }
        // Split on the HOST OS path separator. A regex like `[;:]` is WRONG on Windows because
        // `C:\foo` contains a colon — splitting would shred the drive letter. The env var is set
        // by the user on the host, so the host's File.pathSeparator is the correct delimiter.
        final String[] parts = raw.split(Pattern.quote(File.pathSeparator), -1);
        int added = 0;
        for (String part : parts) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty() && entries.add(trimmed)) {
                added++;
            }
        }
        log.info("[LocalClasspath] {} contributed {} entries", ENV_NAME, added);
    }

    /**
     * Seam for executing a Maven command and harvesting its output. The production implementation
     * is {@link ProcessBuilderMavenRunner}; tests provide in-memory stubs.
     *
     * Failures propagate to the provider so it can report them alongside the other sources.
     */
    @FunctionalInterface
    public interface MavenRunner {
        /**
         * Runs {@code command} with {@code workingDirectory} as the process CWD, bounded by
         * {@code timeoutSeconds}, and returns the classpath entries the command's output files
         * contributed.
         *
         * @param command          Maven executable and discovery arguments; the runner supplies its output path
         * @param workingDirectory CWD for the spawned process; the runner reads output from its private directory
         * @param timeoutSeconds   wall-clock budget; on timeout the implementation MUST kill the
         *                         process before reporting the failure
         * @return harvested classpath entries
         */
        List<String> run(List<String> command, Path workingDirectory, int timeoutSeconds)
            throws IOException, InterruptedException;

        /** Whether cleanup on this host uses descendant polling and may miss reparented helpers. */
        default boolean usesDescendantPolling() {
            return false;
        }
    }
}
