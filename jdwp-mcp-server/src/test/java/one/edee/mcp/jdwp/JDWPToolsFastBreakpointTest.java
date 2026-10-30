package one.edee.mcp.jdwp;

import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tool-surface contract of {@code jdwp_set_fast_breakpoint}: invalid arguments are refused and the
 * consent gate returns the briefing, both without touching the VM; {@code jdwp_overview} lists fast
 * breakpoints in their own section; a fast breakpoint's stop names itself and its reason; and the
 * resume tools wait for an arming in flight before they resume the VM.
 */
@DisplayName("jdwp_set_fast_breakpoint tool surface")
class JDWPToolsFastBreakpointTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker tracker;
	private FastLogpointService fastLogpoints;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		fastLogpoints = JDWPToolsTestSupport.fastLogpointsMock();
		tools = JDWPToolsTestSupport.newTools(jdiService, tracker, new EventHistory(), fastLogpoints);
	}

	@Test
	@DisplayName("missing or false userApproved returns the risk briefing for the requested policy and mode")
	void consentGate() throws Exception {
		when(jdiService.getConnectionStatus()).thenReturn(
			new JDIConnectionService.ConnectionStatus(true, "localhost", 5005, Instant.now(), null));
		for (Boolean approval : new Boolean[]{null, false}) {
			assertThat(tools.jdwp_set_fast_breakpoint("com.example.Cart", 42, "total < 0", approval, "thread", false, null))
				.startsWith("[CONSENT REQUIRED] Nothing was armed.")
				.contains("com.example.Cart:42 — condition: total < 0 — stops on every hit — suspendPolicy: thread")
				.contains("the JVM attached at localhost:5005")
				.contains("that thread is paused");
		}
		verify(jdiService, never()).getVM();
		verifyNoInteractions(fastLogpoints);
	}

	@Test
	@DisplayName("a blank condition or a policy that does not suspend is refused before the briefing")
	void invalidArgumentsBeforeTheGate() throws Exception {
		assertThat(tools.jdwp_set_fast_breakpoint("com.example.Cart", 42, " ", null, null, null, null))
			.startsWith("Error: condition is required");
		assertThat(tools.jdwp_set_fast_breakpoint("com.example.Cart", 42, "total < 0", null, "none", null, null))
			.startsWith("Error: Invalid suspend policy 'none'");
		verify(jdiService, never()).getVM();
		verifyNoInteractions(fastLogpoints);
	}

	@Test
	@DisplayName("a call matching a fast breakpoint already set at the line is answered by the re-arm, not registered again")
	void sameLineGoesToReArm() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final com.sun.jdi.ReferenceType type = mock(com.sun.jdi.ReferenceType.class);
		final com.sun.jdi.Location location = mock(com.sun.jdi.Location.class);
		final com.sun.jdi.Method method = mock(com.sun.jdi.Method.class);
		when(jdiService.getRawVM()).thenReturn(vm);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.canRedefineClasses()).thenReturn(true);
		when(vm.canGetBytecodes()).thenReturn(true);
		when(vm.canGetConstantPool()).thenReturn(true);
		when(jdiService.findLoadedClass("com.example.Cart")).thenReturn(type);
		when(type.name()).thenReturn("com.example.Cart");
		when(type.classLoader()).thenReturn(mock(com.sun.jdi.ClassLoaderReference.class));
		when(type.locationsOfLine(42)).thenReturn(List.of(location));
		when(location.method()).thenReturn(method);
		when(fastLogpoints.rearmBreakpoint(location, "total < 0", true, EventRequest.SUSPEND_ALL))
			.thenReturn("Fast breakpoint 7 at com.example.Cart:42 re-armed (ID: 7)");

		assertThat(tools.jdwp_set_fast_breakpoint("com.example.Cart", 42, "total < 0", true, null, null, null))
			.isEqualTo("Fast breakpoint 7 at com.example.Cart:42 re-armed (ID: 7)");
		verify(fastLogpoints, never()).registerBreakpoint(any(), any(), anyString(), anyBoolean(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("jdwp_overview lists fast breakpoints in their own section, fast logpoints in theirs")
	void overviewSeparatesKinds() {
		final FastLogpointService.Provenance provenance = new FastLogpointService.Provenance(
			Instant.parse("2026-10-28T15:02:11Z"), "mcp-jdwp-java, session 3fa1c2d9", "AppClassLoader#12", 0);
		when(fastLogpoints.list()).thenReturn(List.of(
			new FastLogpointService.Info(3, "com.example.Cart", 42, "total", null, FastLogpointService.State.ARMED, null,
				4096, 0, 0, 0, true, null, FastLogpointService.Kind.LOGPOINT, EventRequest.SUSPEND_EVENT_THREAD, false, null),
			new FastLogpointService.Info(5, "com.example.Cart", 50, "", "qty > 9", FastLogpointService.State.ARMED, null,
				256, 0, 0, 0, true, null, FastLogpointService.Kind.BREAKPOINT, EventRequest.SUSPEND_ALL, true,
				new FastLogpointService.BreakpointCounters(4, 1, 1, true)),
			new FastLogpointService.Info(6, "com.example.Cart", 60, "", "id == 0", FastLogpointService.State.PENDING, null,
				256, 0, 0, -1, true, null, FastLogpointService.Kind.BREAKPOINT, EventRequest.SUSPEND_EVENT_THREAD, false,
				new FastLogpointService.BreakpointCounters(-1, -1, -1, false)),
			new FastLogpointService.Info(8, "com.example.Order", 7, "", "x", FastLogpointService.State.RECOVERED, null,
				256, 0, 0, 0, true, provenance, FastLogpointService.Kind.BREAKPOINT, EventRequest.SUSPEND_EVENT_THREAD, true,
				new FastLogpointService.BreakpointCounters(2, 0, 0, true))));

		final String breakpoints = tools.jdwp_overview("fast_breakpoint", null, null);
		assertThat(breakpoints)
			.contains("Fast breakpoints (3)")
			.contains("#5  com.example.Cart:50  [ARMED, spent — re-arm to stop again — true=4 errors=1 stops=1]  "
				+ "cond=\"qty > 9\"  stops once, suspendPolicy=all")
			.contains("#6  com.example.Cart:60  [PENDING — arms on the next hit]  cond=\"id == 0\"  stops on every hit, "
				+ "suspendPolicy=thread")
			.contains("#8  com.example.Order:7  [RECOVERED, evaluating, never stops — true=2 errors=0 stops=0; "
				+ "jdwp_clear_breakpoint disables, jdwp_restore_fast_logpoints restores (user approval)]  cond=\"x\"  "
				+ "[left by an earlier session: armed 2026-10-28T15:02:11Z by mcp-jdwp-java, session 3fa1c2d9, "
				+ "loader AppClassLoader#12]")
			.doesNotContain("Fast logpoints").doesNotContain("#3 ");
		assertThat(tools.jdwp_overview("fast_logpoint", null, null))
			.contains("Fast logpoints (1)").doesNotContain("Fast breakpoints");
		assertThat(tools.jdwp_overview("fast_breakpoint", "qty", null)).contains("Fast breakpoints (1)");
	}

	@Test
	@DisplayName("a fast breakpoint's stop names the fast breakpoint and why it stopped")
	void eventTagNamesTheFastStop() {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("worker-0");
		when(thread.isSuspended()).thenReturn(false);
		tracker.setLastBreakpointThread(thread, 7);
		when(fastLogpoints.stopReason(7)).thenReturn("condition failed: ArithmeticException: / by zero");

		assertThat(tools.jdwp_get_current_thread())
			.contains("fast_breakpoint=7, condition failed: ArithmeticException: / by zero");

		tracker.setLastBreakpointThread(thread, 8);
		assertThat(tools.jdwp_get_current_thread()).contains("breakpoint=8").doesNotContain("fast_breakpoint");
	}

	@Test
	@DisplayName("a step from a fast breakpoint's stop excludes every probe class before its count filter")
	void stepFromFastStopExcludesProbes() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final EventRequestManager erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(erm.stepRequests()).thenReturn(List.of());
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.name()).thenReturn("box-office-3");
		when(thread.isSuspended()).thenReturn(true);
		when(vm.allThreads()).thenReturn(List.of(thread));
		final StackFrame stop = frame("com.example.McpFastBreakpoint_aa", "stop", 1);
		final StackFrame hit = frame("com.example.McpFastBreakpoint_aa", "hit", 1);
		final StackFrame user = frame("com.example.BoxOffice", "trySell", 54);
		when(thread.frameCount()).thenReturn(3);
		when(thread.frames(0, 3)).thenReturn(List.of(stop, hit, user));
		when(thread.frame(2)).thenReturn(user);
		when(fastLogpoints.probeClassNames()).thenReturn(List.of("com.example.McpFastLogpoint_bb"));
		final StepRequest step = mock(StepRequest.class);
		when(erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER)).thenReturn(step);
		// The fast stop, published and shown, holds the thread (issue #45).
		final SuspensionSession session = tracker.openSuspensionSession(new EventHistory());
		final EventSet stopSet = mock(EventSet.class);
		when(stopSet.suspendPolicy()).thenReturn(EventRequest.SUSPEND_ALL);
		when(stopSet.iterator()).thenAnswer(invocation -> java.util.Collections.emptyIterator());
		final SuspensionSession.Context context = session.route(stopSet, thread, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(thread, 5));
		session.finish(context);
		session.nextStop(0);

		assertThat(tools.jdwp_step_over(1L))
			.contains("from com.example.BoxOffice:54")
			.contains("first lands on the stop line");

		final InOrder order = inOrder(step, thread);
		order.verify(step).addClassExclusionFilter("com.example.McpFastBreakpoint_aa");
		order.verify(step).addCountFilter(1);
		order.verify(step).enable();
		order.verify(thread).resume();
		verify(step).addClassExclusionFilter("com.example.McpFastLogpoint_bb");
	}

	private static StackFrame frame(String className, String methodName, int line) {
		final com.sun.jdi.ReferenceType type = mock(com.sun.jdi.ReferenceType.class);
		when(type.name()).thenReturn(className);
		final com.sun.jdi.Method method = mock(com.sun.jdi.Method.class);
		when(method.name()).thenReturn(methodName);
		final com.sun.jdi.Location location = mock(com.sun.jdi.Location.class);
		when(location.declaringType()).thenReturn(type);
		when(location.method()).thenReturn(method);
		when(location.lineNumber()).thenReturn(line);
		final StackFrame frame = mock(StackFrame.class);
		when(frame.location()).thenReturn(location);
		return frame;
	}

	@Test
	@DisplayName("jdwp_resume releases only the stops a tool has shown, never a thread nobody holds")
	void resumeReleasesOnlySeenStops() throws Exception {
		when(jdiService.getVM()).thenReturn(mock(VirtualMachine.class));
		final SuspensionSession session = tracker.openSuspensionSession(new EventHistory());
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread seen = model.thread("seen");
		final FakeSuspendModel.FakeThread unseen = model.thread("unseen");
		for (FakeSuspendModel.FakeThread thread : List.of(seen, unseen)) {
			final SuspensionSession.Context context = session.route(
				model.breakpointEvent(thread, EventRequest.SUSPEND_EVENT_THREAD), thread.ref, false);
			session.begin(context);
			session.candidate(context, new BreakpointTracker.LastBreakpoint(thread.ref, thread == seen ? 1 : 2));
			session.finish(context);
		}
		assertThat(tools.jdwp_get_current_thread()).contains("breakpoint=1").contains("1 further stop(s) not yet seen");

		assertThat(tools.jdwp_resume())
			.contains("Resumed: breakpoint 1 on thread seen")
			.contains("Still held, not yet seen: breakpoint 2 on thread unseen");
		assertThat(seen.count).isZero();
		assertThat(unseen.count).isEqualTo(1);
		assertThat(tools.jdwp_resume()).startsWith("Nothing to release");
	}
}
