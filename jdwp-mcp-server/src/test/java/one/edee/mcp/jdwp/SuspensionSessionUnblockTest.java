package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SuspensionSession#unblock} over {@link FakeSuspendModel}: what the watchdog may release so a
 * stuck dispatcher invocation finishes, how a cancelled set is settled, and what it never touches
 * (issue #45, proposal § 19 A1, A2; review finding 5). Every scenario ends on the suspend counts the
 * target would really have.
 */
@DisplayName("SuspensionSession — the watchdog's releases (#45 A1)")
class SuspensionSessionUnblockTest {

	private FakeSuspendModel model;
	private FakeSuspendModel.FakeThread t;
	private FakeSuspendModel.FakeThread u;
	private FakeSuspendModel.FakeThread w;
	private EventHistory history;
	private SuspensionSession session;

	@BeforeEach
	void setUp() {
		model = new FakeSuspendModel();
		t = model.thread("t");
		u = model.thread("u");
		w = model.thread("w");
		history = new EventHistory();
		session = new BreakpointTracker().openSuspensionSession(history);
	}

	@AfterEach
	void tearDown() {
		SuspensionSession.endProcessing();
	}

	@Test
	@DisplayName("a bystander of the stop-all being processed is released; the evaluation finishes and the stop is partial")
	void bystanderOfTheProcessedStopAll() throws Exception {
		final SuspensionSession.Context own = processing(t, EventRequest.SUSPEND_ALL);
		final SuspensionSession.Invocation invocation = invoking(t);
		assertThat(u.count).as("u, parked by the stop-all, holds the monitor t's invocation waits for").isEqualTo(1);

		assertThat(unblock(invocation, u.ref)).contains("thread u").contains("evaluation on thread t");
		assertThat(u.count).isZero();
		assertThat(w.count).as("only the owner").isEqualTo(1);
		assertThat(types()).contains("EVALUATION_UNBLOCKED");

		finishInvocation(t);
		session.candidate(own, new BreakpointTracker.LastBreakpoint(t.ref, 1));
		assertThat(session.finish(own)).isTrue();
		assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
		assertThat(session.unblockedNote()).contains("partial stop").contains("u");

		session.releaseAll();
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).isZero());
	}

	@Test
	@DisplayName("finding 5: a queued thread-only set is cancelled and settled at once — a later stop of its thread keeps its count")
	void queuedThreadOnlySetIsSettledAtOnce() throws Exception {
		final SuspensionSession.Context own = processing(t, EventRequest.SUSPEND_EVENT_THREAD);
		final SuspensionSession.Invocation invocation = invoking(t);
		final SuspensionSession.Context queued = session.route(
			model.breakpointEvent(u, EventRequest.SUSPEND_EVENT_THREAD), u.ref, false);

		assertThat(unblock(invocation, u.ref)).contains("cancelled");
		assertThat(u.count).isZero();
		assertThat(session.stateOf(queued)).isEqualTo(SuspensionSession.State.RELEASED);
		assertThat(types()).contains("EVENT_CANCELLED");

		// u runs on and stops again, at a breakpoint R.
		final SuspensionSession.Context later = session.route(
			model.breakpointEvent(u, EventRequest.SUSPEND_EVENT_THREAD), u.ref, false);
		finishInvocation(t);
		session.finish(own);

		assertThat(session.begin(queued)).as("the dispatcher skips its handlers").isFalse();
		assertThat(session.finish(queued)).isFalse();
		assertThat(u.count).as("R's suspension is not spent by the cancelled set").isEqualTo(1);

		session.begin(later);
		session.candidate(later, new BreakpointTracker.LastBreakpoint(u.ref, 2));
		assertThat(session.finish(later)).isTrue();
		assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
		session.releaseAll();
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).isZero());
	}

	@Test
	@DisplayName("a queued stop-all of the owner is cancelled: its other threads stay parked until it is processed, and it never publishes")
	void queuedStopAllIsCancelledButKeepsTheOthers() throws Exception {
		final SuspensionSession.Context own = processing(t, EventRequest.SUSPEND_EVENT_THREAD);
		final SuspensionSession.Invocation invocation = invoking(t);
		final SuspensionSession.Context queued = session.route(model.breakpointEvent(u, EventRequest.SUSPEND_ALL),
			u.ref, false);
		assertThat(model.invocationRuns(t)).as("routing took t out of the stop-all").isTrue();

		assertThat(unblock(invocation, u.ref)).contains("cancelled");
		assertThat(u.count).isZero();
		assertThat(w.count).isEqualTo(1);
		assertThat(session.stateOf(queued)).isEqualTo(SuspensionSession.State.QUEUED);

		finishInvocation(t);
		session.finish(own);
		assertThat(w.count).as("parked by the cancelled set until it is processed").isEqualTo(1);

		assertThat(session.begin(queued)).isFalse();
		session.candidate(queued, new BreakpointTracker.LastBreakpoint(u.ref, 2));
		assertThat(session.finish(queued)).as("a cancelled set never publishes").isFalse();
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).isZero());
		assertThat(session.unseenCount()).isZero();
	}

	@Test
	@DisplayName("a thread born under the stop-all being processed inherits its count and is released from it")
	void newbornIsReleasedFromTheStopAll() throws Exception {
		final SuspensionSession.Context own = processing(t, EventRequest.SUSPEND_ALL);
		final SuspensionSession.Invocation invocation = invoking(t);
		final FakeSuspendModel.FakeThread born = model.thread("born");
		assertThat(born.count).isEqualTo(1);

		assertThat(unblock(invocation, born.ref)).isNotNull();
		assertThat(born.count).isZero();

		finishInvocation(t);
		session.finish(own);
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).isZero());
	}

	@Test
	@DisplayName("nothing the session cannot account for is released: a published stop, a manual hold, a reserved thread, an unknown count")
	void refusesWhatItCannotAccountFor() throws Exception {
		presented(stopOn(w, EventRequest.SUSPEND_EVENT_THREAD, 1));
		assertThat(session.suspendThread(u.ref)).isNull();
		final FakeSuspendModel.FakeThread y = model.thread("y");
		processing(t, EventRequest.SUSPEND_EVENT_THREAD);
		final SuspensionSession.Invocation invocation = invoking(t);
		session.route(model.breakpointEvent(y, EventRequest.SUSPEND_EVENT_THREAD), y.ref, false);

		assertThat(unblock(invocation, w.ref)).as("held by a published stop").isNull();
		assertThat(unblock(invocation, u.ref)).as("manually held").isNull();
		assertThat(unblock(invocation, model.thread("free").ref)).as("held by nothing").isNull();
		assertThat(unblock(invocation, t.ref)).as("the invoking thread itself").isNull();
		session.reserve(y.ref);
		assertThat(unblock(invocation, y.ref)).as("reserved by a frame tool").isNull();
		session.unreserve(y.ref);
		y.ref.suspend();
		assertThat(unblock(invocation, y.ref)).as("a hold the session does not know").isNull();
		y.ref.resume();
		assertThat(model.counts()).containsEntry("w", 1).containsEntry("u", 1).containsEntry("y", 1);

		assertThat(unblock(invocation, y.ref)).as("all of it accounted for").isNotNull();
		assertThat(y.count).isZero();

		finishInvocation(t);
		assertThat(unblock(invocation, w.ref)).as("an ended invocation").isNull();
	}

	@Test
	@DisplayName("a set whose handling needs its thread stopped (a fast breakpoint's stop) is never cancelled")
	void keepsThreadSetIsNeverCancelled() throws Exception {
		processing(t, EventRequest.SUSPEND_EVENT_THREAD);
		final SuspensionSession.Invocation invocation = invoking(t);
		final BreakpointRequest stopRequest = mock(BreakpointRequest.class);
		when(stopRequest.getProperty(SuspensionSession.KEEPS_THREAD_PROPERTY)).thenReturn(Boolean.TRUE);
		session.route(model.breakpointEvent(u, EventRequest.SUSPEND_EVENT_THREAD, stopRequest), u.ref, false);

		assertThat(unblock(invocation, u.ref)).isNull();
		assertThat(u.count).isEqualTo(1);
	}

	@Test
	@DisplayName("only dispatcher invocations are watched: a tool's has its own bounded wait")
	void workerInvocationsAreNotWatched() throws Exception {
		presented(stopOn(w, EventRequest.SUSPEND_EVENT_THREAD, 1));
		session.lease(w.ref);
		assertThat(session.dispatcherInvocations()).isEmpty();
		session.unlease(w.ref);

		processing(t, EventRequest.SUSPEND_EVENT_THREAD);
		invoking(t);
		assertThat(session.dispatcherInvocations()).extracting(SuspensionSession.Invocation::thread).containsExactly(t.ref);
	}

	/**
	 * {@link SuspensionSession#unblock} called from another thread, as the watchdog's: the test thread
	 * plays the dispatcher, whose history entries are buffered until its set finishes.
	 */
	private String unblock(SuspensionSession.Invocation invocation, ThreadReference owner) throws Exception {
		return CompletableFuture.supplyAsync(() -> session.unblock(invocation.token(), owner)).get();
	}

	/**
	 * Routes a set on {@code thread} and starts processing it on the test thread, as the dispatcher does.
	 */
	private SuspensionSession.Context processing(FakeSuspendModel.FakeThread thread, int policy) {
		final SuspensionSession.Context context = session.route(model.breakpointEvent(thread, policy), thread.ref, false);
		assertThat(session.begin(context)).isTrue();
		session.beginProcessing(context);
		return context;
	}

	/**
	 * The dispatcher leases {@code thread} and starts an invocation on it.
	 */
	private SuspensionSession.Invocation invoking(FakeSuspendModel.FakeThread thread) throws Exception {
		session.lease(thread.ref);
		model.beginInvocation(thread);
		assertThat(model.invocationRuns(thread)).isTrue();
		final List<SuspensionSession.Invocation> invocations = session.dispatcherInvocations();
		assertThat(invocations).hasSize(1);
		return invocations.get(0);
	}

	private void finishInvocation(FakeSuspendModel.FakeThread thread) {
		model.completeInvocation(thread);
		session.unlease(thread.ref);
		SuspensionSession.endProcessing();
	}

	private SuspensionSession.Context stopOn(FakeSuspendModel.FakeThread thread, int policy, int breakpointId) {
		final SuspensionSession.Context context = session.route(model.breakpointEvent(thread, policy), thread.ref, false);
		session.begin(context);
		session.candidate(context, new BreakpointTracker.LastBreakpoint(thread.ref, breakpointId));
		session.finish(context);
		return context;
	}

	private void presented(SuspensionSession.Context context) throws InterruptedException {
		assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
	}

	private List<String> types() {
		return history.getRecent(EventHistory.MAX_EVENTS).stream().map(EventHistory.DebugEvent::type).toList();
	}
}
