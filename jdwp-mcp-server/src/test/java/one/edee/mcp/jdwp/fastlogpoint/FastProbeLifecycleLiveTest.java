package one.edee.mcp.jdwp.fastlogpoint;

import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.fastlogpoint.fixture.LateTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.idOf;
import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.lineOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ends of a fast probe's life against a real forked JVM (#48): a fast logpoint or fast
 * breakpoint set before its class is loaded waits for the class and then arms (B1), and the hits a
 * fast logpoint wrote but nobody read are read when the target exits, before the JVM goes (B2). Each
 * test forks its own target — the exit test ends it.
 * <p>
 * Opt-in like the other live tests:
 * {@code mvn -pl jdwp-mcp-server test -Djdwp.live=true -Dtest=FastProbeLifecycleLiveTest}.
 */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@DisplayName("fast probes before the class loads and at the target's exit, against a live JVM")
class FastProbeLifecycleLiveTest {

	private static final String TARGET = LiveSession.TARGET;
	private static final String LATE = LateTarget.class.getName();
	private static final Path LATE_SOURCE =
		Path.of("src/test/java/one/edee/mcp/jdwp/fastlogpoint/fixture/LateTarget.java");
	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private LiveSession session;

	@AfterEach
	void stopTarget() throws Exception {
		if (session != null) {
			session.close();
		}
	}

	@Test
	@DisplayName("a fast logpoint on a class not loaded yet waits for it, then arms and logs")
	void fastLogpointWaitsForItsClass() throws Exception {
		session = LiveSession.start(List.of());
		assertThat(session.connection.findLoadedClass(LATE)).as("not loaded before the test asks for it").isNull();

		final String reply = session.tools.jdwp_set_fast_logpoint(LATE, lineOf("// MARK: late", LATE_SOURCE),
			"\"n=\" + n", "n % 100000 == 0", true, null, null);
		assertThat(reply).contains("WAITING: " + LATE + " is not loaded yet");
		final int id = idOf(reply);
		assertThat(state(id)).contains(FastLogpointService.State.WAITING_FOR_CLASS);
		assertThat(session.tools.jdwp_overview(null, null, null)).contains("#" + id).contains("WAITING");

		session.loadLateTarget();
		await("the fast logpoint armed", () -> state(id).equals(Optional.of(FastLogpointService.State.ARMED)));
		await("in-target hits", () -> drained("FAST_LOGPOINT").stream()
			.anyMatch(e -> !e.details().containsKey("armingHit") && e.details().get("result").startsWith("n=")));
		assertThat(types()).contains("FAST_LOGPOINT_CLASS_LOADED", "FAST_LOGPOINT_ARMED")
			.doesNotContain("FAST_LOGPOINT_ARM_FAILED");

		assertThat(session.tools.jdwp_clear_breakpoint(id)).contains("original bytecode of " + LATE + " restored");
	}

	@Test
	@DisplayName("a fast breakpoint on a class not loaded yet waits for it, then stops at the user's line")
	void fastBreakpointWaitsForItsClass() throws Exception {
		session = LiveSession.start(List.of());
		final int line = lineOf("// MARK: late", LATE_SOURCE);

		final String reply = session.tools.jdwp_set_fast_breakpoint(LATE, line, "n == 300000", true, null, null, null);
		assertThat(reply).contains("WAITING: " + LATE + " is not loaded yet");
		final int id = idOf(reply);
		assertThat(session.tools.jdwp_set_fast_breakpoint(LATE, line, "n == 300000", true, null, null, null))
			.as("a second identical call does not register a duplicate").contains("already waits for");

		session.loadLateTarget();
		final String stop = session.tools.jdwp_resume_until_event(60_000);
		// The probe frames are hidden: the reply's location is the user's line.
		assertThat(stop).startsWith("Event fired at " + LATE + ':' + line).contains("Thread: late-worker")
			.contains("breakpoint=" + id);
		assertThat(types()).contains("FAST_BREAKPOINT_CLASS_LOADED");

		assertThat(session.tools.jdwp_reset()).contains("In-target probes cleared:      1");
	}

