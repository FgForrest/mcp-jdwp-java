package one.edee.mcp.jdwp;

import com.sun.jdi.IntegerValue;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The races of issue #47 § 4, each held at its gap: the competing action runs inside the mocked JDI
 * call that marks the gap — on the thread the dispatcher, the watchdog or a tool's job would run it
 * on — so every run takes the same interleaving. Every scenario ends on the suspend counts the
 * target would really have ({@link FakeSuspendModel}).
 */
@DisplayName("SuspensionSession — races held at their barriers (#47 § 4)")
class SuspensionRaceBarrierTest {

	private static final long WAIT_SECONDS = 5;

	private FakeSuspendModel model;
	private FakeSuspendModel.FakeThread t;
	private FakeSuspendModel.FakeThread u;
	private EventHistory history;
	private SuspensionSession session;
	private EvaluationGuard guard;
	private JdiExpressionEvaluator evaluator;
	private JDWPTools tools;
	/**
	 * Plays the event dispatcher: the sets it processes and the leases it takes are its own.
	 */
	private ExecutorService dispatcher;

	@BeforeEach
	void setUp() throws Exception {
		model = new FakeSuspendModel();
		t = model.thread("t");
		u = model.thread("u");
		history = new EventHistory();
		final BreakpointTracker tracker = new BreakpointTracker();
		session = tracker.openSuspensionSession(history);
		guard = new EvaluationGuard();
		guard.bind(session);
		evaluator = mock(JdiExpressionEvaluator.class);
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		when(jdiService.getVM()).thenReturn(model.vm);
		tools = JDWPToolsTestSupport.newTools(jdiService, tracker, mock(WatcherManager.class), evaluator, history, guard,
			new JvmDiscoveryService());
		dispatcher = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-dispatcher"));
	}

	@AfterEach
	void tearDown() throws Exception {
		dispatcher.submit(SuspensionSession::endProcessing).get(WAIT_SECONDS, TimeUnit.SECONDS);
		dispatcher.shutdownNow();
	}

	@Nested
	@DisplayName("reservation, then the evaluation check, then lease entry")
	class ReservationAndLeaseEntry {

		@Test
		@DisplayName("a tool's job still compiling holds no lease yet, but a frame tool refuses its thread before reading anything")
		void compilingJobRefusesFrameTools() throws Exception {
			presentedStop(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
			final CountDownLatch compiling = new CountDownLatch(1);
			final CountDownLatch compiled = new CountDownLatch(1);
			doAnswer(invocation -> {
				compiling.countDown();
				assertThat(compiled.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
				return null;
			}).when(evaluator).configureCompilerClasspath(t.ref);
			final StackFrame frame = mock(StackFrame.class);
			when(t.ref.frame(0)).thenReturn(frame);
			final Value result = mock(Value.class);
			when(evaluator.evaluate(eq(frame), eq("slow()"), any())).thenAnswer(invocation -> invoked(t, result));
			final CompletableFuture<String> evaluation =
				CompletableFuture.supplyAsync(() -> tools.jdwp_evaluate_expression(id(t), "slow()", null));
			assertThat(compiling.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertThat(guard.isEvaluating(t.ref)).as("no lease yet").isFalse();

			final List<String> replies = List.of(
				tools.jdwp_get_locals(id(t), 0),
				tools.jdwp_set_local(id(t), 0, "count", "42"),
				tools.jdwp_get_stack(id(t), null, null));

			assertThat(replies).allSatisfy(reply -> assertThat(reply).startsWith("Error: an evaluation is running on thread t"));
			verify(t.ref, never()).frame(anyInt());
			verify(t.ref, never()).frames();

			compiled.countDown();
			assertThat(evaluation.get(WAIT_SECONDS, TimeUnit.SECONDS)).startsWith("Result of slow()");
			assertThat(t.count).as("back at its stop after the invocation").isEqualTo(1);
			session.releaseAll();
			assertAllRunning();
		}

		@Test
		@DisplayName("a frame tool has reserved the thread and found no evaluation: the dispatcher's lease waits until the tool is done")
		void dispatcherLeaseWaitsForTheFrameTool() throws Exception {
			// t is at a breakpoint whose condition the dispatcher is about to evaluate on t.
			final SuspensionSession.Context own = session.route(
				model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD), t.ref, false);
			onDispatcher(() -> {
				assertThat(session.begin(own)).isTrue();
				session.beginProcessing(own);
				return null;
			});
			final CountDownLatch invoking = new CountDownLatch(1);
			final AtomicBoolean invokedDuringTheRead = new AtomicBoolean();
			final AtomicReference<Future<?>> condition = new AtomicReference<>();
			final StackFrame frame = mock(StackFrame.class);
			when(t.ref.frame(0)).thenAnswer(invocation -> {
				// The gap: the tool reserved t and found no evaluation; now the dispatcher leases t.
				condition.set(dispatcher.submit(() -> {
					try (EvaluationGuard.Lease lease = guard.lease(t.ref)) {
						model.beginInvocation(t);
						invoking.countDown();
						model.completeInvocation(t);
					}
					return null;
				}));
				invokedDuringTheRead.set(invoking.await(300, TimeUnit.MILLISECONDS));
				return frame;
			});

			final String reply = tools.jdwp_get_locals(id(t), 0);

			assertThat(reply).startsWith("Local variables in frame 0");
			assertThat(invokedDuringTheRead).as("t ran invoked code while its frames were read").isFalse();
			condition.get().get(WAIT_SECONDS, TimeUnit.SECONDS);
			assertThat(invoking.getCount()).as("the condition ran once the tool was done").isZero();
			onDispatcher(() -> {
				SuspensionSession.endProcessing();
				assertThat(session.finish(own)).as("the condition was false").isFalse();
				return null;
			});
			assertAllRunning();
		}

		@Test
		@DisplayName("a frame tool that does not finish: the lease is refused after the wait, and nothing was taken")
		void leaseIsRefusedWhenTheToolDoesNotFinish() throws Exception {
			final SuspensionSession.Context own = session.route(
				model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD), t.ref, false);
			session.reserve(t.ref);
			final long started = System.nanoTime();
			assertThatThrownBy(() -> onDispatcher(() -> {
				assertThat(session.begin(own)).isTrue();
				session.beginProcessing(own);
				return guard.lease(t.ref);
			})).hasRootCauseInstanceOf(EvaluationRefusedException.class)
				.hasMessageContaining("a tool is reading the frames of thread t");

			assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("it waited for the tool first")
				.isGreaterThan(SuspensionSession.RESERVATION_WAIT_MS / 2);
			assertThat(guard.isEvaluating(t.ref)).isFalse();
			assertThat(session.dispatcherInvocations()).isEmpty();
			session.unreserve(t.ref);
			onDispatcher(() -> {
				SuspensionSession.endProcessing();
				assertThat(session.finish(own)).isFalse();
				return null;
			});
			assertAllRunning();
		}
	}

