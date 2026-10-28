package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the publish order of {@code jdwp_set_logpoint} on an already-loaded class: the logpoint
 * expression and condition must be registered before the JDI request is enabled. JDI delivers
 * events the moment a request is enabled; a hit that lands before the expression is known is
 * handled as a plain breakpoint, which on a hot line parks the hitting thread for good.
 */
@DisplayName("jdwp_set_logpoint enables the request last")
class JDWPToolsSetLogpointEnableOrderTest {

	@Test
	@DisplayName("expression and condition are registered before the request is enabled")
	void registersMetadataBeforeEnabling() throws Exception {
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker tracker = mock(BreakpointTracker.class);
		final JDWPTools tools = JDWPToolsTestSupport.newTools(jdiService, tracker, mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			mock(JvmDiscoveryService.class));

		final VirtualMachine vm = mock(VirtualMachine.class);
		final EventRequestManager erm = mock(EventRequestManager.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Location location = mock(Location.class);
		final BreakpointRequest request = mock(BreakpointRequest.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(jdiService.findLoadedClass("com.example.Hot")).thenReturn(type);
		when(type.locationsOfLine(10)).thenReturn(List.of(location));
		when(location.method()).thenReturn(mock(Method.class));
		when(erm.createBreakpointRequest(location)).thenReturn(request);
		when(tracker.registerBreakpoint(request)).thenReturn(5);

		final String reply = tools.jdwp_set_logpoint("com.example.Hot", 10, "x", "x > 1", null);

		assertThat(reply).contains("Logpoint set").contains("ID: 5");
		final InOrder order = inOrder(tracker, request);
		order.verify(tracker).registerBreakpoint(request);
		order.verify(tracker).setLogpointExpression(5, "x");
		order.verify(tracker).setCondition(5, "x > 1");
		order.verify(request).enable();
	}
}
