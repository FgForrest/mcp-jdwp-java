package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins how {@link JdiEventListener} hands fast-logpoint and fast-breakpoint events to
 * {@link FastLogpointService}: an arming hit bypasses the regular breakpoint handler (no
 * {@code BREAKPOINT} event, no last-breakpoint snapshot, no "untracked breakpoint" suspension) and
 * is resumed unless the service published it as a fast-breakpoint stop; when the same event set also
 * carries a regular breakpoint, that one is handled first — arming redefines the class and would
 * otherwise orphan its request. A fast breakpoint's stop goes to the service before the regular
 * handler, stays suspended only when the service published it, and is skipped (and resumed) on a
 * thread inside an MCP evaluation.
 */
@DisplayName("Listener routing of fast-logpoint and fast-breakpoint events")
class JdiEventListenerFastLogpointRoutingTest {

	private BreakpointTracker tracker;
	private EventHistory history;
	private FastLogpointService fastLogpoints;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		history = new EventHistory();
		fastLogpoints = mock(FastLogpointService.class);
		listener = new JdiEventListener(tracker, history, mock(JdiExpressionEvaluator.class), new EvaluationGuard(),
			null, new MarkedInstanceRegistry());
		listener.setFastLogpointService(fastLogpoints);
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("an arming hit is armed and resumed, not treated as an untracked breakpoint")
	void armingHitResumes() throws Exception {
		final ThreadReference thread = mockThread("worker", 11L);
		final BreakpointEvent arming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 10);
		when(fastLogpoints.claimsArmingEvent(arming)).thenReturn(true);
		when(fastLogpoints.handleArmingHit(arming, false)).thenReturn(FastLogpointService.ArmingOutcome.RESUME);
		final EventSet eventSet = mockEventSet(arming);

		runListenerWith(listener, eventSet);

