package one.edee.mcp.jdwp;

import com.sun.jdi.InternalException;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.FastBreakpointProbeSource;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JDWPTools#jdwp_step_over}, {@link JDWPTools#jdwp_step_into} and
 * {@link JDWPTools#jdwp_step_out} (and their shared {@code doStep} helper): step-depth wiring,
 * explicit-thread vs last-breakpoint-thread fallback, the not-suspended and unknown-thread
 * guards, the synchronised delete-then-create of a pre-existing StepRequest, the
 * {@code resume_until_event} hint in the return message, and the create &rarr; enable &rarr;
 * resume ordering.
 */
@DisplayName("jdwp_step_over / _into / _out")
class JDWPToolsStepTest {

	private static final String JDK_STEP_TIMEOUT = "The step may be passing JDK code, which runs slowly while the step is pending "
		+ "or may be blocking. The thread may also have returned from the last application method to its JDK caller. "
		+ "A breakpoint in the method of interest is quicker.";

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private JDWPTools tools;
	private VirtualMachine vm;
	private EventRequestManager erm;
	private EventHistory eventHistory;

	/** Wires the step tools and suspension session to the same mock VM. */
	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = spy(new BreakpointTracker());
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		erm = mock(EventRequestManager.class);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(erm.stepRequests()).thenReturn(List.of());
		session = breakpointTracker.openSuspensionSession(vm, eventHistory);
	}

	private SuspensionSession session;
	/**
	 * The event set of each thread's held stop (see {@link #suspendedThread}).
	 */
	private final Map<ThreadReference, EventSet> stops = new HashMap<>();

	/**
	 * Holds a stop of {@code thread} that a tool has shown, as a breakpoint hit followed by
	 * {@code jdwp_resume_until_event} leaves it: a step releases its thread through that stop.
	 */
	private void holdStop(ThreadReference thread, int breakpointId) throws Exception {
		holdStop(thread, breakpointId, EventRequest.SUSPEND_EVENT_THREAD, true);
	}

	private void holdStop(ThreadReference thread, int breakpointId, int policy, boolean present) throws Exception {
		holdStop(new BreakpointTracker.LastBreakpoint(thread, breakpointId), policy, present);
	}

	/** Publishes a synthetic stop without a locatable JDI event. */
	private void holdStop(BreakpointTracker.LastBreakpoint stop, int policy, boolean present) throws Exception {
		final EventSet set = mock(EventSet.class);
		when(set.suspendPolicy()).thenReturn(policy);
		when(set.iterator()).thenAnswer(invocation -> Collections.emptyIterator());
		holdStop(stop, set, present);
	}

	/** Publishes the supplied event set and retains it for assertions about release. */
	private void holdStop(BreakpointTracker.LastBreakpoint stop, EventSet set, boolean present) throws Exception {
		final SuspensionSession.Context context = session.route(set, stop.thread(), false);
		session.begin(context);
		session.candidate(context, stop);
		session.finish(context);
		if (present) {
			session.nextStop(0);
		}
		stops.put(stop.thread(), set);
	}

	private ThreadReference suspendedThread(long id) {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(id);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.status()).thenReturn(ThreadReference.THREAD_STATUS_RUNNING);
		when(thread.name()).thenReturn("thread-" + id);
		try {
			holdStop(thread, (int) id);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return thread;
	}

	private StepRequest stubStepRequestCreation(ThreadReference thread, int stepDepth) {
		final StepRequest req = mock(StepRequest.class);
		when(erm.createStepRequest(thread, StepRequest.STEP_LINE, stepDepth)).thenReturn(req);
		return req;
	}

	@Test
	@DisplayName("Step reports the remaining holders, including unseen stops and manual holds")
	void shouldReportRemainingHolders() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		final ThreadReference seen = suspendedThread(2L);
		final ThreadReference unseen = mock(ThreadReference.class);
		when(unseen.uniqueID()).thenReturn(3L);
		when(unseen.name()).thenReturn("unseen-worker");
		holdStop(unseen, 3, EventRequest.SUSPEND_EVENT_THREAD, false);
		final ThreadReference manual = mock(ThreadReference.class);
		when(manual.uniqueID()).thenReturn(4L);
		when(manual.name()).thenReturn("manual-worker");
		session.suspendThread(manual);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping, seen, unseen, manual));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		assertThat(tools.jdwp_step_over(1L)).contains("released", "presented thread stops: thread-2",
			"unseen thread stops: unseen-worker", "jdwp_suspend_thread holds: manual-worker",
			"All remaining threads run.");
	}

	/** A failed release reports all attempts and returns immediately, including for a waiting step. */
	@ParameterizedTest
	@CsvSource({"false,true", "true,true", "true,false"})
	void shouldReportStepReleaseProgressAndDisconnect(boolean wait, boolean disconnect) throws Exception {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread stepping = model.thread("stepping");
		when(model.vm.eventRequestManager()).thenReturn(erm);
		when(jdiService.getVM()).thenReturn(model.vm);
		assertThat(session.suspendThread(stepping.ref)).isNull();
		final EventSet first = model.breakpointEvent(stepping, EventRequest.SUSPEND_EVENT_THREAD);
		publish(stepping, first, 11);
		doThrow(new InternalException("first resume rejected")).when(first).resume();
		final EventSet second = model.breakpointEvent(stepping, EventRequest.SUSPEND_EVENT_THREAD);
		publish(stepping, second, 12);
		if (disconnect) {
			doThrow(new VMDisconnectedException("transport closed")).when(second).resume();
		}
		final StepRequest request = stubStepRequestCreation(stepping.ref, StepRequest.STEP_OVER);
		when(request.virtualMachine()).thenReturn(model.vm);

		final String reply = tools.jdwp_step_over(stepping.ref.uniqueID(), wait);
		assertThat(reply).startsWith(disconnect ? "[VM_DEATH]" : "Error: cannot step")
			.contains("jdwp_suspend_thread hold", "first resume rejected")
			.doesNotContain("Step over started", "[TIMEOUT]");
		if (disconnect) {
			assertThat(reply).contains("VMDisconnectedException", "jdwp_wait_for_attach");
		} else {
			assertThat(reply).contains("Released: its jdwp_suspend_thread hold; breakpoint 12 stop",
				"affected holds remain in the session");
		}
		assertThat(session.isClosed()).isEqualTo(disconnect);
		assertThat(session.stepPending(stepping.ref)).isFalse();
		assertThat(stepping.count).isEqualTo(disconnect ? 2 : 1);
		verify(erm).deleteEventRequest(request);
	}

	/** Recovery holds have distinct labels and use the existing shared thread-name budget. */
	@Test
	void shouldGroupAutomaticReleaseFailuresAndLeaseDebts() throws Exception {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread stepping = model.thread("recover-stepping");
		when(model.vm.eventRequestManager()).thenReturn(erm);
		when(jdiService.getVM()).thenReturn(model.vm);
		for (int i = 0; i < 5; i++) {
			final FakeSuspendModel.FakeThread debtor = model.thread("recover-debtor-" + i);
			publish(debtor, model.breakpointEvent(debtor, EventRequest.SUSPEND_EVENT_THREAD), 10 + i);
			session.lease(debtor.ref);
			model.beginInvocation(debtor);
			assertThat(session.releaseAll().released()).hasSize(1);
			model.completeInvocation(debtor);
			doThrow(new InternalException("lease repayment rejected")).when(debtor.ref).resume();
			session.unlease(debtor.ref);
		}
		final FakeSuspendModel.FakeThread worker = model.thread("recover-worker");
		final EventSet failed = model.breakpointEvent(worker, EventRequest.SUSPEND_EVENT_THREAD);
		doThrow(new InternalException("automatic release rejected")).when(failed).resume();
		final SuspensionSession.Context context = session.route(failed, worker.ref, false, false);
		session.begin(context);
		session.finish(context);
		assertThat(session.suspendThread(stepping.ref)).isNull();
		stubStepRequestCreation(stepping.ref, StepRequest.STEP_OVER);

		final String reply = tools.jdwp_step_over(stepping.ref.uniqueID());

		assertThat(reply).contains("Step over started", "failed automatic release", "recover-worker", "lease debt",
			"recover-debtor-0", "and 2 more").doesNotContain("still being processed", "All remaining threads run");
		assertThat(java.util.regex.Pattern.compile("recover-[\\w-]+").matcher(reply).results().count())
			.isLessThanOrEqualTo(5);
		assertThat(stepping.count).isZero();
		assertThat(worker.count).isEqualTo(1);
	}

	/** Publishes and presents a modeled breakpoint suspension. */
	private void publish(FakeSuspendModel.FakeThread thread, EventSet set, int id) throws InterruptedException {
		final SuspensionSession.Context context = session.route(set, thread.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(thread.ref, id));
		session.finish(context);
		assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
	}

	/** Reserves space for the stepping thread and stop-all origin in the shared name cap. */
	@Test
	@DisplayName("Step from stop-all shows the remaining threads with a capped list")
	void shouldCapStopAllThreadNames() throws Exception {
		final List<ThreadReference> threads = new java.util.ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			final ThreadReference thread = mock(ThreadReference.class);
			when(thread.uniqueID()).thenReturn((long) i);
			when(thread.name()).thenReturn("worker-" + i);
			threads.add(thread);
		}
		final ThreadReference stepping = threads.get(0);
		when(stepping.isSuspended()).thenReturn(true);
		holdStop(stepping, 1, EventRequest.SUSPEND_ALL, true);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(threads);
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		assertThat(tools.jdwp_step_over(1L)).contains("presented stop-all from thread worker-1",
			"still covers other threads (JDI lists worker-2", "and 4 more")
			.doesNotContain("worker-5", "worker-6", "worker-7", "worker-8");
	}

	@Test
	@DisplayName("A stop-all is reported even if JDI only lists the stepping thread")
	void shouldDescribeStopAllScopeWithNoOtherEnumeratedThreads() throws Exception {
		final ThreadReference stepping = mock(ThreadReference.class);
		when(stepping.uniqueID()).thenReturn(1L);
		when(stepping.name()).thenReturn("main");
		when(stepping.isSuspended()).thenReturn(true);
		holdStop(stepping, 1, EventRequest.SUSPEND_ALL, true);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		assertThat(tools.jdwp_step_over(1L))
			.contains("presented stop-all from thread main", "still covers other threads")
			.doesNotContain("All other threads run.", "All remaining threads run.");
	}

	/** Exercises several presented stop-alls still held after repeatedly releasing only their origin. */
	@Test
	void shouldCountRepeatedStopAllsFromTheSameThread() throws Exception {
		final ThreadReference stepping = mock(ThreadReference.class);
		when(stepping.uniqueID()).thenReturn(1L);
		when(stepping.name()).thenReturn("main");
		when(stepping.isSuspended()).thenReturn(true);
		final ThreadReference bystander = mock(ThreadReference.class);
		when(bystander.uniqueID()).thenReturn(2L);
		when(bystander.name()).thenReturn("bystander");
		for (int hit = 1; hit <= 8; hit++) {
			holdStop(stepping, hit, EventRequest.SUSPEND_ALL, true);
			if (hit < 8) {
				assertThat(session.releaseThread(stepping).refusal()).isNull();
			}
		}
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping, bystander));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		assertThat(tools.jdwp_step_over(1L))
			.contains("8 presented stop-alls from thread main")
			.containsOnlyOnce("JDI lists bystander");
	}

	/** Shares one thread-name budget across stop-all scopes and manual-hold groups. */
	@Test
	void shouldCapThreadNamesAcrossDifferentHolders() throws Exception {
		final List<ThreadReference> threads = new java.util.ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			final ThreadReference thread = mock(ThreadReference.class);
			when(thread.uniqueID()).thenReturn((long) i);
			when(thread.name()).thenReturn("sample-" + i);
			threads.add(thread);
		}
		final ThreadReference stepping = threads.get(0);
		when(stepping.isSuspended()).thenReturn(true);
		assertThat(session.suspendThread(threads.get(7))).isNull();
		holdStop(stepping, 1, EventRequest.SUSPEND_ALL, true);
		holdStop(threads.get(1), 2, EventRequest.SUSPEND_ALL, true);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(threads);
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		final String reply = tools.jdwp_step_over(1L);
		assertThat(reply).contains("jdwp_suspend_thread holds", "stop-all")
			.doesNotContain("All remaining threads run.");
		assertThat(java.util.regex.Pattern.compile("sample-\\d+").matcher(reply).results().count())
			.isLessThanOrEqualTo(5);
	}

	/** The arming summary has a five-name budget and the appended outcome names its own thread. */
	@Test
	void shouldCapArmingSummaryNamesWhenWaitingForGroupedStopAll() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		assertThat(session.releaseThread(stepping).released()).isTrue();
		final List<ThreadReference> threads = new java.util.ArrayList<>();
		threads.add(stepping);
		for (int id = 2; id <= 6; id++) {
			final ThreadReference other = mock(ThreadReference.class);
			when(other.uniqueID()).thenReturn((long) id);
			when(other.name()).thenReturn("worker-" + id);
			threads.add(other);
		}
		holdStop(stepping, 19, EventRequest.SUSPEND_ALL, true);
		assertThat(session.releaseThread(stepping).released()).isTrue();
		holdStop(stepping, 19, EventRequest.SUSPEND_ALL, true);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(threads);
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);
		session = spy(session);
		doReturn(session).when(breakpointTracker).suspensionSession();
		doAnswer(invocation -> {
			holdStop(stepping, 30, EventRequest.SUSPEND_EVENT_THREAD, false);
			return invocation.callRealMethod();
		}).when(session).awaitStepStop(anyLong(), any());

		final String reply = tools.jdwp_step_over(1L, true);
		final String[] sections = reply.split("\nEvent fired", 2);
		assertThat(sections).hasSize(2);
		assertThat(sections[0]).contains("2 presented stop-alls from thread thread-1",
			"JDI lists worker-2, worker-3, worker-4 and 2 more");
		assertThat(java.util.regex.Pattern.compile("\\b(?:thread-1|worker-\\d+)\\b")
			.matcher(sections[0]).results().count()).isEqualTo(5);
		assertThat(sections[1]).contains("Thread: thread-1 (ID=1", "breakpoint=30");
	}

	@Test
	@DisplayName("wait=true reports the step landing and leaves older stops unseen")
	void shouldWaitForStepLanding() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		final ThreadReference other = mock(ThreadReference.class);
		when(other.name()).thenReturn("other");
		holdStop(other, 2, EventRequest.SUSPEND_EVENT_THREAD, false);
		final CountDownLatch released = signalOnRelease(stepping);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping, other));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		final CompletableFuture<String> reply = CompletableFuture.supplyAsync(() -> tools.jdwp_step_over(1L, true));
		assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(reply).isNotDone();
		holdStop(new BreakpointTracker.LastBreakpoint(stepping, null, BreakpointTracker.EventKind.STEP),
			EventRequest.SUSPEND_EVENT_THREAD, false);

		assertThat(reply.get(5, TimeUnit.SECONDS)).startsWith("Step over started")
			.contains("unseen thread stops: other", "\nEvent fired", "via=step", "1 unseen stop");
		assertThat(session.unseenCount()).isEqualTo(1);
		verify(stops.get(other), never()).resume();
	}

	@Test
	@DisplayName("wait=true reports a breakpoint that interrupts the step on its thread")
	void shouldWaitForInterruptingStop() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		final CountDownLatch released = signalOnRelease(stepping);
		when(jdiService.getVM()).thenReturn(vm);
		final StepRequest request = stubStepRequestCreation(stepping, StepRequest.STEP_INTO);
		when(request.virtualMachine()).thenReturn(vm);

		final CompletableFuture<String> reply = CompletableFuture.supplyAsync(() -> tools.jdwp_step_into(null, true));
		assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(reply).isNotDone();
		holdStop(stepping, 3, EventRequest.SUSPEND_EVENT_THREAD, false);

		assertThat(reply.get(5, TimeUnit.SECONDS)).startsWith("Step into started")
			.contains("\nEvent fired", "breakpoint=3");
		assertThat(session.stepPending(stepping)).isFalse();
		verify(erm).deleteEventRequest(request);
		assertThat(eventHistory.getRecent(10)).extracting(EventHistory.DebugEvent::type).contains("STEP_INTERRUPTED");
	}

	@Test
	@DisplayName("a competing presenter cannot make wait=true return a later stop")
	void shouldReportTheStepOutcomeAlreadyPresentedByAnotherCall() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		session = spy(session);
		doReturn(session).when(breakpointTracker).suspensionSession();
		doAnswer(invocation -> {
			final Object armed = invocation.callRealMethod();
			holdStop(stepping, 30, EventRequest.SUSPEND_EVENT_THREAD, false);
			assertThat(tools.jdwp_resume_until_event(0)).contains("breakpoint=30");
			assertThat(session.releaseThread(stepping).released()).isTrue();
			holdStop(stepping, 31, EventRequest.SUSPEND_EVENT_THREAD, false);
			return armed;
		}).when(session).step(eq(stepping), any(), any());

		assertThat(tools.jdwp_step_over(1L, true)).startsWith("Step over started")
			.contains("step ended at", "breakpoint=30", "already presented by another call")
			.doesNotContain("breakpoint=31", "Event fired", "[TIMEOUT]");
		assertThat(session.currentStop()).isNull();
		assertThat(session.unseenCount()).isEqualTo(1);
		assertThat(tools.jdwp_resume_until_event(0)).contains("breakpoint=31");
	}

	/** Forces T to stop again during enrichment of the step's already selected outcome. */
	@Test
	void shouldKeepCapturedStepLocationWhenThreadStopsAgainDuringFrameRead() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);
		session = spy(session);
		doReturn(session).when(breakpointTracker).suspensionSession();
		final StackFrame laterFrame = mock(StackFrame.class);
		final Location laterLocation = location(31);
		when(laterFrame.location()).thenReturn(laterLocation);
		doAnswer(invocation -> {
			final EventSet ending = mock(EventSet.class);
			final BreakpointEvent event = mock(BreakpointEvent.class);
			final Location endingLocation = location(30);
			when(event.thread()).thenReturn(stepping);
			when(event.location()).thenReturn(endingLocation);
			when(ending.suspendPolicy()).thenReturn(EventRequest.SUSPEND_EVENT_THREAD);
			when(ending.iterator()).thenAnswer(ignored -> List.<Event>of(event).iterator());
			holdStop(new BreakpointTracker.LastBreakpoint(stepping, 30), ending, false);
			when(stepping.frameCount()).thenAnswer(read -> {
				assertThat(session.releaseThread(stepping).released()).isTrue();
				holdStop(stepping, 31, EventRequest.SUSPEND_EVENT_THREAD, false);
				doReturn(1).when(stepping).frameCount();
				return 1;
			});
			when(stepping.frame(0)).thenReturn(laterFrame);
			return invocation.callRealMethod();
		}).when(session).awaitStepStop(anyLong(), any());

		assertThat(tools.jdwp_step_over(1L, true)).contains("Event fired at example.Work:30", "breakpoint=30")
			.doesNotContain("example.Work:31", "frames=1");
		assertThat(session.unseenCount()).isOne();
	}

	/** A source location used to distinguish the selected stop from the later stack. */
	private static Location location(int line) {
		final Location location = mock(Location.class);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn("example.Work");
		when(location.declaringType()).thenReturn(type);
		when(location.lineNumber()).thenReturn(line);
		return location;
	}

	/** Gives the suspended thread stack frames in physical order, including any leading probe. */
	private static void stack(ThreadReference thread, String... classNames) throws Exception {
		final List<StackFrame> frames = new ArrayList<>();
		for (final String className : classNames) {
			final ReferenceType type = mock(ReferenceType.class);
			when(type.name()).thenReturn(className);
			final Method method = mock(Method.class);
			when(method.name()).thenReturn(FastBreakpointProbeSource.STOP_METHOD);
			final Location location = mock(Location.class);
			when(location.declaringType()).thenReturn(type);
			when(location.method()).thenReturn(method);
			final StackFrame frame = mock(StackFrame.class);
			when(frame.location()).thenReturn(location);
			frames.add(frame);
		}
		when(thread.frameCount()).thenReturn(frames.size());
		when(thread.frames(0, frames.size())).thenReturn(frames);
		for (int i = 0; i < frames.size(); i++) {
			when(thread.frame(i)).thenReturn(frames.get(i));
		}
	}

	/** Ensures an armed step keeps its continuation hint when the previous diagnostic thread is stale. */
	@Test
	void shouldKeepArmedStepTimeoutWhenFormerStopThreadIsCollected() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		final ThreadReference former = suspendedThread(2L);
		assertThat(session.releaseThread(former).released()).isTrue();
		when(former.name()).thenThrow(new ObjectCollectedException("former worker already collected"));
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);
		session = spy(session);
		doReturn(session).when(breakpointTracker).suspensionSession();
		doAnswer(invocation -> session.awaitStepStop(0, invocation.getArgument(1)))
			.when(session).awaitStepStop(eq(30_000L), any());

		assertThat(tools.jdwp_step_over(1L, true)).startsWith("Step over started")
			.contains("[TIMEOUT]", "jdwp_resume_until_event(threadId=1)").doesNotContain(JDK_STEP_TIMEOUT);
		assertThat(session.stepPending(stepping)).isTrue();
	}

	/** A failed thread sample must leave the stop-all summary and the waiting step's result intact. */
	@Test
	void shouldKeepStopAllSummaryWhenThreadSamplingFails() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		assertThat(session.releaseThread(stepping).released()).isTrue();
		holdStop(stepping, 19, EventRequest.SUSPEND_ALL, true);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenThrow(new ObjectCollectedException("thread sample unavailable"));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);
		session = spy(session);
		doReturn(session).when(breakpointTracker).suspensionSession();
		doAnswer(invocation -> {
			holdStop(stepping, 30, EventRequest.SUSPEND_EVENT_THREAD, false);
			return invocation.callRealMethod();
		}).when(session).awaitStepStop(anyLong(), any());

		assertThat(tools.jdwp_step_over(null, true)).startsWith("Step over started")
			.contains("presented stop-all from thread thread-1 still covers other threads", "Event fired", "breakpoint=30")
			.doesNotContain("All other threads run.", "All remaining threads run.");
		verify(stepping).resume();
	}

	/** Drops JDK exclusions only at a return with no application caller below it. */
	@ParameterizedTest
	@CsvSource({"172, java.lang.Thread, false, false", "177, java.lang.Thread, false, false",
		"176, example.Work, false, true", "184, java.lang.Thread, false, true",
		"177, java.lang.Thread, true, true"})
	void shouldFilterStepIntoBasedOnReturnAndCaller(int opcode, String caller, boolean unreadable,
													boolean filtered) throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		stack(thread, "example.Work", caller, "jdk.internal.reflect.DirectMethodHandleAccessor");
		final Method method = thread.frame(0).location().method();
		when(method.bytecodes()).thenReturn(new byte[] {0, (byte) opcode});
		when(thread.frame(0).location().codeIndex()).thenReturn(1L);
		if (unreadable) {
			when(thread.frame(1)).thenThrow(new InvalidStackFrameException());
		}
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		final StepRequest request = stubStepRequestCreation(thread, StepRequest.STEP_INTO);

		assertThat(tools.jdwp_step_into(1L)).startsWith("Step into started");

		verify(request, times(filtered ? 5 : 0)).addClassExclusionFilter(any());
		verify(thread, times(opcode == 184 ? 0 : 1)).frame(1);
		verify(thread, times(filtered ? 0 : 1)).frame(2);
	}

	/** Checks the timeout advice while a presented stop-all still holds other threads. */
	@Test
	void shouldLeaveTimedOutStepPending() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		stack(stepping, "example.Work");
		holdStop(stepping, 19, EventRequest.SUSPEND_ALL, true);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping));
		final StepRequest request = stubStepRequestCreation(stepping, StepRequest.STEP_INTO);
		session = spy(session);
		doReturn(session).when(breakpointTracker).suspensionSession();
		doAnswer(invocation -> session.awaitStepStop(0, invocation.getArgument(1)))
			.when(session).awaitStepStop(eq(30_000L), any());

		assertThat(tools.jdwp_step_into(1L, true)).startsWith("Step into started")
			.contains("still_waiting", "[TIMEOUT]", "30000ms", "jdwp_resume_until_event(threadId=1)", JDK_STEP_TIMEOUT,
				"jdwp_resume releases them, and the step stays armed");
		assertThat(session.stepPending(stepping)).isTrue();
		verify(erm, never()).deleteEventRequest(request);
	}

	/** A global wait reports why its release was skipped and limits the step threads it names. */
	@Test
	void shouldReportPendingStepThreadsAfterGlobalTimeout() throws Exception {
		when(jdiService.getVM()).thenReturn(vm);
		final ThreadReference presented = suspendedThread(99L);
		for (int i = 1; i <= 6; i++) {
			final ThreadReference thread = suspendedThread(i);
			final StepRequest request = stubStepRequestCreation(thread, StepRequest.STEP_OVER);
			assertThat(session.step(thread, List::of, () -> request).refusal()).isNull();
		}

		final String reply = tools.jdwp_resume_until_event(20);
		assertThat(reply).contains("[TIMEOUT]", "released nothing", "jdwp_resume releases the stops already seen",
			"steps stay armed", "thread-1 (ID=1)", "thread-2 (ID=2)", "thread-3 (ID=3)", "thread-4 (ID=4)",
			"thread-5 (ID=5)").doesNotContain("thread-6 (ID=6)");
		verify(stops.get(presented), never()).resume();
	}

	/** Checks timeout advice for an unfiltered step into and a step out of application code. */
	@ParameterizedTest
	@CsvSource({"java.util.ArrayList, true", "example.Work, false"})
	void shouldOmitJdkAdviceFromUnfilteredStepTimeouts(String className, boolean into) throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		stack(thread, className);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		final StepRequest req = stubStepRequestCreation(thread, into ? StepRequest.STEP_INTO : StepRequest.STEP_OUT);
		session = spy(session);
		doReturn(session).when(breakpointTracker).suspensionSession();
		doAnswer(invocation -> session.awaitStepStop(0, invocation.getArgument(1)))
			.when(session).awaitStepStop(eq(30_000L), any());

		final String reply = into ? tools.jdwp_step_into(1L, true) : tools.jdwp_step_out(1L, true);

		assertThat(reply).contains("[TIMEOUT]", "jdwp_resume_until_event(threadId=1)").doesNotContain(JDK_STEP_TIMEOUT);
		verify(req, never()).addClassExclusionFilter(any());
	}

	private CountDownLatch signalOnRelease(ThreadReference thread) {
		final CountDownLatch released = new CountDownLatch(1);
		doAnswer(invocation -> {
			released.countDown();
			return null;
		}).when(stops.get(thread)).resume();
		return released;
	}

	@Nested
	@DisplayName("Step depth wiring")
	class StepDepthWiring {

		/** Keeps step over unfiltered in application code. */
		@Test
		@DisplayName("jdwp_step_over creates a STEP_LINE/STEP_OVER request, count-filtered and enabled")
		void shouldCreateStepOverRequestWithStepLineAndStepOver() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			stack(thread, "example.Work");
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			tools.jdwp_step_over(1L);

			verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
			verify(req, never()).addClassExclusionFilter(any());
			verify(req).addCountFilter(1);
			verify(req).enable();
		}

		/** Checks that a step into from application code adds all platform filters before the count. */
		@Test
		@DisplayName("jdwp_step_into filters JDK classes before applying the count filter")
		void shouldCreateStepIntoRequestWithStepLineAndStepInto() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			stack(thread, "example.Work");
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_INTO);

			tools.jdwp_step_into(1L);

			verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
			final InOrder order = inOrder(req);
			order.verify(req).addClassExclusionFilter("java.*");
			order.verify(req).addClassExclusionFilter("javax.*");
			order.verify(req).addClassExclusionFilter("jdk.*");
			order.verify(req).addClassExclusionFilter("sun.*");
			order.verify(req).addClassExclusionFilter("com.sun.*");
			order.verify(req).addCountFilter(1);
			order.verify(req).enable();
		}

		/** Keeps step out unfiltered in application code. */
		@Test
		@DisplayName("jdwp_step_out creates a STEP_LINE/STEP_OUT request, count-filtered and enabled")
		void shouldCreateStepOutRequestWithStepLineAndStepOut() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			stack(thread, "example.Work");
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_OUT);

			tools.jdwp_step_out(1L);

			verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OUT);
			verify(req, never()).addClassExclusionFilter(any());
			verify(req).addCountFilter(1);
			verify(req).enable();
		}

		/** A hidden probe is excluded on every step, and the visible frame decides JDK filtering. */
		@ParameterizedTest
		@CsvSource({"example.Work, into", "java.util.ArrayList, into", "example.Work, over", "example.Work, out"})
		void shouldExcludeProbeClassesAndUseVisibleFrame(String className, String direction) throws Exception {
			final String probe = "example." + FastBreakpointProbeSource.CLASS_PREFIX + "Test";
			final ThreadReference thread = suspendedThread(1L);
			stack(thread, probe, className);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final int depth = switch (direction) {
				case "into" -> StepRequest.STEP_INTO;
				case "over" -> StepRequest.STEP_OVER;
				default -> StepRequest.STEP_OUT;
			};
			final StepRequest req = stubStepRequestCreation(thread, depth);

			final String reply = switch (direction) {
				case "into" -> tools.jdwp_step_into(1L);
				case "over" -> tools.jdwp_step_over(1L);
				default -> tools.jdwp_step_out(1L);
			};

			assertThat(reply).startsWith("Step " + direction + " started from " + className)
				.contains("first lands on the stop line").doesNotContain(JDK_STEP_TIMEOUT);
			final InOrder order = inOrder(req);
			order.verify(req).addClassExclusionFilter(probe);
			order.verify(req).addCountFilter(1);
			final boolean filtered = "into".equals(direction) && "example.Work".equals(className);
			verify(req, times(filtered ? 6 : 1)).addClassExclusionFilter(any());
		}

		/** Preserves stepping when a fresh read of the logical top frame fails. */
		@Test
		void shouldStepUnfilteredWhenTopFrameCannotBeRead() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			stack(thread, "example.Work");
			when(thread.frame(0)).thenThrow(new InvalidStackFrameException());
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_INTO);

			assertThat(tools.jdwp_step_into(1L)).startsWith("Step into started on thread");
			verify(req, never()).addClassExclusionFilter(any());
			verify(req).enable();
		}
	}

	@Nested
	@DisplayName("Thread resolution")
	class ThreadResolution {

		@Test
		@DisplayName("uses the explicit threadId when supplied (resolved via vm.allThreads())")
		void shouldUseExplicitThreadIdWhenSupplied() throws Exception {
			final ThreadReference thread = suspendedThread(42L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			final String out = tools.jdwp_step_over(42L);

			final ArgumentCaptor<ThreadReference> threadCaptor = ArgumentCaptor.forClass(ThreadReference.class);
			verify(erm).createStepRequest(threadCaptor.capture(), anyInt(), anyInt());
			assertThat(threadCaptor.getValue()).isSameAs(thread);
			assertThat(out).contains("42").contains("thread-42")
				.contains("Call jdwp_resume_until_event(threadId=42) to wait for this thread's next stop")
				.contains("All other threads run.");
		}

		@Test
		@DisplayName("falls back to the last breakpoint thread when threadId is null")
		void shouldFallBackToLastBreakpointThreadWhenThreadIdIsNull() throws Exception {
			final ThreadReference thread = suspendedThread(7L);
			breakpointTracker.setLastBreakpointThread(thread, -1);
			when(jdiService.getVM()).thenReturn(vm);
			stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			final String out = tools.jdwp_step_over(null);

			verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
			assertThat(out).contains("thread 7").contains("thread-7");
		}

		@Test
		@DisplayName("returns 'Thread not found with ID 99' when supplied threadId is unknown")
		void shouldReturnErrorWhenThreadIdSuppliedButNotFound() throws Exception {
			final ThreadReference other = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(other));

			final String out = tools.jdwp_step_over(99L);

			assertThat(out).isEqualTo("Error: Thread not found with ID 99");
			verify(erm, never()).createStepRequest(any(), anyInt(), anyInt());
		}

		@Test
		@DisplayName("returns 'No current stop' when threadId is null and nothing is stopped")
		void shouldReturnErrorWhenNullThreadIdAndNoLastBreakpointThread() throws Exception {
			when(jdiService.getVM()).thenReturn(vm);

			final String out = tools.jdwp_step_over(null);

			assertThat(out).isEqualTo("Error: No current stop. Provide a threadId, or hit a breakpoint first.");
			verify(erm, never()).createStepRequest(any(), anyInt(), anyInt());
		}
	}

	@Nested
	@DisplayName("Suspended-state guard")
	class SuspendedStateGuard {

		@Test
		@DisplayName("rejects with 'Thread is not suspended. Cannot step.' when resolved thread is running")
		void shouldRejectWhenResolvedThreadIsNotSuspended() throws Exception {
			final ThreadReference thread = mock(ThreadReference.class);
			when(thread.uniqueID()).thenReturn(1L);
			when(thread.isSuspended()).thenReturn(false);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));

			final String out = tools.jdwp_step_over(1L);

			assertThat(out).isEqualTo("Error: Thread is not suspended. Cannot step.");
			verify(erm, never()).createStepRequest(any(), anyInt(), anyInt());
			verify(thread, never()).resume();
		}
	}

	@Nested
	@DisplayName("Pre-existing StepRequest cleanup")
	class PreExistingStepRequestCleanup {

		@Test
		@DisplayName("deletes the pre-existing StepRequest for the same thread before creating a new one")
		void shouldDeletePreExistingStepRequestForSameThreadBeforeCreatingNew() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));

			final StepRequest stale = mock(StepRequest.class);
			when(stale.thread()).thenReturn(thread);
			when(erm.stepRequests()).thenReturn(List.of(stale));
			stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			tools.jdwp_step_over(1L);

			final InOrder order = inOrder(erm);
			order.verify(erm).deleteEventRequest(stale);
			order.verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
		}

		@Test
		@DisplayName("does not delete StepRequests belonging to other threads")
		void shouldNotDeleteStepRequestsBelongingToOtherThreads() throws Exception {
			final ThreadReference target = suspendedThread(1L);
			final ThreadReference otherThread = suspendedThread(2L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(target, otherThread));

			final StepRequest foreign = mock(StepRequest.class);
			when(foreign.thread()).thenReturn(otherThread);
			when(erm.stepRequests()).thenReturn(List.of(foreign));
			stubStepRequestCreation(target, StepRequest.STEP_OVER);

			tools.jdwp_step_over(1L);

			verify(erm, never()).deleteEventRequest(foreign);
			verify(erm).createStepRequest(target, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
		}
	}

	@Nested
	@DisplayName("Return message")
	class ReturnMessage {

		@Test
		@DisplayName("includes the hint to wait for the same thread")
		void shouldIncludeResumeUntilEventHintInReturnMessage() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			final String out = tools.jdwp_step_over(1L);

			assertThat(out).contains("Call jdwp_resume_until_event(threadId=1) to wait for this thread's next stop");
		}

		@Test
		@DisplayName("includes the action label, thread uniqueID and thread name")
		void shouldIncludeThreadIdAndNameInReturnMessage() throws Exception {
			final ThreadReference thread = suspendedThread(13L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			stubStepRequestCreation(thread, StepRequest.STEP_INTO);

			final String out = tools.jdwp_step_into(13L);

			assertThat(out).contains("into").contains("13").contains("thread-13");
		}
	}

	@Nested
	@DisplayName("Resume")
	class Resume {

		/**
		 * The step request must be enabled BEFORE the thread is resumed. Enabling after resume
		 * would race with the thread's first line transition and the step could be lost.
		 */
		@Test
		@DisplayName("creates → enables the StepRequest, then releases the thread's stop (in that order)")
		void shouldResumeThreadAfterCreatingStepRequest() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			tools.jdwp_step_over(1L);

			final EventSet stop = stops.get(thread);
			final InOrder order = inOrder(erm, req, stop);
			order.verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
			order.verify(req).setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
			order.verify(req).enable();
			order.verify(stop).resume();
		}
	}

	@Nested
	@DisplayName("Error envelope")
	class ErrorEnvelope {

		@Test
		@DisplayName("wraps unexpected JDI failures as 'Error: <message>'")
		void shouldWrapJdiExceptionsInErrorPrefix() throws Exception {
			when(jdiService.getVM()).thenThrow(new RuntimeException("boom"));

			final String out = tools.jdwp_step_over(1L);

			assertThat(out).startsWith("Error: boom");
		}
	}
}
