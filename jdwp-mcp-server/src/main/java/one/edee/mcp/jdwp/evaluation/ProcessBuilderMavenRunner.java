package one.edee.mcp.jdwp.evaluation;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Production {@link LocalProjectClasspathProvider.MavenRunner} that shells out to {@code mvn} (or
 * the project's Maven wrapper) via {@link ProcessBuilder} and harvests the per-module
 * classpath files Maven writes to a private temporary directory for each run.
 *
 * <p>This class owns invocation, timeout enforcement, stdout draining, and the temporary output
 * directory. Maven resolves its own reactor module coordinates, so output collection is independent
 * of module paths and does not scan or remove files from the project tree. Callers receive
 * read failures alongside any successfully collected classpath entries.
 *
 * <p>Thread model: each {@link #run} invocation spawns ONE daemon platform thread named
 * {@code jdwp-mcp-maven-stdout-drainer} to drain the child process's merged stdout/stderr stream.
 * The drainer terminates when the last writer closes the pipe. Without process-group isolation,
 * an undetected helper can keep it open after the runner returns. Platform threads are used
 * because the project targets Java 17.
 */
@Service
public class ProcessBuilderMavenRunner implements LocalProjectClasspathProvider.MavenRunner {

    /** Uses full Maven coordinates because a reactor can build multiple versions of an artifact. */
    private static final String MODULE_OUTPUT = "${project.groupId}@${project.artifactId}@${project.version}.cp";
    /** Passes the private path outside argv so platform launchers cannot reinterpret it. */
    private static final String OUTPUT_DIRECTORY_ENV = "JDWP_MCP_CLASSPATH_DIR";
    /** Upper bound on captured child stdout/stderr included in the WARN log on failure. */
    private static final int STDOUT_CAPTURE_BYTES = 64 * 1024;
    /**
     * Runs Maven with its argv intact and reports its exit code on a separate status channel.
     * The session leader reads the server's pipe even while Maven runs and kills its own group on EOF.
     */
    private static final String GROUP_SUPERVISOR = """
        status=$1
        shift
        ( "$@" </dev/null; printf '%s\\n' "$?" > "$status" ) &
        IFS= read -r cleanup || :
        kill -KILL 0
        """;
    private static final Logger log = LoggerFactory.getLogger(ProcessBuilderMavenRunner.class);

    /** Seam for the actual process execution; replaced in tests to drive timeout / failure paths. */
    private final CommandExecutor commandExecutor;
    /** Whether this runner can launch a private process group and signal it from its leader. */
    private final boolean isolated;

    /** Default Spring wiring — uses the real {@link ProcessBuilder}-based command executor. */
    public ProcessBuilderMavenRunner() {
        this(false);
    }

    /** Test seam to exercise real descendant polling on hosts that also have setsid. */
    ProcessBuilderMavenRunner(boolean forcePolling) {
        isolated = !forcePolling && Files.isExecutable(Path.of("/usr/bin/setsid"))
            && Files.isExecutable(Path.of("/bin/sh"));
        commandExecutor = request -> executeRealCommand(request, isolated);
    }

    /** Test seam: a stub owns its own cleanup, so it never advertises real descendant polling. */
    ProcessBuilderMavenRunner(CommandExecutor commandExecutor) {
        this.isolated = true;
        this.commandExecutor = commandExecutor;
    }

    /** {@inheritDoc} */
    @Override
    public boolean usesDescendantPolling() {
        return !isolated;
    }

    /** {@inheritDoc} */
    @Override
    public List<String> run(List<String> command, Path workingDirectory, int timeoutSeconds)
        throws IOException, InterruptedException {
        checkDiscoveryInterrupted();
        final Path outputDirectory = Files.createTempDirectory("jdwp-mcp-maven-classpath-").toAbsolutePath();
        try {
            final List<String> argv = new ArrayList<>(command);
            // Maven expands the environment reference and reactor coordinates for each module.
            // Keep the absolute path out of argv, especially when launching mvnw.cmd on Windows.
            argv.add("-Dmdep.outputFile=${env." + OUTPUT_DIRECTORY_ENV + "}/" + MODULE_OUTPUT);
            final int exitCode = commandExecutor.execute(new CommandRequest(List.copyOf(argv), workingDirectory,
                timeoutSeconds, outputDirectory));
            switch (exitCode) {
                case -1 -> throw new IOException("Maven timed out after " + timeoutSeconds + " seconds");
                case 126, 127 -> throw new IOException("Maven executable could not be started: " + command.get(0)
                    + " (exit code " + exitCode + "). Check for a missing interpreter, CRLF line endings,"
                    + " or a missing inner command.");
                case 0 -> { return harvestOutputFiles(outputDirectory); }
                default -> throw new IOException("Maven exited with code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            removeOutputDirectory(outputDirectory);
        }
    }

    /**
     * Reads regular files recursively without following links, including paths nested by a version.
     * Returns the union of entries, reports read failures with partial results, and treats an
     * interrupted read as cancellation. A successful Maven run must produce at least one file.
     */
    private static List<String> harvestOutputFiles(Path directory) throws IOException, InterruptedException {
        checkDiscoveryInterrupted();
        final Set<String> entries = new LinkedHashSet<>();
        final List<IOException> failures = new ArrayList<>();
        boolean foundOutput = false;
        try (Stream<Path> paths = Files.walk(directory)) {
            final Iterator<Path> files = paths.iterator();
            while (files.hasNext()) {
                checkDiscoveryInterrupted();
                final Path file = files.next();
                try {
                    if (Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .isRegularFile()) {
                        foundOutput = true;
                        if (directory.relativize(file).toString().contains("${")) {
                            throw new IOException("Maven did not expand the classpath output path");
                        }
                        readOutputFile(file, entries);
                    }
                } catch (IOException e) {
                    checkHarvestInterrupted(e);
                    failures.add(new IOException("Could not read classpath output " + file + ": " + e, e));
                } catch (InvalidPathException e) {
                    failures.add(new IOException("Could not read classpath output " + file + ": " + e, e));
                }
            }
        } catch (UncheckedIOException e) {
            final IOException cause = Objects.requireNonNull(e.getCause());
            checkHarvestInterrupted(cause);
            failures.add(cause);
        } catch (IOException e) {
            checkHarvestInterrupted(e);
            failures.add(e);
        }
        checkDiscoveryInterrupted();
        if (!failures.isEmpty()) {
            throw new OutputReadException(List.copyOf(entries), failures);
        }
        if (!foundOutput) {
            throw new IOException("Maven succeeded but wrote no classpath output. "
                + "Check whether the POM configures the dependency plugin's outputFile.");
        }
        return List.copyOf(entries);
    }

    /** Stops a cancelled discovery before process launch or at an output-read boundary. */
    private static void checkDiscoveryInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Maven classpath discovery was interrupted");
        }
    }

    /** Recognizes cancellation reported as a filesystem read failure, even if it cleared the flag. */
    private static void checkHarvestInterrupted(IOException cause) throws InterruptedException {
        if (cause instanceof InterruptedIOException || cause instanceof ClosedByInterruptException
            || Thread.currentThread().isInterrupted()) {
            final InterruptedException cancelled = new InterruptedException("Maven classpath discovery was interrupted");
            cancelled.initCause(cause);
            throw cancelled;
        }
    }

    /** Deletes only this run's private output directory without following links, even after cancellation. */
    private static void removeOutputDirectory(Path directory) {
        final boolean interrupted = Thread.interrupted();
        try {
            FileSystemUtils.deleteRecursively(directory);
        } catch (IOException | RuntimeException e) {
            log.warn("[LocalClasspath] Could not remove Maven classpath output directory {}", directory, e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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
     * Reads a module's private classpath file without following links, splits on the host
     * {@link File#pathSeparator}, and appends non-blank, non-duplicate entries to {@code entries}.
     * Maven wrote this file on the host so its entries are separated by the host's separator —
     * splitting on {@code [;:]} would shred {@code C:\foo} on Windows.
     */
    private static void readOutputFile(Path file, Set<String> entries) throws IOException {
        final String content;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(input.readAllBytes())).toString();
        }
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
     * exceeded (after cleanup). On {@link InterruptedException}, kills
     * the owned process tree, restores the interrupt flag, and rethrows so the caller can
     * propagate cancellation. Cleanup also covers interruptions while joining the stdout drainer.
     */
    private static int executeRealCommand(CommandRequest req, boolean isolated)
        throws IOException, InterruptedException {
        final Path status = isolated ? Files.createTempFile("jdwp-mcp-maven-", ".status") : null;
        try {
            final List<String> command = new ArrayList<>();
            if (status != null) {
                command.addAll(List.of("/usr/bin/setsid", "--", "/bin/sh", "-c", GROUP_SUPERVISOR,
                    "jdwp-mcp-maven", status.toAbsolutePath().toString()));
            }
            command.addAll(req.command());
            return collectProcessOutput(req, command, status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (status != null) {
                try {
                    Files.deleteIfExists(status);
                } catch (IOException e) {
                    log.warn("[LocalClasspath] Could not remove Maven status file {}", status, e);
                }
            }
        }
    }

    /**
     * Exports the private output directory and drains output while waiting on the discovery worker.
     * Cleans up before joining the drainer, since a reparented helper can hold the pipe open.
     * Returns the Maven exit code, or {@code -1} on timeout, and propagates interruption or a
     * status-channel failure.
     */
    private static int collectProcessOutput(CommandRequest req, List<String> command, @Nullable Path status)
        throws IOException, InterruptedException {
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
        final Set<ProcessHandle> observed = new LinkedHashSet<>();
        final ProcessBuilder builder = new ProcessBuilder(command).directory(req.workingDirectory().toFile())
            .redirectErrorStream(true);
        builder.environment().put(OUTPUT_DIRECTORY_ENV, req.outputDirectory().toString());
        final Process process = builder.start();
        final Thread drainer;
        int exit = -1;
        IOException statusFailure = null;
        try {
            drainer = startOutputDrainer(process, captured, capturedLock);
            if (status != null) {
                try {
                    exit = awaitMavenStatus(process, status, req.timeoutSeconds());
                } catch (IOException e) {
                    statusFailure = e;
                }
            } else {
                exit = waitForAndObserveChildren(process, req.timeoutSeconds(), observed) ? process.exitValue() : -1;
            }
        } finally {
            // Clean up only this run's processes, including on interrupt. The group leader
            // remains alive to do the signalling itself even if Maven has already exited.
            if (status != null) {
                releaseGroupSupervisor(process);
            } else if (process.isAlive() || observed.stream().anyMatch(ProcessHandle::isAlive)) {
                destroyProcessTree(process, observed);
            }
        }
        drainer.join(1000);
        if (drainer.isAlive()) {
            // The drainer outlived its budget — a very large Maven output stream or a stuck child
            // still holding the pipe open. We have already collected the exit code, so this is not
            // a correctness issue, but a one-line DEBUG helps anyone investigating slow shutdowns.
            log.debug("[LocalClasspath] stdout drainer still alive after 1s join — leaving as daemon");
        }
        if (statusFailure != null) {
            log.warn("[LocalClasspath] {}. Captured output (up to {} bytes):\n{}",
                statusFailure.getMessage(), STDOUT_CAPTURE_BYTES, snapshotCaptured(captured, capturedLock));
            throw statusFailure;
        }
        if (exit == -1) {
            log.warn("[LocalClasspath] Maven invocation timed out after {}s. Captured output (up to {} bytes):\n{}",
                req.timeoutSeconds(), STDOUT_CAPTURE_BYTES, snapshotCaptured(captured, capturedLock));
        } else if (exit != 0) {
            log.warn("[LocalClasspath] Maven exited with code {}. Captured output (up to {} bytes):\n{}",
                exit, STDOUT_CAPTURE_BYTES,
                snapshotCaptured(captured, capturedLock));
        } else {
            log.debug("[LocalClasspath] Maven succeeded ({} bytes of output captured)",
                snapshotSize(captured, capturedLock));
        }
        return exit;
    }

    /** Starts a daemon that drains the merged pipe and keeps at most the configured output cap. */
    private static Thread startOutputDrainer(Process process, ByteArrayOutputStream captured, Object capturedLock) {
        final Thread drainer = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                final byte[] buf = new byte[4096];
                int n;
                // EOF is the only loop exit. Keep draining after a zero-length read or after
                // reaching the capture cap so Maven never blocks on a full pipe.
                while ((n = in.read(buf)) != -1) {
                    synchronized (capturedLock) {
                        if (captured.size() < STDOUT_CAPTURE_BYTES) {
                            captured.write(buf, 0, Math.min(n, STDOUT_CAPTURE_BYTES - captured.size()));
                        }
                    }
                }
            } catch (IOException ignored) {}
        }, "jdwp-mcp-maven-stdout-drainer");
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    /**
     * Waits up to the Maven budget for a complete status line from the supervisor's child.
     * Returns the Maven exit code or -1 on timeout. If the supervisor exits, checks for a last
     * status write before reporting failure.
     */
    private static int awaitMavenStatus(Process supervisor, Path status, int timeoutSeconds)
        throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            final Integer code = readMavenStatus(status);
            if (code != null) {
                return code;
            }
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return -1;
            }
            if (supervisor.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) {
                final Integer lastCode = readMavenStatus(status);
                if (lastCode != null) {
                    return lastCode;
                }
                throw new IOException("Maven process group supervisor exited before reporting status (code "
                    + supervisor.exitValue() + ')');
            }
        }
    }

    /** Reads a complete Maven status line, or returns null while the status write is unfinished. */
    private static @Nullable Integer readMavenStatus(Path status) throws IOException {
        final String code = Files.readString(status);
        if (!code.endsWith("\n")) {
            return null;
        }
        final String value = code.trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Maven exit status: " + value, e);
        }
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
     * if descendant inspection fails. A JVM-wide descendants sweep would kill other components'
     * subprocesses.
     */
    // Package-private so the regression test can exercise the descendants()-throwing path with a
    // stub Process; production callers use the variant that also passes their retained handles.
    static void destroyProcessTree(Process process) {
        destroyProcessTree(process, Set.of());
    }

    /**
     * Runs bounded cleanup on the discovery worker using live descendants and retained handles.
     * Used on hosts without a private process group. Preserves the caller's interrupt status.
     */
    private static void destroyProcessTree(Process process, Set<ProcessHandle> observed) {
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
     * Closes the control pipe so the owned session leader signals its own group. Cleanup never
     * sends a signal to a numeric group ID, which could have been recycled after a leader exits.
     * Waits briefly for the signal even on cancellation, preserving the discovery worker's interrupt.
     */
    private static void releaseGroupSupervisor(Process supervisor) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean interrupted = Thread.interrupted();
        try {
            supervisor.getOutputStream().close();
            while (supervisor.isAlive()) {
                try {
                    if (!supervisor.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        log.warn("[LocalClasspath] Maven process group supervisor did not exit after closing its pipe");
                    }
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (IOException e) {
            log.warn("[LocalClasspath] Could not close Maven's process group control pipe", e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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

    /** Carries the argv, process directory, timeout and private output directory to the executor. */
    record CommandRequest(List<String> command, Path workingDirectory, int timeoutSeconds, Path outputDirectory) {}
}
