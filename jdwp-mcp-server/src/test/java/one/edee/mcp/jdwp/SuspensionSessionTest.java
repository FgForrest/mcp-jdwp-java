package one.edee.mcp.jdwp;

import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SuspensionSession} over {@link FakeSuspendModel}: every scenario ends by asserting the
 * suspend counts the target would really have, not which calls were made.
 */
@DisplayName("SuspensionSession — who releases which suspension (#45)")
class SuspensionSessionTest {

	private FakeSuspendModel model;
	private FakeSuspendModel.FakeThread t;
	private FakeSuspendModel.FakeThread u;
	private FakeSuspendModel.FakeThread w;
	private BreakpointTracker tracker;
	private EventHistory history;
	private SuspensionSession session;

	@BeforeEach
	void setUp() {
		model = new FakeSuspendModel();
		t = model.thread("t");
		u = model.thread("u");
		w = model.thread("w");
		tracker = new BreakpointTracker();
		history = new EventHistory();
		session = tracker.openSuspensionSession(history);
	}

	@Test
	@DisplayName("a stop is held until presented and released; the release nets every count back to zero")
	void stopAllIsReleasedAfterPresentation() throws Exception {
		final SuspensionSession.Context stop = stopOn(t, EventRequest.SUSPEND_ALL, 1);
		assertThat(model.counts()).containsEntry("t", 1).containsEntry("u", 1).containsEntry("w", 1);

		final SuspensionSession.ReleaseResult early = session.releaseAll();
		assertThat(early.unseen()).as("not presented yet").hasSize(1);
		assertThat(model.counts()).containsEntry("t", 1).containsEntry("u", 1);

		final SuspensionSession.NextStop next = session.nextStop(0);
		assertThat(next.outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
		assertThat(next.released()).as("a stop was waiting to be seen: nothing released").isNull();
		assertThat(tracker.getLastBreakpointId()).isEqualTo(1);

		assertThat(session.releaseAll().released()).hasSize(1);
		assertThat(model.counts()).containsOnlyKeys("t", "u", "w").allSatisfy((name, count) -> assertThat(count).isZero());
		assertThat(session.stateOf(stop)).isEqualTo(SuspensionSession.State.RELEASED);
	}

	@Test
	@DisplayName("a queued set keeps its threads parked through jdwp_resume; it is released only by its own processing")
	void queuedSetIsUntouchable() throws Exception {
		// Review 3's W case: the user holds a thread-policy stop on W, a stop-all set of T is queued.
		final SuspensionSession.Context held = stopOn(w, EventRequest.SUSPEND_EVENT_THREAD, 1);
		session.presentCurrent();
		final EventSet queuedSet = model.breakpointEvent(t, EventRequest.SUSPEND_ALL);
		final SuspensionSession.Context queued = session.route(queuedSet, t.ref, false);
		assertThat(model.counts()).containsEntry("w", 2).containsEntry("t", 1);

		final SuspensionSession.ReleaseResult result = session.releaseAll();
		assertThat(result.released()).hasSize(1);
		assertThat(result.pending()).isEqualTo(1);
		assertThat(model.counts()).as("only the held stop was released")
			.containsEntry("w", 1).containsEntry("t", 1).containsEntry("u", 1);

		session.begin(queued);
		session.finish(queued);
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).isZero());
		assertThat(session.stateOf(held)).isEqualTo(SuspensionSession.State.RELEASED);
	}

	@Test
	@DisplayName("releasing one thread out of a stop-all, then everything, nets zero; current is invalidated")
	void releaseThreadThenAllNetsZero() throws Exception {
		stopOn(t, EventRequest.SUSPEND_ALL, 1);
		session.presentCurrent();

		final SuspensionSession.ThreadRelease release = session.releaseThread(t.ref);
		assertThat(release.released()).isTrue();
		assertThat(model.counts()).containsEntry("t", 0).containsEntry("u", 1);
		assertThat(session.presentCurrent()).as("its event thread runs: no current stop").isNull();

		session.releaseAll();
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).isZero());
	}

	@Test
	@DisplayName("a step chain from a stop-all: one jdwp_resume afterwards resumes every thread")
	void stepChainFromStopAll() throws Exception {
		stopOn(t, EventRequest.SUSPEND_ALL, 1);
		session.presentCurrent();
		for (int step = 1; step <= 3; step++) {
			final StepRequest request = stepRequest();
			assertThat(session.step(t.ref, () -> request)).isNull();
			assertThat(session.stepPending(t.ref)).isTrue();
			// The step lands: thread policy.
			final SuspensionSession.Context landing = session.route(
				model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD), t.ref, false);
			session.begin(landing);
			session.candidate(landing, new BreakpointTracker.LastBreakpoint(t.ref, 1, BreakpointTracker.EventKind.STEP));
			session.finish(landing);
			assertThat(session.stepPending(t.ref)).isFalse();
			assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
		}
		assertThat(model.counts()).containsEntry("u", 1).containsEntry("w", 1).containsEntry("t", 1);

		session.releaseAll();
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).isZero());
	}

	@Test
	@DisplayName("a set with two stopping events publishes one stop, after processing")
	void compositeSetPublishesOnce() {
		final SuspensionSession.Context context = session.route(
			model.breakpointEvent(t, EventRequest.SUSPEND_ALL), t.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(t.ref, 1));
		assertThat(session.unseenCount()).as("not published mid-set").isZero();
		session.candidate(context, new BreakpointTracker.LastBreakpoint(t.ref, 2));
		assertThat(session.finish(context)).isTrue();

		assertThat(session.unseenCount()).isEqualTo(1);
		assertThat(session.presentCurrent()).isNotNull().satisfies(stop -> assertThat(stop.id()).isEqualTo(1));
	}

	@Test
	@DisplayName("jdwp_reset during processing: the set can no longer publish and is released")
	void resetCancelsUnfinishedPublication() {
		final SuspensionSession.Context context = session.route(
			model.breakpointEvent(t, EventRequest.SUSPEND_ALL), t.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(t.ref, 1));
		session.record(context, new EventHistory.DebugEvent("BREAKPOINT", "hit"));

		session.reset();
		assertThat(session.finish(context)).isFalse();
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).isZero());
		assertThat(history.getRecent(10)).as("the stale history is dropped").isEmpty();
	}

	@Test
	@DisplayName("an event of a request created before jdwp_reset, routed after it, does not publish")
	void staleRequestDoesNotPublish() {
		final Map<Object, Object> properties = new HashMap<>();
		final BreakpointRequest request = mock(BreakpointRequest.class);
		doAnswer(invocation -> properties.put(invocation.getArgument(0), invocation.getArgument(1)))
			.when(request).putProperty(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		when(request.getProperty(org.mockito.ArgumentMatchers.any()))
			.thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
		session.stamp(request);
		final EventSet set = model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD, request);

		session.reset();
		final SuspensionSession.Context context = session.route(set, t.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(t.ref, 1));

		assertThat(session.finish(context)).isFalse();
		assertThat(t.count).isZero();
	}

	@Test
	@DisplayName("a set that suspends nothing never becomes a hold")
	void noneNeverHolds() {
		final SuspensionSession.Context context = session.route(
			model.breakpointEvent(t, EventRequest.SUSPEND_NONE), t.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(t.ref, 1));

		assertThat(session.finish(context)).isFalse();
		assertThat(session.unseenCount()).isZero();
		assertThat(context.invocable()).isFalse();
	}

	@Test
	@DisplayName("a manual hold does not stack and is released by jdwp_resume")
	void manualHold() {
		assertThat(session.suspendThread(u.ref)).isNull();
		assertThat(session.suspendThread(u.ref)).contains("already suspended");
		assertThat(u.count).isEqualTo(1);

		assertThat(session.releaseAll().manual()).containsExactly(u.ref);
		assertThat(u.count).isZero();
		assertThat(session.releaseThread(u.ref).refusal()).contains("nothing holds");
	}

	@Test
	@DisplayName("jdwp_resume_thread refuses while a covering set is queued, processed, or not yet seen")
	void releaseThreadRefusals() {
		final SuspensionSession.Context queued = session.route(
			model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD), t.ref, false);
		assertThat(session.releaseThread(t.ref).refusal()).contains("waiting to be processed");
		session.begin(queued);
		assertThat(session.releaseThread(t.ref).refusal()).contains("being processed");
		session.candidate(queued, new BreakpointTracker.LastBreakpoint(t.ref, 1));
		session.finish(queued);
		assertThat(session.releaseThread(t.ref).refusal()).contains("not seen yet");
		assertThat(t.count).isEqualTo(1);
	}

	@Test
	@DisplayName("another stop on the stepping thread interrupts the step and deletes its request")
	void stepInterruptedByBreakpoint() throws Exception {
		stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
		session.presentCurrent();
		final StepRequest request = stepRequest();
		final EventRequestManager erm = mock(EventRequestManager.class);
		when(model.vm.eventRequestManager()).thenReturn(erm);
		assertThat(session.step(t.ref, () -> request)).isNull();

		stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 2);

		assertThat(session.stepPending(t.ref)).isFalse();
		verify(erm).deleteEventRequest(request);
		assertThat(history.getRecent(10)).anyMatch(e -> "STEP_INTERRUPTED".equals(e.type()));
	}

	@Test
	@DisplayName("a waiting jdwp_resume_until_event wakes for a stop published by the dispatcher")
	void nextStopWaitsForPublication() throws Exception {
		final CompletableFuture<SuspensionSession.NextStop> waiter = CompletableFuture.supplyAsync(() -> {
			try {
				return session.nextStop(10_000);
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		});
		Thread.sleep(100);
		assertThat(waiter).isNotDone();
		stopOn(u, EventRequest.SUSPEND_EVENT_THREAD, 7);

		final SuspensionSession.NextStop next = waiter.get(5, TimeUnit.SECONDS);
		assertThat(next.outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
		assertThat(next.stop()).isNotNull().satisfies(stop -> assertThat(stop.id()).isEqualTo(7));
	}

	@Test
	@DisplayName("a closed session ignores a late finish and wakes its waiters")
	void closeIsTerminal() throws Exception {
		final SuspensionSession.Context context = session.route(
			model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD), t.ref, false);
		session.begin(context);
		session.close();

		session.candidate(context, new BreakpointTracker.LastBreakpoint(t.ref, 1));
		assertThat(session.finish(context)).isFalse();
		assertThat(session.nextStop(10_000).outcome()).isEqualTo(SuspensionSession.Outcome.CLOSED);
		assertThat(t.count).as("dispose releases it; the session does not").isEqualTo(1);
	}

	/**
	 * Routes, processes and publishes a breakpoint stop of {@code thread} with {@code policy}.
	 */
	private SuspensionSession.Context stopOn(FakeSuspendModel.FakeThread thread, int policy, int breakpointId) {
		final SuspensionSession.Context context = session.route(model.breakpointEvent(thread, policy), thread.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(thread.ref, breakpointId));
		session.finish(context);
		return context;
	}

	private StepRequest stepRequest() {
		final StepRequest request = mock(StepRequest.class);
		when(request.virtualMachine()).thenReturn(model.vm);
		return request;
	}
}
