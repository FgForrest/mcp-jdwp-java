package one.edee.mcp.jdwp;

import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;

import java.net.ConnectException;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link JDWPTools#jdwp_evaluate_expression}: happy path, thread/frame
 * resolution, suspended-state guard, the {@code frameIndex} null default, and the
 * {@code enrichEvaluationError} hint path that fires when the evaluator's "X cannot be
 * resolved" diagnostic matches a field on {@code this}.
 */
@DisplayName("jdwp_evaluate_expression")
class JDWPToolsEvaluateExpressionTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private JdiExpressionEvaluator evaluator;
	private JDWPTools tools;
	private VirtualMachine vm;
	private MarkedInstanceRegistry markedInstances;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		markedInstances = new MarkedInstanceRegistry();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService(), markedInstances);
		vm = mock(VirtualMachine.class);
	}

	@Test
	@DisplayName("happy path — returns formatted result")
	void shouldReturnFormattedResultOnHappyPath() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		final IntegerValue value = mock(IntegerValue.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(eq(frame), eq("1 + 2"), anyMap())).thenReturn(value);
		when(jdiService.formatFieldValue(value)).thenReturn("3");

		final String result = tools.jdwp_evaluate_expression(1L, "1 + 2", null);

		// P3-5 echoes the expression in the result so batched evals can be attributed.
		assertThat(result).isEqualTo("Result of 1 + 2: 3");
		verify(evaluator).configureCompilerClasspath(thread);
	}

	@Test
	@DisplayName("returns 'Thread not found' when threadId is unknown")
	void shouldReturnThreadNotFoundWhenThreadIdIsUnknown() throws Exception {
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of());

		final String result = tools.jdwp_evaluate_expression(999L, "1 + 2", null);

		assertThat(result).isEqualTo("Error: Thread not found with ID 999");
	}

	@Test
	@DisplayName("returns 'Thread is not suspended' when target thread is running")
	void shouldReturnThreadNotSuspendedWhenThreadIsRunning() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(false);

		final String result = tools.jdwp_evaluate_expression(1L, "x", null);

		assertThat(result).isEqualTo("Error: Thread is not suspended.");
	}

	@Test
	@DisplayName("defaults frameIndex to 0 when null")
	void shouldDefaultFrameIndexToZeroWhenNull() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(eq(frame), eq("x"), anyMap())).thenReturn(null);
		when(jdiService.formatFieldValue(null)).thenReturn("null");

		tools.jdwp_evaluate_expression(1L, "x", null);

		// Frame 0 is the documented default; verify it via the JDI call rather than parsing the
		// rendered output.
		verify(thread).frame(0);
	}

	@Test
	@DisplayName("explicit frameIndex is respected")
	void shouldUseExplicitFrameIndexWhenProvided() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(2)).thenReturn(frame);
		when(evaluator.evaluate(eq(frame), eq("y"), anyMap())).thenReturn(null);
		when(jdiService.formatFieldValue(null)).thenReturn("null");

		tools.jdwp_evaluate_expression(1L, "y", 2);

		verify(thread).frame(2);
	}

	/**
	 * When the evaluator throws "X cannot be resolved" AND {@code X} matches a field on
	 * {@code this}'s declared type, the tool enriches the error with a hint that points at
	 * {@code jdwp_get_fields}. The hint should mention the offending field, the {@code this}
	 * type, and the {@code jdwp_get_fields(<id>)} workaround call.
	 */
	@Test
	@DisplayName("enriches 'cannot be resolved' errors with a field-on-this hint")
	void shouldEnrichEvaluationErrorWithFieldHint() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		final ObjectReference thisObj = mock(ObjectReference.class);
		// Non-public class so the hint actually fires the "workaround" branch.
		final ClassType thisType = mock(ClassType.class);
		final Field field = mock(Field.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		// First evaluate() throws; the enrichment then re-resolves thread/frame to probe `this`.
		when(evaluator.evaluate(eq(frame), eq("secret"), anyMap()))
			.thenThrow(new JdiEvaluationException("secret cannot be resolved to a variable"));
		when(frame.thisObject()).thenReturn(thisObj);
		when(thisObj.referenceType()).thenReturn(thisType);
		when(thisType.name()).thenReturn("com.example.Hidden");
		when(thisType.isPublic()).thenReturn(false);
		when(thisType.allFields()).thenReturn(List.of(field));
		when(field.name()).thenReturn("secret");
		when(field.isPublic()).thenReturn(false);
		when(thisObj.uniqueID()).thenReturn(42L);

		final String result = tools.jdwp_evaluate_expression(1L, "secret", null);

		assertThat(result).startsWith("Error evaluating expression:");
		assertThat(result).contains("secret cannot be resolved");
		assertThat(result).contains("Hint:");
		assertThat(result).contains("'secret' is a field on this");
		assertThat(result).contains("com.example.Hidden");
		assertThat(result).contains("jdwp_get_fields(42)");
	}

	/**
	 * The evaluator may throw {@link VMDisconnectedException} when the target VM dies mid-call.
	 * The tool must surface the canonical {@code [VM_DEATH]} hint rather than the generic
	 * "Error evaluating expression:" prefix, so the caller can immediately re-attach.
	 */
	@Test
	@DisplayName("surfaces VMDisconnectedException as the canonical [VM_DEATH] hint")
	void shouldSurfaceVmDisconnectedAsCanonicalHint() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(eq(frame), eq("x"), anyMap())).thenThrow(new VMDisconnectedException("gone"));

		final String result = tools.jdwp_evaluate_expression(1L, "x", null);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).contains("jdwp_evaluate_expression");
		assertThat(result).contains("jdwp_connect");
		assertThat(result).contains("jdwp_wait_for_attach");
	}

	/**
	 * F-RA1: a raw transport failure (SIGKILL of the target JVM) surfaces as a
	 * {@link ConnectException} / {@link java.net.SocketException} wrapped in a
	 * {@link JdiEvaluationException} — JDI never wraps it in
	 * {@link VMDisconnectedException} when the socket dies between calls. The tool must
	 * still land on the canonical {@code [VM_DEATH]} envelope so the agent's recovery
	 * recipe ({@code jdwp_connect} / {@code jdwp_wait_for_attach}) is suggested instead
	 * of an opaque {@code "Error evaluating expression: Connection refused"} message.
	 */
	@Test
	@DisplayName("surfaces SocketException-in-cause-chain as the canonical [VM_DEATH] hint (F-RA1)")
	void shouldSurfaceSocketExceptionAsCanonicalHint() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(eq(frame), eq("x"), anyMap()))
			.thenThrow(new JdiEvaluationException("eval failed", new ConnectException("Connection refused")));

		final String result = tools.jdwp_evaluate_expression(1L, "x", null);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).contains("jdwp_evaluate_expression");
		assertThat(result).contains("jdwp_connect");
		assertThat(result).contains("jdwp_wait_for_attach");
	}

	/**
	 * Marks registered before evaluation must be exposed as synthetic {@code $label} bindings to the
	 * expression. Regression test for P0-6 — prior to the fix, {@code jdwp_evaluate_expression}
	 * called the 2-arg {@code evaluator.evaluate(frame, expr)} form and the user's expression
	 * referencing {@code $marked} would fail with "cannot be resolved" even though
	 * {@code jdwp_get_locals} advertised the binding as visible.
	 */
	@Test
	@DisplayName("forwards marked-instance bindings to the evaluator")
	void shouldForwardMarkedInstanceBindingsToEvaluator() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		final IntegerValue value = mock(IntegerValue.class);
		final ObjectReference markedObj = mock(ObjectReference.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(markedObj.uniqueID()).thenReturn(42L);
		// MarkedInstanceRegistry.mark requires a real-ish ObjectReference; the registry just stores
		// it without dereferencing.
		markedInstances.mark("session", markedObj, false);

		when(evaluator.evaluate(eq(frame), eq("$session.hashCode()"), anyMap())).thenReturn(value);
		when(jdiService.formatFieldValue(value)).thenReturn("0xdeadbeef");

		tools.jdwp_evaluate_expression(1L, "$session.hashCode()", null);

		// Capture the bindings map actually passed to the evaluator and assert the mark is in it.
		@SuppressWarnings("unchecked")
		final ArgumentCaptor<Map<String, com.sun.jdi.Value>> bindingsCaptor =
			ArgumentCaptor.forClass(Map.class);
		verify(evaluator).evaluate(eq(frame), eq("$session.hashCode()"), bindingsCaptor.capture());
		assertThat(bindingsCaptor.getValue()).containsKey("$session");
	}

	/**
	 * Counterpart for {@link JDWPTools#jdwp_assert_expression}. Same wiring as above — marks must
	 * be visible. Argument captor is intentionally separate (rather than a parameterised test) so
	 * a single test failure points at exactly which tool regressed.
	 */
	@Test
	@DisplayName("jdwp_assert_expression forwards marked-instance bindings to the evaluator")
	void assertExpressionShouldForwardMarkedInstanceBindings() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		final ObjectReference markedObj = mock(ObjectReference.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(markedObj.uniqueID()).thenReturn(42L);
		markedInstances.mark("session", markedObj, false);

		// Stub evaluate so the assert tool can compare; payload value doesn't matter — we only
		// care that the evaluator received the marks map.
		when(evaluator.evaluate(eq(frame), eq("$session.getRole()"), anyMap())).thenReturn(null);
		when(jdiService.formatFieldValue(null)).thenReturn("null");

		tools.jdwp_assert_expression("$session.getRole()", "null", 1L, null);

		verify(evaluator).evaluate(eq(frame), eq("$session.getRole()"), argThat(m -> m.containsKey("$session")));
	}
}
