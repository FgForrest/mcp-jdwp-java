package one.edee.mcp.jdwp;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
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
 * Exercises every branch of {@link JdiEventListener}'s {@code evaluateCondition} helper:
 * <ul>
 *   <li>boxed {@code java.lang.Boolean(true)} returned from the evaluator → BP suspends and
 *       records {@code BREAKPOINT};</li>
 *   <li>boxed {@code java.lang.Boolean(false)} → BP auto-resumes without recording
 *       {@code BREAKPOINT};</li>
 *   <li>non-boolean result (e.g. {@link IntegerValue}) → fail-safe path returns {@code true},
 *       BP suspends so the user sees the bad condition rather than silently skipping;</li>
 *   <li>evaluator throws {@link JdiEvaluationException} → same fail-safe path: BP suspends.</li>
 * </ul>
 *
 * <p>The primitive {@link BooleanValue} branch is already covered in
 * {@code JdiEventListenerChainTest.shouldNotPropagateChainOnConditionFalse} /
 * {@code shouldPropagateChainOnConditionTrue}, so it is not duplicated here. The boxed-Boolean
 * paths are the autoboxing-via-wrapper-class case that the production code handles by reading
 * the wrapper's internal {@code value} field — a non-trivial JDI walk that needs its own pin.
 */
class JdiEventListenerConditionEvaluationTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private JdiExpressionEvaluator evaluator;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator, new EvaluationGuard(), null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("Boxed java.lang.Boolean(true) → BP suspends and records BREAKPOINT")
	void shouldSuspendWhenConditionReturnsBoxedBooleanTrue() throws Exception {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		int bpId = tracker.registerBreakpoint(bp);
		tracker.setCondition(bpId, "isHot()");

		ThreadReference thread = mockThread("worker-boxed-true", 1100L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		// Build the boxed-Boolean mock chain BEFORE the evaluator stubbing — Mockito does not
		// allow nesting mock creation inside another .thenReturn(...) argument.
		ObjectReference boxedTrue = boxedBoolean(true);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenReturn(boxedTrue);

		BreakpointEvent event = mockBreakpointEvent(thread, bp, "com.Foo", 10);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		assertThat(hasEventOfType("BREAKPOINT")).isTrue();
	}

	@Test
	@DisplayName("Boxed java.lang.Boolean(false) → BP auto-resumes, no BREAKPOINT recorded")
	void shouldAutoResumeWhenConditionReturnsBoxedBooleanFalse() throws Exception {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		int bpId = tracker.registerBreakpoint(bp);
		tracker.setCondition(bpId, "isHot()");

		ThreadReference thread = mockThread("worker-boxed-false", 1101L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		ObjectReference boxedFalse = boxedBoolean(false);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenReturn(boxedFalse);

		BreakpointEvent event = mockBreakpointEvent(thread, bp, "com.Foo", 11);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		assertThat(hasEventOfType("BREAKPOINT")).isFalse();
	}

	@Test
	@DisplayName("Non-boolean condition result → fail-safe: BP suspends and records BREAKPOINT")
	void shouldSuspendWhenConditionReturnsNonBoolean() throws Exception {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		int bpId = tracker.registerBreakpoint(bp);
		tracker.setCondition(bpId, "someInt");

		ThreadReference thread = mockThread("worker-int-cond", 1102L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		IntegerValue intResult = mock(IntegerValue.class);
		when(intResult.value()).thenReturn(42);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenReturn(intResult);

		BreakpointEvent event = mockBreakpointEvent(thread, bp, "com.Foo", 12);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Fail-safe: a non-boolean condition is treated as "suspend so the user can investigate".
		verify(eventSet, never()).resume();
		assertThat(hasEventOfType("BREAKPOINT")).isTrue();
	}

	@Test
	@DisplayName("Evaluator throws → fail-safe: BP suspends and records BREAKPOINT")
	void shouldSuspendWhenConditionEvaluatorThrows() throws Exception {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		int bpId = tracker.registerBreakpoint(bp);
		tracker.setCondition(bpId, "broken.expression()");

		ThreadReference thread = mockThread("worker-cond-fail", 1103L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any()))
			.thenThrow(new JdiEvaluationException("compile failed"));

		BreakpointEvent event = mockBreakpointEvent(thread, bp, "com.Foo", 13);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Fail-safe: any error in the condition pipeline must keep the BP suspended so the user
		// can see the failure rather than silently miss the hit.
		verify(eventSet, never()).resume();
		assertThat(hasEventOfType("BREAKPOINT")).isTrue();
	}

	/**
	 * {@code evaluateCondition} catches every exception (including {@link InterruptedException})
	 * via the blanket {@code catch (Exception e)} block at the end of the method — the fail-safe
	 * still suspends the BP, but the catch must re-arm the interrupt status on the firing thread
	 * before returning so a subsequent blocking call on the listener thread observes the interrupt.
	 * Losing it would silently postpone listener-thread shutdown signalling.
	 */
	@Test
	@DisplayName("evaluateCondition preserves interrupt status when the evaluator throws InterruptedException")
	void shouldPreserveInterruptStatusWhenConditionEvaluatorThrowsInterrupted() throws Exception {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		int bpId = tracker.registerBreakpoint(bp);
		tracker.setCondition(bpId, "neverTerminates()");

		ThreadReference thread = mockThread("worker-cond-interrupt", 1104L);
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);

		// Capture the listener thread inside the evaluator answer so we can verify the interrupt
		// status was re-armed after the catch in evaluateCondition returned. Use a one-shot latch
		// to wait deterministically for the answer to run instead of polling.
		java.util.concurrent.atomic.AtomicReference<Thread> listenerThreadRef =
			new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.CountDownLatch evaluatorEntered = new java.util.concurrent.CountDownLatch(1);
		// Mockito rejects checked-throws for methods that don't declare them, so we throw the
		// InterruptedException through thenAnswer which has no signature validation.
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any()))
			.thenAnswer(invocation -> {
				listenerThreadRef.set(Thread.currentThread());
				evaluatorEntered.countDown();
				throw new InterruptedException("simulated interrupt");
			});

		BreakpointEvent event = mockBreakpointEvent(thread, bp, "com.Foo", 14);
		EventSet eventSet = mockEventSet(event);

		// Drive the listener with a single event set and let the listener exit through its own
		// InterruptedException catch (triggered by evaluateCondition restoring the interrupt). The
		// standard runListenerWith helper appends a VMDisconnectedException sentinel that the
		// queue.remove() call cannot reach once the interrupt is set — so we hand-roll the driver
		// here for full control over the post-event behaviour.
		com.sun.jdi.VirtualMachine vm = mock(com.sun.jdi.VirtualMachine.class);
		com.sun.jdi.event.EventQueue queue = mock(com.sun.jdi.event.EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);
		java.util.concurrent.atomic.AtomicInteger removeCalls = new java.util.concurrent.atomic.AtomicInteger();
		when(queue.remove()).thenAnswer(invocation -> {
			if (removeCalls.getAndIncrement() == 0) {
				return eventSet;
			}
			// Second remove sees the interrupt set by evaluateCondition's catch.
			throw new InterruptedException("queue.remove sees interrupt");
		});

		listener.start(vm);
		assertThat(evaluatorEntered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		// Give the listener a moment to finish processing the event set and exit via its
		// InterruptedException catch.
		Thread.sleep(50);

		// Fail-safe still applies: the BP suspends and a BREAKPOINT entry lands.
		verify(eventSet, never()).resume();
		assertThat(hasEventOfType("BREAKPOINT")).isTrue();

		// Interrupt-preservation contract: the listener thread must have observed the interrupt
		// status, otherwise its next blocking call would silently miss the shutdown signal.
		// listen()'s InterruptedException catch then re-sets it via Thread.currentThread().interrupt()
		// so the status is still observable on the (dead) listener thread.
		assertThat(listenerThreadRef.get())
			.as("evaluator answer must have run on the listener thread")
			.isNotNull();
		assertThat(listenerThreadRef.get().isInterrupted())
			.as("evaluateCondition must restore interrupt status when the evaluator throws InterruptedException")
			.isTrue();
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private boolean hasEventOfType(String type) {
		return eventHistory.getRecent(20).stream().anyMatch(e -> type.equals(e.type()));
	}

	/**
	 * Builds an {@link ObjectReference} mock that masquerades as a boxed {@code java.lang.Boolean}
	 * holding the given primitive value. The production code reads the wrapper's internal
	 * {@code value} field via JDI reflection — this mock wires that field-read path end to end so
	 * the boxed-Boolean branch of {@code evaluateCondition} actually executes.
	 */
	private static ObjectReference boxedBoolean(boolean value) {
		ObjectReference boxed = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn("java.lang.Boolean");
		when(boxed.referenceType()).thenReturn(refType);

		Field valueField = mock(Field.class);
		when(refType.fieldByName("value")).thenReturn(valueField);
		BooleanValue inner = mock(BooleanValue.class);
		when(inner.value()).thenReturn(value);
		when(boxed.getValue(valueField)).thenReturn(inner);
		return boxed;
	}
}
