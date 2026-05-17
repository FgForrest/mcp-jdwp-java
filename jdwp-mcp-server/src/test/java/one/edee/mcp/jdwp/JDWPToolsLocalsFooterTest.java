package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@code appendMarkedInstancesFooter} rendering through {@link JDWPTools#jdwp_get_locals}.
 *
 * <p>Footer contract:
 * <ul>
 *   <li>Empty registry → footer entirely omitted; locals output unchanged.</li>
 *   <li>Non-empty registry → "--- Marked instances visible to expressions ---" section
 *       listing every label with its type, object id, and a "[collected — binding will be
 *       skipped]" suffix when the underlying mirror has died.</li>
 *   <li>Labels render alphabetically so output is stable across calls.</li>
 * </ul>
 */
@DisplayName("jdwp_get_locals — marked instances footer")
class JDWPToolsLocalsFooterTest {

	private JDIConnectionService jdiService;
	private MarkedInstanceRegistry registry;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker tracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory history = new EventHistory();
		registry = new MarkedInstanceRegistry();
		vm = mock(VirtualMachine.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator, history,
			new EvaluationGuard(), new JvmDiscoveryService(), registry);
	}

	/**
	 * Wires the minimum JDI mock chain needed for {@code jdwp_get_locals(threadId, 0)} to return
	 * normally with no locals — the call's "this" object is null (static method), the frame's
	 * visibleVariables is empty, and the live VM is returned by {@link JDIConnectionService#getVM()}.
	 * Returns the thread mock so the test can assert against the configured thread id.
	 */
	private ThreadReference primeEmptyFrame(long threadId) throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(threadId);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		when(frame.thisObject()).thenReturn(null);
		when(frame.visibleVariables()).thenReturn(Collections.emptyList());
		when(frame.getValues(Collections.emptyList())).thenReturn(Map.of());
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		return thread;
	}

	private ObjectReference liveRef(long id, String typeName) {
		final ObjectReference r = mock(ObjectReference.class);
		when(r.uniqueID()).thenReturn(id);
		when(r.isCollected()).thenReturn(false);
		when(r.virtualMachine()).thenReturn(vm);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(typeName);
		when(r.referenceType()).thenReturn(type);
		return r;
	}

	@Test
	@DisplayName("empty registry → footer entirely omitted, locals payload unchanged")
	void shouldOmitFooterWhenNoMarks() throws Exception {
		primeEmptyFrame(1L);

		final String out = tools.jdwp_get_locals(1L, 0);

		assertThat(out)
			.contains("Local variables in frame 0")
			.doesNotContain("Marked instances")
			.doesNotContain("visible to expressions");
	}

	@Test
	@DisplayName("non-empty registry → footer lists each mark with $label : Type (Object#N)")
	void shouldRenderFooterForEachMark() throws Exception {
		primeEmptyFrame(1L);
		registry.mark("cart", liveRef(42L, "com.example.Cart"), true);
		registry.mark("user", liveRef(7L, "com.example.User"), false);

		final String out = tools.jdwp_get_locals(1L, 0);

		assertThat(out)
			.contains("--- Marked instances visible to expressions ---")
			.contains("$cart : com.example.Cart (Object#42)")
			.contains("$user : com.example.User (Object#7)");
	}

	@Test
	@DisplayName("collected mark → footer row carries '[collected — binding will be skipped]'")
	void shouldFlagCollectedMarks() throws Exception {
		primeEmptyFrame(1L);
		final ObjectReference dead = liveRef(99L, "com.example.D");
		registry.mark("dead", dead, false);
		// Mirror dies after registration.
		when(dead.isCollected()).thenReturn(true);

		final String out = tools.jdwp_get_locals(1L, 0);

		assertThat(out)
			.contains("$dead : com.example.D (Object#99)")
			.contains("[collected — binding will be skipped]");
	}

	@Test
	@DisplayName("footer labels rendered alphabetically regardless of insertion order")
	void shouldRenderFooterLabelsAlphabetically() throws Exception {
		primeEmptyFrame(1L);
		registry.mark("zulu", liveRef(1L, "T"), false);
		registry.mark("alpha", liveRef(2L, "T"), false);
		registry.mark("mike", liveRef(3L, "T"), false);

		final String out = tools.jdwp_get_locals(1L, 0);

		final int alpha = out.indexOf("$alpha");
		final int mike = out.indexOf("$mike");
		final int zulu = out.indexOf("$zulu");
		assertThat(alpha).isPositive();
		assertThat(alpha).isLessThan(mike);
		assertThat(mike).isLessThan(zulu);
	}

	/**
	 * The footer is an advisory tail: a failure inside the marked-instance enumeration must not
	 * destroy the primary locals payload. Without isolation, an exception thrown by
	 * {@code registry.list()} bubbles out of {@code jdwp_get_locals}, the outer catch in the tool
	 * returns {@code "Error: ..."}, and the agent loses every variable in the frame. The footer
	 * helper guards itself with a try/catch that appends a one-line warning instead so the agent
	 * still sees the locals plus a hint that the mark footer was unavailable.
	 */
	@Test
	@DisplayName("registry failure during footer → locals payload preserved, warning appended")
	void shouldPreserveLocalsPayloadWhenFooterFails() throws Exception {
		// Re-wire the tools with a spy registry that throws from list() so we can drive the failure
		// path. A non-empty marker entry is registered first so isEmpty() returns false and the
		// helper proceeds to list().
		final MarkedInstanceRegistry spyRegistry = spy(new MarkedInstanceRegistry());
		spyRegistry.mark("sentinel", liveRef(1L, "T"), false);
		doThrow(new RuntimeException("registry boom")).when(spyRegistry).list();

		final JDIConnectionService jdiSvc = mock(JDIConnectionService.class);
		final VirtualMachine localVm = mock(VirtualMachine.class);
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(1L);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		when(frame.thisObject()).thenReturn(null);
		when(frame.visibleVariables()).thenReturn(Collections.emptyList());
		when(frame.getValues(Collections.emptyList())).thenReturn(Map.of());
		when(jdiSvc.getVM()).thenReturn(localVm);
		when(localVm.allThreads()).thenReturn(List.of(thread));

		final JDWPTools localTools = JDWPToolsTestSupport.newTools(
			jdiSvc, mock(BreakpointTracker.class), mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class), new EventHistory(),
			new EvaluationGuard(), new JvmDiscoveryService(), spyRegistry);

		final String out = localTools.jdwp_get_locals(1L, 0);

		assertThat(out)
			.startsWith("Local variables in frame 0")
			.contains("marked-instances footer unavailable")
			.contains("registry boom");
	}
}
