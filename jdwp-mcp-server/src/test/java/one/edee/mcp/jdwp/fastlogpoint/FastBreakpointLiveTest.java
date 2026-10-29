package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.BreakpointTracker;
import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.JDWPTools;
import one.edee.mcp.jdwp.SuspensionSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.TARGET;
import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.idOf;
import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.lineOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of {@code jdwp_set_fast_breakpoint} against a real forked JVM: the consent
 * gate, a stop-once breakpoint that parks every thread and is re-armed, an every-hit breakpoint that
 * parks only its thread, a failing condition that stops once and then installs, a first hit that
 * stops before anything is installed, restoration of the original bytecode, and an evaluation that
 * survives another thread's stop-all.
 * <p>
 * Opt-in like {@link FastLogpointLiveTest}:
 * {@code mvn -pl jdwp-mcp-server test -Djdwp.live=true -Dtest=FastBreakpointLiveTest}.
 */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("jdwp_set_fast_breakpoint against a live JVM")
class FastBreakpointLiveTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(60);
	/**
	 * How long a wait that must not end in a stop lasts.
	 */
	private static final int QUIET_MS = 2_000;

	private LiveSession session;
	private int port;
	private int probeLine;
	private EventHistory history;
	private JDIConnectionService connection;
	private FastLogpointService fastLogpoints;
	private JDWPTools tools;
	/**
	 * Bytecode of every method of the target class before anything was instrumented.
	 */
	private Map<String, byte[]> originalBytecode;

	@BeforeAll
	void startTargetAndAttach() throws Exception {
		probeLine = lineOf("// MARK: probe");
		session = LiveSession.start(List.of());
		port = session.port;
		history = session.history;
		connection = session.connection;
		fastLogpoints = session.fastLogpoints;
		tools = session.tools;
		originalBytecode = session.liveBytecode();
	}

	@AfterAll
	void stopTarget() throws Exception {
		session.close();
	}

	@AfterEach
	void leaveNothingBehind() throws Exception {
		// Release every hold, seen or not (an every-hit breakpoint can stop again before it is
		// cleared), clear, let the dispatcher finish a set it already took, and release whatever
		// stopped in between, so the next test starts from a quiet session.
		releaseEverything();
		for (FastLogpointService.Info info : fastLogpoints.list()) {
			tools.jdwp_clear_breakpoint(info.id());
		}
		await("the dispatcher to finish its sets", this::dispatcherIdle);
		releaseEverything();
		history.clear();
	}

	private void releaseEverything() {
		final SuspensionSession suspensions = session.tracker.suspensionSession();
		if (suspensions != null) {
			suspensions.reset();
		}
	}

	private boolean dispatcherIdle() {
		final SuspensionSession suspensions = session.tracker.suspensionSession();
		if (suspensions == null) {
			return true;
		}
		final String state = suspensions.describeState();
		return !state.contains("QUEUED") && !state.contains("PROCESSING");
	}

	@Test
	@Order(1)
	@DisplayName("without userApproved=true nothing is armed; invalid arguments are refused before the briefing")
	void consentGateArmsNothing() throws Exception {
		for (Boolean approval : new Boolean[]{null, false}) {
			final String reply = tools.jdwp_set_fast_breakpoint(TARGET, probeLine, "i < 0", approval, null, null, null);
			assertThat(reply)
				.startsWith("[CONSENT REQUIRED] Nothing was armed.")
				.contains(TARGET + ':' + probeLine)
				.contains("suspendPolicy: all")
				.contains("stops once")
				.contains("127.0.0.1:" + port)
				.contains("word for word");
		}
		assertThat(tools.jdwp_set_fast_breakpoint(TARGET, probeLine, "i < 0", true, "none", null, null))
			.startsWith("Error: Invalid suspend policy 'none'");
		assertThat(tools.jdwp_set_fast_breakpoint(TARGET, probeLine, " ", true, null, null, null))
			.startsWith("Error: condition is required");
		assertThat(fastLogpoints.list()).isEmpty();
		assertThat(connection.getVM().eventRequestManager().breakpointRequests()).isEmpty();
	}

	@Test
	@Order(2)
	@DisplayName("stop once: the first true hit parks every thread at the user's line, later ones run on; re-arm stops again")
	void stopOnceParksAllThreadsAndReArms() throws Exception {
		final String condition = "i % 2000000 == 1999999";
		final String reply = tools.jdwp_set_fast_breakpoint(TARGET, probeLine, condition, true, null, null, null);
		assertThat(reply).contains("PENDING").contains("stops once").contains("suspendPolicy: all");
		final int id = idOf(reply);

		final String stop = waitForStop();
		assertThat(stop)
			.startsWith("Event fired at " + TARGET + ':' + probeLine)
			.contains("fast_breakpoint=" + id + ", condition true");
		assertThat(tools.jdwp_get_current_thread()).contains("fast_breakpoint=" + id + ", condition true");
		assertThat(state(id)).isEqualTo(FastLogpointService.State.ARMED);
		final ThreadReference stopped = stoppedThread();
		assertThat(stopped.frame(0).location().declaringType().name()).contains(".McpFastBreakpoint_");
		assertThat(stopped.frame(0).location().method().name()).isEqualTo("stop");
		for (ThreadReference worker : workers()) {
			assertThat(worker.isSuspended()).as(worker.name() + " is parked by suspendPolicy=all").isTrue();
		}
		assertThat(tools.jdwp_overview("fast_breakpoint", null, null))
			.contains("Fast breakpoints (1)")
			.contains("#" + id + "  " + TARGET + ':' + probeLine + "  [ARMED, spent");

		tools.jdwp_resume();
		assertThat(tools.jdwp_resume_until_event(QUIET_MS)).as("a spent breakpoint does not stop").contains("still_waiting");
		assertThat(counters(id).stops()).isEqualTo(1);

		assertThat(tools.jdwp_set_fast_breakpoint(TARGET, probeLine, condition, true, null, null, null))
			.contains("Fast breakpoint " + id).contains("re-armed");
		assertThat(waitForStop())
			.contains("fast_breakpoint=" + id + ", condition true");
		tools.jdwp_resume();
		// stop() counts after its breakpoint, so the count follows the resume.
		await("the second stop counted", () -> counters(id).stops() == 2);

		assertThat(tools.jdwp_set_fast_breakpoint(TARGET, probeLine, "i < 0", true, null, null, null))
			.startsWith("Error: fast breakpoint " + id + " is already set");
		assertThat(tools.jdwp_clear_breakpoint(id)).contains("original bytecode");
		assertBytecodeRestored();
	}

	@Test
	@Order(3)
	@DisplayName("every hit with suspendPolicy=thread: each true hit stops, only its own thread is parked")
	void everyHitParksOnlyItsThread() throws Exception {
		final String reply = tools.jdwp_set_fast_breakpoint(TARGET, probeLine,
			"Thread.currentThread().getName().equals(\"worker-0\") && i % 3000000 == 0", true, "thread", false, null);
		assertThat(reply).contains("stops on every hit").contains("suspendPolicy: thread");
		final int id = idOf(reply);

		for (int stop = 1; stop <= 2; stop++) {
			assertThat(waitForStop())
				.contains("Thread: worker-0")
				.contains("fast_breakpoint=" + id + ", condition true");
			final long before = calls();
			Thread.sleep(200);
			assertThat(calls()).as("worker-1 keeps running while worker-0 is stopped").isGreaterThan(before);
			assertThat(worker("worker-1").isSuspended()).isFalse();
			tools.jdwp_resume();
		}
		await("both stops counted", () -> counters(id).stops() >= 2);
		assertThat(tools.jdwp_clear_breakpoint(id)).contains("original bytecode");
		assertBytecodeRestored();
	}

	@Test
	@Order(4)
	@DisplayName("a condition that throws stops once, then the probe installs and only counts the failures")
	void failingConditionStopsOnceThenInstalls() throws Exception {
		final int id = idOf(tools.jdwp_set_fast_breakpoint(TARGET, probeLine, "10 / (i - i) == 0", true, null, null,
			null));

		assertThat(waitForStop())
			.startsWith("Event fired at " + TARGET + ':' + probeLine)
			.contains("fast_breakpoint=" + id + ", condition failed:")
			.contains("ArithmeticException");
		tools.jdwp_resume();
		await("the probe installed on the next hit", () -> state(id) == FastLogpointService.State.ARMED);
		assertThat(tools.jdwp_resume_until_event(QUIET_MS)).as("later failures do not stop").contains("still_waiting");
		await("failures counted in the target", () -> counters(id).errors() > 0);
		assertThat(eventTypes()).contains("FAST_BREAKPOINT_ERROR");
		assertThat(tools.jdwp_clear_breakpoint(id)).contains("original bytecode");
		assertBytecodeRestored();
	}

	@Test
	@Order(5)
	@DisplayName("a first hit that meets the condition stops through the debugger and leaves the class untouched")
	void trueFirstHitStopsWithoutInstalling() throws Exception {
		final int id = idOf(tools.jdwp_set_fast_breakpoint(TARGET, probeLine, "i >= 0", true, null, null, null));

		assertThat(waitForStop())
			.startsWith("Event fired at " + TARGET + ':' + probeLine)
			.contains("fast_breakpoint=" + id + ", condition true");
		assertThat(stoppedThread().frame(0).location().method().name()).as("stopped in the user's frame").isEqualTo("compute");
		assertThat(state(id)).isEqualTo(FastLogpointService.State.PENDING);
		assertBytecodeRestored();
		assertThat(tools.jdwp_overview("fast_breakpoint", null, null)).contains("PENDING, spent");
		assertThat(history.getRecent(EventHistory.MAX_EVENTS)).anyMatch(e -> "FAST_BREAKPOINT".equals(e.type())
			&& "true".equals(e.details().get("armingHit")));

		tools.jdwp_resume();
		assertThat(tools.jdwp_clear_breakpoint(id)).contains("never modified");
		assertBytecodeRestored();
	}

	@Test
	@Order(6)
	@DisplayName("a fast stop is inspected at the user's frame: stack, locals, evaluation, set_local, and a step")
	void fastStopIsInspectedAtTheUsersFrame() throws Exception {
		// suspendPolicy=thread: after a step from a stop-all stop, today's blind jdwp_resume leaves the other
		// threads one suspension behind (issue #45).
		final int id = idOf(tools.jdwp_set_fast_breakpoint(TARGET, probeLine, "i % 2000000 == 1999999", true, "thread",
			null, null));
		assertThat(waitForStop())
			.contains("fast_breakpoint=" + id + ", condition true");
		final long threadId = stoppedThread().uniqueID();

		assertThat(tools.jdwp_get_stack(threadId, null, null))
			.contains("frame(s) of fast-breakpoint probe")
			.contains("compute");
		assertThat(tools.jdwp_get_locals(threadId, 0)).contains("i (int) = ").contains("doubled (int) = ");
		assertThat(tools.jdwp_evaluate_expression(threadId, "i % 2000000", 0)).isEqualTo("Result of i % 2000000: 1999999");
		assertThat(tools.jdwp_set_local(threadId, 0, "doubled", "-1")).contains("set to -1");

		assertThat(tools.jdwp_step_over(threadId)).contains("first lands on the stop line");
		// A step is in flight: the wait releases nothing and returns its landing.
		assertThat(waitForStop())
			.startsWith("Event fired at " + TARGET + ':' + probeLine)
			.contains("via=step");
		assertThat(tools.jdwp_get_locals(threadId, 0)).as("the step stayed in the user's frame").contains("doubled (int) = -1");
	}

	@Test
	@Order(7)
	@DisplayName("an evaluation in flight on one thread survives another thread's stop-all (issue #45)")
	void evaluationSurvivesAnotherThreadsStopAll() throws Exception {
		final ThreadReference slow = worker("worker-0");
		final ThreadReference stopper = worker("worker-1");
		// Every read of `base` on worker-0 runs an evaluation of 1.5 s on the dispatcher.
		final int logpointId = idOf(tools.jdwp_set_field_logpoint(TARGET, "base", "access",
			"{ java.util.concurrent.locks.LockSupport.parkNanos(1_500_000_000L); return \"slept\"; }", null,
			slow.uniqueID(), null, null, null, null, null));
		BreakpointRequest stopAll = null;
		try {
			// The first evaluation compiles; the next one spends its time parked in the target.
			await("a first field logpoint entry", () -> history.getRecent(50).stream()
				.anyMatch(event -> event.type().equals("FIELD_LOGPOINT")));
			await("an evaluation in flight on worker-0", () -> sessionState().contains("leases=[worker-0]"));

			// A stop-all on worker-1 suspends worker-0 too, in the middle of that evaluation. Without the
			// lease's exemption the evaluation never completes and the dispatcher never gets to the stop.
			final VirtualMachine vm = connection.getVM();
			final Location location = vm.classesByName(TARGET).get(0).locationsOfLine(probeLine).get(0);
			stopAll = vm.eventRequestManager().createBreakpointRequest(location);
			stopAll.addThreadFilter(stopper);
			stopAll.setSuspendPolicy(EventRequest.SUSPEND_ALL);
			stopAll.enable();

			assertThat(waitForStop()).contains("Thread: worker-1");
			assertThat(sessionState()).as("the stop-all landed during the evaluation and took worker-0 out of it")
				.doesNotContain("routeExemptions=0");
			assertThat(history.getRecent(EventHistory.MAX_EVENTS)).as("the evaluation itself succeeded too")
				.noneMatch(event -> event.type().equals("FIELD_LOGPOINT_ERROR"));
		} finally {
			if (stopAll != null) {
				connection.getVM().eventRequestManager().deleteEventRequest(stopAll);
			}
			tools.jdwp_clear_breakpoint(logpointId);
		}
	}

	// ── helpers ──

	/**
	 * {@code jdwp_resume_until_event} that, when no stop arrives, fails with the state needed to
	 * tell why: the target's threads and their suspend counts, the server's threads, the session.
	 */
	private String waitForStop() throws Exception {
		final String reply = tools.jdwp_resume_until_event((int) TIMEOUT.toMillis());
		if (reply.startsWith("Event fired")) {
			return reply;
		}
		final StringBuilder state = new StringBuilder(reply).append("\n── session ──\n");
		final SuspensionSession suspensions = session.tracker.suspensionSession();
		state.append(suspensions != null ? suspensions.describeState() : "none").append("\n── workers ──\n");
		for (ThreadReference worker : workers()) {
			state.append(worker.name()).append(" suspendCount=").append(worker.suspendCount())
				.append(" status=").append(worker.status()).append('\n');
		}
		state.append("── compute() progress ──\n");
		final long before = calls();
		Thread.sleep(500);
		state.append("CALLS +").append(calls() - before).append(" in 500 ms\n");
		state.append("── breakpoint requests ──\n");
		final VirtualMachine vm = connection.getVM();
		for (com.sun.jdi.request.BreakpointRequest request : vm.eventRequestManager().breakpointRequests()) {
			state.append(request.location()).append(" enabled=").append(request.isEnabled())
				.append(" policy=").append(request.suspendPolicy())
				.append(" obsoleteMethod=").append(request.location().method().isObsolete()).append('\n');
		}
		state.append("── target jstack ──\n");
		try {
			final Process jstack = new ProcessBuilder(
				java.nio.file.Path.of(System.getProperty("java.home"), "bin", "jstack").toString(),
				String.valueOf(session.process.pid())).redirectErrorStream(true).start();
			state.append(new String(jstack.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
		} catch (Exception e) {
			state.append("jstack failed: ").append(e).append('\n');
		}
		state.append("── server threads ──\n");
		Thread.getAllStackTraces().forEach((thread, stack) -> {
			if (thread.getName().startsWith("jdi-") || thread.getName().equals("main")) {
				state.append(thread.getName()).append(" (").append(thread.getState()).append(")\n");
				for (StackTraceElement frame : stack) {
					state.append("    at ").append(frame).append('\n');
				}
			}
		});
		throw new AssertionError(state.toString());
	}

	private FastLogpointService.Info info(int id) {
		return fastLogpoints.list().stream().filter(i -> i.id() == id).findFirst()
			.orElseThrow(() -> new AssertionError("fast breakpoint " + id + " is not listed"));
	}

	private FastLogpointService.State state(int id) {
		return info(id).state();
	}

	private FastLogpointService.BreakpointCounters counters(int id) {
		return Objects.requireNonNull(info(id).counters());
	}

	private ThreadReference stoppedThread() {
		final BreakpointTracker.LastBreakpoint snapshot = session.tracker.getLastBreakpoint();
		assertThat(snapshot).isNotNull();
		return Objects.requireNonNull(snapshot).thread();
	}

	private List<ThreadReference> workers() throws Exception {
		return connection.getVM().allThreads().stream().filter(t -> t.name().startsWith("worker-")).toList();
	}

	private String sessionState() {
		final SuspensionSession suspensions = session.tracker.suspensionSession();
		return suspensions == null ? "" : suspensions.describeState();
	}

	private ThreadReference worker(String name) throws Exception {
		return workers().stream().filter(t -> name.equals(t.name())).findFirst().orElseThrow();
	}

	private List<String> eventTypes() {
		return history.getRecent(EventHistory.MAX_EVENTS).stream().map(EventHistory.DebugEvent::type).toList();
	}

	private long calls() throws Exception {
		final VirtualMachine vm = connection.getVM();
		final ReferenceType type = vm.classesByName(TARGET).get(0);
		final ObjectReference counter = (ObjectReference) type.getValue(type.fieldByName("CALLS"));
		return ((LongValue) counter.getValue(counter.referenceType().fieldByName("value"))).value();
	}

	private void assertBytecodeRestored() throws Exception {
		assertThat(session.liveBytecode()).as("live bytecode equals the pre-instrumentation bytecode")
			.containsOnlyKeys(originalBytecode.keySet())
			.allSatisfy((method, code) -> assertThat(code).as(method).isEqualTo(originalBytecode.get(method)));
	}

	private void await(String what, BooleanSupplier condition) throws Exception {
		final long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("timed out waiting for " + what + "; events: " + history.getRecent(20));
	}
}
