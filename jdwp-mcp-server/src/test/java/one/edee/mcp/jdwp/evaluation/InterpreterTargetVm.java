package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.fixture.InterpreterTarget;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A running fixture JVM with its loopback debugger endpoint.
 *
 * @param process target JVM process owned by this scope
 * @param port JDWP port announced by the target
 */
record InterpreterTargetVm(Process process, int port) implements AutoCloseable {
    static final String CLASS = InterpreterTarget.class.getName();

    static List<Path> javaHomes() {
        return Arrays.stream(System.getProperty("jdwp.live.target.java.home", System.getProperty("java.home"))
                .split(",", -1))
            .map(String::trim)
            .map(Path::of)
            .toList();
    }

    /** Starts the target with optional directories for its context classloader fixture. */
    static InterpreterTargetVm start(Path javaHome, String... loaderDirectories) throws Exception {
        return startClass(javaHome, InterpreterTarget.class, loaderDirectories);
    }

    /** Starts a fixture with the same classpath and JDWP transport as the interpreter target. */
    static InterpreterTargetVm startClass(Path javaHome, Class<?> mainClass, String... arguments) throws Exception {
        return launch(javaHome, mainClass, List.of(), List.of(arguments));
    }

    /** Starts the interpreter target with JVM options before its classpath. */
    static InterpreterTargetVm startWithOptions(Path javaHome, String... options) throws Exception {
        return launch(javaHome, InterpreterTarget.class, List.of(options), List.of());
    }

    private static InterpreterTargetVm launch(Path javaHome, Class<?> mainClass, List<String> options,
                                               List<String> arguments) throws Exception {
        final String classpath = Path.of(mainClass.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toString();
        final List<String> launchArguments = new ArrayList<>(options);
        launchArguments.addAll(List.of("-cp", classpath, mainClass.getName()));
        launchArguments.addAll(arguments);
        return startIn(javaHome, null, launchArguments);
    }

    /** Starts a fixture with the supplied Java launch arguments and optional working directory. */
    static InterpreterTargetVm startIn(Path javaHome, @Nullable Path directory, List<String> launchArguments)
        throws Exception {
        final List<String> command = new ArrayList<>(List.of(
            javaHome.resolve("bin/java").toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0"));
        command.addAll(launchArguments);
        final Process process = new ProcessBuilder(command).directory(directory == null ? null : directory.toFile())
            .redirectErrorStream(true).start();

        final CompletableFuture<Integer> port = new CompletableFuture<>();
        // Keep draining output after startup so a full pipe cannot block the fixture.
        final Thread drain = new Thread(() -> {
            try (final BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8))) {
                final String prefix = "Listening for transport dt_socket at address: ";
                final StringBuilder startup = new StringBuilder();
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.startsWith(prefix)) {
                        port.complete(Integer.parseInt(line.substring(prefix.length())));
                    } else if (!port.isDone()) {
                        startup.append(line).append('\n');
                    }
                }
                port.completeExceptionally(new IOException("Fixture exited before JDWP listened: " + startup));
            } catch (IOException | NumberFormatException e) {
                port.completeExceptionally(e);
            }
        }, "interpreter-fixture-output");
        drain.setDaemon(true);
        drain.start();
        try {
            return new InterpreterTargetVm(process, port.get(15, TimeUnit.SECONDS));
        } catch (Exception e) {
            terminate(process);
            throw e;
        }
    }

    @Override
    public void close() throws InterruptedException {
        terminate(process);
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
