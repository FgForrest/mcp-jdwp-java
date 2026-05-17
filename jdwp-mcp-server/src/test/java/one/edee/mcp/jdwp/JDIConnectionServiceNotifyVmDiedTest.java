package one.edee.mcp.jdwp;

import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link JDIConnectionService#notifyVmDied()} — the post-mortem cleanup hook the
 * {@link JdiEventListener} invokes when the target VM emits a {@code VMDeathEvent} /
 * {@code VMDisconnectEvent} (or {@code queue.remove()} throws {@code VMDisconnectedException}).
 *
 * <p>The four behaviour pillars verified here:
 * <ul>
 *   <li><b>Clears VM and session caches</b> — the {@code vm} reference is nulled, the breakpoint
 *       tracker is reset, the watcher manager is cleared, the object cache is emptied, and the
 *       cached classpath / JDK path / target major version are wiped so the next attach starts
 *       from a clean slate.</li>
 *   <li><b>Preserves auto-reconnect seed</b> — {@code lastHost} / {@code lastPort} stay populated
 *       so {@link JDIConnectionService#getVM()} can transparently reattach when the target VM
 *       comes back (e.g., during a restart-cycle debugging session).</li>
 *   <li><b>Preserves event history</b> — the {@code VM_DEATH} entry the listener just recorded
 *       must stay visible via {@code jdwp_get_events} and {@code jdwp_diagnose}.</li>
 *   <li><b>Idempotent and failure-tolerant</b> — a no-op when {@code vm == null}; a second call
 *       after the first has nulled the field is also a no-op; an exception from {@code vm.dispose()}
 *       is swallowed so the cleanup path never propagates a failure that originates from the
 *       (already-dying) target VM.</li>
 * </ul>
 */
@DisplayName("JDIConnectionService.notifyVmDied")
class JDIConnectionServiceNotifyVmDiedTest {

	@Nested
	@DisplayName("Cache cleanup and collaborator reset")
	class CleanupBehaviour {

		@Test
		@DisplayName("nulls the VM and resets every session-bound collaborator")
		void shouldClearVmAndSessionCachesWhenVmAlive() throws Exception {
			final BreakpointTracker tracker = mock(BreakpointTracker.class);
			final WatcherManager watchers = mock(WatcherManager.class);
			final EventHistory history = new EventHistory();
			final EvaluationGuard guard = new EvaluationGuard();
			final JdiEventListener listener = mock(JdiEventListener.class);
			final JDIConnectionService service = JDIConnectionServiceTestSupport
				.newServiceWithCollaborators(listener, tracker, history, watchers, guard);

			final VirtualMachine vm = mock(VirtualMachine.class);
			JDIConnectionServiceTestSupport.setVm(service, vm);

			service.notifyVmDied();

			verify(vm, times(1)).dispose();
			verify(tracker, times(1)).reset();
			verify(watchers, times(1)).clearAll();
			// After the hook runs, any caller that needs the VM should fail — vm is null and there
			// is no seeded lastHost/lastPort because we never went through connect(). The failure
			// message points the user back to jdwp_connect rather than silently retrying.
			assertThatThrownBy(service::getVM)
				.hasMessageContaining("jdwp_connect");
		}

		@Test
		@DisplayName("drops the object cache so stale ObjectReferences are not handed out")
		void shouldDropObjectCacheOnVmDeath() {
			final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithMocks();
			JDIConnectionServiceTestSupport.setVm(service, mock(VirtualMachine.class));
			// Seed an object reference so we can prove the cache is cleared.
			final com.sun.jdi.ObjectReference cached = mock(com.sun.jdi.ObjectReference.class);
			org.mockito.Mockito.when(cached.uniqueID()).thenReturn(42L);
			service.cacheObject(cached);
			assertThat(service.getCachedObject(42L)).isSameAs(cached);

			service.notifyVmDied();

			assertThat(service.getCachedObject(42L)).isNull();
		}

		@Test
		@DisplayName("clears every registered watcher")
		void shouldClearWatchersOnVmDeath() {
			final WatcherManager watchers = new WatcherManager();
			final BreakpointTracker tracker = new BreakpointTracker();
			final EventHistory history = new EventHistory();
			final EvaluationGuard guard = new EvaluationGuard();
			final JdiEventListener listener = mock(JdiEventListener.class);
			final JDIConnectionService service = JDIConnectionServiceTestSupport
				.newServiceWithCollaborators(listener, tracker, history, watchers, guard);

			JDIConnectionServiceTestSupport.setVm(service, mock(VirtualMachine.class));
			watchers.createWatcher("user.email", 1, "user.getEmail()");
			assertThat(watchers.getAllWatchers()).hasSize(1);

			service.notifyVmDied();

			assertThat(watchers.getAllWatchers()).isEmpty();
		}
	}

	@Nested
	@DisplayName("Preserved state across the death hook")
	class PreservedState {

		@Test
		@DisplayName("preserves lastHost/lastPort so auto-reconnect remains possible")
		void shouldPreserveLastHostAndPortForAutoReconnect() {
			final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithMocks();
			JDIConnectionServiceTestSupport.setVm(service, mock(VirtualMachine.class));
			JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "target-host", 5005);

			service.notifyVmDied();

			// The auto-reconnect target is preserved — getVM() would route through
			// ensureConnected() which uses lastHost/lastPort to re-attach. We do not run the
			// reconnect here (no live JDWP server), but we verify the failure mode is "attach
			// failed" rather than "use jdwp_connect first", which is what proves the seed survived.
			assertThatThrownBy(service::getVM)
				.matches(t -> t.getMessage() == null || !t.getMessage().contains("jdwp_connect"),
					"reconnect target preserved — error must NOT instruct the user to call jdwp_connect");
		}

		@Test
		@DisplayName("preserves event history so VM_DEATH entry stays visible to diagnostics")
		void shouldPreserveEventHistory() {
			final BreakpointTracker tracker = new BreakpointTracker();
			final WatcherManager watchers = new WatcherManager();
			final EventHistory history = new EventHistory();
			final EvaluationGuard guard = new EvaluationGuard();
			final JdiEventListener listener = mock(JdiEventListener.class);
			final JDIConnectionService service = JDIConnectionServiceTestSupport
				.newServiceWithCollaborators(listener, tracker, history, watchers, guard);
			JDIConnectionServiceTestSupport.setVm(service, mock(VirtualMachine.class));
			// Simulate the listener having recorded the death event just before invoking the hook.
			history.record(new EventHistory.DebugEvent("VM_DEATH", "target VM died"));

			service.notifyVmDied();

			assertThat(history.getRecent(10))
				.extracting(EventHistory.DebugEvent::type)
				.contains("VM_DEATH");
		}
	}

	@Nested
	@DisplayName("Idempotence and failure tolerance")
	class IdempotenceAndFailureTolerance {

		@Test
		@DisplayName("is a no-op when vm is already null")
		void shouldBeNoopWhenVmAlreadyNull() {
			final BreakpointTracker tracker = mock(BreakpointTracker.class);
			final WatcherManager watchers = mock(WatcherManager.class);
			final JDIConnectionService service = JDIConnectionServiceTestSupport
				.newServiceWithCollaborators(mock(JdiEventListener.class), tracker,
					new EventHistory(), watchers, new EvaluationGuard());

			// vm field is null by default — no setVm() call.
			service.notifyVmDied();

			// No collaborator should be touched: the early return short-circuits everything.
			verify(tracker, never()).reset();
			verify(watchers, never()).clearAll();
		}

		@Test
		@DisplayName("is idempotent across two consecutive calls")
		void shouldBeIdempotentAcrossTwoCalls() {
			final BreakpointTracker tracker = mock(BreakpointTracker.class);
			final WatcherManager watchers = mock(WatcherManager.class);
			final JDIConnectionService service = JDIConnectionServiceTestSupport
				.newServiceWithCollaborators(mock(JdiEventListener.class), tracker,
					new EventHistory(), watchers, new EvaluationGuard());

			final VirtualMachine vm = mock(VirtualMachine.class);
			JDIConnectionServiceTestSupport.setVm(service, vm);

			service.notifyVmDied();
			service.notifyVmDied();

			// First call performs the cleanup; second call short-circuits at the vm == null guard.
			verify(vm, times(1)).dispose();
			verify(tracker, times(1)).reset();
			verify(watchers, times(1)).clearAll();
		}

		@Test
		@DisplayName("swallows exceptions from vm.dispose() and still completes the cleanup")
		void shouldSwallowDisposeException() {
			final BreakpointTracker tracker = mock(BreakpointTracker.class);
			final WatcherManager watchers = mock(WatcherManager.class);
			final JDIConnectionService service = JDIConnectionServiceTestSupport
				.newServiceWithCollaborators(mock(JdiEventListener.class), tracker,
					new EventHistory(), watchers, new EvaluationGuard());

			final VirtualMachine vm = mock(VirtualMachine.class);
			// A dying VM is the whole reason we're here — JDI calls against it may explode.
			doThrow(new VMDisconnectedException("already disconnected")).when(vm).dispose();
			JDIConnectionServiceTestSupport.setVm(service, vm);

			// The hook MUST NOT propagate the dispose failure — the listener thread relies on this
			// not throwing so its own VM-death bookkeeping (history recording, latch firing) can
			// finish cleanly even when the target VM is already gone.
			service.notifyVmDied();

			// Despite dispose() failing, the rest of the cleanup still ran.
			verify(tracker, times(1)).reset();
			verify(watchers, times(1)).clearAll();
		}

	}

	@Nested
	@DisplayName("Disconnect / reconnect lifecycle invariants")
	class LifecycleInvariants {

		@Test
		@DisplayName("disconnect() clears lastHost/lastPort so a subsequent getVM() does NOT silently reconnect")
		void shouldClearReconnectSeedAfterExplicitDisconnect() {
			// Explicit disconnect() is the user-initiated path: cleanupSessionState() wipes the
			// auto-reconnect seed so a follow-up tool call surfaces "Use jdwp_connect first"
			// rather than silently re-attaching to the just-released target. The
			// listener-initiated death path (notifyVmDied) is the only place the seed is
			// intentionally preserved, for restart-cycle debugging.
			final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithRealListener();
			JDIConnectionServiceTestSupport.setVm(service, mock(VirtualMachine.class));
			JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "127.0.0.1", 1);

			final String status = service.disconnect();

			assertThat(status).isEqualTo("Disconnected");
			assertThatThrownBy(service::getVM)
				.hasMessageContaining("jdwp_connect");
		}

		@Test
		@DisplayName("connect() to the same host/port while attached is a no-op and reports 'Already connected'")
		void shouldNoopWhenReconnectingToSameTarget() throws Exception {
			final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithRealListener();
			final VirtualMachine vm = mock(VirtualMachine.class);
			org.mockito.Mockito.when(vm.name()).thenReturn("mock-vm");
			JDIConnectionServiceTestSupport.setVm(service, vm);
			JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "same-host", 5005);

			final String result = service.connect("same-host", 5005);

			assertThat(result).startsWith("Already connected to ");
			// Same target — no teardown should have happened.
			verify(vm, never()).dispose();
		}

		@Test
		@DisplayName("connect() to a different host/port releases the current session before re-attaching")
		void shouldReleaseCurrentSessionWhenTargetChanges() {
			// connect(newHost, newPort) called while attached to a different target must not
			// silently keep the old session — it must run cleanupSessionState() so a fresh
			// attach can take over. The new attach itself will fail here (no live JDWP server
			// on the test target), but the teardown of the OLD session is what we verify.
			final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithRealListener();
			final VirtualMachine vm = mock(VirtualMachine.class);
			org.mockito.Mockito.when(vm.name()).thenReturn("mock-vm");
			JDIConnectionServiceTestSupport.setVm(service, vm);
			JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "old-host", 5005);

			assertThatThrownBy(() -> service.connect("new-host", 9999))
				.isInstanceOf(Exception.class);

			// The old session was released: dispose() was called on the previous mock VM.
			verify(vm, times(1)).dispose();
		}

		@Test
		@DisplayName("getConnectionStatus() on a dead VM is a pure read and does not mutate the vm field")
		void shouldNotMutateVmFieldWhenStatusProbeFindsDeadVm() throws Exception {
			// A read-only diagnostic must not mutate state — even when the probe finds the VM
			// dead. Mutation is now the responsibility of the explicit code paths (connect /
			// ensureConnected) that decide what to do with the dead handle.
			final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithMocks();
			final VirtualMachine deadVm = mock(VirtualMachine.class);
			org.mockito.Mockito.when(deadVm.name()).thenThrow(new VMDisconnectedException());
			JDIConnectionServiceTestSupport.setVm(service, deadVm);

			final JDIConnectionService.ConnectionStatus first = service.getConnectionStatus();
			assertThat(first.connected()).isFalse();

			// Read the private vm field via reflection — it must still reference deadVm because
			// the status call is pure. (If the field had been nulled as a side effect, name()
			// would never have been called on the dead handle on the second status probe; we
			// assert the stronger invariant: the field reference is unchanged.)
			final java.lang.reflect.Field vmField = JDIConnectionService.class.getDeclaredField("vm");
			vmField.setAccessible(true);
			assertThat(vmField.get(service)).isSameAs(deadVm);

			final JDIConnectionService.ConnectionStatus second = service.getConnectionStatus();
			assertThat(second.connected()).isFalse();
			assertThat(vmField.get(service)).isSameAs(deadVm);
		}
	}

}
