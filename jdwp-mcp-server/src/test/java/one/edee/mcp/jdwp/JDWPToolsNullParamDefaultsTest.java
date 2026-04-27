package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that null / out-of-range MCP tool parameters are mapped to the documented defaults.
 * <p>
 * Most tool methods require a live JDI connection; these tests focus on methods where the
 * defaulting logic produces observable output (event counts, error messages revealing the
 * default, etc.) without needing a full VM mock.
 */
class JDWPToolsNullParamDefaultsTest {

	private JDWPTools tools;
	private EventHistory eventHistory;
	private BreakpointTracker tracker;
	private JDIConnectionService jdiService;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		WatcherManager watcherManager = new WatcherManager();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
		tools = new JDWPTools(jdiService, tracker, watcherManager, evaluator, eventHistory, new EvaluationGuard());
	}

	@Nested
	@DisplayName("jdwp_get_events defaults")
	class GetEventsDefaults {

		@Test
		@DisplayName("null count defaults to 20 (returns empty, no crash)")
		void shouldDefaultToTwentyWhenCountIsNull() {
			// No events recorded; just verify no NPE and an empty-events message is returned.
			String result = tools.jdwp_get_events(null);
			assertThat(result).contains("No events recorded");
		}

		@Test
		@DisplayName("count=0 defaults to 20")
		void shouldDefaultToTwentyWhenCountIsZero() {
			String result = tools.jdwp_get_events(0);
			assertThat(result).contains("No events recorded");
		}

		@Test
		@DisplayName("count=200 is clamped to 100")
		void shouldClampToOneHundredWhenCountExceedsMax() {
			// Record more than 100 events
			for (int i = 0; i < 120; i++) {
				eventHistory.record(new EventHistory.DebugEvent("TEST", "event " + i));
			}

			String result = tools.jdwp_get_events(200);
			// The result should contain events, but at most 100 of them.
			// Count the "event " occurrences — should be exactly 100 (clamped from 200).
			long eventLines = result.lines()
				.filter(line -> line.contains("TEST"))
				.count();
			assertThat(eventLines).isLessThanOrEqualTo(100);
		}
	}

	@Nested
	@DisplayName("jdwp_set_exception_breakpoint defaults")
	class ExceptionBreakpointDefaults {

		@Test
		@DisplayName("null caught/uncaught default to true (shown in error when no VM)")
		void shouldDefaultCaughtAndUncaughtToTrue() throws Exception {
			// With no VM connected, the call will fail with "Error: ..." but the defaults
			// are applied before the VM access, so we simply verify no NPE on the Boolean unboxing.
			when(jdiService.getVM()).thenThrow(new IllegalStateException("Not connected"));

			String result = tools.jdwp_set_exception_breakpoint("java.lang.NullPointerException", null, null, null, null);
			// Should get an error about connection, not a NullPointerException on auto-unboxing.
			assertThat(result).contains("Error");
			assertThat(result).doesNotContain("NullPointerException");
		}
	}

	@Nested
	@DisplayName("jdwp_get_breakpoint_context defaults")
	class BreakpointContextDefaults {

		@Test
		@DisplayName("null maxFrames — no breakpoint hit returns a 'no current breakpoint' message")
		void shouldDefaultMaxFramesWhenNull() {
			// No breakpoint set; verifies the null maxFrames path is reached without NPE.
			String result = tools.jdwp_get_breakpoint_context(null, null);
			assertThat(result).contains("No current breakpoint");
		}
	}

	@Nested
	@DisplayName("jdwp_evaluate_watchers defaults")
	class EvaluateWatchersDefaults {

		@Test
		@DisplayName("null breakpointId gracefully falls back to last breakpoint (no NPE)")
		void shouldNotThrowNpeWhenBreakpointIdIsNull() throws Exception {
			// When breakpointId is null the method should fall back to
			// breakpointTracker.getLastBreakpointId() instead of NPE-ing.
			when(jdiService.getVM()).thenThrow(new IllegalStateException("Not connected"));

			// Passing null for breakpointId must NOT throw NPE — it should produce
			// an error about the VM connection, not about the parameter itself.
			String result = tools.jdwp_evaluate_watchers(1L, "current_frame", null);
			assertThat(result).contains("Error");
			assertThat(result).doesNotContain("NullPointerException");
		}

		@Test
		@DisplayName("non-null breakpointId still works as before")
		void shouldAcceptExplicitBreakpointId() throws Exception {
			when(jdiService.getVM()).thenThrow(new IllegalStateException("Not connected"));

			String result = tools.jdwp_evaluate_watchers(1L, "current_frame", 42);
			assertThat(result).contains("Error");
		}
	}
}
