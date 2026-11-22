package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.CountingJdwpConnection.Command;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

final class InterpreterBaseline {
    private static final Command CLASS_INVOKE = new Command(3, 3);
    private static final Command OBJECT_INVOKE = new Command(9, 6);
    private static final Command ARRAY_SET_VALUES = new Command(13, 3);

    /** Explains how the packet and timing measurements are collected. */
    static final String HEADER = """
        # Expression evaluation baseline

        Host JVM: %s %s

        For each target, five fresh connections each contribute one cold evaluation and
        five immediately repeated evaluations per engine. The compiled and navigation probes use
        `n + 1`. The invocation probe uses `store.map.size()`. One untimed connection per engine and
        target warms the host before sampling. The cold sample starts at the first
        fixture breakpoint with the compiler unconfigured. Local Maven classpath discovery is disabled. Measurement includes
        a fresh frame read and production value formatting. Attach, stop setup, semantic
        observation and report writing are outside the timed window.

        The public JDI Connection SPI counts completed debugger commands by matching reply packet
        IDs to outgoing commands. It counts error replies too. VM-originated event packets and the
        handshake are excluded. Times use the monotonic clock and are median [min–max] in ms.
        Packet counts show the range when samples vary. ECJ time is included in the total.

        | Target JVM | Cache | Samples | Total packets | Classpath packets | Evaluation packets | Classpath ms | ECJ ms | Total ms | Class invokes (evaluation) | Object invokes (evaluation) | Array uploads | Wrapper bytes compiled |
        |---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
        """;

    static final class Compiler extends InMemoryJavaCompiler {
        long nanos;
        int bytes;
        int compilations;

        /** Counts every compilation: the public overload and the evaluator's captured-options path both end here. */
        @Override
        Map<String, byte[]> compile(String className, String sourceCode, @Nullable Configuration config)
            throws JdiEvaluationException {
            final long start = System.nanoTime();
            try {
                final Map<String, byte[]> result = super.compile(className, sourceCode, config);
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
            final long discovery = discoveryPackets();
            assertThat(discovery).matches(count -> cold ? count > 0 : count == 0);
            assertThat(delta(CLASS_INVOKE)).isEqualTo(2);
            assertThat(delta(OBJECT_INVOKE)).isEqualTo(cold ? 1 : 0);
            assertThat(delta(ARRAY_SET_VALUES)).isEqualTo(cold ? 1 : 0);
        }

        void verifyNavigation() {
            assertThat(discoveryPackets()).isZero();
            assertThat(delta(CLASS_INVOKE)).isZero();
            assertThat(delta(OBJECT_INVOKE)).isZero();
            assertThat(delta(ARRAY_SET_VALUES)).isZero();
        }

        /** Confirms the probe made one instance call without classpath discovery or bytecode upload. */
        void verifySingleInvocation() {
            assertThat(discoveryPackets()).isZero();
            assertThat(delta(CLASS_INVOKE)).isZero();
            assertThat(delta(OBJECT_INVOKE)).isEqualTo(1);
            assertThat(delta(ARRAY_SET_VALUES)).isZero();
        }

        private long discoveryPackets() {
            return sum(configured) - sum(before);
        }

        private long evaluationPackets() {
            return sum(after) - sum(configured);
        }

        private long totalPackets() {
            return discoveryPackets() + evaluationPackets();
        }

        private long delta(Command command) {
            return after.getOrDefault(command, 0L) - configured.getOrDefault(command, 0L);
        }

        private static long sum(Map<Command, Long> counts) {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    static String row(String version, String cache, List<Sample> samples) {
        return String.format(Locale.ROOT, "| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%n",
            version, cache, samples.size(), countRange(samples, Sample::totalPackets),
            countRange(samples, Sample::discoveryPackets), countRange(samples, Sample::evaluationPackets),
            timing(samples, Sample::classpathNanos), timing(samples, Sample::compileNanos),
            timing(samples, Sample::totalNanos), countRange(samples, sample -> sample.delta(CLASS_INVOKE)),
            countRange(samples, sample -> sample.delta(OBJECT_INVOKE)),
            countRange(samples, sample -> sample.delta(ARRAY_SET_VALUES)), countRange(samples, Sample::bytes));
    }

    private static String countRange(List<Sample> samples, ToLongFunction<Sample> value) {
        final var stats = samples.stream().mapToLong(value).summaryStatistics();
        final long min = stats.getMin();
        final long max = stats.getMax();
        return min == max ? Long.toString(min) : min + "–" + max;
    }

    private static String timing(List<Sample> samples, ToLongFunction<Sample> value) {
        final long[] sorted = samples.stream().mapToLong(value).sorted().toArray();
        final int middle = sorted.length / 2;
        final double median = (sorted[(sorted.length - 1) / 2] + (double) sorted[middle]) / 2;
        return String.format(Locale.ROOT, "%.3f [%.3f–%.3f]", median / 1_000_000,
            sorted[0] / 1_000_000.0, sorted[sorted.length - 1] / 1_000_000.0);
    }

    static void write(Path path, String rows, String vmStart) throws IOException {
        Files.writeString(path, HEADER.formatted(System.getProperty("java.vm.name"), Runtime.version())
            + rows + "\nThe cold define sends one ArrayReference.SetValues command "
            + "for the entire wrapper. Primitive byte mirrors are created locally. Both samples invoke "
            + "Class.forName and the wrapper. The cold sample also invokes ClassLoader.defineClass.\n\n"
            + "## VM start\n\n" + vmStart);
    }

    private InterpreterBaseline() {
    }
}
