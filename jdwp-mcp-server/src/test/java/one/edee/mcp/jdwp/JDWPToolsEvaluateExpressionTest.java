package one.edee.mcp.jdwp;

import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.InvocationException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.RemoteCodeExecutor;
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

import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider;
import one.edee.mcp.jdwp.evaluation.interpret.EvaluationRouter;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
	@DisplayName("without threadId it evaluates on the current stop's thread — and says so when there is none (#50)")
	void shouldDefaultToTheCurrentStop() throws Exception {
		when(jdiService.getVM()).thenReturn(vm);

		assertThat(tools.jdwp_evaluate_expression(null, "1 + 2", null))
			.startsWith("Error: No current stop. Provide a threadId, or hit a breakpoint first");
	}

	@Test
	@DisplayName("a block or a long expression is echoed shortened, whitespace collapsed (#50)")
	void shouldShortenTheEcho() {
		assertThat(JDWPTools.echoed("  x +\n   y ")).isEqualTo("x + y");
		final String block = "{ final java.lang.reflect.Field f = _this.getClass().getDeclaredField(\"owners\");\n"
			+ "  f.setAccessible(true);\n  return ((int[]) f.get(_this)).length; }";
		assertThat(JDWPTools.echoed(block)).hasSize(80).startsWith("{ final java.lang.reflect.Field f").endsWith("…");
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

	/** Uses the default frame for loader selection and again for execution. */
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

		// Classpath discovery can resume the thread, so execution reads the same index again.
		verify(thread, times(2)).frame(0);
	}

	/** Uses the explicit frame for loader selection and again for execution. */
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

		verify(thread, times(2)).frame(2);
	}

	/**
	 * When the evaluator throws "X cannot be resolved" AND {@code X} matches a field on
	 * {@code this}'s declared type, the tool enriches the error with a hint that points at
	 * {@code this.X}, which a compiled expression resolves. The hint should mention the offending
	 * field, the {@code this} type, and {@code jdwp_get_fields(<id>)} for a look at the object.
	 */
	@Test
	@DisplayName("a bare field name that cannot be resolved is answered with this.<name>")
	void shouldEnrichEvaluationErrorWithFieldHint() throws Exception {
		final String result = evaluateFailing("secret", "secret cannot be resolved to a variable", false, false);

		assertThat(result).startsWith("Error evaluating expression:");
		assertThat(result).contains("secret cannot be resolved");
		assertThat(result).contains("Hint:");
		assertThat(result).contains("'secret' is a field on this");
		assertThat(result).contains("com.example.Hidden");
		assertThat(result).contains("Write this.secret");
		assertThat(result).contains("jdwp_get_fields(42)");
		assertThat(result).doesNotContain("cannot reference it directly");
	}

	/** A block resolves no bare field name, not even a public one of a public class. */
	@Test
	@DisplayName("a public field named bare in a block is answered with this.<name>")
	void shouldHintThisForPublicFieldInBlock() throws Exception {
		final String result = evaluateFailing("{ return secret; }", "secret cannot be resolved", true, true);

		assertThat(result).contains("Write this.secret").doesNotContain("please report");
	}

	/** The user qualified the name already, so {@code this.X} is no advice. */
	@Test
	@DisplayName("a field that is not visible is answered with jdwp_get_fields only")
	void shouldNotHintThisForInvisibleField() throws Exception {
		final String result = evaluateFailing("this.secret",
			"The field com.example.Hidden.secret is not visible", true, false);

		assertThat(result).contains("The field is non-public", "jdwp_get_fields(42)").doesNotContain("Write this.");
	}

	/** Target code can throw a message that reads like a compile error. */
	@Test
	@DisplayName("the message of a target exception gets no field hint")
	void shouldNotHintOnTargetException() throws Exception {
		final String result = evaluateFailing("secret", targetException("javax.script.ScriptException",
			"secret cannot be resolved"), false, false);

		assertThat(result).isEqualTo("Error evaluating expression: "
			+ "Target VM threw exception: javax.script.ScriptException: secret cannot be resolved");
	}

	/** The target answered, so its own failed connection does not mean that it is gone. */
	@Test
	@DisplayName("a transport error thrown by target code is not reported as [VM_DEATH]")
	void shouldNotMistakeTargetTransportErrorForVmDeath() throws Exception {
		final String result = evaluateFailing("client.ping()", targetException("java.net.ConnectException",
			"Connection refused"), false, false);

		assertThat(result).isEqualTo("Error evaluating expression: "
			+ "Target VM threw exception: java.net.ConnectException: Connection refused");
	}

	/** Evaluates {@code expression}, whose evaluation fails with the diagnostic, where {@code this} has a field {@code secret}. */
	private String evaluateFailing(String expression, String diagnostic, boolean publicClass, boolean publicField)
		throws Exception {
		return evaluateFailing(expression, new JdiEvaluationException(diagnostic), publicClass, publicField);
	}

	/** Evaluates {@code expression}, whose evaluation fails, where {@code this} has a field {@code secret}. */
	private String evaluateFailing(String expression, JdiEvaluationException failure, boolean publicClass,
								   boolean publicField) throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		final ObjectReference thisObj = mock(ObjectReference.class);
		final ClassType thisType = mock(ClassType.class);
		final Field field = mock(Field.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		// evaluate() throws; the enrichment then re-resolves thread/frame to probe `this`.
		when(evaluator.evaluate(eq(frame), eq(expression), anyMap())).thenThrow(failure);
		when(frame.thisObject()).thenReturn(thisObj);
		when(thisObj.referenceType()).thenReturn(thisType);
		when(thisType.name()).thenReturn("com.example.Hidden");
		when(thisType.isPublic()).thenReturn(publicClass);
		when(thisType.allFields()).thenReturn(List.of(field));
		when(field.name()).thenReturn("secret");
		when(field.isPublic()).thenReturn(publicField);
		when(thisObj.uniqueID()).thenReturn(42L);
		return tools.jdwp_evaluate_expression(1L, expression, null);
	}

	/** What the evaluator throws when the evaluated code fails with an exception of its own. */
	private static JdiEvaluationException targetException(String typeName, String message) {
		final ObjectReference exception = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final ReferenceType throwable = mock(ReferenceType.class);
		final Field detailMessage = mock(Field.class);
		final StringReference string = mock(StringReference.class);
		when(exception.type()).thenReturn(type);
		when(exception.referenceType()).thenReturn(type);
		when(type.name()).thenReturn(typeName);
		when(type.allFields()).thenReturn(List.of(detailMessage));
		when(detailMessage.name()).thenReturn("detailMessage");
		when(detailMessage.declaringType()).thenReturn(throwable);
		when(throwable.name()).thenReturn("java.lang.Throwable");
		when(exception.getValue(detailMessage)).thenReturn(string);
		when(string.value()).thenReturn(message);
		return RemoteCodeExecutor.targetException(new InvocationException(exception));
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

	/** Keeps the invocation runner reply for both compiler-mode expression tools while a job is running. */
	@Test
	void preservesStillRunningReplyWhileAnEarlierInvocationIsPending() throws Exception {
		final FakeSuspendModel target = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread thread = target.thread("debuggee");
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final EvaluationGuard guard = new EvaluationGuard();
		tracker.openSuspensionSession(target.vm, history).suspendThread(thread.ref);
		final JDIConnectionService connection = mock(JDIConnectionService.class);
		when(connection.getVM()).thenReturn(target.vm);
		final EvaluationRouter router = new EvaluationRouter(mock(JdiExpressionEvaluator.class), EvaluationRouter.Mode.COMPILER);
		final JDWPTools tools = new JDWPTools(connection, tracker, new WatcherManager(), router, history, guard,
			mock(JvmDiscoveryService.class), new MarkedInstanceRegistry(), new JdiHealthMonitor(),
			mock(LocalProjectClasspathProvider.class), mock(FastLogpointService.class));
		final InvocationRunner invocations = (InvocationRunner) ReflectionTestUtils.getField(tools, "invocations");
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final FutureTask<String> first = new FutureTask<>(() -> invocations.run(thread.ref, "earlier evaluation", () -> {
			started.countDown();
			finish.await();
			return "done";
		}));
		new Thread(first).start();
		try {
			assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
			assertThat(tools.jdwp_evaluate_expression(thread.ref.uniqueID(), "1", null))
				.startsWith("[STILL_RUNNING]");
			assertThat(tools.jdwp_assert_expression("1", "1", thread.ref.uniqueID(), null))
				.startsWith("[STILL_RUNNING]");
		} finally {
			finish.countDown();
			first.get(3, TimeUnit.SECONDS);
		}
	}
}
