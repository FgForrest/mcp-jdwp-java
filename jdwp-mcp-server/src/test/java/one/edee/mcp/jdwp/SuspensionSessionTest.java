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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
	// ── Leases (proposal §§ 6–7): every ordering ends with the lease's own context holding ──

	@Test
	@DisplayName("§ 7 row 1: a dispatcher invocation survives another thread's stop-all, which takes it back at lease end")
	void dispatcherInvocationSurvivesStopAll() throws Exception {
		final SuspensionSession.Context own = session.route(model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD),
			t.ref, false);
		session.begin(own);
		session.beginProcessing(own);
		try {
			session.lease(t.ref);
			model.beginInvocation(t);

			final SuspensionSession.Context other = session.route(model.breakpointEvent(u, EventRequest.SUSPEND_ALL),
				u.ref, false);
			assertThat(model.invocationRuns(t)).as("the stop-all no longer parks the invocation").isTrue();
			assertThat(model.counts()).containsEntry("u", 1).containsEntry("w", 1);

			model.completeInvocation(t);
			session.unlease(t.ref);
			assertThat(model.counts()).as("own set + the stop-all, which covers t again")
				.containsEntry("t", 2).containsEntry("u", 1).containsEntry("w", 1);
			assertThat(session.stateOf(other)).isEqualTo(SuspensionSession.State.QUEUED);
		} finally {
			SuspensionSession.endProcessing();
		}
		session.finish(own);
		assertThat(model.counts()).as("the stop-all alone").containsEntry("t", 1).containsEntry("u", 1);
	}

	@Test
	@DisplayName("§ 7 row 2: the stop-all is released mid-invocation; the lease's own stop alone remains")
	void stopAllReleasedBeforeTheInvocationRestores() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		session.lease(t.ref);
		model.beginInvocation(t);

		final SuspensionSession.Context other = session.route(model.breakpointEvent(u, EventRequest.SUSPEND_ALL),
			u.ref, false);
		session.begin(other);
		session.finish(other);
		assertThat(model.invocationRuns(t)).isTrue();

		model.completeInvocation(t);
		session.unlease(t.ref);
		assertThat(model.counts()).containsEntry("t", 1).containsEntry("u", 0).containsEntry("w", 0);
	}

	@Test
	@DisplayName("§ 7 row 3: the invocation restores, then the stop-all is released; the lease's own stop alone remains")
	void invocationRestoresBeforeTheStopAllIsReleased() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		session.lease(t.ref);
		model.beginInvocation(t);
		final SuspensionSession.Context other = session.route(model.breakpointEvent(u, EventRequest.SUSPEND_ALL),
			u.ref, false);

		model.completeInvocation(t);
		session.begin(other);
		session.finish(other);
		session.unlease(t.ref);

		assertThat(model.counts()).containsEntry("t", 1).containsEntry("u", 0).containsEntry("w", 0);
	}

	@Test
	@DisplayName("a lease takes the thread out of a stop-all already held, and gives it back at its end")
	void leaseExemptsFromAHeldStopAll() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		presented(stopOn(u, EventRequest.SUSPEND_ALL, 2));
		assertThat(model.counts()).containsEntry("t", 2);

		session.lease(t.ref);
		model.beginInvocation(t);
		assertThat(model.invocationRuns(t)).isTrue();
		model.completeInvocation(t);
		session.unlease(t.ref);
		assertThat(model.counts()).containsEntry("t", 2).containsEntry("u", 1).containsEntry("w", 1);

		session.releaseAll();
		assertThat(model.counts()).containsEntry("t", 0).containsEntry("u", 0).containsEntry("w", 0);
	}

	@Test
	@DisplayName("the holds known on a thread: its stops and a manual hold; a lease keeps them, a release ends them")
	void holdsOnNamesWhatKeepsTheThreadAtItsStop() throws Exception {
		final SuspensionSession.Context own = presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		final SuspensionSession.Context all = presented(stopOn(u, EventRequest.SUSPEND_ALL, 2));
		assertThat(session.holdsOn(t.ref)).containsExactly(own.id(), all.id());
		assertThat(session.holdsOn(w.ref)).containsExactly(all.id());

		session.lease(t.ref);
		model.beginInvocation(t);
		assertThat(session.holdsOn(t.ref)).as("the invocation returns to the same frames")
			.containsExactly(own.id(), all.id());
		model.completeInvocation(t);
		session.unlease(t.ref);

		assertThat(session.releaseThread(w.ref).released()).isTrue();
		assertThat(session.holdsOn(w.ref)).isEmpty();
		session.releaseAll();
		assertThat(session.holdsOn(t.ref)).isEmpty();

		assertThat(session.suspendThread(u.ref)).isNull();
		assertThat(session.holdsOn(u.ref)).containsExactly(SuspensionSession.MANUAL_HOLD);
		session.close();
		assertThat(session.holdsOn(u.ref)).isEmpty();
	}

	@Test
	@DisplayName("the dispatcher may invoke on the thread of the set it processes, though nobody has seen it")
	void dispatcherLeaseIsAdmittedByConstruction() {
		final SuspensionSession.Context own = session.route(model.breakpointEvent(t, EventRequest.SUSPEND_ALL),
			t.ref, false);
		session.begin(own);
		session.beginProcessing(own);
		try {
			session.lease(t.ref);
			session.unlease(t.ref);
		} finally {
			SuspensionSession.endProcessing();
		}
		assertThat(model.counts()).containsEntry("t", 1).containsEntry("u", 1);
	}

	@Test
	@DisplayName("an invocation is refused, with the reason, on a thread not stopped at a shown event of its own")
	void leaseIsRefusedWithAReason() throws Exception {
		assertThatThrownBy(() -> session.lease(w.ref)).isInstanceOf(EvaluationRefusedException.class)
			.hasMessageContaining("not stopped by this debugging session");

		stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
		assertThatThrownBy(() -> session.lease(t.ref)).hasMessageContaining("not seen yet");
		session.nextStop(0);

		session.suspendThread(w.ref);
		assertThatThrownBy(() -> session.lease(w.ref)).hasMessageContaining("jdwp_suspend_thread");

		presented(stopOn(u, EventRequest.SUSPEND_ALL, 2));
		final FakeSuspendModel.FakeThread bystander = model.thread("bystander");
		assertThat(bystander.count).as("born during the stop-all").isEqualTo(1);
		assertThatThrownBy(() -> session.lease(bystander.ref)).hasMessageContaining("parked by another thread's stop");

		final Map<String, Integer> before = model.counts();
		session.route(model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD), t.ref, false);
		assertThatThrownBy(() -> session.lease(t.ref)).hasMessageContaining("waiting to be processed");
		assertThat(model.counts()).as("a refusal suspends and resumes nothing")
			.containsEntry("u", before.get("u")).containsEntry("w", before.get("w"));
	}

	@Test
	@DisplayName("VM start cannot be invoked on, so a lease there is refused")
	void leaseAtVmStartIsRefused() throws Exception {
		final SuspensionSession.Context start = session.route(model.breakpointEvent(t, EventRequest.SUSPEND_ALL),
			t.ref, true);
		session.begin(start);
		session.candidate(start, new BreakpointTracker.LastBreakpoint(t.ref, null,
			BreakpointTracker.EventKind.VM_START));
		session.finish(start);
		session.nextStop(0);

		assertThatThrownBy(() -> session.lease(t.ref)).isInstanceOf(EvaluationRefusedException.class)
			.hasMessageContaining("VM start");
	}

	@Test
	@DisplayName("a hold undone behind the session's back refuses the lease instead of letting the thread run")
	void staleHoldRefusesTheLease() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		t.ref.resume();
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 2));
		assertThat(t.count).as("only the second stop really holds t").isEqualTo(1);

		assertThatThrownBy(() -> session.lease(t.ref)).isInstanceOf(EvaluationRefusedException.class)
			.hasMessageContaining("fewer than the 2 holds");
		assertThat(t.count).isEqualTo(1);
	}

	@Test
	@DisplayName("a manual hold that raced the stop is taken out for the lease and put back at its end")
	void manualHoldIsExemptedForTheLease() throws Exception {
		// jdwp_suspend_thread saw t running, then a breakpoint stopped t before the suspend landed.
		assertThat(session.suspendThread(t.ref)).isNull();
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		assertThat(t.count).isEqualTo(2);

		session.lease(t.ref);
		model.beginInvocation(t);
		assertThat(model.invocationRuns(t)).isTrue();
		model.completeInvocation(t);
		session.unlease(t.ref);
		assertThat(t.count).isEqualTo(2);

		session.releaseAll();
		assertThat(t.count).isZero();
	}

	@Test
	@DisplayName("a manual hold released during a lease is not resumed twice; the lease's own stop remains")
	void manualHoldReleasedDuringTheLease() throws Exception {
		assertThat(session.suspendThread(t.ref)).isNull();
		final SuspensionSession.Context own = session.route(model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD),
			t.ref, false);
		session.begin(own);
		session.beginProcessing(own);
		try {
			session.lease(t.ref);
			model.beginInvocation(t);
			model.completeInvocation(t);
			// jdwp_resume runs meanwhile, between two invocations of the dispatcher.
			session.releaseAll();
			assertThat(t.count).as("the set being processed still holds t").isEqualTo(1);
			session.unlease(t.ref);
		} finally {
			SuspensionSession.endProcessing();
		}
		assertThat(t.count).isEqualTo(1);
		session.finish(own);
		assertThat(t.count).isZero();
	}

	// ── Releasing the stop a running invocation borrows from: its share moves to the lease ──

	@Test
	@DisplayName("a thread-only stop released mid-invocation: the invocation runs on, and the thread runs once it ends")
	void ownStopReleasedMidInvocation() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		session.lease(t.ref);
		model.beginInvocation(t);

		assertThat(session.releaseAll().released()).hasSize(1);
		assertThat(model.invocationRuns(t)).isTrue();

		model.completeInvocation(t);
		assertThat(t.count).as("the restored count now belongs to the lease").isEqualTo(1);
		session.unlease(t.ref);
		assertThat(t.count).isZero();
	}

	@Test
	@DisplayName("a thread-only stop released between two invocations: the next one still borrows a real count")
	void ownStopReleasedBetweenInvocations() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		session.lease(t.ref);

		session.releaseAll();
		assertThat(t.count).as("still suspended for the next invocation").isEqualTo(1);
		model.beginInvocation(t);
		model.completeInvocation(t);
		session.unlease(t.ref);

		assertThat(t.count).isZero();
	}

	@Test
	@DisplayName("a stop-all released mid-invocation frees the other threads; the borrower's share waits for the lease")
	void stopAllReleasedMidInvocation() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_ALL, 1));
		session.lease(t.ref);
		model.beginInvocation(t);

		session.releaseAll();
		assertThat(model.counts()).containsEntry("t", 0).containsEntry("u", 0).containsEntry("w", 0);
		assertThat(model.invocationRuns(t)).isTrue();

		model.completeInvocation(t);
		session.unlease(t.ref);
		assertThat(model.counts()).containsEntry("t", 0).containsEntry("u", 0).containsEntry("w", 0);
	}

	@Test
	@DisplayName("a stop-all released between two invocations keeps the borrower suspended for the next one")
	void stopAllReleasedBetweenInvocations() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_ALL, 1));
		session.lease(t.ref);

		session.releaseAll();
		assertThat(model.counts()).containsEntry("t", 1).containsEntry("u", 0).containsEntry("w", 0);
		model.beginInvocation(t);
		model.completeInvocation(t);
		session.unlease(t.ref);

		assertThat(model.counts()).containsEntry("t", 0);
	}

	@Test
	@DisplayName("jdwp_reset during an invocation hands the stop's share to the lease too")
	void resetDuringInvocation() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		session.lease(t.ref);
		model.beginInvocation(t);

		assertThat(session.reset()).isEqualTo(1);
		model.completeInvocation(t);
		session.unlease(t.ref);

		assertThat(t.count).isZero();
	}

	@Test
	@DisplayName("the borrowed stop released while a stop-all of another thread still holds: that stop-all alone remains")
	void releasedAnchorWithAnotherStopStillHolding() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		session.lease(t.ref);
		model.beginInvocation(t);
		final SuspensionSession.Context other = session.route(model.breakpointEvent(u, EventRequest.SUSPEND_ALL),
			u.ref, false);

		session.releaseAll();
		model.completeInvocation(t);
		session.unlease(t.ref);
		assertThat(model.counts()).as("the queued stop-all holds everyone once")
			.containsEntry("t", 1).containsEntry("u", 1).containsEntry("w", 1);

		session.begin(other);
		session.finish(other);
		assertThat(model.counts()).containsEntry("t", 0).containsEntry("u", 0).containsEntry("w", 0);
	}

	@Test
	@DisplayName("resume_thread and a step are refused on a thread an evaluation is running on")
	void releaseAndStepRefusedOnALeasedThread() throws Exception {
		presented(stopOn(t, EventRequest.SUSPEND_EVENT_THREAD, 1));
		session.lease(t.ref);

		assertThat(session.releaseThread(t.ref).refusal()).contains("an evaluation is running");
		assertThat(session.step(t.ref, this::stepRequest)).contains("an evaluation is running");
		assertThat(t.count).isEqualTo(1);
		session.unlease(t.ref);
	}

	@Test
	@DisplayName("a closed session leases nothing and touches no count")
	void closedSessionLeasesNothing() {
		session.close();
		session.lease(w.ref);
		session.unlease(w.ref);
		assertThat(model.counts()).containsEntry("w", 0);
	}

	/**
	 * Presents {@code context}'s stop, as a tool showing it does.
	 */
	private SuspensionSession.Context presented(SuspensionSession.Context context) throws InterruptedException {
		assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
		return context;
	}

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
