package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.request.BreakpointRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.assertLatestEventType;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Layer B of the recursive-breakpoint suppression test plan: verify that the real
 * {@link JdiEventListener} loop honours {@link EvaluationGuard} for every suspending event
 * type, without regressing the normal suspend path.
 *
 * <p>Setup mirrors {@code JdiEventListenerDisconnectBugTest}: we mock {@link VirtualMachine}
 * and feed synthetic {@link EventSet}s through a real {@link EventQueue} implementation, then
 * let the listener consume them on its daemon thread. Each test pushes one event set followed
 * by a disconnect sentinel so the listener exits cleanly at the end.
 *
 * <p>Each scenario asserts the observable side effects: whether {@link EventSet#resume()} was
 * called (auto-resume path), whether the tracker's {@code lastBreakpointThread} was set,
 * whether the next-event latch was released, and which {@link EventHistory} type was recorded.
 */
class JdiEventListenerEvaluationSuppressionTest {

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
		listener = new JdiEventListener(tracker, eventHistory, evaluator, evaluationGuard);
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	void shouldSuppressBreakpointEventWhenThreadIsInsideEvaluation() throws Exception {
		ThreadReference evalThread = mockThread("eval-thread", 111L);
		BreakpointRequest request = mock(BreakpointRequest.class);
		int bpId = tracker.registerBreakpoint(request);

		BreakpointEvent event = mockBreakpointEvent(request, evalThread,
			"io.mcp.jdwp.sandbox.recursion.RecursiveCalculator", 42);
		EventSet eventSet = mockEventSet(event);

		evaluationGuard.enter(evalThread.uniqueID());
		runListenerWith(listener, eventSet);

		// Suppressed: listener must call eventSet.resume() and NOT touch tracker state.
		// (Note on the latch: we deliberately don't assert on the next-event latch here because
		// the disconnect sentinel used to terminate the listener ALWAYS calls fireNextEvent() on
		// exit — that firing is not distinguishable from a suppression-path firing, so the latch
		// state carries no signal for this test. The observable suppression contract is: no
		// eventSet suspension, no tracker-state mutation, and a recorded BREAKPOINT_SUPPRESSED
		// entry in EventHistory.)
		verify(eventSet).resume();
		assertThat(tracker.getLastBreakpointThread()).isNull();
		assertLatestEventType(eventHistory, "BREAKPOINT_SUPPRESSED");

		// Guard state unaffected by the suppression — cleanup is the caller's responsibility.
		assertThat(evaluationGuard.isEvaluating(evalThread)).isTrue();
		// Breakpoint registration preserved so the subsequent "real" hit still resolves its ID.
		assertThat(tracker.getBreakpoint(bpId)).isSameAs(request);
	}

	@Test
	void shouldSuspendBreakpointEventOnANormalThread() throws Exception {
		// Regression guard: without the guard armed, the happy path must suspend normally,
		// populate lastBreakpointThread, and release the next-event latch.
		ThreadReference normalThread = mockThread("worker-1", 222L);
		BreakpointRequest request = mock(BreakpointRequest.class);
		int bpId = tracker.registerBreakpoint(request);

		BreakpointEvent event = mockBreakpointEvent(request, normalThread,
			"io.mcp.jdwp.sandbox.order.OrderProcessor", 30);
		EventSet eventSet = mockEventSet(event);
		CountDownLatch latch = tracker.armNextEventLatch();

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		assertThat(tracker.getLastBreakpointThread()).isSameAs(normalThread);
		assertThat(tracker.getLastBreakpointId()).isEqualTo(bpId);
		assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
		assertLatestEventType(eventHistory, "BREAKPOINT");
	}

	@Test
	void shouldSuppressExceptionEventWhenThreadIsInsideEvaluation() throws Exception {
		ThreadReference evalThread = mockThread("eval-thread", 333L);
		ExceptionEvent event = mockExceptionEvent(evalThread, "java.lang.IllegalStateException");
		EventSet eventSet = mockEventSet(event);

		evaluationGuard.enter(evalThread.uniqueID());
		runListenerWith(listener, eventSet);

		// Suppressed: resume() called, tracker untouched, EXCEPTION_SUPPRESSED recorded.
		// See the note in shouldSuppressBreakpointEventWhenThreadIsInsideEvaluation for why the
		// next-event latch is not asserted on the suppression path.
		verify(eventSet).resume();
		assertThat(tracker.getLastBreakpointThread()).isNull();
		assertThat(tracker.getLastBreakpointId()).isNull();
		assertLatestEventType(eventHistory, "EXCEPTION_SUPPRESSED");
	}

	@Test
	void shouldNotSuppressBreakpointOnDifferentThread() throws Exception {
		// Suppression is per-thread. An evaluation on thread A must not hide a BP on thread B.
		ThreadReference evalThread = mockThread("eval-thread", 444L);
		ThreadReference otherThread = mockThread("worker-2", 555L);
		BreakpointRequest request = mock(BreakpointRequest.class);
		tracker.registerBreakpoint(request);

		BreakpointEvent event = mockBreakpointEvent(request, otherThread,
			"io.mcp.jdwp.sandbox.session.SessionStore", 24);
		EventSet eventSet = mockEventSet(event);
		CountDownLatch latch = tracker.armNextEventLatch();

		evaluationGuard.enter(evalThread.uniqueID()); // guard is for evalThread, NOT otherThread
		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		assertThat(tracker.getLastBreakpointThread()).isSameAs(otherThread);
		assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
		assertLatestEventType(eventHistory, "BREAKPOINT");
	}

	// ── Test-specific event factories ───────────────────────────────────────

	/** Builds a {@link BreakpointEvent} mock wired to the given request, thread, and location. */
	private static BreakpointEvent mockBreakpointEvent(BreakpointRequest request,
			ThreadReference thread, String className, int line) {
		BreakpointEvent event = mock(BreakpointEvent.class);
		when(event.request()).thenReturn(request);
		when(event.thread()).thenReturn(thread);
		Location location = mock(Location.class);
		ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn(className);
		when(location.declaringType()).thenReturn(declaringType);
		when(location.lineNumber()).thenReturn(line);
		when(event.location()).thenReturn(location);
		return event;
	}

	/** Builds an {@link ExceptionEvent} mock whose exception reports the given fully-qualified type. */
	private static ExceptionEvent mockExceptionEvent(ThreadReference thread, String exceptionType) {
		ExceptionEvent event = mock(ExceptionEvent.class);
		when(event.thread()).thenReturn(thread);
		ObjectReference exception = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn(exceptionType);
		when(exception.referenceType()).thenReturn(refType);
		when(event.exception()).thenReturn(exception);
		// throwLocation/catchLocation are only touched on the non-suppressed path, so leave null.
		return event;
	}
}