	@Nested
	@DisplayName("the hold check, then a frame tool's retry")
	class HoldCheckAndRetry {

		@Test
		@DisplayName("a set the dispatcher may release does not vouch for the retry: nothing is written to the frames of the thread's next stop")
		void unpublishedSetDoesNotVouchForTheRetry() throws Exception {
			// u's stop-all is being processed (its condition evaluated, say): t is parked by it.
			final SuspensionSession.Context stopAll = session.route(
				model.breakpointEvent(u, EventRequest.SUSPEND_ALL), u.ref, false);
			onDispatcher(() -> {
				assertThat(session.begin(stopAll)).isTrue();
				session.beginProcessing(stopAll);
				return null;
			});
			final IntegerValue fortyTwo = mock(IntegerValue.class);
			when(model.vm.mirrorOf(42)).thenReturn(fortyTwo);
			final LocalVariable count = intLocal("count");
			final StackFrame stale = frameWith("count", count);
			doThrow(new InvalidStackFrameException("Thread has been resumed")).when(stale).setValue(count, fortyTwo);
			final StackFrame next = frameWith("count", count);
			final AtomicReference<SuspensionSession.Context> nextStop = new AtomicReference<>();
			// The gap: the stop-all held t at the check; before the retry reads, the dispatcher finishes
			// it (its condition was false), and t runs on to a breakpoint R.
			final Callable<Void> runOn = () -> {
				if (nextStop.get() == null) {
					onDispatcher(() -> {
						SuspensionSession.endProcessing();
						assertThat(session.finish(stopAll)).isFalse();
						return null;
					});
					assertThat(t.count).as("released with the stop-all").isZero();
					nextStop.set(session.route(model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD), t.ref, false));
				}
				return null;
			};
			when(t.ref.frame(0)).thenReturn(stale).thenAnswer(invocation -> {
				runOn.call();
				return next;
			});

			final String reply = tools.jdwp_set_local(id(t), 0, "count", "42");

