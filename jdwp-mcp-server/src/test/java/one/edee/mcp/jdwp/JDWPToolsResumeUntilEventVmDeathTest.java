package one.edee.mcp.jdwp;

import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
		session = breakpointTracker.openSuspensionSession(eventHistory);
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);
	}

	/**
	 * Publishes a stop of {@code main} with {@code policy}, as the dispatcher does once a set is
	 * processed.
	 */
	private SuspensionSession.Context publish(BreakpointTracker.LastBreakpoint stop, int policy) {
		final SuspensionSession.Context context = session.route(model.breakpointEvent(main, policy), main.ref, false);
		session.begin(context);
		session.candidate(context, stop);
		session.finish(context);
		return context;
	}

	@Test
	@DisplayName("returns [VM_DEATH] when the session closed while waiting")
	void shouldReturnVmDeathWhenSessionCloses() throws Exception {
		session.close();

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_DEATH]").contains("jdwp_connect").contains("jdwp_wait_for_attach");
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

		assertThat(waiting.get(5, TimeUnit.SECONDS)).contains("breakpoint=12").contains("Resumed: breakpoint 11 on thread main");
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
	@DisplayName("a stop published just before the VM died is still shown, with the disconnect noted")
	void shouldShowStopPublishedBeforeDeath() {
		publish(new BreakpointTracker.LastBreakpoint(main.ref, 11), EventRequest.SUSPEND_EVENT_THREAD);
		eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected"));
		session.close();

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("Event fired.").contains("breakpoint=11").contains("VM has since disconnected");
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
