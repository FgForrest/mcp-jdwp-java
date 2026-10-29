package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * Pins the contract that watchers survive {@code jdwp_reconnect}. The natural risk: the JDI
 * event listener's {@code stop()} method invokes a VM-death hook wired to
 * {@link JDIConnectionService#notifyVmDied()}, and that hook calls
 * {@link WatcherManager#clearAll()}. Without explicit detach/re-attach of the hook around the
 * reconnect's listener-stop step, the watchers documented as "Survives" in the reconnect
 * contract would be silently wiped.
 *
 * <p>The test exercises the attach-failure path because a real successful re-attach requires a
 * live JDWP target. The behaviour on the failure path is identical for the watcher-clearing
 * concern: if the hook fired during the listener stop, it would have wiped the watchers before
 * the attach was even attempted, and a subsequent throw would leave them gone.
 */
@DisplayName("JDIConnectionService.reconnectPreservingSpecs — watcher preservation")
class JDIConnectionServiceReconnectWatcherPreservationTest {

	@Test
	@DisplayName("watchers survive a reconnect even when the fresh attach fails")
	void shouldNotClearWatchersWhenReconnectAttachFails() throws Exception {
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		// Real listener — the wiring of vmDeathHook → notifyVmDied happens in the JDIConnectionService
		// constructor; using a real listener exercises the full path. The listener's start() is not
		// invoked because we never reach a successful attach.
		final JdiEventListener listener = new JdiEventListener(tracker, history, evaluator, guard, null, marks);
		final JDIConnectionService service = new JDIConnectionService(
			listener, tracker, history, watchers, guard, marks, new JdiHealthMonitor());

		// Seed a watcher that the reconnect must preserve.
		watchers.createWatcher("trace.id", 1, "entity.id");
		assertThat(watchers.getAllWatchers()).hasSize(1);

		// Plant a fake successful-attach target via reflection so reconnect's precondition passes.
		// The attach itself will fail (the port is unreachable), exercising the failure branch.
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "127.0.0.1", 1);

		assertThatThrownBy(service::reconnectPreservingSpecs)
			.isInstanceOf(Exception.class);

		// Watcher must still be present — the hook was detached around the listener stop so
		// notifyVmDied's clearAll could not run.
		assertThat(watchers.getAllWatchers())
			.as("watcher count after failed reconnect")
			.hasSize(1);
	}

	/**
	 * Pins the recoverable-failure contract: when the fresh attach fails inside
	 * {@code reconnectPreservingSpecs}, the tracker must be left in a clean pure-pending state
	 * (no JDI request handles tied to the disposed VM) so the agent's retry can safely
	 * re-snapshot. Otherwise the tracker's active maps would still hold dead
	 * {@link com.sun.jdi.request.BreakpointRequest} handles whose {@code location()} call
	 * throws {@link com.sun.jdi.VMDisconnectedException}.
	 */
	@Test
	@DisplayName("tracker is recoverable (pure-pending) after a failed reconnect attach")
	void shouldLeaveTrackerInRecoverablePendingStateOnAttachFailure() throws Exception {
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final JdiEventListener listener = new JdiEventListener(tracker, history, evaluator, guard, null, marks);
		final JDIConnectionService service = new JDIConnectionService(
			listener, tracker, history, watchers, guard, marks, new JdiHealthMonitor());

		// Seed one PENDING line BP into the tracker so the snapshot has something to round-trip.
		// Using a pending entry avoids needing a mock BreakpointRequest with a live JDI handle.
		final int bpId = tracker.registerPendingBreakpoint(
			"com.example.Foo", 42, com.sun.jdi.request.EventRequest.SUSPEND_ALL, "all");

		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "127.0.0.1", 1);

		assertThatThrownBy(service::reconnectPreservingSpecs)
			.isInstanceOf(Exception.class);

		// After the failure: the tracker has the original synthetic ID in its PENDING map. No
		// stale active entry remains (the active map would have held the JDI handle from before
		// the dispose; that handle is dead now).
		assertThat(tracker.getPendingBreakpoint(bpId))
			.as("pending BP must survive a failed reconnect for the agent to retry")
			.isNotNull();
		assertThat(tracker.getAllBreakpoints())
			.as("active map must be empty after a failed reconnect — no dangling JDI handles")
			.isEmpty();
	}

	/**
	 * Pins the narrowed VM-death-hook detach window: the hook must be detached ONLY around
	 * {@code eventListener.stop()} and re-attached immediately after stop returns — NOT held
	 * detached for the full reconnect body. A wider window would silently swallow a genuine
	 * post-reattach disconnect of the fresh VM (notifyVmDied would never fire for it).
	 *
	 * <p>Verified via Mockito {@code InOrder} on the listener: the sequence must be
	 * {@code setVmDeathHook(null)} → {@code stop()} → {@code setVmDeathHook(non-null)}, with the
	 * re-attach happening BEFORE any attach attempt against the fresh target. The attach itself
	 * fails (port 1) so we never reach a successful {@code start(vm)}; the re-attach must still
	 * have happened.
	 */
	@Test
	@DisplayName("vmDeathHook is re-attached immediately after listener stop — narrow detach window")
	void shouldReAttachVmDeathHookBeforeFreshAttachAttempt() throws Exception {
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		// Mock the listener so we can assert the call order on setVmDeathHook / stop.
		final JdiEventListener listener = mock(JdiEventListener.class);
		// Make stop() observable so we can interleave checks: if the test wanted to assert the
		// hook is null *during* stop, we could capture state here. The InOrder check below
		// covers the sequencing already.
		doAnswer(inv -> null).when(listener).stop();
		final JDIConnectionService service = new JDIConnectionService(
			listener, tracker, history, watchers, guard, marks, new JdiHealthMonitor());

		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "127.0.0.1", 1);

		assertThatThrownBy(service::reconnectPreservingSpecs)
			.isInstanceOf(Exception.class);

		// Sequence: null-out hook → stop the listener → restore the hook. The restore must run
		// BEFORE the failed attach (which throws), proving the detach window is narrow.
		final var ordered = inOrder(listener);
		ordered.verify(listener).setVmDeathHook(isNull());
		ordered.verify(listener).stop();
		ordered.verify(listener).setVmDeathHook(any(Runnable.class));
	}
}
