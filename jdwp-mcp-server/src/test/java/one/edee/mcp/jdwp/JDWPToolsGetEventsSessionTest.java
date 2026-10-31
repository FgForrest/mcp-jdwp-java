package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link JDWPTools#jdwp_get_events} renders the session epoch on each line and draws a
 * divider at a session boundary — the user-facing payoff of session-epoch tagging (issue #25). The
 * scenario mirrors a VM_DEATH preserved across an auto-reconnect: events straddling two sessions in
 * one buffer must read as two segments, not one confusing stream.
 */
@DisplayName("jdwp_get_events — session segmentation")
class JDWPToolsGetEventsSessionTest {

	private EventHistory eventHistory;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			mock(JDIConnectionService.class), mock(BreakpointTracker.class), mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class), eventHistory, new EvaluationGuard(),
			new JvmDiscoveryService());
	}

	@Test
	@DisplayName("events from two sessions are tagged and separated by a divider")
	void shouldSegmentTwoSessions() {
		// Session 1: an exception, then the VM dies (preserved in the buffer).
		eventHistory.beginNewSession(); // s1
		eventHistory.record(new EventHistory.DebugEvent("EXCEPTION", "boom on old VM"));
		eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "old VM died"));
		// Auto-reconnect to a fresh VM bumps the epoch; new events are session 2.
		eventHistory.beginNewSession(); // s2
		eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT", "hit on new VM"));

		final String result = tools.jdwp_get_events(20);

		assertThat(result)
			.contains("current session s2; 2 from earlier attachments, kept until jdwp_reset or jdwp_clear_events")
			.contains("── session s1 (an earlier attachment) ──")
			.contains("[s1] [EXCEPTION]")
			.contains("[s1] [VM_DEATH]")
			.contains("── session s2 (new VM attachment — the current one) ──")
			.contains("[s2] [BREAKPOINT]");
		// the boundary divider appears once
		assertThat(result.lines().filter(l -> l.contains("new VM attachment")).count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a single session renders no divider")
	void shouldNotDrawDividerWithinOneSession() {
		eventHistory.beginNewSession(); // s1
		eventHistory.record(new EventHistory.DebugEvent("VM_START", "started"));
		eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT", "hit"));

		final String result = tools.jdwp_get_events(20);

		assertThat(result)
			.contains("[s1] [VM_START]")
			.contains("[s1] [BREAKPOINT]")
			.doesNotContain("new VM attachment");
	}

	@Test
	@DisplayName("an event timestamped on a whole second renders a full time without collapsing into an error")
	void shouldRenderWholeSecondTimestamp() {
		// Instant.toString() omits the fractional part on a whole-second instant (a 20-char string),
		// so the previous fixed-offset substring threw and collapsed the listing into an "Error:".
		// A length-safe HH:mm:ss.SSS formatter renders the milliseconds explicitly instead.
		eventHistory.record(new EventHistory.DebugEvent(
			java.time.Instant.ofEpochSecond(1000), "VM_START", "started", java.util.Map.of(), 1));

		final String result = tools.jdwp_get_events(20);

		assertThat(result)
			.doesNotContain("Error:")
			.contains("[s1] [VM_START]")
			.contains("00:16:40.000");
	}
}
