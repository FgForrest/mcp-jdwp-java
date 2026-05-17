package one.edee.mcp.jdwp;

import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMStartEvent;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.assertLatestEventType;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies the {@link VMStartEvent} branch in {@link JdiEventListener}'s main loop: when the
 * target JVM emits its start event, the listener must keep the event set suspended (so the user
 * can finish wiring up breakpoints before the application begins executing) and record a
 * {@code VM_START} entry in {@link EventHistory}.
 */
class JdiEventListenerVmStartTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator, new EvaluationGuard(), null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("VMStartEvent keeps event set suspended and records VM_START")
	void shouldRecordVmStartAndKeepSuspendedOnVmStartEvent() throws Exception {
		VMStartEvent event = mock(VMStartEvent.class);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Suspending event: resume() must NOT be called for this set.
		verify(eventSet, never()).resume();
		assertLatestEventType(eventHistory, "VM_START");
	}
}
