package one.edee.mcp.jdwp;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@link JDWPTools#jdwp_reset} reports: the {@code jdwp_suspend_thread} holds it released next
 * to the stops (#48 B3), that released threads may let a test JVM run to its end, and a target that
 * had already exited — whose exit dropped the session's breakpoints and probes — instead of an empty
 * session (#50).
 */
@DisplayName("jdwp_reset — report")
class JDWPToolsResetReportTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		final FastLogpointService fastLogpoints = JDWPToolsTestSupport.fastLogpointsMock();
		when(fastLogpoints.discover(any())).thenReturn("");
		tools = new JDWPTools(jdiService, breakpointTracker, mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class), mock(EventHistory.class), new EvaluationGuard(),
			mock(JvmDiscoveryService.class), new MarkedInstanceRegistry(), new JdiHealthMonitor(),
			JDWPToolsTestSupport.defaultEmptyClasspathProvider(), fastLogpoints);
	}

	@Test
	@DisplayName("a released jdwp_suspend_thread hold is counted next to the stops, and the reply warns the target runs on")
	void countsManualHolds() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(vm.eventRequestManager()).thenReturn(mock(EventRequestManager.class));
		when(jdiService.getVM()).thenReturn(vm);
		final SuspensionSession session = mock(SuspensionSession.class);
		when(session.reset()).thenReturn(new SuspensionSession.ResetResult(0, 1));
		when(breakpointTracker.suspensionSession()).thenReturn(session);

		assertThat(tools.jdwp_reset())
			.startsWith("Reset complete (VM connection preserved).")
			.contains("Stops released:                0 (+1 jdwp_suspend_thread hold)")
			.contains("The released threads run on — a test JVM that was held only by them may now run to its end")
			.endsWith("Next: set breakpoints, then jdwp_resume_until_event.");
	}

	@Test
	@DisplayName("nothing released: no run-on warning")
	void noWarningWithoutReleases() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(vm.eventRequestManager()).thenReturn(mock(EventRequestManager.class));
		when(jdiService.getVM()).thenReturn(vm);

		assertThat(tools.jdwp_reset())
			.isEqualTo("Reset complete (VM connection preserved). Nothing was set. Next: set breakpoints, then "
				+ "jdwp_resume_until_event.");
	}

	@Test
	@DisplayName("after the target exited: says so, names what its exit dropped and points at a new attach")
	void reportsTheTargetsExit() throws Exception {
		when(jdiService.getVM()).thenThrow(new Exception("Not connected"));
		when(jdiService.lastExit()).thenReturn(new JDIConnectionService.VmExit(
			Instant.parse("2026-10-30T17:42:10Z"), "localhost", 5005, 1, 0, 0, 1, 0, List.of()));

		assertThat(tools.jdwp_reset())
			.startsWith("Reset complete — there is no target to reset: the target VM at localhost:5005 exited at ")
			.contains(", and its 1 breakpoint and 1 in-target probe went with it.")
			.doesNotContain("Next: set breakpoints")
			.endsWith("Next: start the target again and attach (jdwp_wait_for_attach).");
	}
}
