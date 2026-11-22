package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.ThreadReference;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Checks the visibility hint for both ordinary probe alternatives. */
class FastProbeVisibilityHintTest {
    /** Checks explicit visibility diagnostics and the fallback for an unmatched name. */
    @Test
    void recommendsTheCorrespondingOrdinaryProbeForAVisibilityDiagnostic() {
        final ThreadReference thread = mock(ThreadReference.class);
        final JdiEvaluationException invisible = new JdiEvaluationException("Compilation failed: The field Sample.counter is not visible");
        assertThat(FastLogpointService.describeProbeFailure(FastLogpointService.Kind.BREAKPOINT, invisible, thread))
            .contains("jdwp_set_breakpoint", "private members", "debugger round trip per hit");
        assertThat(FastLogpointService.describeProbeFailure(FastLogpointService.Kind.LOGPOINT, invisible, thread))
            .contains("jdwp_set_logpoint", "private members", "debugger round trip per hit");
        final JdiEvaluationException missing = new JdiEvaluationException("Compilation failed: Sample cannot be resolved");
        assertThat(FastLogpointService.describeProbeFailure(FastLogpointService.Kind.LOGPOINT, missing, thread))
            .isEqualTo(missing.getMessage());
    }
}
