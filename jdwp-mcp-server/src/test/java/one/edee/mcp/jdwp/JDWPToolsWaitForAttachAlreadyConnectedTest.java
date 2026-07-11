package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link JDWPTools#jdwp_wait_for_attach} does not mislabel an already-connected session
 * as a fresh VM_START attach. {@code connect()}'s "Already connected" fast-path returns a
 * pre-existing, mid-execution session — appending the "suspended at VM_START — land at a real BP
 * first" guidance to it is wrong (and actively misleading after a same-port target restart, where
 * the fast-path can hand back a stale handle). A genuine fresh attach still carries the guidance.
 */
@DisplayName("jdwp_wait_for_attach — already-connected fast-path")
class JDWPToolsWaitForAttachAlreadyConnectedTest {

	private JDIConnectionService jdiService;
	private JvmDiscoveryService discovery;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		discovery = mock(JvmDiscoveryService.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(),
			new EvaluationGuard(), discovery);
	}

	@Test
	@DisplayName("'Already connected' fast-path is not labelled as VM_START")
	void shouldNotClaimVmStartOnAlreadyConnected() throws Exception {
		when(jdiService.connect(Mockito.anyString(), Mockito.anyInt()))
			.thenReturn("Already connected to OpenJDK 64-Bit Server VM");

		final String result = tools.jdwp_wait_for_attach("localhost", 5005, 5000);

		assertThat(result)
			.contains("Already connected")
			// The defect: the false suspension-state claim must not be appended to a pre-existing session.
			.doesNotContain("suspended at VM_START");
		// The recovery path for a same-port restart is surfaced instead.
		assertThat(result).contains("jdwp_diagnose");
		verify(discovery, never()).discover();
	}

	@Test
	@DisplayName("genuine fresh attach still carries the VM_START guidance")
	void shouldKeepVmStartGuidanceOnFreshAttach() throws Exception {
		when(jdiService.connect(Mockito.anyString(), Mockito.anyInt()))
			.thenReturn("Connected to OpenJDK 64-Bit Server VM (version 21)");

		final String result = tools.jdwp_wait_for_attach("localhost", 5005, 5000);

		assertThat(result).contains("VM_START");
		verify(discovery, never()).discover();
	}
}
