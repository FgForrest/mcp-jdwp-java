package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the defensive branch in {@link JdiEventListener}'s breakpoint handler: when a
 * {@link BreakpointEvent} fires whose {@link BreakpointRequest} is NOT registered with the
 * {@link BreakpointTracker} (e.g., a leftover request from a previous session that the tracker
 * never recorded), the listener must still suspend the thread but must NOT:
 * <ul>
 *   <li>set {@code lastBreakpointThread} — there is no synthetic ID to associate the thread with;</li>
 *   <li>record a {@code BREAKPOINT} entry — the event has no user-facing identity.</li>
 * </ul>
 * <p>This is the "err on the side of inspection" path: keep the thread parked so the developer
 * can investigate the unexpected hit manually, but don't pollute the tracker state.
 *
 * <p>Note: the next-event latch is intentionally not asserted on either side because the test
 * harness always fires it on the trailing disconnect sentinel, so the signal carries no
 * information about the untracked-BP path itself.
 */
class JdiEventListenerUntrackedBreakpointTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator, new EvaluationGuard(), null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("Untracked BP suspends without touching tracker state or event history")
	void shouldSuspendAndIgnoreUntrackedBreakpoint() throws Exception {
		ThreadReference thread = mockThread("worker-untracked", 700L);
		// Note: request is NEVER registered with the tracker — simulates a leftover stale request.
		BreakpointRequest unregisteredRequest = mock(BreakpointRequest.class);

		BreakpointEvent event = mockBreakpointEvent(thread, unregisteredRequest,
			"com.example.Stale", 99);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Suspending: resume() must NOT have been called.
		verify(eventSet, never()).resume();

		// Tracker state must be untouched — no setLastBreakpointThread call.
		assertThat(tracker.getLastBreakpointThread()).isNull();
		assertThat(tracker.getLastBreakpointId()).isNull();

		// No BREAKPOINT entry must be recorded. The disconnect-sentinel adds a VM_DEATH, but no
		// BREAKPOINT (or BREAKPOINT_SUPPRESSED) should appear in history for the stale request.
		assertThat(eventHistory.getRecent(10))
			.extracting(EventHistory.DebugEvent::type)
			.doesNotContain("BREAKPOINT", "BREAKPOINT_SUPPRESSED");
	}

	/**
	 * {@code handleBreakpointEvent} must null-guard {@code event.request()} before dispatching to
	 * the tracker. A null request can legitimately arrive on synthesised events, JDI provider
	 * quirks, or stale events delivered after the request was removed. The listener treats the
	 * null-request case the same way as an untracked request: suspend the thread so the developer
	 * notices, but skip tracker mutation and history recording because there is no synthetic ID
	 * to attribute the hit to.
	 */
	@Test
	@DisplayName("Null event.request() is handled defensively: suspends without tracker mutation or history record")
	void shouldSuspendWithoutSideEffectsWhenEventRequestIsNull() throws Exception {
		ThreadReference thread = mockThread("worker-null-request", 800L);

		// Build a BreakpointEvent directly so event.request() returns null.
		BreakpointEvent event = mock(BreakpointEvent.class);
		when(event.request()).thenReturn(null);
		when(event.thread()).thenReturn(thread);
		Location location = mock(Location.class);
		ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn("com.example.NoRequest");
		when(location.declaringType()).thenReturn(declaringType);
		when(location.lineNumber()).thenReturn(7);
		when(event.location()).thenReturn(location);

		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		assertThat(tracker.getLastBreakpointThread()).isNull();
		assertThat(eventHistory.getRecent(10))
			.extracting(EventHistory.DebugEvent::type)
			.doesNotContain("BREAKPOINT", "BREAKPOINT_SUPPRESSED");
	}

	/**
	 * A set can wait for the dispatcher thread while its request goes — a logpoint cleared, a class
	 * redefined. JDI drops such an event when the set leaves its queue and resumes the set if nothing
	 * is left; the listener must apply the same rule when it processes the set, not park the thread
	 * as an untracked hit that nobody will ever resume.
	 */
	@Test
	@DisplayName("An event whose request was deleted while its set waited is dropped and the set resumed")
	void shouldDropEventOfDeletedRequest() throws Exception {
		ThreadReference thread = mockThread("worker-cleared", 900L);
		BreakpointRequest request = mock(BreakpointRequest.class);
		int id = tracker.registerBreakpoint(request);
		tracker.setLogpointExpression(id, "i");
		BreakpointEvent event = mockBreakpointEvent(thread, request, "com.example.Cleared", 12);
		EventSet eventSet = mockEventSet(event);
		// The logpoint is cleared before the set is processed: forgotten, and its request deleted.
		tracker.removeBreakpoint(id);
		when(request.isEnabled()).thenReturn(false);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		assertThat(tracker.getLastBreakpointThread()).isNull();
		assertThat(eventHistory.getRecent(10))
			.extracting(EventHistory.DebugEvent::type)
			.doesNotContain("BREAKPOINT", "LOGPOINT", "LOGPOINT_ERROR");
	}

	/**
	 * The other half of the rule above: a breakpoint that is still tracked but was disabled while its
	 * set waited is a hit, as it was when the set left JDI's queue. Resuming that set late could
	 * release a newer suspension of the same thread — after an intervening {@code jdwp_resume} the
	 * thread may already be stopped at its next event.
	 */
	@Test
	@DisplayName("An event whose tracked request was disabled while its set waited is still a hit")
	void shouldKeepHitOfDisabledTrackedRequest() throws Exception {
		ThreadReference thread = mockThread("worker-disabled", 901L);
		BreakpointRequest request = mock(BreakpointRequest.class);
		int id = tracker.registerBreakpoint(request);
		BreakpointEvent event = mockBreakpointEvent(thread, request, "com.example.Disabled", 13);
		EventSet eventSet = mockEventSet(event);
		when(request.isEnabled()).thenReturn(false);

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		assertThat(tracker.getLastBreakpointId()).isEqualTo(id);
		assertThat(eventHistory.getRecent(10))
			.extracting(EventHistory.DebugEvent::type)
			.contains("BREAKPOINT");
	}
}
