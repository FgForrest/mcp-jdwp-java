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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.security.Permission;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
				String.class, Boolean.class, Boolean.class, Integer.class, Boolean.class, Boolean.class,
				String.class, String.class, String.class, String.class)
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
			null, true, true, null, null, null, null, null, null, null);

		assertThat(result).startsWith("Error:").contains("required");
		verify(jdiService, never()).getVM();
		assertThat(tracker.getAllPendingExceptionBreakpoints()).isEmpty();
		assertThat(tracker.getAllExceptionBreakpoints()).isEmpty();
	}

	@Test
	@DisplayName("rejects a blank class name with a hard error, never touches the VM, and registers nothing")
	void shouldRejectBlankClassName() throws Exception {
		final String result = tools.jdwp_set_exception_breakpoint(
			"   ", true, true, null, null, null, null, null, null, null);

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
			"java.lang.RuntimeException", null, null, null, null, null, null, null, null, null);

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
			"com.example.MyException", null, null, null, null, null, null, null, null, null);

		assertThat(result).startsWith("Exception breakpoint deferred");
		assertThat(result).contains("com.example.MyException");
		verify(cpr).addClassFilter("com.example.MyException");
		verify(cpr).enable();
	}

	/** Exercises the hint through exception registration while the requested class is still unloaded. */
	@ParameterizedTest
	@CsvSource({
		"java.lang.NumberFormatException, java.lang.Number, false",
		"com.sun.jdi.AbsentInformationException, com.acme.AbsentInformationException, false",
		"java.lang.NumberFormatExeption, java.lang.Number, true",
		"java.lang/NumberFormatException, java.lang.NumberFormatException, true",
		"com.example.NumberFormatException, com.example.Number, true",
		"one.edee.mcp.jdwp.EventHistory, com.acme.EventHistory, true"
	})
	@DisplayName("only valid JDK binary names suppress suggestions on a matching JDK")
	void shouldSuppressSuggestionsForKnownJdkExceptions(String className, String loadedName, boolean suggest) {
		when(vm.version()).thenReturn(Runtime.version().toString());
		final ClassPrepareRequest cpr = mockDeferredException(loadedName);
		final String reply = deferException(className);

		assertThat(reply).startsWith("Exception breakpoint deferred");
		if (suggest) {
			assertThat(reply).contains("Did you mean: " + loadedName);
		} else {
			assertThat(reply).doesNotContain("Did you mean:");
		}
		verify(cpr).addClassFilter(className);
	}

	/** Warns about the exact filter while suggesting both the JDK spelling and a loaded application match. */
	@Test
	void shouldSuggestQualifiedNamesForABareExceptionName() {
		final String applicationException = "com.acme.NumberFormatException";
		final ClassPrepareRequest cpr = mockDeferredException(applicationException);
		when(vm.version()).thenReturn(Runtime.version().toString());

		final String reply = deferException("NumberFormatException");

		assertThat(reply).contains("Exception breakpoints need a fully qualified class name",
			"Did you mean: java.lang.NumberFormatException, " + applicationException);
		assertThat(tracker.getPendingExceptionBreakpointsForClass(applicationException)).isEmpty();
		verify(cpr).addClassFilter("NumberFormatException");
	}

	/** Retains a loaded alternative when the target uses an older Java release. */
	@Test
	void shouldKeepSuggestionsForADifferentTargetJdk() {
		assumeTrue(Runtime.version().feature() >= 21);
		when(vm.version()).thenReturn("17.0.18");
		mockDeferredException("com.acme.MatchException");

		assertThat(deferException("java.lang.MatchException")).contains("Did you mean: com.acme.MatchException");
	}

	/** A bare name on Java 17 keeps its loaded match without suggesting the server's Java 21 class. */
	@Test
	void shouldKeepLoadedApplicationMatchForABareExceptionOnADifferentJdk() {
		assumeTrue(Runtime.version().feature() >= 21);
		when(vm.version()).thenReturn("17.0.18");
		final ClassPrepareRequest cpr = mockDeferredException("com.acme.MatchException");

		assertThat(deferException("MatchException"))
			.contains("Did you mean: com.acme.MatchException", "Exception breakpoints need a fully qualified class name")
			.doesNotContain("java.lang.MatchException");
		verify(cpr).addClassFilter("MatchException");
	}

	/** An exact `java.lang` match loaded by the target is valid across JDK versions. */
	@Test
	void shouldSuggestAnExactLoadedJavaLangMatchOnADifferentJdk() {
		when(vm.version()).thenReturn(Runtime.version().feature() == 17 ? "21.0.1" : "17.0.18");
		final ClassPrepareRequest cpr = mockDeferredException("java.lang.NumberFormatException");

		assertThat(deferException("NumberFormatException")).contains(
			"Did you mean: java.lang.NumberFormatException", "Exception breakpoints need a fully qualified class name");
		verify(cpr).addClassFilter("NumberFormatException");
	}

	/** A denied JDK resource check must leave the loaded-class matcher available. */
	@Test
	@SuppressWarnings("removal")
	void shouldKeepSuggestionsWhenResourceAccessIsDenied() {
		assumeTrue(Runtime.version().feature() < 24 && "allow".equals(System.getProperty("java.security.manager")));
		mockDeferredException("com.acme.NumberFormatException");
		when(vm.version()).thenReturn(Runtime.version().toString());
		final SecurityManager previous = System.getSecurityManager();
		final AtomicBoolean denied = new AtomicBoolean();
		final SecurityManager denyJdkLookup = new SecurityManager() {
			/** Denies access to system modules and the platform loader used by the earlier lookup. */
			@Override
			public void checkPermission(Permission permission) {
				if ("accessSystemModules".equals(permission.getName()) || "getClassLoader".equals(permission.getName())
					&& StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
						frame.getMethodName().equals("getPlatformClassLoader")))) {
					denied.set(true);
					throw new SecurityException("JDK resource access denied");
				}
			}
		};
		final String reply;
		System.setSecurityManager(denyJdkLookup);
		try {
			reply = deferException("java.lang.NumberFormatException");
		} finally {
			System.setSecurityManager(previous);
		}

		assertThat(denied.get()).isTrue();
		assertThat(reply).contains("Did you mean: com.acme.NumberFormatException");
	}

	/** Supplies a loaded alternative and a request for the exact name supplied by the caller. */
	private ClassPrepareRequest mockDeferredException(String loadedName) {
		final ReferenceType loaded = mock(ReferenceType.class);
		when(loaded.name()).thenReturn(loadedName);
		when(vm.allClasses()).thenReturn(List.of(loaded));
		final ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);
		return cpr;
	}

	/** Sets a regular exception breakpoint with default options. */
	private String deferException(String className) {
		return tools.jdwp_set_exception_breakpoint(className, null, null, null, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("rejects an unknown triggerBreakpointId without touching the VM")
	void shouldRejectUnknownTrigger() throws Exception {
		final String result = tools.jdwp_set_exception_breakpoint(
			"java.lang.RuntimeException", null, null, 999, null, null, null, null, null, null);

		assertThat(result).startsWith("Error:").contains("Trigger breakpoint #999");
	}

	@Test
	@DisplayName("Exception breakpoint wires its opt-in throw-site filters and condition before enabling")
	void shouldWireThrowSiteFiltersAndCondition() throws Exception {
		final ReferenceType type = mock(ReferenceType.class);
		final ExceptionRequest request = mock(ExceptionRequest.class);
		when(jdiService.findLoadedClass("java.lang.Exception")).thenReturn(type);
		when(erm.createExceptionRequest(type, true, true)).thenReturn(request);
		final String condition = "$exception.getMessage() != null";

		final String reply = setFilteredBreakpoint(condition, "com.example.*", "java.*, *Test");

		assertThat(reply).contains(condition, "thrownIn=com.example.*", "notThrownIn=java.*,*Test");
		assertThat(tracker.getCondition(1)).isEqualTo(condition);
		final var order = org.mockito.Mockito.inOrder(request);
		order.verify(request).addClassFilter("com.example.*");
		order.verify(request).addClassExclusionFilter("java.*");
		order.verify(request).addClassExclusionFilter("*Test");
		order.verify(request).setEnabled(true);
		assertThat(tools.jdwp_overview(null, null, null))
			.contains(condition, "thrownIn=com.example.*", "notThrownIn=java.*,*Test");
	}

	@Test
	@DisplayName("Malformed wildcard patterns are rejected before touching the VM")
	void shouldRejectInvalidThrowSiteFilter() throws Exception {
		assertThat(setFilteredBreakpoint(null, "com.*.Broken", null))
			.startsWith("Error:").contains("thrownIn", "com.*.Broken");
		verify(jdiService, never()).getVM();
	}

	@Test
	@DisplayName("thrownIn accepts one pattern and directs multiple exclusions to notThrownIn")
	void shouldRejectMultipleInclusionPatterns() throws Exception {
		when(erm.createClassPrepareRequest()).thenReturn(mock(ClassPrepareRequest.class));

		assertThat(setFilteredBreakpoint(null, "com.a.*,com.b.*", null))
			.startsWith("Error:").contains("thrownIn", "one pattern", "notThrownIn");
		verify(jdiService, never()).getVM();
	}

	private String setFilteredBreakpoint(String condition, String include, String exclude) {
		return tools.jdwp_set_exception_breakpoint("java.lang.Exception", null, null, null, null, null, null,
			condition, include, exclude);
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
			"java.lang.RuntimeException", null, null, triggerId, true, null, null, null, null, null);

		assertThat(result).contains("Chain: trigger=#" + triggerId).contains("one-shot");
	}
}
