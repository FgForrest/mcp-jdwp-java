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
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
		when(evaluator.evaluate(frame, "1 + 2")).thenReturn(value);
		when(jdiService.formatFieldValue(value)).thenReturn("3");

		final String result = tools.jdwp_evaluate_expression(1L, "1 + 2", null);

		assertThat(result).isEqualTo("Result: 3");
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
		when(evaluator.evaluate(frame, "x")).thenReturn(null);
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
		when(evaluator.evaluate(frame, "y")).thenReturn(null);
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
		when(evaluator.evaluate(frame, "secret"))
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
		when(evaluator.evaluate(frame, "x")).thenThrow(new VMDisconnectedException("gone"));

		final String result = tools.jdwp_evaluate_expression(1L, "x", null);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).contains("jdwp_evaluate_expression");
		assertThat(result).contains("jdwp_connect");
		assertThat(result).contains("jdwp_wait_for_attach");
	}
}
