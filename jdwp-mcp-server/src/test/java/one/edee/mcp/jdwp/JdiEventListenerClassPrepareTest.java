package one.edee.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import one.edee.mcp.jdwp.BreakpointTracker.ExceptionBreakpointSpec;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link JdiEventListener#handleClassPrepareEvent} via a synthetic
 * {@link ClassPrepareEvent} to verify the promotion path:
 * <ul>
 *   <li>pending line BPs are promoted with the correct enabled state (ENABLED by default, DISABLED
 *       when chained — so the very first event the JVM might deliver does not bypass the trigger);</li>
 *   <li>pending exception BPs are promoted with the same disabled-when-chained semantics;</li>
 *   <li>failures during promotion ({@code locationsOfLine} returning empty,
 *       {@link AbsentInformationException}) are recorded on the pending entry as a failure reason
 *       rather than crashing the listener thread;</li>
 *   <li>the originating {@link ClassPrepareRequest} is deleted from the event request manager once
 *       no more pending items reference the class.</li>
 * </ul>
 *
 * <p>Uses {@link JdiEventListenerTestSupport} for the shared listener-driving scaffold.
 */
class JdiEventListenerClassPrepareTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private EvaluationGuard evaluationGuard;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		evaluationGuard = new EvaluationGuard();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator, evaluationGuard, null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("Pending line BP without a chain edge is promoted ENABLED")
	void shouldActivateLineBreakpointWithoutChainAsEnabled() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location loc = mock(Location.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenReturn(List.of(loc));

		BreakpointRequest createdBp = mock(BreakpointRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

		int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		verify(createdBp).setSuspendPolicy(2);
		verify(createdBp).enable();
		// Without a chain edge the BP must NOT be disabled after enabling.
		verify(createdBp, never()).setEnabled(false);
		assertThat(tracker.getAllBreakpoints()).containsKey(pendingId);
		assertThat(tracker.getAllPendingBreakpoints()).doesNotContainKey(pendingId);
	}

	@Test
	@DisplayName("Pending line BP with a chain edge is promoted DISABLED")
	void shouldActivateChainedLineBreakpointDisabled() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location loc = mock(Location.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenReturn(List.of(loc));

		BreakpointRequest createdBp = mock(BreakpointRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

		int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
		int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
		tracker.registerDependency(pendingId, triggerId, false);

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		verify(createdBp).enable();
		verify(createdBp).setEnabled(false);
	}

	@Test
	@DisplayName("Pending exception BP with a chain edge is promoted DISABLED")
	void shouldActivateChainedExceptionBreakpointDisabled() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn("com.example.MyException");

		ExceptionRequest createdEx = mock(ExceptionRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(createdEx);

		int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
		int pendingId = tracker.registerPendingExceptionBreakpoint(
			ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));
		tracker.registerDependency(pendingId, triggerId, false);

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		verify(createdEx).enable();
		verify(createdEx).setEnabled(false);
	}

	@Test
	@DisplayName("Empty locationsOfLine marks the pending BP as FAILED AND records BP_PROMOTION_FAILED event")
	void shouldRecordFailureWhenLocationsOfLineEmpty() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenReturn(List.of());

		EventRequestManager erm = mock(EventRequestManager.class);

		int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		BreakpointTracker.PendingBreakpoint pending = tracker.getPendingBreakpoint(pendingId);
		assertThat(pending).isNotNull();
		assertThat(pending.getFailureReason()).contains("No executable code at line 42");

		// Regression coverage for P0-1 polish: the failure must also surface as an EventHistory
		// entry so agents reading jdwp_get_events / the [VM_DEATH] hint can see why their BP
		// never fired — silent-slf4j-log-only is what made the original audit misdiagnose this
		// as a race condition.
		assertThat(eventHistory.getRecent(10))
			.anySatisfy(e -> {
				assertThat(e.type()).isEqualTo("BP_PROMOTION_FAILED");
				assertThat(e.summary()).contains("#" + pendingId);
				assertThat(e.summary()).contains("com.example.Foo:42");
				assertThat(e.summary()).contains("No executable code");
			});
	}

	@Test
	@DisplayName("AbsentInformationException from locationsOfLine marks the pending BP as FAILED AND records event")
	void shouldRecordFailureWhenAbsentInformationException() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenThrow(new AbsentInformationException());

		EventRequestManager erm = mock(EventRequestManager.class);

		int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		BreakpointTracker.PendingBreakpoint pending = tracker.getPendingBreakpoint(pendingId);
		assertThat(pending).isNotNull();
		assertThat(pending.getFailureReason()).contains("No debug info");

		assertThat(eventHistory.getRecent(10))
			.anySatisfy(e -> {
				assertThat(e.type()).isEqualTo("BP_PROMOTION_FAILED");
				assertThat(e.summary()).contains("No debug info");
			});
	}

	/**
	 * When the trigger BP fired BEFORE the dependent's class was loaded, the dependent must be
	 * promoted ARMED, not disarmed — the trigger gating has already been satisfied earlier in the
	 * session. The {@link BreakpointTracker#markTriggerFired} memory is the bridge between the
	 * earlier hit and the later promotion; without it the dependent silently misses the very first
	 * chained event after class load.
	 */
	@Test
	@DisplayName("chained pending BP promoted ENABLED when trigger already fired")
	void shouldPromoteChainedPendingBpArmedWhenTriggerAlreadyFired() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location loc = mock(Location.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenReturn(List.of(loc));

		BreakpointRequest createdBp = mock(BreakpointRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

		int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
		int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
		tracker.registerDependency(pendingId, triggerId, false);

		// Simulate: the trigger BP fired during the pending interval.
		tracker.markTriggerFired(triggerId);

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		// The promotion path still calls enable(); the key assertion is that setEnabled(false)
		// is NOT called this time because the trigger has already fired.
		verify(createdBp).enable();
		verify(createdBp, never()).setEnabled(false);
	}

	/**
	 * Companion to {@link #shouldPromoteChainedPendingBpArmedWhenTriggerAlreadyFired}: when the
	 * trigger has NOT fired yet, the historical behaviour is preserved — the promoted dependent
	 * comes up disabled until the trigger eventually fires.
	 */
	@Test
	@DisplayName("chained pending BP still promoted DISABLED when trigger has not fired")
	void shouldStillPromoteChainedPendingBpDisarmedWhenTriggerNotFired() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location loc = mock(Location.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenReturn(List.of(loc));

		BreakpointRequest createdBp = mock(BreakpointRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

		int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
		int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
		tracker.registerDependency(pendingId, triggerId, false);

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		verify(createdBp).enable();
		verify(createdBp).setEnabled(false);
	}

	@Test
	@DisplayName("ClassPrepareRequest is deleted once no more pending BPs reference the class")
	void shouldDeleteClassPrepareRequestWhenNoMorePendingBpsForClass() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location loc = mock(Location.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenReturn(List.of(loc));

		BreakpointRequest createdBp = mock(BreakpointRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

		ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		tracker.registerClassPrepareRequest("com.example.Foo", cpr);

		tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		verify(erm).deleteEventRequest(cpr);
		assertThat(tracker.hasClassPrepareRequest("com.example.Foo")).isFalse();
	}

	@Test
	@DisplayName("Generic exception from createBreakpointRequest marks the pending line BP as FAILED")
	void shouldRecordFailureWhenCreateBreakpointRequestThrowsGenericException() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location loc = mock(Location.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenReturn(List.of(loc));

		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(loc))
			.thenThrow(new RuntimeException("ERM rejected the request"));

		int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		// The generic catch (Exception e) branch must record the failure on the pending entry so
		// the user can see why activation failed — and the listener thread must NOT die.
		BreakpointTracker.PendingBreakpoint pending = tracker.getPendingBreakpoint(pendingId);
		assertThat(pending).isNotNull();
		assertThat(pending.getFailureReason()).contains("ERM rejected the request");
	}

	@Test
	@DisplayName("Generic exception from createExceptionRequest marks the pending exception BP as FAILED")
	void shouldRecordFailureWhenCreateExceptionRequestThrowsGenericException() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn("com.example.MyException");

		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createExceptionRequest(refType, true, true))
			.thenThrow(new RuntimeException("ERM rejected the exception request"));

		int pendingId = tracker.registerPendingExceptionBreakpoint(
			ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		BreakpointTracker.PendingExceptionBreakpoint pending =
			tracker.getAllPendingExceptionBreakpoints().get(pendingId);
		assertThat(pending).isNotNull();
		assertThat(pending.getFailureReason()).contains("ERM rejected the exception request");
	}

	// ── Diagnostic event emissions (CLASS_PREPARE, BP_MULTI_LOCATION) ────────

	@Test
	@DisplayName("CLASS_PREPARE event recorded in EventHistory when a CPE activates pending items")
	void shouldRecordClassPrepareEventInHistory() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location loc = mock(Location.class);
		when(refType.name()).thenReturn("com.example.Foo");
		when(refType.locationsOfLine(42)).thenReturn(List.of(loc));

		BreakpointRequest createdBp = mock(BreakpointRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

		tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		assertThat(eventHistory.getRecent(10))
			.anySatisfy(e -> {
				assertThat(e.type()).isEqualTo("CLASS_PREPARE");
				assertThat(e.summary()).contains("com.example.Foo");
				assertThat(e.summary()).contains("1 line BP");
			});
	}

	@Test
	@DisplayName("CLASS_PREPARE NOT recorded when the CPE has no pending items for the class")
	void shouldNotRecordClassPrepareWhenNothingPending() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn("com.example.Unrelated");
		EventRequestManager erm = mock(EventRequestManager.class);

		// No pending entries for "com.example.Unrelated".
		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		// The handler short-circuits at the "all pending lists empty" check before recording.
		assertThat(eventHistory.getRecent(10))
			.noneSatisfy(e -> assertThat(e.type()).isEqualTo("CLASS_PREPARE"));
	}

	@Test
	@DisplayName("BP_MULTI_LOCATION recorded when locationsOfLine returns more than one Location (lambda case)")
	void shouldRecordMultiLocationEventForLambdaLine() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location enclosingLoc = mock(Location.class);
		Location lambdaLoc = mock(Location.class);
		// Mock describeLocation's path so the summary is readable. The exact contents aren't
		// what the test asserts on, but a sane shape helps diagnosis if the assertion fails.
		com.sun.jdi.Method enclosingMethod = mock(com.sun.jdi.Method.class);
		when(enclosingMethod.name()).thenReturn("doWork");
		when(enclosingLoc.method()).thenReturn(enclosingMethod);
		when(enclosingLoc.codeIndex()).thenReturn(12L);

		when(refType.name()).thenReturn("com.example.WithLambda");
		when(refType.locationsOfLine(99)).thenReturn(List.of(enclosingLoc, lambdaLoc));

		BreakpointRequest createdBp = mock(BreakpointRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(enclosingLoc)).thenReturn(createdBp);

		int pendingId = tracker.registerPendingBreakpoint("com.example.WithLambda", 99, 2, "ALL");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		assertThat(eventHistory.getRecent(10))
			.anySatisfy(e -> {
				assertThat(e.type()).isEqualTo("BP_MULTI_LOCATION");
				assertThat(e.summary()).contains("#" + pendingId);
				assertThat(e.summary()).contains("com.example.WithLambda:99");
				assertThat(e.summary()).contains("2 bytecode locations");
				assertThat(e.summary()).contains("other paths will MISS");
				// Per the new logpoint-vs-breakpoint distinction, this is a plain BP.
				assertThat(e.summary()).startsWith("BP ");
			});
	}

	@Test
	@DisplayName("BP_MULTI_LOCATION label is `LP` when the activated pending entry is a logpoint")
	void shouldLabelMultiLocationAsLogpointWhenLogpointMetadataPresent() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location enclosingLoc = mock(Location.class);
		Location lambdaLoc = mock(Location.class);
		com.sun.jdi.Method enclosingMethod = mock(com.sun.jdi.Method.class);
		when(enclosingMethod.name()).thenReturn("doWork");
		when(enclosingLoc.method()).thenReturn(enclosingMethod);
		when(enclosingLoc.codeIndex()).thenReturn(12L);

		when(refType.name()).thenReturn("com.example.WithLambda");
		when(refType.locationsOfLine(99)).thenReturn(List.of(enclosingLoc, lambdaLoc));

		BreakpointRequest createdBp = mock(BreakpointRequest.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(enclosingLoc)).thenReturn(createdBp);

		int pendingId = tracker.registerPendingBreakpoint("com.example.WithLambda", 99, 2, "ALL");
		// Mark this pending entry as a logpoint by attaching an expression.
		tracker.setLogpointExpression(pendingId, "x + 1");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		assertThat(eventHistory.getRecent(10))
			.anySatisfy(e -> {
				assertThat(e.type()).isEqualTo("BP_MULTI_LOCATION");
				// The diagnostic should use "LP" rather than "BP" because this entry is a logpoint.
				assertThat(e.summary()).startsWith("LP ");
			});
	}

	@Test
	@DisplayName("CLASS_PREPARE summary splits the line-pending count into BP(s) vs logpoint(s)")
	void shouldSplitLineBpAndLogpointCountsInClassPrepareSummary() throws Exception {
		ReferenceType refType = mock(ReferenceType.class);
		Location bpLoc = mock(Location.class);
		Location lpLoc = mock(Location.class);
		when(refType.name()).thenReturn("com.example.Mixed");
		when(refType.locationsOfLine(10)).thenReturn(List.of(bpLoc));
		when(refType.locationsOfLine(20)).thenReturn(List.of(lpLoc));

		EventRequestManager erm = mock(EventRequestManager.class);
		when(erm.createBreakpointRequest(bpLoc)).thenReturn(mock(BreakpointRequest.class));
		when(erm.createBreakpointRequest(lpLoc)).thenReturn(mock(BreakpointRequest.class));

		// One plain breakpoint and one logpoint deferred against the same class — both ride the
		// pending-line path, so the summary must attribute them to separate buckets.
		tracker.registerPendingBreakpoint("com.example.Mixed", 10, 2, "ALL");
		int lpId = tracker.registerPendingBreakpoint("com.example.Mixed", 20, 2, "ALL");
		tracker.setLogpointExpression(lpId, "x + 1");

		ClassPrepareEvent event = mockClassPrepareEvent(refType, erm);
		runListenerWith(listener, mockEventSet(event));

		assertThat(eventHistory.getRecent(10))
			.anySatisfy(e -> {
				assertThat(e.type()).isEqualTo("CLASS_PREPARE");
				assertThat(e.summary()).contains("1 line BP(s)");
				assertThat(e.summary()).contains("1 logpoint(s)");
				assertThat(e.details()).containsEntry("lineBpCount", "1");
				assertThat(e.details()).containsEntry("lineLpCount", "1");
			});
	}

	// ── Test-specific event factory ──────────────────────────────────────────

	private static ClassPrepareEvent mockClassPrepareEvent(ReferenceType refType, EventRequestManager erm) {
		ClassPrepareEvent event = mock(ClassPrepareEvent.class);
		when(event.referenceType()).thenReturn(refType);
		VirtualMachine vm = mock(VirtualMachine.class);
		when(event.virtualMachine()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		return event;
	}
}
