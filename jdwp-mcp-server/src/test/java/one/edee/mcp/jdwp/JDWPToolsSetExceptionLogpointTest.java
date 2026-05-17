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
 * Behavioural tests for {@link JDWPTools#jdwp_set_exception_logpoint}: required expression,
 * blank-expression rejection, optional condition persistence under the synthetic ID, defaulting
 * of caught / uncaught, deferred path on unloaded class, and chain integration. The suspending
 * sibling {@code jdwp_set_exception_breakpoint} is covered in {@link JDWPToolsSetExceptionBreakpointTest}.
 */
@DisplayName("jdwp_set_exception_logpoint")
class JDWPToolsSetExceptionLogpointTest {

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

	@Test
	@DisplayName("rejects a null expression with a hard error and never touches the VM")
	void shouldRejectNullExpression() throws Exception {
		final String result = tools.jdwp_set_exception_logpoint(
			"java.lang.RuntimeException", null, null, null, null, null, null);

		assertThat(result).startsWith("Error:").contains("expression is required");
		verify(jdiService, never()).getVM();
	}

	@Test
	@DisplayName("rejects a blank expression with a hard error and never touches the VM")
	void shouldRejectBlankExpression() throws Exception {
		final String result = tools.jdwp_set_exception_logpoint(
			"java.lang.RuntimeException", "   ", null, null, null, null, null);

		assertThat(result).startsWith("Error:").contains("expression is required");
		verify(jdiService, never()).getVM();
	}

	@Test
	@DisplayName("active path — renders log-only mode and the expression in the response")
	void shouldRenderLogOnlyModeAndExpression() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final String result = tools.jdwp_set_exception_logpoint(
			"java.lang.RuntimeException", "$exception.getMessage()", null, null, null, null, null);

		assertThat(result).contains("Mode: log-only");
		assertThat(result).contains("Expression: $exception.getMessage()");
	}

	@Test
	@DisplayName("active path — persists the condition under the synthetic ID")
	void shouldPersistCondition() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final String result = tools.jdwp_set_exception_logpoint(
			"java.lang.RuntimeException", "$exception.getMessage()",
			"$exception.getMessage() != null", null, null, null, null);

		assertThat(result).contains("Condition: $exception.getMessage() != null");
		// And the metadata is queryable by the listener path via the synthetic ID.
		final Integer id = tracker.getAllExceptionBreakpoints().keySet().stream().findFirst().orElseThrow();
		assertThat(tracker.getCondition(id)).isEqualTo("$exception.getMessage() != null");
	}

	@Test
	@DisplayName("blank condition is treated as no condition — no metadata persisted")
	void shouldTreatBlankConditionAsNoCondition() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final String result = tools.jdwp_set_exception_logpoint(
			"java.lang.RuntimeException", "$exception", "   ", null, null, null, null);

		assertThat(result).doesNotContain("Condition:");
		final Integer id = tracker.getAllExceptionBreakpoints().keySet().stream().findFirst().orElseThrow();
		assertThat(tracker.getCondition(id)).isNull();
	}

	@Test
	@DisplayName("null caught and null uncaught both default to true")
	void shouldDefaultCaughtAndUncaughtToTrue() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final String result = tools.jdwp_set_exception_logpoint(
			"java.lang.RuntimeException", "$exception", null, null, null, null, null);

		assertThat(result).contains("Caught: true").contains("Uncaught: true");
		verify(erm).createExceptionRequest(refType, true, true);
	}

	@Test
	@DisplayName("deferred path — registers a ClassPrepareRequest when the exception class is unloaded")
	void shouldDeferWhenExceptionClassIsUnloaded() throws Exception {
		final ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		when(jdiService.findOrForceLoadClass("com.example.MyException")).thenReturn(null);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);

		final String result = tools.jdwp_set_exception_logpoint(
			"com.example.MyException", "$exception", "$exception != null", null, null, null, null);

		assertThat(result).startsWith("Exception breakpoint deferred");
		assertThat(result).contains("Mode: log-only");
		assertThat(result).contains("Expression: $exception");
		assertThat(result).contains("Condition: $exception != null");
		// Condition is persisted against the pending ID so it survives promotion.
		final Integer pendingId = tracker.getAllPendingExceptionBreakpoints().keySet().stream().findFirst().orElseThrow();
		assertThat(tracker.getCondition(pendingId)).isEqualTo("$exception != null");
		verify(cpr).addClassFilter("com.example.MyException");
		verify(cpr).enable();
	}

	@Test
	@DisplayName("rejects an unknown triggerBreakpointId without touching the VM")
	void shouldRejectUnknownTrigger() throws Exception {
		final String result = tools.jdwp_set_exception_logpoint(
			"java.lang.RuntimeException", "$exception", null, null, null, 999, null);

		assertThat(result).startsWith("Error:").contains("Trigger breakpoint #999");
		verify(jdiService, never()).getVM();
	}

	@Test
	@DisplayName("accepts a known trigger and embeds the chain suffix")
	void shouldAcceptKnownTriggerAndEmbedChainSuffix() throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final ExceptionRequest req = mock(ExceptionRequest.class);
		when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
		when(erm.createExceptionRequest(refType, true, true)).thenReturn(req);

		final int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

		final String result = tools.jdwp_set_exception_logpoint(
			"java.lang.RuntimeException", "$exception", null, null, null, triggerId, true);

		assertThat(result).contains("Chain: trigger=#" + triggerId).contains("one-shot");
	}
}
