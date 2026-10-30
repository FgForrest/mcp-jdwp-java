package one.edee.mcp.jdwp;

import com.sun.jdi.IntegerValue;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Watchers and dependencies on fast breakpoint IDs, which the {@link BreakpointTracker} does not know:
 * a fast breakpoint takes watchers, a fast logpoint never does; clearing one removes its watchers;
 * neither kind can take part in a dependency; and the full-stack scan evaluates the watchers of every
 * breakpoint at a frame's line, ordinary and fast, at the logical frame above the hidden probe frames.
 */
@DisplayName("Watchers and dependencies on fast breakpoint IDs")
class JDWPToolsFastBreakpointWatcherTest {

	private static final int FAST_ID = 5;
	private static final int LOGPOINT_ID = 6;

	private JDIConnectionService jdiService;
	private BreakpointTracker tracker;
	private WatcherManager watcherManager;
	private JdiExpressionEvaluator evaluator;
	private FastLogpointService fastLogpoints;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		tracker = mock(BreakpointTracker.class);
		watcherManager = new WatcherManager();
		evaluator = mock(JdiExpressionEvaluator.class);
		fastLogpoints = JDWPToolsTestSupport.fastLogpointsMock();
		when(fastLogpoints.listedKind(FAST_ID)).thenReturn(FastLogpointService.Kind.BREAKPOINT);
		when(fastLogpoints.listedKind(LOGPOINT_ID)).thenReturn(FastLogpointService.Kind.LOGPOINT);
		tools = JDWPToolsTestSupport.newTools(jdiService, tracker, watcherManager, evaluator, fastLogpoints);
	}

	@Test
	@DisplayName("a fast breakpoint the tracker does not know takes a watcher")
	void fastBreakpointTakesAWatcher() {
		assertThat(tools.jdwp_attach_watcher(FAST_ID, "total", "total")).startsWith("✓ Watcher [")
			.contains("BP #" + FAST_ID);
		assertThat(watcherManager.getWatchersForBreakpoint(FAST_ID)).hasSize(1);
	}

	@Test
	@DisplayName("the service's refusal is returned and no watcher is created")
	void refusalCreatesNoWatcher() {
		when(fastLogpoints.watcherRefusal(LOGPOINT_ID)).thenReturn("[ERROR] #6 is a fast logpoint: it never stops");

		assertThat(tools.jdwp_attach_watcher(LOGPOINT_ID, "total", "total")).startsWith("[ERROR] #6 is a fast logpoint");
		assertThat(watcherManager.getAllWatchers()).isEmpty();
	}

	@Test
	@DisplayName("clearing a fast breakpoint removes its watchers and says so")
	void clearRemovesTheWatchers() {
		watcherManager.createWatcher("total", FAST_ID, "total");
		watcherManager.createWatcher("other", 9, "other");
		when(fastLogpoints.clear(FAST_ID)).thenReturn("Fast breakpoint 5 cleared — original bytecode restored");

		assertThat(tools.jdwp_clear_breakpoint(FAST_ID))
			.startsWith("Fast breakpoint 5 cleared")
			.endsWith("1 associated watcher(s) also removed.");
		assertThat(watcherManager.getWatchersForBreakpoint(FAST_ID)).isEmpty();
		assertThat(watcherManager.getAllWatchers()).as("other breakpoints keep theirs").hasSize(1);
	}

	@Test
	@DisplayName("jdwp_clear(types=fast_breakpoint) removes the watchers too")
	void bulkClearRemovesTheWatchers() {
		watcherManager.createWatcher("total", FAST_ID, "total");
		when(fastLogpoints.list()).thenReturn(List.of(new FastLogpointService.Info(FAST_ID, "com.example.Cart", 42, "",
			"total < 0", FastLogpointService.State.ARMED, null, 256, 0, 0, 0, true, null,
			FastLogpointService.Kind.BREAKPOINT, 2, true, null)));
		when(fastLogpoints.clear(FAST_ID)).thenReturn("Fast breakpoint 5 cleared");

		assertThat(tools.jdwp_clear("fast_breakpoint", null)).contains("removed #5");
		assertThat(watcherManager.getAllWatchers()).isEmpty();
	}

	@Test
	@DisplayName("a dependency refuses a fast breakpoint or a fast logpoint in either role")
	void dependencyRefusesFastIds() {
		when(tracker.isKnownBreakpointId(1)).thenReturn(true);

		assertThat(tools.jdwp_set_breakpoint_dependency(FAST_ID, 1, null))
			.isEqualTo("Error: Dependent breakpoint #5 is a fast breakpoint — a breakpoint dependency cannot include "
				+ "fast breakpoints or fast logpoints: the target JVM decides their hits.");
		assertThat(tools.jdwp_set_breakpoint_dependency(1, LOGPOINT_ID, null))
			.startsWith("Error: Trigger breakpoint #6 is a fast logpoint");
	}

	@Test
	@DisplayName("jdwp_set_breakpoint refuses a fast breakpoint as its trigger")
	void setBreakpointRefusesAFastTrigger() throws Exception {
		when(jdiService.getVM()).thenReturn(mock(VirtualMachine.class));

		assertThat(tools.jdwp_set_breakpoint("com.example.Cart", 50, null, null, FAST_ID, null, null))
			.startsWith("Error: Trigger breakpoint #5 is a fast breakpoint");
	}

	@Test
	@DisplayName("full stack: the watchers of every breakpoint at the line, ordinary and fast, at the logical frame")
	void fullStackEvaluatesEveryBreakpointAtTheLine() throws Exception {
		when(tracker.getBreakpointLocationMap()).thenReturn(Map.of("com.example.Cart:42", List.of(3)));
		when(fastLogpoints.breakpointIdsByLocation()).thenReturn(Map.of("com.example.Cart:42", List.of(FAST_ID)));
		watcherManager.createWatcher("fast", FAST_ID, "total");
		watcherManager.createWatcher("ordinary", 3, "count");

		// A fast stop: stop() and hit() of the probe on top, then the user's frame at the line.
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame stop = probeFrame("stop");
		final StackFrame hit = probeFrame("hit");
		final StackFrame user = mock(StackFrame.class);
		final Location userLocation = location("com.example.Cart", "total", 42);
		when(user.location()).thenReturn(userLocation);
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.name()).thenReturn("worker-0");
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(3);
		when(thread.frames(0, 3)).thenReturn(List.of(stop, hit, user));
		when(thread.frame(2)).thenReturn(user);
		final IntegerValue total = mock(IntegerValue.class);
		final IntegerValue count = mock(IntegerValue.class);
		when(evaluator.evaluate(eq(user), eq("total"), anyMap())).thenReturn(total);
		when(evaluator.evaluate(eq(user), eq("count"), anyMap())).thenReturn(count);
		when(jdiService.formatFieldValue(total)).thenReturn("120");
		when(jdiService.formatFieldValue(count)).thenReturn("3");

		final String result = tools.jdwp_evaluate_watchers(1L, "full_stack", null);

		assertThat(result)
			.contains("Frame #0: com.example.Cart:42 (Breakpoint ID: 3)")
			.contains("Frame #0: com.example.Cart:42 (Breakpoint ID: 5)")
			.contains("count = 3")
			.contains("total = 120")
			.contains("Total: Evaluated 2 expression(s)");
		assertThat(result.indexOf("Breakpoint ID: 3")).as("IDs ascending").isLessThan(result.indexOf("Breakpoint ID: 5"));
	}

	@Test
	@DisplayName("full stack with fast breakpoints only still scans the stack")
	void fullStackWithFastBreakpointsOnly() throws Exception {
		when(tracker.getBreakpointLocationMap()).thenReturn(Map.of());
		when(fastLogpoints.breakpointIdsByLocation()).thenReturn(Map.of("com.example.Cart:42", List.of(FAST_ID)));
		watcherManager.createWatcher("fast", FAST_ID, "total");
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame user = mock(StackFrame.class);
		final Location userLocation = location("com.example.Cart", "total", 42);
		when(user.location()).thenReturn(userLocation);
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.name()).thenReturn("worker-0");
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(1);
		when(thread.frames(0, 1)).thenReturn(List.of(user));
		when(thread.frame(0)).thenReturn(user);
		final IntegerValue total = mock(IntegerValue.class);
		when(evaluator.evaluate(eq(user), eq("total"), anyMap())).thenReturn(total);
		when(jdiService.formatFieldValue(total)).thenReturn("120");

		assertThat(tools.jdwp_evaluate_watchers(1L, "full_stack", null))
			.doesNotContain("No breakpoints found")
			.contains("total = 120");
	}

	private static StackFrame probeFrame(String method) {
		final StackFrame frame = mock(StackFrame.class);
		final Location location = location("com.example.McpFastBreakpoint_1a2b", method, 7);
		when(frame.location()).thenReturn(location);
		return frame;
	}

	private static Location location(String className, String methodName, int line) {
		final Location location = mock(Location.class);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(className);
		final Method method = mock(Method.class);
		when(method.name()).thenReturn(methodName);
		when(location.declaringType()).thenReturn(type);
		when(location.method()).thenReturn(method);
		when(location.lineNumber()).thenReturn(line);
		return location;
	}
}
