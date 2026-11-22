package one.edee.mcp.jdwp.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterpreterBaselineTest {

    @Test
    void reportsTheMedianAndRangeWithoutDependingOnSampleOrder() {
        final var samples = List.of(sample(30), sample(5), sample(12));

        assertThat(InterpreterBaseline.row("test", "hit", samples))
            .contains("| test | hit | 3 | 78 | 0 |", "12.000 [5.000–30.000]");
    }

    private static InterpreterBaseline.Sample sample(long millis) {
        final var packets = Map.of(new CountingJdwpConnection.Command(3, 3), 78L);
        return new InterpreterBaseline.Sample(Map.of(), Map.of(), packets, 0, 0, millis * 1_000_000, 0);
    }
}
