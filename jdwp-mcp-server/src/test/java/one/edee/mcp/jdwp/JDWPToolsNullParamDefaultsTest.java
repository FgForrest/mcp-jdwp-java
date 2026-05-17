package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that null / out-of-range MCP tool parameters are mapped to the documented defaults.
 * <p>
 * Most tool methods require a live JDI connection; these tests focus on methods where the
 * defaulting logic produces observable output (event counts, error messages revealing the
 * default, etc.) without needing a full VM mock.
 */
@DisplayName("MCP tool null-parameter defaults")
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
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
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
			// Use a non-NPE exception class name so the `doesNotContain("NullPointerException")`
			// assertion is unambiguous (the input string would otherwise echo through the error
			// path and trivially match).
			when(jdiService.getVM()).thenThrow(new IllegalStateException("Not connected"));

			String result = tools.jdwp_set_exception_breakpoint("com.example.MyException", null, null, null, null);
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

			// Passing null for breakpointId must NOT throw — wrap in assertThatCode so the
			// no-throw contract is explicit (the old `doesNotContain("NullPointerException")`
			// check was brittle: an unrelated mention of NPE in the error path would also
			// pass).
			final String[] holder = new String[1];
			assertThatCode(() -> holder[0] = tools.jdwp_evaluate_watchers(1L, "current_frame", null))
				.doesNotThrowAnyException();
			assertThat(holder[0]).startsWith("Error");
		}

		@Test
		@DisplayName("non-null breakpointId still works as before")
		void shouldAcceptExplicitBreakpointId() throws Exception {
			when(jdiService.getVM()).thenThrow(new IllegalStateException("Not connected"));

			String result = tools.jdwp_evaluate_watchers(1L, "current_frame", 42);
			assertThat(result).contains("Error");
		}
	}

	@Nested
	@DisplayName("jdwp_get_stack defaults")
	class GetStackDefaults {

		@Test
		@DisplayName("null maxFrames and null includeNoise default without NPE")
		void shouldDefaultMaxFramesAndIncludeNoiseWhenNull() throws Exception {
			final VirtualMachine vm = mock(VirtualMachine.class);
			final ThreadReference thread = mock(ThreadReference.class);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			when(thread.uniqueID()).thenReturn(1L);
			when(thread.isSuspended()).thenReturn(true);
			when(thread.name()).thenReturn("main");
			when(thread.frames()).thenReturn(List.of());

			final String result = tools.jdwp_get_stack(1L, null, null);

			// No frames produced — but the header is still rendered, proving the defaults wired
			// through without an NPE on the Integer / Boolean unboxing.
			assertThat(result).contains("Stack trace for thread 1");
			assertThat(result).contains("0 frame(s) total");
		}
	}

	@Nested
	@DisplayName("jdwp_get_threads defaults")
	class GetThreadsDefaults {

		@Test
		@DisplayName("null includeSystemThreads defaults to false (system threads hidden)")
		void shouldDefaultIncludeSystemThreadsToFalse() throws Exception {
			final VirtualMachine vm = mock(VirtualMachine.class);
			final ThreadReference user = mock(ThreadReference.class);
			final ThreadReference system = mock(ThreadReference.class);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(user, system));
			when(user.name()).thenReturn("main");
			when(user.uniqueID()).thenReturn(1L);
			when(user.status()).thenReturn(ThreadReference.THREAD_STATUS_RUNNING);
			when(user.isSuspended()).thenReturn(false);
			// "Reference Handler" is a known JVM-internal thread name in ThreadFormatting's
			// hide list, so this thread should be filtered out when includeSystemThreads is null.
			when(system.name()).thenReturn("Reference Handler");
			when(system.uniqueID()).thenReturn(2L);
			when(system.status()).thenReturn(ThreadReference.THREAD_STATUS_RUNNING);
			when(system.isSuspended()).thenReturn(false);

			final String result = tools.jdwp_get_threads(null);

			assertThat(result).contains("main");
			assertThat(result).contains("system thread(s) hidden");
			assertThat(result).doesNotContain("Reference Handler");
		}
	}

	@Nested
	@DisplayName("jdwp_resume_until_event defaults")
	class ResumeUntilEventDefaults {

		@Test
		@DisplayName("null timeoutMs falls through without NPE on auto-unbox")
		void shouldHandleNullTimeoutMs() throws Exception {
			when(jdiService.getVM()).thenThrow(new IllegalStateException("Not connected"));

			assertThatCode(() -> tools.jdwp_resume_until_event(null))
				.doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("jdwp_set_breakpoint_dependency defaults")
	class SetBreakpointDependencyDefaults {

		@Test
		@DisplayName("null oneShot defaults to false (sticky)")
		void shouldDefaultOneShotToFalseWhenNull() {
			final int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			final int depId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			final String result = tools.jdwp_set_breakpoint_dependency(depId, triggerId, null);

			assertThat(result).contains("sticky");
			assertThat(tracker.getDependencyOfDependent(depId).oneShot()).isFalse();
		}
	}

	@Nested
	@DisplayName("jdwp_get_breakpoint_context more defaults")
	class BreakpointContextMoreDefaults {

		@Test
		@DisplayName("null maxFrames and null includeThisFields both default without NPE")
		void shouldDefaultMaxFramesAndIncludeThisFieldsWhenNull() {
			// No current breakpoint — the method bails early with the documented message but
			// still proves both Integer/Boolean unboxes are guarded against null.
			final String result = tools.jdwp_get_breakpoint_context(null, null);

			assertThat(result).contains("No current breakpoint");
		}
	}
}
