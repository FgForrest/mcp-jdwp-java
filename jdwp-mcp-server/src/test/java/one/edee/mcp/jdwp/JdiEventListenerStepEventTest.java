package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.assertLatestEventType;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockStepEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link JdiEventListener}'s handling of {@link StepEvent}s: the one-shot StepRequest
 * deletion, event recording, latch firing, and suppression under the evaluation guard.
 */
class JdiEventListenerStepEventTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private EvaluationGuard evaluationGuard;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		evaluationGuard = new EvaluationGuard();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator, evaluationGuard, null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("Normal step: STEP recorded, latch fired, StepRequest deleted, snapshot tagged STEP (F-RA2)")
	void shouldRecordStepAndFireLatchAndDeleteRequest() throws Exception {
		ThreadReference thread = mockThread("worker", 100L);
		StepRequest stepRequest = mock(StepRequest.class);
		VirtualMachine requestVm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(stepRequest.virtualMachine()).thenReturn(requestVm);
		when(requestVm.eventRequestManager()).thenReturn(erm);

		StepEvent event = mockStepEvent(thread, stepRequest, "com.example.App", 42);
		EventSet eventSet = mockEventSet(event);

		CountDownLatch latch = tracker.armNextEventLatch();
		runListenerWith(listener, eventSet);

		// StepRequest should be deleted (JDI convention for one-shot steps)
		verify(erm).deleteEventRequest(stepRequest);
		// STEP event recorded
		assertLatestEventType(eventHistory, "STEP");
		// Latch should be fired
		assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
		// EventSet should NOT be resumed (step stays suspended)
		verify(eventSet, never()).resume();
		// F-RA2: snapshot must be tagged STEP (id null) so the "Event fired" renderer doesn't
		// echo a stale breakpoint=N from whatever last suspended this thread.
		final BreakpointTracker.LastBreakpoint snapshot = tracker.getLastBreakpoint();
		assertThat(snapshot).isNotNull();
		assertThat(snapshot.kind()).isEqualTo(BreakpointTracker.EventKind.STEP);
		assertThat(snapshot.id()).isNull();
		assertThat(snapshot.thread()).isSameAs(thread);
	}

	@Test
	@DisplayName("Suppressed step: guard armed, STEP_SUPPRESSED recorded, eventSet resumed")
	void shouldSuppressStepWhenThreadIsInsideEvaluation() throws Exception {
		ThreadReference evalThread = mockThread("eval-thread", 200L);
		StepRequest stepRequest = mock(StepRequest.class);
		VirtualMachine requestVm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(stepRequest.virtualMachine()).thenReturn(requestVm);
		when(requestVm.eventRequestManager()).thenReturn(erm);

		StepEvent event = mockStepEvent(evalThread, stepRequest, "com.example.Internal", 99);
		EventSet eventSet = mockEventSet(event);

		evaluationGuard.enter(evalThread.uniqueID());
		runListenerWith(listener, eventSet);

		// StepRequest is always deleted, even when suppressed
		verify(erm).deleteEventRequest(stepRequest);
		// STEP_SUPPRESSED recorded
		assertLatestEventType(eventHistory, "STEP_SUPPRESSED");
		// EventSet should be auto-resumed since no event demanded suspension
		verify(eventSet).resume();
	}
}
