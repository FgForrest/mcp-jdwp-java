package one.edee.mcp.jdwp;

import com.sun.jdi.IntegerValue;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.Watcher;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for P0-5: a single captured {@link StackFrame} reused across multiple
 * watcher evaluations on the same hit failed with {@link InvalidStackFrameException} ("Thread has
 * been resumed") on every watcher after the first, because every {@code invokeMethod} on the
 * underlying {@link ThreadReference} invalidates all previously-captured stack frames.
 *
 * <p>This test simulates the JDI invalidation contract: the first {@code thread.frame(0)} call
 * returns a "stale" StackFrame whose locals/this calls throw {@link InvalidStackFrameException},
 * and a second {@code thread.frame(0)} call returns a fresh frame that succeeds. The fix asserts
 * that {@code evaluateWatchersCurrentFrame} re-fetches the frame inside the per-watcher loop, so
 * watcher #2 receives the fresh frame instead of the stale one — and both watchers produce
 * results.
 *
 * <p>Also locks the new partial-failure tally line: when one watcher errors and another succeeds,
 * the rendered "Total" line splits the counts ({@code Evaluated 2 (1 succeeded, 1 errored)})
 * instead of conflating them.
 */
@DisplayName("jdwp_evaluate_watchers — per-watcher frame re-fetch (P0-5)")
class JDWPToolsEvaluateWatchersFrameRefetchTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private WatcherManager watcherManager;
	private JdiExpressionEvaluator evaluator;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		watcherManager = mock(WatcherManager.class);
		evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
	}

	/** Gives each watcher a distinct frame after compiler setup may resume the thread. */
	@Test
	@DisplayName("two watchers on one BP: both evaluate because frame is re-fetched per iteration")
	void shouldRefetchFramePerWatcherSoBothEvaluate() throws Exception {
		// Setup: BP #1 has two watchers attached.
		final Watcher w1 = new Watcher("first", 1, "x");
		final Watcher w2 = new Watcher("second", 1, "y");
		when(watcherManager.getWatchersForBreakpoint(1)).thenReturn(List.of(w1, w2));

		// Each watcher reads once for loader selection and again for execution.
		// Only the post-setup frame can be used for the wrapper.
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame headerFrame = mock(StackFrame.class);
		final StackFrame loaderFrame1 = mock(StackFrame.class);
		final StackFrame frameForWatcher1 = mock(StackFrame.class);
		final StackFrame loaderFrame2 = mock(StackFrame.class);
		final StackFrame frameForWatcher2 = mock(StackFrame.class);

		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(5);
		when(thread.name()).thenReturn("main");
		when(thread.frame(0)).thenReturn(headerFrame, loaderFrame1, frameForWatcher1, loaderFrame2, frameForWatcher2);

		final Location loc = mock(Location.class);
		final ReferenceType declaringType = mock(ReferenceType.class);
		when(headerFrame.location()).thenReturn(loc);
		when(loc.declaringType()).thenReturn(declaringType);
		when(declaringType.name()).thenReturn("com.example.MyClass");
		when(loc.lineNumber()).thenReturn(42);

		final IntegerValue v1 = mock(IntegerValue.class);
		final IntegerValue v2 = mock(IntegerValue.class);
		// Stub each watcher to its OWN fresh frame — verifies that the re-fetched frames were
		// actually passed in. If the production code captured a frame once and reused it,
		// watcher #2 would receive frameForWatcher1 (the stale one) and this stub would not match.
		when(evaluator.evaluate(eq(frameForWatcher1), eq("x"), anyMap())).thenReturn(v1);
		when(evaluator.evaluate(eq(frameForWatcher2), eq("y"), anyMap())).thenReturn(v2);
		when(jdiService.formatFieldValue(v1)).thenReturn("11");
		when(jdiService.formatFieldValue(v2)).thenReturn("22");

		final String result = tools.jdwp_evaluate_watchers(1L, "current_frame", 1);

		assertThat(result).contains("x = 11");
		assertThat(result).contains("y = 22");
		assertThat(result).doesNotContain("[ERROR");
		// Tally must read 2 (both succeeded) — no split because no errors.
		assertThat(result).contains("Total: Evaluated 2 expression(s)");
		// One header read and two reads per compiled watcher.
		verify(thread, times(5)).frame(0);
	}

	@Test
	@DisplayName("partial failure: one watcher errors → tally splits succeeded vs errored counts")
	void shouldSplitTallyOnPartialFailure() throws Exception {
		final Watcher w1 = new Watcher("ok-one", 1, "x");
		final Watcher w2 = new Watcher("broken-one", 1, "boom");
		when(watcherManager.getWatchersForBreakpoint(1)).thenReturn(List.of(w1, w2));

		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(1);
		when(thread.name()).thenReturn("main");
		when(thread.frame(0)).thenReturn(frame);
		final Location loc = mock(Location.class);
		final ReferenceType declaringType = mock(ReferenceType.class);
		when(frame.location()).thenReturn(loc);
		when(loc.declaringType()).thenReturn(declaringType);
		when(declaringType.name()).thenReturn("com.example.MyClass");
		when(loc.lineNumber()).thenReturn(42);

		final IntegerValue v1 = mock(IntegerValue.class);
		when(evaluator.evaluate(eq(frame), eq("x"), anyMap())).thenReturn(v1);
		when(jdiService.formatFieldValue(v1)).thenReturn("11");
		when(evaluator.evaluate(eq(frame), eq("boom"), anyMap()))
			.thenThrow(new RuntimeException("compilation failed"));

		final String result = tools.jdwp_evaluate_watchers(1L, "current_frame", 1);

		assertThat(result).contains("x = 11");
		assertThat(result).contains("boom = [ERROR: compilation failed]");
		// Split tally proves the counter pair is wired correctly.
		assertThat(result).contains("Total: Evaluated 2 (1 succeeded, 1 errored)");
	}
}
