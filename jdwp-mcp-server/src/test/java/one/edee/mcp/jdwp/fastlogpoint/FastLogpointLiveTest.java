package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.*;
import one.edee.mcp.jdwp.fastlogpoint.fixture.FastLogpointTarget;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.idOf;
import static one.edee.mcp.jdwp.fastlogpoint.LiveSession.lineOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of {@code jdwp_set_fast_logpoint} against a real forked JVM: arming on the
 * first hit, in-target evaluation without suspending, breakpoint survival across redefinition, the
 * bytecode guard, buffer-overrun reporting, and restoration of the original bytecode by clear,
 * reset and disconnect. Also prints the per-hit cost against {@code jdwp_set_logpoint}.
 * <p>
 * Forks a JVM and attaches over JDWP, which the test suite otherwise never does (see
 * {@code docs/testing.md}), so it is opt-in:
 * {@code mvn -pl jdwp-mcp-server test -Djdwp.live=true -Dtest=FastLogpointLiveTest}.
 */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("jdwp_set_fast_logpoint against a live JVM")
class FastLogpointLiveTest {

	private static final String TARGET = LiveSession.TARGET;
	private static final Duration ARM_TIMEOUT = Duration.ofSeconds(60);

	private LiveSession session;
	private int port;
	private int probeLine;
	private int otherLine;
	private EventHistory history;
	private BreakpointTracker tracker;
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
		otherLine = lineOf("// MARK: other");
		session = LiveSession.start(List.of());
		port = session.port;
		history = session.history;
		tracker = session.tracker;
		connection = session.connection;
		fastLogpoints = session.fastLogpoints;
		tools = session.tools;
		originalBytecode = liveBytecode();
	}

	@AfterAll
	void stopTarget() throws Exception {
		session.close();
	}

	@Test
	@Order(1)
	@DisplayName("without userApproved=true nothing is armed and the risk briefing names the target")
	void consentGateArmsNothing() throws Exception {
		for (Boolean approval : new Boolean[]{null, false}) {
			final String reply = tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", null, approval, null, null);
			assertThat(reply)
				.startsWith("[CONSENT REQUIRED] Nothing was armed.")
				.contains(TARGET + ':' + probeLine)
				.contains("127.0.0.1:" + port)
				.contains("word for word");
		}
		assertThat(fastLogpoints.list()).isEmpty();
		assertThat(connection.getVM().eventRequestManager().breakpointRequests()).isEmpty();
	}

	@Test
	@Order(2)
	@DisplayName("arms on the first hit, logs in the target without suspending, keeps breakpoints in the class")
	void armsAndLogsWithoutSuspending() throws Exception {
		// A disabled breakpoint elsewhere in the same class: redefinition deletes it in JDI, the
		// service must re-create it — still disabled — on the new bytecode.
		final int breakpointId = setDisabledBreakpoint(otherLine);

		final String reply = tools.jdwp_set_fast_logpoint(TARGET, probeLine,
			"\"i=\" + i + \" doubled=\" + doubled + \" base=\" + base + \" label=\" + label",
			"i % 100000 == 0", true, null, null);
		assertThat(reply).contains("PENDING");
		final int id = idOf(reply);
		awaitArmed(id);
		assertThat(eventTypes()).contains("FAST_LOGPOINT_ARMED").doesNotContain("FAST_LOGPOINT_ARM_FAILED");

		await("fast-logpoint hits", () -> drainedEvents("FAST_LOGPOINT").stream()
			.anyMatch(e -> !e.details().containsKey("armingHit")));
		final Pattern shape = Pattern.compile("i=(\\d+) doubled=(\\d+) base=7 label=v(\\d+)");
		final List<EventHistory.DebugEvent> hits = drainedEvents("FAST_LOGPOINT").stream()
			.filter(e -> !e.details().containsKey("armingHit"))
			.toList();
		for (EventHistory.DebugEvent hit : hits) {
			final Matcher m = shape.matcher(hit.details().get("result"));
			assertThat(m.matches()).as(hit.summary()).isTrue();
			final long i = Long.parseLong(m.group(1));
			assertThat(i % 100000).isZero();
			assertThat(Long.parseLong(m.group(2))).isEqualTo(2 * i);
			assertThat(hit.details()).containsKeys("seq", "nanoTime", "thread");
		}

		// No JDWP traffic from the line: the arming request is gone and no worker is suspended.
		final VirtualMachine vm = connection.getVM();
		assertThat(vm.eventRequestManager().breakpointRequests())
			.noneMatch(r -> r.location().lineNumber() == probeLine);
		for (ThreadReference thread : vm.allThreads()) {
			if (thread.name().startsWith("worker-")) {
				assertThat(thread.isSuspended()).as(thread.name()).isFalse();
			}
		}

		final BreakpointRequest recreated = tracker.getBreakpoint(breakpointId);
		assertThat(recreated).isNotNull();
		assertThat(vm.eventRequestManager().breakpointRequests()).contains(recreated);
		assertThat(recreated.isEnabled()).isFalse();
		assertThat(recreated.location().lineNumber()).isEqualTo(otherLine);
	}

	@Test
	@Order(3)
	@DisplayName("a breakpoint re-created on the instrumented bytecode still fires")
	void recreatedBreakpointStillFires() throws Exception {
		final int breakpointId = tracker.getAllBreakpoints().keySet().iterator().next();
		tracker.setBreakpointEnabledById(breakpointId, true);
		try {
			final String reply = tools.jdwp_resume_until_event(20_000);
			assertThat(reply).contains("Event fired").contains(TARGET);
		} finally {
			tracker.setBreakpointEnabledById(breakpointId, false);
			tools.jdwp_resume();
		}
	}

	@Test
	@Order(4)
	@DisplayName("an exception in the expression is recorded and never reaches the application")
	void expressionExceptionIsRecorded() throws Exception {
		final String reply = tools.jdwp_set_fast_logpoint(TARGET, otherLine, "10 / (i - i)", "i % 5000 == 0",
			true, null, null);
		final int id = idOf(reply);
		awaitArmed(id);
		final long before = calls();
		await("expression failure entries", () -> drainedEvents("FAST_LOGPOINT_ERROR").stream()
			.anyMatch(e -> e.summary().contains("ArithmeticException") && !e.details().containsKey("armingHit")));
		Thread.sleep(200);
		assertThat(calls()).as("the application keeps running").isGreaterThan(before);
	}

	@Test
	@Order(5)
	@DisplayName("entries overwritten before they are read are reported as dropped")
	void overrunIsReported() throws Exception {
		final String reply = tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", null, true, 16, null);
		final int id = idOf(reply);
		awaitArmed(id);
		Thread.sleep(500);
		await("a FAST_LOGPOINT_DROPPED event", () -> drainedEvents("FAST_LOGPOINT_DROPPED").stream()
			.anyMatch(e -> e.summary().contains("[FastLogpoint " + id + "]") && e.summary().contains("buffer overrun")));
		assertThat(tools.jdwp_clear_breakpoint(id)).contains("original bytecode");
	}

	@Test
	@Order(6)
	@DisplayName("clearing the last fast logpoint restores the original bytecode and keeps the breakpoint")
	void clearRestoresOriginalBytecode() throws Exception {
		final List<String> probes = new ArrayList<>();
		for (ReferenceType type : connection.getVM().allClasses()) {
			if (type.name().contains(".McpFastLogpoint_")) {
				probes.add(type.name());
			}
		}
		for (FastLogpointService.Info info : fastLogpoints.list()) {
			assertThat(tools.jdwp_clear_breakpoint(info.id())).contains("original bytecode");
		}
		assertBytecodeRestored();
		// The probe classes stay loaded; their rings must not keep entries reachable.
		assertThat(probes).isNotEmpty();
		for (String probe : probes) {
			final ReferenceType type = connection.getVM().classesByName(probe).get(0);
			final ArrayReference ring = (ArrayReference) type.getValue(type.fieldByName("RING"));
			assertThat(ring.getValues()).as(probe).allMatch(Objects::isNull);
		}
		assertThat(eventTypes()).contains("FAST_LOGPOINT_DISARMED").doesNotContain("FAST_LOGPOINT_WARNING");
		final BreakpointRequest survivor = tracker.getAllBreakpoints().values().iterator().next();
		assertThat(connection.getVM().eventRequestManager().breakpointRequests()).contains(survivor);
		assertThat(survivor.location().lineNumber()).isEqualTo(otherLine);
		assertThat(survivor.isEnabled()).isFalse();
	}

	@Test
	@Order(7)
	@DisplayName("jdwp_reset, jdwp_disconnect and jdwp_reconnect restore the original bytecode")
	void resetAndDisconnectRestoreBytecode() throws Exception {
		awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i < 0", true, null, null)));
		assertThat(tools.jdwp_reset()).contains("Fast logpoints cleared:        1");
		assertBytecodeRestored();

		awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i < 0", true, null, null)));
		connection.disconnect();
		reattach();
		assertBytecodeRestored();

		awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i < 0", true, null, null)));
		// The reattach races the agent's re-listen after the dispose; the tool's contract is to retry.
		String reconnected = tools.jdwp_reconnect();
		final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (reconnected.contains("Connection refused") && System.nanoTime() < deadline) {
			Thread.sleep(100);
			reconnected = tools.jdwp_reconnect();
		}
		assertThat(reconnected).startsWith("Reconnected to");
		Thread.sleep(300);
		assertBytecodeRestored();
		assertThat(fastLogpoints.list()).as("fast logpoints are not carried across a reconnect").isEmpty();
	}

	@Test
	@Order(8)
	@DisplayName("arming is refused when the live bytecode differs from the class file")
	void guardRefusesForeignRedefinition() throws Exception {
		final VirtualMachine vm = connection.getVM();
		final ReferenceType type = vm.classesByName(TARGET).get(0);
		final byte[] classFile = Files.readAllBytes(Path.of(FastLogpointTarget.class.getProtectionDomain()
			.getCodeSource().getLocation().toURI()).resolve(TARGET.replace('.', '/') + ".class"));
		// Stand-in for another agent: a NOP at the start of other() changes the live bytecode.
		vm.redefineClasses(Map.of(type, withLeadingNop(classFile, "other")));
		try {
			final int id = idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", null, true, null, null));
			await("FAST_LOGPOINT_ARM_FAILED", () -> history.getRecent(EventHistory.MAX_EVENTS).stream().anyMatch(e ->
				"FAST_LOGPOINT_ARM_FAILED".equals(e.type()) && e.summary().contains("[FastLogpoint " + id + "]")));
			assertThat(history.getRecent(EventHistory.MAX_EVENTS)).anyMatch(e ->
				"FAST_LOGPOINT_ARM_FAILED".equals(e.type()) && e.summary().contains("[FastLogpoint " + id + "]")
					&& e.summary().contains("differ from the live bytecode"));
			assertThat(fastLogpoints.list()).singleElement()
				.satisfies(info -> assertThat(info.state()).isEqualTo(FastLogpointService.State.FAILED));
			assertThat(tools.jdwp_clear_breakpoint(id)).contains("never modified");
		} finally {
			vm.redefineClasses(Map.of(type, classFile));
		}
	}

	@Test
	@Order(9)
	@DisplayName("arms through another thread while one thread is parked on the same line")
	void armsWhileAnotherThreadIsParkedOnTheLine() throws Exception {
		final VirtualMachine vm = connection.getVM();
		final ThreadReference parked = vm.allThreads().stream()
			.filter(t -> "worker-0".equals(t.name())).findFirst().orElseThrow();
		final Location location = vm.classesByName(TARGET).get(0).locationsOfLine(probeLine).get(0);
		final BreakpointRequest park = vm.eventRequestManager().createBreakpointRequest(location);
		park.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
		park.addThreadFilter(parked);
		park.addCountFilter(1);
		final int parkId = tracker.registerBreakpoint(park);
		park.enable();
		try {
			await("worker-0 parked on the probe line", parked::isSuspended);
			final int id = idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i < 0", true, null, null));
			awaitArmed(id);
			assertThat(tools.jdwp_clear_breakpoint(id)).contains("original bytecode");
		} finally {
			tools.jdwp_clear_breakpoint(parkId);
			parked.resume();
		}
		assertBytecodeRestored();
	}

	@Test
	@Order(10)
	@DisplayName("per-hit cost: fast logpoint vs jdwp_set_logpoint, both with a condition that is never true")
	void measuresPerHitCost() throws Exception {
		Thread.sleep(1000); // the guard test just redefined the class back — let the JIT recompile it
		final double baseline = callsPerSecond();

		final String slowReply = tools.jdwp_set_logpoint(TARGET, probeLine, "i", "i < 0", null);
		final int slowId = idOf(slowReply);
		Thread.sleep(1000); // first hits pay the one-time compile
		final double slow = callsPerSecond();
		tools.jdwp_clear_breakpoint(slowId);

		final int fastId = idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i < 0", true, null, null));
		awaitArmed(fastId);
		Thread.sleep(1000); // let the JIT recompile the instrumented method
		final double fast = callsPerSecond();
		tools.jdwp_clear_breakpoint(fastId);
		Thread.sleep(1000);
		final double baselineAfter = callsPerSecond();

		System.out.printf(Locale.ROOT, "%n[fast-logpoint spike] compute() calls/s across 2 worker threads:%n"
				+ "  no logpoint (before) ... %,14.0f  (%.1f ns/call/thread)%n"
				+ "  jdwp_set_logpoint ...... %,14.0f  (%.1f us/call/thread)%n"
				+ "  jdwp_set_fast_logpoint . %,14.0f  (%.1f ns/call/thread)%n"
				+ "  no logpoint (after) .... %,14.0f  (%.1f ns/call/thread)%n",
			baseline, 2e9 / baseline, slow, 2e6 / slow, fast, 2e9 / fast, baselineAfter, 2e9 / baselineAfter);
		assertThat(fast).as("fast logpoint throughput vs suspend-per-hit logpoint").isGreaterThan(slow * 10);
	}

	@Test
	@Order(11)
	@DisplayName("arming is refused for a class JaCoCo instrumented (needs -Djdwp.live.jacocoAgent=<runtime jar>)")
	void guardRefusesJacocoInstrumentedClass() throws Exception {
		final String agent = System.getProperty("jdwp.live.jacocoAgent");
		Assumptions.assumeTrue(agent != null && !agent.isBlank(),
			"set -Djdwp.live.jacocoAgent to the path of org.jacoco.agent-<version>-runtime.jar");
		try (LiveSession jacoco = LiveSession.start(List.of("-javaagent:" + agent + "=output=none"))) {
			final int id = idOf(jacoco.tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", null, true, null, null));
			final long deadline = System.nanoTime() + ARM_TIMEOUT.toNanos();
			while (jacoco.fastLogpoints.list().get(0).state() == FastLogpointService.State.PENDING
				&& System.nanoTime() < deadline) {
				Thread.sleep(50);
			}
			final FastLogpointService.Info info = jacoco.fastLogpoints.list().get(0);
			assertThat(info.state()).isEqualTo(FastLogpointService.State.FAILED);
			assertThat(info.failureReason()).contains("differ from the live bytecode").contains("JaCoCo");
			assertThat(jacoco.tools.jdwp_clear_breakpoint(id)).contains("never modified");
		}
	}

	@Test
	@Order(12)
	@DisplayName("a third fast logpoint arms after the second one made HotSpot renumber the constant pool")
	void thirdArmAfterConstantPoolRemap() throws Exception {
		try {
			awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, otherLine, "i", "i < 0", true, null, null)));
			// compute() precedes other() in the class, so this probe's constant-pool entries come first
			// in the instrumented class file and HotSpot renumbers them against the live pool.
			awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i < 0", true, null, null)));
			awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "-i", "i < 0", true, null, null)));
		} finally {
			for (FastLogpointService.Info info : fastLogpoints.list()) {
				tools.jdwp_clear_breakpoint(info.id());
			}
		}
		assertBytecodeRestored();
	}

	@Test
	@Order(13)
	@DisplayName("fast logpoints of a server that died are listed on the next attach, disabled, and restored on approval")
	void leftoversAreRecoveredAndRestoredOnApproval() throws Exception {
		final int first = idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i % 100000 == 0", true, null, null));
		awaitArmed(first);
		awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, otherLine, "i", "i < 0", true, null, null)));
		simulateServerCrash();

		final String notice = connection.takeAttachNotice();
		assertThat(notice).contains("FAST LOGPOINTS LEFT BY AN EARLIER SESSION — 2 found").contains("RECOVERED");
		final List<FastLogpointService.Info> leftovers = fastLogpoints.list();
		assertThat(leftovers).hasSize(2).allSatisfy(info -> {
			assertThat(info.state()).isEqualTo(FastLogpointService.State.RECOVERED);
			assertThat(info.enabled()).isTrue();
			assertThat(Objects.requireNonNull(info.provenance()).armedBy()).startsWith("mcp-jdwp-java, session ");
		});
		assertThat(eventTypes()).contains("FAST_LOGPOINT_RECOVERED");
		final int recovered = leftovers.stream().filter(info -> info.line() == probeLine).findFirst().orElseThrow().id();
		// Hits written after the attach are drained under the new ID.
		await("hits of the recovered fast logpoint", () -> drainedEvents("FAST_LOGPOINT").stream()
			.anyMatch(e -> String.valueOf(recovered).equals(e.details().get("breakpointId"))));

		assertThat(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", null, true, null, null))
			.contains("still carries probes left by an earlier debugging session");
		assertThat(tools.jdwp_clear_breakpoint(recovered)).contains("disabled").contains("jdwp_restore_fast_logpoints");
		assertThat(fastLogpoints.list()).filteredOn(info -> info.id() == recovered).singleElement()
			.satisfies(info -> assertThat(info.enabled()).isFalse());

		final String briefing = tools.jdwp_restore_fast_logpoints(recovered, null);
		assertThat(briefing).startsWith("RESTORE NOT PERFORMED").contains("line " + probeLine).contains("line " + otherLine);
		assertThat(fastLogpoints.list()).hasSize(2);

		assertThat(tools.jdwp_restore_fast_logpoints(recovered, true)).startsWith("Restored the class file of " + TARGET);
		assertThat(fastLogpoints.list()).isEmpty();
		assertBytecodeRestored();

		connection.disconnect();
		reattach();
		assertThat(connection.takeAttachNotice()).as("nothing is left after the restore").isEmpty();
		assertThat(fastLogpoints.list()).isEmpty();
	}

	@Test
	@Order(14)
	@DisplayName("a leftover whose class changed afterwards is STRANDED: only its kill switch is offered")
	void changedClassStrandsLeftovers() throws Exception {
		// The class no longer matches what the arming session recorded (stand-in: a tampered record).
		awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i < 0", true, null, null)));
		tamperInstallRecords();
		simulateServerCrash();
		final FastLogpointService.Info stranded = fastLogpoints.list().get(0);
		assertThat(fastLogpoints.list()).hasSize(1);
		assertThat(stranded.state()).isEqualTo(FastLogpointService.State.STRANDED);
		assertThat(stranded.failureReason()).contains("differs from what the arming session recorded");
		assertThat(tools.jdwp_restore_fast_logpoints(stranded.id(), true)).startsWith("Not restored");
		assertThat(tools.jdwp_clear_breakpoint(stranded.id())).contains("disabled").contains("until the JVM restarts");
		assertThat(fastLogpoints.list()).singleElement().satisfies(info -> assertThat(info.enabled()).isFalse());

		// Whoever changed the class redefines it back: the disabled probe is no longer called — inert, not listed.
		redefineTargetWithClassFile();
		simulateServerCrash();
		assertThat(fastLogpoints.list()).isEmpty();

		// A redefinition dropped the call while the probe was still enabled: listed until disabled, then retired.
		awaitArmed(idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "i", "i < 0", true, null, null)));
		redefineTargetWithClassFile();
		simulateServerCrash();
		final FastLogpointService.Info unwired = fastLogpoints.list().get(0);
		assertThat(fastLogpoints.list()).hasSize(1);
		assertThat(unwired.state()).isEqualTo(FastLogpointService.State.STRANDED);
		assertThat(unwired.failureReason()).contains("no longer called");
		assertThat(tools.jdwp_clear_breakpoint(unwired.id())).contains("disabled and retired");
		assertThat(fastLogpoints.list()).isEmpty();
		assertBytecodeRestored();
	}

	@Test
	@Order(15)
	@DisplayName("a breakpoint hit inside a method the debugger invokes is suppressed instead of hanging the debugger")
	void breakpointInsideInvokedCodeDoesNotHang() throws Exception {
		// A logpoint that never logs: from now on every call of other(i) raises a breakpoint event.
		final int otherId = idOf(tools.jdwp_set_logpoint(TARGET, otherLine, "i", "i < 0", null));
		try {
			// Evaluating this logpoint calls other(i) on the very thread the debugger invokes it on.
			final int callingId = idOf(tools.jdwp_set_logpoint(TARGET, probeLine, "this.other(i)", null, null));
			await("a logpoint whose evaluation called other(i)", () -> history.getRecent(EventHistory.MAX_EVENTS)
				.stream().anyMatch(e -> "LOGPOINT".equals(e.type()) && "this.other(i)".equals(e.details().get("expression"))));
			tools.jdwp_clear_breakpoint(callingId);
			assertThat(suppressedAtOtherLine()).isTrue();

			// Arming evaluates its first hit through the debugger, which calls other(i) as well.
			history.clear();
			final int fastId = idOf(tools.jdwp_set_fast_logpoint(TARGET, probeLine, "this.other(i)", null, true, null, null));
			awaitArmed(fastId);
			assertThat(suppressedAtOtherLine()).isTrue();
			assertThat(tools.jdwp_clear_breakpoint(fastId)).contains("original bytecode");
		} finally {
			tools.jdwp_clear_breakpoint(otherId);
		}
		assertBytecodeRestored();
	}

	// ── helpers ──

	/**
	 * Registers a tracked, still-disabled thread-suspending breakpoint — created disabled so no worker
	 * hits it before the test decides to enable it.
	 */
	private int setDisabledBreakpoint(int line) throws Exception {
		final VirtualMachine vm = connection.getVM();
		final Location location = vm.classesByName(TARGET).get(0).locationsOfLine(line).get(0);
		final BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(location);
		request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
		return tracker.registerBreakpoint(request);
	}

	/**
	 * What a SIGKILL of the server leaves behind: the instrumentation stays in the target, the
	 * server's state is gone, and the next server attaches afresh (and runs discovery).
	 */
	private void simulateServerCrash() throws Exception {
		fastLogpoints.forgetAll();
		connection.disconnect();
		reattach();
	}

	/**
	 * Stand-in for "the class changed after the install": every install record's snapshot gets one
	 * extra byte, so it no longer equals the live code.
	 */
	private void tamperInstallRecords() throws Exception {
		final VirtualMachine vm = connection.getVM();
		for (ReferenceType type : vm.allClasses()) {
			if (!type.name().contains(".McpFastLogpoint_") || !(type instanceof ClassType probe)) {
				continue;
			}
			final Field imageField = probe.fieldByName("MCP_IMAGE");
			if (!(probe.getValue(imageField) instanceof ArrayReference image)) {
				continue;
			}
			final byte[] encoded = new byte[image.length()];
			final List<Value> values = image.getValues();
			for (int i = 0; i < encoded.length; i++) {
				encoded[i] = ((ByteValue) values.get(i)).value();
			}
			final GenerationRecord record = GenerationRecord.decode(encoded);
			final Map<String, byte[]> installed = new HashMap<>(record.installed());
			installed.replaceAll((method, code) -> Arrays.copyOf(code, code.length + 1));
			final byte[] tampered = new GenerationRecord(record.targetClass(), record.original(), record.probes(),
				installed).encode();
			final ArrayReference copy = ((ArrayType) vm.classesByName("byte[]").get(0)).newInstance(tampered.length);
			final List<Value> mirrors = new ArrayList<>();
			for (byte b : tampered) {
				mirrors.add(vm.mirrorOf(b));
			}
			copy.setValues(mirrors);
			probe.setValue(imageField, copy);
		}
	}

	/**
	 * Stand-in for someone else redefining the target class: back to its class file.
	 */
	private void redefineTargetWithClassFile() throws Exception {
		final VirtualMachine vm = connection.getVM();
		vm.redefineClasses(Map.of(vm.classesByName(TARGET).get(0), targetClassFile()));
	}

	private static byte[] targetClassFile() throws Exception {
		return Files.readAllBytes(Path.of(FastLogpointTarget.class.getProtectionDomain()
			.getCodeSource().getLocation().toURI()).resolve(TARGET.replace('.', '/') + ".class"));
	}

	/**
	 * Re-attaches after a detach. The target's JDWP agent takes a moment to listen again, and an
	 * attach that lands while it is still tearing down the previous session is dropped right away —
	 * so a connection only counts once it is still answering a little later.
	 */
	private void reattach() throws Exception {
		final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (true) {
			try {
				connection.connect("127.0.0.1", port);
				Thread.sleep(300);
				connection.getVM().allThreads();
				return;
			} catch (Exception notYet) {
				if (System.nanoTime() > deadline) {
					throw notYet;
				}
				Thread.sleep(100);
			}
		}
	}

	private void awaitArmed(int id) throws Exception {
		final long deadline = System.nanoTime() + ARM_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			final Optional<FastLogpointService.Info> info = fastLogpoints.list().stream()
				.filter(i -> i.id() == id).findFirst();
			if (info.isPresent() && info.get().state() == FastLogpointService.State.ARMED) {
				return;
			}
			if (info.isPresent() && info.get().state() == FastLogpointService.State.FAILED) {
				throw new AssertionError("fast logpoint " + id + " failed to arm: " + info.get().failureReason());
			}
			Thread.sleep(50);
		}
		throw new AssertionError("fast logpoint " + id + " not armed within " + ARM_TIMEOUT + "; events: "
			+ history.getRecent(20) + "\n" + threadDumps());
	}

	/**
	 * Thread dumps of the target (jstack) and of this JVM, for diagnosing a stuck arm.
	 */
	private String threadDumps() {
		final StringBuilder out = new StringBuilder("── target threads ──\n");
		try {
			final Process jstack = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "jstack").toString(),
				String.valueOf(session.process.pid())).redirectErrorStream(true).start();
			out.append(new String(jstack.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
		} catch (Exception e) {
			out.append("jstack failed: ").append(e).append('\n');
		}
		out.append("── test JVM threads ──\n");
		Thread.getAllStackTraces().forEach((thread, stack) -> {
			out.append(thread.getName()).append(" (").append(thread.getState()).append(")\n");
			for (StackTraceElement frame : stack) {
				out.append("    at ").append(frame).append('\n');
			}
		});
		return out.toString();
	}

	private void await(String what, BooleanSupplier condition) throws Exception {
		final long deadline = System.nanoTime() + ARM_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("timed out waiting for " + what + "; events: " + history.getRecent(20));
	}

	/**
	 * Drains the rings (what jdwp_get_events does first) and returns every history event of {@code type}.
	 */
	private List<EventHistory.DebugEvent> drainedEvents(String type) {
		tools.jdwp_get_events(1);
		return history.getRecent(EventHistory.MAX_EVENTS).stream().filter(e -> type.equals(e.type())).toList();
	}

	/**
	 * Whether the history holds a breakpoint event at the {@code other} line that was suppressed
	 * because its thread was inside a method the debugger invoked.
	 */
	private boolean suppressedAtOtherLine() {
		return history.getRecent(EventHistory.MAX_EVENTS).stream()
			.anyMatch(e -> "BREAKPOINT_SUPPRESSED".equals(e.type())
				&& String.valueOf(otherLine).equals(e.details().get("line")));
	}

	private Set<String> eventTypes() {
		final Set<String> types = new HashSet<>();
		history.getRecent(EventHistory.MAX_EVENTS).forEach(e -> types.add(e.type()));
		return types;
	}

	private void assertBytecodeRestored() throws Exception {
		assertThat(liveBytecode()).as("live bytecode equals the pre-instrumentation bytecode")
			.containsOnlyKeys(originalBytecode.keySet())
			.allSatisfy((method, code) -> assertThat(code).as(method).isEqualTo(originalBytecode.get(method)));
	}

	private Map<String, byte[]> liveBytecode() throws Exception {
		return session.liveBytecode();
	}

	private long calls() throws Exception {
		final ReferenceType type = connection.getVM().classesByName(TARGET).get(0);
		final ObjectReference counter = (ObjectReference) type.getValue(type.fieldByName("CALLS"));
		return ((LongValue) counter.getValue(counter.referenceType().fieldByName("value"))).value();
	}

	private double callsPerSecond() throws Exception {
		final long start = calls();
		final long t0 = System.nanoTime();
		Thread.sleep(2000);
		final long end = calls();
		return (end - start) / ((System.nanoTime() - t0) / 1e9);
	}

	private static byte[] withLeadingNop(byte[] classFile, String methodName) {
		final ClassReader reader = new ClassReader(classFile);
		final ClassNode node = new ClassNode(Opcodes.ASM9);
		reader.accept(node, 0);
		for (MethodNode method : node.methods) {
			if (method.name.equals(methodName)) {
				method.instructions.insert(new InsnNode(Opcodes.NOP));
			}
		}
		final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
