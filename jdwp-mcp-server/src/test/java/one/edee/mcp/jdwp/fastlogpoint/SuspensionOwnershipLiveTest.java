package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.Location;
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
			parkHolder.enable();

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

	// ── helpers ──

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
