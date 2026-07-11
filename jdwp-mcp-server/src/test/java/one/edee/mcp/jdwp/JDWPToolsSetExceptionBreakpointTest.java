package one.edee.mcp.jdwp;

import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link JDWPTools#jdwp_set_exception_breakpoint}: the null-default
 * behaviour for caught / uncaught (both default to true), the deferred path when the exception
 * class is not yet loaded, and the unknown-trigger rejection. The log-only / expression-bearing
 * variants live in {@link JDWPToolsSetExceptionLogpointTest} since they target the separate
 * {@code jdwp_set_exception_logpoint} tool.
 */
@DisplayName("jdwp_set_exception_breakpoint")
class JDWPToolsSetExceptionBreakpointTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker tracker;
	private JDWPTools tools;
	private VirtualMachine vm;
	private EventRequestManager erm;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		final WatcherManager watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
	}

	/**
	 * Null caught / uncaught both default to true — verified by observing that the JDI
	 * {@code createExceptionRequest} call passes {@code (true, true)} to the
	 * {@link EventRequestManager}.
	 */
	/**
	 * Canary for the root cause of the incident: the MCP tool schema derives each parameter name
	 * from the reflected {@link java.lang.reflect.Parameter#getName()} (the module is compiled with
	 * {@code -parameters}). The exception tools MUST expose their class argument as {@code className}
	 * — consistent with the sibling {@code jdwp_set_breakpoint} / {@code jdwp_set_field_breakpoint}
	 * tools — so a client that reuses {@code className} across all breakpoint tools binds correctly
	 * instead of dropping the value and passing null. This also fails loudly (returning {@code arg0})
	 * if {@code <parameters>} is ever dropped from the compiler config, which would silently
	 * resurrect the null-class NPE.
	 */
	@Test
	@DisplayName("exposes its class parameter as 'className' (schema binding contract)")
	void shouldExposeClassParameterAsClassName() throws Exception {
		final java.lang.reflect.Parameter first = JDWPTools.class
			.getMethod("jdwp_set_exception_breakpoint",
				String.class, Boolean.class, Boolean.class, Integer.class, Boolean.class, Boolean.class)
			.getParameters()[0];

		assertThat(first.getName()).isEqualTo("className");
	}

	/**
	 * Regression for the reported {@code NullPointerException: "key" is null}: when the class name
	 * arrives null (e.g. the MCP client sent the argument under a different key), the tool must
	 * fail loud with a clear message BEFORE it reaches {@code hasClassPrepareRequest(null)} —
	 * which would otherwise throw deep inside {@link java.util.concurrent.ConcurrentHashMap}. It
	 * must also leave the registry untouched (no null-named pending entry).
	 */
	@Test
	@DisplayName("rejects a null class name with a hard error, never touches the VM, and registers nothing")
	void shouldRejectNullClassName() throws Exception {
		final String result = tools.jdwp_set_exception_breakpoint(
			null, true, true, null, null, null);

		assertThat(result).startsWith("Error:").contains("required");
		verify(jdiService, never()).getVM();
		assertThat(tracker.getAllPendingExceptionBreakpoints()).isEmpty();
		assertThat(tracker.getAllExceptionBreakpoints()).isEmpty();
	}

	@Test
	@DisplayName("rejects a blank class name with a hard error, never touches the VM, and registers nothing")
	void shouldRejectBlankClassName() throws Exception {
		final String result = tools.jdwp_set_exception_breakpoint(
			"   ", true, true, null, null, null);

		assertThat(result).startsWith("Error:").contains("required");
		verify(jdiService, never()).getVM();
		assertThat(tracker.getAllPendingExceptionBreakpoints()).isEmpty();
		assertThat(tracker.getAllExceptionBreakpoints()).isEmpty();
	}

	@Test
	@DisplayName("null caught and null uncaught both default to true")
	void shouldDefaultCaughtAndUncaughtToTrue() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findLoadedClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final String result = tools.jdwp_set_exception_breakpoint(
			"java.lang.RuntimeException", null, null, null, null, null);

		assertThat(result).contains("Caught: true").contains("Uncaught: true").contains("Mode: suspend");
		verify(erm).createExceptionRequest(refType, true, true);
	}

	@Test
	@DisplayName("deferred path — registers a ClassPrepareRequest when the exception class is unloaded")
	void shouldDeferWhenExceptionClassIsUnloaded() throws Exception {
		final ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		when(jdiService.findLoadedClass("com.example.MyException")).thenReturn(null);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);

		final String result = tools.jdwp_set_exception_breakpoint(
			"com.example.MyException", null, null, null, null, null);

		assertThat(result).startsWith("Exception breakpoint deferred");
		assertThat(result).contains("com.example.MyException");
		verify(cpr).addClassFilter("com.example.MyException");
		verify(cpr).enable();
	}

	@Test
	@DisplayName("rejects an unknown triggerBreakpointId without touching the VM")
	void shouldRejectUnknownTrigger() throws Exception {
		final String result = tools.jdwp_set_exception_breakpoint(
			"java.lang.RuntimeException", null, null, 999, null, null);

		assertThat(result).startsWith("Error:").contains("Trigger breakpoint #999");
	}

	@Test
	@DisplayName("accepts a known trigger and embeds the chain suffix in the response")
	void shouldAcceptKnownTriggerAndEmbedChainSuffix() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findLoadedClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

		final String result = tools.jdwp_set_exception_breakpoint(
			"java.lang.RuntimeException", null, null, triggerId, true, null);

		assertThat(result).contains("Chain: trigger=#" + triggerId).contains("one-shot");
	}
}
