package one.edee.mcp.jdwp;

import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
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
import java.util.concurrent.atomic.AtomicInteger;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
	 * How many times the listener has asked for its next event set; it asks again only once it has
	 * routed the previous one.
	 */
	private final AtomicInteger removals = new AtomicInteger();
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
			return FastLogpointService.ArmingOutcome.RESUME;
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

	@Test
	@DisplayName("a set raised inside an evaluation is only suppressed, even if the evaluation ended meanwhile")
	void reentrantSetNeverEvaluates() throws Exception {
		final ThreadReference thread = threadWithFrame();
		final BreakpointRequest conditional = mock(BreakpointRequest.class);
		tracker.setCondition(tracker.registerBreakpoint(conditional), "helper() > 0");
		// Inside the evaluation when the listener routes the set, out of it when the set is handled —
		// possible for a breakpoint with suspend policy "none", which does not hold the invocation up.
		final EvaluationGuard closing = spy(new EvaluationGuard());
		doReturn(true, false).when(closing).isEvaluating(thread);
		listener.stop();
		listener = new JdiEventListener(tracker, history, evaluator, closing, null, new MarkedInstanceRegistry());
		final EventSet eventSet = mockEventSet(mockBreakpointEvent(thread, conditional, "com.example.Hot", 10));

		start(eventSet);

		// Suppressed on the listener thread, not on the dispatcher that finish() waits for.
		verify(eventSet, timeout(2000)).resume();
		finish();
		verify(evaluator, never()).evaluate(any(StackFrame.class), anyString(), any());
		assertThat(types()).contains("BREAKPOINT_SUPPRESSED").doesNotContain("BREAKPOINT", "CONDITION_ERROR");
	}

	@Test
	@DisplayName("a set suspending nothing, raised inside an evaluation that ended before it was routed, does not hold the evaluated set's resume")
	void setSuspendingNothingIsNoStop() throws Exception {
		final ThreadReference thread = threadWithFrame();
		final BreakpointRequest logpointRequest = mock(BreakpointRequest.class);
		tracker.setLogpointExpression(tracker.registerBreakpoint(logpointRequest), "helper()");
		final CountDownLatch guardClosed = new CountDownLatch(1);
		final CountDownLatch finishEvaluation = new CountDownLatch(1);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenAnswer(invocation -> {
			blockInsideInvocation();
			guardClosed.countDown();
			assertThat(finishEvaluation.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		});
		final EventSet logpointSet = mockEventSet(mockBreakpointEvent(thread, logpointRequest, "com.example.Hot", 10));
		// The invoked code crossed a breakpoint with suspend policy "none": it did not hold the
		// invocation up, which has ended by the time the listener routes the set.
		final EventSet passedSet = reentrantBreakpointSet(thread);
		when(passedSet.suspendPolicy()).thenReturn(EventRequest.SUSPEND_NONE);

		start(logpointSet);
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		release.countDown();
		assertThat(guardClosed.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(passedSet);
		// The third removal starts once the listener has routed passedSet to the dispatcher.
		awaitRemovals(3);
		finishEvaluation.countDown();
		finish();

		verify(logpointSet).resume();
		assertThat(types()).containsSubsequence("LOGPOINT", "BREAKPOINT");
	}

	@Test
	@DisplayName("a set whose thread was resumed and stopped again while it waited is not resumed")
	void overtakenSetIsNotResumed() throws Exception {
		// The dispatcher is busy with one thread's evaluation while another thread's sets queue up.
		final ThreadReference busy = threadWithFrame();
		final BreakpointRequest busyRequest = mock(BreakpointRequest.class);
		tracker.setLogpointExpression(tracker.registerBreakpoint(busyRequest), "helper()");
		final ThreadReference thread = mockThread("other", 22L);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		final BreakpointRequest logpointRequest = mock(BreakpointRequest.class);
		tracker.setLogpointExpression(tracker.registerBreakpoint(logpointRequest), "i");
		final BreakpointRequest userRequest = mock(BreakpointRequest.class);
		final int userId = tracker.registerBreakpoint(userRequest);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenAnswer(invocation -> {
			if (invocation.getArgument(0) != frame) {
				blockInsideInvocation();
			}
			return null;
		});
		final EventSet busySet = mockEventSet(mockBreakpointEvent(busy, busyRequest, "com.example.Busy", 5));
		// The other thread hits a logpoint; while that set waits it is resumed (jdwp_resume) and stops
		// at a breakpoint. The logpoint set's suspension is spent: resuming it would release the stop.
		final EventSet logpointSet = mockEventSet(mockBreakpointEvent(thread, logpointRequest, "com.example.Hot", 10));
		final EventSet breakpointSet = mockEventSet(mockBreakpointEvent(thread, userRequest, "com.example.Hot", 20));

		start(busySet);
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(logpointSet);
		queue.put(breakpointSet);
		Thread.sleep(100);
		finish();

		verify(busySet).resume();
		verify(logpointSet, never()).resume();
		verify(breakpointSet, never()).resume();
		assertThat(types()).containsSubsequence("LOGPOINT", "LOGPOINT", "BREAKPOINT");
		assertThat(tracker.getLastBreakpointId()).isEqualTo(userId);
	}

	@Test
	@DisplayName("at VM death, sets queued behind a stuck evaluation are dropped instead of processed after VM_DEATH")
	void deathDropsQueuedSets() throws Exception {
		final ThreadReference busy = threadWithFrame();
		final BreakpointRequest busyRequest = mock(BreakpointRequest.class);
		tracker.setLogpointExpression(tracker.registerBreakpoint(busyRequest), "helper()");
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenAnswer(invocation -> {
			blockInsideInvocation();
			return null;
		});
		final BreakpointRequest userRequest = mock(BreakpointRequest.class);
		tracker.registerBreakpoint(userRequest);
		final EventSet queuedSet = mockEventSet(
			mockBreakpointEvent(mockThread("other", 22L), userRequest, "com.example.Hot", 20));

		start(mockEventSet(mockBreakpointEvent(busy, busyRequest, "com.example.Busy", 5)));
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(queuedSet);
		queue.put(mockEventSet(mock(VMDisconnectEvent.class)));

		// The listener gives the stuck evaluation DISPATCH_DRAIN_TIMEOUT_MS, then records the death.
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!types().contains("VM_DEATH") && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(types()).contains("VM_DEATH");
		assertThat(listener.awaitIdle(5000)).as("the stuck evaluation is interrupted").isTrue();
		assertThat(types()).doesNotContain("BREAKPOINT");
		verify(queuedSet, never()).resume();
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
			removals.incrementAndGet();
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
	 * Waits until the listener has asked for its {@code count}-th event set.
	 */
	private void awaitRemovals(int count) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (removals.get() < count && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		assertThat(removals.get()).as("listener asked for event set #%d", count).isGreaterThanOrEqualTo(count);
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