		verify(fastLogpoints).handleArmingHit(arming, false);
		verify(eventSet).resume();
		assertThat(tracker.getLastBreakpointThread()).isNull();
		assertThat(history.getRecent(10)).noneMatch(e -> "BREAKPOINT".equals(e.type()));
	}

	@Test
	@DisplayName("a regular breakpoint in the same event set is handled before arming and keeps the set suspended")
	void regularBreakpointFirst() throws Exception {
		final ThreadReference thread = mockThread("worker", 12L);
		final BreakpointRequest userRequest = mock(BreakpointRequest.class);
		when(userRequest.suspendPolicy()).thenReturn(EventRequest.SUSPEND_ALL);
		final int userId = tracker.registerBreakpoint(userRequest);
		final BreakpointEvent user = mockBreakpointEvent(thread, userRequest, "com.example.Hot", 10);
		final BreakpointEvent arming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 10);
		when(fastLogpoints.claimsArmingEvent(arming)).thenReturn(true);

		// The regular breakpoint's stop is a candidate of the set by the time arming runs (its history
		// is buffered until the set finishes, issue #45).
		final List<Integer> candidatesWhenArming = new ArrayList<>();
		final List<Boolean> setStops = new ArrayList<>();
		doAnswer(invocation -> {
			candidatesWhenArming.add(SuspensionSession.processingCandidateCount());
			setStops.add(invocation.getArgument(1));
			return FastLogpointService.ArmingOutcome.RESUME;
		}).when(fastLogpoints).handleArmingHit(any(), anyBoolean());
		// Arming event first in the set: the listener must still defer it.
		final EventSet eventSet = mockEventSet(arming, user);

		runListenerWith(listener, eventSet);

		assertThat(candidatesWhenArming).containsExactly(1);
		assertThat(setStops).as("arming is told its set stops, so the redefinition rule counts its thread")
			.containsExactly(true);
		assertThat(history.getRecent(10)).extracting(EventHistory.DebugEvent::type).contains("BREAKPOINT");
		assertThat(tracker.getLastBreakpointId()).isEqualTo(userId);
		verify(eventSet, never()).resume();
	}

	@Test
	@DisplayName("a fast breakpoint's arming hit is handled before a fast logpoint's in the same set, which then defers")
	void breakpointArmingBeforeLogpointArming() throws Exception {
		final ThreadReference thread = mockThread("worker", 15L);
		final BreakpointEvent logpointArming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 10);
		final BreakpointEvent breakpointArming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 10);
		when(fastLogpoints.claimsArmingEvent(logpointArming)).thenReturn(true);
		when(fastLogpoints.armingNeverStops(logpointArming)).thenReturn(true);
		when(fastLogpoints.claimsArmingEvent(breakpointArming)).thenReturn(true);
		final List<BreakpointEvent> order = new ArrayList<>();
		final List<Boolean> setStops = new ArrayList<>();
		doAnswer(invocation -> {
			final BreakpointEvent hit = invocation.getArgument(0);
			order.add(hit);
			setStops.add(invocation.getArgument(1));
			return hit == breakpointArming
				? FastLogpointService.ArmingOutcome.STOPPED : FastLogpointService.ArmingOutcome.RESUME;
		}).when(fastLogpoints).handleArmingHit(any(), anyBoolean());
		// The logpoint's arming first in the set: arming it first would redefine the class and lose the
		// breakpoint's hit, which may be its only true one.
		final EventSet eventSet = mockEventSet(logpointArming, breakpointArming);

		runListenerWith(listener, eventSet);

		assertThat(order).containsExactly(breakpointArming, logpointArming);
		assertThat(setStops).as("the logpoint's arming learns that the set stops, so it defers").containsExactly(false, true);
		verify(eventSet, never()).resume();
	}

	@Test
	@DisplayName("an arming hit the service published as a fast-breakpoint stop keeps its set suspended")
	void publishedArmingHitStaysSuspended() throws Exception {
		final ThreadReference thread = mockThread("worker", 13L);
		final BreakpointEvent arming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 10);
		when(fastLogpoints.claimsArmingEvent(arming)).thenReturn(true);
		when(fastLogpoints.handleArmingHit(arming, false)).thenReturn(FastLogpointService.ArmingOutcome.STOPPED);
		final EventSet eventSet = mockEventSet(arming);

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
	}

	@Test
	@DisplayName("a published fast-breakpoint stop bypasses the regular handler and stays suspended")
	void publishedStopStaysSuspended() throws Exception {
		final ThreadReference thread = mockThread("worker", 14L);
		final BreakpointEvent stop = mockBreakpointEvent(thread, mock(BreakpointRequest.class),
			"com.example.McpFastBreakpoint_x", 1);
		when(fastLogpoints.claimsStopEvent(stop)).thenReturn(true);
		when(fastLogpoints.handleStopHit(stop)).thenReturn(true);
		final EventSet eventSet = mockEventSet(stop);

		runListenerWith(listener, eventSet);

		verify(fastLogpoints).handleStopHit(stop);
		verify(eventSet, never()).resume();
		assertThat(history.getRecent(10)).noneMatch(e -> "BREAKPOINT".equals(e.type()));
	}

	@Test
	@DisplayName("a stale fast-breakpoint stop is resumed, never parked as an untracked breakpoint")
	void staleStopResumes() throws Exception {
		final ThreadReference thread = mockThread("worker", 15L);
		final BreakpointEvent stop = mockBreakpointEvent(thread, mock(BreakpointRequest.class),
			"com.example.McpFastBreakpoint_x", 1);
		when(fastLogpoints.claimsStopEvent(stop)).thenReturn(true);
		final EventSet eventSet = mockEventSet(stop);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		assertThat(tracker.getLastBreakpointThread()).isNull();
	}

	@Test
	@DisplayName("a fast-breakpoint stop inside an MCP evaluation is skipped and resumed on the listener thread")
	void suppressedStopSkipped() throws Exception {
		final ThreadReference thread = mockThread("evaluating", 16L);
		final BreakpointEvent stop = mockBreakpointEvent(thread, mock(BreakpointRequest.class),
			"com.example.McpFastBreakpoint_x", 1);
		when(fastLogpoints.claimsStopEvent(stop)).thenReturn(true);
		final EvaluationGuard guard = new EvaluationGuard();
		guard.enter(16L);
		final JdiEventListener guarded = new JdiEventListener(tracker, history, mock(JdiExpressionEvaluator.class),
			guard, null, new MarkedInstanceRegistry());
		guarded.setFastLogpointService(fastLogpoints);
		final EventSet eventSet = mockEventSet(stop);
		try {
			runListenerWith(guarded, eventSet);
		} finally {
			guarded.stop();
		}

		verify(fastLogpoints).skipStopHit(stop);
		verify(fastLogpoints, never()).handleStopHit(any());
		verify(eventSet).resume();
	}

	@Test
	@DisplayName("a set of logpoints, fast-logpoint arming hits and class prepares never stops; anything else may")
	void judgesWhetherASetMayStop() {
		final ThreadReference thread = mockThread("worker", 14L);
		final BreakpointRequest logpointRequest = mock(BreakpointRequest.class);
		tracker.setLogpointExpression(tracker.registerBreakpoint(logpointRequest), "i");
		final BreakpointEvent logpoint = mockBreakpointEvent(thread, logpointRequest, "com.example.Hot", 10);
		final BreakpointRequest breakpointRequest = mock(BreakpointRequest.class);
		tracker.registerBreakpoint(breakpointRequest);
		final BreakpointEvent breakpoint = mockBreakpointEvent(thread, breakpointRequest, "com.example.Hot", 11);
		final BreakpointEvent logpointArming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 12);
		final BreakpointEvent breakpointArming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 13);
		when(fastLogpoints.claimsArmingEvent(logpointArming)).thenReturn(true);
		when(fastLogpoints.armingNeverStops(logpointArming)).thenReturn(true);
		when(fastLogpoints.claimsArmingEvent(breakpointArming)).thenReturn(true);

		assertThat(listener.mayStop(mockEventSet(logpoint, logpointArming, mock(ClassPrepareEvent.class)))).isFalse();
		assertThat(listener.mayStop(mockEventSet(logpoint, breakpoint))).isTrue();
		assertThat(listener.mayStop(mockEventSet(breakpointArming))).as("a fast breakpoint's arming hit may stop").isTrue();
		assertThat(listener.mayStop(mockEventSet(mock(StepEvent.class)))).isTrue();
	}
}
