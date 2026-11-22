package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.ClassType;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider;
import one.edee.mcp.jdwp.evaluation.ProcessBuilderMavenRunner;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
class DispatcherMavenLiveTest {
	/** Safety bound for observing target and discovery signals. */
	private static final int SIGNAL_TIMEOUT_SECONDS = 30;
	@Test
	void firstLogpointAndFollowingStopCompleteWhileMavenIsStillRunning(@TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		Files.createDirectories(project.resolve("target/classes"));
		final CompletableFuture<Thread> mavenStarted = new CompletableFuture<>();
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, workingDirectory, timeoutSeconds) -> {
				mavenStarted.complete(Thread.currentThread());
				releaseMaven.await();
				return List.of();
			});
		try (LiveSession session = LiveSession.start(List.of(), provider)) {
			try {
				// A loop is never interpreted, so this logpoint exercises the compiled path and its classpath.
				final int logpoint = LiveSession.idOf(session.tools.jdwp_set_logpoint(
					LiveSession.TARGET, LiveSession.lineOf("MARK: label"),
					"{ int sum = 0; for (int k = 0; k < 1; k++) { sum += i; } return sum; }", null, null));
				final Thread mavenThread = mavenStarted.get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				// Either target worker can reach the plain breakpoint first. Observe a logpoint
				// evaluation before arming the stop that could park both workers.
				await("successful logpoint", () -> session.history.getRecent(100).stream().anyMatch(event ->
					event.type().equals("LOGPOINT") && event.summary().contains("[Logpoint " + logpoint + "]")));
				final int stopLine = LiveSession.lineOf("MARK: other");
				final int breakpoint = LiveSession.idOf(session.tools.jdwp_set_breakpoint(
					LiveSession.TARGET, stopLine, "thread", null, null, null, null));

				assertThat(session.tools.jdwp_resume_until_event(SIGNAL_TIMEOUT_SECONDS * 1_000))
					.startsWith("Event fired at " + LiveSession.TARGET + ":" + stopLine);
				assertThat(mavenThread.isDaemon()).isTrue();
				assertThat(mavenThread.getName()).doesNotStartWith("jdi-event-");
				assertThat(session.tools.jdwp_diagnose(false)).contains("Local classpath discovery is still running");

				final Set<String> remoteOnlyWrappers = evaluatorWrappers(session);
				assertThat(remoteOnlyWrappers).isNotEmpty();
				releaseMaven.countDown();
				assertThat(provider.discover()).containsExactly(project.resolve("target/classes").toString());
				session.tools.jdwp_clear_breakpoint(breakpoint);
				session.tools.jdwp_resume();
				await("recompiled expression", () ->
					evaluatorWrappers(session).stream().anyMatch(name -> !remoteOnlyWrappers.contains(name)));
			} finally {
				releaseMaven.countDown();
			}
		}
	}

	@Test
	void explicitAndAutomaticReconnectStartFreshDiscovery(@TempDir Path root) throws Exception {
		final Path first = Files.createDirectory(root.resolve("first"));
		final Path second = Files.createDirectory(root.resolve("second"));
		Files.writeString(first.resolve("pom.xml"), "<project/>");
		Files.writeString(second.resolve("pom.xml"), "<project/>");
		final ClientWorkspace workspace = new ClientWorkspace();
		final LinkedBlockingQueue<Path> scanned = new LinkedBlockingQueue<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(first, workspace, name -> null,
			(command, directory, timeout) -> {
				scanned.add(directory);
				return List.of(directory.resolve("dependency.jar").toString());
			});
		try (LiveSession session = LiveSession.start(List.of(), provider)) {
			assertThat(scanned.poll(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(first);
			assertThat(provider.discover()).containsExactly(first.resolve("dependency.jar").toString());

			workspace.observe(Map.of("codex/sandbox-state", Map.of("sandboxCwd", second.toString())));
			await("explicit reconnect", () -> {
				final String result = session.tools.jdwp_reconnect();
				if (result.contains("Connection refused")) {
					return false;
				}
				assertThat(result).startsWith("Reconnected to");
				return true;
			});
			assertThat(scanned.poll(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(second);
			assertThat(provider.discover()).containsExactly(second.resolve("dependency.jar").toString());

			session.connection.notifyVmDied();
			await("automatic reconnect", () -> {
				try {
					session.connection.getVM();
					return true;
				} catch (ConnectException waitingForAgent) {
					return false;
				}
			});
			assertThat(scanned.poll(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(second);
		}
	}

	/** Selects the probe expression and the stage that first needs the local classpath. */
	private enum ClasspathUse { LOGPOINT, BREAKPOINT_CONDITION, ARMING_CAPTURE, INSTALLED_CAPTURE }

	/** Checks overview during discovery and verifies that a deferred probe retries under the same ID. */
	@ParameterizedTest
	@EnumSource(ClasspathUse.class)
	void fastProbeRetriesWhenItsTypeBecomesAvailable(ClasspathUse use, @TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final Path classes = Files.createDirectory(project.resolve("maven-dependency"));
		final Path source = Files.writeString(project.resolve("BuildConstant.java"), """
			package probeclasspath;
			public final class BuildConstant {
				public static final int VALUE = 42;
			}
			""");
		assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null,
			"-d", classes.toString(), source.toString())).isZero();
		final CountDownLatch mavenStarted = new CountDownLatch(1);
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, directory, timeout) -> {
				mavenStarted.countDown();
				releaseMaven.await();
				return List.of(classes.toString());
			});
		try (LiveSession session = LiveSession.start(List.of(), provider)) {
			try {
				assertThat(mavenStarted.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
				final int line = LiveSession.lineOf("MARK: label");
				final String constant = "probeclasspath.BuildConstant.VALUE";
				final String prefix = use == ClasspathUse.LOGPOINT ? "FAST_LOGPOINT" : "FAST_BREAKPOINT";
				final String registration = switch (use) {
					case LOGPOINT -> session.tools.jdwp_set_fast_logpoint(LiveSession.TARGET, line, constant + " + i",
						null, true, null, null);
					case BREAKPOINT_CONDITION -> session.tools.jdwp_set_fast_breakpoint(LiveSession.TARGET, line,
						constant + " < 0", true, "thread", true, null, null);
					case ARMING_CAPTURE -> session.tools.jdwp_set_fast_breakpoint(LiveSession.TARGET, line,
						"true", true, "thread", true, null, constant);
					case INSTALLED_CAPTURE -> session.tools.jdwp_set_fast_breakpoint(LiveSession.TARGET, line,
						LiveSession.TARGET + ".captureTestStop", true, "thread", true, null, constant);
				};
				final int probe = LiveSession.idOf(registration);
				await("arming attempt", () -> hasProbeEvent(session, probe, prefix + "_ARMING_DEFERRED")
					|| hasProbeEvent(session, probe, prefix + "_ARM_FAILED")
					|| hasProbeEvent(session, probe, prefix + "_ARMED")
					|| hasProbeEvent(session, probe, "FAST_BREAKPOINT"));
				assertThat(hasProbeEvent(session, probe, prefix + "_ARMING_DEFERRED")).isTrue();
				assertThat(hasProbeEvent(session, probe, prefix + "_ARM_FAILED")).isFalse();
				assertThat(hasProbeEvent(session, probe, prefix + "_ARMED")).isFalse();
				assertThat(hasProbeEvent(session, probe, "FAST_BREAKPOINT")).isFalse();
				final String kind = use == ClasspathUse.LOGPOINT ? "fast_logpoint" : "fast_breakpoint";
				final String waiting = "PENDING — waiting for local classpath discovery";
				assertThat(session.tools.jdwp_overview(kind, null, null))
					.contains("#" + probe + "  " + LiveSession.TARGET + ":" + line + "  [" + waiting + "]");
				if (use == ClasspathUse.ARMING_CAPTURE || use == ClasspathUse.INSTALLED_CAPTURE) {
					assertThat(registration).contains("While local classpath discovery runs, arming can move to a later hit");
				}

				releaseMaven.countDown();
				assertThat(provider.discover()).containsExactly(classes.toString());
				if (use != ClasspathUse.ARMING_CAPTURE) {
					await("the original probe to arm", () -> hasProbeEvent(session, probe, prefix + "_ARMED"));
				}
				if (use == ClasspathUse.INSTALLED_CAPTURE) {
					final VirtualMachine vm = session.connection.getVM();
					final ClassType target = (ClassType) vm.classesByName(LiveSession.TARGET).get(0);
					target.setValue(target.fieldByName("captureTestStop"), vm.mirrorOf(true));
				}
				if (use == ClasspathUse.ARMING_CAPTURE || use == ClasspathUse.INSTALLED_CAPTURE) {
					assertThat(session.tools.jdwp_resume_until_event(SIGNAL_TIMEOUT_SECONDS * 1_000))
						.contains("fast_breakpoint=" + probe + ", condition true", "Captured at the hit: 42");
				}
				await("overview to clear the discovery notice", () ->
					!session.tools.jdwp_overview(kind, null, null).contains(waiting));
				session.tools.jdwp_clear_breakpoint(probe);

				if (use == ClasspathUse.LOGPOINT) {
					final int invalid = LiveSession.idOf(session.tools.jdwp_set_fast_logpoint(
						LiveSession.TARGET, line, "unknownType.unknownField + i", null, true, null, null));
					await("permanent failure after discovery", () ->
						hasProbeEvent(session, invalid, "FAST_LOGPOINT_ARM_FAILED"));
				}
			} finally {
				releaseMaven.countDown();
			}
		}
	}

	/** A misspelled capture local reports a failure on either stop while Maven is still running. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void captureTypoDoesNotWaitForMaven(boolean install, @TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final CountDownLatch mavenStarted = new CountDownLatch(1);
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, directory, timeout) -> {
				mavenStarted.countDown();
				releaseMaven.await();
				return List.of();
			});
		try (LiveSession session = LiveSession.start(List.of(), provider)) {
			try {
				assertThat(mavenStarted.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
				final String condition = install ? LiveSession.TARGET + ".captureTestStop" : "true";
				final int probe = LiveSession.idOf(session.tools.jdwp_set_fast_breakpoint(LiveSession.TARGET,
					LiveSession.lineOf("MARK: label"), condition, true, "thread", true, null,
					"{ return unknownCaptureLocal; }"));
				final String expected = install ? "FAST_BREAKPOINT_ARMED" : "FAST_BREAKPOINT";
				await("probe arming outcome", () -> hasProbeEvent(session, probe, expected)
					|| hasProbeEvent(session, probe, "FAST_BREAKPOINT_ARMING_DEFERRED")
					|| hasProbeEvent(session, probe, "FAST_BREAKPOINT_ARM_FAILED"));
				assertThat(hasProbeEvent(session, probe, expected)).as(expected).isTrue();
				if (install) {
					final VirtualMachine vm = session.connection.getVM();
					final ClassType target = (ClassType) vm.classesByName(LiveSession.TARGET).get(0);
					target.setValue(target.fieldByName("captureTestStop"), vm.mirrorOf(true));
				}
				assertThat(session.tools.jdwp_resume_until_event(SIGNAL_TIMEOUT_SECONDS * 1_000))
					.contains("fast_breakpoint=" + probe + ", condition true",
						"Captured at the hit: capture failed:", "unknownCaptureLocal");
				assertThat(provider.discoveryStatus().running()).isTrue();
				assertThat(hasProbeEvent(session, probe, "FAST_BREAKPOINT_ARMING_DEFERRED")).isFalse();
			} finally {
				releaseMaven.countDown();
			}
		}
	}

	/** Checks that clearing or resetting a deferred probe removes its discovery notice immediately. */
	@Test
	void clearingAndResettingForgetDeferredProbes(@TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final CountDownLatch mavenStarted = new CountDownLatch(1);
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, directory, timeout) -> {
				mavenStarted.countDown();
				releaseMaven.await();
				return List.of();
			});
		try (LiveSession session = LiveSession.start(List.of(), provider)) {
			try {
				assertThat(mavenStarted.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
				final int line = LiveSession.lineOf("MARK: label");
				final String waiting = "PENDING — waiting for local classpath discovery";
				for (boolean reset : new boolean[]{false, true}) {
					final int probe = LiveSession.idOf(session.tools.jdwp_set_fast_logpoint(LiveSession.TARGET, line,
						"probeclasspath.BuildConstant.VALUE", null, true, null, null));
					await("arming to defer", () -> hasProbeEvent(session, probe, "FAST_LOGPOINT_ARMING_DEFERRED"));
					assertThat(session.tools.jdwp_overview("fast_logpoint", null, null)).contains(waiting);
					if (reset) {
						session.tools.jdwp_reset();
					} else {
						session.tools.jdwp_clear_breakpoint(probe);
					}
					assertThat(session.tools.jdwp_overview("fast_logpoint", null, null))
						.doesNotContain("#" + probe + "  " + LiveSession.TARGET + ":" + line, waiting);
				}
				assertThat(provider.discoveryStatus().running()).isTrue();
			} finally {
				releaseMaven.countDown();
			}
		}
	}

	/** Arms the original fast logpoint after Maven supplies the superclass of a remote type. */
	@Test
	void fastLogpointRetriesWhenAnIndirectlyReferencedTypeBecomesAvailable(@TempDir Path project)
		throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final Path remote = Files.createDirectory(project.resolve("remote"));
		final Path localPackage = Files.createDirectories(project.resolve("local/probeclasspath"));
		final Path parent = Files.writeString(project.resolve("ProbeParent.java"), """
			package probeclasspath;
			public class ProbeParent { public int value() { return 42; } }
			""");
		final Path child = Files.writeString(project.resolve("ProbeChild.java"), """
			package probeclasspath;
			public class ProbeChild extends ProbeParent {}
			""");
		assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", remote.toString(),
			parent.toString(), child.toString())).isZero();
		Files.move(remote.resolve("probeclasspath/ProbeParent.class"), localPackage.resolve("ProbeParent.class"));
		final String localClasspath = localPackage.getParent().toString();
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, directory, timeout) -> {
				releaseMaven.await();
				return List.of(localClasspath);
			});
		try (LiveSession session = LiveSession.start(List.of(), provider, remote)) {
			final int probe = LiveSession.idOf(session.tools.jdwp_set_fast_logpoint(LiveSession.TARGET,
				LiveSession.lineOf("MARK: label"), "false ? new probeclasspath.ProbeChild().value() : i",
				null, true, null, null));
			await("first arming outcome", () -> hasProbeEvent(session, probe, "FAST_LOGPOINT_ARMING_DEFERRED")
				|| hasProbeEvent(session, probe, "FAST_LOGPOINT_ARM_FAILED"));
			assertThat(hasProbeEvent(session, probe, "FAST_LOGPOINT_ARMING_DEFERRED")).isTrue();
			assertThat(hasProbeEvent(session, probe, "FAST_LOGPOINT_ARM_FAILED")).isFalse();
			assertThat(provider.discoveryStatus().running()).isTrue();

			releaseMaven.countDown();
			assertThat(provider.discover()).containsExactly(localClasspath);
			await("the original probe to arm", () -> hasProbeEvent(session, probe, "FAST_LOGPOINT_ARMED"));
			session.tools.jdwp_clear_breakpoint(probe);
		} finally {
			releaseMaven.countDown();
		}
	}

	/**
	 * Reports the compiler's error after a live probe's initial discovery deferral. The unresolved name is qualified,
	 * so a classpath entry could still supply it; a bare misspelled local fails at once instead.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void invalidProbeReportsCompilerErrorOnceDiscoveryFinishes(boolean breakpoint, @TempDir Path project)
		throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, directory, timeout) -> {
				assertThat(releaseMaven.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
				return List.of();
			});
		try (LiveSession session = LiveSession.start(List.of(), provider)) {
			final int line = LiveSession.lineOf("MARK: label");
			final int probe = LiveSession.idOf(breakpoint
				? session.tools.jdwp_set_fast_breakpoint(LiveSession.TARGET, line, "UnknownType.VALUE < i",
					true, "all", true, null, null)
				: session.tools.jdwp_set_fast_logpoint(LiveSession.TARGET, line, "UnknownType.VALUE + i",
					null, true, null, null));
			final String prefix = breakpoint ? "FAST_BREAKPOINT" : "FAST_LOGPOINT";
			await("discovery deferral", () -> hasProbeEvent(session, probe, prefix + "_ARMING_DEFERRED"));

			releaseMaven.countDown();
			assertThat(provider.discoverWithBreakdown().failure()).isNull();
			if (breakpoint) {
				assertThat(session.tools.jdwp_resume_until_event(30_000))
					.contains("condition failed", "UnknownType cannot be resolved");
				session.tools.jdwp_resume();
			}
			await("compiler error", () -> session.history.getRecent(EventHistory.MAX_EVENTS).stream().anyMatch(event ->
				event.type().equals(prefix + "_ARM_FAILED")
					&& Integer.toString(probe).equals(event.details().get("breakpointId"))
					&& event.summary().contains("UnknownType cannot be resolved")));
		} finally {
			releaseMaven.countDown();
		}
	}

	/** Retains both process handles before disconnect and checks that both exit. */
	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void disconnectCancelsMavenAndItsHelper(@TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final Path wrapper = Files.writeString(project.resolve("mvnw"), """
			#!/bin/sh
			sleep 90 &
			printf '%s %s\n' "$$" "$!" > pids
			wait
			""");
		assertThat(wrapper.toFile().setExecutable(true)).isTrue();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project,
			name -> null, new ProcessBuilderMavenRunner());
		try (LiveSession session = LiveSession.start(List.of(), provider)) {
			final Path pids = project.resolve("pids");
			await("Maven process IDs", () -> Files.isRegularFile(pids) && Files.readString(pids).endsWith("\n"));
			final String[] ids = Files.readString(pids).trim().split(" ");
			final ProcessHandle maven = ProcessHandle.of(Long.parseLong(ids[0])).orElseThrow();
			final ProcessHandle helper = ProcessHandle.of(Long.parseLong(ids[1])).orElseThrow();
			try {
				session.connection.disconnect();
				assertThat(provider.discoveryStatus().running()).isFalse();
				assertThat(session.tools.jdwp_diagnose(false)).doesNotContain("Local classpath discovery is still running");
				await("Maven and its helper to exit", () -> !maven.isAlive() && !helper.isAlive());
			} finally {
				helper.destroyForcibly();
				maven.destroyForcibly();
				provider.reset();
			}
		}
	}

	/** Returns whether the live session recorded the given event type for the probe ID. */
	private static boolean hasProbeEvent(LiveSession session, int id, String type) {
		return session.history.getRecent(EventHistory.MAX_EVENTS).stream().anyMatch(event ->
			event.type().equals(type) && String.valueOf(id).equals(event.details().get("breakpointId")));
	}

	private static Set<String> evaluatorWrappers(LiveSession session) throws Exception {
		return session.connection.getVM().allClasses().stream()
			.map(ReferenceType::name)
			.filter(name -> name.contains(".ExpressionEvaluator_"))
			.collect(Collectors.toSet());
	}

	private static void await(String expected, Callable<Boolean> condition) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SIGNAL_TIMEOUT_SECONDS);
		while (System.nanoTime() < deadline) {
			if (condition.call()) {
				return;
			}
			Thread.sleep(10);
		}
		assertThat(condition.call()).as(expected).isTrue();
	}
}
