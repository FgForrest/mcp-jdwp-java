package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JDWPTools#jdwp_step_over}, {@link JDWPTools#jdwp_step_into} and
 * {@link JDWPTools#jdwp_step_out} (and their shared {@code doStep} helper): step-depth wiring,
 * explicit-thread vs last-breakpoint-thread fallback, the not-suspended and unknown-thread
 * guards, the synchronised delete-then-create of a pre-existing StepRequest, the
 * {@code resume_until_event} hint in the return message, and the create &rarr; enable &rarr;
 * resume ordering.
 */
@DisplayName("jdwp_step_over / _into / _out")
class JDWPToolsStepTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private JDWPTools tools;
	private VirtualMachine vm;
	private EventRequestManager erm;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = new BreakpointTracker();
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		erm = mock(EventRequestManager.class);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(erm.stepRequests()).thenReturn(List.of());
		session = breakpointTracker.openSuspensionSession(eventHistory);
	}

	private SuspensionSession session;
	/**
	 * The event set of each thread's held stop (see {@link #suspendedThread}).
	 */
	private final Map<ThreadReference, EventSet> stops = new HashMap<>();

	/**
	 * Holds a stop of {@code thread} that a tool has shown, as a breakpoint hit followed by
	 * {@code jdwp_resume_until_event} leaves it: a step releases its thread through that stop.
	 */
	private void holdStop(ThreadReference thread, int breakpointId) throws Exception {
		holdStop(thread, breakpointId, EventRequest.SUSPEND_EVENT_THREAD, true);
	}

	private void holdStop(ThreadReference thread, int breakpointId, int policy, boolean present) throws Exception {
		final EventSet set = mock(EventSet.class);
		when(set.suspendPolicy()).thenReturn(policy);
		when(set.iterator()).thenAnswer(invocation -> Collections.emptyIterator());
		final SuspensionSession.Context context = session.route(set, thread, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(thread, breakpointId));
		session.finish(context);
		if (present) {
			session.nextStop(0);
		}
		stops.put(thread, set);
	}

	private ThreadReference suspendedThread(long id) {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(id);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.name()).thenReturn("thread-" + id);
		try {
			holdStop(thread, (int) id);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return thread;
	}

	private StepRequest stubStepRequestCreation(ThreadReference thread, int stepDepth) {
		final StepRequest req = mock(StepRequest.class);
		when(erm.createStepRequest(thread, StepRequest.STEP_LINE, stepDepth)).thenReturn(req);
		return req;
	}

	@Test
	@DisplayName("Step reports the remaining holders, including unseen stops and manual holds")
	void shouldReportRemainingHolders() throws Exception {
		final ThreadReference stepping = suspendedThread(1L);
		final ThreadReference seen = suspendedThread(2L);
		final ThreadReference unseen = mock(ThreadReference.class);
		when(unseen.uniqueID()).thenReturn(3L);
		when(unseen.name()).thenReturn("unseen-worker");
		holdStop(unseen, 3, EventRequest.SUSPEND_EVENT_THREAD, false);
		final ThreadReference manual = mock(ThreadReference.class);
		when(manual.uniqueID()).thenReturn(4L);
		when(manual.name()).thenReturn("manual-worker");
		session.suspendThread(manual);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(stepping, seen, unseen, manual));
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		assertThat(tools.jdwp_step_over(1L)).contains("released", "presented thread stops: thread-2",
			"unseen thread stops: unseen-worker", "jdwp_suspend_thread holds: manual-worker",
			"All remaining threads run.");
	}

	@Test
	@DisplayName("Step from stop-all shows the remaining threads with a capped list")
	void shouldCapStopAllThreadNames() throws Exception {
		final List<ThreadReference> threads = new java.util.ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			final ThreadReference thread = mock(ThreadReference.class);
			when(thread.uniqueID()).thenReturn((long) i);
			when(thread.name()).thenReturn("worker-" + i);
			threads.add(thread);
		}
		final ThreadReference stepping = threads.get(0);
		when(stepping.isSuspended()).thenReturn(true);
		holdStop(stepping, 1, EventRequest.SUSPEND_ALL, true);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(threads);
		stubStepRequestCreation(stepping, StepRequest.STEP_OVER);

		assertThat(tools.jdwp_step_over(1L)).contains("presented stop-all stops: worker-2", "and 2 more")
			.doesNotContain("worker-7", "worker-8");
	}

	@Nested
	@DisplayName("Step depth wiring")
	class StepDepthWiring {

		@Test
		@DisplayName("jdwp_step_over creates a STEP_LINE/STEP_OVER request, count-filtered and enabled")
		void shouldCreateStepOverRequestWithStepLineAndStepOver() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			tools.jdwp_step_over(1L);

			verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
			verify(req).addCountFilter(1);
			verify(req).enable();
		}

		@Test
		@DisplayName("jdwp_step_into creates a STEP_LINE/STEP_INTO request, count-filtered and enabled")
		void shouldCreateStepIntoRequestWithStepLineAndStepInto() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_INTO);

			tools.jdwp_step_into(1L);

			verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
			verify(req).addCountFilter(1);
			verify(req).enable();
		}

		@Test
		@DisplayName("jdwp_step_out creates a STEP_LINE/STEP_OUT request, count-filtered and enabled")
		void shouldCreateStepOutRequestWithStepLineAndStepOut() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_OUT);

			tools.jdwp_step_out(1L);

			verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OUT);
			verify(req).addCountFilter(1);
			verify(req).enable();
		}
	}

	@Nested
	@DisplayName("Thread resolution")
	class ThreadResolution {

		@Test
		@DisplayName("uses the explicit threadId when supplied (resolved via vm.allThreads())")
		void shouldUseExplicitThreadIdWhenSupplied() throws Exception {
			final ThreadReference thread = suspendedThread(42L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			final String out = tools.jdwp_step_over(42L);

			final ArgumentCaptor<ThreadReference> threadCaptor = ArgumentCaptor.forClass(ThreadReference.class);
			verify(erm).createStepRequest(threadCaptor.capture(), anyInt(), anyInt());
			assertThat(threadCaptor.getValue()).isSameAs(thread);
			assertThat(out).contains("42").contains("thread-42")
				.contains("Call jdwp_resume_until_event to wait for the STEP event")
				.contains("All other threads run.");
		}

		@Test
		@DisplayName("falls back to the last breakpoint thread when threadId is null")
		void shouldFallBackToLastBreakpointThreadWhenThreadIdIsNull() throws Exception {
			final ThreadReference thread = suspendedThread(7L);
			breakpointTracker.setLastBreakpointThread(thread, -1);
			when(jdiService.getVM()).thenReturn(vm);
			stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			final String out = tools.jdwp_step_over(null);

			verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
			assertThat(out).contains("thread 7").contains("thread-7");
		}

		@Test
		@DisplayName("returns 'Thread not found with ID 99' when supplied threadId is unknown")
		void shouldReturnErrorWhenThreadIdSuppliedButNotFound() throws Exception {
			final ThreadReference other = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(other));

			final String out = tools.jdwp_step_over(99L);

			assertThat(out).isEqualTo("Error: Thread not found with ID 99");
			verify(erm, never()).createStepRequest(any(), anyInt(), anyInt());
		}

		@Test
		@DisplayName("returns 'No current stop' when threadId is null and nothing is stopped")
		void shouldReturnErrorWhenNullThreadIdAndNoLastBreakpointThread() throws Exception {
			when(jdiService.getVM()).thenReturn(vm);

			final String out = tools.jdwp_step_over(null);

			assertThat(out).isEqualTo("Error: No current stop. Provide a threadId, or hit a breakpoint first.");
			verify(erm, never()).createStepRequest(any(), anyInt(), anyInt());
		}
	}

	@Nested
	@DisplayName("Suspended-state guard")
	class SuspendedStateGuard {

		@Test
		@DisplayName("rejects with 'Thread is not suspended. Cannot step.' when resolved thread is running")
		void shouldRejectWhenResolvedThreadIsNotSuspended() throws Exception {
			final ThreadReference thread = mock(ThreadReference.class);
			when(thread.uniqueID()).thenReturn(1L);
			when(thread.isSuspended()).thenReturn(false);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));

			final String out = tools.jdwp_step_over(1L);

			assertThat(out).isEqualTo("Error: Thread is not suspended. Cannot step.");
			verify(erm, never()).createStepRequest(any(), anyInt(), anyInt());
			verify(thread, never()).resume();
		}
	}

	@Nested
	@DisplayName("Pre-existing StepRequest cleanup")
	class PreExistingStepRequestCleanup {

		@Test
		@DisplayName("deletes the pre-existing StepRequest for the same thread before creating a new one")
		void shouldDeletePreExistingStepRequestForSameThreadBeforeCreatingNew() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));

			final StepRequest stale = mock(StepRequest.class);
			when(stale.thread()).thenReturn(thread);
			when(erm.stepRequests()).thenReturn(List.of(stale));
			stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			tools.jdwp_step_over(1L);

			final InOrder order = inOrder(erm);
			order.verify(erm).deleteEventRequest(stale);
			order.verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
		}

		@Test
		@DisplayName("does not delete StepRequests belonging to other threads")
		void shouldNotDeleteStepRequestsBelongingToOtherThreads() throws Exception {
			final ThreadReference target = suspendedThread(1L);
			final ThreadReference otherThread = suspendedThread(2L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(target, otherThread));

			final StepRequest foreign = mock(StepRequest.class);
			when(foreign.thread()).thenReturn(otherThread);
			when(erm.stepRequests()).thenReturn(List.of(foreign));
			stubStepRequestCreation(target, StepRequest.STEP_OVER);

			tools.jdwp_step_over(1L);

			verify(erm, never()).deleteEventRequest(foreign);
			verify(erm).createStepRequest(target, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
		}
	}

	@Nested
	@DisplayName("Return message")
	class ReturnMessage {

		@Test
		@DisplayName("includes the 'Call jdwp_resume_until_event to wait for the STEP event.' hint")
		void shouldIncludeResumeUntilEventHintInReturnMessage() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			final String out = tools.jdwp_step_over(1L);

			assertThat(out).contains("Call jdwp_resume_until_event to wait for the STEP event");
		}

		@Test
		@DisplayName("includes the action label, thread uniqueID and thread name")
		void shouldIncludeThreadIdAndNameInReturnMessage() throws Exception {
			final ThreadReference thread = suspendedThread(13L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			stubStepRequestCreation(thread, StepRequest.STEP_INTO);

			final String out = tools.jdwp_step_into(13L);

			assertThat(out).contains("into").contains("13").contains("thread-13");
		}
	}

	@Nested
	@DisplayName("Resume")
	class Resume {

		/**
		 * The step request must be enabled BEFORE the thread is resumed. Enabling after resume
		 * would race with the thread's first line transition and the step could be lost.
		 */
		@Test
		@DisplayName("creates → enables the StepRequest, then releases the thread's stop (in that order)")
		void shouldResumeThreadAfterCreatingStepRequest() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			final StepRequest req = stubStepRequestCreation(thread, StepRequest.STEP_OVER);

			tools.jdwp_step_over(1L);

			final EventSet stop = stops.get(thread);
			final InOrder order = inOrder(erm, req, stop);
			order.verify(erm).createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
			order.verify(req).setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
			order.verify(req).enable();
			order.verify(stop).resume();
		}
	}

	@Nested
	@DisplayName("Error envelope")
	class ErrorEnvelope {

		@Test
		@DisplayName("wraps unexpected JDI failures as 'Error: <message>'")
		void shouldWrapJdiExceptionsInErrorPrefix() throws Exception {
			when(jdiService.getVM()).thenThrow(new RuntimeException("boom"));

			final String out = tools.jdwp_step_over(1L);

			assertThat(out).startsWith("Error: boom");
		}
	}
}
