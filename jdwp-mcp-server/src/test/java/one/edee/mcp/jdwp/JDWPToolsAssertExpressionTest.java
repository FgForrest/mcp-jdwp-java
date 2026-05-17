package one.edee.mcp.jdwp;

import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JDWPTools#jdwp_assert_expression}: OK / MISMATCH formatting, the surrounding-
 * quote stripping branch, the {@code threadId=null} fallback to the last breakpoint thread, the
 * suspended-state guard, and the evaluator-throws path.
 */
@DisplayName("jdwp_assert_expression")
class JDWPToolsAssertExpressionTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private JdiExpressionEvaluator evaluator;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
	}

	private ThreadReference suspendedThread(long id) {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(id);
		when(thread.isSuspended()).thenReturn(true);
		return thread;
	}

	@Test
	@DisplayName("returns OK when the actual and expected values match")
	void shouldReturnOkWhenValuesMatch() throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		final StackFrame frame = mock(StackFrame.class);
		final Value result = mock(Value.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(frame, "x")).thenReturn(result);
		when(jdiService.formatFieldValue(result)).thenReturn("5");

		final String out = tools.jdwp_assert_expression("x", "5", 1L, null);

		assertThat(out).startsWith("OK").contains("x = 5");
	}

	@Test
	@DisplayName("returns MISMATCH with both values when they differ")
	void shouldReturnMismatchWhenValuesDiffer() throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		final StackFrame frame = mock(StackFrame.class);
		final Value result = mock(Value.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(frame, "x")).thenReturn(result);
		when(jdiService.formatFieldValue(result)).thenReturn("5");

		final String out = tools.jdwp_assert_expression("x", "6", 1L, null);

		assertThat(out).startsWith("MISMATCH")
			.contains("expected: 6")
			.contains("actual:   5");
	}

	/**
	 * The formatter wraps strings in double quotes; the comparator strips them so the user can
	 * pass either {@code expected="hello"} or {@code expected=hello} and have it match.
	 */
	@Test
	@DisplayName("strips surrounding double quotes before comparing string results")
	void shouldStripSurroundingQuotesBeforeComparing() throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		final StackFrame frame = mock(StackFrame.class);
		final Value result = mock(Value.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(frame, "name")).thenReturn(result);
		when(jdiService.formatFieldValue(result)).thenReturn("\"hello\"");

		final String out = tools.jdwp_assert_expression("name", "hello", 1L, null);

		assertThat(out).startsWith("OK");
	}

	@Test
	@DisplayName("falls back to the last breakpoint thread when threadId is null")
	void shouldFallBackToLastBreakpointThreadWhenThreadIdIsNull() throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		final StackFrame frame = mock(StackFrame.class);
		final Value result = mock(Value.class);
		when(breakpointTracker.getLastBreakpointThread()).thenReturn(thread);
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(frame, "x")).thenReturn(result);
		when(jdiService.formatFieldValue(result)).thenReturn("1");

		final String out = tools.jdwp_assert_expression("x", "1", null, null);

		assertThat(out).startsWith("OK");
	}

	@Test
	@DisplayName("returns guidance when no thread is available")
	void shouldReturnGuidanceWhenNoThreadIsAvailable() {
		when(breakpointTracker.getLastBreakpointThread()).thenReturn(null);

		final String out = tools.jdwp_assert_expression("x", "1", null, null);

		assertThat(out).contains("No suspended thread available");
	}

	@Test
	@DisplayName("rejects when the resolved thread is not suspended")
	void shouldReturnErrorWhenThreadIsNotSuspended() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(false);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));

		final String out = tools.jdwp_assert_expression("x", "1", 1L, null);

		assertThat(out).isEqualTo("Error: Thread is not suspended.");
	}

	@Test
	@DisplayName("returns 'Error: ...' when the evaluator throws")
	void shouldReturnErrorWhenEvaluatorThrows() throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(frame, "boom"))
			.thenThrow(new JdiEvaluationException("compile error"));

		final String out = tools.jdwp_assert_expression("boom", "x", 1L, null);

		assertThat(out).startsWith("Error:").contains("compile error");
	}

	/**
	 * If the target VM dies mid-evaluation, {@code jdwp_assert_expression} must surface the
	 * canonical {@code [VM_DEATH]} hint instead of a generic {@code "Error:"} prefix so the
	 * caller sees the actionable re-attach instruction.
	 */
	@Test
	@DisplayName("surfaces VMDisconnectedException as the canonical [VM_DEATH] hint")
	void shouldSurfaceVmDisconnectedAsCanonicalHint() throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(frame, "x")).thenThrow(new VMDisconnectedException("gone"));

		final String out = tools.jdwp_assert_expression("x", "5", 1L, null);

		assertThat(out).startsWith("[VM_DEATH]");
		assertThat(out).contains("jdwp_assert_expression");
		assertThat(out).contains("jdwp_connect");
	}
}
