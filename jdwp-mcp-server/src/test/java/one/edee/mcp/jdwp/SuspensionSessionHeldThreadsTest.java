package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which threads the redefinition rule (#42 proposal § 6) must protect: those the user may see
 * stopped. {@link SuspensionSession#heldThreads} names them with their holds, against the counts a
 * real target would have ({@link FakeSuspendModel}).
 */
@DisplayName("SuspensionSession.heldThreads — the threads the user may see stopped")
class SuspensionSessionHeldThreadsTest {

	private FakeSuspendModel model;
	private FakeSuspendModel.FakeThread t;
	private FakeSuspendModel.FakeThread u;
	private FakeSuspendModel.FakeThread w;
	private SuspensionSession session;

	@BeforeEach
	void setUp() {
		model = new FakeSuspendModel();
		t = model.thread("t");
		u = model.thread("u");
		w = model.thread("w");
		session = new BreakpointTracker().openSuspensionSession(new EventHistory());
	}

	@Test
	@DisplayName("a stop and a stop-all still queued hold their threads; the set being processed only when it stops")
	void stopsAndQueuedSetsHold() {
		final SuspensionSession.Context stop = stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
		assertThat(held(false, 0)).containsOnly(Map.entry(t.ref, Set.of(stop.id())));

		final SuspensionSession.Context queued = session.route(model.breakpointEvent(u, EventRequest.SUSPEND_ALL),
			u.ref, false);
		assertThat(held(false, 0)).as("a queued stop-all may still show every thread")
			.containsOnly(Map.entry(t.ref, Set.of(stop.id(), queued.id())), Map.entry(u.ref, Set.of(queued.id())),
				Map.entry(w.ref, Set.of(queued.id())));

		session.begin(queued);
		session.beginProcessing(queued);
		try {
			assertThat(held(false, 0)).as("an arming hit's own set resumes unshown").containsOnlyKeys(t.ref);
			assertThat(held(true, 0)).as("unless it has a stop").containsOnlyKeys(t.ref, u.ref, w.ref);
		} finally {
			SuspensionSession.endProcessing();
		}
	}

	@Test
	@DisplayName("a queued set that cannot stop (logpoints, say) holds nothing, and its suspension is no unknown hold")
	void setThatCannotStopHoldsNothing() {
		session.route(model.breakpointEvent(t, EventRequest.SUSPEND_ALL), t.ref, false, false);

		assertThat(held(false, 0)).isEmpty();
	}

	@Test
	@DisplayName("a set jdwp_reset left behind can no longer be shown, and its suspension is no unknown hold")
	void staleSetsHoldNothing() {
		session.route(model.breakpointEvent(t, EventRequest.SUSPEND_ALL), t.ref, false);
		session.reset();

		assertThat(held(false, 0)).isEmpty();
	}

	@Test
	@DisplayName("a jdwp_suspend_thread hold and a step in flight hold their threads")
	void manualHoldAndStepHold() throws Exception {
		assertThat(session.suspendThread(u.ref)).isNull();
		stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
		session.presentCurrent();
		final StepRequest step = mock(StepRequest.class);
		when(step.virtualMachine()).thenReturn(model.vm);
		assertThat(session.step(t.ref, () -> step)).isNull();

		assertThat(held(false, 0)).containsOnly(Map.entry(u.ref, Set.of(SuspensionSession.MANUAL_HOLD)),
			Map.entry(t.ref, Set.of(SuspensionSession.STEP_HOLD)));
	}

	@Test
	@DisplayName("a suspension no set accounts for is an unknown hold; the caller's own vm.suspend() is not")
	void unroutedSuspensionIsAnUnknownHold() {
		// The target raised the event, the listener has not routed it yet.
		model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD);
		assertThat(held(false, 0)).containsOnly(Map.entry(t.ref, Set.of(SuspensionSession.UNKNOWN_HOLD)));

		model.vm.suspend();
		try {
			assertThat(held(false, 1)).containsOnly(Map.entry(t.ref, Set.of(SuspensionSession.UNKNOWN_HOLD)));
		} finally {
			model.vm.resume();
		}
	}

	@Test
	@DisplayName("a leased thread keeps its stop's hold, and the share it keeps after a release is no unknown hold")
	void leasedThreadCountIsNotJudged() throws Exception {
		final SuspensionSession.Context stop = stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
		session.presentCurrent();
		session.lease(t.ref);
		model.beginInvocation(t);
		assertThat(held(false, 0)).as("the invocation returns to the stopped frames")
			.containsOnly(Map.entry(t.ref, Set.of(stop.id())));

		session.releaseAll();
		model.completeInvocation(t);
		assertThat(t.count).as("the released stop's share waits for the lease's end").isEqualTo(1);
		assertThat(held(false, 0)).isEmpty();
		session.unlease(t.ref);
		assertThat(t.count).isZero();
	}

	@Test
	@DisplayName("a closed session holds nothing")
	void closedSessionHoldsNothing() {
		stopOn(t, EventRequest.SUSPEND_ALL, 1);
		session.close();

		assertThat(held(true, 0)).isEmpty();
	}

	private Map<ThreadReference, Set<Long>> held(boolean countProcessing, int callerSuspends) {
		return session.heldThreads(model.vm.allThreads(), countProcessing, callerSuspends);
	}

	private SuspensionSession.Context stopOn(FakeSuspendModel.FakeThread thread, int policy, int breakpointId) {
		final SuspensionSession.Context context = session.route(model.breakpointEvent(thread, policy), thread.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(thread.ref, breakpointId));
		session.finish(context);
		return context;
	}
}
