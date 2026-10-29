package one.edee.mcp.jdwp;

import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An event set waits for the dispatcher while it handles the sets before it, and a tool can resume
 * the target meanwhile. {@link EventSet#resume()} only decrements suspend counts, so a set processed
 * late must not resume a suspension a tool has already spent: it would release a newer stop. It must
 * still resume a suspension that is owed, or its thread stays parked with no stop reported. The
 * dispatcher is held busy by an evaluation the test blocks; the tool resumes go through the
 * {@link ResumeLedger}, as {@code jdwp_resume} and {@code jdwp_resume_thread} do.
 */
@DisplayName("Event sets processed after a tool resumed the target")
class JdiEventListenerLateResumeTest {

	private static final long BUSY_ID = 21L;

	private BreakpointTracker tracker;
	private EventHistory history;
	private EvaluationGuard guard;
	private JdiExpressionEvaluator evaluator;
	private ResumeLedger resumes;
	private JdiEventListener listener;
	private VirtualMachine vm;
	private ThreadReference busy;
	private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
	/**
	 * Counted down when the blocked evaluation has started (its thread is inside it).
	 */
	private final CountDownLatch invoking = new CountDownLatch(1);
	/**
	 * Released by the test once it has done what has to happen while the evaluation is blocked.
	 */
	private final CountDownLatch release = new CountDownLatch(1);

	@BeforeEach
	void setUp() throws Exception {
		tracker = new BreakpointTracker();
		history = new EventHistory();
		guard = new EvaluationGuard();
		evaluator = mock(JdiExpressionEvaluator.class);
		resumes = new ResumeLedger();
		listener = new JdiEventListener(tracker, history, evaluator, guard, null,
			new MarkedInstanceRegistry(), resumes);
		busy = threadWithFrame("busy", BUSY_ID);
		final StackFrame busyFrame = busy.frame(0);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenAnswer(invocation -> {
			if (invocation.getArgument(0) == busyFrame) {
				blockInsideInvocation();
			}
			return null;
		});
	}

	@AfterEach
	void tearDown() {
		release.countDown();
		listener.stop();
	}

	@Test
	@DisplayName("a set suspending nothing, raised inside an evaluation that has ended, does not hold the evaluated set's resume")
	void setSuspendingNothingIsNoStop() throws Exception {
		final CountDownLatch guardClosed = new CountDownLatch(1);
		final CountDownLatch finishEvaluation = new CountDownLatch(1);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenAnswer(invocation -> {
			blockInsideInvocation();
			guardClosed.countDown();
			assertThat(finishEvaluation.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		});
		final EventSet logpointSet = logpointSet(busy, "com.example.Hot", 10);
		// The evaluated code crossed a breakpoint with suspend policy "none": it did not stop the
		// invocation, which has ended by the time the listener routes the set.
		final EventSet passedSet = breakpointSet(busy, "com.example.Helper", 30);
		when(passedSet.suspendPolicy()).thenReturn(EventRequest.SUSPEND_NONE);

		start(logpointSet);
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		release.countDown();
		assertThat(guardClosed.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(passedSet);
		verify(passedSet, timeout(2000)).suspendPolicy();
		finishEvaluation.countDown();
		finish();

		verify(logpointSet).resume();
		assertThat(types()).containsSubsequence("LOGPOINT", "BREAKPOINT");
	}

	@Test
	@DisplayName("a set whose thread a tool resumed while the set waited is not resumed")
	void threadResumedWhileWaiting() throws Exception {
		final ThreadReference thread = threadWithFrame("worker", 22L);
		final EventSet logpointSet = logpointSet(thread, "com.example.Hot", 10);

		start(logpointSet(busy, "com.example.Busy", 5));
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(logpointSet);
		awaitRouted(logpointSet);
		// jdwp_resume_thread: the thread runs on; it has not stopped again, so no later set exists.
		resumes.resume(thread);
		finish();

		verify(thread, times(1)).resume();
		verify(logpointSet, never()).resume();
	}

	@Test
	@DisplayName("a stop-all set spent by a VM resume does not release another thread's newer stop")
	void stopAllSetSpentByVmResume() throws Exception {
		final ThreadReference thread = threadWithFrame("worker", 22L);
		// A cleared breakpoint: forgotten, and its request deleted, while its set waited.
		final BreakpointRequest cleared = mock(BreakpointRequest.class);
		final int clearedId = tracker.registerBreakpoint(cleared);
		final EventSet clearedSet = mockEventSet(mockBreakpointEvent(thread, cleared, "com.example.Hot", 10));
		when(clearedSet.suspendPolicy()).thenReturn(EventRequest.SUSPEND_ALL);
		final EventSet otherStop = breakpointSet(mockThread("other", 23L), "com.example.Hot", 20);
		when(otherStop.suspendPolicy()).thenReturn(EventRequest.SUSPEND_ALL);

		start(logpointSet(busy, "com.example.Busy", 5));
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(clearedSet);
		awaitRouted(clearedSet);
		tracker.removeBreakpoint(clearedId);
		when(cleared.isEnabled()).thenReturn(false);
		// jdwp_resume: every thread runs on, and another thread stops at a breakpoint.
		resumes.resume(vm);
		queue.put(otherStop);
		finish();

		verify(vm, times(1)).resume();
		verify(clearedSet, never()).resume();
		verify(thread, never()).resume();
		verify(otherStop, never()).resume();
		assertThat(types()).contains("BREAKPOINT");
	}

	@Test
	@DisplayName("a stop-all set whose own thread a tool resumed while the set waited is not resumed")
	void stopAllSetSpentByResumeOfItsThread() throws Exception {
		final ThreadReference thread = threadWithFrame("worker", 22L);
		final EventSet logpointSet = logpointSet(thread, "com.example.Hot", 10);
		when(logpointSet.suspendPolicy()).thenReturn(EventRequest.SUSPEND_ALL);

		start(logpointSet(busy, "com.example.Busy", 5));
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		queue.put(logpointSet);
		awaitRouted(logpointSet);
		resumes.resume(thread);
		finish();

		verify(logpointSet, never()).resume();
	}

	@Test
	@DisplayName("a VM resume during the set's own evaluation does not reach its thread: the set is still resumed")
	void vmResumeDuringEvaluation() throws Exception {
		final EventSet logpointSet = logpointSet(busy, "com.example.Hot", 10);

		start(logpointSet);
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		// The invocation resumed the thread for the call and suspends it again when it returns.
		resumes.resume(vm);
		finish();

		verify(logpointSet).resume();
		verify(busy, never()).resume();
	}

	@Test
	@DisplayName("after a VM resume during its evaluation, a stop-all set resumes only its own thread")
	void stopAllSetAfterVmResumeDuringEvaluation() throws Exception {
		final EventSet logpointSet = logpointSet(busy, "com.example.Hot", 10);
		when(logpointSet.suspendPolicy()).thenReturn(EventRequest.SUSPEND_ALL);

		start(logpointSet);
		assertThat(invoking.await(2, TimeUnit.SECONDS)).isTrue();
		// The VM resume released every other thread; resuming the set would release them again.
		resumes.resume(vm);
		finish();

		verify(busy).resume();
		verify(logpointSet, never()).resume();
		verify(vm, times(1)).resume();
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private static ThreadReference threadWithFrame(String name, long id) throws Exception {
		final ThreadReference thread = mockThread(name, id);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		return thread;
	}

	/**
	 * The set of a logpoint hit by {@code thread}.
	 */
	private EventSet logpointSet(ThreadReference thread, String className, int line) {
		final BreakpointRequest request = mock(BreakpointRequest.class);
		tracker.setLogpointExpression(tracker.registerBreakpoint(request), "i");
		return mockEventSet(mockBreakpointEvent(thread, request, className, line));
	}

	/**
	 * The set of a plain breakpoint hit by {@code thread}.
	 */
	private EventSet breakpointSet(ThreadReference thread, String className, int line) {
		final BreakpointRequest request = mock(BreakpointRequest.class);
		tracker.registerBreakpoint(request);
		return mockEventSet(mockBreakpointEvent(thread, request, className, line));
	}

	/**
	 * What a method invocation on the busy thread looks like to the listener: the thread is marked as
	 * evaluating, and the call does not return until the test releases it.
	 */
	private void blockInsideInvocation() throws InterruptedException {
		guard.enter(BUSY_ID);
		try {
			invoking.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).as("released by the test").isTrue();
		} finally {
			guard.exit(BUSY_ID);
		}
	}

	/**
	 * Waits until the listener has routed {@code eventSet}: it reads the set's suspend policy then.
	 */
	private static void awaitRouted(EventSet eventSet) {
		verify(eventSet, timeout(2000).atLeastOnce()).suspendPolicy();
	}

	private void start(EventSet first) throws Exception {
		vm = mock(VirtualMachine.class);
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
	 * Lets the blocked evaluation return and waits until every set has been processed.
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
