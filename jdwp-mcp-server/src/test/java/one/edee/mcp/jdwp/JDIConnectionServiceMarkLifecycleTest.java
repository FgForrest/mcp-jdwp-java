package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the lifecycle invariants of marked instances against {@link JDIConnectionService}.
 *
 * <p>Marks must share the session lifecycle of the object cache that they ride on top of: when the
 * VM dies, the user disconnects, or the user explicitly switches targets, every mark must be
 * dropped so a stale {@code $label} cannot point at a dead object across sessions. The registry
 * identity itself, however, is preserved (Spring singleton) so other tools that hold a reference
 * to it continue to work.
 *
 * <p>Five pins:
 * <ul>
 *   <li>{@code notifyVmDied()} clears marks and releases pins.</li>
 *   <li>{@code disconnect()} (user-initiated) clears marks and releases pins.</li>
 *   <li>{@code disconnect()} when not connected is a no-op (does NOT call clearAll).</li>
 *   <li>{@code connect()} to a different host releases the prior session's marks before re-attaching.</li>
 *   <li>The registry instance identity survives the lifecycle event — the field is reused, not
 *       replaced, so consumers holding a reference (e.g. {@link JDWPTools}) keep working.</li>
 * </ul>
 */
@DisplayName("JDIConnectionService — marked-instance lifecycle")
class JDIConnectionServiceMarkLifecycleTest {

	/**
	 * Builds a live {@link ObjectReference} mock recording pin / unpin so the test can assert that
	 * {@link ObjectReference#enableCollection()} was called on session teardown.
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
	@DisplayName("notifyVmDied() clears every mark and releases pins on a real registry")
	void shouldClearMarksOnVmDeath() {
		final MarkedInstanceRegistry registry = new MarkedInstanceRegistry();
		final ObjectReference pinned = liveRef(1L, "T");
		final ObjectReference unpinned = liveRef(2L, "T");
		registry.mark("p", pinned, true);
		registry.mark("u", unpinned, false);
		assertThat(registry.list()).hasSize(2);

		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			mock(JdiEventListener.class), new BreakpointTracker(), new EventHistory(),
			new WatcherManager(), new EvaluationGuard(), registry);
		JDIConnectionServiceTestSupport.setVm(service, mock(VirtualMachine.class));

		service.notifyVmDied();

		assertThat(registry.list()).isEmpty();
		// Pinned object: collection was re-enabled. Verify via Mockito directly.
		org.mockito.Mockito.verify(pinned).enableCollection();
		// Unpinned object: never had collection disabled, so enableCollection MUST NOT be called.
		org.mockito.Mockito.verify(unpinned, org.mockito.Mockito.never()).enableCollection();
	}

	@Test
	@DisplayName("disconnect() clears every mark — user-initiated path also wipes the registry")
	void shouldClearMarksOnExplicitDisconnect() throws Exception {
		final MarkedInstanceRegistry registry = new MarkedInstanceRegistry();
		registry.mark("p", liveRef(1L, "T"), true);
		assertThat(registry.list()).hasSize(1);

		// Use a real listener so the disconnect path (which calls eventListener.stop()) does not
		// blow up on an unstubbed mock. Then swap in our own registry-bearing collaborators.
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator evaluator =
			mock(one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator.class);
		final JdiEventListener listener =
			new JdiEventListener(tracker, history, evaluator, guard, null, new MarkedInstanceRegistry());
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			listener, tracker, history, watchers, guard, registry);

		final VirtualMachine vm = mock(VirtualMachine.class);
		final EventRequestManager erm = mock(EventRequestManager.class);
		when(vm.eventRequestManager()).thenReturn(erm);
		JDIConnectionServiceTestSupport.setVm(service, vm);
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "127.0.0.1", 1);

		final String result = service.disconnect();

		assertThat(result).isEqualTo("Disconnected");
		assertThat(registry.list()).isEmpty();
	}

	@Test
	@DisplayName("disconnect() when not connected → no-op, registry untouched")
	void shouldNotClearMarksWhenNotConnected() {
		final MarkedInstanceRegistry registry = new MarkedInstanceRegistry();
		registry.mark("p", liveRef(1L, "T"), false);

		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			mock(JdiEventListener.class), new BreakpointTracker(), new EventHistory(),
			new WatcherManager(), new EvaluationGuard(), registry);
		// VM is null (no setVm call).

		final String result = service.disconnect();

		assertThat(result).isEqualTo("Not connected");
		// disconnect() short-circuits when vm is null, so the registry should still hold the mark.
		assertThat(registry.list()).hasSize(1);
	}

	@Test
	@DisplayName("connect() to a different host releases the prior session's marks before re-attaching")
	void shouldClearMarksWhenSwitchingTarget() {
		final MarkedInstanceRegistry registry = new MarkedInstanceRegistry();
		registry.mark("old", liveRef(1L, "T"), true);

		// Use a real listener so cleanupSessionState() can call eventListener.stop() without NPE.
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator evaluator =
			mock(one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator.class);
		final JdiEventListener listener =
			new JdiEventListener(tracker, history, evaluator, guard, null, new MarkedInstanceRegistry());
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			listener, tracker, history, watchers, guard, registry);

		final VirtualMachine vm = mock(VirtualMachine.class);
		final EventRequestManager erm = mock(EventRequestManager.class);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(vm.name()).thenReturn("mock-vm");
		JDIConnectionServiceTestSupport.setVm(service, vm);
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "old-host", 5005);

		// The new attach itself will fail (no live JDWP server on the target). What we verify is
		// that the OLD session's marks are wiped during cleanupSessionState() BEFORE the failed
		// connect attempt happens.
		assertThatThrownBy(() -> service.connect("new-host", 9999))
			.isInstanceOf(Exception.class);

		assertThat(registry.list()).isEmpty();
	}

	@Test
	@DisplayName("registry instance identity is preserved across the lifecycle event")
	void shouldPreserveRegistryInstanceAcrossDeath() throws Exception {
		final MarkedInstanceRegistry registry = new MarkedInstanceRegistry();
		registry.mark("p", liveRef(1L, "T"), true);

		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			mock(JdiEventListener.class), new BreakpointTracker(), new EventHistory(),
			new WatcherManager(), new EvaluationGuard(), registry);
		JDIConnectionServiceTestSupport.setVm(service, mock(VirtualMachine.class));

		service.notifyVmDied();

		// The field must still point at the SAME registry instance — consumers (JDWPTools,
		// JdiEventListener) hold a reference and would break if the service swapped it for a new
		// instance. Verify directly via reflection so the contract is independent of any future
		// getter additions.
		final java.lang.reflect.Field field =
			JDIConnectionService.class.getDeclaredField("markedInstances");
		field.setAccessible(true);
		assertThat(field.get(service)).isSameAs(registry);
		// And the registry is now empty, so a fresh mark would not collide with the prior one.
		assertThat(registry.list()).isEmpty();
		registry.mark("p", liveRef(2L, "T"), false);
		assertThat(registry.list()).hasSize(1);
	}
}
