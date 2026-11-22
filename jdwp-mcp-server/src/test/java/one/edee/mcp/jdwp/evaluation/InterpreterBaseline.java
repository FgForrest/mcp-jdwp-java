package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.CountingJdwpConnection.Command;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class InterpreterBaseline {
    private static final Command CLASS_INVOKE = new Command(3, 3);
    private static final Command OBJECT_INVOKE = new Command(9, 6);
    private static final Command ARRAY_SET_VALUES = new Command(13, 3);

    static final String HEADER = """
        # Compiled expression baseline

        One sample per target and cache state for `n + 1`. The cold sample starts with an
        unconfigured evaluator on the fixture's first breakpoint. The immediately repeated
        evaluation uses the same connection, frame location, expression and bindings. Local
        Maven classpath discovery is disabled. Attach, stop setup and report writing are excluded.
        The evaluation timing includes a fresh frame read and the production value formatter.

        The public JDI Connection SPI counts completed debugger commands by matching reply packet
        IDs to outgoing commands. It counts error replies too. VM-originated event packets and the
        handshake are excluded. Times use the monotonic clock. ECJ time is included in the total.

        | Target JVM | Cache | Classpath packets | Evaluation packets | Total packets | Classpath ms | ECJ ms | Total ms | Class invokes (evaluation) | Object invokes (evaluation) | Array uploads | Wrapper bytes compiled |
        |---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
        """;

    static final class Compiler extends InMemoryJavaCompiler {
        long nanos;
        int bytes;
        int compilations;

        @Override
        public Map<String, byte[]> compile(String className, String sourceCode) throws JdiEvaluationException {
            final long start = System.nanoTime();
            try {
                final Map<String, byte[]> result = super.compile(className, sourceCode);
                bytes += result.values().stream().mapToInt(bytecode -> bytecode.length).sum();
                return result;
            } finally {
                nanos += System.nanoTime() - start;
                compilations++;
            }
        }
    }

    record Sample(Map<Command, Long> before, Map<Command, Long> configured, Map<Command, Long> after,
                  long classpathNanos, long compileNanos, long totalNanos, int bytes) {
        void verify(boolean cold) {
            final long discovery = sum(configured) - sum(before);
            assertThat(discovery).matches(count -> cold ? count > 0 : count == 0);
            assertThat(delta(CLASS_INVOKE)).isEqualTo(2);
            assertThat(delta(OBJECT_INVOKE)).isEqualTo(cold ? 1 : 0);
            assertThat(delta(ARRAY_SET_VALUES)).isEqualTo(cold ? 1 : 0);
        }

        String row(String version, String cache) {
            return String.format(Locale.ROOT,
                "| %s | %s | %d | %d | %d | %.3f | %.3f | %.3f | %d | %d | %d | %d |%n",
                version, cache, sum(configured) - sum(before), sum(after) - sum(configured),
                sum(after) - sum(before), millis(classpathNanos), millis(compileNanos), millis(totalNanos),
                delta(CLASS_INVOKE), delta(OBJECT_INVOKE), delta(ARRAY_SET_VALUES), bytes);
        }

        private long delta(Command command) {
            return after.getOrDefault(command, 0L) - configured.getOrDefault(command, 0L);
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }

        private static long sum(Map<Command, Long> counts) {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    static void write(Path path, String rows, String vmStart) throws IOException {
        Files.writeString(path, HEADER + rows + "\nThe cold define sends one ArrayReference.SetValues command "
            + "for the entire wrapper. Primitive byte mirrors are created locally. Both samples invoke "
            + "Class.forName and the wrapper. The cold sample also invokes ClassLoader.defineClass.\n\n"
            + "## VM start\n\n" + vmStart);
    }

    private InterpreterBaseline() {
    }
}