			assertThat(reply).startsWith("Error: thread t was resumed").contains("nothing was written");
			verify(next, never()).setValue(any(), any());
			runOn.call();
			assertThat(session.begin(nextStop.get())).isTrue();
			session.candidate(nextStop.get(), new BreakpointTracker.LastBreakpoint(t.ref, 2));
			assertThat(session.finish(nextStop.get())).isTrue();
			assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
			assertThat(t.count).isEqualTo(1);
			session.releaseAll();
			assertAllRunning();
		}

		@Test
		@DisplayName("a published stop vouches for the retry: the dispatcher releasing another set meanwhile cannot move the thread")
		void publishedStopVouchesForTheRetry() throws Exception {
			presentedStop(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
			final SuspensionSession.Context stopAll = session.route(
				model.breakpointEvent(u, EventRequest.SUSPEND_ALL), u.ref, false);
			onDispatcher(() -> {
				assertThat(session.begin(stopAll)).isTrue();
				session.beginProcessing(stopAll);
				return null;
			});
			final IntegerValue fortyTwo = mock(IntegerValue.class);
			when(model.vm.mirrorOf(42)).thenReturn(fortyTwo);
			final LocalVariable count = intLocal("count");
			final StackFrame stale = frameWith("count", count);
			doThrow(new InvalidStackFrameException("Thread has been resumed")).when(stale).setValue(count, fortyTwo);
			final StackFrame fresh = frameWith("count", count);
			when(t.ref.frame(0)).thenReturn(stale).thenAnswer(invocation -> {
				onDispatcher(() -> {
					SuspensionSession.endProcessing();
					assertThat(session.finish(stopAll)).isFalse();
					return null;
				});
				return fresh;
			});

			final String reply = tools.jdwp_set_local(id(t), 0, "count", "42");

			assertThat(reply).startsWith("Variable 'count' set to 42");
			verify(fresh).setValue(count, fortyTwo);
			assertThat(t.count).as("still at its published stop").isEqualTo(1);
			session.releaseAll();
			assertAllRunning();
		}
	}

	@Nested
	@DisplayName("the owner query, then the watchdog's decision")
	class OwnerQueryAndUnblock {

		@Test
		@DisplayName("the sampled owner leaves the monitor and stops at a new breakpoint before the decision: that stop is not cancelled")
		void ownerThatStoppedElsewhereKeepsItsStop() throws Exception {
			// The dispatcher evaluates a condition on t; u, running, owns the monitor the invoked code waits for.
			final SuspensionSession.Context own = session.route(
				model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD), t.ref, false);
			onDispatcher(() -> {
				assertThat(session.begin(own)).isTrue();
				session.beginProcessing(own);
				session.lease(t.ref);
				model.beginInvocation(t);
				return null;
			});
			final AtomicReference<SuspensionSession.Context> queued = new AtomicReference<>();
			final ObjectReference monitor = mock(ObjectReference.class);
			when(monitor.owningThread()).thenAnswer(invocation -> {
				if (queued.get() != null) {
					return null;
				}
				// The gap: asked, u owns the monitor; before the session decides, u leaves it and stops at Q.
				queued.set(session.route(model.breakpointEvent(u, EventRequest.SUSPEND_EVENT_THREAD), u.ref, false));
				return u.ref;
			});
			when(t.ref.currentContendedMonitor()).thenReturn(monitor);
			t.status = ThreadReference.THREAD_STATUS_MONITOR;
			final AtomicLong now = new AtomicLong();
			final SuspensionWatchdog watchdog = new SuspensionWatchdog(session, now::get);
			watchdog.tick();
			now.addAndGet(SuspensionWatchdog.MIN_AGE_NANOS);
			watchdog.tick();
			now.addAndGet(TimeUnit.MILLISECONDS.toNanos(SuspensionWatchdog.PERIOD_MS));
			watchdog.tick();

			assertThat(queued.get()).as("the watchdog asked for the owner").isNotNull();
			assertThat(session.stateOf(queued.get())).isEqualTo(SuspensionSession.State.QUEUED);
			assertThat(u.count).isEqualTo(1);
			assertThat(types()).doesNotContain("EVENT_CANCELLED", "EVALUATION_UNBLOCKED");
			assertThat(model.invocationRuns(t)).as("the brief suspension around the query is undone").isTrue();

			// The invocation ends, the dispatcher finishes its set, then Q, which publishes.
			t.status = ThreadReference.THREAD_STATUS_RUNNING;
			onDispatcher(() -> {
				model.completeInvocation(t);
				session.unlease(t.ref);
				SuspensionSession.endProcessing();
				assertThat(session.finish(own)).isFalse();
				assertThat(session.begin(queued.get())).isTrue();
				session.candidate(queued.get(), new BreakpointTracker.LastBreakpoint(u.ref, 2));
				assertThat(session.finish(queued.get())).isTrue();
				return null;
			});
			assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
			assertThat(u.count).as("Q holds u").isEqualTo(1);
			session.releaseAll();
			assertAllRunning();
		}
	}

