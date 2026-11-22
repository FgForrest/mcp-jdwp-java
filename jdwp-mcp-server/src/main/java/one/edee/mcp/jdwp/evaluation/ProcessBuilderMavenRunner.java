package one.edee.mcp.jdwp.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Production {@link LocalProjectClasspathProvider.MavenRunner} that shells out to {@code mvn} (or
 * the project's Maven wrapper) via {@link ProcessBuilder} and harvests the per-module
 * {@code .jdwp-mcp-classpath} files Maven writes under each module's {@code target/}.
 *
 * <p>Responsibility boundary: this class owns the process invocation, timeout enforcement, stdout
 * draining, and output-file collection. It NEVER deletes files — {@code target/} is Maven-owned
 * and gets wiped by {@code mvn clean}; arbitrary deletion in the project tree is prohibited by the
 * file-safety policy. Callers receive the failure so they can diagnose a partial classpath.
 *
 * <p>Thread model: each {@link #run} invocation spawns ONE daemon platform thread named
 * {@code jdwp-mcp-maven-stdout-drainer} to drain the child process's merged stdout/stderr stream.
 * The drainer terminates when the child closes its pipe (i.e. when the process exits or is forcibly
 * destroyed), so its lifetime is bounded by the process. Platform (not virtual) threads are used
 * because the project targets Java 17.
 */
@Service
public class ProcessBuilderMavenRunner implements LocalProjectClasspathProvider.MavenRunner {

    /** Filename Maven writes the classpath into under each module's {@code target/}. */
    private static final String OUTPUT_FILE_NAME = ".jdwp-mcp-classpath";
    /** Upper bound on captured child stdout/stderr included in the WARN log on failure. */
    private static final int STDOUT_CAPTURE_BYTES = 64 * 1024;
    /**
     * Walk cap for the classpath-file harvester: the provider's filesystem scan depth plus two —
     * covering {@code <module>/target/.jdwp-mcp-classpath} for modules sitting at the scan boundary
     * (the file is two segments — {@code target/.jdwp-mcp-classpath} — below the module dir). Derived
     * from {@link LocalProjectClasspathProvider#MAX_SCAN_DEPTH} at compile time so a future bump to
     * the scan depth is mirrored here automatically rather than silently dropping boundary modules.
     */
    private static final int HARVEST_MAX_DEPTH = LocalProjectClasspathProvider.MAX_SCAN_DEPTH + 2;
    private static final Logger log = LoggerFactory.getLogger(ProcessBuilderMavenRunner.class);

    /** Seam for the actual process execution; replaced in tests to drive timeout / failure paths. */
    private final CommandExecutor commandExecutor;

    /** Default Spring wiring — uses the real {@link ProcessBuilder}-based command executor. */
    public ProcessBuilderMavenRunner() {
        this(ProcessBuilderMavenRunner::executeRealCommand);
    }

    /** Test seam: swap in a stub {@link CommandExecutor} to drive success, failure, and timeout. */
    ProcessBuilderMavenRunner(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    /** {@inheritDoc} */
    @Override
    public List<String> run(List<String> command, Path workingDirectory, int timeoutSeconds)
        throws IOException, InterruptedException {
        final int exitCode = commandExecutor.execute(new CommandRequest(command, workingDirectory, timeoutSeconds));
        if (exitCode == -1) {
            throw new IOException("Maven timed out after " + timeoutSeconds + " seconds");
        }
        if (exitCode != 0) {
            throw new IOException("Maven exited with code " + exitCode);
        }
        return harvestOutputFiles(workingDirectory);
    }

    /**
     * Walks {@code root} for Maven-written classpath files and aggregates their entries.
     *
     * <p><b>Depth.</b> The walk's max depth is {@link #HARVEST_MAX_DEPTH} (= filesystem-scan depth
     * + 2), so a module discovered at the scan boundary still has its {@code target/<file>}
     * reachable. Counting from the root, {@code <root>/a/b/c/d/e/target/.jdwp-mcp-classpath} is
     * seven levels deep; the previous fixed depth of 5 would silently drop reactor modules at the
     * deeper boundary even though the scan correctly discovered them.
     *
     * <p><b>File-safety invariant.</b> A candidate file is accepted ONLY if BOTH conditions hold:
     * its direct parent directory is named {@code target}, AND no ancestor directory between
     * {@code root} and the candidate is in {@link LocalProjectClasspathProvider#HARVEST_SKIP_DIRS}.
     * This gate keeps the harvester confined to Maven-owned build output even on projects that
     * happen to contain a stray file with the matching name elsewhere in the tree. The harvester
     * NEVER deletes; the next Maven run overwrites the file in place and {@code mvn clean} removes
     * it.
     *
     * <p>Uses {@link Files#walkFileTree} with {@code preVisitDirectory} pruning so skip-listed
     * directories ({@code node_modules}, {@code .git}, {@code .idea}, {@code .gradle}, etc.)
     * are never descended into — a meaningful saving on large repos where {@code node_modules/}
     * alone can hold tens of thousands of files. Symlinks are not followed because no
     * {@link FileVisitOption#FOLLOW_LINKS} is passed.
     */
    private static List<String> harvestOutputFiles(Path root) throws IOException {
        final Set<String> entries = new LinkedHashSet<>();
        final List<IOException> failures = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), HARVEST_MAX_DEPTH, new SimpleFileVisitor<Path>() {
                /** Prunes excluded directories and throws on cancellation before descending. */
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    checkHarvestInterrupted();
                    if (!dir.equals(root)) {
                        final Path name = dir.getFileName();
                        if (name != null && LocalProjectClasspathProvider.HARVEST_SKIP_DIRS.contains(name.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                /** Collects entries or output-read failures and continues to the next file. */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    checkHarvestInterrupted();
                    if (attrs.isRegularFile() && isClasspathOutput(file)) {
                        try {
                            readOutputFile(file, entries);
                        } catch (IOException | InvalidPathException e) {
                            failures.add(new IOException("Could not read classpath output " + file + ": " + e, e));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                /** Records inaccessible classpath output and continues unless cancelled. */
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    checkHarvestInterrupted();
                    final Path name = file.getFileName();
                    if (isClasspathOutput(file) || (name != null && "target".equals(name.toString()))) {
                        failures.add(new IOException("Could not access classpath output at " + file + ": " + exc, exc));
                    } else {
                        log.debug("[LocalClasspath] Skipping unreadable path {}: {}", file, exc.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failures.add(e);
        }
        if (!failures.isEmpty()) {
            throw new OutputReadException(List.copyOf(entries), failures);
        }
        return new ArrayList<>(entries);
    }

    /**
     * Whether {@code file} is a {@code target/.jdwp-mcp-classpath} output written by the Maven run.
     */
    private static boolean isClasspathOutput(Path file) {
        final Path name = file.getFileName();
        final Path parent = file.getParent();
        final Path parentName = parent == null ? null : parent.getFileName();
        return name != null && OUTPUT_FILE_NAME.equals(name.toString())
            && parentName != null && "target".equals(parentName.toString());
    }

    /**
     * Ends the output walk when the discovery was cancelled (a superseded connection or directory).
     */
    private static void checkHarvestInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Maven classpath output scan was interrupted");
        }
    }

    /** Carries entries read successfully even when another module's output was unreadable. */
    static final class OutputReadException extends IOException {
        /** Serialization version for this harvest exception. */
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        /** Entries harvested successfully before the failure was reported. */
        private final List<String> entries;

        /** Preserves partial entries and summarizes a nonempty list of output-read failures. */
        OutputReadException(List<String> entries, List<IOException> failures) {
            super(failures.get(0).getMessage() + (failures.size() == 1 ? ""
                : " (" + (failures.size() - 1) + " additional output read failures)"), failures.get(0));
            this.entries = entries;
        }

        /** Returns entries the provider can keep alongside the reported Maven failure. */
        List<String> entries() {
            return entries;
        }
    }

    /**
     * Throws {@link InvalidPathException} for a classpath entry that is not a valid local path.
     */
    private static void requireValidPath(String entry) {
        if (Path.of(entry).toString().isEmpty()) {
            throw new InvalidPathException(entry, "empty classpath entry");
        }
    }

    /**
     * Reads a Maven-written {@code .jdwp-mcp-classpath} file, splits on the host
     * {@link File#pathSeparator}, and appends non-blank, non-duplicate entries to {@code entries}.
     * Maven wrote this file on the host so its entries are separated by the host's separator —
     * splitting on {@code [;:]} would shred {@code C:\foo} on Windows.
     *
     * <p>NO DELETION. The file lives in a Maven-owned {@code target/} directory; the next Maven
     * run overwrites it in place and {@code mvn clean} removes it. Deleting arbitrary files in
     * the project tree is forbidden by the file-safety policy.
     */
    private static void readOutputFile(Path file, Set<String> entries) throws IOException {
        final String content = Files.readString(file);
        if (content.indexOf('\0') >= 0) {
            throw new IOException("NUL byte in classpath output");
        }
        if (content.isBlank()) {
            log.debug("[LocalClasspath] Output file {} was empty", file);
            return;
        }
        final Set<String> parsed = new LinkedHashSet<>();
        final String separator = Pattern.quote(File.pathSeparator);
        for (String part : content.split(separator, -1)) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                // Reject malformed paths before committing any entries from this file.
                requireValidPath(trimmed);
                parsed.add(trimmed);
            }
        }
        final int before = entries.size();
        entries.addAll(parsed);
        log.debug("[LocalClasspath] Harvested {} new entries from {}", entries.size() - before, file);
    }

    /**
     * Runs the Maven command via {@link ProcessBuilder} with stderr merged into stdout. A daemon
     * platform thread drains the merged stream into a {@link #STDOUT_CAPTURE_BYTES}-bounded buffer
     * so the child never blocks on a full pipe and the WARN log on failure includes Maven's actual
     * diagnostic output. Returns the process exit code, or {@code -1} if the wall-clock budget was
     * exceeded (after {@link Process#destroyForcibly()}). On {@link InterruptedException}, kills
     * the owned process tree, restores the interrupt flag, and rethrows so the caller can
     * propagate cancellation. Cleanup also covers interruptions while joining the stdout drainer.
     */
    private static int executeRealCommand(CommandRequest req) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        // On Linux a private process group lets cleanup reach helpers even after reparenting.
        // Keep the owned descendant sweep for platforms without setsid and to let Maven reap
        // its children before killing the root.
        final boolean isolated = Files.isExecutable(Path.of("/usr/bin/setsid"))
            && Files.isExecutable(Path.of("/bin/kill"));
        if (isolated) {
            command.add("/usr/bin/setsid");
            command.add("--");
        }
        command.addAll(req.command());
        final Set<ProcessHandle> observed = new LinkedHashSet<>();
        final Process process = new ProcessBuilder(command).directory(req.workingDirectory().toFile())
            .redirectErrorStream(true).start();
        final int exit;
        try {
            exit = collectProcessOutput(req, process, isolated, observed);
        } catch (InterruptedException e) {
            destroyProcessTree(process, isolated, observed);
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException | Error e) {
            destroyProcessTree(process, isolated, observed);
            throw e;
        }
        // The wrapper can exit with helpers still running. Finish their cleanup before the output
        // walk. A process group also reaches helpers whose parent has already exited.
        if (exit != -1) {
            if (isolated) {
                killProcessGroup(process.pid());
            } else if (observed.stream().anyMatch(ProcessHandle::isAlive)) {
                destroyProcessTree(process, false, observed);
            }
        }
        return exit;
    }

    /**
     * Drains the child's output and waits for it within the budget. Returns the exit code, or
     * {@code -1} after killing the process tree on timeout.
     */
    private static int collectProcessOutput(CommandRequest req, Process process, boolean isolated,
                                            Set<ProcessHandle> observed) throws InterruptedException {
        // Drain stdout so the process never blocks on a full pipe buffer (a common cause of
        // ProcessBuilder hangs). Capture the first STDOUT_CAPTURE_BYTES into a buffer so the WARN
        // log on non-zero exit / timeout can include Maven's actual diagnostic output. The cap
        // prevents a runaway log volume from a Maven misconfiguration.
        // NOTE: a daemon platform thread is used here rather than a virtual thread because the
        // project targets Java 17 (virtual threads were finalized in Java 21). The semantics are
        // identical: "drain in the background, don't block the process pipe".
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        // Explicit monitor: ByteArrayOutputStream's per-method synchronization is an OpenJDK
        // implementation detail and does not give the JMM guarantee we need across "write from
        // drainer + read from main on the timeout path". A shared lock makes the cap check + write
        // atomic and the toString() snapshot consistent.
        final Object capturedLock = new Object();
        final Thread drainer = new Thread(() -> {
            try (var in = process.getInputStream()) {
                final byte[] buf = new byte[4096];
                int n;
                // `!= -1` (not `> 0`): EOF is the only loop-exit. A spurious zero-length read must
                // keep draining, or the child could block on a full stdout pipe — the exact hang
                // this drainer exists to prevent.
                while ((n = in.read(buf)) != -1) {
                    synchronized (capturedLock) {
                        if (captured.size() < STDOUT_CAPTURE_BYTES) {
                            captured.write(buf, 0, Math.min(n, STDOUT_CAPTURE_BYTES - captured.size()));
                        }
                    }
                    // Continue reading even after the cap so the process never blocks on output.
                }
            } catch (IOException ignored) {}
        }, "jdwp-mcp-maven-stdout-drainer");
        drainer.setDaemon(true);
        drainer.start();
        final boolean exited = isolated ? process.waitFor(req.timeoutSeconds(), TimeUnit.SECONDS)
            : waitForAndObserveChildren(process, req.timeoutSeconds(), observed);
        if (!exited) {
            destroyProcessTree(process, isolated, observed);
            // Let the drainer capture writes already in flight before logging the timeout.
            drainer.join(500);
            log.warn("[LocalClasspath] Maven invocation timed out after {}s. Captured output (up to {} bytes):\n{}",
                req.timeoutSeconds(), STDOUT_CAPTURE_BYTES, snapshotCaptured(captured, capturedLock));
            return -1;
        }
        // The wrapper may have exited while a helper still has the stdout pipe open. An
        // interrupt here takes the same cleanup path as an interrupt during process.waitFor.
        drainer.join(1000);
        if (drainer.isAlive()) {
            // The drainer outlived its budget — a very large Maven output stream or a stuck child
            // still holding the pipe open. We have already collected the exit code, so this is not
            // a correctness issue, but a one-line DEBUG helps anyone investigating slow shutdowns.
            log.debug("[LocalClasspath] stdout drainer still alive after 1s join — leaving as daemon");
        }
        final int exit = process.exitValue();
        if (exit != 0) {
            log.warn("[LocalClasspath] Maven exited with code {}. Captured output (up to {} bytes):\n{}",
                exit, STDOUT_CAPTURE_BYTES,
                snapshotCaptured(captured, capturedLock));
        } else {
            log.debug("[LocalClasspath] Maven succeeded ({} bytes of output captured)",
                snapshotSize(captured, capturedLock));
        }
        return exit;
    }

    /**
     * Without a process group, retain the handles seen while the parent was alive so an interrupt
     * during stdout draining can still reach helpers even if the parent has already exited.
     */
    private static boolean waitForAndObserveChildren(Process process, int timeoutSeconds, Set<ProcessHandle> observed)
        throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            try (Stream<ProcessHandle> children = process.descendants()) {
                children.forEach(observed::add);
            } catch (UnsupportedOperationException | SecurityException e) {
                log.debug("[LocalClasspath] Could not track Maven descendants", e);
                return process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            final long remaining = deadline - System.nanoTime();
            if (process.waitFor(Math.max(0, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100))),
                TimeUnit.NANOSECONDS)) {
                return true;
            }
            if (remaining <= 0) {
                return false;
            }
        }
    }

    /**
     * Sweeps this process's descendants repeatedly so a child forked during cleanup is found on
     * the next pass. Stop after two empty snapshots or a bounded deadline. Kill the root even
     * if descendant inspection fails. A private process group also reaches helpers that have
     * been reparented. A JVM-wide descendants sweep would kill other components' subprocesses.
     */
    // Package-private so the regression test can exercise the descendants()-throwing path with a
    // stub Process; production callers use the three-argument variant.
    static void destroyProcessTree(Process process) {
        destroyProcessTree(process, false, Set.of());
    }

    /**
     * Runs bounded cleanup on the discovery worker using live descendants and retained handles.
     * Signals the private process group when available and preserves the caller's interrupt status.
     */
    private static void destroyProcessTree(Process process, boolean isolated, Set<ProcessHandle> observed) {
        final Set<ProcessHandle> owned = new LinkedHashSet<>(observed);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean interrupted = Thread.interrupted();
        try {
            try {
                boolean wasEmpty = false;
                do {
                    try (Stream<ProcessHandle> children = process.descendants()) {
                        children.forEach(owned::add);
                    }
                    final List<ProcessHandle> live = owned.stream().filter(ProcessHandle::isAlive).toList();
                    if (live.isEmpty() && wasEmpty) {
                        break;
                    }
                    wasEmpty = live.isEmpty();
                    live.forEach(ProcessHandle::destroyForcibly);
                    // A brief pause also gives Maven a chance to reap the terminated children.
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    interrupted |= Thread.interrupted();
                } while (System.nanoTime() < deadline);
            } catch (RuntimeException e) {
                log.warn("[LocalClasspath] Could not enumerate or destroy Maven descendants: {}", e.toString());
            } finally {
                process.destroyForcibly();
                if (isolated) {
                    killProcessGroup(process.pid());
                }
            }
            final List<CompletableFuture<?>> exits = new ArrayList<>();
            owned.forEach(child -> exits.add(child.onExit()));
            exits.add(process.onExit());
            try {
                CompletableFuture.allOf(exits.toArray(CompletableFuture<?>[]::new))
                    .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                log.debug("[LocalClasspath] Could not confirm the Maven process tree exited", e);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Sends SIGKILL to the private process group {@code setsid} gave the Maven run (Linux only),
     * reaching helpers that were reparented after their parent exited.
     */
    private static void killProcessGroup(long group) {
        try {
            final Process signal = new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + group)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                signal.waitFor(200, TimeUnit.MILLISECONDS);
            } finally {
                if (signal.isAlive()) {
                    signal.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("[LocalClasspath] Could not signal Maven's private process group: {}", e.toString());
        }
    }

    private static String snapshotCaptured(ByteArrayOutputStream captured, Object lock) {
        synchronized (lock) {
            return captured.toString(StandardCharsets.UTF_8);
        }
    }

    private static int snapshotSize(ByteArrayOutputStream captured, Object lock) {
        synchronized (lock) {
            return captured.size();
        }
    }

    /** Seam for the actual process execution; returns the child's exit code (or {@code -1} on timeout). */
    @FunctionalInterface
    interface CommandExecutor {
        int execute(CommandRequest request) throws IOException, InterruptedException;
    }

    /** Bundles the inputs the {@link CommandExecutor} needs so the seam stays a single-arg lambda. */
    record CommandRequest(List<String> command, Path workingDirectory, int timeoutSeconds) {}
}
