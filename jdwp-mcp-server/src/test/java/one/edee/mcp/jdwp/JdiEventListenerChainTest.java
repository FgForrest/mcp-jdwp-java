package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ExceptionRequest;
import one.edee.mcp.jdwp.BreakpointTracker.ExceptionBreakpointSpec;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockException;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockExceptionEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link JdiEventListener} applies chain effects after a BP hit: it arms any
 * dependent BPs registered against the firing BP and, when the firing BP is itself a one-shot
 * dependent, re-disarms it. Covers the suspending path, the logpoint auto-resume path, the
 * condition-false auto-resume path, the exception-BP path (both suspending and log-only), and
 * the failure-isolation contract (a thrown {@code setEnabled} on a dependent must not break the
 * listener loop).
 *
 * <p>Uses {@link JdiEventListenerTestSupport} for the shared listener-driving scaffold. Only the
 * test-event factories live locally because their argument shapes differ per scenario.
 */
class JdiEventListenerChainTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private EvaluationGuard evaluationGuard;
	private JdiExpressionEvaluator evaluator;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		evaluationGuard = new EvaluationGuard();
		evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator, evaluationGuard, null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	/**
	 * Even when no dependents are currently waiting, the listener must still record that the
	 * trigger fired. A chained dependent registered later (e.g. promoted from a pending entry)
	 * will consult {@link BreakpointTracker#hasTriggerFired} on promotion and come up armed.
	 */
	@Test
	@DisplayName("BP hit records markTriggerFired even with no current dependents")
	void shouldMarkTriggerFiredEvenWithNoDependents() throws Exception {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		int bpId = tracker.registerBreakpoint(bp);

		ThreadReference thread = mockThread("worker", 100L);
		BreakpointEvent event = mockBreakpointEvent(thread, bp, "com.Foo", 10);
		runListenerWith(listener, mockEventSet(event));

		assertThat(tracker.hasTriggerFired(bpId)).isTrue();
	}

	@Test
	@DisplayName("Trigger BP hit arms a disabled dependent and records CHAIN_ARMED")
	void triggerHitArmsDependent() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		// Dependent must report disabled so the listener sees an arming opportunity.
		when(dependentBp.isEnabled()).thenReturn(false);

		ThreadReference thread = mockThread("worker", 100L);
		BreakpointEvent event = mockBreakpointEvent(thread, triggerBp, "com.Foo", 10);
		runListenerWith(listener, mockEventSet(event));

		verify(dependentBp).setEnabled(true);
		assertThat(hasEventOfType("CHAIN_ARMED")).isTrue();
	}

	@Test
	@DisplayName("Already-armed dependent: arming is idempotent and CHAIN_ARMED is still recorded")
	void shouldReArmAlreadyEnabledDependentIdempotently() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		when(dependentBp.isEnabled()).thenReturn(true);

		ThreadReference thread = mockThread("worker", 100L);
		BreakpointEvent event = mockBreakpointEvent(thread, triggerBp, "com.Foo", 10);
		runListenerWith(listener, mockEventSet(event));

		// New contract: the listener always re-arms via setBreakpointEnabledById and always records
		// CHAIN_ARMED. The redundant call is the cost of treating "the BP" as a single logical
		// unit — for a BOTH-mode field BP "is already armed?" cannot be answered correctly from a
		// single EventRequest, so the short-circuit was removed. JDI tolerates the redundant
		// setEnabled(true) at no observable cost.
		verify(dependentBp).setEnabled(true);
		assertThat(hasEventOfType("CHAIN_ARMED")).isTrue();
	}

	@Test
	@DisplayName("One-shot dependent firing re-disarms itself and records CHAIN_DISARMED")
	void oneShotDependentDisarmsSelf() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, true);

		ThreadReference thread = mockThread("worker", 100L);
		BreakpointEvent event = mockBreakpointEvent(thread, dependentBp, "com.Bar", 20);
		runListenerWith(listener, mockEventSet(event));

		verify(dependentBp).setEnabled(false);
		assertThat(hasEventOfType("CHAIN_DISARMED")).isTrue();
	}

	@Test
	@DisplayName("Sticky dependent firing leaves itself enabled — no disarm event")
	void stickyDependentStaysArmed() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);

		ThreadReference thread = mockThread("worker", 100L);
		BreakpointEvent event = mockBreakpointEvent(thread, dependentBp, "com.Bar", 20);
		runListenerWith(listener, mockEventSet(event));

		verify(dependentBp, never()).setEnabled(false);
		assertThat(hasEventOfType("CHAIN_DISARMED")).isFalse();
	}

	@Test
	@DisplayName("Chained BP with no dependents: no chain events at all")
	void shouldEmitNoChainEventsWhenChainEmpty() throws Exception {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		tracker.registerBreakpoint(bp);

		ThreadReference thread = mockThread("worker", 100L);
		BreakpointEvent event = mockBreakpointEvent(thread, bp, "com.Plain", 5);
		runListenerWith(listener, mockEventSet(event));

		assertThat(hasEventOfType("CHAIN_ARMED")).isFalse();
		assertThat(hasEventOfType("CHAIN_DISARMED")).isFalse();
		verify(bp, never()).setEnabled(anyBoolean());
	}

	/**
	 * When a logpoint fires, the listener evaluates the expression, records a {@code LOGPOINT}
	 * entry, auto-resumes the event set — and STILL applies chain effects. The dependent must
	 * therefore be armed and a {@code CHAIN_ARMED} event must be recorded alongside the
	 * {@code LOGPOINT} entry. Mirrors the suspending-BP path but on the auto-resume branch.
	 */
	@Test
	@DisplayName("Logpoint hit arms dependent, records CHAIN_ARMED, and auto-resumes")
	void shouldArmDependentsAfterLogpointHit() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		when(dependentBp.isEnabled()).thenReturn(false);

		// Wire the trigger BP as a logpoint with an expression that the mocked evaluator handles.
		tracker.setLogpointExpression(triggerId, "\"x=\" + x");

		ThreadReference thread = mockThread("worker-log", 700L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		com.sun.jdi.StringReference resultRef = mock(com.sun.jdi.StringReference.class);
		when(resultRef.value()).thenReturn("x=5");
		when(evaluator.evaluate(any(), anyString(), any())).thenReturn(resultRef);

		BreakpointEvent event = mockBreakpointEvent(thread, triggerBp, "com.Foo", 11);
		EventSet eventSet = mockEventSet(event);
		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		verify(dependentBp).setEnabled(true);
		assertThat(hasEventOfType("LOGPOINT")).isTrue();
		assertThat(hasEventOfType("CHAIN_ARMED")).isTrue();
	}

	/**
	 * A conditional BP whose condition evaluates to FALSE is not a "real" hit from the user's
	 * point of view, so the listener auto-resumes without recording {@code BREAKPOINT} and
	 * WITHOUT applying chain effects. Pins the deliberate carve-out in
	 * {@link JdiEventListener#handleBreakpointEvent}.
	 */
	@Test
	@DisplayName("Condition-false BP: no chain effects, no BREAKPOINT recorded, auto-resumes")
	void shouldNotPropagateChainOnConditionFalse() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		when(dependentBp.isEnabled()).thenReturn(false);

		tracker.setCondition(triggerId, "i > 100");

		ThreadReference thread = mockThread("worker-cond", 800L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		// Condition evaluator returns boolean primitive false.
		com.sun.jdi.BooleanValue falseVal = mock(com.sun.jdi.BooleanValue.class);
		when(falseVal.value()).thenReturn(false);
		when(evaluator.evaluate(any(), anyString(), any())).thenReturn(falseVal);

		BreakpointEvent event = mockBreakpointEvent(thread, triggerBp, "com.Foo", 22);
		EventSet eventSet = mockEventSet(event);
		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		verify(dependentBp, never()).setEnabled(anyBoolean());
		assertThat(hasEventOfType("BREAKPOINT")).isFalse();
		assertThat(hasEventOfType("CHAIN_ARMED")).isFalse();
	}

	/**
	 * Counterpart to {@code shouldNotPropagateChainOnConditionFalse}: a conditional BP whose
	 * condition evaluates to TRUE behaves like a plain BP — it stays suspended, records
	 * {@code BREAKPOINT}, and arms its dependents.
	 */
	@Test
	@DisplayName("Condition-true BP: BREAKPOINT recorded + chain armed")
	void shouldPropagateChainOnConditionTrue() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		when(dependentBp.isEnabled()).thenReturn(false);

		tracker.setCondition(triggerId, "i > 0");

		ThreadReference thread = mockThread("worker-cond-true", 801L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		com.sun.jdi.BooleanValue trueVal = mock(com.sun.jdi.BooleanValue.class);
		when(trueVal.value()).thenReturn(true);
		when(evaluator.evaluate(any(), anyString(), any())).thenReturn(trueVal);

		BreakpointEvent event = mockBreakpointEvent(thread, triggerBp, "com.Foo", 33);
		EventSet eventSet = mockEventSet(event);
		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		verify(dependentBp).setEnabled(true);
		assertThat(hasEventOfType("BREAKPOINT")).isTrue();
		assertThat(hasEventOfType("CHAIN_ARMED")).isTrue();
	}

	/**
	 * BP with BOTH a condition and a logpoint expression: when the condition is false, neither
	 * the logpoint expression is evaluated nor are chain effects applied — the BP is fully
	 * skipped this hit.
	 */
	@Test
	@DisplayName("Logpoint with condition-false: no chain effects, expression not evaluated")
	void shouldNotPropagateChainOnLogpointConditionFalse() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		when(dependentBp.isEnabled()).thenReturn(false);

		tracker.setCondition(triggerId, "i > 100");
		tracker.setLogpointExpression(triggerId, "\"x=\" + x");

		ThreadReference thread = mockThread("worker-cond-log", 802L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		com.sun.jdi.BooleanValue falseVal = mock(com.sun.jdi.BooleanValue.class);
		when(falseVal.value()).thenReturn(false);
		when(evaluator.evaluate(any(), anyString(), any())).thenReturn(falseVal);

		BreakpointEvent event = mockBreakpointEvent(thread, triggerBp, "com.Foo", 44);
		EventSet eventSet = mockEventSet(event);
		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		verify(dependentBp, never()).setEnabled(anyBoolean());
		// Exactly one evaluator call — the condition. The logpoint expression must NOT have been
		// evaluated because the condition gated it out. Both paths share the 3-arg overload, so
		// the assertion is "the evaluator was called once, not twice".
		verify(evaluator, org.mockito.Mockito.times(1)).evaluate(any(), anyString(), any());
		assertThat(hasEventOfType("LOGPOINT")).isFalse();
		assertThat(hasEventOfType("CHAIN_ARMED")).isFalse();
	}

	/**
	 * A suspending exception BP firing arms its dependents and records {@code CHAIN_ARMED}
	 * alongside the usual {@code EXCEPTION} entry. The exception path is symmetric to the
	 * line-BP path.
	 */
	@Test
	@DisplayName("Suspending exception BP arms dependent and records CHAIN_ARMED")
	void shouldArmDependentsAfterExceptionBpHitWhenSuspending() throws Exception {
		ExceptionRequest exReq = mock(ExceptionRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerExceptionBreakpoint(exReq,
			ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		when(dependentBp.isEnabled()).thenReturn(false);

		ThreadReference thread = mockThread("worker-ex", 900L);
		ObjectReference exception = mockException("java.lang.RuntimeException");
		ExceptionEvent event = mockExceptionEvent(exReq, thread, exception, "com.Foo", 55);
		runListenerWith(listener, mockEventSet(event));

		verify(dependentBp).setEnabled(true);
		assertThat(hasEventOfType("EXCEPTION")).isTrue();
		assertThat(hasEventOfType("CHAIN_ARMED")).isTrue();
	}

	/**
	 * A log-only exception BP firing arms its dependents and records {@code CHAIN_ARMED} on the
	 * auto-resume path. Mirrors the logpoint case but for the exception side of the state
	 * machine.
	 */
	@Test
	@DisplayName("Log-only exception BP arms dependent + auto-resumes")
	void shouldArmDependentsAfterLogOnlyExceptionHit() throws Exception {
		ExceptionRequest exReq = mock(ExceptionRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerExceptionBreakpoint(exReq,
			ExceptionBreakpointSpec.logOnly("java.lang.RuntimeException", true, true, null));
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		when(dependentBp.isEnabled()).thenReturn(false);

		ThreadReference thread = mockThread("worker-ex-log", 901L);
		ObjectReference exception = mockException("java.lang.RuntimeException");
		ExceptionEvent event = mockExceptionEvent(exReq, thread, exception, "com.Foo", 56);
		EventSet eventSet = mockEventSet(event);
		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		verify(dependentBp).setEnabled(true);
		assertThat(hasEventOfType("EXCEPTION_LOG")).isTrue();
		assertThat(hasEventOfType("CHAIN_ARMED")).isTrue();
	}

	/**
	 * A one-shot log-only exception BP must re-disarm itself after firing — the auto-resume
	 * path must still honour the {@code oneShot} flag exactly like the suspending path.
	 */
	@Test
	@DisplayName("One-shot log-only exception BP disarms itself after firing")
	void shouldDisarmOneShotExceptionBpAfterLogOnlyHit() throws Exception {
		ExceptionRequest exReq = mock(ExceptionRequest.class);
		BreakpointRequest triggerLineBp = mock(BreakpointRequest.class);
		int triggerLineId = tracker.registerBreakpoint(triggerLineBp);
		int exId = tracker.registerExceptionBreakpoint(exReq,
			ExceptionBreakpointSpec.logOnly("java.lang.RuntimeException", true, true, null));
		// Chain: exception BP is the DEPENDENT, depends on a line BP. One-shot.
		tracker.registerDependency(exId, triggerLineId, true);

		ThreadReference thread = mockThread("worker-ex-oneshot", 902L);
		ObjectReference exception = mockException("java.lang.RuntimeException");
		ExceptionEvent event = mockExceptionEvent(exReq, thread, exception, "com.Foo", 57);
		EventSet eventSet = mockEventSet(event);
		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		// The exception request itself should be re-disabled after firing (one-shot self-disarm).
		verify(exReq).setEnabled(false);
		assertThat(hasEventOfType("CHAIN_DISARMED")).isTrue();
	}

	/**
	 * Defensive: when a dependent's {@code setEnabled(true)} throws (e.g., dependent already
	 * removed mid-event by another thread), the chain handler must swallow the failure and
	 * keep the listener loop alive — otherwise a single rogue dependent could break unrelated
	 * events for the rest of the session.
	 */
	@Test
	@DisplayName("setEnabled failure on dependent is swallowed by the chain handler")
	void shouldNotPropagateSetEnabledFailureToCallerFromChainHandler() throws Exception {
		BreakpointRequest triggerBp = mock(BreakpointRequest.class);
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int triggerId = tracker.registerBreakpoint(triggerBp);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, triggerId, false);
		when(dependentBp.isEnabled()).thenReturn(false);
		doThrow(new RuntimeException("simulated arm failure"))
			.when(dependentBp).setEnabled(true);

		ThreadReference thread = mockThread("worker-fail", 1000L);
		BreakpointEvent event = mockBreakpointEvent(thread, triggerBp, "com.Foo", 60);

		// runListenerWith asserts the listener loop drains both events cleanly — if the chain
		// handler propagated the exception, the listener thread would die and the latch in the
		// support helper would time out.
		runListenerWith(listener, mockEventSet(event));

		// The trigger BP still produced a BREAKPOINT entry on the suspending path.
		assertThat(hasEventOfType("BREAKPOINT")).isTrue();
		// No CHAIN_ARMED was recorded because the setEnabled call failed before the record.
		assertThat(hasEventOfType("CHAIN_ARMED")).isFalse();
	}

	// ── Helpers ──

	private boolean hasEventOfType(String type) {
		return eventHistory.getRecent(20).stream().anyMatch(e -> type.equals(e.type()));
	}
}
