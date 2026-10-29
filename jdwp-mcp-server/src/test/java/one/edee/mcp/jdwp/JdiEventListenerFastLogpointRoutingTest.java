package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
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
		when(fastLogpoints.handleArmingHit(arming)).thenReturn(FastLogpointService.ArmingOutcome.RESUME);
		final EventSet eventSet = mockEventSet(arming);

		runListenerWith(listener, eventSet);

		verify(fastLogpoints).handleArmingHit(arming);
		verify(eventSet).resume();
		assertThat(tracker.getLastBreakpointThread()).isNull();
		assertThat(history.getRecent(10)).noneMatch(e -> "BREAKPOINT".equals(e.type()));
	}

	@Test
	@DisplayName("an arming hit whose thread a resume of the VM released meanwhile is not resumed again")
	void spentArmingHitIsNotResumedAgain() throws Exception {
		final ThreadReference thread = mockThread("worker", 15L);
		final BreakpointEvent arming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 10);
		when(fastLogpoints.claimsArmingEvent(arming)).thenReturn(true);
		when(fastLogpoints.handleArmingHit(arming)).thenReturn(FastLogpointService.ArmingOutcome.SPENT);
		final EventSet eventSet = mockEventSet(arming);

		runListenerWith(listener, eventSet);

		verify(fastLogpoints).handleArmingHit(arming);
		verify(eventSet, never()).resume();
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

		final List<String> typesSeenWhenArming = new ArrayList<>();
		doAnswer(invocation -> {
			history.getRecent(10).forEach(e -> typesSeenWhenArming.add(e.type()));
			return FastLogpointService.ArmingOutcome.RESUME;
		}).when(fastLogpoints).handleArmingHit(any());
		// Arming event first in the set: the listener must still defer it.
		final EventSet eventSet = mockEventSet(arming, user);

		runListenerWith(listener, eventSet);

		assertThat(typesSeenWhenArming).contains("BREAKPOINT");
		assertThat(tracker.getLastBreakpointId()).isEqualTo(userId);
		verify(eventSet, never()).resume();
	}

	@Test
	@DisplayName("an arming hit the service published as a fast-breakpoint stop keeps its set suspended")
	void publishedArmingHitStaysSuspended() throws Exception {
		final ThreadReference thread = mockThread("worker", 13L);
		final BreakpointEvent arming = mockBreakpointEvent(thread, mock(BreakpointRequest.class), "com.example.Hot", 10);
		when(fastLogpoints.claimsArmingEvent(arming)).thenReturn(true);
		when(fastLogpoints.handleArmingHit(arming)).thenReturn(FastLogpointService.ArmingOutcome.STOPPED);
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
}
