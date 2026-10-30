package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link JDWPTools#jdwp_disconnect} reports exactly what session state the call discarded
 * rather than returning a bare "Disconnected" — the teardown-communication follow-up to the session
 * boundary work (issue #25). A vanished breakpoint set must be named in the reply, and the agent
 * must be steered toward jdwp_reconnect when (and only when) specs worth keeping were cleared.
 */
@DisplayName("jdwp_disconnect — cleared-state report")
class JDWPToolsDisconnectReportTest {

	private JDIConnectionService jdiService;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, mock(BreakpointTracker.class), mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class), mock(EventHistory.class), new EvaluationGuard(),
			new JvmDiscoveryService());
	}

	@Test
	@DisplayName("a populated session is itemized and carries the reconnect-preservation hint")
	void shouldItemizeAndHintReconnect() {
		when(jdiService.disconnect()).thenReturn(new JDIConnectionService.DisconnectResult(
			true, "localhost", 5005, 5, 1, 1, 2, 3, 4, 6));

		final String result = tools.jdwp_disconnect();

		assertThat(result)
			.contains("Disconnected from localhost:5005. Cleared all session state:")
			.contains("Breakpoints: 7 (line 5, exception 1, field 1)")
			.contains("Watchers: 2")
			.contains("Marked instances: 3")
			.contains("Event history: 6")
			.contains("Object cache: 4")
			.contains("classpath discovery cache")
			.contains("use jdwp_reconnect");
	}

	@Test
	@DisplayName("a session with nothing set returns a terse one-liner and no reconnect hint")
	void shouldStayTerseWhenNothingWasSet() {
		when(jdiService.disconnect()).thenReturn(new JDIConnectionService.DisconnectResult(
			true, "localhost", 5005, 0, 0, 0, 0, 0, 0, 0));

		final String result = tools.jdwp_disconnect();

		assertThat(result)
			.isEqualTo("Disconnected from localhost:5005. Nothing was left to clear (no breakpoints, watchers or "
				+ "cached state).");
		assertThat(result).doesNotContain("jdwp_reconnect");
	}

	@Test
	@DisplayName("the no-op (not connected) case says so plainly")
	void shouldReportNotConnected() {
		when(jdiService.disconnect()).thenReturn(JDIConnectionService.DisconnectResult.notConnected());

		assertThat(tools.jdwp_disconnect()).isEqualTo("Not connected — nothing to disconnect.");
	}

	@Test
	@DisplayName("after the target exited, the reply says so and names what its exit dropped")
	void shouldReportTheTargetsExit() {
		final JDIConnectionService.VmExit exit = new JDIConnectionService.VmExit(
			Instant.parse("2026-10-30T17:42:10Z"), "localhost", 5005, 2, 0, 1, 1, 0, List.of());
		when(jdiService.disconnect()).thenReturn(new JDIConnectionService.DisconnectResult(
			false, null, 0, 0, 0, 0, 0, 0, 0, 0, exit));

		assertThat(tools.jdwp_disconnect())
			.startsWith("Not connected — the target VM at localhost:5005 exited at ")
			.contains("which ended the session; its 2 breakpoints, 1 field breakpoint and 1 in-target probe went "
				+ "with it")
			.endsWith("Nothing left to disconnect.");
	}

	@Test
	@DisplayName("caches cleared but no breakpoints/watchers → itemized, but the reconnect hint is suppressed")
	void shouldSuppressHintWhenOnlyCachesCleared() {
		// Object cache + event history were populated, but no breakpoints or watchers — there is
		// nothing reconnect would have preserved, so the preservation hint would only add noise.
		when(jdiService.disconnect()).thenReturn(new JDIConnectionService.DisconnectResult(
			true, "localhost", 5005, 0, 0, 0, 0, 0, 4, 6));

		final String result = tools.jdwp_disconnect();

		assertThat(result)
			.contains("Object cache: 4")
			.contains("Event history: 6")
			.doesNotContain("jdwp_reconnect");
	}

	@Test
	@DisplayName("a connected session with no recorded host falls back to a generic target label")
	void shouldFallBackToGenericTargetWhenHostUnknown() {
		// wasConnected but host is null (no recorded attach) — one line BP forces the itemized path.
		when(jdiService.disconnect()).thenReturn(new JDIConnectionService.DisconnectResult(
			true, null, 0, 1, 0, 0, 0, 0, 0, 0));

		final String result = tools.jdwp_disconnect();

		assertThat(result)
			.contains("Disconnected from the target VM. Cleared all session state:")
			.doesNotContain("null:");
	}
}
