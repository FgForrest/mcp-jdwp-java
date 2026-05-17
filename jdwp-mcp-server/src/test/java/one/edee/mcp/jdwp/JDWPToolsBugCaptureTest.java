package one.edee.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins observable bugs in {@link JDWPTools} entry points. Each test asserts the CURRENT broken
 * behaviour so the eventual fix can flip the assertion to the corrected expectation.
 */
@DisplayName("JDWPTools known limitations")
class JDWPToolsBugCaptureTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private WatcherManager watcherManager;
	private JdiExpressionEvaluator evaluator;
	private EventHistory eventHistory;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		watcherManager = mock(WatcherManager.class);
		evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = mock(EventHistory.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(),
			new one.edee.mcp.jdwp.discovery.JvmDiscoveryService());
	}

	/**
	 * Passing a length-1 input of just {@code "} must NOT crash with a
	 * {@link StringIndexOutOfBoundsException}. The strip-quotes branch must guard on
	 * {@code value.length() >= 2} so an unbalanced single quote falls through unchanged.
	 * The resulting value is mirrored verbatim and the call succeeds.
	 */
	@Test
	void shouldHandleSingleQuoteStringWithoutCrashing() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		ThreadReference thread = mock(ThreadReference.class);
		StackFrame frame = mock(StackFrame.class);
		LocalVariable localVar = mock(LocalVariable.class);
		Type type = mock(Type.class);
		com.sun.jdi.StringReference mirrored = mock(com.sun.jdi.StringReference.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frame(0)).thenReturn(frame);
		when(frame.visibleVariableByName("s")).thenReturn(localVar);
		when(localVar.typeName()).thenReturn("java.lang.String");
		when(localVar.type()).thenReturn(type);
		when(type.name()).thenReturn("java.lang.String");
		when(vm.mirrorOf("\"")).thenReturn(mirrored);

		String result = tools.jdwp_set_local(1L, 0, "s", "\"");

		assertThat(result).doesNotStartWith("Error");
		assertThat(result).contains("Variable 's' set to");
	}

	/**
	 * When {@code jdiService.getVM()} throws (e.g. external VM disconnect), {@code jdwp_reset}
	 * must still clear all server-local state (watchers, object cache, event history, breakpoint
	 * tracker) so a subsequent reconnect starts from a clean slate.
	 */
	@Test
	void shouldClearServerLocalStateWhenJdiVmFails() throws Exception {
		when(jdiService.getVM()).thenThrow(new Exception("Not connected"));

		String result = tools.jdwp_reset();

		assertThat(result).doesNotStartWith("Error:");
		// Server-local state must be cleared even when the VM connection is dead.
		verify(watcherManager).clearAll();
		verify(eventHistory).clear();
		verify(jdiService).clearObjectCache();
		verify(breakpointTracker).reset();
	}

	/**
	 * In {@code jdwp_set_breakpoint}, the recheck-after-CPR-registration path can throw
	 * {@link AbsentInformationException} from {@code locationsOfLine}. The pending breakpoint
	 * registered earlier in the method must be removed in the catch block — otherwise it leaks
	 * into {@code BreakpointTracker} along with its ClassPrepareRequest.
	 */
	@Test
	void shouldRemoveOrphanPendingBreakpointWhenLocationsOfLineThrows() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);
		// First lookup returns nothing → enter the pending branch
		when(jdiService.findOrForceLoadClass("com.example.MyClass")).thenReturn(null);
		// The recheck inside the pending branch finds the class — race window
		when(vm.classesByName("com.example.MyClass")).thenReturn(List.of(refType));
		// locationsOfLine throws AbsentInformationException
		when(refType.locationsOfLine(42)).thenThrow(new AbsentInformationException());
		// The pending registration returns ID 99
		when(breakpointTracker.registerPendingBreakpoint(anyString(), anyInt(), anyInt(), anyString()))
			.thenReturn(99);

		String result = tools.jdwp_set_breakpoint("com.example.MyClass", 42, "all", null, null, null);

		// User-facing error message preserved
		assertThat(result).startsWith("Error:");
		assertThat(result).contains("debug info");
		// The orphan pending entry must be cleaned up
		verify(breakpointTracker).removePendingBreakpoint(99);
	}

	/**
	 * Same orphan-leak surface in {@code jdwp_set_logpoint}. The recheck path can throw
	 * {@link AbsentInformationException}; the pending entry, its logpoint expression metadata,
	 * and the ClassPrepareRequest must all be cleaned up.
	 */
	@Test
	void shouldRemoveOrphanPendingLogpointWhenLocationsOfLineThrows() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);
		when(jdiService.findOrForceLoadClass("com.example.MyClass")).thenReturn(null);
		when(vm.classesByName("com.example.MyClass")).thenReturn(List.of(refType));
		when(refType.locationsOfLine(42)).thenThrow(new AbsentInformationException());
		when(breakpointTracker.registerPendingBreakpoint(anyString(), anyInt(), anyInt(), anyString()))
			.thenReturn(77);

		String result = tools.jdwp_set_logpoint("com.example.MyClass", 42, "\"x=\" + x", null);

		assertThat(result).startsWith("Error:");
		assertThat(result).contains("debug info");
		verify(breakpointTracker).removePendingBreakpoint(77);
	}

	/**
	 * {@code jdwp_get_breakpoint_context} renders the {@code this} field dump by calling
	 * {@code thisObj.getValue(field)} for each instance field. If a single field's read throws
	 * (e.g. {@link com.sun.jdi.ObjectCollectedException} on a field whose value has been GC'd),
	 * the tool must catch per-field, emit {@code "<unavailable>"} for the offending field, and
	 * keep rendering the rest. Aborting the whole dump on a single dead reference would erase the
	 * other fields the user wants to inspect.
	 */
	@Test
	void shouldAbortBreakpointContextOnPerFieldException() throws Exception {
		// Wire a current breakpoint with a live `this` and a single field whose getValue throws.
		final com.sun.jdi.ThreadReference thread = mock(com.sun.jdi.ThreadReference.class);
		when(thread.name()).thenReturn("main");
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);

		final com.sun.jdi.StackFrame frame = mock(com.sun.jdi.StackFrame.class);
		when(thread.frames()).thenReturn(List.of(frame));
		final com.sun.jdi.Location loc = mock(com.sun.jdi.Location.class);
		final com.sun.jdi.ReferenceType decl = mock(com.sun.jdi.ReferenceType.class);
		when(frame.location()).thenReturn(loc);
		when(loc.declaringType()).thenReturn(decl);
		when(decl.name()).thenReturn("com.example.User");
		final com.sun.jdi.Method method = mock(com.sun.jdi.Method.class);
		when(loc.method()).thenReturn(method);
		when(method.name()).thenReturn("m");
		when(loc.sourceName()).thenReturn("User.java");
		when(loc.lineNumber()).thenReturn(10);

		final com.sun.jdi.ObjectReference thisObj = mock(com.sun.jdi.ObjectReference.class);
		when(frame.thisObject()).thenReturn(thisObj);
		final com.sun.jdi.ReferenceType thisType = mock(com.sun.jdi.ReferenceType.class);
		when(thisObj.referenceType()).thenReturn(thisType);
		when(thisType.name()).thenReturn("com.example.User");
		when(thisObj.uniqueID()).thenReturn(42L);
		when(frame.visibleVariables()).thenReturn(List.of());
		when(frame.getValues(List.of())).thenReturn(java.util.Map.of());

		final com.sun.jdi.Field field = mock(com.sun.jdi.Field.class);
		when(field.isStatic()).thenReturn(false);
		when(field.typeName()).thenReturn("java.lang.String");
		when(field.name()).thenReturn("name");
		when(thisType.allFields()).thenReturn(List.of(field));
		when(thisObj.getValue(field)).thenThrow(new com.sun.jdi.ObjectCollectedException("gone"));

		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, 1));

		final String result = tools.jdwp_get_breakpoint_context(5, true);

		assertThat(result).doesNotStartWith("Error:");
		assertThat(result).contains("=== Breakpoint Context ===");
		assertThat(result).contains("name = <unavailable");
		assertThat(result).contains("ObjectCollectedException");
	}
}
