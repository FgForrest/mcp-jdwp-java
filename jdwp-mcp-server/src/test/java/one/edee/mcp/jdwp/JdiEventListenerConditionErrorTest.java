package one.edee.mcp.jdwp;

import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the listener's conditional-breakpoint path when the condition itself fails to evaluate
 * (e.g. a private-field reference that does not compile). Fail-open behaviour is preserved — the
 * thread stays suspended so the failure cannot be missed — but it must now be LOUD: a
 * {@code CONDITION_ERROR} event is recorded so an agent driving {@code jdwp_resume_until_event}
 * sees that its condition never filtered anything, instead of silently believing it did.
 *
 * @see JdiEventListener#evaluateConditionWithBindings
 */
class JdiEventListenerConditionErrorTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private JdiExpressionEvaluator evaluator;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(
			tracker, eventHistory, evaluator, new EvaluationGuard(), null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("condition that fails to compile records CONDITION_ERROR and still suspends (loud fail-open)")
	void shouldRecordConditionErrorAndSuspendWhenConditionFailsToEvaluate() throws Exception {
		final ThreadReference thread = mockThread("main", 1L);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);

		final BreakpointRequest request = mock(BreakpointRequest.class);
		final int bpId = tracker.registerBreakpoint(request);
		tracker.setCondition(bpId, "allowTransactionalLayerCreation == false");

		// Simulate a private-field reference that does not compile — the evaluator throws.
		when(evaluator.evaluate(any(), eq("allowTransactionalLayerCreation == false"), any()))
			.thenThrow(new JdiEvaluationException("The field allowTransactionalLayerCreation is not visible"));

		final BreakpointEvent event = mockBreakpointEvent(thread, request,
			"io.evitadb.core.transaction.memory.TransactionalLayerMaintainer", 160);
		final EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Fail-open preserved: the event set is NOT auto-resumed — the thread stays suspended.
		verify(eventSet, never()).resume();

		// Loud: a CONDITION_ERROR event surfaces the compile failure and names the breakpoint.
		assertThat(eventHistory.getRecent(10))
			.anySatisfy(e -> {
				assertThat(e.type()).isEqualTo("CONDITION_ERROR");
				assertThat(e.details()).containsEntry("breakpointId", String.valueOf(bpId));
			});
	}
}
