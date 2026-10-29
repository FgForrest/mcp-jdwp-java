package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the snapshot-before-wipe contract of {@link JDIConnectionService#disconnect()} — the
 * teardown-communication follow-up to the session boundary work (issue #25). The returned
 * {@link JDIConnectionService.DisconnectResult} must capture what session state existed
 * <em>before</em> {@code cleanupSessionState()} zeroes it; otherwise the tool layer would always
 * report zero counts and a vanished breakpoint/watcher/mark set would become a silent surprise.
 *
 * <p>The sibling {@link JDIConnectionServiceMarkLifecycleTest} only asserts {@code wasConnected()}
 * and post-wipe emptiness — it would still pass if the snapshot were taken after the wipe. These
 * tests pin the ordering: the result reflects the pre-wipe counts <em>and</em> the collaborators
 * are emptied afterward. Object-cache / event-history population is deliberately out of scope here
 * (wiring those on the real service is disproportionately heavy); watchers and marks are enough to
 * prove the capture order for the at-risk categories.
 */
@DisplayName("JDIConnectionService.disconnect() — snapshot before wipe")
class JDIConnectionServiceDisconnectSnapshotTest {

	/**
	 * Builds a live {@link ObjectReference} mock so the registry accepts the mark on a VM that
	 * advertises collection support.
	 */
	private ObjectReference liveRef(long id, String typeName) {
		final ObjectReference r = mock(ObjectReference.class);
		when(r.uniqueID()).thenReturn(id);
		when(r.isCollected()).thenReturn(false);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(typeName);
		when(r.referenceType()).thenReturn(type);
		return r;
	}

	@Test
	@DisplayName("counts reflect pre-wipe state while collaborators are emptied afterward")
	void shouldSnapshotClearedCountsBeforeWipe() {
		final MarkedInstanceRegistry registry = new MarkedInstanceRegistry();
		registry.mark("a", liveRef(1L, "T"), false);
		registry.mark("b", liveRef(2L, "T"), true);

		final WatcherManager watchers = new WatcherManager();
		watchers.createWatcher("w1", 1, "x");
		watchers.createWatcher("w2", 1, "y");

		// Use a real listener so cleanupSessionState() can call eventListener.stop() without NPE.
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final EvaluationGuard guard = new EvaluationGuard();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final JdiEventListener listener =
			new JdiEventListener(tracker, history, evaluator, guard, null, new MarkedInstanceRegistry());
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			listener, tracker, history, watchers, guard, registry);

		final VirtualMachine vm = mock(VirtualMachine.class);
		final EventRequestManager erm = mock(EventRequestManager.class);
		when(vm.eventRequestManager()).thenReturn(erm);
		JDIConnectionServiceTestSupport.setVm(service, vm);
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "127.0.0.1", 9);

		final JDIConnectionService.DisconnectResult result = service.disconnect();

		// The snapshot reflects what existed BEFORE the wipe.
		assertThat(result.wasConnected()).isTrue();
		assertThat(result.host()).isEqualTo("127.0.0.1");
		assertThat(result.port()).isEqualTo(9);
		assertThat(result.watchers()).isEqualTo(2);
		assertThat(result.markedInstances()).isEqualTo(2);
		// And the collaborators were actually emptied — proving the snapshot was taken first.
		assertThat(watchers.getAllWatchers()).isEmpty();
		assertThat(registry.list()).isEmpty();
	}

	@Test
	@DisplayName("not-connected is an all-zeros no-op that leaves collaborators untouched")
	void shouldReturnAllZerosNoOpWhenNotConnected() {
		final MarkedInstanceRegistry registry = new MarkedInstanceRegistry();
		registry.mark("a", liveRef(1L, "T"), false);
		final WatcherManager watchers = new WatcherManager();
		watchers.createWatcher("w1", 1, "x");

		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			mock(JdiEventListener.class), new BreakpointTracker(), new EventHistory(),
			watchers, new EvaluationGuard(), registry);
		// VM is null (no setVm call) — disconnect() short-circuits to the no-op result.

		final JDIConnectionService.DisconnectResult result = service.disconnect();

		assertThat(result.wasConnected()).isFalse();
		assertThat(result.host()).isNull();
		assertThat(result.port()).isZero();
		assertThat(result.totalBreakpoints()).isZero();
		assertThat(result.watchers()).isZero();
		assertThat(result.markedInstances()).isZero();
		assertThat(result.objectCacheEntries()).isZero();
		assertThat(result.eventHistoryEntries()).isZero();
		assertThat(result.clearedAnything()).isFalse();
		// The no-op must not touch any collaborator state.
		assertThat(watchers.getAllWatchers()).hasSize(1);
		assertThat(registry.list()).hasSize(1);
	}
}