	@Nested
	@DisplayName("compilation, then a replaced stop")
	class CompilationAndReplacedStop {

		@Test
		@DisplayName("a job that outlived its wait while compiling, whose stop was released and replaced by a new one: its invocation is refused, the new stop untouched")
		void jobOfAReleasedStopDoesNotInvokeAtTheNextOne() throws Exception {
			presentedStop(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
			final ExecutorService pool = InvocationRunner.newPool();
			try {
				final InvocationRunner runner = new InvocationRunner(pool, 100, history, () -> session);
				final CountDownLatch compiled = new CountDownLatch(1);
				assertThatThrownBy(() -> runner.run(t.ref, "evaluating slow()", () -> {
					assertThat(compiled.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
					return invoked(t, "late value");
				})).isInstanceOf(InvocationRunner.StillRunning.class);

				// The user releases the stop; t runs on and stops at the same place again, and that
				// stop is presented — then the job finishes compiling.
				assertThat(session.releaseAll().released()).hasSize(1);
				assertThat(t.count).isZero();
				final SuspensionSession.Context replaced = presentedStop(t, EventRequest.SUSPEND_EVENT_THREAD, 1);
				compiled.countDown();

				final EventHistory.DebugEvent late = awaitEvent("EVALUATION_COMPLETED_LATE");
				assertThat(late.summary()).contains("failed: thread t was released from the stop this evaluation started at");
				assertThat(t.loans).as("nothing was invoked").isZero();
				assertThat(t.count).isEqualTo(1);
				assertThat(session.stateOf(replaced)).isEqualTo(SuspensionSession.State.HELD);
				assertThat(guard.isEvaluating(t.ref)).isFalse();
				session.releaseAll();
				assertAllRunning();
			} finally {
				pool.shutdownNow();
			}
		}
	}

	// ── helpers ──

	/**
	 * A stop published on {@code thread} and presented, as {@code jdwp_resume_until_event} shows it.
	 */
	private SuspensionSession.Context presentedStop(FakeSuspendModel.FakeThread thread, int policy, int breakpointId)
		throws Exception {
		final SuspensionSession.Context context = session.route(model.breakpointEvent(thread, policy), thread.ref, false);
		assertThat(session.begin(context)).isTrue();
		session.candidate(context, new BreakpointTracker.LastBreakpoint(thread.ref, breakpointId));
		assertThat(session.finish(context)).isTrue();
		assertThat(session.nextStop(0).outcome()).isEqualTo(SuspensionSession.Outcome.STOPPED);
		return context;
	}

	/**
	 * What the evaluator does once it has compiled: leases {@code thread} and invokes on it.
	 */
	private <V> V invoked(FakeSuspendModel.FakeThread thread, V value) throws Exception {
		try (EvaluationGuard.Lease lease = guard.lease(thread.ref)) {
			model.beginInvocation(thread);
			model.completeInvocation(thread);
		}
		return value;
	}

	private <V> V onDispatcher(Callable<V> work) throws Exception {
		return dispatcher.submit(work).get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	private static long id(FakeSuspendModel.FakeThread thread) {
		return thread.ref.uniqueID();
	}

	private static LocalVariable intLocal(String name) throws Exception {
		final LocalVariable local = mock(LocalVariable.class);
		final Type intType = mock(Type.class);
		when(local.name()).thenReturn(name);
		when(local.typeName()).thenReturn("int");
		when(local.type()).thenReturn(intType);
		when(intType.name()).thenReturn("int");
		return local;
	}

	private static StackFrame frameWith(String name, LocalVariable local) throws Exception {
		final StackFrame frame = mock(StackFrame.class);
		when(frame.visibleVariableByName(name)).thenReturn(local);
		return frame;
	}

	private EventHistory.DebugEvent awaitEvent(String type) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
		while (System.nanoTime() < deadline) {
			for (EventHistory.DebugEvent event : history.getRecent(EventHistory.MAX_EVENTS)) {
				if (event.type().equals(type)) {
					return event;
				}
			}
			Thread.sleep(10);
		}
		throw new AssertionError("no " + type + " in " + history.getRecent(EventHistory.MAX_EVENTS));
	}

	private List<String> types() {
		return history.getRecent(EventHistory.MAX_EVENTS).stream().map(EventHistory.DebugEvent::type).toList();
	}

	private void assertAllRunning() {
		assertThat(model.counts()).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
	}
}
