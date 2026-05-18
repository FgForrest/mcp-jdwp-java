package one.edee.mcp.jdwp;

import com.sun.jdi.VMDisconnectedException;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the {@code [VM_DEATH]} contract for {@link JDWPTools#jdwp_evaluate_watchers}: when JDI
 * raises {@link VMDisconnectedException} mid-call, the tool must surface the canonical re-attach
 * hint instead of a generic {@code "Error: ..."} prefix.
 */
@DisplayName("jdwp_evaluate_watchers VM death handling")
class JDWPToolsEvaluateWatchersVmDeathTest {

	private JDIConnectionService jdiService;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker tracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
	}

	@Test
	@DisplayName("surfaces VMDisconnectedException as the canonical [VM_DEATH] hint")
	void shouldSurfaceVmDisconnectedAsCanonicalHint() throws Exception {
		when(jdiService.getVM()).thenThrow(new VMDisconnectedException("gone"));

		final String result = tools.jdwp_evaluate_watchers(1L, "current_frame", null);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).contains("jdwp_evaluate_watchers");
		assertThat(result).contains("jdwp_connect");
		assertThat(result).contains("jdwp_wait_for_attach");
	}

	/**
	 * F-RA1: a {@link SocketException} buried in the cause chain of a wrapper exception must be
	 * unwrapped by {@code isVmGone} and rendered as the canonical {@code [VM_DEATH]} envelope.
	 * Without the chain walk the wrapper leaked through and the agent lost the re-attach hint.
	 */
	@Test
	@DisplayName("F-RA1: surfaces wrapped SocketException as the canonical [VM_DEATH] hint")
	void shouldSurfaceSocketExceptionAsCanonicalHint() throws Exception {
		when(jdiService.getVM())
			.thenThrow(new RuntimeException("wrapper", new SocketException("Connection reset")));

		final String result = tools.jdwp_evaluate_watchers(1L, "current_frame", null);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).contains("jdwp_evaluate_watchers");
	}

	/**
	 * F-RA1: when neither {@link SocketException} nor {@link VMDisconnectedException} appears in
	 * the cause chain, the message-substring fallback inside {@code isVmGone} must still classify
	 * a transport-down {@link IOException} as VM death. Pins the "Broken pipe" branch of the
	 * message scan.
	 */
	@Test
	@DisplayName("F-RA1: surfaces 'Broken pipe' IOException as the canonical [VM_DEATH] hint")
	void shouldSurfaceBrokenPipeMessageAsCanonicalHint() throws Exception {
		when(jdiService.getVM()).thenThrow(new IOException("Broken pipe"));

		final String result = tools.jdwp_evaluate_watchers(1L, "current_frame", null);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).contains("jdwp_evaluate_watchers");
	}
}
