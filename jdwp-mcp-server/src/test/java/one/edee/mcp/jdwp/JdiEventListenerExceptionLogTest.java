package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ExceptionRequest;
import one.edee.mcp.jdwp.BreakpointTracker.ExceptionBreakpointSpec;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.assertLatestEventType;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.latestMeaningfulEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockException;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockExceptionEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the listener's exception-log path: when an active {@link ExceptionRequest} is registered
 * with {@code logOnly=true}, the firing thread must auto-resume and the listener must record an
 * {@code EXCEPTION_LOG} (or {@code EXCEPTION_LOG_ERROR}) entry. Pairs with
 * {@link JdiEventListenerEvaluationSuppressionTest} which covers the reentrancy-suppression path
 * and the default suspending path.
 *
 * <p>The {@link JdiExpressionEvaluator} is mocked so we can assert that the {@code $exception}
 * binding is forwarded with the thrown {@link ObjectReference}, and so we can simulate evaluation
 * failures without spinning up a target VM.
 */
class JdiEventListenerExceptionLogTest {

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

	@Test
	@DisplayName("logOnly without expression: auto-resume + EXCEPTION_LOG, evaluator untouched")
	void shouldAutoResumeAndRecordExceptionLogWhenLogOnlyWithoutExpression() throws Exception {
		ThreadReference thread = mockThread("worker-1", 100L);
		ExceptionRequest request = mock(ExceptionRequest.class);
		ObjectReference exception = mockException("java.lang.IllegalStateException");

		// logOnly=true, no expression — listener should record EXCEPTION_LOG and auto-resume.
		tracker.registerExceptionBreakpoint(
			request, ExceptionBreakpointSpec.logOnly("java.lang.IllegalStateException", true, true, null));

		ExceptionEvent event = mockExceptionEvent(request, thread, exception,
			"com.example.Service", 42);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		assertThat(tracker.getLastBreakpointThread()).isNull();
		assertLatestEventType(eventHistory, "EXCEPTION_LOG");
		// No expression → evaluator must NOT have been touched.
		verify(evaluator, never()).evaluate(any(), anyString(), any());
	}

	@Test
	@DisplayName("logOnly with expression: evaluator invoked with $exception binding, result recorded")
	void shouldEvaluateExpressionAndBindExceptionWhenLogOnlyWithExpression() throws Exception {
		ThreadReference thread = mockThread("worker-2", 200L);
		ExceptionRequest request = mock(ExceptionRequest.class);
		ObjectReference exception = mockException("java.lang.IllegalArgumentException");
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);

		// Evaluator returns a StringReference-shaped result via mock — formatter handles it.
		com.sun.jdi.StringReference msgRef = mock(com.sun.jdi.StringReference.class);
		when(msgRef.value()).thenReturn("boom");
		when(evaluator.evaluate(eq(frame), eq("$exception.getMessage()"),
			eq(Map.of("$exception", exception)))).thenReturn(msgRef);

		tracker.registerExceptionBreakpoint(
			request, ExceptionBreakpointSpec.logOnly("java.lang.IllegalArgumentException", true, true, "$exception.getMessage()"));

		ExceptionEvent event = mockExceptionEvent(request, thread, exception,
			"com.example.Validator", 17);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		verify(evaluator).configureCompilerClasspath(thread);
		verify(evaluator).evaluate(eq(frame), eq("$exception.getMessage()"),
			eq(Map.of("$exception", exception)));

