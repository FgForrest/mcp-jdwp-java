package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.ReferenceType;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

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
	@Test
	void firstLogpointAndFollowingStopCompleteWhileMavenIsStillRunning(@TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		Files.createDirectories(project.resolve("target/classes"));
		final CompletableFuture<Thread> mavenStarted = new CompletableFuture<>();
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, workingDirectory, timeoutSeconds) -> {
				mavenStarted.complete(Thread.currentThread());
				try {
					releaseMaven.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return List.of();
			});
		try (LiveSession session = LiveSession.start(List.of(), provider)) {
			try {
				final int logpoint = LiveSession.idOf(session.tools.jdwp_set_logpoint(
					LiveSession.TARGET, LiveSession.lineOf("MARK: label"), "i", null, null));
				final Thread mavenThread = mavenStarted.get(10, TimeUnit.SECONDS);
				final int stopLine = LiveSession.lineOf("MARK: other");
				final int breakpoint = LiveSession.idOf(session.tools.jdwp_set_breakpoint(
					LiveSession.TARGET, stopLine, "thread", null, null, null, null));

				assertThat(session.tools.jdwp_resume_until_event(5_000))
					.startsWith("Event fired at " + LiveSession.TARGET + ":" + stopLine);
				await("successful logpoint", () -> session.history.getRecent(100).stream().anyMatch(event ->
					event.type().equals("LOGPOINT") && event.summary().contains("[Logpoint " + logpoint + "]")));
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
			assertThat(scanned.poll(5, TimeUnit.SECONDS)).isEqualTo(first);
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
			assertThat(scanned.poll(5, TimeUnit.SECONDS)).isEqualTo(second);
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
			assertThat(scanned.poll(5, TimeUnit.SECONDS)).isEqualTo(second);
		}
	}

	private static Set<String> evaluatorWrappers(LiveSession session) throws Exception {
		return session.connection.getVM().allClasses().stream()
			.map(ReferenceType::name)
			.filter(name -> name.contains(".ExpressionEvaluator_"))
			.collect(Collectors.toSet());
	}

	private static void await(String expected, Callable<Boolean> condition) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (condition.call()) {
				return;
			}
			Thread.sleep(10);
		}
		assertThat(condition.call()).as(expected).isTrue();
	}
}
