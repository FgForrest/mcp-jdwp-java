package one.edee.mcp.jdwp;

import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The frame tools ({@code jdwp_get_stack}, {@code jdwp_get_locals}, {@code jdwp_set_local},
 * {@code jdwp_get_breakpoint_context}) under the suspension ownership of issue #45:
 * <ul>
 *   <li>JDI invalidates every frame of a thread on any resume of it — another stop's release, the
 *       arming's VM-wide suspend — though the thread stays suspended; a tool that read such frames
 *       reads them afresh (amendments A9 and A12, review finding 4) — but only while a hold the
 *       session knows kept the thread at its stop: without one it may have run on and stopped
 *       elsewhere, and a fresh frame would be another activation.</li>
 *   <li>A thread an evaluation runs on is refused before anything is read from it: its frames are
 *       the invoked code's then, and it is running, not "not suspended".</li>
 * </ul>
 */
@DisplayName("frame tools — fresh frames after an invalidation, refusal during an evaluation")
class JDWPToolsFrameToolsFreshFramesTest {

	private static final long THREAD_ID = 11L;
	private static final long STOP = 5L;

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private SuspensionSession session;
	private EvaluationGuard guard;
	private JDWPTools tools;
	private VirtualMachine vm;
	private ThreadReference thread;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		guard = new EvaluationGuard();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, mock(WatcherManager.class), mock(JdiExpressionEvaluator.class),
			new EventHistory(), guard, new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(THREAD_ID);
		when(thread.name()).thenReturn("worker-0");
		when(thread.isSuspended()).thenReturn(true);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, 1, BreakpointTracker.EventKind.BREAKPOINT));
		session = mock(SuspensionSession.class);
		when(breakpointTracker.suspensionSession()).thenReturn(session);
		// The stop holding the thread lasts unless a test says otherwise.
		when(session.userHoldsOn(thread)).thenReturn(Set.of(STOP));
	}

	private static StackFrame staleFrame() {
		final StackFrame stale = mock(StackFrame.class);
		final InvalidStackFrameException resumed = new InvalidStackFrameException("Thread has been resumed");
		when(stale.location()).thenThrow(resumed);
		when(stale.thisObject()).thenThrow(resumed);
		return stale;
	}

	private static StackFrame frameAt(String className, String methodName, int line) throws Exception {
		final StackFrame frame = mock(StackFrame.class);
		final Location location = mock(Location.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method method = mock(Method.class);
		when(type.name()).thenReturn(className);
		when(method.name()).thenReturn(methodName);
		when(location.declaringType()).thenReturn(type);
		when(location.method()).thenReturn(method);
		when(location.sourceName()).thenReturn(className.substring(className.lastIndexOf('.') + 1) + ".java");
		when(location.lineNumber()).thenReturn(line);
		when(frame.location()).thenReturn(location);
		when(frame.getValues(any())).thenReturn(Map.of());
		return frame;
	}

	@Nested
	@DisplayName("a resume elsewhere invalidated the frames read")
	class InvalidatedFrames {

		@Test
		@DisplayName("jdwp_get_stack renders the frames it reads afresh")
		void stackIsReadAfresh() throws Exception {
			final StackFrame stale = staleFrame();
			final StackFrame fresh = frameAt("com.example.Orders", "place", 42);
			when(thread.frames()).thenReturn(List.of(stale), List.of(fresh));

			final String result = tools.jdwp_get_stack(THREAD_ID, null, null);

			assertThat(result).contains("com.example.Orders.place (Orders.java:42)").doesNotContain("resumed");
			verify(thread, times(2)).frames();
		}

		@Test
		@DisplayName("jdwp_get_locals renders the frame it reads afresh")
		void localsAreReadAfresh() throws Exception {
			final StackFrame fresh = frameAt("com.example.Orders", "place", 42);
			final LocalVariable count = mock(LocalVariable.class);
			final IntegerValue seven = mock(IntegerValue.class);
			when(count.name()).thenReturn("count");
			when(count.typeName()).thenReturn("int");
			when(seven.value()).thenReturn(7);
			when(fresh.getValues(any())).thenReturn(Map.of(count, seven));
			final StackFrame stale = staleFrame();
			when(thread.frame(0)).thenReturn(stale, fresh);
			// Another thread's stop-all (9) was released — the resume that invalidated the frames — while
			// the thread's own stop lasted.
			when(session.userHoldsOn(thread)).thenReturn(Set.of(STOP, 9L), Set.of(STOP));

			final String result = tools.jdwp_get_locals(THREAD_ID, 0);

			assertThat(result).contains("count (int)").doesNotContain("resumed");
			verify(thread, times(2)).frame(0);
		}

		@Test
		@DisplayName("jdwp_set_local writes once, through the frame it reads afresh")
		void localIsSetThroughAFreshFrame() throws Exception {
			final LocalVariable count = mock(LocalVariable.class);
			final Type intType = mock(Type.class);
			final IntegerValue fortyTwo = mock(IntegerValue.class);
			when(count.typeName()).thenReturn("int");
			when(count.type()).thenReturn(intType);
			when(intType.name()).thenReturn("int");
			when(vm.mirrorOf(42)).thenReturn(fortyTwo);
			final StackFrame stale = mock(StackFrame.class);
			final StackFrame fresh = mock(StackFrame.class);
			when(stale.visibleVariableByName("count")).thenReturn(count);
			when(fresh.visibleVariableByName("count")).thenReturn(count);
			doThrow(new InvalidStackFrameException("Thread has been resumed")).when(stale).setValue(count, fortyTwo);
			when(thread.frame(0)).thenReturn(stale, fresh);

			final String result = tools.jdwp_set_local(THREAD_ID, 0, "count", "42");

			assertThat(result).startsWith("Variable 'count' set to 42");
			verify(stale).setValue(count, fortyTwo);
			verify(fresh).setValue(count, fortyTwo);
		}

		@Test
		@DisplayName("jdwp_get_breakpoint_context renders the frames it reads afresh")
		void contextIsReadAfresh() throws Exception {
			final StackFrame stale = staleFrame();
			final StackFrame fresh = frameAt("com.example.Orders", "place", 42);
			when(thread.frames()).thenReturn(List.of(stale), List.of(fresh));

			final String result = tools.jdwp_get_breakpoint_context(null, false);

			assertThat(result).contains("com.example.Orders.place (Orders.java:42)").contains("--- Locals at frame 0 ---")
				.doesNotContain("resumed");
			verify(thread, times(2)).frames();
		}

		@Test
		@DisplayName("frames invalidated on every attempt end in an error after three reads")
		void givesUpAfterThreeAttempts() throws Exception {
			when(thread.frame(0)).thenAnswer(invocation -> staleFrame());

			final String result = tools.jdwp_get_locals(THREAD_ID, 0);

			assertThat(result).startsWith("Error:").contains("Thread has been resumed");
			verify(thread, times(3)).frame(0);
		}
	}

	@Nested
	@DisplayName("no hold the session knows lasted across the attempt")
	class MayHaveRunOn {

		@Test
		@DisplayName("jdwp_set_local writes nothing into a frame that may be another activation, and says so")
		void setLocalWritesNothing() throws Exception {
			// The stop that held the thread was released; it ran on and stopped again, under stop 7.
			when(session.userHoldsOn(thread)).thenReturn(Set.of(STOP), Set.of(7L));
			final LocalVariable count = mock(LocalVariable.class);
			final Type intType = mock(Type.class);
			final IntegerValue fortyTwo = mock(IntegerValue.class);
			when(count.typeName()).thenReturn("int");
			when(count.type()).thenReturn(intType);
			when(intType.name()).thenReturn("int");
			when(vm.mirrorOf(42)).thenReturn(fortyTwo);
			final StackFrame stale = mock(StackFrame.class);
			final StackFrame elsewhere = mock(StackFrame.class);
			when(stale.visibleVariableByName("count")).thenReturn(count);
			when(elsewhere.visibleVariableByName("count")).thenReturn(count);
			doThrow(new InvalidStackFrameException("Thread has been resumed")).when(stale).setValue(count, fortyTwo);
			when(thread.frame(0)).thenReturn(stale, elsewhere);

			final String result = tools.jdwp_set_local(THREAD_ID, 0, "count", "42");

			assertThat(result).startsWith("Error: thread worker-0 was resumed").contains("nothing was written");
			verify(thread, times(1)).frame(0);
			verify(elsewhere, never()).setValue(any(), any());
		}

		@Test
		@DisplayName("a read tool renders no frames that may be another activation")
		void readToolAsksToCallAgain() throws Exception {
			when(session.userHoldsOn(thread)).thenReturn(Set.of(STOP), Set.of());
			final StackFrame stale = staleFrame();
			final StackFrame elsewhere = frameAt("com.example.Elsewhere", "run", 7);
			when(thread.frames()).thenReturn(List.of(stale), List.of(elsewhere));

			final String result = tools.jdwp_get_stack(THREAD_ID, null, null);

			assertThat(result).startsWith("Error: thread worker-0 was resumed").endsWith("call again.")
				.doesNotContain("Elsewhere");
			verify(thread, times(1)).frames();
		}

		@Test
		@DisplayName("without a session nothing proves the thread stayed: one read, and its error")
		void withoutASessionNoRetry() throws Exception {
			when(breakpointTracker.suspensionSession()).thenReturn(null);
			final StackFrame stale = staleFrame();
			when(thread.frame(0)).thenReturn(stale);

			final String result = tools.jdwp_get_locals(THREAD_ID, 0);

			assertThat(result).startsWith("Error:").contains("Thread has been resumed");
			verify(thread, times(1)).frame(0);
		}
	}

	@Nested
	@DisplayName("an evaluation runs on the thread")
	class EvaluationRunning {

		@BeforeEach
		void evaluating() {
			// While its invocation runs, the thread is not suspended.
			when(thread.isSuspended()).thenReturn(false);
			guard.enter(THREAD_ID);
		}

		@Test
		@DisplayName("every frame tool refuses it before reading a frame, and names the evaluation, not a missing suspension")
		void frameToolsRefuse() throws Exception {
			final List<String> replies = List.of(
				tools.jdwp_get_stack(THREAD_ID, null, null),
				tools.jdwp_get_locals(THREAD_ID, 0),
				tools.jdwp_set_local(THREAD_ID, 0, "count", "42"),
				tools.jdwp_get_breakpoint_context(null, null));

			assertThat(replies).allSatisfy(reply -> assertThat(reply)
				.startsWith("Error: an evaluation is running on thread worker-0")
				.doesNotContain("not suspended"));
			verify(thread, never()).frames();
			verify(thread, never()).frame(anyInt());
			verify(thread, never()).frameCount();
		}

		@Test
		@DisplayName("the thread can be inspected again once the evaluation has ended")
		void inspectableAfterTheEvaluation() throws Exception {
			guard.exit(THREAD_ID);
			when(thread.isSuspended()).thenReturn(true);
			final StackFrame frame = frameAt("com.example.Orders", "place", 42);
			when(thread.frames()).thenReturn(List.of(frame));

			assertThat(tools.jdwp_get_stack(THREAD_ID, null, null)).contains("com.example.Orders.place (Orders.java:42)");
		}
	}
}