	@Test
	@DisplayName("hits nobody read are read when the target exits; the [VM_DEATH] reply says so and names a silent field watch")
	void hitsAreReadWhenTheTargetExits() throws Exception {
		session = LiveSession.start(List.of());
		// Never written after the constructor, which ran before the attach. Set first: a field watch can
		// send compiled code back to the interpreter, which slows the workers for a while.
		final String watch = session.tools.jdwp_set_field_logpoint(TARGET, "base", "modification", "$newValue",
			null, null, null, null, null, null, null);
		final int watchId = idOf(watch);
		final String reply = session.tools.jdwp_set_fast_logpoint(TARGET, lineOf("// MARK: probe"), "\"i=\" + i",
			"i % 100000 == 0", true, null, null);
		final int id = idOf(reply);
		await("the fast logpoint armed", () -> state(id).equals(Optional.of(FastLogpointService.State.ARMED)));
		// Hits accumulate in the ring; nothing reads it (no jdwp_get_events).
		awaitWritten(id);

		final CompletableFuture<String> waiter =
			CompletableFuture.supplyAsync(() -> session.tools.jdwp_resume_until_event(60_000));
		Thread.sleep(500);
		session.letTargetExit();

		assertThat(session.process.waitFor(30, TimeUnit.SECONDS)).as("the target exits: the death set was resumed")
			.isTrue();
		final String death = waiter.get(30, TimeUnit.SECONDS);
		assertThat(death).startsWith("[VM_DEATH]")
			.contains("Recorded before the exit (jdwp_get_events):")
			.contains("FAST_LOGPOINT_READ_AT_EXIT")
			.contains("Field breakpoints at the exit: #" + watchId)
			.contains("(modification, log-only) — 0 hits")
			.contains("Field.set");
		final List<EventHistory.DebugEvent> hits = session.history.getRecent(EventHistory.MAX_EVENTS).stream()
			.filter(e -> "FAST_LOGPOINT".equals(e.type()) && !e.details().containsKey("armingHit"))
			.toList();
		assertThat(hits).as("the probe's hits, read at the exit; history: %s", session.history.getRecent(40))
			.isNotEmpty()
			.allSatisfy(e -> assertThat(e.details().get("result")).startsWith("i="));
		assertThat(session.history.getRecent(EventHistory.MAX_EVENTS))
			.anySatisfy(e -> assertThat(e.type()).isEqualTo("FAST_LOGPOINT_READ_AT_EXIT"));
		assertThat(session.tools.jdwp_reset()).startsWith("Reset complete — there is no target to reset:")
			.contains("1 in-target probe");
	}

	@Test
	@DisplayName("hits nobody read are read when the target ends with System.exit, as a forked test JVM does")
	void hitsAreReadWhenTheTargetCallsSystemExit() throws Exception {
		session = LiveSession.start(List.of());
		final int id = idOf(session.tools.jdwp_set_fast_logpoint(TARGET, lineOf("// MARK: probe"), "\"i=\" + i",
			"i % 100000 == 0", true, null, null));
		await("the fast logpoint armed", () -> state(id).equals(Optional.of(FastLogpointService.State.ARMED)));
		awaitWritten(id);

		final CompletableFuture<String> waiter =
			CompletableFuture.supplyAsync(() -> session.tools.jdwp_resume_until_event(60_000));
		Thread.sleep(500);
		session.exitTarget();

		assertThat(session.process.waitFor(30, TimeUnit.SECONDS)).as("System.exit completes: the death set was resumed")
			.isTrue();
		assertThat(waiter.get(30, TimeUnit.SECONDS)).startsWith("[VM_DEATH]").contains("FAST_LOGPOINT_READ_AT_EXIT");
		assertThat(session.history.getRecent(EventHistory.MAX_EVENTS))
			.anySatisfy(e -> assertThat(e.type()).isEqualTo("FAST_LOGPOINT_READ_AT_EXIT"))
			.anySatisfy(e -> {
				assertThat(e.type()).isEqualTo("FAST_LOGPOINT");
				assertThat(e.details()).doesNotContainKey("armingHit");
			});
	}

	/**
	 * Waits until probe {@code id} has written entries — its live sequence counter, read without
	 * draining the ring.
	 */
	private void awaitWritten(int id) throws Exception {
		await("probe hits written in the target", () -> session.fastLogpoints.list().stream()
			.anyMatch(i -> i.id() == id && i.written() > 0));
	}

	private Optional<FastLogpointService.State> state(int id) {
		return session.fastLogpoints.list().stream().filter(i -> i.id() == id).findFirst()
			.map(FastLogpointService.Info::state);
	}

	/**
	 * Drains the rings (what jdwp_get_events does first) and returns every history event of {@code type}.
	 */
	private List<EventHistory.DebugEvent> drained(String type) {
		session.tools.jdwp_get_events(1);
		return session.history.getRecent(EventHistory.MAX_EVENTS).stream().filter(e -> type.equals(e.type())).toList();
	}

	private List<String> types() {
		return session.history.getRecent(EventHistory.MAX_EVENTS).stream().map(EventHistory.DebugEvent::type).toList();
	}

	private void await(String what, BooleanSupplier condition) throws Exception {
		final long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("timed out waiting for " + what + "; events: " + session.history.getRecent(20));
	}
}
