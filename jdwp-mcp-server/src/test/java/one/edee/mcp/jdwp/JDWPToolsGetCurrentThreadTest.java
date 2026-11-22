package one.edee.mcp.jdwp;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InternalException;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Objects;

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
	/** The event history shared by the getters and their suspension sessions. */
	private EventHistory eventHistory;
	private JDWPTools tools;

	/** Wires both getters to the same simulated debugger history. */
	@BeforeEach
	void setUp() {
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
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

	/** Both presenting getters must identify the stop before the step waiter observes it as seen. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void shouldIdentifyThePresentedStopWhenLiveStatusFails(boolean breakpointContext) throws Exception {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final SuspensionSession session = new BreakpointTracker().openSuspensionSession(model.vm, new EventHistory());
		when(breakpointTracker.suspensionSession()).thenReturn(session);
		assertThat(session.suspendThread(worker.ref)).isNull();
		final SuspensionSession.ArmedStep step = Objects.requireNonNull(
			session.step(worker.ref, List::of, () -> mock(StepRequest.class)).armed());
		final SuspensionSession.Context stop = session.route(
			model.breakpointEvent(worker, EventRequest.SUSPEND_EVENT_THREAD), worker.ref, false);
		session.begin(stop);
		session.candidate(stop, new BreakpointTracker.LastBreakpoint(worker.ref, 30));
		assertThat(session.finish(stop)).isTrue();
		when(worker.ref.isSuspended()).thenThrow(new InternalException("status read failed"));

		final String reply = breakpointContext ? tools.jdwp_get_breakpoint_context(null, null)
			: tools.jdwp_get_current_thread();
		assertThat(reply).contains("worker", "ID=1", "breakpoint=30").doesNotStartWith("Error:");
		assertThat(session.awaitStepStop(0, step).outcome()).isEqualTo(SuspensionSession.Outcome.ALREADY_PRESENTED);
	}

	/** Both getters report the selected stop and its epoch when a frame read fails during close. */
	@ParameterizedTest
	@CsvSource({"false,false", "true,false", "false,true", "true,true"})
	void shouldReportDeathWhenSessionClosesDuringFailedFrameRead(boolean breakpointContext, boolean reconnect) throws Exception {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread worker = model.thread("worker");
		final BreakpointTracker sessions = new BreakpointTracker();
		final SuspensionSession session = sessions.openSuspensionSession(model.vm, eventHistory);
		when(breakpointTracker.suspensionSession()).thenReturn(session);
		final SuspensionSession.Context stop = session.route(
			model.breakpointEvent(worker, EventRequest.SUSPEND_EVENT_THREAD), worker.ref, false);
		session.begin(stop);
		session.candidate(stop, new BreakpointTracker.LastBreakpoint(
			worker.ref, 30, BreakpointTracker.EventKind.BREAKPOINT, "example.Work:30"));
		assertThat(session.finish(stop)).isTrue();
		eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT", "original stop"));
		when(worker.ref.frameCount()).thenAnswer(invocation -> {
			session.close();
			if (reconnect) {
				final FakeSuspendModel replacement = new FakeSuspendModel();
				eventHistory.beginNewSession();
				when(breakpointTracker.suspensionSession()).thenReturn(sessions.openSuspensionSession(replacement.vm, eventHistory));
				eventHistory.record(new EventHistory.DebugEvent("RECONNECT", "replacement attached"));
				eventHistory.record(new EventHistory.DebugEvent("LOGPOINT", "replacement logpoint"));
			}
			throw new IncompatibleThreadStateException("frame read lost the suspension");
		});

		final String reply = breakpointContext ? tools.jdwp_get_breakpoint_context(null, null)
			: tools.jdwp_get_current_thread();
		assertThat(reply).startsWith("[VM_DEATH]")
			.contains("example.Work:30", "breakpoint=30", "Recorded before the exit (jdwp_get_events): 1 BREAKPOINT.")
			.doesNotContain("1 RECONNECT", "1 LOGPOINT");
	}
}
