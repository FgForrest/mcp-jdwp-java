package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvaluationGuard}, the per-thread reentrancy guard that lets
 * {@link JdiEventListener} skip recursive breakpoint hits during MCP-driven evaluations.
 *
 * <p>{@code enter} and {@code exit} take a {@code long} captured by the caller, so most tests
 * here don't need any JDI mocks at all. The two {@link #isEvaluating(ThreadReference)} tests
 * mock a {@link ThreadReference} because that signature exists for the listener's hot path and
 * we still want coverage for it — the same precedent used by {@code JdiEventListenerDisconnectTest}
 * for mocking JDI interfaces that have no real-VM-free fake.
 */
class EvaluationGuardTest {

	private static final long THREAD_A = 1L;
	private static final long THREAD_B = 2L;

	@Test
	@DisplayName("fresh guard reports no threads evaluating")
	void shouldStartEmptyAndReportNotEvaluating() {
		EvaluationGuard guard = new EvaluationGuard();

		assertThat(guard.depth(THREAD_A)).isZero();
		assertThat(guard.isEvaluating(mockThread(THREAD_A))).isFalse();
	}

	@Test
	@DisplayName("enter flips isEvaluating to true")
	void shouldReportEvaluatingAfterEnter() {
		EvaluationGuard guard = new EvaluationGuard();

		guard.enter(THREAD_A);

		assertThat(guard.depth(THREAD_A)).isEqualTo(1);
		assertThat(guard.isEvaluating(mockThread(THREAD_A))).isTrue();
	}

	@Test
	@DisplayName("matching exit clears the guard")
	void shouldClearAfterMatchingExit() {
		EvaluationGuard guard = new EvaluationGuard();

		guard.enter(THREAD_A);
		guard.exit(THREAD_A);

		assertThat(guard.depth(THREAD_A)).isZero();
		assertThat(guard.isEvaluating(mockThread(THREAD_A))).isFalse();
	}

	@Test
	@DisplayName("nested enters require matching exits before the guard clears")
	void shouldKeepEvaluatingWhileNestedEntersOutnumberExits() {
		EvaluationGuard guard = new EvaluationGuard();

		guard.enter(THREAD_A);
		guard.enter(THREAD_A);
		assertThat(guard.depth(THREAD_A)).isEqualTo(2);

		guard.exit(THREAD_A);
		assertThat(guard.depth(THREAD_A)).isEqualTo(1);

		guard.exit(THREAD_A);
		assertThat(guard.depth(THREAD_A)).isZero();
	}

	@Test
	@DisplayName("threads are tracked independently by uniqueID")
	void shouldIsolateThreadsByUniqueId() {
		EvaluationGuard guard = new EvaluationGuard();

		guard.enter(THREAD_A);

		assertThat(guard.isEvaluating(mockThread(THREAD_A))).isTrue();
		assertThat(guard.isEvaluating(mockThread(THREAD_B))).isFalse();

		guard.enter(THREAD_B);
		guard.exit(THREAD_A);

		assertThat(guard.isEvaluating(mockThread(THREAD_A))).isFalse();
		assertThat(guard.isEvaluating(mockThread(THREAD_B))).isTrue();
	}

	@Test
	@DisplayName("different ThreadReference instances with the same uniqueID share state")
	void shouldTreatSameUniqueIdAsSameThread() {
		// JDI may hand out different ThreadReference instances pointing at the same underlying
		// target thread across lookups — the guard's hot-path read must key on the stable
		// uniqueID, not identity.
		EvaluationGuard guard = new EvaluationGuard();
		guard.enter(42L);

		ThreadReference first = mockThread(42L);
		ThreadReference second = mockThread(42L);

		assertThat(guard.isEvaluating(first)).isTrue();
		assertThat(guard.isEvaluating(second)).isTrue();
	}

	@Test
	@DisplayName("exit without matching enter is a no-op and does not go negative")
	void shouldHandleExitWithoutEnterAsNoOp() {
		EvaluationGuard guard = new EvaluationGuard();

		// Must not throw
		guard.exit(THREAD_A);
		guard.exit(THREAD_A);

		assertThat(guard.depth(THREAD_A)).isZero();

		// A subsequent enter still behaves normally — the counter did not go negative under the hood.
		guard.enter(THREAD_A);
		assertThat(guard.depth(THREAD_A)).isEqualTo(1);
	}

	@Test
	@DisplayName("exit succeeds with an id whose ThreadReference has died")
	void shouldAllowExitAfterThreadDeath() {
		// If the target thread dies mid-evaluation, exit() must still clean up via the pre-captured
		// id. Simulate this by calling enter with a captured id, then having any subsequent
		// ThreadReference lookup for that id throw (as JDI would via ObjectCollectedException). The
		// exit path must never touch a ThreadReference.
		EvaluationGuard guard = new EvaluationGuard();
		long capturedId = 9000L;

		guard.enter(capturedId);
		// Imagine the target thread dies here.
		guard.exit(capturedId);

		assertThat(guard.depth(capturedId)).isZero();
	}

	@Test
	@DisplayName("balanced enter/exit pairs from many threads leave the guard empty")
	void shouldBeSafeUnderConcurrentEntersAndExits() throws InterruptedException {
		EvaluationGuard guard = new EvaluationGuard();

		int workers = 16;
		int iterations = 1_000;
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(workers);

		try {
			for (int i = 0; i < workers; i++) {
				pool.submit(() -> {
					try {
						start.await();
						for (int j = 0; j < iterations; j++) {
							guard.enter(THREAD_A);
							guard.exit(THREAD_A);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}

			start.countDown();
			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}

		assertThat(guard.depth(THREAD_A)).isZero();
	}

	@Test
	@DisplayName("concurrent workers that each leave one dangling enter sum to the expected depth")
	void shouldAccumulateDepthCorrectlyWhenEntersOutnumberExitsPerThread() throws InterruptedException {
		// Stronger atomicity check: each worker does `enter; enter; exit` so the guard MUST see
		// exactly one net increment per worker. With a non-atomic merge/compute pair, racing
		// threads would drop increments and the depth would fall short of `workers`.
		EvaluationGuard guard = new EvaluationGuard();

		int workers = 32;
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(workers);

		try {
			for (int i = 0; i < workers; i++) {
				pool.submit(() -> {
					try {
						start.await();
						guard.enter(THREAD_A);
						guard.enter(THREAD_A);
						guard.exit(THREAD_A);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				});
			}

			start.countDown();
			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}

		assertThat(guard.depth(THREAD_A)).isEqualTo(workers);
	}

	/**
	 * Creates a {@link ThreadReference} mock whose {@code uniqueID()} returns {@code id}. Only
	 * used by the {@link EvaluationGuard#isEvaluating(ThreadReference)} hot-path tests; the
	 * enter/exit tests take {@code long} directly and don't need mocks.
	 */
	private static ThreadReference mockThread(long id) {
		ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(id);
		return thread;
	}
}
