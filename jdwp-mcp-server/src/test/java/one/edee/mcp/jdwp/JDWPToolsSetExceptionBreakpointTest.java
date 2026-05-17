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
	@Test
	@DisplayName("null caught and null uncaught both default to true")
	void shouldDefaultCaughtAndUncaughtToTrue() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final String result = tools.jdwp_set_exception_breakpoint(
			"java.lang.RuntimeException", null, null, null, null);

		assertThat(result).contains("Caught: true").contains("Uncaught: true").contains("Mode: suspend");
		verify(erm).createExceptionRequest(refType, true, true);
	}

	@Test
	@DisplayName("deferred path — registers a ClassPrepareRequest when the exception class is unloaded")
	void shouldDeferWhenExceptionClassIsUnloaded() throws Exception {
		final ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		when(jdiService.findOrForceLoadClass("com.example.MyException")).thenReturn(null);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);

		final String result = tools.jdwp_set_exception_breakpoint(
			"com.example.MyException", null, null, null, null);

		assertThat(result).startsWith("Exception breakpoint deferred");
		assertThat(result).contains("com.example.MyException");
		verify(cpr).addClassFilter("com.example.MyException");
		verify(cpr).enable();
	}

	@Test
	@DisplayName("rejects an unknown triggerBreakpointId without touching the VM")
	void shouldRejectUnknownTrigger() throws Exception {
		final String result = tools.jdwp_set_exception_breakpoint(
			"java.lang.RuntimeException", null, null, 999, null);

		assertThat(result).startsWith("Error:").contains("Trigger breakpoint #999");
	}

	@Test
	@DisplayName("accepts a known trigger and embeds the chain suffix in the response")
	void shouldAcceptKnownTriggerAndEmbedChainSuffix() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

		final String result = tools.jdwp_set_exception_breakpoint(
			"java.lang.RuntimeException", null, null, triggerId, true);

		assertThat(result).contains("Chain: trigger=#" + triggerId).contains("one-shot");
	}
}
