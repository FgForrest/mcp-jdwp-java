package one.edee.mcp.jdwp;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The redefinition rule (#42 proposal § 6) at arming: a fast breakpoint is not installed while a
 * thread the user may see stopped has a frame in its method — the frame would turn obsolete and
 * lose its locals. Arming is deferred (a fresh one-shot arming request) before anything is compiled,
 * and recorded once per hold. Runs the service against {@link FakeSuspendModel}'s counts.
 */
@DisplayName("Fast-breakpoint arming under the redefinition rule")
class FastLogpointRedefinitionRuleTest {

	private static final int LINE = 42;

	private FakeSuspendModel model;
	private FakeSuspendModel.FakeThread armer;
	private FakeSuspendModel.FakeThread inspected;
	private EventHistory history;
	private SuspensionSession session;
	private JdiExpressionEvaluator evaluator;
	private FastLogpointService service;
	private Location location;
	private StackFrame frame;
	/**
	 * Every breakpoint request the service created, in order.
	 */
	private final List<BreakpointRequest> created = new ArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		model = new FakeSuspendModel();
		armer = model.thread("armer");
		inspected = model.thread("inspected");
		history = new EventHistory();
		final BreakpointTracker tracker = new BreakpointTracker();
		session = tracker.openSuspensionSession(history);
		evaluator = mock(JdiExpressionEvaluator.class);
		service = new FastLogpointService(tracker, history, new EvaluationGuard(), evaluator,
			mock(JdiEventListener.class), mock(JDIConnectionService.class));

		final EventRequestManager requests = mock(EventRequestManager.class);
		when(model.vm.eventRequestManager()).thenReturn(requests);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn("com.example.Cart");
		final Method method = mock(Method.class);
		when(method.name()).thenReturn("total");
		when(method.signature()).thenReturn("()I");
		when(method.declaringType()).thenReturn(type);
		location = mock(Location.class);
		when(location.method()).thenReturn(method);
		when(location.declaringType()).thenReturn(type);
		when(location.lineNumber()).thenReturn(LINE);
		when(type.locationsOfLine(LINE)).thenReturn(List.of(location));
		when(requests.createBreakpointRequest(location)).thenAnswer(invocation -> {
			final BreakpointRequest request = mock(BreakpointRequest.class);
			created.add(request);
			return request;
		});
		// Both threads are inside total(): the arming thread at the line, the other one anywhere.
		frame = mock(StackFrame.class);
		when(frame.location()).thenReturn(location);
		when(armer.ref.frame(0)).thenReturn(frame);
		when(armer.ref.frames()).thenReturn(List.of(frame));
		when(inspected.ref.frames()).thenReturn(List.of(frame));
		final BooleanValue falseValue = mock(BooleanValue.class);
		when(evaluator.evaluate(frame, "total > 100")).thenReturn(falseValue);

		service.registerBreakpoint(model.vm, location, "total > 100", true, EventRequest.SUSPEND_EVENT_THREAD,
			FastLogpointService.BREAKPOINT_ERROR_BUFFER_SIZE);
	}

	@Test
	@DisplayName("a stopped thread in the method defers arming before anything is compiled, recorded once per hold")
	void stoppedThreadDefersArming() throws Exception {
		stopOn(inspected, 1);

		assertThat(armingHit(false)).isEqualTo(FastLogpointService.ArmingOutcome.RESUME);
		assertThat(created).as("a fresh one-shot arming request waits for the next hit").hasSize(2);
		assertThat(armingHit(false)).isEqualTo(FastLogpointService.ArmingOutcome.RESUME);

		assertThat(service.list().get(0).state()).isEqualTo(FastLogpointService.State.PENDING);
		assertThat(deferrals()).as("once per hold").hasSize(1).first().asString()
			.contains("thread inspected is stopped in total");
		verify(evaluator, never()).compileFastBreakpointProbe(any(), anyBoolean(), anyString(), anyString(),
			anyBoolean(), anyInt(), any());
	}

	@Test
	@DisplayName("the arming thread's own frame counts only when its set stops")
	void armingThreadCountsWhenItsSetStops() throws Exception {
		assertThat(armingHit(true)).isEqualTo(FastLogpointService.ArmingOutcome.RESUME);

		assertThat(deferrals()).hasSize(1).first().asString().contains("thread armer is stopped in total");
		assertThat(service.list().get(0).state()).isEqualTo(FastLogpointService.State.PENDING);
	}

	@Test
	@DisplayName("a thread held only by a set not routed yet does not defer the check ahead of the suspension")
	void unroutedSetIsLeftToTheCheckUnderTheSuspension() throws Exception {
		// The target raised the event (a logpoint hit, most likely), the listener has not routed it yet.
		model.breakpointEvent(inspected, EventRequest.SUSPEND_EVENT_THREAD);

		armingHit(false);

		assertThat(deferrals()).isEmpty();
		assertThat(service.list().get(0).state()).as("arming went on (the mock VM serves no class bytes)")
			.isEqualTo(FastLogpointService.State.FAILED);
	}

	@Test
	@DisplayName("once the stopped thread is released, the next arming hit goes ahead")
	void releasedThreadNoLongerDefers() throws Exception {
		stopOn(inspected, 1);
		armingHit(false);
		session.presentCurrent();
		session.releaseAll();

		armingHit(false);

		assertThat(deferrals()).hasSize(1);
		assertThat(service.list().get(0).state()).as("arming went on (the mock VM serves no class bytes)")
			.isEqualTo(FastLogpointService.State.FAILED);
	}

	/**
	 * Delivers the current arming request's hit on {@link #armer} the way the dispatcher does: inside
	 * the processing of its set, which then finishes.
	 */
	private FastLogpointService.ArmingOutcome armingHit(boolean setStops) {
		final BreakpointEvent event = mock(BreakpointEvent.class);
		when(event.request()).thenReturn(created.get(created.size() - 1));
		when(event.thread()).thenReturn(armer.ref);
		when(event.location()).thenReturn(location);
		final SuspensionSession.Context context = session.route(
			model.breakpointEvent(armer, EventRequest.SUSPEND_EVENT_THREAD), armer.ref, false);
		session.begin(context);
		session.beginProcessing(context);
		try {
			return service.handleArmingHit(event, setStops);
		} finally {
			SuspensionSession.endProcessing();
			session.finish(context);
		}
	}

	private void stopOn(FakeSuspendModel.FakeThread thread, int breakpointId) {
		final SuspensionSession.Context context = session.route(
			model.breakpointEvent(thread, EventRequest.SUSPEND_EVENT_THREAD), thread.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(thread.ref, breakpointId));
		session.finish(context);
	}

	private List<String> deferrals() {
		final List<String> summaries = new ArrayList<>();
		for (EventHistory.DebugEvent event : history.getRecent(EventHistory.MAX_EVENTS)) {
			if ("FAST_BREAKPOINT_ARMING_DEFERRED".equals(event.type())) {
				summaries.add(event.summary());
			}
		}
		return summaries;
	}
}
