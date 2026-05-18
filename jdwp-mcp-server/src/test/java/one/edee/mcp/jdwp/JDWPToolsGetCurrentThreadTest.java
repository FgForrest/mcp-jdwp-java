package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the render contract of {@link JDWPTools#jdwp_get_current_thread}: when a {@code
 * LastBreakpoint} snapshot is published the renderer must surface the correct {@code via=} tag
 * for STEP / EXCEPTION snapshots (F-RA2), never echo a stale BP id, and annotate snapshots whose
 * thread has resumed since the snapshot was captured.
 */
@DisplayName("jdwp_get_current_thread")
class JDWPToolsGetCurrentThreadTest {

	private BreakpointTracker breakpointTracker;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
	}

	private ThreadReference suspendedThread(String name, long id, int frameCount) throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn(name);
		when(thread.uniqueID()).thenReturn(id);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(frameCount);
		return thread;
	}

	@Test
	@DisplayName("returns 'No current breakpoint detected' when no snapshot is published")
	void shouldReturnNoCurrentBreakpointMessageWhenSnapshotIsNull() {
		when(breakpointTracker.getLastBreakpoint()).thenReturn(null);

		final String result = tools.jdwp_get_current_thread();

		assertThat(result).startsWith("No current breakpoint detected");
	}

	@Test
	@DisplayName("renders BREAKPOINT snapshot with 'breakpoint=N'")
	void shouldRenderBreakpointKindWithBpId() throws Exception {
		final ThreadReference thread = suspendedThread("main", 1L, 3);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, 11));

		final String result = tools.jdwp_get_current_thread();

		assertThat(result).contains("main");
		assertThat(result).contains("ID=1");
		assertThat(result).contains("breakpoint=11");
		assertThat(result).contains("suspended=true");
		assertThat(result).contains("frames=3");
		assertThat(result).doesNotContain("via=");
	}

	/**
	 * F-RA2: a STEP snapshot carries no BP id and must render as {@code via=step} — the previous
	 * code echoed the last hit BP id ({@code breakpoint=N}) even though the step landed away
	 * from any BP.
	 */
	@Test
	@DisplayName("F-RA2: STEP snapshot renders 'via=step' instead of 'breakpoint=N'")
	void shouldRenderStepKindAsViaStep() throws Exception {
		final ThreadReference thread = suspendedThread("main", 1L, 3);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(
				thread, null, BreakpointTracker.EventKind.STEP));

		final String result = tools.jdwp_get_current_thread();

		assertThat(result).contains("via=step");
		assertThat(result).doesNotContain("breakpoint=");
	}

	/**
	 * F-RA2: an EXCEPTION snapshot renders as {@code via=exception}. Mirror of the STEP case.
	 */
	@Test
	@DisplayName("F-RA2: EXCEPTION snapshot renders 'via=exception'")
	void shouldRenderExceptionKindAsViaException() throws Exception {
		final ThreadReference thread = suspendedThread("worker", 2L, 5);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(
				thread, null, BreakpointTracker.EventKind.EXCEPTION));

		final String result = tools.jdwp_get_current_thread();

		assertThat(result).contains("via=exception");
		assertThat(result).doesNotContain("breakpoint=");
	}

	/**
	 * F-RA2 back-compat: the legacy {@code id == -1} sentinel for exception suspensions is
	 * auto-upgraded to {@link BreakpointTracker.EventKind#EXCEPTION} by the {@code LastBreakpoint}
	 * compact constructor, so any in-flight code path still using the sentinel form continues to
	 * render correctly.
	 */
	@Test
	@DisplayName("F-RA2: legacy id=-1 sentinel renders 'via=exception' via the compact constructor")
	void shouldRenderMinusOneSentinelAsViaExceptionViaCompactConstructor() throws Exception {
		final ThreadReference thread = suspendedThread("worker", 2L, 5);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, -1));

		final String result = tools.jdwp_get_current_thread();

		assertThat(result).contains("via=exception");
		assertThat(result).doesNotContain("breakpoint=");
	}

	@Test
	@DisplayName("appends 'stale — thread has resumed' note when the snapshot thread is running")
	void shouldAppendStaleNoteWhenThreadHasResumed() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("main");
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(false);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, 11));

		final String result = tools.jdwp_get_current_thread();

		assertThat(result).contains("suspended=false");
		assertThat(result).contains("frames=N/A");
		assertThat(result).contains("(stale");
		assertThat(result).contains("thread has resumed");
	}

	@Test
	@DisplayName("returns 'Error: ...' envelope when the tracker throws")
	void shouldReturnErrorEnvelopeWhenTrackerThrows() {
		when(breakpointTracker.getLastBreakpoint())
			.thenThrow(new RuntimeException("tracker boom"));

		final String result = tools.jdwp_get_current_thread();

		assertThat(result).startsWith("Error:");
		assertThat(result).contains("tracker boom");
	}
}
