package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.ReferenceType;
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
				final int logpoint = LiveSession.idOf(session.tools.jdwp_set_logpoint(
					LiveSession.TARGET, LiveSession.lineOf("MARK: label"), "i", null, null));
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

	/** Verifies that a pending fast probe retries the same ID when discovery supplies the missing type. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void fastProbeRetriesWhenItsTypeBecomesAvailable(boolean breakpoint, @TempDir Path project) throws Exception {
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
				final String prefix = breakpoint ? "FAST_BREAKPOINT" : "FAST_LOGPOINT";
				final int probe = LiveSession.idOf(breakpoint
					? session.tools.jdwp_set_fast_breakpoint(LiveSession.TARGET, line, constant + " < 0",
						true, "thread", true, null)
					: session.tools.jdwp_set_fast_logpoint(LiveSession.TARGET, line, constant + " + i",
						null, true, null, null));
				await("arming attempt", () -> hasProbeEvent(session, probe, prefix + "_ARMING_DEFERRED")
					|| hasProbeEvent(session, probe, prefix + "_ARM_FAILED"));
				assertThat(hasProbeEvent(session, probe, prefix + "_ARM_FAILED")).isFalse();
				assertThat(hasProbeEvent(session, probe, prefix + "_ARMED")).isFalse();
				assertThat(hasProbeEvent(session, probe, "FAST_BREAKPOINT")).isFalse();

				releaseMaven.countDown();
				assertThat(provider.discover()).containsExactly(classes.toString());
				await("the original probe to arm", () -> hasProbeEvent(session, probe, prefix + "_ARMED"));
				session.tools.jdwp_clear_breakpoint(probe);

				if (!breakpoint) {
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
			event.type().equals(type) && (event.summary().contains("[FastLogpoint " + id + "]")
				|| event.summary().contains("[FastBreakpoint " + id + "]")));
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
