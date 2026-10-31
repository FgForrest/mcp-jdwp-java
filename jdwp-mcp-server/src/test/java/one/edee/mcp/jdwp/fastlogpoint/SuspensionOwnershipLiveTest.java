package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.JDWPTools;
import one.edee.mcp.jdwp.SuspensionSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.TARGET;
import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.enableSuspended;
import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.idOf;
import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.lineOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Suspension ownership (issue #45) against a real forked JVM: the watchdog frees an evaluation the
 * dispatcher is stuck in, whose monitor's owner a set still queued behind it keeps parked.
 * <p>
 * Opt-in like {@link FastLogpointLiveTest}:
 * {@code mvn -pl jdwp-mcp-server test -Djdwp.live=true -Dtest=SuspensionOwnershipLiveTest}.
 */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("suspension ownership against a live JVM")
class SuspensionOwnershipLiveTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private LiveSession session;
	private EventHistory history;
	private JDIConnectionService connection;
	private JDWPTools tools;

	@BeforeAll
	void startTargetAndAttach() throws Exception {
		session = LiveSession.start(List.of());
		history = session.history;
		connection = session.connection;
		tools = session.tools;
	}

	@AfterAll
	void stopTarget() throws Exception {
		session.close();
	}

	@Test
	@Order(1)
	@DisplayName("an evaluation blocked on a monitor whose owner a queued stop-all parks is freed by the watchdog")
	void watchdogFreesAnEvaluationStuckBehindAQueuedStop() throws Exception {
		final ThreadReference evaluating = thread("worker-0");
		final ThreadReference holder = thread("lock-holder");
		// Every read of `base` on worker-0 runs an evaluation that waits a second, then takes LOCK.
		final int logpointId = idOf(tools.jdwp_set_field_logpoint(TARGET, "base", "access",
			"{ java.util.concurrent.locks.LockSupport.parkNanos(1_000_000_000L); synchronized (" + TARGET
				+ ".LOCK) { return \"got the lock\"; } }",
			null, evaluating.uniqueID(), null, null, null, null, null));
		BreakpointRequest parkHolder = null;
		try {
			await("an evaluation in flight on worker-0", () -> sessionState().contains("leases=[worker-0]"));

			// Meanwhile a stop-all parks the holder inside its synchronized block. Its set queues behind the
			// evaluation, which then waits for LOCK: the dispatcher never gets to the set, and no tool
			// releases a set before it is processed.
			final VirtualMachine vm = connection.getVM();
			final Location holding = vm.classesByName(TARGET).get(0).locationsOfLine(lineOf("// MARK: holding")).get(0);
			parkHolder = vm.eventRequestManager().createBreakpointRequest(holding);
			parkHolder.addThreadFilter(holder);
			parkHolder.addCountFilter(1);
			parkHolder.setSuspendPolicy(EventRequest.SUSPEND_ALL);
			enableSuspended(parkHolder, holder);

			await("the watchdog to release the holder", () -> eventTypes().contains("EVALUATION_UNBLOCKED"));
			assertThat(history.getRecent(EventHistory.MAX_EVENTS)).as("the holder's stop was cancelled, not shown")
				.anyMatch(event -> event.type().equals("EVENT_CANCELLED") && event.summary().contains("lock-holder"));
			await("the evaluation to get the lock", () -> history.getRecent(EventHistory.MAX_EVENTS).stream()
				.anyMatch(event -> event.type().equals("FIELD_LOGPOINT") && event.summary().contains("got the lock")));
		} finally {
			tools.jdwp_clear_breakpoint(logpointId);
			if (parkHolder != null) {
				connection.getVM().eventRequestManager().deleteEventRequest(parkHolder);
			}
		}

		await("the dispatcher to finish its sets", this::dispatcherIdle);
		final SuspensionSession suspensions = Objects.requireNonNull(session.tracker.suspensionSession());
		assertThat(suspensions.unseenCount()).as("the cancelled stop-all published nothing").isZero();
		await("every thread running again", () -> {
			try {
				for (ThreadReference thread : connection.getVM().allThreads()) {
					if ((thread.name().startsWith("worker-") || thread == holder) && thread.suspendCount() != 0) {
						return false;
					}
				}
				return true;
			} catch (Exception e) {
				return false;
			}
		});
	}

	@Test
	@Order(2)
	@DisplayName("jdwp_resume releases the stop it holds, not the stop-all queued behind an evaluation, which is shown later with its thread stopped")
	void resumeReleasesOnlyHeldStops() throws Exception {
		final VirtualMachine vm = connection.getVM();
		final ThreadReference held = thread("worker-1");
		final ThreadReference holder = thread("lock-holder");
		final Location probe = vm.classesByName(TARGET).get(0).locationsOfLine(lineOf("// MARK: probe")).get(0);
		final BreakpointRequest heldStop = vm.eventRequestManager().createBreakpointRequest(probe);
		heldStop.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
		heldStop.addThreadFilter(held);
		heldStop.addCountFilter(1);
		final int heldId = session.tracker.registerBreakpoint(heldStop);
		enableSuspended(heldStop);
		int logpointId = -1;
		BreakpointRequest queuedStop = null;
		try {
			assertThat(tools.jdwp_resume_until_event((int) TIMEOUT.toMillis())).contains("Thread: worker-1");

			// The dispatcher is busy in a 3 s evaluation on worker-0 when a stop-all of the holder arrives.
			logpointId = idOf(tools.jdwp_set_field_logpoint(TARGET, "base", "access",
				"{ java.util.concurrent.locks.LockSupport.parkNanos(3_000_000_000L); return \"slept\"; }", null,
				thread("worker-0").uniqueID(), null, null, null, null, null));
			await("an evaluation in flight on worker-0", () -> sessionState().contains("leases=[worker-0]"));
			queuedStop = vm.eventRequestManager().createBreakpointRequest(
				vm.classesByName(TARGET).get(0).locationsOfLine(lineOf("// MARK: holding")).get(0));
			queuedStop.addThreadFilter(holder);
			queuedStop.addCountFilter(1);
			queuedStop.setSuspendPolicy(EventRequest.SUSPEND_ALL);
			enableSuspended(queuedStop, holder);
			await("the holder's stop-all to queue", () -> sessionState().contains("QUEUED"));
			assertThat(held.suspendCount()).as("its own stop and the queued stop-all").isEqualTo(2);

			assertThat(tools.jdwp_resume()).contains("worker-1");
			assertThat(held.suspendCount()).as("only the held stop was released").isEqualTo(1);
			assertThat(holder.suspendCount()).as("the queued set still holds its thread").isEqualTo(1);

			tools.jdwp_clear_breakpoint(logpointId);
			logpointId = -1;
			final String shown = tools.jdwp_resume_until_event((int) TIMEOUT.toMillis());
			assertThat(shown).contains("Thread: lock-holder").contains("suspended=true");
			assertThat(holder.isSuspended()).as("a stop is never shown for a running thread").isTrue();
		} finally {
			if (logpointId >= 0) {
				tools.jdwp_clear_breakpoint(logpointId);
			}
			if (queuedStop != null) {
				vm.eventRequestManager().deleteEventRequest(queuedStop);
			}
			tools.jdwp_clear_breakpoint(heldId);
			await("the dispatcher to finish its sets", this::dispatcherIdle);
			Objects.requireNonNull(session.tracker.suspensionSession()).reset();
		}
		await("every thread running again", () -> {
			try {
				for (ThreadReference thread : vm.allThreads()) {
					if ((thread.name().startsWith("worker-") || thread == holder) && thread.suspendCount() != 0) {
						return false;
					}
				}
				return true;
			} catch (Exception e) {
				return false;
			}
		});
	}

	@Test
	@Order(3)
	@DisplayName("jdwp_reset while an evaluation runs: every count ends at 0 and the application keeps running (#47 § 4)")
	void resetDuringAnInvocation() throws Exception {
		final ThreadReference evaluating = thread("worker-0");
		tools.jdwp_set_field_logpoint(TARGET, "base", "access",
			"{ java.util.concurrent.locks.LockSupport.parkNanos(1_500_000_000L); return \"slept\"; }", null,
			evaluating.uniqueID(), null, null, null, null, null);
		await("an evaluation in flight on worker-0", () -> sessionState().contains("leases=[worker-0]"));

		assertThat(tools.jdwp_reset()).startsWith("Reset complete (VM connection preserved).");

		await("the evaluation to end", () -> !sessionState().contains("leases=[worker-0]"));
		assertApplicationRuns();
	}

	@Test
	@Order(4)
	@DisplayName("a fast logpoint and a fast breakpoint on one line, set before either fires: one stop, both armed in the end (#47 § 4)")
	void coDeliveredArmingHits() throws Exception {
		final VirtualMachine vm = connection.getVM();
		final int line = lineOf("// MARK: probe");
		final int logpointId;
		final int breakpointId;
		// Both arming requests in place before a worker reaches the line: their hits come in one set.
		vm.suspend();
		try {
			logpointId = idOf(tools.jdwp_set_fast_logpoint(TARGET, line, "\"i=\" + i", "i % 100000 == 0", true, null,
				null));
			breakpointId = idOf(tools.jdwp_set_fast_breakpoint(TARGET, line, "i >= 0", true, "thread", true, null));
		} finally {
			vm.resume();
		}
		try {
			final String stop = tools.jdwp_resume_until_event((int) TIMEOUT.toMillis());
			assertThat(stop).contains("fast_breakpoint=" + breakpointId + ", condition true");
			assertThat(tools.jdwp_resume()).contains("Resumed:");
			await("the fast logpoint armed", () -> session.fastLogpoints.list().stream()
				.anyMatch(i -> i.id() == logpointId && i.state() == FastLogpointService.State.ARMED));
			await("in-target hits", () -> {
				tools.jdwp_get_events(1);
				return history.getRecent(EventHistory.MAX_EVENTS).stream().anyMatch(e -> "FAST_LOGPOINT".equals(e.type())
					&& !e.details().containsKey("armingHit"));
			});
		} finally {
			tools.jdwp_reset();
		}
		assertApplicationRuns();
	}

	@Test
	@Order(5)
	@DisplayName("a stop-all whose evaluation outlives its wait is released: the other threads run at once, the evaluating thread once it ends (#47 § 4)")
	void stopAllAnchorHandedToARunningEvaluation() throws Exception {
		final VirtualMachine vm = connection.getVM();
		final ThreadReference evaluating = thread("worker-0");
		final ThreadReference other = thread("worker-1");
		final ThreadReference holder = thread("lock-holder");
		final Location probe = vm.classesByName(TARGET).get(0).locationsOfLine(lineOf("// MARK: probe")).get(0);
		final BreakpointRequest stopAll = vm.eventRequestManager().createBreakpointRequest(probe);
		stopAll.setSuspendPolicy(EventRequest.SUSPEND_ALL);
		stopAll.addThreadFilter(evaluating);
		stopAll.addCountFilter(1);
		final int stopId = session.tracker.registerBreakpoint(stopAll);
		enableSuspended(stopAll);
		try {
			assertThat(tools.jdwp_resume_until_event((int) TIMEOUT.toMillis())).contains("Thread: worker-0");
			assertThat(other.suspendCount()).as("parked by the stop-all").isEqualTo(1);

			final String reply = tools.jdwp_evaluate_expression(evaluating.uniqueID(),
				"{ java.util.concurrent.locks.LockSupport.parkNanos(25_000_000_000L); return 42; }", 0);
			assertThat(reply).startsWith("[STILL_RUNNING]").contains("thread worker-0");

			// The user releases the stop-all while the evaluation still runs: worker-0's share of it now
			// belongs to the evaluation; every other thread runs at once.
			assertThat(tools.jdwp_resume()).contains("worker-0");
			assertThat(other.suspendCount()).isZero();
			assertThat(holder.suspendCount()).isZero();
			assertThat(sessionState()).as("the evaluation still runs").contains("leases=[worker-0]");
			final long before = calls();
			await("worker-1 to make progress meanwhile", () -> {
				try {
					return calls() > before;
				} catch (Exception e) {
					return false;
				}
			});

			await("the late result", () -> history.getRecent(EventHistory.MAX_EVENTS).stream()
				.anyMatch(event -> event.type().equals("EVALUATION_COMPLETED_LATE") && event.summary().contains("42")));
			assertApplicationRuns();
		} finally {
			tools.jdwp_clear_breakpoint(stopId);
		}
	}

	@Test
	@Order(6)
	@DisplayName("the watchdog cancels a queued thread stop; the thread's next stop keeps its own suspension (#47 § 4)")
	void cancelledThreadStopThenANewStop() throws Exception {
		final VirtualMachine vm = connection.getVM();
		final ThreadReference evaluating = thread("worker-0");
		final ThreadReference holder = thread("lock-holder");
		// A read of `base` on worker-0 runs an evaluation that waits a second, then takes LOCK.
		int logpointId = idOf(tools.jdwp_set_field_logpoint(TARGET, "base", "access",
			"{ java.util.concurrent.locks.LockSupport.parkNanos(1_000_000_000L); synchronized (" + TARGET
				+ ".LOCK) { return \"got the lock\"; } }",
			null, evaluating.uniqueID(), null, null, null, null, null));
		int holdingId = -1;
		try {
			await("an evaluation in flight on worker-0", () -> sessionState().contains("leases=[worker-0]"));
			// Just this one: worker-0 waits at its event until the evaluation ends, so no later read
			// raises another.
			tools.jdwp_clear_breakpoint(logpointId);
			logpointId = -1;

			// The holder stops inside its synchronized block, on every pass: a thread stop Q queued behind
			// the evaluation, which then waits for LOCK. The watchdog cancels Q; the holder runs on, leaves
			// LOCK, and stops there again — R.
			final BreakpointRequest holding = vm.eventRequestManager().createBreakpointRequest(
				vm.classesByName(TARGET).get(0).locationsOfLine(lineOf("// MARK: holding")).get(0));
			holding.addThreadFilter(holder);
			holding.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
			holdingId = session.tracker.registerBreakpoint(holding);
			enableSuspended(holding, holder);

			await("the watchdog to cancel the holder's stop", () -> history.getRecent(EventHistory.MAX_EVENTS).stream()
				.anyMatch(event -> event.type().equals("EVENT_CANCELLED") && event.summary().contains("lock-holder")));
			await("the evaluation to get the lock", () -> history.getRecent(EventHistory.MAX_EVENTS).stream()
				.anyMatch(event -> event.type().equals("FIELD_LOGPOINT") && event.summary().contains("got the lock")));

			final String shown = tools.jdwp_resume_until_event((int) TIMEOUT.toMillis());
			assertThat(shown).contains("Thread: lock-holder").contains("suspended=true");
			assertThat(holder.suspendCount()).as("R's own suspension, not spent by the cancelled Q").isEqualTo(1);
		} finally {
			if (holdingId >= 0) {
				tools.jdwp_clear_breakpoint(holdingId);
			}
			if (logpointId >= 0) {
				tools.jdwp_clear_breakpoint(logpointId);
			}
		}
		tools.jdwp_resume();
		await("the dispatcher to finish its sets", this::dispatcherIdle);
		assertApplicationRuns();
	}

	@Test
	@Order(7)
	@DisplayName("an invocation that does not return, then disconnect: the server lets go and the application runs on (#47 § 4)")
	void disconnectDuringAnInvocation() throws Exception {
		final ThreadReference evaluating = thread("worker-1");
		tools.jdwp_set_field_logpoint(TARGET, "base", "access",
			"{ java.util.concurrent.locks.LockSupport.parkNanos(3_000_000_000L); return \"slept\"; }", null,
			evaluating.uniqueID(), null, null, null, null, null);
		await("an evaluation in flight on worker-1", () -> sessionState().contains("leases=[worker-1]"));

		assertThat(tools.jdwp_disconnect()).startsWith("Disconnected from 127.0.0.1:" + session.port);

		// The target lets go of the debugger's suspensions on detach; after the evaluation's park the
		// worker runs on. Attach again to look.
		reattach();
		assertApplicationRuns();
	}

	// ── helpers ──

	/**
	 * Every worker's and the holder's suspend count is 0, and the workers make progress.
	 */
	private void assertApplicationRuns() throws Exception {
		await("every thread running again", () -> {
			try {
				for (ThreadReference thread : connection.getVM().allThreads()) {
					if ((thread.name().startsWith("worker-") || thread.name().equals("lock-holder"))
						&& thread.suspendCount() != 0) {
						return false;
					}
				}
				return true;
			} catch (Exception e) {
				return false;
			}
		});
		final long before = calls();
		await("the workers to make progress", () -> {
			try {
				return calls() > before;
			} catch (Exception e) {
				return false;
			}
		});
	}

	private long calls() throws Exception {
		final ReferenceType type = connection.getVM().classesByName(TARGET).get(0);
		final ObjectReference counter = (ObjectReference) type.getValue(type.fieldByName("CALLS"));
		return ((LongValue) counter.getValue(counter.referenceType().fieldByName("value"))).value();
	}

	/**
	 * Attaches again after a detach; the agent takes a moment to listen again.
	 */
	private void reattach() throws Exception {
		final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (true) {
			try {
				connection.connect("127.0.0.1", session.port);
				Thread.sleep(300);
				connection.getVM().allThreads();
				return;
			} catch (Exception e) {
				if (System.nanoTime() > deadline) {
					throw e;
				}
				Thread.sleep(100);
			}
		}
	}

	private ThreadReference thread(String name) throws Exception {
		return connection.getVM().allThreads().stream().filter(t -> name.equals(t.name())).findFirst().orElseThrow();
	}

	private String sessionState() {
		final SuspensionSession suspensions = session.tracker.suspensionSession();
		return suspensions == null ? "" : suspensions.describeState();
	}

	private boolean dispatcherIdle() {
		final String state = sessionState();
		return !state.contains("QUEUED") && !state.contains("PROCESSING");
	}

	private List<String> eventTypes() {
		return history.getRecent(EventHistory.MAX_EVENTS).stream().map(EventHistory.DebugEvent::type).toList();
	}

	private void await(String what, BooleanSupplier condition) throws Exception {
		final long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("timed out waiting for " + what + "; session: " + sessionState() + "; events: "
			+ history.getRecent(20));
	}
}
