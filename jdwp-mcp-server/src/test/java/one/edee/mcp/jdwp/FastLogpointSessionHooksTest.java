package one.edee.mcp.jdwp;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the session-level contracts fast logpoints depend on: the connection service reverts
 * instrumentation <em>before</em> it disposes the VM (dispose does not revert redefinitions) and
 * merely forgets it when the VM died; the tracker's request swap keeps the breakpoint ID and
 * refuses to resurrect a breakpoint cleared concurrently; and fast logpoints share the
 * breakpoint ID space.
 */
@DisplayName("Fast-logpoint session hooks")
class FastLogpointSessionHooksTest {

	@Test
	@DisplayName("disconnect disarms fast logpoints before stopping the listener and disposing the VM")
	void disconnectDisarmsBeforeDispose() {
		final JdiEventListener listener = mock(JdiEventListener.class);
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			listener, new BreakpointTracker(), new EventHistory(), new WatcherManager(), new EvaluationGuard());
		final FastLogpointService fastLogpoints = mock(FastLogpointService.class);
		service.setFastLogpointService(fastLogpoints);
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(vm.eventRequestManager()).thenReturn(mock(EventRequestManager.class));
		JDIConnectionServiceTestSupport.setVm(service, vm);

		service.disconnect();

		// stop() can run the VM-death hook synchronously, which disposes the VM and forgets the
		// instrumentation — so the disarm has to come first.
		final InOrder order = inOrder(fastLogpoints, listener, vm);
		order.verify(fastLogpoints).disarmAll(anyString());
		order.verify(listener).stop();
		order.verify(vm).dispose();
		verify(fastLogpoints, never()).forgetAll();
	}

	@Test
	@DisplayName("VM death only forgets fast-logpoint state")
	void vmDeathForgets() {
		final JDIConnectionService service = JDIConnectionServiceTestSupport.newServiceWithMocks();
		final FastLogpointService fastLogpoints = mock(FastLogpointService.class);
		service.setFastLogpointService(fastLogpoints);
		JDIConnectionServiceTestSupport.setVm(service, mock(VirtualMachine.class));

		service.notifyVmDied();

		verify(fastLogpoints).forgetAll();
		verify(fastLogpoints, never()).disarmAll(anyString());
	}

	@Test
	@DisplayName("replaceBreakpointRequest keeps the ID and updates the reverse index")
	void replaceKeepsId() {
		final BreakpointTracker tracker = new BreakpointTracker();
		final BreakpointRequest before = mock(BreakpointRequest.class);
		final BreakpointRequest after = mock(BreakpointRequest.class);
		final int id = tracker.registerBreakpoint(before);
		tracker.setCondition(id, "x > 1");

		assertThat(tracker.replaceBreakpointRequest(id, before, after)).isTrue();

		assertThat(tracker.getBreakpoint(id)).isSameAs(after);
		assertThat(tracker.findIdByRequest(after)).isEqualTo(id);
		assertThat(tracker.findIdByRequest(before)).isNull();
		assertThat(tracker.getCondition(id)).isEqualTo("x > 1");
	}

	@Test
	@DisplayName("replaceBreakpointRequest does not resurrect a breakpoint cleared meanwhile")
	void replaceRefusesClearedBreakpoint() {
		final BreakpointTracker tracker = new BreakpointTracker();
		final BreakpointRequest before = mock(BreakpointRequest.class);
		when(before.virtualMachine()).thenReturn(mock(VirtualMachine.class));
		final int id = tracker.registerBreakpoint(before);
		tracker.removeBreakpoint(id);

		assertThat(tracker.replaceBreakpointRequest(id, before, mock(BreakpointRequest.class))).isFalse();
		assertThat(tracker.getBreakpoint(id)).isNull();
	}

	@Test
	@DisplayName("allocateId draws from the shared breakpoint ID space")
	void allocateIdSharesSpace() {
		final BreakpointTracker tracker = new BreakpointTracker();
		final int first = tracker.registerBreakpoint(mock(BreakpointRequest.class));
		final int allocated = tracker.allocateId();
		final int next = tracker.registerBreakpoint(mock(BreakpointRequest.class));
		assertThat(allocated).isEqualTo(first + 1);
		assertThat(next).isEqualTo(allocated + 1);
	}
}
