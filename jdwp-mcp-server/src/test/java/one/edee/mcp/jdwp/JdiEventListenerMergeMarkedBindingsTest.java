package one.edee.mcp.jdwp;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JdiEventListener}'s marked-binding merging behaviour.
 *
 * <p>The {@code mergeMarkedBindings(Map)} helper combines per-event synthetic bindings (e.g.
 * {@code $exception}, {@code $oldValue}) with the agent-registered marks. The four pins:
 *
 * <ul>
 *   <li><b>Empty registry fast-path</b> — the caller's map is returned by reference so the hot
 *       per-event path stays allocation-free in the common case.</li>
 *   <li><b>Mark materialisation</b> — non-empty registry surfaces every live entry as a
 *       {@code $label} key in the merged map.</li>
 *   <li><b>Per-event precedence</b> — on name collision the extraBindings value wins, even though
 *       the reserved-binding validator already prevents marks from using reserved names. The
 *       precedence is asserted via the merge helper directly so a future refactor cannot silently
 *       invert it.</li>
 *   <li><b>Conditional-breakpoint path</b> — the marks merge is also applied when evaluating
 *       conditional-breakpoint expressions, so {@code $label} bindings are usable in conditions
 *       just like they are in logpoint / watcher / exception-logpoint expressions.</li>
 * </ul>
 */
@DisplayName("JdiEventListener marked-bindings merging")
class JdiEventListenerMergeMarkedBindingsTest {

	private MarkedInstanceRegistry registry;
	private BreakpointTracker tracker;
	private JdiExpressionEvaluator evaluator;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		registry = new MarkedInstanceRegistry();
		tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, history, evaluator,
			new EvaluationGuard(), null, registry);
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	/**
	 * Invokes the private {@code mergeMarkedBindings(Map)} via reflection. Returning the merged
	 * map lets each test inspect both contents and identity.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Value> invokeMerge(Map<String, Value> extraBindings) throws Exception {
		final Method m = JdiEventListener.class.getDeclaredMethod("mergeMarkedBindings", Map.class);
		m.setAccessible(true);
		return (Map<String, Value>) m.invoke(listener, extraBindings);
	}

	/**
	 * Live (not-collected) {@link ObjectReference} suitable for registry storage with a recognisable
	 * type name so failures surface a useful diagnostic.
	 */
	private ObjectReference markRef(String typeName) {
		final ObjectReference r = mock(ObjectReference.class);
		when(r.isCollected()).thenReturn(false);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(typeName);
		when(r.referenceType()).thenReturn(type);
		return r;
	}

	@Test
	@DisplayName("empty registry → caller's extraBindings map returned verbatim (no allocation)")
	void shouldReturnExtraBindingsByReferenceWhenRegistryEmpty() throws Exception {
		final Value sentinel = mock(Value.class);
		final Map<String, Value> extra = new HashMap<>();
		extra.put("$exception", sentinel);

		final Map<String, Value> result = invokeMerge(extra);

		// Fast-path contract: the SAME map instance is returned, not a copy.
		assertThat(result).isSameAs(extra);
		assertThat(result).containsEntry("$exception", sentinel);
	}

	@Test
	@DisplayName("non-empty registry + empty extraBindings → marks surface as $label entries")
	void shouldMaterialiseMarksWhenExtraBindingsEmpty() throws Exception {
		final ObjectReference cartRef = markRef("com.example.Cart");
		registry.mark("cart", cartRef, false);

		final Map<String, Value> result = invokeMerge(Map.of());

		assertThat(result)
			.containsOnlyKeys("$cart")
			.containsEntry("$cart", cartRef);
	}

	@Test
	@DisplayName("name collision → extraBindings value wins (per-event precedence preserved)")
	void shouldPreferExtraBindingsOnNameCollision() throws Exception {
		// The reserved-binding validator normally prevents collisions, but we drive the helper
		// directly to prove the merge order. The intent: even if a future code path were to inject
		// a colliding name, the per-event binding must beat the registry value so semantics like
		// $exception / $oldValue cannot be silently shadowed by a mark.
		final ObjectReference markValue = markRef("com.example.X");
		registry.mark("collide", markValue, false);

		final Value perEventValue = mock(Value.class);
		final Map<String, Value> extra = new HashMap<>();
		extra.put("$collide", perEventValue);

		final Map<String, Value> result = invokeMerge(extra);

		assertThat(result.get("$collide")).isSameAs(perEventValue);
		assertThat(result.get("$collide")).isNotSameAs(markValue);
	}

	@Test
	@DisplayName("conditional breakpoint evaluation sees marked-instance bindings")
	void shouldPropagateMarksToConditionEvaluation() throws Exception {
		// Marks must be visible to conditional-breakpoint expressions just like they are to
		// logpoint / exception-logpoint / watcher / field-logpoint expressions: the condition path
		// merges the registry's $label bindings into the per-event extras before handing the map
		// to the evaluator, so a condition like `$cart.items.size() > 3` is resolvable.
		final ObjectReference cartRef = markRef("com.example.Cart");
		registry.mark("cart", cartRef, false);
		assertThat(registry.buildBindings()).containsKey("$cart");

		final BreakpointRequest bp = mock(BreakpointRequest.class);
		final int bpId = tracker.registerBreakpoint(bp);
		tracker.setCondition(bpId, "cart != null");

		final ThreadReference thread = mockThread("worker-cond-mark", 9001L);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.frame(0)).thenReturn(frame);
		final BooleanValue trueResult = mock(BooleanValue.class);
		when(trueResult.value()).thenReturn(true);
		when(evaluator.evaluate(any(StackFrame.class), anyString(), any())).thenReturn(trueResult);

		final BreakpointEvent event = mockBreakpointEvent(thread, bp, "com.Foo", 10);
		final EventSet eventSet = mockEventSet(event);

		runListenerWith(listener, eventSet);

		// Capture the extraBindings actually passed to the evaluator for the condition expression.
		@SuppressWarnings("unchecked")
		final ArgumentCaptor<Map<String, Value>> bindingsCaptor =
			ArgumentCaptor.forClass(Map.class);
		verify(evaluator).evaluate(any(StackFrame.class), eq("cart != null"), bindingsCaptor.capture());
		final Map<String, Value> conditionBindings = bindingsCaptor.getValue();

		// Contract: the registry's marks are merged into the bindings handed to the evaluator, so
		// the condition expression can dereference $cart directly.
		assertThat(conditionBindings)
			.containsKey("$cart")
			.containsEntry("$cart", cartRef);
	}
}
