package one.edee.mcp.jdwp;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InternalException;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.FastBreakpointProbeSource;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.SocketException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the branches of {@code jdwp_resume_until_event} over a real {@link SuspensionSession}
 * and the {@link FakeSuspendModel} (issue #45):
 * <ul>
 *   <li>a stop nobody has seen yet is shown at once and nothing is resumed;</li>
 *   <li>otherwise the seen stops are released and the call waits for the next stop;</li>
 *   <li>timeout, reset, interrupt, VM death, and the VM-gone envelopes.</li>
 * </ul>
 */
@DisplayName("jdwp_resume_until_event")
class JDWPToolsResumeUntilEventVmDeathTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private EventHistory eventHistory;
	private JDWPTools tools;
	private FakeSuspendModel model;
	private FakeSuspendModel.FakeThread main;
	private SuspensionSession session;

	/** Wires the wait tools and suspension session to the same simulated VM. */
	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = new BreakpointTracker();
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		model = new FakeSuspendModel();
		main = model.thread("main");
		session = breakpointTracker.openSuspensionSession(model.vm, eventHistory);
		when(jdiService.getVM()).thenReturn(model.vm);
	}

	/**
	 * Publishes a stop with {@code policy}, as the dispatcher does once a set is processed.
	 */
	private SuspensionSession.Context publish(BreakpointTracker.LastBreakpoint stop, int policy) {
		return publish(stop, model.breakpointEvent(model.of(stop.thread()), policy));
	}

	/** Publishes a stop from the supplied event set. */
	private SuspensionSession.Context publish(BreakpointTracker.LastBreakpoint stop, EventSet set) {
		final SuspensionSession.Context context = session.route(set, stop.thread(), false);
		session.begin(context);
		session.candidate(context, stop);
		session.finish(context);
		return context;
	}

	/** Reports death for a closed connection without enumerating its threads. */
	@Test
	@DisplayName("returns [VM_DEATH] when the session closed while waiting")
	void shouldReturnVmDeathWhenSessionCloses() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		session.close();

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_DEATH]").contains("jdwp_connect").contains("jdwp_wait_for_attach");
		assertThat(tools.jdwp_resume_until_event(1_000, main.ref.uniqueID())).startsWith("[VM_DEATH]");
		verify(model.vm, never()).allThreads();
	}

	@Test
	@DisplayName("a stop nobody has seen is returned at once, and nothing is resumed")
	void shouldReturnUnseenStopWithoutResuming() throws Exception {
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("Event fired.").contains("main").contains("breakpoint=11");
		assertThat(main.count).as("the stop stays held").isEqualTo(1);
	}

	@Test
	@DisplayName("a seen stop is released before waiting, and the next stop is returned")
	void shouldReleaseSeenStopAndWaitForTheNext() throws Exception {
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);
		assertThat(tools.jdwp_resume_until_event(1_000)).contains("breakpoint=11");

		final CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> tools.jdwp_resume_until_event(10_000));
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (main.count != 0 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(main.count).as("the seen stop was released").isZero();
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 12), EventRequest.SUSPEND_EVENT_THREAD);

		assertThat(waiting.get(5, TimeUnit.SECONDS)).contains("breakpoint=12")
			.contains("Released before waiting: breakpoint 11 on thread main");
	}

	@Test
	void resumeReportsReleasedAndFailedHolds() {
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		assertThat(session.suspendThread(worker.ref)).isNull();
		assertThat(session.suspendThread(main.ref)).isNull();
		doThrow(new InternalException("resume rejected")).when(main.ref).resume();

		assertThat(tools.jdwp_resume())
			.contains("Resumed: the jdwp_suspend_thread hold of worker")
			.contains("Resume failed", "the jdwp_suspend_thread hold of main", "InternalException: resume rejected")
			.doesNotContain("Nothing to release");
		assertThat(model.counts()).containsEntry("worker", 0).containsEntry("main", 1);
	}

	/** A failed lease repayment is reported by the next resume, which can release the saved count. */
	@Test
	void resumeRetriesUnpaidLeaseDebt() throws Exception {
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);
		session.presentCurrent();
		session.lease(main.ref);
		model.beginInvocation(main);
		assertThat(tools.jdwp_resume()).contains("Resumed: breakpoint 11 on thread main");

		model.completeInvocation(main);
		doThrow(new InternalException("lease repayment failed"))
			.doThrow(new InternalException("lease retry rejected"))
			.doAnswer(call -> --main.count).when(main.ref).resume();
		session.unlease(main.ref);

		assertThat(main.count).isEqualTo(1);
		assertThat(tools.jdwp_resume()).contains("lease debt", "InternalException: lease retry rejected");
		assertThat(main.count).isEqualTo(1);
		assertThat(tools.jdwp_resume_thread(main.ref.uniqueID())).contains("released:", "lease debt");
		assertThat(main.count).isZero();
	}

	/** Both resume tools name a failed automatic release and can retry the finished set. */
	@Test
	void resumeReportsThenRecoversFailedAutomaticRelease() {
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final EventSet set = model.breakpointEvent(main, EventRequest.SUSPEND_ALL);
		doThrow(new InternalException("automatic release rejected"))
			.doThrow(new InternalException("automatic release retry rejected")).doAnswer(call -> {
				model.vm.resume();
				return null;
			}).when(set).resume();
		finishWithoutStop(set);

		assertThat(tools.jdwp_resume()).contains("failed automatic release", "main", "automatic release retry rejected")
			.doesNotContain("still being processed");
		assertThat(model.counts()).containsEntry("main", 1).containsEntry("worker", 1);

		when(model.vm.allThreads()).thenReturn(List.of(worker.ref));
		assertThat(tools.jdwp_resume_thread(main.ref.uniqueID()))
			.contains("failed automatic release", "released:", "VM-wide suspension");
		assertThat(model.counts()).containsEntry("main", 0).containsEntry("worker", 0);
	}

	/** The event thread can retry a suppressed set while its original invocation still holds a lease. */
	@Test
	void targetedResumeRetriesFailedSuppressionDuringInvocation() throws Exception {
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);
		session.presentCurrent();
		session.lease(main.ref);
		model.beginInvocation(main);
		final EventSet set = model.breakpointEvent(main, EventRequest.SUSPEND_EVENT_THREAD);
		doThrow(new InternalException("suppressed release rejected")).doAnswer(call -> {
			main.ref.resume();
			return null;
		}).when(set).resume();
		session.finish(session.route(set, main.ref, false));

		assertThat(tools.jdwp_resume_thread(main.ref.uniqueID())).contains("Released from thread",
			"failed automatic release", "an evaluation is running");
		assertThat(model.invocationRuns(main)).isTrue();
		model.completeInvocation(main);
		session.unlease(main.ref);
		assertThat(main.count).isEqualTo(1);
	}

	/** Targeted resume can retry a watchdog failure for a bystander omitted from JDI enumeration. */
	@Test
	void targetedResumeFindsUnenumeratedWatchdogFailureOwner() throws Exception {
		final FakeSuspendModel.FakeThread owner = model.thread("monitor-owner");
		final FakeSuspendModel.FakeThread bystander = model.thread("bystander");
		final SuspensionSession.Context context = session.route(
			model.breakpointEvent(main, EventRequest.SUSPEND_ALL), main.ref, false);
		assertThat(session.begin(context)).isTrue();
		session.beginProcessing(context);
		try {
			session.lease(main.ref);
			model.beginInvocation(main);
			final ObjectReference monitor = mock(ObjectReference.class);
			when(monitor.owningThread()).thenReturn(owner.ref);
			doThrow(new InternalException("watchdog resume rejected"))
				.doAnswer(call -> --owner.count).when(owner.ref).resume();
			assertThat(session.unblock(session.dispatcherInvocations().get(0).token(), owner.ref, monitor)).isNull();
			assertThat(owner.count).isOne();
			when(model.vm.allThreads()).thenReturn(List.of(main.ref, bystander.ref));

			assertThat(tools.jdwp_resume_thread(owner.ref.uniqueID())).contains("watchdog", "monitor-owner");
			assertThat(model.counts()).containsEntry("monitor-owner", 0).containsEntry("bystander", 1);
		} finally {
			SuspensionSession.endProcessing();
		}
	}

	/** An automatic release failure wakes a waiter that already finished its initial release pass. */
	@Test
	void resumeUntilEventWakesWhenAutomaticReleaseFailsDuringWait() throws Exception {
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final EventSet set = model.breakpointEvent(main, EventRequest.SUSPEND_EVENT_THREAD);
		doThrow(new InternalException("automatic release rejected during wait")).when(set).resume();
		final SuspensionSession.Context context = session.route(set, main.ref, false, false);
		session.begin(context);
		final CompletableFuture<String> waiting = startWaitReleasing(worker);
		session.finish(context);

		assertThat(waiting.get(2, TimeUnit.SECONDS)).startsWith("[RESUME_FAILED]")
			.contains("the jdwp_suspend_thread hold of worker", "failed automatic release",
				"automatic release rejected during wait", "thread ID " + main.ref.uniqueID(),
				"jdwp_resume_thread(threadId=" + main.ref.uniqueID() + ")")
			.doesNotContain("still being processed");
		assertThat(main.count).isEqualTo(1);
	}

	/** An invocation ending after the first pass can leave debt that prevents another stop. */
	@Test
	void resumeUntilEventReportsLateLeaseDebtAndEarlierProgress() throws Exception {
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);
		session.presentCurrent();
		session.lease(main.ref);
		model.beginInvocation(main);
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final CompletableFuture<String> waiting = startWaitReleasing(worker);
		model.completeInvocation(main);
		doThrow(new InternalException("lease repayment rejected")).when(main.ref).resume();

		session.unlease(main.ref);

		assertThat(waiting.get(2, TimeUnit.SECONDS)).startsWith("[RESUME_FAILED]")
			.contains("breakpoint 11", "the jdwp_suspend_thread hold of worker", "lease debt", "lease repayment rejected");
		assertThat(main.count).isEqualTo(1);
	}

	/** The advised debt retry leaves the pending step and the bystander's stop-all share in place. */
	@Test
	void targetedDebtRetryKeepsBystanderParkedDuringStep() throws Exception {
		final FakeSuspendModel.FakeThread debtor = model.thread("debtor");
		final FakeSuspendModel.FakeThread bystander = model.thread("bystander");
		final EventRequestManager requests = mock(EventRequestManager.class);
		when(model.vm.eventRequestManager()).thenReturn(requests);
		when(requests.createStepRequest(main.ref, StepRequest.STEP_LINE, StepRequest.STEP_OVER))
			.thenReturn(mock(StepRequest.class));

		publish(new BreakpointTracker.LastBreakpoint(debtor.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);
		session.presentCurrent();
		session.lease(debtor.ref);
		model.beginInvocation(debtor);
		assertThat(tools.jdwp_resume()).contains("breakpoint 11 on thread debtor");

		publish(new BreakpointTracker.LastBreakpoint(main.ref, 12), EventRequest.SUSPEND_ALL);
		session.presentCurrent();
		assertThat(tools.jdwp_step_over(main.ref.uniqueID())).contains("Step over started", "stop-all");
		model.completeInvocation(debtor);
		doThrow(new InternalException("debt repayment rejected"))
			.doAnswer(call -> --debtor.count).when(debtor.ref).resume();
		session.unlease(debtor.ref);
		assertThat(debtor.count).isEqualTo(2);

		final long debtorId = debtor.ref.uniqueID();
		final String reply = tools.jdwp_resume_until_event(50);

		assertThat(reply).startsWith("[RESUME_FAILED]").contains("lease debt on thread debtor",
			"thread ID " + debtorId, "jdwp_resume_thread(threadId=" + debtorId + ")",
			"jdwp_resume also releases every other presented stop", "jdwp_suspend_thread hold",
			"stop-all a pending step relies on");
		assertThat(bystander.count).isEqualTo(1);
		assertThat(tools.jdwp_resume_thread(debtorId)).contains("lease debt on thread debtor");
		assertThat(debtor.count).isZero();
		assertThat(bystander.count).isEqualTo(1);
		assertThat(session.stepPending(main.ref)).isTrue();
	}

	/** A closed wait keeps confirmed earlier releases and omits obsolete pending-set counts. */
	@Test
	void closedWaitKeepsProgressWithoutReportingPendingSets() throws Exception {
		final SuspensionSession.Context context =
			session.route(model.breakpointEvent(main, EventRequest.SUSPEND_EVENT_THREAD), main.ref, false);
		session.begin(context);
		final CompletableFuture<String> waiting = startWaitReleasing(model.thread("worker"));
		session.close();

		assertThat(waiting.get(2, TimeUnit.SECONDS)).startsWith("[VM_DEATH]")
			.contains("the jdwp_suspend_thread hold of worker").doesNotContain("still being processed");
	}

	/** A disconnect during stop rendering preserves confirmed restoration and releases, without pending-set counts. */
	@Test
	void closedStopRenderingKeepsConfirmedProbeRestoration() throws Exception {
		final FastLogpointService fastLogpoints = JDWPToolsTestSupport.fastLogpointsMock();
		tools = JDWPToolsTestSupport.newTools(jdiService, breakpointTracker, eventHistory, fastLogpoints);
		final String restoration = "Cleared fast breakpoint 7: the original bytecode of example.Work is restored.";
		when(fastLogpoints.retryPendingRemovals()).thenReturn(restoration);
		when(main.ref.isSuspended()).thenAnswer(call -> {
			session.close();
			throw new VMDisconnectedException("transport closed during stop rendering");
		});
		final FakeSuspendModel.FakeThread pending = model.thread("pending");
		session.route(model.breakpointEvent(pending, EventRequest.SUSPEND_EVENT_THREAD), pending.ref, false);
		final CompletableFuture<String> waiting = startWaitReleasing(model.thread("worker"));
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);

		assertThat(waiting.get(2, TimeUnit.SECONDS)).startsWith("[VM_DEATH]")
			.contains("breakpoint=11", "the jdwp_suspend_thread hold of worker", restoration)
			.doesNotContain("still being processed");
		verify(fastLogpoints).retryPendingRemovals();
	}

	/** The targeted first pass reports only its own successful and failed releases, including on disconnect. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void targetedWaitKeepsProgressAfterReleaseFailure(boolean disconnect) {
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		assertThat(session.suspendThread(main.ref)).isNull();
		assertThat(session.suspendThread(worker.ref)).isNull();
		final EventSet set = model.breakpointEvent(main, EventRequest.SUSPEND_EVENT_THREAD);
		final RuntimeException failure = disconnect ? new VMDisconnectedException("lost during reply")
			: new InternalException("targeted resume rejected");
		doThrow(failure).when(set).resume();
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), set);
		session.presentCurrent();

		final long id = main.ref.uniqueID();
		final String reply = tools.jdwp_resume_until_event(30_000, id);
		assertThat(reply).startsWith(disconnect ? "[VM_DEATH]" : "[RESUME_FAILED]")
			.contains("Released before waiting on thread " + id, "its jdwp_suspend_thread hold", "Resume failed",
				"breakpoint 11", failure.getMessage()).doesNotContain("worker");
		if (!disconnect) {
			assertThat(reply).contains("jdwp_resume_thread(threadId=" + id + ")");
		}
		assertThat(model.counts()).containsEntry("main", 1).containsEntry("worker", 1);
	}

	/** Only a failed automatic release that covers the selected thread stops a targeted wait. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void targetedWaitScopesLateAutomaticReleaseFailure(boolean coversTarget) throws Exception {
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final CompletableFuture<String> waiting = startTargetedWait(30_000);
		synchronized (session) {
			final int policy = coversTarget ? EventRequest.SUSPEND_ALL : EventRequest.SUSPEND_EVENT_THREAD;
			final EventSet set = model.breakpointEvent(worker, policy);
			doThrow(new InternalException("worker auto-resume rejected")).when(set).resume();
			final SuspensionSession.Context context = session.route(set, worker.ref, false, false);
			session.begin(context);
			session.finish(context);
		}
		if (coversTarget) {
			assertThat(waiting.get(2, TimeUnit.SECONDS)).startsWith("[RESUME_FAILED]")
				.contains("Released before waiting on thread 1", "worker auto-resume rejected", "VM-wide suspension",
					"jdwp_resume_thread(threadId=" + worker.ref.uniqueID() + ")");
		} else {
			assertThatThrownBy(() -> waiting.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			synchronized (session) {
				publish(new BreakpointTracker.LastBreakpoint(main.ref, 22), EventRequest.SUSPEND_EVENT_THREAD);
			}
			assertThat(waiting.get(2, TimeUnit.SECONDS)).startsWith("Event fired").contains("breakpoint=22");
		}
		assertThat(worker.count).isOne();
	}

	/** Only debt on the selected thread stops the waiter, and a debt-only thread can be found for retry. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void targetedWaitScopesLateLeaseDebt(boolean targetOwesDebt) throws Exception {
		final FakeSuspendModel.FakeThread debtor = targetOwesDebt ? main : model.thread("worker");
		final CompletableFuture<String> waiting = startTargetedWait(30_000);
		synchronized (session) {
			publish(new BreakpointTracker.LastBreakpoint(debtor.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);
			session.presentCurrent();
			session.lease(debtor.ref);
			model.beginInvocation(debtor);
			assertThat(session.releaseAll().released()).hasSize(1);
			model.completeInvocation(debtor);
			doThrow(new InternalException("debt repayment rejected"))
				.doAnswer(call -> --debtor.count).when(debtor.ref).resume();
			session.unlease(debtor.ref);
		}
		if (targetOwesDebt) {
			assertThat(waiting.get(2, TimeUnit.SECONDS)).startsWith("[RESUME_FAILED]")
				.contains("Released before waiting on thread 1", "lease debt", "debt repayment rejected",
					"jdwp_resume_thread(threadId=1)");
		} else {
			assertThatThrownBy(() -> waiting.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			synchronized (session) {
				publish(new BreakpointTracker.LastBreakpoint(main.ref, 22), EventRequest.SUSPEND_EVENT_THREAD);
			}
			assertThat(waiting.get(2, TimeUnit.SECONDS)).startsWith("Event fired").contains("breakpoint=22");
		}
		when(model.vm.allThreads()).thenReturn(List.of());
		assertThat(tools.jdwp_resume_thread(debtor.ref.uniqueID())).contains("resumed (released:", "lease debt");
		assertThat(debtor.count).isZero();
	}

	/** Starts a wait and signals after its first release pass reaches the given manual hold. */
	private CompletableFuture<String> startWaitReleasing(FakeSuspendModel.FakeThread thread) throws Exception {
		assertThat(session.suspendThread(thread.ref)).isNull();
		final CountDownLatch resumed = new CountDownLatch(1);
		doAnswer(call -> {
			thread.count--;
			resumed.countDown();
			return null;
		}).when(thread.ref).resume();
		final CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> tools.jdwp_resume_until_event(5_000));
		assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue();
		return waiting;
	}

	/** An automatic resume that fails again on the initial pass still gives the event thread's ID. */
	@Test
	void resumeUntilEventReportsFailedAutomaticReleaseBeforeWaiting() {
		final EventSet set = model.breakpointEvent(main, EventRequest.SUSPEND_EVENT_THREAD);
		doThrow(new InternalException("automatic release rejected")).when(set).resume();
		finishWithoutStop(set);

		assertThat(tools.jdwp_resume_until_event(50)).startsWith("[RESUME_FAILED]")
			.contains("failed automatic release", "automatic release rejected", "thread ID " + main.ref.uniqueID(),
				"jdwp_resume_thread(threadId=" + main.ref.uniqueID() + ")");
	}

	/** Processes a non-stopping event set so finish attempts its automatic release. */
	private void finishWithoutStop(EventSet set) {
		final SuspensionSession.Context context = session.route(set, main.ref, false, false);
		session.begin(context);
		session.finish(context);
	}

	@Test
	void resumeThreadReportsFailureWithoutClaimingTheThreadResumed() {
		assertThat(session.suspendThread(main.ref)).isNull();
		doThrow(new ObjectCollectedException()).when(main.ref).resume();

		assertThat(tools.jdwp_resume_thread(main.ref.uniqueID()))
			.contains("Resume failed", "its jdwp_suspend_thread hold", "ObjectCollectedException")
			.doesNotContain("resumed (released:", "nothing holds", ": null");
	}

	@Test
	void resumeThreadReportsPartialProgressOnTheSameThread() {
		assertThat(session.suspendThread(main.ref)).isNull();
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_ALL);
		session.presentCurrent();
		doThrow(new InternalException()).doAnswer(call -> --main.count).when(main.ref).resume();

		assertThat(tools.jdwp_resume_thread(main.ref.uniqueID()))
			.contains("Released from thread", "breakpoint 11 stop", "Resume failed",
				"its jdwp_suspend_thread hold", "InternalException")
			.doesNotContain("resumed (released:");
		assertThat(main.count).isEqualTo(1);
	}

	@Test
	void resumeUntilEventReportsPartialProgressWithoutWaitingOnFailedRelease() {
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		assertThat(session.suspendThread(worker.ref)).isNull();
		final EventSet set = model.breakpointEvent(main, EventRequest.SUSPEND_EVENT_THREAD);
		doThrow(new InternalException("resume rejected")).when(set).resume();
		final SuspensionSession.Context context = session.route(set, main.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(main.ref, 11));
		session.finish(context);
		session.presentCurrent();

		assertThat(tools.jdwp_resume_until_event(50)).startsWith("[RESUME_FAILED]")
			.contains("the jdwp_suspend_thread hold of worker", "breakpoint 11 on thread main",
				"InternalException: resume rejected");
		assertThat(model.counts()).containsEntry("worker", 0).containsEntry("main", 1);
	}

	@Test
	void resumeToolsReportDisconnectDuringRelease() {
		for (String tool : new String[]{"jdwp_resume", "jdwp_resume_thread", "jdwp_resume_until_event"}) {
			final FakeSuspendModel.FakeThread thread = model.thread(tool);
			session = breakpointTracker.openSuspensionSession(model.vm, eventHistory);
			assertThat(session.suspendThread(thread.ref)).isNull();
			doThrow(new VMDisconnectedException("transport closed")).when(thread.ref).resume();

			final String reply = switch (tool) {
				case "jdwp_resume" -> tools.jdwp_resume();
				case "jdwp_resume_thread" -> tools.jdwp_resume_thread(thread.ref.uniqueID());
				default -> tools.jdwp_resume_until_event(50);
			};

			assertThat(reply).as(tool).startsWith("[VM_DEATH]").contains("disconnect")
				.doesNotContain("Resumed:", "resumed (released:");
			assertThat(session.isClosed()).isTrue();
		}
	}

	@Test
	@DisplayName("returns the diagnostic report when no stop arrives before the deadline")
	void shouldReturnTimeoutReport() {
		final String result = tools.jdwp_resume_until_event(50);

		assertThat(result).doesNotStartWith("Event fired.").doesNotStartWith("[VM_DEATH]").contains("still_waiting");
	}

	@Test
	@DisplayName("returns [NO_EVENT] when jdwp_reset runs while waiting")
	void shouldReturnNoEventOnReset() throws Exception {
		final CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> tools.jdwp_resume_until_event(10_000));
		Thread.sleep(100);
		session.reset();

		assertThat(waiting.get(5, TimeUnit.SECONDS)).startsWith("[NO_EVENT]").contains("jdwp_reset");
	}

	@Test
	@DisplayName("targeted wait presents its thread's stop and reports the number of other unseen stops")
	void shouldReportOtherUnseenStops() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		publish(new BreakpointTracker.LastBreakpoint(worker.ref, 1), EventRequest.SUSPEND_EVENT_THREAD);
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 2), EventRequest.SUSPEND_EVENT_THREAD);

		assertThat(tools.jdwp_resume_until_event(1_000, main.ref.uniqueID())).startsWith("Event fired")
			.contains("Thread: main", "breakpoint=2", "1 unseen stop").doesNotContain("breakpoint=1");
		assertThat(worker.count).isEqualTo(1);
		assertThat(tools.jdwp_resume_until_event(1_000)).contains("breakpoint=1");
	}

	@Test
	@DisplayName("a stop is found by ID even when JDI omits its thread from enumeration")
	void shouldFindPublishedStopWithoutThreadEnumeration() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		when(model.vm.allThreads()).thenReturn(List.of());
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 40), EventRequest.SUSPEND_EVENT_THREAD);

		assertThat(tools.jdwp_resume_until_event(1_000, main.ref.uniqueID())).startsWith("Event fired")
			.contains("Thread: main", "breakpoint=40");
	}

	@Test
	@DisplayName("targeted waits resolve an omitted thread from its manual hold or pending step")
	void shouldFindHeldAndSteppingThreadWithoutEnumeration() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		when(model.vm.allThreads()).thenReturn(List.of());
		final long id = main.ref.uniqueID();
		assertThat(session.suspendThread(main.ref)).isNull();

		assertThat(tools.jdwp_resume_until_event(25, id)).contains("[TIMEOUT]", "its jdwp_suspend_thread hold");
		assertThat(main.count).isZero();

		assertThat(session.suspendThread(main.ref)).isNull();
		assertThat(session.step(main.ref, List::of, () -> mock(StepRequest.class)).refusal()).isNull();
		assertThat(tools.jdwp_resume_until_event(25, id)).contains("[TIMEOUT]").doesNotContain("Released before waiting");
		assertThat(session.stepPending(main.ref)).isTrue();
	}

	/** Exercises the recovery advised when a foreign stop-all blocks a virtual thread's step. */
	@Test
	void shouldResumePendingStepThreadOmittedByEnumeration() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final long id = main.ref.uniqueID();
		assertThat(session.suspendThread(main.ref)).isNull();
		assertThat(session.step(main.ref, List::of, () -> mock(StepRequest.class)).refusal()).isNull();
		when(model.vm.allThreads()).thenReturn(List.of(worker.ref));
		publish(new BreakpointTracker.LastBreakpoint(worker.ref, 20), EventRequest.SUSPEND_ALL);
		assertThat(tools.jdwp_resume_until_event(25, id)).contains("breakpoint 20");
		assertThat(tools.jdwp_resume_until_event(25)).contains("breakpoint=20");

		assertThat(tools.jdwp_resume_thread(id)).contains("resumed (released:");
		assertThat(session.stepPending(main.ref)).isTrue();
		assertThat(main.count).isZero();
		assertThat(worker.count).isOne();
	}

	/** Pins the death report when a stop becomes known while JDI is enumerating threads. */
	@Test
	void shouldNameStopPublishedDuringFailedEnumeration() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final long id = main.ref.uniqueID();
		when(model.vm.allThreads()).thenAnswer(invocation -> {
			publish(new BreakpointTracker.LastBreakpoint(main.ref, 40), EventRequest.SUSPEND_EVENT_THREAD);
			session.close();
			throw new VMDisconnectedException("transport closed during enumeration");
		});

		assertThat(tools.jdwp_resume_until_event(25, id)).startsWith("[VM_")
			.contains("Last selected stop", "Thread: main", "breakpoint=40");
		assertThat(session.unseenCount()).isZero();
	}

	/** Keeps timeout and release information when the last event belonged to a collected thread. */
	@Test
	void shouldKeepTargetedTimeoutWhenFormerStopThreadIsCollected() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final FakeSuspendModel.FakeThread worker = model.thread("former-worker");
		publish(new BreakpointTracker.LastBreakpoint(worker.ref, 21), EventRequest.SUSPEND_EVENT_THREAD);
		assertThat(tools.jdwp_resume_until_event(25)).contains("breakpoint=21");
		assertThat(session.releaseThread(worker.ref).released()).isTrue();
		when(worker.ref.name()).thenThrow(new ObjectCollectedException("former worker already collected"));
		assertThat(session.suspendThread(main.ref)).isNull();

		assertThat(tools.jdwp_resume_until_event(25, main.ref.uniqueID()))
			.contains("Status: still_waiting", "[TIMEOUT]", "Released before waiting on thread 1");
		assertThat(main.count).isZero();
	}

	/** Publishes atomically with routing so the waiter sees the stop before checking for blockers. */
	@Test
	@DisplayName("a targeted wait after jdwp_resume_thread waits for the running thread's next stop")
	void shouldWaitForRunningThreadsNextStop() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final long id = main.ref.uniqueID();
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 1), EventRequest.SUSPEND_EVENT_THREAD);
		assertThat(tools.jdwp_resume_until_event(1_000, id)).contains("breakpoint=1");
		assertThat(tools.jdwp_resume_thread(id)).contains(") resumed");
		assertThat(main.count).isZero();
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		publish(new BreakpointTracker.LastBreakpoint(worker.ref, 2), EventRequest.SUSPEND_EVENT_THREAD);

		final FutureTask<String> waiting = new FutureTask<>(() -> tools.jdwp_resume_until_event(10_000, id));
		final Thread waiter = new Thread(waiting, "targeted-wait-test");
		waiter.start();
		try {
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (waiter.getState() != Thread.State.TIMED_WAITING && !waiting.isDone() && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertThat(waiter.getState()).isEqualTo(Thread.State.TIMED_WAITING);
			synchronized (session) {
				publish(new BreakpointTracker.LastBreakpoint(main.ref, 3), EventRequest.SUSPEND_EVENT_THREAD);
			}

			assertThat(waiting.get(5, TimeUnit.SECONDS)).startsWith("Event fired")
				.contains("Thread: main", "breakpoint=3", "1 unseen stop").doesNotContain("Released before waiting");
			assertThat(main.count).isEqualTo(1);
			assertThat(worker.count).isEqualTo(1);
			assertThat(session.unseenCount()).isEqualTo(1);
		} finally {
			waiting.cancel(true);
			waiter.join(5_000);
		}
	}

	@Test
	@DisplayName("a targeted wait on a running thread with no stop reports a targeted timeout")
	void shouldTimeOutWaitingForRunningThread() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final long id = main.ref.uniqueID();

		assertThat(tools.jdwp_resume_until_event(50, id)).startsWith("Status: still_waiting")
			.contains("[TIMEOUT] No suspending event fired on thread " + id + " within 50ms.")
			.doesNotContain("Released before waiting");
		assertThat(main.count).isZero();
	}

	@Test
	@DisplayName("targeted wait reports its release refusal without consuming another thread's stop")
	void shouldReportTargetedReleaseRefusal() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		publish(new BreakpointTracker.LastBreakpoint(worker.ref, 1), EventRequest.SUSPEND_ALL);

		assertThat(tools.jdwp_resume_until_event(1_000, main.ref.uniqueID()))
			.contains("was not resumed", "breakpoint 1", "thread worker", "not seen yet", "1 unseen stop");
		assertThat(main.count).isEqualTo(1);
		assertThat(session.unseenCount()).isEqualTo(1);
	}

	/** An active wait reports an unseen stop-all when it is published on another thread. */
	@Test
	void shouldRefuseForeignStopAllThatPublishesDuringTargetedWait() throws Exception {
		final CompletableFuture<String> waiting = startTargetedWait(10_000);
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		synchronized (session) {
			publish(new BreakpointTracker.LastBreakpoint(worker.ref, 21), EventRequest.SUSPEND_ALL);
		}

		assertThat(waiting.get(5, TimeUnit.SECONDS)).contains("Wait for thread 1 blocked", "thread worker",
			"Released before waiting", "breakpoint 21", "not seen yet").doesNotContain("[TIMEOUT]");
		assertThat(main.count).isEqualTo(1);
		assertThat(session.unseenCount()).isEqualTo(1);
	}

	/** A covering set that remains queued or processing at the deadline is named in the timeout. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void shouldReportUnsettledSetOnTargetedTimeout(boolean processing) throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final SuspensionSession.Context context =
			session.route(model.breakpointEvent(worker, EventRequest.SUSPEND_ALL), worker.ref, false);
		if (processing) {
			session.begin(context);
		}

		assertThat(tools.jdwp_resume_until_event(25, main.ref.uniqueID())).contains("[TIMEOUT]",
			"context#" + context.id(), "thread worker", processing ? "being processed" : "waiting to be processed")
			.doesNotContain("try again", "Released before waiting");
		assertThat(main.count).isEqualTo(1);
		assertThat(session.unseenCount()).isZero();
	}

	@Test
	@DisplayName("reset wakes a targeted wait with [NO_EVENT]")
	void shouldReturnNoEventOnResetDuringTargetedWait() throws Exception {
		final CompletableFuture<String> waiting = startTargetedWait(10_000);
		session.reset();

		assertThat(waiting.get(5, TimeUnit.SECONDS)).startsWith("[NO_EVENT]").contains("jdwp_reset");
	}

	@Test
	@DisplayName("VM death wakes a targeted wait with [VM_DEATH] even when another stop is unseen")
	void shouldReturnVmDeathDuringTargetedWait() throws Exception {
		final CompletableFuture<String> waiting = startTargetedWait(10_000);
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		publish(new BreakpointTracker.LastBreakpoint(worker.ref, 1), EventRequest.SUSPEND_EVENT_THREAD);
		session.close();

		assertThat(waiting.get(5, TimeUnit.SECONDS)).startsWith("[VM_DEATH]")
			.contains("Released before waiting on thread 1", "its jdwp_suspend_thread hold");
		assertThat(session.unseenCount()).isEqualTo(1);
	}

	private CompletableFuture<String> startTargetedWait(int timeoutMs) throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		assertThat(session.suspendThread(main.ref)).isNull();
		final long id = main.ref.uniqueID();
		final CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> tools.jdwp_resume_until_event(timeoutMs, id));
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!waiting.isDone() && !session.holdsOn(main.ref).isEmpty() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(waiting).isNotDone();
		assertThat(session.holdsOn(main.ref)).isEmpty();
		return waiting;
	}

	@Test
	@DisplayName("returns 'Wait interrupted' and preserves the thread interrupt status")
	void shouldReturnWaitInterruptedWhenThreadInterruptedDuringAwait() {
		Thread.currentThread().interrupt();
		try {
			final String result = tools.jdwp_resume_until_event(5_000);

			assertThat(result).isEqualTo("Wait interrupted");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	@DisplayName("VM death names the last stop selected before the connection closed")
	void shouldShowStopPublishedBeforeDeath() {
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);
		eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected"));
		session.close();

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_DEATH]").contains("breakpoint=11", "Thread: main");
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "status", "frames"})
	@DisplayName("live thread read failures fall back to the published stop report")
	void shouldRenderPublishedStopWhenLiveReadsFail(String read) throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final EventSet set = model.breakpointEvent(main, EventRequest.SUSPEND_EVENT_THREAD);
		final BreakpointEvent event = (BreakpointEvent) set.iterator().next();
		final Location location = mock(Location.class);
		final ReferenceType type = mock(ReferenceType.class);
		when(location.declaringType()).thenReturn(type);
		when(type.name()).thenReturn("example.Work");
		when(location.lineNumber()).thenReturn(49);
		when(event.location()).thenReturn(location);
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 49), set);
		final InternalException failure = new InternalException("cannot read thread " + read);
		switch (read) {
			case "name" -> when(main.ref.name()).thenThrow(failure);
			case "status" -> when(main.ref.isSuspended()).thenThrow(failure);
			case "frames" -> when(main.ref.frameCount()).thenThrow(failure);
			default -> throw new AssertionError(read);
		}

		assertThat(tools.jdwp_resume_until_event(1_000, main.ref.uniqueID()))
			.startsWith("Event fired at example.Work:49").contains("Thread: main (ID=1", "breakpoint=49");
		assertThat(session.releaseAll().released()).extracting(BreakpointTracker.LastBreakpoint::id).containsExactly(49);
	}

	@Test
	@DisplayName("a transport failure during rendering names the stop that was selected")
	void shouldNameSelectedStopWhenTransportFails() throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 50), EventRequest.SUSPEND_EVENT_THREAD);
		when(main.ref.isSuspended()).thenThrow(new VMDisconnectedException("transport closed"));

		assertThat(tools.jdwp_resume_until_event(1_000, main.ref.uniqueID())).startsWith("[VM_GONE]")
			.contains("breakpoint=50", "Thread: main (ID=1");
		assertThat(session.unseenCount()).isZero();
	}

	/** A death reply names the old stop and includes only verifiable details of that connection. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void shouldReportDeathWhenSessionClosesDuringFailedFrameRead(boolean reconnect) throws Exception {
		when(jdiService.getVM()).thenReturn(model.vm);
		final BreakpointTracker.LastBreakpoint stop = new BreakpointTracker.LastBreakpoint(
			main.ref, 30, BreakpointTracker.EventKind.BREAKPOINT, "example.Work:30");
		publish(stop, EventRequest.SUSPEND_EVENT_THREAD);
		eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT", "original stop"));
		final int exitEpoch = eventHistory.currentEpoch() + (reconnect ? 1 : 0);
		final String field = reconnect ? "example.Replacement.field" : "example.Work.field";
		when(jdiService.lastExit()).thenReturn(new JDIConnectionService.VmExit(
			Instant.EPOCH, null, 0, 0, 0, 1, 0, 0, List.of(new JDIConnectionService.VmExit.FieldWatch(
				42, field, "modification", false, 1)), exitEpoch));
		final FakeSuspendModel replacement = new FakeSuspendModel();
		when(main.ref.frameCount()).thenAnswer(invocation -> {
			session.close();
			if (reconnect) {
				eventHistory.beginNewSession();
				breakpointTracker.openSuspensionSession(replacement.vm, eventHistory);
				eventHistory.record(new EventHistory.DebugEvent("RECONNECT", "replacement attached"));
				eventHistory.record(new EventHistory.DebugEvent("LOGPOINT", "replacement logpoint"));
				final int id = breakpointTracker.registerPendingBreakpoint("example.Replacement", 40, 2, "ALL");
				breakpointTracker.markPendingFailed(id, "replacement breakpoint failed");
			}
			throw new IncompatibleThreadStateException("frame read lost the suspension");
		});

		final String reply = tools.jdwp_resume_until_event(25, main.ref.uniqueID());
		assertThat(reply).startsWith("[VM_DEATH]")
			.contains("example.Work:30", "breakpoint=30", "Recorded before the exit (jdwp_get_events): 1 BREAKPOINT.")
			.doesNotContain("1 RECONNECT", "1 LOGPOINT", "example.Replacement", "replacement breakpoint failed");
		if (reconnect) {
			assertThat(reply).doesNotContain("Field breakpoints at the exit");
		} else {
			assertThat(reply).contains("Field breakpoints at the exit: #42 example.Work.field (modification) — 1 hit");
		}
	}

	/** A replacement session that reuses an ID needs a fresh lookup before any retry. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void shouldRejectSessionFromADifferentVm(boolean wait) throws Exception {
		final FakeSuspendModel replacement = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread replacementThread = replacement.thread("replacement");
		final long id = main.ref.uniqueID();
		assertThat(replacementThread.ref.uniqueID()).isEqualTo(id);
		when(jdiService.getVM()).thenAnswer(invocation -> {
			// The old VM was captured before the reconnect installed the replacement session.
			final SuspensionSession next = breakpointTracker.openSuspensionSession(replacement.vm, eventHistory);
			assertThat(next.suspendThread(replacementThread.ref)).isNull();
			return model.vm;
		});

		final String reply = wait ? tools.jdwp_resume_until_event(25, id) : tools.jdwp_resume_thread(id);
		assertThat(replacementThread.count).as("replacement hold after %s", reply).isOne();
		assertThat(reply).startsWith("[CONNECTION_CHANGED]").contains("connection changed", "jdwp_get_threads",
			"fresh thread ID").doesNotContain("retry the call", "[VM_GONE]", "to re-attach");
		assertThat(breakpointTracker.suspensionSession().isClosed()).isFalse();
		verify(model.vm, never()).allThreads();
		verify(replacement.vm, never()).allThreads();
	}

	/** Unfiltered waits distinguish the user frame from a captured probe throw site. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void shouldRenderLogicalLocationForAnExceptionInsideAProbe(boolean invalidated) throws Exception {
		final String probeClass = "example." + FastBreakpointProbeSource.CLASS_PREFIX + "Condition";
		final Location physical = location(probeClass, 18);
		final Method method = mock(Method.class);
		when(method.name()).thenReturn("__mcpEvaluate");
		when(physical.method()).thenReturn(method);
		final Location user = location("example.Work", 30);
		final StackFrame probeFrame = mock(StackFrame.class);
		final StackFrame userFrame = mock(StackFrame.class);
		when(probeFrame.location()).thenReturn(physical);
		when(userFrame.location()).thenReturn(user);
		when(main.ref.frameCount()).thenReturn(2);
		when(main.ref.frames(0, 2)).thenReturn(List.of(probeFrame, userFrame));
		if (invalidated) {
			final StackFrame invalidatedFrame = mock(StackFrame.class);
			when(invalidatedFrame.location()).thenThrow(new InvalidStackFrameException("logical frame invalidated"));
			when(main.ref.frame(1)).thenReturn(invalidatedFrame, userFrame);
		} else {
			when(main.ref.frame(1)).thenReturn(userFrame);
		}

		final EventSet set = model.breakpointEvent(main, EventRequest.SUSPEND_EVENT_THREAD);
		final ExceptionEvent event = mock(ExceptionEvent.class);
		when(event.thread()).thenReturn(main.ref);
		when(event.location()).thenReturn(physical);
		when(set.iterator()).thenAnswer(invocation -> List.<Event>of(event).iterator());
		publish(new BreakpointTracker.LastBreakpoint(main.ref, null, BreakpointTracker.EventKind.EXCEPTION), set);

		final String reply = tools.jdwp_resume_until_event(25);
		if (invalidated) {
			assertThat(reply).startsWith("Event fired.").contains("via=exception", "Probe throw site at " + probeClass + ":18")
				.doesNotContain("example.Work:30");
		} else {
			assertThat(reply).startsWith("Event fired at example.Work:30.").contains("frames=1", "via=exception");
		}
		verify(main.ref).frame(1);
	}

	/** Builds a JDI location usable as an event site or a logical stack frame. */
	private static Location location(String className, int line) {
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(className);
		final Location location = mock(Location.class);
		when(location.declaringType()).thenReturn(type);
		when(location.lineNumber()).thenReturn(line);
		return location;
	}

	@Test
	@DisplayName("a step landing renders 'via=step'; an exception renders 'via=exception'")
	void shouldRenderStepAndExceptionKinds() {
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 3, BreakpointTracker.EventKind.STEP),
			EventRequest.SUSPEND_EVENT_THREAD);
		assertThat(tools.jdwp_resume_until_event(1_000)).startsWith("Event fired").contains("via=step")
			.doesNotContain("breakpoint=");

		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final SuspensionSession.Context context = session.route(
			model.breakpointEvent(worker, EventRequest.SUSPEND_EVENT_THREAD), worker.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(worker.ref, null,
			BreakpointTracker.EventKind.EXCEPTION));
		session.finish(context);
		assertThat(tools.jdwp_resume_until_event(1_000)).contains("via=exception").doesNotContain("breakpoint=");
	}

	@Test
	@DisplayName("an exception stop names the exception's type and message; a field-breakpoint stop is labelled as one (#50)")
	void shouldNameTheExceptionAndTheField() {
		breakpointTracker.recordStopDetail(main.ref, BreakpointTracker.EventKind.EXCEPTION, null,
			"java.lang.IllegalStateException: Insufficient stock (thrown at Inventory:12, uncaught)");
		publish(new BreakpointTracker.LastBreakpoint(main.ref, null, BreakpointTracker.EventKind.EXCEPTION),
			EventRequest.SUSPEND_EVENT_THREAD);
		assertThat(tools.jdwp_resume_until_event(1_000))
			.contains("via=exception, java.lang.IllegalStateException: Insufficient stock (thrown at Inventory:12, "
				+ "uncaught)");
	}

	@Test
	@DisplayName("a stop names the logpoint hits recorded since the last jdwp_get_events (#50)")
	void shouldNameUnreadLogpointHits() {
		eventHistory.record(new EventHistory.DebugEvent("FIELD_LOGPOINT", "Field X.y modification"));
		eventHistory.record(new EventHistory.DebugEvent("FIELD_LOGPOINT", "Field X.y modification"));
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);

		assertThat(tools.jdwp_resume_until_event(1_000)).contains("breakpoint=11")
			.endsWith("Recorded since your last jdwp_get_events: 2 FIELD_LOGPOINT.");
	}

	@Test
	@DisplayName("a detail recorded for another stop of the thread is not shown")
	void staleDetailIsNotShown() {
		breakpointTracker.recordStopDetail(main.ref, BreakpointTracker.EventKind.BREAKPOINT, 4, "Config.timeout modification");
		publish(new BreakpointTracker.LastBreakpoint(main.ref, null, BreakpointTracker.EventKind.EXCEPTION),
			EventRequest.SUSPEND_EVENT_THREAD);
		assertThat(tools.jdwp_resume_until_event(1_000)).contains("via=exception").doesNotContain("Config.timeout");
	}

	@Test
	@DisplayName("[VM_DEATH] hint lists deferred BPs that failed to install, and omits the block when none did")
	void shouldListFailedPendingBpsInVmDeathHint() {
		session.close();
		assertThat(tools.jdwp_resume_until_event(1_000)).startsWith("[VM_DEATH]")
			.doesNotContain("deferred breakpoint(s) were promoted but failed");

		final int pendingId = breakpointTracker.registerPendingBreakpoint("com.example.Foo", 40, 2, "ALL");
		breakpointTracker.markPendingFailed(pendingId, "No executable code at line 40");
		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_DEATH]")
			.contains("deferred breakpoint(s) were promoted but failed")
			.contains("#" + pendingId)
			.contains("com.example.Foo:40")
			.contains("No executable code at line 40")
			.contains("Set BPs on real executable lines");
	}

	@Test
	@DisplayName("after VM is gone: [VM_GONE] envelope (never 'Error: null')")
	void shouldReturnVmGoneEnvelopeWhenNotConnected() throws Exception {
		when(jdiService.getVM()).thenThrow(new IllegalStateException("Not connected"));

		assertThat(tools.jdwp_resume_until_event(1_000)).startsWith("[VM_GONE]")
			.contains("jdwp_resume_until_event").contains("jdwp_connect");
	}

	@Test
	@DisplayName("after VM is gone with null-message exception: envelope falls back to class name")
	void shouldRenderClassNameWhenExceptionMessageIsNull() throws Exception {
		when(jdiService.getVM()).thenThrow(new RuntimeException((String) null));

		assertThat(tools.jdwp_resume_until_event(1_000)).startsWith("Error in jdwp_resume_until_event")
			.doesNotContain(": null").contains("RuntimeException");
	}

	@Test
	@DisplayName("VMDisconnectedException, SocketException and 'closed by the remote host' route through [VM_GONE]")
	void shouldRouteTransportLossThroughVmGoneEnvelope() throws Exception {
		doThrow(new VMDisconnectedException("connection is closed")).when(jdiService).getVM();
		assertThat(tools.jdwp_resume_until_event(1_000)).startsWith("[VM_GONE]").contains("connection is closed");

		doThrow(new RuntimeException(new SocketException("Connection reset"))).when(jdiService).getVM();
		assertThat(tools.jdwp_resume_until_event(1_000)).startsWith("[VM_GONE]").contains("jdwp_resume_until_event");

		doThrow(new RuntimeException("Connection closed by the remote host")).when(jdiService).getVM();
		assertThat(tools.jdwp_resume_until_event(1_000)).startsWith("[VM_GONE]");
	}
}
