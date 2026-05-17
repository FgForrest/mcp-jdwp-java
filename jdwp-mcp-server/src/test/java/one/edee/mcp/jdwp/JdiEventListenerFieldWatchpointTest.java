package one.edee.mcp.jdwp;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.AccessWatchpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.event.WatchpointEvent;
import com.sun.jdi.request.AccessWatchpointRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import com.sun.jdi.request.WatchpointRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the field-watchpoint dispatch in {@link JdiEventListener#handleWatchpointEvent}:
 * <ul>
 *   <li>access event on a suspending field BP → {@code FIELD_ACCESS} recorded, eventSet not resumed,
 *       lastBreakpointThread bound to the firing thread;</li>
 *   <li>modification event on a suspending field BP → {@code FIELD_MODIFICATION} recorded;</li>
 *   <li>log-only field BP → {@code FIELD_LOGPOINT} recorded, eventSet auto-resumed;</li>
 *   <li>conditional field BP with a false condition → no FIELD_* event recorded, eventSet
 *       auto-resumed;</li>
 *   <li>reentrancy guard active → {@code FIELD_BREAKPOINT_SUPPRESSED} recorded, eventSet
 *       auto-resumed;</li>
 *   <li>untracked watchpoint hit (defensive path) → eventSet not resumed and no field event
 *       recorded (the BP keeps the thread suspended for inspection).</li>
 * </ul>
 *
 * <p>The watchpoint event mocks are built inline in this class — only this test exercises the
 * field-event path, so adding factories to {@code JdiEventListenerTestSupport} would lift code
 * that has no other consumers. If a second field-event test arrives, hoist them then.
 */
class JdiEventListenerFieldWatchpointTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private JdiExpressionEvaluator evaluator;
	private EvaluationGuard evaluationGuard;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		evaluator = mock(JdiExpressionEvaluator.class);
		evaluationGuard = new EvaluationGuard();
		listener = new JdiEventListener(tracker, eventHistory, evaluator, evaluationGuard, null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("Access event on suspending field BP records FIELD_ACCESS and keeps thread suspended")
	void shouldRecordFieldAccessAndSuspend() throws Exception {
		AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		int bpId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.ACCESS,
				null, null, null),
			req, null);

		ThreadReference thread = mockThread("worker-access", 4001L);
		AccessWatchpointEvent event = mockAccessEvent(thread, req, "com.Foo", "counter",
			mockIntValue(42));
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		assertLatestFieldEvent("FIELD_ACCESS", bpId, "counter", "access");
		assertThat(tracker.getLastBreakpointThread()).isSameAs(thread);
	}

	@Test
	@DisplayName("Modification event on suspending field BP records FIELD_MODIFICATION")
	void shouldRecordFieldModification() throws Exception {
		ModificationWatchpointRequest req = mock(ModificationWatchpointRequest.class);
		int bpId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "balance", BreakpointTracker.FieldWatchMode.MODIFICATION,
				null, null, null),
			null, req);

		ThreadReference thread = mockThread("worker-mod", 4002L);
		ModificationWatchpointEvent event = mockModificationEvent(thread, req, "com.Foo", "balance",
			mockIntValue(100), mockIntValue(150));
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		assertLatestFieldEvent("FIELD_MODIFICATION", bpId, "balance", "modification");
	}

	@Test
	@DisplayName("Log-only field BP records FIELD_LOGPOINT and auto-resumes")
	void shouldAutoResumeAndLogForLogpoint() throws Exception {
		AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		int bpId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.logOnly(
				"com.Foo", "ttl", BreakpointTracker.FieldWatchMode.ACCESS,
				"$oldValue", null, null, null),
			req, null);

		ThreadReference thread = mockThread("worker-log", 4003L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		IntegerValue result = mockIntValue(7);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenReturn(result);

		AccessWatchpointEvent event = mockAccessEvent(thread, req, "com.Foo", "ttl",
			mockIntValue(7));
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		assertThat(hasEventOfType("FIELD_LOGPOINT")).isTrue();
		assertThat(hasEventOfType("FIELD_ACCESS")).isFalse();
		// History should carry the field/expression/result triple so jdwp_get_events shows the value.
		EventHistory.DebugEvent logEntry = firstEventOfType("FIELD_LOGPOINT");
		assertThat(logEntry.details())
			.containsEntry("breakpointId", String.valueOf(bpId))
			.containsEntry("field", "ttl")
			.containsEntry("expression", "$oldValue")
			.containsEntry("result", "7");
	}

	@Test
	@DisplayName("False condition on field BP suppresses the hit and auto-resumes")
	void shouldAutoResumeWhenConditionFalse() throws Exception {
		ModificationWatchpointRequest req = mock(ModificationWatchpointRequest.class);
		int bpId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "level", BreakpointTracker.FieldWatchMode.MODIFICATION,
				null, null, "level > 100"),
			null, req);
		// Conditions live in the tracker's metadata map (the MCP tool layer mirrors spec.condition()
		// there); the listener reads them via breakpointTracker.getCondition(bpId), not from the spec.
		tracker.setCondition(bpId, "level > 100");

		ThreadReference thread = mockThread("worker-cond-false", 4004L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		BooleanValue falseResult = mock(BooleanValue.class);
		when(falseResult.value()).thenReturn(false);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenReturn(falseResult);

		ModificationWatchpointEvent event = mockModificationEvent(thread, req, "com.Foo", "level",
			mockIntValue(50), mockIntValue(60));
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		assertThat(hasEventOfType("FIELD_MODIFICATION")).isFalse();
		assertThat(hasEventOfType("FIELD_ACCESS")).isFalse();
		// BP-id is still recorded by setLastBreakpointThread, but we don't gate on it — condition-
		// false is a non-hit, and the caller will infer state from the absent FIELD_* event.
	}

	@Test
	@DisplayName("Reentrancy guard active → FIELD_BREAKPOINT_SUPPRESSED and auto-resume")
	void shouldSuppressRecursiveFieldEvent() throws Exception {
		AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "cache", BreakpointTracker.FieldWatchMode.ACCESS,
				null, null, null),
			req, null);

		ThreadReference thread = mockThread("worker-recursive", 4005L);
		// Mark the thread as inside MCP evaluation — production code's reentrancy guard short-circuits.
		evaluationGuard.enter(thread.uniqueID());
		try {
			AccessWatchpointEvent event = mockAccessEvent(thread, req, "com.Foo", "cache", null);
			EventSet eventSet = mockEventSet(event);

			runListenerWith(listener, eventSet);

			verify(eventSet).resume();
			assertThat(hasEventOfType("FIELD_BREAKPOINT_SUPPRESSED")).isTrue();
			assertThat(hasEventOfType("FIELD_ACCESS")).isFalse();
		} finally {
			evaluationGuard.exit(thread.uniqueID());
		}
	}

	@Test
	@DisplayName("Untracked watchpoint hit suspends thread (defensive path) — no FIELD_* event recorded")
	void shouldSuspendForUntrackedWatchpoint() throws Exception {
		// Build a WatchpointRequest that was never registered with the tracker.
		AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);

		ThreadReference thread = mockThread("worker-untracked", 4006L);
		AccessWatchpointEvent event = mockAccessEvent(thread, req, "com.Foo", "lonely", null);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Defensive: an unknown watchpoint suspends so the user sees the surprise hit rather than
		// it silently auto-resuming.
		verify(eventSet, never()).resume();
		assertThat(hasEventOfType("FIELD_ACCESS")).isFalse();
		assertThat(hasEventOfType("FIELD_MODIFICATION")).isFalse();
	}

	@Test
	@DisplayName("BOTH-mode field BP routes both event kinds to the same synthetic ID")
	void shouldRouteBothModeEventsToSameId() throws Exception {
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);
		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
		int bpId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.BOTH,
				null, null, null),
			accessReq, modReq);

		// First fire an access event ...
		ThreadReference thread1 = mockThread("worker-both-1", 4007L);
		AccessWatchpointEvent accessEvent = mockAccessEvent(thread1, accessReq, "com.Foo", "counter",
			mockIntValue(1));
		runListenerWith(listener, mockEventSet(accessEvent));

		assertLatestFieldEvent("FIELD_ACCESS", bpId, "counter", "access");

		// ... then a modification event on the same synthetic ID. The second event must record
		// against the same bpId — both reverse-index entries point to the same FieldBreakpointInfo.
		ThreadReference thread2 = mockThread("worker-both-2", 4008L);
		ModificationWatchpointEvent modEvent = mockModificationEvent(thread2, modReq, "com.Foo", "counter",
			mockIntValue(1), mockIntValue(2));
		runListenerWith(listener, mockEventSet(modEvent));

		assertLatestFieldEvent("FIELD_MODIFICATION", bpId, "counter", "modification");
	}

	@Test
	@DisplayName("Logpoint evaluator throws → FIELD_LOGPOINT_ERROR recorded, eventSet auto-resumes")
	void shouldRecordFieldLogpointErrorWhenEvaluatorThrows() throws Exception {
		AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		int bpId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.logOnly(
				"com.Foo", "session", BreakpointTracker.FieldWatchMode.ACCESS,
				"broken.expr()", null, null, null),
			req, null);

		ThreadReference thread = mockThread("worker-log-err", 4009L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any()))
			.thenThrow(new RuntimeException("compile failure"));

		AccessWatchpointEvent event = mockAccessEvent(thread, req, "com.Foo", "session",
			mockIntValue(1));
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		assertThat(hasEventOfType("FIELD_LOGPOINT_ERROR")).isTrue();
		assertThat(hasEventOfType("FIELD_LOGPOINT")).isFalse();
		EventHistory.DebugEvent errEntry = firstEventOfType("FIELD_LOGPOINT_ERROR");
		assertThat(errEntry.details())
			.containsEntry("breakpointId", String.valueOf(bpId))
			.containsEntry("field", "session")
			.containsEntry("expression", "broken.expr()");
		assertThat(errEntry.details().get("error")).contains("compile failure");
	}

	@Test
	@DisplayName("Suspending field BP hit arms chained dependent and records CHAIN_ARMED")
	void shouldArmDependentAfterSuspendingFieldHit() throws Exception {
		AccessWatchpointRequest triggerReq = mock(AccessWatchpointRequest.class);
		int triggerId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.ACCESS,
				null, null, null),
			triggerReq, null);
		com.sun.jdi.request.BreakpointRequest dependentReq = mock(com.sun.jdi.request.BreakpointRequest.class);
		int dependentId = tracker.registerBreakpoint(dependentReq);
		tracker.registerDependency(dependentId, triggerId, false);

		ThreadReference thread = mockThread("worker-chain-suspend", 4010L);
		AccessWatchpointEvent event = mockAccessEvent(thread, triggerReq, "com.Foo", "counter",
			mockIntValue(1));
		runListenerWith(listener, mockEventSet(event));

		verify(dependentReq).setEnabled(true);
		assertThat(hasEventOfType("CHAIN_ARMED")).isTrue();
		assertThat(triggerId).isPositive();
		assertThat(dependentId).isPositive();
	}

	@Test
	@DisplayName("Log-only field BP hit still arms chained dependent (chain effects propagate on auto-resume)")
	void shouldArmDependentAfterFieldLogpointHit() throws Exception {
		AccessWatchpointRequest triggerReq = mock(AccessWatchpointRequest.class);
		tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.logOnly(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.ACCESS,
				"$oldValue", null, null, null),
			triggerReq, null);
		final int triggerId = tracker.getAllFieldBreakpoints().keySet().iterator().next();

		com.sun.jdi.request.BreakpointRequest dependentReq = mock(com.sun.jdi.request.BreakpointRequest.class);
		int dependentId = tracker.registerBreakpoint(dependentReq);
		tracker.registerDependency(dependentId, triggerId, false);

		ThreadReference thread = mockThread("worker-chain-log", 4011L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		// Build mocks outside the when() chain — Mockito rejects nested stubbing.
		IntegerValue evalResult = mockIntValue(7);
		IntegerValue currentValue = mockIntValue(7);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenReturn(evalResult);
		AccessWatchpointEvent event = mockAccessEvent(thread, triggerReq, "com.Foo", "counter",
			currentValue);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Logpoint path auto-resumes the firing thread, but chain side-effects still propagate.
		verify(eventSet).resume();
		verify(dependentReq).setEnabled(true);
		assertThat(hasEventOfType("CHAIN_ARMED")).isTrue();
	}

	@Test
	@DisplayName("Condition-false on field BP does NOT propagate chain — dependent stays disarmed")
	void shouldNotPropagateChainOnFieldConditionFalse() throws Exception {
		ModificationWatchpointRequest triggerReq = mock(ModificationWatchpointRequest.class);
		int triggerId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.MODIFICATION,
				null, null, "false"),
			null, triggerReq);
		tracker.setCondition(triggerId, "false");

		com.sun.jdi.request.BreakpointRequest dependentReq = mock(com.sun.jdi.request.BreakpointRequest.class);
		int dependentId = tracker.registerBreakpoint(dependentReq);
		tracker.registerDependency(dependentId, triggerId, false);

		ThreadReference thread = mockThread("worker-chain-cond-false", 4012L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		BooleanValue falseResult = mock(BooleanValue.class);
		when(falseResult.value()).thenReturn(false);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenReturn(falseResult);

		ModificationWatchpointEvent event = mockModificationEvent(thread, triggerReq, "com.Foo", "counter",
			mockIntValue(1), mockIntValue(2));
		runListenerWith(listener, mockEventSet(event));

		// Condition-false is a non-hit — no chain side-effects propagate.
		verify(dependentReq, never()).setEnabled(true);
		assertThat(hasEventOfType("CHAIN_ARMED")).isFalse();
		assertThat(dependentId).isPositive();
	}

	@Test
	@DisplayName("Null JDI values → $oldValue / $newValue / $object keys absent from condition bindings")
	void shouldOmitNullBindingsFromFieldEventBindings() throws Exception {
		ModificationWatchpointRequest req = mock(ModificationWatchpointRequest.class);
		int bpId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "session", BreakpointTracker.FieldWatchMode.MODIFICATION,
				null, null, "true"),
			null, req);
		tracker.setCondition(bpId, "true");

		ThreadReference thread = mockThread("worker-null-bindings", 4013L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Value>> bindingsCaptor =
			(org.mockito.ArgumentCaptor<java.util.Map<String, Value>>)
				(org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		BooleanValue trueResult = mock(BooleanValue.class);
		when(trueResult.value()).thenReturn(true);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), bindingsCaptor.capture()))
			.thenReturn(trueResult);

		// Null valueCurrent, null valueToBe, null object — these correspond to a static reference
		// field being written from null to null. All three should be omitted from the bindings map.
		ModificationWatchpointEvent event = mockModificationEvent(thread, req, "com.Foo", "session",
			null, null);
		runListenerWith(listener, mockEventSet(event));

		java.util.Map<String, Value> captured = bindingsCaptor.getValue();
		assertThat(captured).containsKeys("$fieldName", "$mode");
		assertThat(captured).doesNotContainKey("$oldValue");
		assertThat(captured).doesNotContainKey("$newValue");
		assertThat(captured).doesNotContainKey("$object");
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private boolean hasEventOfType(String type) {
		return eventHistory.getRecent(50).stream().anyMatch(e -> type.equals(e.type()));
	}

	private EventHistory.DebugEvent firstEventOfType(String type) {
		return eventHistory.getRecent(50).stream()
			.filter(e -> type.equals(e.type()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no " + type + " event in history"));
	}

	private void assertLatestFieldEvent(String expectedType, int expectedBpId, String expectedField,
			String expectedMode) {
		EventHistory.DebugEvent entry = firstEventOfType(expectedType);
		assertThat(entry.type()).isEqualTo(expectedType);
		assertThat(entry.details())
			.containsEntry("breakpointId", String.valueOf(expectedBpId))
			.containsEntry("field", expectedField)
			.containsEntry("mode", expectedMode);
	}

	/**
	 * Builds an {@link AccessWatchpointEvent} mock wired to the given request, thread, class and
	 * field names. {@code valueCurrent} is optional — pass {@code null} to leave the binding
	 * out of {@code $oldValue}. The mocked {@code virtualMachine().mirrorOf(String)} chain is
	 * required because the production code synthesises {@code $fieldName} / {@code $mode} via
	 * {@code vm.mirrorOf(String)}.
	 */
	private static AccessWatchpointEvent mockAccessEvent(ThreadReference thread, WatchpointRequest req,
			String className, String fieldName, @Nullable Value valueCurrent) {
		AccessWatchpointEvent event = mock(AccessWatchpointEvent.class);
		wireWatchpointEvent(event, thread, req, className, fieldName, valueCurrent);
		return event;
	}

	private static ModificationWatchpointEvent mockModificationEvent(ThreadReference thread,
			WatchpointRequest req, String className, String fieldName,
			@Nullable Value valueCurrent, @Nullable Value valueToBe) {
		ModificationWatchpointEvent event = mock(ModificationWatchpointEvent.class);
		wireWatchpointEvent(event, thread, req, className, fieldName, valueCurrent);
		when(event.valueToBe()).thenReturn(valueToBe);
		return event;
	}

	private static void wireWatchpointEvent(WatchpointEvent event, ThreadReference thread,
			WatchpointRequest req, String className, String fieldName, @Nullable Value valueCurrent) {
		when(event.request()).thenReturn(req);
		when(event.thread()).thenReturn(thread);
		when(event.valueCurrent()).thenReturn(valueCurrent);
		// `object()` returns null for static fields; tests in this file all use static-field semantics.
		when(event.object()).thenReturn(null);

		Field field = mock(Field.class);
		when(field.name()).thenReturn(fieldName);
		ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn(className);
		when(field.declaringType()).thenReturn(declaringType);
		when(event.field()).thenReturn(field);

		VirtualMachine vm = mock(VirtualMachine.class);
		StringReference fieldNameMirror = mock(StringReference.class);
		StringReference modeAccessMirror = mock(StringReference.class);
		StringReference modeModMirror = mock(StringReference.class);
		when(vm.mirrorOf(fieldName)).thenReturn(fieldNameMirror);
		when(vm.mirrorOf("access")).thenReturn(modeAccessMirror);
		when(vm.mirrorOf("modification")).thenReturn(modeModMirror);
		when(event.virtualMachine()).thenReturn(vm);
	}

	private static IntegerValue mockIntValue(int value) {
		IntegerValue v = mock(IntegerValue.class);
		when(v.value()).thenReturn(value);
		when(v.toString()).thenReturn(String.valueOf(value));
		return v;
	}
}
