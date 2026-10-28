package one.edee.mcp.jdwp;

import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A method the debugger invokes while processing an event — a logpoint expression, a condition, a
 * fast logpoint's arming — can hit a breakpoint of its own. That event must be drained and resumed
 * while the invocation is still running: the invocation cannot return before its thread is resumed.
 * When the invocation ran on the thread draining the queue, nothing ever took the second event off
 * the queue and the debugger hung. These tests block the invocation until the reentrant event's set
 * has been resumed, so they hang (and fail on the timeout) if the listener stops draining.
 */
@DisplayName("Breakpoints hit inside debugger-invoked code")
class JdiEventListenerReentrantHitTest {

	private static final long THREAD_ID = 21L;

	private BreakpointTracker tracker;
	private EventHistory history;
	private EvaluationGuard guard;
	private JdiExpressionEvaluator evaluator;
	private FastLogpointService fastLogpoints;
	private JdiEventListener listener;
	private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
	/**
	 * Counted down when the blocked invocation has started (the thread is inside it).
	 */
	private final CountDownLatch invoking = new CountDownLatch(1);
	/**
	 * Released by the test once it has checked what happened while the invocation was blocked.
	 */
	private final CountDownLatch release = new CountDownLatch(1);

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		history = new EventHistory();
		guard = new EvaluationGuard();
		evaluator = mock(JdiExpressionEvaluator.class);
		fastLogpoints = mock(FastLogpointService.class);
		listener = new JdiEventListener(tracker, history, evaluator, guard, null, new MarkedInstanceRegistry());
		listener.setFastLogpointService(fastLogpoints);
	}

	@AfterEach
	void tearDown() {
		release.countDown();
		listener.stop();
	}

	@Test
	@DisplayName("a breakpoint hit inside a logpoint's evaluation is suppressed and resumed while it runs")
	void logpointEvaluation() throws Exception {
		final ThreadReference thread = threadWithFrame();
		final BreakpointRequest logpointRequest = mock(BreakpointRequest.class);
		final int logpointId = tracker.registerBreakpoint(logpointRequest);
		tracker.setLogpointExpression(logpointId, "helper()");
		final StringReference result = mock(StringReference.class);
		when(result.value()).thenReturn("done");
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenAnswer(invocation -> {
			blockInsideInvocation();
			return result;
		});
		final EventSet logpointSet = mockEventSet(mockBreakpointEvent(thread, logpointRequest, "com.example.Hot", 10));
		final EventSet reentrantSet = reentrantBreakpointSet(thread);

		start(logpointSet);
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(reentrantSet);

		verify(reentrantSet, timeout(2000)).resume();
		assertThat(types()).contains("BREAKPOINT_SUPPRESSED").doesNotContain("LOGPOINT");
		verify(logpointSet, never()).resume();

		finish();
		verify(logpointSet).resume();
		assertThat(types()).containsSubsequence("BREAKPOINT_SUPPRESSED", "LOGPOINT");
	}

	@Test
	@DisplayName("a breakpoint hit while a fast logpoint arms is suppressed and resumed while arming runs")
	void fastLogpointArming() throws Exception {
		final ThreadReference thread = threadWithFrame();
		final BreakpointEvent arming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 10);
		when(fastLogpoints.claimsArmingEvent(arming)).thenReturn(true);
		doAnswer(invocation -> {
			blockInsideInvocation();
			return null;
		}).when(fastLogpoints).handleArmingHit(arming);
		final EventSet armingSet = mockEventSet(arming);
		final EventSet reentrantSet = reentrantBreakpointSet(thread);

		start(armingSet);
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(reentrantSet);

		verify(reentrantSet, timeout(2000)).resume();
		assertThat(types()).contains("BREAKPOINT_SUPPRESSED");
		verify(armingSet, never()).resume();

		finish();
		verify(armingSet).resume();
	}

	@Test
	@DisplayName("another thread's breakpoint waits for the evaluation in flight and is handled after it")
	void otherThreadsStayInOrder() throws Exception {
		final ThreadReference thread = threadWithFrame();
		final BreakpointRequest logpointRequest = mock(BreakpointRequest.class);
		tracker.setLogpointExpression(tracker.registerBreakpoint(logpointRequest), "helper()");
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenAnswer(invocation -> {
			blockInsideInvocation();
			return null;
		});
		final BreakpointRequest userRequest = mock(BreakpointRequest.class);
		final int userId = tracker.registerBreakpoint(userRequest);
		final EventSet otherSet = mockEventSet(
			mockBreakpointEvent(mockThread("other", 22L), userRequest, "com.example.Hot", 20));

		start(mockEventSet(mockBreakpointEvent(thread, logpointRequest, "com.example.Hot", 10)));
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(otherSet);
		Thread.sleep(100);
		assertThat(types()).doesNotContain("BREAKPOINT");

		finish();
		assertThat(types()).containsSubsequence("LOGPOINT", "BREAKPOINT");
		assertThat(tracker.getLastBreakpointId()).isEqualTo(userId);
		verify(otherSet, never()).resume();
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private ThreadReference threadWithFrame() throws Exception {
		final ThreadReference thread = mockThread("worker", THREAD_ID);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		return thread;
	}

	/**
	 * What a method invocation on the thread looks like to the listener: the thread is marked as
	 * evaluating, and the call does not return until the test releases it.
	 */
	private void blockInsideInvocation() throws InterruptedException {
		guard.enter(THREAD_ID);
		try {
			invoking.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).as("released by the test").isTrue();
		} finally {
			guard.exit(THREAD_ID);
		}
	}

	/**
	 * The set of a plain suspending breakpoint the invoked code hits on the same thread.
	 */
	private EventSet reentrantBreakpointSet(ThreadReference thread) {
		final BreakpointRequest request = mock(BreakpointRequest.class);
		tracker.registerBreakpoint(request);
		return mockEventSet(mockBreakpointEvent(thread, request, "com.example.Loader", 42));
	}

	private void start(EventSet first) throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final EventQueue eventQueue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(eventQueue);
		when(eventQueue.remove()).thenAnswer(invocation -> {
			final Object next = queue.take();
			if (next instanceof EventSet eventSet) {
				return eventSet;
			}
			throw (VMDisconnectedException) next;
		});
		queue.put(first);
		listener.start(vm);
	}

	/**
	 * Lets the blocked invocation return and waits until every set has been processed.
	 */
	private void finish() throws Exception {
		release.countDown();
		assertThat(listener.awaitIdle(2000)).isTrue();
		queue.put(new VMDisconnectedException());
		assertThat(listener.awaitIdle(2000)).isTrue();
	}

	private List<String> types() {
		return history.getRecent(20).stream().map(EventHistory.DebugEvent::type).toList();
	}
}
