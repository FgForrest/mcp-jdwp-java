package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 *   <li>Maven {@code dependency:build-classpath} per detected {@code pom.xml}</li>
 * </ul>
 *
 * <p>The project directory is the one the MCP client reports, when it reports one (Codex, see
 * {@link ClientWorkspace}), and the server's launch directory otherwise (Claude Code starts the
 * server in the project).
 *
 * <p>The first {@link #discoverWithBreakdown()} call is memoised; subsequent calls return the cached
 * result until {@link #reset()} is invoked (typically on JDI reconnect) or the project directory
 * changes. Discovery is synchronised so concurrent callers see the same memoised snapshot.
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
    // Package-private (not private) so ProcessBuilderMavenRunner.HARVEST_MAX_DEPTH can derive from
    // it at compile time, keeping the harvester's walk depth genuinely coupled to the scan depth.
    static final int MAX_SCAN_DEPTH = 5;
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
     * Subset of {@link #SKIP_DIRS} that other components (notably the Maven-output harvester) must
     * also skip when walking the project tree. {@code target} is excluded here because the harvester
     * specifically looks for files whose direct parent is {@code target/} — that gate handles the
     * "skip nested target dirs we shouldn't descend into" concern without needing this list.
     */
    static final Set<String> HARVEST_SKIP_DIRS = Set.of(
        ".git", ".idea", ".vscode", "node_modules", ".gradle", ".mvn"
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
     */
    public record Breakdown(int envOverride, int filesystem, int maven, Set<String> all) {}

    /** Directory the scan is anchored at when no client reports one; the server's launch directory in production. */
    private final Path launchDirectory;
    /** Source of the client-reported project directory; {@code null} when the client's is never consulted. */
    private final @Nullable ClientWorkspace clientWorkspace;
    /** Indirection for {@link System#getenv(String)} so tests can drive the env-override branch. */
    private final Function<String, @Nullable String> envLookup;
    /** Indirection for the Maven shell-out so tests can stub success/failure modes. */
    private final MavenRunner mavenRunner;
    /** Memoised result of the first {@link #discoverWithBreakdown()} call; cleared by {@link #reset()}. */
    private @Nullable Breakdown cachedBreakdown;
    /** Project directory {@link #cachedBreakdown} was computed for. */
    private @Nullable Path cachedFor;

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
     * Returns the union of all contributing entries from the memoised breakdown. Convenience
     * wrapper around {@link #discoverWithBreakdown()} for callers that only need the merged set.
     *
     * @return insertion-ordered, immutable set of local classpath entries
     */
    public Set<String> discover() {
        return discoverWithBreakdown().all();
    }

    /**
     * Project directory the next discovery is rooted at: the directory the MCP client reported, or
     * the launch directory when no client reported one. Exposed for the {@code jdwp_diagnose} report
     * and the evaluator's "nothing found" hint so they name the directory that was actually scanned.
     */
    public Path getWorkingDirectory() {
        final Path reported = clientWorkspace == null ? null : clientWorkspace.reportedDirectory();
        return reported != null ? reported : launchDirectory;
    }

    /**
     * True when {@link #getWorkingDirectory()} comes from the MCP client rather than from the
     * server's launch directory.
     */
    public boolean isWorkingDirectoryReportedByClient() {
        return clientWorkspace != null && clientWorkspace.reportedDirectory() != null;
    }

    /**
     * True when a {@code pom.xml} sits at the working-directory root. Cheap I/O; called only from
     * the diagnose path (once per call). Surfaced so the report can say "pom.xml: yes/no" without
     * duplicating the path logic.
     */
    public boolean hasPomAtRoot() {
        // NOFOLLOW_LINKS: the provider's invariant is "symlinks are never followed". A symlinked
        // pom.xml could redirect Maven discovery at an arbitrary tree on the host filesystem; we
        // treat it as "no pom" so the Maven source is skipped, matching how the filesystem scan
        // treats symlinked target/classes directories.
        return Files.isRegularFile(getWorkingDirectory().resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Non-blocking peek at the cached breakdown. Returns {@code null} when no discovery has run
     * yet on this provider instance, or when the cached one was computed for another project
     * directory. Used by the diagnose path so the report never blocks the caller waiting for a
     * cold-cache Maven invocation; if the cache is cold, the diagnose renderer prints a one-line
     * "not yet computed" hint and moves on.
     */
    public synchronized @Nullable Breakdown peekCachedBreakdown() {
        return getWorkingDirectory().equals(cachedFor) ? cachedBreakdown : null;
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
     * Runs (or returns the memoised result of) the three-source discovery. Synchronised so two
     * concurrent callers cannot both pay the Maven invocation cost; the second caller blocks and
     * receives the first caller's cached breakdown.
     *
     * <p>On an empty result, emits one INFO log line that explains which sources had what state
     * (env unset vs blank, {@code target/} present, {@code pom.xml} present) so operators can
     * diagnose "why is nothing being added" without enabling DEBUG.
     *
     * @return memoised breakdown — never null; {@link Breakdown#all()} is immutable
     */
    public synchronized Breakdown discoverWithBreakdown() {
        final Path workingDirectory = getWorkingDirectory();
        final Breakdown cached = cachedBreakdown;
        if (cached != null) {
            if (workingDirectory.equals(cachedFor)) {
                return cached;
            }
            log.info("[LocalClasspath] Project directory changed from {} to {} — scanning again",
                cachedFor, workingDirectory);
        }
        final Set<String> entries = new LinkedHashSet<>();
        addEnvOverride(entries);
        final int afterEnv = entries.size();
        addFilesystemScan(entries, workingDirectory);
        final int afterFs = entries.size();
        addMavenDependencies(entries, workingDirectory);
        final int afterMaven = entries.size();
        final Breakdown breakdown = new Breakdown(
            afterEnv,
            afterFs - afterEnv,
            afterMaven - afterFs,
            // Wrap a defensive LinkedHashSet copy so the cached view is immutable but still
            // preserves insertion order — callers rely on containsExactly-style assertions and
            // Set.copyOf would drop order (it returns a hash-based immutable set).
            Collections.unmodifiableSet(new LinkedHashSet<>(entries))
        );
        cachedBreakdown = breakdown;
        cachedFor = workingDirectory;
        if (entries.isEmpty()) {
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
     * Clears the memoised breakdown so the next {@link #discoverWithBreakdown()} call re-scans.
     * Called by {@link JdiExpressionEvaluator#configureCompilerClasspath} on a JDI reconnect because
     * the new connection may target a different project layout — a stale breakdown would mismatch.
     */
    public synchronized void reset() {
        cachedBreakdown = null;
        cachedFor = null;
        log.debug("[LocalClasspath] Cache reset");
    }

    /**
     * Adds Maven-resolved dependency jars to {@code entries}. No-ops cleanly when no {@code pom.xml}
     * sits at the working-directory root. Logs at INFO with the count and elapsed time on every
     * outcome (added &gt; 0, added == 0, failure) so operators see one line per discovery.
     */
    private void addMavenDependencies(Set<String> entries, Path workingDirectory) {
        // NOFOLLOW_LINKS: same invariant as hasPomAtRoot — a symlinked pom.xml must not trigger
        // Maven discovery, because Maven would resolve the link and pull dependencies from a tree
        // outside the configured workingDirectory.
        if (!Files.isRegularFile(workingDirectory.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
            log.debug("[LocalClasspath] No pom.xml under {} — skipping Maven source", workingDirectory);
            return;
        }
        final List<String> command = buildMavenCommand(workingDirectory);
        final long startTime = System.currentTimeMillis();
        try {
            final List<String> jars = mavenRunner.run(command, workingDirectory, MAVEN_TIMEOUT_SECONDS);
            final int before = entries.size();
            entries.addAll(jars);
            final int added = entries.size() - before;
            final long elapsed = System.currentTimeMillis() - startTime;
            if (added > 0) {
                log.info("[LocalClasspath] Maven contributed {} dependency jars in {}ms", added, elapsed);
            } else {
                // "Reason-out on empty" — required by the logging policy. 0 jars is NOT necessarily a
                // failure: a project with no runtime dependencies (or a filtered classpath) legitimately
                // yields nothing. The runner already logs a WARN on a genuine shell-level failure, so
                // point operators there for the failure case without implying one happened.
                log.info("[LocalClasspath] Maven contributed 0 jars in {}ms (no runtime deps, or Maven "
                    + "failed — check earlier WARN lines for timeout / non-zero exit / missing output)", elapsed);
            }
        } catch (Exception e) {
            log.warn("[LocalClasspath] Maven dependency:build-classpath failed after {}ms: {}: {} (cwd={})",
                System.currentTimeMillis() - startTime, e.getClass().getSimpleName(), e.getMessage(),
                workingDirectory);
        }
    }

    /**
     * Builds the {@code dependency:build-classpath} command line, picking up the project's Maven
     * wrapper when available (see {@link #resolveMavenExecutable}). The output file lives under each
     * module's {@code target/}, never at a bare project-root filename.
     */
    private static List<String> buildMavenCommand(Path workingDirectory) {
        final String executable = resolveMavenExecutable(workingDirectory);
        // `-q` keeps Maven CLI noise off the operator's terminal; the runner captures stdout to a
        // size-capped buffer so the WARN path can still surface diagnostic output on failure.
        // `-DincludeScope=runtime` matches what's actually on the app's runtime classpath.
        // The output file lives under each module's `target/` — Maven-owned build output, gitignored
        // everywhere, wiped by `mvn clean`. NEVER use a bare filename here (`.jdwp-mcp-classpath`):
        // that would land in project source directories which we must not write to. See the
        // file-safety policy.
        // We do NOT pass `-Dmdep.pathSeparator=:` — that flag forces a Unix separator on Windows and
        // would corrupt drive letters in jar paths. Letting Maven use the platform default and
        // splitting on File.pathSeparator on the read side keeps things consistent.
        return List.of(
            executable,
            "-q",
            "dependency:build-classpath",
            "-DincludeScope=runtime",
            "-Dmdep.outputFile=target/.jdwp-mcp-classpath"
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
        if (depth > MAX_SCAN_DEPTH || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
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
            children
                // `Files::isDirectory` (the method reference) FOLLOWS links — use the explicit
                // overload with NOFOLLOW_LINKS so a symlinked directory is treated as "not a
                // directory" and skipped.
                .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> {
                    final String name = p.getFileName().toString();
                    return !name.startsWith(".") && !SKIP_DIRS.contains(name);
                })
                .forEach(child -> scanForClassDirs(child, depth + 1, entries));
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
     * <p>Implementations MUST follow the empty-list-on-failure contract: any non-zero exit,
     * timeout, exception, or interruption is reflected by returning an empty list (after logging
     * the cause). This keeps the provider's outer flow declarative — it can union the result with
     * other sources without per-source null/error handling.
     */
    @FunctionalInterface
    public interface MavenRunner {
        /**
         * Runs {@code command} with {@code workingDirectory} as the process CWD, bounded by
         * {@code timeoutSeconds}, and returns the classpath entries the command's output files
         * contributed.
         *
         * @param command          full argv (executable + arguments) to invoke
         * @param workingDirectory CWD for the spawned process; output files are harvested under it
         * @param timeoutSeconds   wall-clock budget; on timeout the implementation MUST kill the
         *                         process and return an empty list
         * @return harvested classpath entries; empty on any failure (never null)
         */
        List<String> run(List<String> command, Path workingDirectory, int timeoutSeconds);
    }
}
