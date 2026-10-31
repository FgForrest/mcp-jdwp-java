package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.request.EventRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SuspensionWatchdog}: when it asks for a stuck dispatcher invocation's monitor owner and
 * hands it to {@link SuspensionSession#unblock} — after {@link SuspensionWatchdog#MIN_AGE_NANOS},
 * blocked on a monitor in {@link SuspensionWatchdog#MONITOR_SAMPLES} consecutive samples of the same
 * invocation (issue #45, proposal § 19 A1).
 */
@DisplayName("SuspensionWatchdog — sampling stuck dispatcher invocations (#45 A1)")
class SuspensionWatchdogTest {

	private FakeSuspendModel model;
	private FakeSuspendModel.FakeThread t;
	private FakeSuspendModel.FakeThread u;
	private SuspensionSession session;
	private final AtomicLong now = new AtomicLong();
	private SuspensionWatchdog watchdog;

	@BeforeEach
	void setUp() throws Exception {
		model = new FakeSuspendModel();
		t = model.thread("t");
		u = model.thread("u");
		session = new BreakpointTracker().openSuspensionSession(new EventHistory());
		watchdog = new SuspensionWatchdog(session, now::get);
		// The dispatcher processes a stop-all of t and invokes on t; u, parked by it, owns the monitor
		// the invoked code waits for.
		final SuspensionSession.Context own = session.route(model.breakpointEvent(t, EventRequest.SUSPEND_ALL),
			t.ref, false);
		session.begin(own);
		session.beginProcessing(own);
		session.lease(t.ref);
		model.beginInvocation(t);
		final ObjectReference monitor = mock(ObjectReference.class);
		when(monitor.owningThread()).thenReturn(u.ref);
		when(t.ref.currentContendedMonitor()).thenReturn(monitor);
	}

	@AfterEach
	void tearDown() {
		SuspensionSession.endProcessing();
	}

	@Test
	@DisplayName("blocked on a monitor past the minimum age, twice in a row: the owner is released, the query is balanced")
	void releasesTheOwnerOfAStuckInvocation() throws Exception {
		t.status = ThreadReference.THREAD_STATUS_MONITOR;
		watchdog.tick();
		advance(SuspensionWatchdog.MIN_AGE_NANOS);
		watchdog.tick();
		assertThat(u.count).as("one sample is not enough").isEqualTo(1);

		advance(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
		watchdog.tick();

		assertThat(u.count).isZero();
		assertThat(model.invocationRuns(t)).as("the brief suspension around the query is undone").isTrue();
	}

	@Test
	@DisplayName("an owner that left the monitor between the query and the decision is not released")
	void ownerThatLeftTheMonitorIsLeftAlone() throws Exception {
		final ObjectReference monitor = mock(ObjectReference.class);
		// Owned by u when asked; by the time the session decides, u has left it (and may have stopped elsewhere).
		when(monitor.owningThread()).thenReturn(u.ref, (ThreadReference) null);
		when(t.ref.currentContendedMonitor()).thenReturn(monitor);
		t.status = ThreadReference.THREAD_STATUS_MONITOR;
		watchdog.tick();
		advance(SuspensionWatchdog.MIN_AGE_NANOS);
		watchdog.tick();
		advance(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
		watchdog.tick();

		assertThat(u.count).isEqualTo(1);
		assertThat(model.invocationRuns(t)).as("the brief suspension around the query is undone").isTrue();
	}

	@Test
	@DisplayName("a young invocation is not sampled, however blocked")
	void youngInvocationIsLeftAlone() throws Exception {
		t.status = ThreadReference.THREAD_STATUS_MONITOR;
		for (int i = 0; i < 5; i++) {
			watchdog.tick();
			advance(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
		}

		assertThat(u.count).isEqualTo(1);
		verify(t.ref, never()).currentContendedMonitor();
	}

	@Test
	@DisplayName("a sample that finds the invocation waiting for no monitor starts the count again")
	void unblockedSampleResetsTheStreak() throws Exception {
		final ObjectReference monitor = mock(ObjectReference.class);
		when(monitor.owningThread()).thenReturn(u.ref);
		// Blocked, then running on (no monitor), then blocked again.
		when(t.ref.currentContendedMonitor()).thenReturn(monitor, null, monitor);
		watchdog.tick();
		advance(SuspensionWatchdog.MIN_AGE_NANOS);
		t.status = ThreadReference.THREAD_STATUS_MONITOR;
		watchdog.tick();
		t.status = ThreadReference.THREAD_STATUS_RUNNING;
		watchdog.tick();
		t.status = ThreadReference.THREAD_STATUS_MONITOR;
		watchdog.tick();

		assertThat(u.count).isEqualTo(1);
	}

	@Test
	@DisplayName("an invocation JDWP shows as running though it waits for a monitor — made from inside its event's handling — is found (#47 § 4)")
	void monitorWaitShownAsRunningIsFound() throws Exception {
		t.status = ThreadReference.THREAD_STATUS_RUNNING;
		watchdog.tick();
		advance(SuspensionWatchdog.MIN_AGE_NANOS);
		watchdog.tick();
		advance(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
		watchdog.tick();

		assertThat(u.count).isZero();
		assertThat(model.invocationRuns(t)).as("the brief suspension around the query is undone").isTrue();
	}

	@Test
	@DisplayName("a waiting or sleeping invocation is not asked for a monitor owner")
	void waitingInvocationIsNotAsked() throws Exception {
		for (int status : new int[]{ThreadReference.THREAD_STATUS_WAIT, ThreadReference.THREAD_STATUS_SLEEPING}) {
			t.status = status;
			watchdog.tick();
			advance(SuspensionWatchdog.MIN_AGE_NANOS);
			watchdog.tick();
			advance(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
			watchdog.tick();
		}

		assertThat(u.count).isEqualTo(1);
		verify(t.ref, never()).currentContendedMonitor();
	}

	@Test
	@DisplayName("samples belong to one invocation: the next invocation on the thread starts young")
	void nextInvocationStartsAfresh() throws Exception {
		t.status = ThreadReference.THREAD_STATUS_MONITOR;
		watchdog.tick();
		advance(SuspensionWatchdog.MIN_AGE_NANOS);
		watchdog.tick();
		model.completeInvocation(t);
		session.unlease(t.ref);
		session.lease(t.ref);
		model.beginInvocation(t);

		watchdog.tick();
		assertThat(u.count).as("the new invocation was only just seen").isEqualTo(1);
	}

	@Test
	@DisplayName("host-side work of the lease (thread suspended between invocations) does not age the next invocation (#47 § 2)")
	void hostWorkDoesNotAgeTheInvocation() throws Exception {
		// The lease starts with host-side work: the thread is still suspended at its event.
		model.completeInvocation(t);
		watchdog.tick();
		advance(SuspensionWatchdog.MIN_AGE_NANOS * 2);
		watchdog.tick();
		// Then the invocation starts and blocks at once.
		model.beginInvocation(t);
		t.status = ThreadReference.THREAD_STATUS_MONITOR;
		for (int i = 0; i < 3; i++) {
			watchdog.tick();
			advance(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
		}

		assertThat(u.count).as("the invocation is only 1.5 s old").isEqualTo(1);
		verify(t.ref, never()).currentContendedMonitor();
	}

	@Test
	@DisplayName("two consecutive blocked samples on different monitors do not count as one wait (#47 § 2)")
	void samplesOnAnotherMonitorStartAgain() throws Exception {
		final ObjectReference first = mock(ObjectReference.class);
		final ObjectReference second = mock(ObjectReference.class);
		when(second.owningThread()).thenReturn(u.ref);
		when(t.ref.currentContendedMonitor()).thenReturn(first, second, second);
		t.status = ThreadReference.THREAD_STATUS_MONITOR;
		watchdog.tick();
		advance(SuspensionWatchdog.MIN_AGE_NANOS);
		watchdog.tick();
		advance(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
		watchdog.tick();
		assertThat(u.count).as("first and second sample waited for different monitors").isEqualTo(1);

		advance(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
		watchdog.tick();
		assertThat(u.count).isZero();
		assertThat(model.invocationRuns(t)).isTrue();
	}

	@Test
	@DisplayName("a closed session stops the watchdog")
	void closedSessionStopsIt() {
		session.close();
		watchdog.tick();
		assertThat(session.dispatcherInvocations()).isEmpty();
	}

	private void advance(long nanos) {
		now.addAndGet(nanos);
	}
}
