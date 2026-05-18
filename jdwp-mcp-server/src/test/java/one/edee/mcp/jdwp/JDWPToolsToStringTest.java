package one.edee.mcp.jdwp;

import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JDWPTools#jdwp_to_string}: happy path, object-not-in-cache, explicit
 * thread vs. fallback, suspended-state guard, missing toString(), and the
 * {@link EvaluationGuard#enter}/{@link EvaluationGuard#exit} pairing around {@code invokeMethod}.
 */
@DisplayName("jdwp_to_string")
class JDWPToolsToStringTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private EvaluationGuard evaluationGuard;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		evaluationGuard = mock(EvaluationGuard.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, evaluationGuard, new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
	}

	private ThreadReference suspendedThread(long id) {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(id);
		when(thread.isSuspended()).thenReturn(true);
		return thread;
	}

	@Test
	@DisplayName("happy path — formats Object#N (Type).toString() = \"...\"")
	void shouldFormatToStringResultOnHappyPath() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method toStringMethod = mock(Method.class);
		final StringReference result = mock(StringReference.class);
		final ThreadReference thread = suspendedThread(1L);

		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(obj.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("com.example.Foo");
		when(type.methodsByName("toString", "()Ljava/lang/String;"))
			.thenReturn(List.of(toStringMethod));
		when(obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
			ObjectReference.INVOKE_SINGLE_THREADED)).thenReturn(result);
		when(result.value()).thenReturn("Foo[id=1]");

		final String out = tools.jdwp_to_string(42L, 1L);

		assertThat(out).isEqualTo("Object #42 (com.example.Foo).toString() = \"Foo[id=1]\"");
	}

	@Test
	@DisplayName("returns '[ERROR] Object not in cache' when the object id is unknown")
	void shouldReturnErrorWhenObjectNotInCache() {
		when(jdiService.getCachedObject(42L)).thenReturn(null);

		final String out = tools.jdwp_to_string(42L, null);

		assertThat(out).contains("[ERROR] Object #42 not found in cache");
	}

	@Test
	@DisplayName("uses the explicit threadId when supplied")
	void shouldUseExplicitThreadIdWhenSupplied() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method toStringMethod = mock(Method.class);
		final StringReference result = mock(StringReference.class);
		final ThreadReference thread = suspendedThread(99L);

		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(obj.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("Foo");
		when(type.methodsByName("toString", "()Ljava/lang/String;"))
			.thenReturn(List.of(toStringMethod));
		when(obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
			ObjectReference.INVOKE_SINGLE_THREADED)).thenReturn(result);
		when(result.value()).thenReturn("v");

		final String out = tools.jdwp_to_string(42L, 99L);

		assertThat(out).contains("\"v\"");
	}

	@Test
	@DisplayName("falls back to the last breakpoint thread when threadId is null")
	void shouldFallBackToLastBreakpointThreadWhenThreadIdIsNull() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method toStringMethod = mock(Method.class);
		final StringReference result = mock(StringReference.class);
		final ThreadReference thread = suspendedThread(7L);

		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(breakpointTracker.getLastBreakpointThread()).thenReturn(thread);
		when(obj.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("Foo");
		when(type.methodsByName("toString", "()Ljava/lang/String;"))
			.thenReturn(List.of(toStringMethod));
		when(obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
			ObjectReference.INVOKE_SINGLE_THREADED)).thenReturn(result);
		when(result.value()).thenReturn("v");

		final String out = tools.jdwp_to_string(42L, null);

		assertThat(out).contains("\"v\"");
	}

	@Test
	@DisplayName("rejects when the resolved thread is not suspended")
	void shouldRejectWhenThreadIsNotSuspended() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(false);
		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));

		final String out = tools.jdwp_to_string(42L, 1L);

		assertThat(out).isEqualTo("Error: Thread is not suspended.");
	}

	@Test
	@DisplayName("returns 'no toString() method' when the type has none")
	void shouldReturnHelpfulMessageWhenNoToStringMethodFound() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final ThreadReference thread = suspendedThread(1L);

		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(obj.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("com.example.Bare");
		when(type.methodsByName("toString", "()Ljava/lang/String;"))
			.thenReturn(List.of());

		final String out = tools.jdwp_to_string(42L, 1L);

		assertThat(out).isEqualTo("Object #42 (com.example.Bare): no toString() method found");
	}

	/**
	 * The reentrancy guard must be entered BEFORE {@code invokeMethod} and exited AFTER — in
	 * that exact order — so the listener can suppress recursive breakpoints fired during the
	 * synthetic call. Verified via {@link InOrder} on the {@link EvaluationGuard}.
	 */
	@Test
	@DisplayName("brackets invokeMethod with EvaluationGuard.enter/exit in order")
	void shouldBracketInvokeMethodWithEvaluationGuard() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method toStringMethod = mock(Method.class);
		final StringReference result = mock(StringReference.class);
		final ThreadReference thread = suspendedThread(5L);

		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(obj.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("Foo");
		when(type.methodsByName("toString", "()Ljava/lang/String;"))
			.thenReturn(List.of(toStringMethod));
		when(obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
			ObjectReference.INVOKE_SINGLE_THREADED)).thenReturn(result);
		when(result.value()).thenReturn("v");

		tools.jdwp_to_string(42L, 5L);

		final InOrder order = inOrder(evaluationGuard, obj);
		order.verify(evaluationGuard).enter(5L);
		order.verify(obj).invokeMethod(thread, toStringMethod, Collections.emptyList(),
			ObjectReference.INVOKE_SINGLE_THREADED);
		order.verify(evaluationGuard).exit(5L);
	}

	/**
	 * When the target VM disconnects mid-{@code invokeMethod}, JDI throws an unchecked
	 * {@link VMDisconnectedException}. The tool must recognise the disconnect and surface the
	 * canonical {@code [VM_DEATH]} hint pointing the user at {@code jdwp_connect} /
	 * {@code jdwp_wait_for_attach} instead of a generic error.
	 */
	@Test
	@DisplayName("surfaces VMDisconnectedException as the canonical [VM_DEATH] hint")
	void shouldSurfaceVmDisconnectedAsGenericError() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method toStringMethod = mock(Method.class);
		final ThreadReference thread = suspendedThread(1L);

		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(obj.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("Foo");
		when(type.methodsByName("toString", "()Ljava/lang/String;"))
			.thenReturn(List.of(toStringMethod));
		when(obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
			ObjectReference.INVOKE_SINGLE_THREADED))
			.thenThrow(new VMDisconnectedException("vm gone"));

		final String out = tools.jdwp_to_string(42L, 1L);

		assertThat(out).startsWith("[VM_DEATH]");
		assertThat(out).contains("jdwp_to_string");
		assertThat(out).contains("jdwp_connect");
		assertThat(out).contains("jdwp_wait_for_attach");
	}

	/**
	 * F-RA1: a {@link SocketException} buried in the cause chain of the unchecked throwable that
	 * JDI raises mid-{@code invokeMethod} must be recognised by the catch block and rendered as
	 * the canonical {@code [VM_DEATH]} envelope. Without the cause-chain walk the wrapped form
	 * leaked through as {@code "Error invoking toString(): wrapper"} and the agent's re-attach
	 * recipe was hidden.
	 */
	@Test
	@DisplayName("F-RA1: surfaces wrapped SocketException as the canonical [VM_DEATH] hint")
	void shouldSurfaceSocketExceptionAsCanonicalHint() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method toStringMethod = mock(Method.class);
		final ThreadReference thread = suspendedThread(1L);

		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(obj.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("Foo");
		when(type.methodsByName("toString", "()Ljava/lang/String;"))
			.thenReturn(List.of(toStringMethod));
		when(obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
			ObjectReference.INVOKE_SINGLE_THREADED))
			.thenThrow(new RuntimeException("wrapper", new SocketException("Broken pipe")));

		final String out = tools.jdwp_to_string(42L, 1L);

		assertThat(out).startsWith("[VM_DEATH]");
		assertThat(out).contains("jdwp_to_string");
	}

	/**
	 * F-RA1: transport failures that surface only as a generic {@link IOException} (no
	 * {@code SocketException} or {@code VMDisconnectedException} in the chain) must still be
	 * recognised via the message-substring fallback inside {@code isVmGone}. Pins the
	 * "Connection reset" branch of the message scan.
	 */
	@Test
	@DisplayName("F-RA1: surfaces message-only transport failure as the canonical [VM_DEATH] hint")
	void shouldSurfaceMessageOnlyTransportFailureAsCanonicalHint() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method toStringMethod = mock(Method.class);
		final ThreadReference thread = suspendedThread(1L);

		when(jdiService.getCachedObject(42L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(obj.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("Foo");
		when(type.methodsByName("toString", "()Ljava/lang/String;"))
			.thenReturn(List.of(toStringMethod));
		// Wrap the IOException in an unchecked exception — invokeMethod doesn't declare IOException
		// so Mockito refuses to plant it raw, but production code sees the same unchecked wrapper
		// shape any time JDI bubbles a transport failure out of a non-throwing method.
		when(obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
			ObjectReference.INVOKE_SINGLE_THREADED))
			.thenThrow(new RuntimeException(new IOException("Connection reset")));

		final String out = tools.jdwp_to_string(42L, 1L);

		assertThat(out).startsWith("[VM_DEATH]");
		assertThat(out).contains("jdwp_to_string");
	}
}