		EventHistory.DebugEvent last = latestMeaningfulEvent(eventHistory);
		assertThat(last.type()).isEqualTo("EXCEPTION_LOG");
		assertThat(last.summary()).contains("$exception.getMessage()", "boom");
		assertThat(last.details()).containsEntry("expression", "$exception.getMessage()");
		assertThat(last.details()).containsEntry("result", "boom");
	}

	@Test
	@DisplayName("Evaluation failure: EXCEPTION_LOG_ERROR recorded and thread still auto-resumes")
	void shouldRecordExceptionLogErrorAndStillAutoResumeOnEvaluationFailure() throws Exception {
		ThreadReference thread = mockThread("worker-3", 300L);
		ExceptionRequest request = mock(ExceptionRequest.class);
		ObjectReference exception = mockException("java.lang.NullPointerException");
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);

		when(evaluator.evaluate(eq(frame), anyString(), any()))
			.thenThrow(new JdiEvaluationException("compile failed"));

		tracker.registerExceptionBreakpoint(
			request, ExceptionBreakpointSpec.logOnly("java.lang.NullPointerException", true, true, "$exception.getCause().getMessage()"));

		ExceptionEvent event = mockExceptionEvent(request, thread, exception,
			"com.example.Foo", 5);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Auto-resume must still happen — a bad expression cannot leave the thread parked.
		verify(eventSet).resume();
		assertLatestEventType(eventHistory, "EXCEPTION_LOG_ERROR");
		EventHistory.DebugEvent last = latestMeaningfulEvent(eventHistory);
		assertThat(last.summary()).contains("compile failed");
	}

	@Test
	@DisplayName("logOnly=false (default): suspends, populates tracker, records EXCEPTION")
	void shouldSuspendAndRecordExceptionWhenLogOnlyFalse() throws Exception {
		// Regression guard: default behaviour (logOnly=false) must keep the thread suspended and
		// record EXCEPTION — same as before this feature was introduced.
		ThreadReference thread = mockThread("worker-4", 400L);
		ExceptionRequest request = mock(ExceptionRequest.class);
		ObjectReference exception = mockException("java.lang.RuntimeException");

		tracker.registerExceptionBreakpoint(
			request, ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));

		ExceptionEvent event = mockExceptionEvent(request, thread, exception,
			"com.example.Worker", 99);
		EventSet eventSet = mockEventSet(event);
		CountDownLatch latch = tracker.armNextEventLatch();

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		assertThat(tracker.getLastBreakpointThread()).isSameAs(thread);
		assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
		assertLatestEventType(eventHistory, "EXCEPTION");
		// F-RA2: the snapshot kind must be EXCEPTION with no BP id so the renderer can produce
		// "via=exception" instead of echoing a stale breakpoint id from a previous BP hit.
		BreakpointTracker.LastBreakpoint snapshot = tracker.getLastBreakpoint();
		assertThat(snapshot).isNotNull();
		assertThat(snapshot.kind()).isEqualTo(BreakpointTracker.EventKind.EXCEPTION);
		assertThat(snapshot.id()).isNull();
	}

	@Test
	@DisplayName("Suspending path with non-null catch location formats catchLocation as ClassName:lineNumber")
	void shouldFormatCatchLocationWhenSuspendingExceptionHasCatchSite() throws Exception {
		// Pins the formatting branch in handleExceptionEvent: when catchLocation is non-null, the
		// recorded EXCEPTION details map must contain "ClassName:lineNumber", not the "uncaught"
		// sentinel used for null catch sites.
		ThreadReference thread = mockThread("worker-catch", 500L);
		ExceptionRequest request = mock(ExceptionRequest.class);
		ObjectReference exception = mockException("java.lang.IllegalStateException");

		tracker.registerExceptionBreakpoint(
			request, ExceptionBreakpointSpec.suspending("java.lang.IllegalStateException", true, true));

		ExceptionEvent event = mockExceptionEvent(request, thread, exception,
			"com.example.Throwing", 11, "com.example.Catching", 27);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet, never()).resume();
		EventHistory.DebugEvent last = latestMeaningfulEvent(eventHistory);
		assertThat(last.type()).isEqualTo("EXCEPTION");
		assertThat(last.details()).containsEntry("catchLocation", "com.example.Catching:27");
		assertThat(last.summary()).contains("caught at com.example.Catching:27");
	}

	@Test
	@DisplayName("Log-only path with non-null catch location records catchLocation in details")
	void shouldFormatCatchLocationWhenLogOnlyExceptionHasCatchSite() throws Exception {
		// Same branch as above but exercised via the log-only auto-resume path — both paths
		// share the same catch-location formatter and must produce the same string shape.
		ThreadReference thread = mockThread("worker-log-catch", 600L);
		ExceptionRequest request = mock(ExceptionRequest.class);
		ObjectReference exception = mockException("java.lang.IllegalArgumentException");

		tracker.registerExceptionBreakpoint(
			request, ExceptionBreakpointSpec.logOnly("java.lang.IllegalArgumentException", true, true, null));

		ExceptionEvent event = mockExceptionEvent(request, thread, exception,
			"com.example.Throwing", 18, "com.example.Handler", 73);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		EventHistory.DebugEvent last = latestMeaningfulEvent(eventHistory);
		assertThat(last.type()).isEqualTo("EXCEPTION_LOG");
		assertThat(last.details()).containsEntry("catchLocation", "com.example.Handler:73");
	}

	/**
	 * Pins the condition gate on log-only exception BPs: when a condition is attached to the
	 * exception logpoint and evaluates to {@code false}, the listener must auto-resume the event
	 * set WITHOUT recording an {@code EXCEPTION_LOG} entry, WITHOUT evaluating the log expression,
	 * and WITHOUT propagating chain effects to dependents. Mirrors the line-BP condition-false
	 * carve-out: a skipped condition is not a meaningful trigger, so dependents must remain
	 * disabled.
	 */
	@Test
	@DisplayName("Log-only exception BP with condition false: auto-resume, no log, no chain effects")
	void shouldSkipExceptionLogAndChainWhenConditionFalse() throws Exception {
		ThreadReference thread = mockThread("worker-cond-false", 700L);
		ExceptionRequest request = mock(ExceptionRequest.class);
		ObjectReference exception = mockException("java.lang.RuntimeException");
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);

		// Register the log-only exception BP WITH an expression — so we can also assert the
		// expression is NOT evaluated when the condition is false (only the condition itself runs).
		int exId = tracker.registerExceptionBreakpoint(
			request, ExceptionBreakpointSpec.logOnly("java.lang.RuntimeException", true, true,
				"$exception.getMessage()"));

		// Wire a disabled line-BP dependent on the exception BP so we can assert no chain arming.
		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, exId, false);
		when(dependentBp.isEnabled()).thenReturn(false);

		// Condition evaluator stub — returns primitive boolean false.
		tracker.setCondition(exId, "$exception.getMessage() != null");
		com.sun.jdi.BooleanValue falseVal = mock(com.sun.jdi.BooleanValue.class);
		when(falseVal.value()).thenReturn(false);
		when(evaluator.evaluate(eq(frame), eq("$exception.getMessage() != null"),
			eq(Map.of("$exception", exception)))).thenReturn(falseVal);

		ExceptionEvent event = mockExceptionEvent(request, thread, exception,
			"com.example.Worker", 42);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Thread auto-resumes — a condition-false exception logpoint must not park the thread.
		verify(eventSet).resume();
		// No EXCEPTION_LOG (or EXCEPTION_LOG_ERROR) entry was recorded.
		assertThat(eventHistory.getRecent(20))
			.extracting(EventHistory.DebugEvent::type)
			.doesNotContain("EXCEPTION_LOG", "EXCEPTION_LOG_ERROR");
		// Chain effects must be skipped — dependent stays disabled, no CHAIN_ARMED entry.
		verify(dependentBp, never()).setEnabled(true);
		assertThat(eventHistory.getRecent(20))
			.extracting(EventHistory.DebugEvent::type)
			.doesNotContain("CHAIN_ARMED");
		// Exactly one evaluator invocation — the condition. The log expression must NOT run.
		verify(evaluator).evaluate(eq(frame), eq("$exception.getMessage() != null"),
			eq(Map.of("$exception", exception)));
		verify(evaluator, org.mockito.Mockito.times(1))
			.evaluate(any(), anyString(), any());
	}

	/**
	 * Counterpart to {@code shouldSkipExceptionLogAndChainWhenConditionFalse}: when the condition
	 * evaluates to {@code true} the listener proceeds with the normal log-only flow — it runs the
	 * log expression, records an {@code EXCEPTION_LOG} entry with the formatted result, and arms
	 * any dependents. Verifies that the evaluator is consulted exactly twice (condition, then
	 * expression) so the condition gate does not accidentally short-circuit the happy path.
	 */
	@Test
	@DisplayName("Log-only exception BP with condition true: expression runs, EXCEPTION_LOG recorded, chain armed")
	void shouldEvaluateExpressionAndRecordExceptionLogWhenConditionTrue() throws Exception {
		ThreadReference thread = mockThread("worker-cond-true", 701L);
		ExceptionRequest request = mock(ExceptionRequest.class);
		ObjectReference exception = mockException("java.lang.RuntimeException");
		StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);

		int exId = tracker.registerExceptionBreakpoint(
			request, ExceptionBreakpointSpec.logOnly("java.lang.RuntimeException", true, true,
				"$exception.getMessage()"));

		BreakpointRequest dependentBp = mock(BreakpointRequest.class);
		int depId = tracker.registerBreakpoint(dependentBp);
		tracker.registerDependency(depId, exId, false);
		when(dependentBp.isEnabled()).thenReturn(false);

		tracker.setCondition(exId, "$exception.getMessage() != null");
		// Distinct stubs for condition vs. expression so we can assert both ran in the correct order.
		com.sun.jdi.BooleanValue trueVal = mock(com.sun.jdi.BooleanValue.class);
		when(trueVal.value()).thenReturn(true);
		when(evaluator.evaluate(eq(frame), eq("$exception.getMessage() != null"),
			eq(Map.of("$exception", exception)))).thenReturn(trueVal);
		com.sun.jdi.StringReference msgRef = mock(com.sun.jdi.StringReference.class);
		when(msgRef.value()).thenReturn("boom");
		when(evaluator.evaluate(eq(frame), eq("$exception.getMessage()"),
			eq(Map.of("$exception", exception)))).thenReturn(msgRef);

		ExceptionEvent event = mockExceptionEvent(request, thread, exception,
			"com.example.Worker", 43);
		EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		verify(eventSet).resume();
		// EXCEPTION_LOG must be recorded — find it amongst recent entries (CHAIN_ARMED follows it
		// because chain effects are applied AFTER the log entry is written).
		EventHistory.DebugEvent logEntry = eventHistory.getRecent(20).stream()
			.filter(e -> "EXCEPTION_LOG".equals(e.type()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("expected an EXCEPTION_LOG entry in history"));
		assertThat(logEntry.details()).containsEntry("expression", "$exception.getMessage()");
		assertThat(logEntry.details()).containsEntry("result", "boom");
		// Chain effects must fire — dependent gets armed.
		verify(dependentBp).setEnabled(true);
		assertThat(eventHistory.getRecent(20))
			.extracting(EventHistory.DebugEvent::type)
			.contains("CHAIN_ARMED");
		// Exactly two evaluator invocations: the condition, then the log expression.
		verify(evaluator, org.mockito.Mockito.times(2))
			.evaluate(any(), anyString(), any());
	}
}
