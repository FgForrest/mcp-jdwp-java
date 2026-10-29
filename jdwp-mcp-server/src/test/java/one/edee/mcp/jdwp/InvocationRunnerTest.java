package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link InvocationRunner}: a tool's invocation runs on a pool thread and is waited for at most the
 * timeout; what outlives it is reported once, late, in the session it started in (issue #45, § 19 A1).
 */
@DisplayName("InvocationRunner — bounded waits for invocations in the target")
class InvocationRunnerTest {

	private ExecutorService pool;
	private EventHistory history;
	private SuspensionSession session;

	@BeforeEach
	void setUp() {
		pool = InvocationRunner.newPool();
		history = new EventHistory();
		session = new BreakpointTracker().openSuspensionSession(history);
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	private InvocationRunner runner(long timeoutMs) {
		return new InvocationRunner(pool, timeoutMs, history, () -> session);
	}

	@Test
	@DisplayName("returns the work's value, and rethrows its failure as it was")
	void returnsValueOrRethrows() throws Exception {
		final InvocationRunner runner = runner(5_000);

		assertThat(runner.run(thread("main", 1), "evaluating 1 + 1", () -> 2)).isEqualTo(2);
		assertThatThrownBy(() -> runner.run(thread("main", 1), "evaluating x", () -> {
			throw new IOException("boom");
		})).isInstanceOf(IOException.class).hasMessage("boom");
	}

	@Test
	@DisplayName("work that outlives the wait is reported as still running, then recorded once when it ends")
	void outlivingWorkIsReportedLate() throws Exception {
		final InvocationRunner runner = runner(100);
		final CountDownLatch release = new CountDownLatch(1);
		final ThreadReference main = thread("main", 1);

		assertThatThrownBy(() -> runner.run(main, "evaluating slow()", () -> {
			release.await();
			return "done";
		})).isInstanceOf(InvocationRunner.StillRunning.class)
			.hasMessageContaining("did not finish within 0 s").hasMessageContaining("thread main");
		assertThat(lateRecords()).isEmpty();

		release.countDown();
		awaitLateRecords(1);
		assertThat(lateRecords().get(0).summary()).contains("evaluating slow()").contains("returned done");
	}

	@Test
	@DisplayName("a thread with work still running refuses another job before it starts; other threads proceed")
	void busyThreadRefusesAnotherJob() throws Exception {
		final InvocationRunner runner = runner(100);
		final CountDownLatch release = new CountDownLatch(1);
		final ThreadReference main = thread("main", 1);
		final AtomicBoolean secondRan = new AtomicBoolean();
		assertThatThrownBy(() -> runner.run(main, "evaluating slow()", () -> release.await(10, TimeUnit.SECONDS)))
			.isInstanceOf(InvocationRunner.StillRunning.class);

		assertThatThrownBy(() -> runner.run(main, "evaluating other()", () -> {
			secondRan.set(true);
			return 1;
		})).isInstanceOf(InvocationRunner.StillRunning.class).hasMessageContaining("evaluating slow()");
		assertThat(runner.run(thread("worker", 2), "evaluating 3", () -> 3)).isEqualTo(3);
		assertThat(secondRan).isFalse();

		release.countDown();
		awaitLateRecords(1);
		assertThat(runner.run(main, "evaluating 4", () -> 4)).as("free again once the first ended").isEqualTo(4);
	}

	@Test
	@DisplayName("a late result of an ended connection is not recorded in the next one")
	void lateResultOfAnEndedConnectionIsDropped() throws Exception {
		final InvocationRunner runner = runner(100);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch finished = new CountDownLatch(1);
		assertThatThrownBy(() -> runner.run(thread("main", 1), "evaluating slow()", () -> {
			try {
				release.await();
				return 1;
			} finally {
				finished.countDown();
			}
		})).isInstanceOf(InvocationRunner.StillRunning.class);

		session.close();
		session = new BreakpointTracker().openSuspensionSession(history);
		release.countDown();
		assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(100);

		assertThat(lateRecords()).isEmpty();
	}

	@Test
	@DisplayName("work the caller gave up on before it started never runs")
	void abandonedBeforeStartNeverRuns() throws Exception {
		final List<Runnable> held = new ArrayList<>();
		final InvocationRunner runner = new InvocationRunner(held::add, 50, history, () -> session);
		final AtomicBoolean ran = new AtomicBoolean();

		assertThatThrownBy(() -> runner.run(thread("main", 1), "evaluating x", () -> {
			ran.set(true);
			return 1;
		})).isInstanceOf(InvocationRunner.StillRunning.class).hasMessageContaining("did not start");
		held.get(0).run();

		assertThat(ran).isFalse();
		assertThat(lateRecords()).isEmpty();
	}

	@Test
	@DisplayName("a thread blocked on a monitor names its owner; the brief suspension is balanced")
	void blockedThreadNamesTheMonitorOwner() throws Exception {
		final InvocationRunner runner = runner(100);
		final ThreadReference main = thread("main", 1);
		final ThreadReference owner = thread("owner", 7);
		when(owner.isSuspended()).thenReturn(true);
		final ObjectReference monitor = mock(ObjectReference.class);
		when(monitor.owningThread()).thenReturn(owner);
		when(main.status()).thenReturn(ThreadReference.THREAD_STATUS_MONITOR);
		when(main.currentContendedMonitor()).thenReturn(monitor);
		final CountDownLatch release = new CountDownLatch(1);

		try {
			assertThatThrownBy(() -> runner.run(main, "evaluating locked()", () -> release.await(10, TimeUnit.SECONDS)))
				.hasMessageContaining("held by thread owner (ID=7), which is suspended");
		} finally {
			release.countDown();
		}
		final InOrder order = inOrder(main);
		order.verify(main).suspend();
		order.verify(main).currentContendedMonitor();
		order.verify(main).resume();
	}

	private static ThreadReference thread(String name, long id) {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn(name);
		when(thread.uniqueID()).thenReturn(id);
		return thread;
	}

	private List<EventHistory.DebugEvent> lateRecords() {
		return history.getRecent(EventHistory.MAX_EVENTS).stream()
			.filter(event -> event.type().equals("EVALUATION_COMPLETED_LATE")).toList();
	}

	private void awaitLateRecords(int count) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (lateRecords().size() < count && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(lateRecords()).hasSize(count);
	}
}
