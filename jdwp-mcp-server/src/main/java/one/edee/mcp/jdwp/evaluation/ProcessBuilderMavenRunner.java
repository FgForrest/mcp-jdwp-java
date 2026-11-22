package one.edee.mcp.jdwp.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
import java.util.regex.Pattern;

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
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), HARVEST_MAX_DEPTH, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Don't skip the root itself even if its name happens to match a SKIP_DIR.
                if (!dir.equals(root)) {
                    final Path name = dir.getFileName();
                    if (name != null
                        && LocalProjectClasspathProvider.HARVEST_SKIP_DIRS.contains(name.toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Symlinks must not be followed: a symlink at target/.jdwp-mcp-classpath could
                // point at any file on the host. attrs.isRegularFile() is false for symlinks
                // because we did NOT pass FOLLOW_LINKS.
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                final Path name = file.getFileName();
                if (name == null || !OUTPUT_FILE_NAME.equals(name.toString())) {
                    return FileVisitResult.CONTINUE;
                }
                final Path parent = file.getParent();
                if (parent == null || parent.getFileName() == null
                    || !"target".equals(parent.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                readOutputFile(file, entries);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Permission denied / transient I/O on a single entry must not abort the whole
                // walk — log at debug and continue, matching the "partial classpath beats no
                // classpath" policy used by the provider's filesystem scan.
                log.debug("[LocalClasspath] Skipping unreadable path {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        return new ArrayList<>(entries);
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
    private static void readOutputFile(Path file, Set<String> entries) {
        try {
            final String content = Files.readString(file).trim();
            if (content.isEmpty()) {
                log.debug("[LocalClasspath] Output file {} was empty", file);
                return;
            }
            int parsed = 0;
            final String separator = Pattern.quote(File.pathSeparator);
            for (String part : content.split(separator, -1)) {
                final String trimmed = part.trim();
                if (!trimmed.isEmpty() && entries.add(trimmed)) {
                    parsed++;
                }
            }
            log.debug("[LocalClasspath] Harvested {} new entries from {}", parsed, file);
        } catch (IOException e) {
            log.warn("[LocalClasspath] Could not read classpath output {}: {}: {}",
                file, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Runs the Maven command via {@link ProcessBuilder} with stderr merged into stdout. A daemon
     * platform thread drains the merged stream into a {@link #STDOUT_CAPTURE_BYTES}-bounded buffer
     * so the child never blocks on a full pipe and the WARN log on failure includes Maven's actual
     * diagnostic output. Returns the process exit code, or {@code -1} if the wall-clock budget was
     * exceeded (after {@link Process#destroyForcibly()}). On {@link InterruptedException}, kills
     * the child, restores the interrupt flag, and rethrows so the caller can propagate cancellation.
     */
    private static int executeRealCommand(CommandRequest req) throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(req.command())
            .directory(req.workingDirectory().toFile())
            .redirectErrorStream(true);
        final Process process = pb.start();
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
        try {
            if (!process.waitFor(req.timeoutSeconds(), TimeUnit.SECONDS)) {
                destroyProcessTree(process);
                // Let any in-flight writes from the child reach the drainer before snapshotting
                // the buffer: otherwise the WARN below would print a truncated tail.
                try {
                    drainer.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                log.warn("[LocalClasspath] Maven invocation timed out after {}s. Captured output (up to {} bytes):\n{}",
                    req.timeoutSeconds(), STDOUT_CAPTURE_BYTES,
                    snapshotCaptured(captured, capturedLock));
                return -1;
            }
        } catch (InterruptedException ie) {
            // waitFor was interrupted. Kill the Maven child and its descendants, since Maven
            // can fork helpers. Restore the interrupt flag and propagate the interruption so the
            // caller can observe the cancellation. A ProcessHandle.current().descendants() sweep
            // could also kill subprocesses owned by other server components, so scope cleanup
            // to the process this invocation started.
            destroyProcessTree(process);
            Thread.currentThread().interrupt();
            throw ie;
        }
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
     * Forcibly destroys the given process and every descendant in its tree, then awaits each one's
     * exit for up to 2s. Bounded to the started process — does NOT touch sibling subprocesses
     * owned by other components, which a {@code ProcessHandle.current().descendants()} sweep would.
     *
     * <p>Descendant enumeration is best-effort: {@link Process#descendants()} can throw under
     * restricted security policies or when handle inspection is unavailable. A failure there must
     * NOT prevent root-process cleanup — that would leak the Maven invocation we started, the very
     * thing this method exists to prevent. The root {@code destroyForcibly()} therefore runs in
     * {@code finally}, after a logged best-effort sweep of descendants.
     */
    // Package-private so the regression test can exercise the descendants()-throwing path
    // with a stub Process; production callers use it through the timeout / interrupt branches above.
    static void destroyProcessTree(Process process) {
        List<ProcessHandle> tree = List.of();
        try {
            tree = process.descendants().toList();
            // Kill children first so the parent can't re-fork during the window between the kill calls.
            tree.forEach(ProcessHandle::destroyForcibly);
        } catch (Exception e) {
            // Restricted env, unavailable handles, anything else — log and fall through to root
            // cleanup. The root destroy below still runs because it is in finally.
            log.warn("[LocalClasspath] Could not enumerate / destroy descendants of Maven process: {}: {}",
                e.getClass().getSimpleName(), e.getMessage());
        } finally {
            // ALWAYS destroy the root — even if descendant enumeration above blew up. Skipping this
            // would leak the Maven subprocess we own, which is exactly the failure mode the caller
            // (timeout or interrupt path) is trying to prevent.
            process.destroyForcibly();
        }
        // Confirm the whole tree actually exited, but bound the TOTAL wait — not 2s PER handle.
        // A Maven reactor can fork several surefire/exec helpers; awaiting them serially would be
        // O(N) × 2s and could blow past the caller's cleanup budget. Combine every exit future
        // (descendants + root) and wait once, so worst-case stays ~2s regardless of tree width.
        final CompletableFuture<?>[] exits = new CompletableFuture<?>[tree.size() + 1];
        for (int i = 0; i < tree.size(); i++) {
            exits[i] = tree.get(i).onExit();
        }
        exits[tree.size()] = process.onExit();
        try {
            CompletableFuture.allOf(exits).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort — we already issued destroyForcibly on every process; nothing more to do.
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
