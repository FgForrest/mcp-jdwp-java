package one.edee.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link JDWPTools#jdwp_get_locals} when the current method has no
 * {@code LocalVariableTable} (target compiled without {@code -g:vars}).
 *
 * <p>Contract: {@code frame.visibleVariables()} throws a message-less
 * {@link AbsentInformationException}. The tool must NOT collapse the whole response into the
 * useless literal {@code "Error: null"} and discard the {@code this} receiver it already
 * rendered. Instead it degrades gracefully — the same partial-output principle the codebase
 * already applies in {@code appendMarkedInstancesFooter} and {@code jdwp_get_breakpoint_context}.
 */
@DisplayName("jdwp_get_locals — missing LocalVariableTable")
class JDWPToolsGetLocalsAbsentInfoTest {

	private JDIConnectionService jdiService;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		vm = mock(VirtualMachine.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, mock(BreakpointTracker.class), mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class), new EventHistory(),
			new EvaluationGuard(), mock(JvmDiscoveryService.class));
	}

	/**
	 * Wires a suspended frame whose {@code visibleVariables()} throws {@link AbsentInformationException}
	 * (no LocalVariableTable) and whose location resolves to {@code com.example.Foo.doWork} for the
	 * hint. When {@code thisObj} is non-null it is returned as the frame receiver.
	 */
	private void primeFrameWithAbsentVariables(long threadId, ObjectReference thisObj) throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(threadId);
		when(thread.isSuspended()).thenReturn(true);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		when(frame.thisObject()).thenReturn(thisObj);
		when(frame.visibleVariables()).thenThrow(new AbsentInformationException());
		final Location loc = mock(Location.class);
		final Method method = mock(Method.class);
		final ReferenceType declaringType = mock(ReferenceType.class);
		when(method.name()).thenReturn("doWork");
		when(declaringType.name()).thenReturn("com.example.Foo");
		when(loc.method()).thenReturn(method);
		when(loc.declaringType()).thenReturn(declaringType);
		when(frame.location()).thenReturn(loc);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
	}

	private ObjectReference liveThis(String typeName) {
		final ObjectReference r = mock(ObjectReference.class);
		when(r.uniqueID()).thenReturn(500L);
		when(r.isCollected()).thenReturn(false);
		when(r.virtualMachine()).thenReturn(vm);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(typeName);
		when(r.referenceType()).thenReturn(type);
		return r;
	}

	@Test
	@DisplayName("instance method → keeps 'this', appends actionable hint, never 'Error: null'")
	void shouldDegradeGracefullyKeepingThis() throws Exception {
		primeFrameWithAbsentVariables(1L, liveThis("com.example.Foo"));

		final String out = tools.jdwp_get_locals(1L, 0);

		assertThat(out)
			.doesNotContain("Error: null")
			.doesNotStartWith("Error:")
			.contains("Local variables in frame 0")
			.contains("this (com.example.Foo)")
			.contains("no LocalVariableTable")
			.contains("com.example.Foo.doWork")
			.contains("-g");
	}

	@Test
	@DisplayName("static method (no 'this') → hint does not falsely claim 'this' is available")
	void shouldDegradeGracefullyForStaticMethod() throws Exception {
		primeFrameWithAbsentVariables(1L, null);

		final String out = tools.jdwp_get_locals(1L, 0);

		assertThat(out)
			.doesNotContain("Error: null")
			.contains("Local variables in frame 0")
			.contains("no LocalVariableTable")
			// A static frame has no receiver — the hint must not claim 'this' still works.
			.contains("static frame")
			.doesNotContain("'this' (above)");
	}

	@Test
	@DisplayName("blank exception message in the outer catch falls back to the class name")
	void shouldFallBackToClassNameForBlankMessage() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		// A whitespace-only message must be treated the same as null: "Error: " alone is useless.
		when(thread.frame(0)).thenThrow(new IllegalStateException("   "));
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));

		final String out = tools.jdwp_get_locals(1L, 0);

		assertThat(out)
			.startsWith("Error:")
			.contains("IllegalStateException");
	}

	@Test
	@DisplayName("message-less exception in the outer catch renders the class name, not 'Error: null'")
	void shouldNotRenderErrorNullForMessagelessException() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		// A message-less exception thrown before the locals loop reaches the tool's outer catch.
		when(thread.frame(0)).thenThrow(new IllegalStateException());
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));

		final String out = tools.jdwp_get_locals(1L, 0);

		assertThat(out)
			.startsWith("Error:")
			.doesNotContain("Error: null")
			.contains("IllegalStateException");
	}
}
