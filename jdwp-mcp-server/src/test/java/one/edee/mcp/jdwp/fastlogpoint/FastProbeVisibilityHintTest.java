package one.edee.mcp.jdwp.fastlogpoint;

import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FastProbeVisibilityHintTest {
    @Test
    void recommendsTheCorrespondingOrdinaryProbeForAVisibilityDiagnostic() {
        final var invisible = new JdiEvaluationException("Compilation failed: The field Sample.counter is not visible");
        assertThat(FastLogpointService.describeProbeFailure(FastLogpointService.Kind.BREAKPOINT, invisible))
            .contains("jdwp_set_breakpoint", "private members", "debugger round trip per hit");
        assertThat(FastLogpointService.describeProbeFailure(FastLogpointService.Kind.LOGPOINT, invisible))
            .contains("jdwp_set_logpoint", "private members", "debugger round trip per hit");
        final var missing = new JdiEvaluationException("Compilation failed: Sample cannot be resolved");
        assertThat(FastLogpointService.describeProbeFailure(FastLogpointService.Kind.LOGPOINT, missing))
            .isEqualTo(missing.getMessage());
    }
}
