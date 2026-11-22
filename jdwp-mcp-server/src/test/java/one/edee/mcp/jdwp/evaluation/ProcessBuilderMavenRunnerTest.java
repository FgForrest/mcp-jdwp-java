package one.edee.mcp.jdwp.evaluation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.jdi.VirtualMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests per-run Maven output harvesting and cleanup, process ownership, and exit propagation.
 */
@DisplayName("ProcessBuilderMavenRunner — harvest, cleanup, exit propagation")
class ProcessBuilderMavenRunnerTest {

	/** Models a cancelled Maven command that writes only after its replacement writes. */
	@Test
	void cancelledRunCannotOverwriteTheReplacementOutput(@TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		Files.createDirectory(project.resolve("target"));
		final CountDownLatch firstStarted = new CountDownLatch(1);
		final CountDownLatch replacementWrote = new CountDownLatch(1);
		final CountDownLatch cancelledWrote = new CountDownLatch(1);
		final AtomicReference<Path> cancelledOutput = new AtomicReference<>();
		final AtomicReference<Path> replacementOutput = new AtomicReference<>();
		final AtomicInteger attempts = new AtomicInteger();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			new ProcessBuilderMavenRunner(request -> {
				final Path output = classpathOutput(request);
				if (attempts.getAndIncrement() == 0) {
					cancelledOutput.set(output);
					firstStarted.countDown();
					// A subprocess can still write after cancellation, while cleanup is in progress.
					final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
					while (replacementWrote.getCount() != 0) {
						try {
							assertThat(replacementWrote.await(Math.max(0, deadline - System.nanoTime()),
								TimeUnit.NANOSECONDS)).isTrue();
						} catch (InterruptedException ignored) {
							// The stand-in process is still running.
						}
					}
					Files.writeString(output, "/m2/old.jar");
					cancelledWrote.countDown();
					throw new InterruptedException("cancelled Maven run");
				}
				replacementOutput.set(output);
				Files.writeString(output, "/m2/new.jar");
				replacementWrote.countDown();
				assertThat(cancelledWrote.await(30, TimeUnit.SECONDS)).isTrue();
				return 0;
			}));
		final VirtualMachine first = mock(VirtualMachine.class);
		final VirtualMachine second = mock(VirtualMachine.class);
		provider.startForConnection(first);
		try {
			assertThat(firstStarted.await(30, TimeUnit.SECONDS)).isTrue();
			provider.startForConnection(second);
			assertThat(provider.discover()).containsExactly("/m2/new.jar");
			assertThat(replacementOutput.get().getParent()).isNotEqualTo(cancelledOutput.get().getParent()).doesNotExist();
			awaitFileRemoval(cancelledOutput.get().getParent());
		} finally {
			replacementWrote.countDown();
			provider.stopForConnection(second);
			provider.stopForConnection(first);
		}
	}

	/** Resolves the path Maven writes for a test module after coordinate expansion. */
	private static Path classpathOutput(ProcessBuilderMavenRunner.CommandRequest request) {
		return request.workingDirectory().resolve(outputArgument(request)
			.replace("${project.groupId}", "fixture").replace("${project.artifactId}", "module")
			.replace("${project.version}", "1"));
	}

	/** Extracts the exact argument Maven will resolve against each reactor module. */
	private static String outputArgument(ProcessBuilderMavenRunner.CommandRequest request) {
		final String prefix = "-Dmdep.outputFile=";
		return request.command().stream().filter(argument -> argument.startsWith(prefix))
			.findFirst().orElseThrow().substring(prefix.length());
	}

	/** Waits for cleanup on a cancelled daemon without joining it from the attach caller. */
	private static void awaitFileRemoval(Path output) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (Files.exists(output) && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(output).doesNotExist();
	}

	/** How the test ends a real Maven invocation under the supervisor. */
	private enum End { SUCCESS, TIMEOUT, CANCEL }

	/** Exercises normal exit, timeout, and cancellation with a reparented stdout helper. */
	@ParameterizedTest
	@EnumSource(End.class)
	@EnabledOnOs(OS.LINUX)
	void mavenGroupCleanupReachesAnAdoptedStdoutHelper(End end, @TempDir Path project) throws Exception {
		assumeTrue(Files.isExecutable(Path.of("/usr/bin/setsid")));
		final Path wrapper = Files.writeString(project.resolve("mvnw"), """
			#!/bin/sh
			group=$(ps -o pgid= -p "$$")
			(sleep 90 & printf '%s\\n' "$!" > helper.pid)
			printf '%s %s %s\\n' "$group" "$$" "$(cat helper.pid)" > pids
			if [ "$1" != "SUCCESS" ]; then
				exec sleep 90
			fi
			while [ ! -f finish ]; do sleep 0.05; done
			""");
		assertThat(wrapper.toFile().setExecutable(true)).isTrue();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner();
		final List<String> command = List.of(wrapper.toString(), end.name());
		final int budget = end == End.TIMEOUT ? 5 : 30;
		final AtomicBoolean interrupted = new AtomicBoolean();
		final FutureTask<List<String>> run = new FutureTask<>(() -> {
			try {
				return runner.run(command, project, budget);
			} finally {
				interrupted.set(Thread.currentThread().isInterrupted());
			}
		});
		final Thread worker = new Thread(run, "maven-group-cleanup-test");
		worker.setDaemon(true);
		worker.start();
		ProcessHandle helper = null;
		try {
			final String[] ids = awaitProcessIds(project.resolve("pids"));
			final long group = Long.parseLong(ids[0]);
			final long maven = Long.parseLong(ids[1]);
			final ProcessHandle leader = ProcessHandle.of(group).orElseThrow();
			final ProcessHandle commandProcess = ProcessHandle.of(maven).orElseThrow();
			helper = ProcessHandle.of(Long.parseLong(ids[2])).orElseThrow();
			if (end == End.TIMEOUT) {
				assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
					.hasRootCauseExactlyInstanceOf(IOException.class)
					.hasRootCauseMessage("Maven timed out after " + budget + " seconds");
			} else if (end == End.CANCEL) {
				worker.interrupt();
				assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS))
					.hasRootCauseExactlyInstanceOf(InterruptedException.class);
				assertThat(interrupted).isTrue();
			} else {
				Files.createFile(project.resolve("finish"));
				assertThat(run.get(30, TimeUnit.SECONDS)).isEmpty();
			}
			assertThat(group).as("The group leader must outlive the Maven command").isNotEqualTo(maven);
			awaitProcessExit(leader);
			awaitProcessExit(commandProcess);
			awaitProcessExit(helper);
		} finally {
			run.cancel(true);
			if (helper != null) {
				helper.destroyForcibly();
			}
			worker.join(TimeUnit.SECONDS.toMillis(30));
		}
	}

	/** Checks that a status written during the process-exit notification gets one final read. */
	@Test
	void shouldReadStatusAgainWhenSupervisorExits(@TempDir Path project) throws Exception {
		final Path status = Files.createFile(project.resolve("status"));
		final Process supervisor = mock(Process.class);
		when(supervisor.waitFor(org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.eq(TimeUnit.NANOSECONDS))).thenAnswer(call -> {
				Files.writeString(status, "3\n");
				return true;
			});
		final Method await = ProcessBuilderMavenRunner.class.getDeclaredMethod(
			"awaitMavenStatus", Process.class, Path.class, int.class);
		await.setAccessible(true);

		assertThat(await.invoke(null, supervisor, status, 30)).isEqualTo(3);
	}

	/** Preserves the child's output in diagnostics when decoding the status fails. */
	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void shouldLogOutputWhenStatusIsInvalid(@TempDir Path project) throws Exception {
		final Path status = Files.writeString(project.resolve("status"), "invalid\n");
		final List<String> command = List.of("sh", "-c", "printf 'maven status failure detail\\n'");
		final ProcessBuilderMavenRunner.CommandRequest request =
			new ProcessBuilderMavenRunner.CommandRequest(command, project, 30);
		final Method collect = ProcessBuilderMavenRunner.class.getDeclaredMethod(
			"collectProcessOutput", ProcessBuilderMavenRunner.CommandRequest.class, List.class, Path.class);
		collect.setAccessible(true);
		final Logger logger = (Logger) LoggerFactory.getLogger(ProcessBuilderMavenRunner.class);
		final ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.start();
		logger.addAppender(captured);
		try {
			assertThatThrownBy(() -> collect.invoke(null, request, command, status))
				.isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(IOException.class);
			assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
				.contains("Invalid Maven exit status", "maven status failure detail"));
		} finally {
			logger.detachAppender(captured);
			captured.stop();
		}
	}

	/** Verifies that the real shell supervisor forwards argv and reports executable errors. */
	@Test
	@EnabledOnOs(OS.LINUX)
	void wrapperPreservesArgumentsAndReportsExitCodes(@TempDir Path project) throws Exception {
		assumeTrue(Files.isExecutable(Path.of("/usr/bin/setsid")));
		final Path wrapper = Files.writeString(project.resolve("maven with spaces"), """
			#!/bin/sh
			printf '<%s>' "$1" "$2" "$3" "$4" "$5" > argv
			exit 3
			""");
		assertThat(wrapper.toFile().setExecutable(true)).isTrue();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner();
		assertThatThrownBy(() -> runner.run(List.of(wrapper.toString(), "a b", "$HOME", "*", "it's", ""), project, 30))
			.isInstanceOf(IOException.class).hasMessage("Maven exited with code 3");
		assertThat(project.resolve("argv")).hasContent("<a b><$HOME><*><it's><>");

		final Path missing = project.resolve("missing maven");
		assertThatThrownBy(() -> runner.run(List.of(missing.toString()), project, 30))
			.isInstanceOf(IOException.class).hasMessage("Maven executable not found: " + missing);
		final Path nonExecutable = Files.createFile(project.resolve("non-executable maven"));
		assertThatThrownBy(() -> runner.run(List.of(nonExecutable.toString()), project, 30))
			.isInstanceOf(IOException.class).hasMessage("Maven executable not executable: " + nonExecutable);
	}

	/** Treats cancellation between Maven exit and classpath harvest as an interrupt. */
	@Test
	void shouldKeepInterruptDuringHarvest(@TempDir Path project) throws Exception {
		Files.createDirectory(project.resolve("target"));
		final AtomicReference<Path> output = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(request -> {
			output.set(Files.writeString(classpathOutput(request), "/m2/partial.jar"));
			Thread.currentThread().interrupt();
			return 0;
		});
		try {
			assertThatThrownBy(() -> runner.run(List.of("mvn"), project, 30))
				.isInstanceOf(InterruptedException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(output.get().getParent()).doesNotExist();
		} finally {
			Thread.interrupted();
		}
	}

	/** Rejects a literal output template from Maven instead of harvesting an overwritten classpath. */
	@Test
	void shouldRejectUnexpandedOutputPath(@TempDir Path project) throws Exception {
		Files.createDirectory(project.resolve("target"));
		final AtomicReference<Path> literal = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(request -> {
			final Path requested = classpathOutput(request);
			literal.set(Files.writeString(requested.resolveSibling(
				"${project.groupId}@${project.artifactId}@${project.version}.cp"), "/m2/incomplete.jar"));
			return 0;
		});
		assertThatThrownBy(() -> runner.run(List.of("mvn"), project, 30))
			.isInstanceOf(ProcessBuilderMavenRunner.OutputReadException.class)
			.hasMessageContaining("did not expand");
		assertThat(literal.get()).doesNotExist();
	}

	/** Simulates Maven's per-module path resolution across a deep module, a sibling and two links. */
	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void shouldCollectReactorOutputOutsideProjectTree(@TempDir Path root) throws Exception {
		final Path project = Files.createDirectory(root.resolve("project"));
		final Path deep = Files.createDirectories(project.resolve("a/b/c/d/e/f/g/h"));
		final Path outside = Files.createDirectory(root.resolve("sibling-module"));
		final Path target = Files.createDirectory(root.resolve("build-output"));
		Files.createSymbolicLink(project.resolve("target"), target);
		final Path cwd = Files.createSymbolicLink(root.resolve("project-link"), project);
		final AtomicReference<Path> outputDirectory = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(request -> {
			final String template = outputArgument(request);
			outputDirectory.set(cwd.resolve(template).getParent());
			for (Path module : List.of(cwd, deep, outside)) {
				final String artifact = module.getFileName().toString();
				final Path output = module.resolve(template.replace("${project.groupId}", "fixture")
					.replace("${project.artifactId}", artifact).replace("${project.version}", "1"));
				Files.createDirectories(output.getParent());
				Files.writeString(output, "/m2/" + artifact + ".jar");
			}
			return 0;
		});

		assertThat(runner.run(List.of("mvn"), cwd, 30))
			.containsExactlyInAnyOrder("/m2/project-link.jar", "/m2/h.jar", "/m2/sibling-module.jar");
		assertThat(outputDirectory.get()).doesNotExist();
		assertThat(target).isEmptyDirectory();
		assertThat(deep).isEmptyDirectory();
		assertThat(outside).isEmptyDirectory();
	}

	/** Allows a signalled process to exit without relying on how soon the OS reaps it. */
	private static void awaitProcessExit(ProcessHandle process) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (process.info().command().isPresent() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(process.info().command()).as("Live command for PID %d", process.pid()).isEmpty();
	}

	/** Waits for the test wrapper to publish all three PIDs before it can exit. */
	private static String[] awaitProcessIds(Path pids) throws IOException, InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			if (Files.exists(pids)) {
				final String line = Files.readString(pids);
				if (line.endsWith("\n")) {
					return line.trim().split("\\s+");
				}
			}
			Thread.sleep(10);
		}
		throw new AssertionError("Maven did not write its process IDs");
	}

	/** Checks that malformed output is reported alongside entries from readable modules. */
	@Test
	void malformedMavenOutputReportsFailureAndKeepsOtherModules(@TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final AtomicReference<Path> outputDirectory = new AtomicReference<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			new ProcessBuilderMavenRunner(request -> {
				final Path output = classpathOutput(request);
				outputDirectory.set(output.getParent());
				Files.writeString(output, "/m2/good.jar");
				Files.write(output.resolveSibling("fixture@bad.cp"), new byte[] {(byte) 0xff});
				Files.writeString(output.resolveSibling("fixture@nul.cp"), "/m2/bad.jar\0");
				return 0;
			}));

		final LocalProjectClasspathProvider.Breakdown breakdown = provider.discoverWithBreakdown();

		assertThat(breakdown.all()).containsExactly("/m2/good.jar");
		assertThat(breakdown.maven()).isEqualTo(1);
		assertThat(breakdown.failure()).contains("Maven classpath discovery failed", "additional output read failures")
			.containsAnyOf("fixture@bad.cp", "fixture@nul.cp");
		assertThat(outputDirectory.get()).doesNotExist();
	}

	/** Collects all module outputs, using the host separator and deduplicating across files. */
	@Test
	void shouldHarvestAllModuleFiles(@TempDir Path project) throws Exception {
		final AtomicReference<Path> outputDirectory = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(request -> {
			final Path output = classpathOutput(request);
			outputDirectory.set(output.getParent());
			Files.writeString(output, String.join(java.io.File.pathSeparator, "/m2/foo.jar", "/m2/bar.jar"));
			Files.writeString(output.resolveSibling("fixture@second.cp"),
				String.join(java.io.File.pathSeparator, "/m2/baz.jar", "/m2/foo.jar"));
			return 0;
		});

		assertThat(runner.run(List.of("mvn"), project, 30))
			.containsExactlyInAnyOrder("/m2/foo.jar", "/m2/bar.jar", "/m2/baz.jar");
		assertThat(outputDirectory.get()).doesNotExist();
	}

	/** Leaves legacy and other runs' classpath files untouched. */
	@Test
	void shouldIgnoreOtherRunsOutput(@TempDir Path tmp) throws Exception {
		final Path target = Files.createDirectory(tmp.resolve("target"));
		final Path legacy = Files.writeString(target.resolve(".jdwp-mcp-classpath"), "/m2/legacy.jar");
		final Path otherRun = Files.writeString(target.resolve(".jdwp-mcp-classpath-other"), "/m2/other.jar");

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);

		assertThat(runner.run(List.of("echo"), tmp, 10)).isEmpty();
		assertThat(legacy).hasContent("/m2/legacy.jar");
		assertThat(otherRun).hasContent("/m2/other.jar");
	}

	/** Reports a nonzero exit and still removes the failed run's output. */
	@Test
	@DisplayName("reports a non-zero Maven exit code")
	void shouldReportNonzeroMavenExit(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target"));
		final AtomicReference<Path> output = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> {
			output.set(Files.writeString(classpathOutput(req), "/m2/foo.jar"));
			return 1;
		});

		assertThatThrownBy(() -> runner.run(List.of("false"), tmp, 10))
			.isInstanceOf(IOException.class).hasMessageContaining("Maven exited with code 1");
		assertThat(output.get().getParent()).doesNotExist();
	}

	/**
	 * Process-ownership invariant — the executor is responsible for cleaning up the child process
	 * IT spawned; the {@code run()} wrapper must NOT consult {@code ProcessHandle.current().descendants()}
	 * to kill processes by PID diff, since that would forcibly destroy any unrelated subprocess
	 * another server component happened to start in the same window. This test asserts the
	 * non-overreach: a sibling subprocess started by the test stays alive even when the
	 * executor throws {@link InterruptedException}.
	 *
	 * <p>This seam does not spawn Maven. The real supervisor's interrupt cleanup is exercised by
	 * {@link #mavenGroupCleanupReachesAnAdoptedStdoutHelper(End, Path)}.
	 */
	@Test
	@org.junit.jupiter.api.condition.EnabledOnOs({
		org.junit.jupiter.api.condition.OS.LINUX,
		org.junit.jupiter.api.condition.OS.MAC
	})
	@DisplayName("run() does NOT destroy unrelated sibling subprocesses when the executor throws InterruptedException")
	void shouldNotOverkillUnrelatedSubprocessesOnInterrupt(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target"));

		// Sibling: a subprocess started by "some other component" before run() is invoked. It
		// must survive run()'s cleanup — process ownership is scoped to the executor. Uses
		// `sleep` so the test is Unix-only — guarded by @EnabledOnOs above.
		final Process sibling = new ProcessBuilder("sleep", "5").redirectErrorStream(true).start();
		try {
			final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> {
				throw new InterruptedException("simulated interrupt; stub does not spawn anything");
			});

			assertThatThrownBy(() -> runner.run(List.of("anything"), tmp, 10))
				.isInstanceOf(InterruptedException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(sibling.isAlive())
				.as("Sibling subprocess must NOT be destroyed by run() — process ownership is scoped to the executor")
				.isTrue();
		} finally {
			Thread.interrupted();
			sibling.destroyForcibly();
			sibling.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	/**
	 * Forces the host fallback and checks that a real timed-out command leaves no running children.
	 */
	@Test
	@org.junit.jupiter.api.condition.EnabledOnOs({
		org.junit.jupiter.api.condition.OS.LINUX,
		org.junit.jupiter.api.condition.OS.MAC
	})
	@DisplayName("executeRealCommand destroys its own process tree on timeout")
	void shouldDestroyOwnedProcessTreeOnTimeout(@TempDir Path tmp) throws Exception {
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(true);
		assertThat(runner.usesDescendantPolling()).isTrue();

		// Snapshot PIDs of the current JVM's descendants before the call so we can verify
		// after-state.
		final java.util.Set<Long> before = ProcessHandle.current().descendants()
			.map(ProcessHandle::pid)
			.collect(java.util.stream.Collectors.toSet());

		final long startTime = System.currentTimeMillis();
		// 1s timeout against a 30s sleep — the runner must terminate the child within a few
		// seconds of the budget, not 30s later.
		assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "sleep 30 & wait"), tmp, 1))
			.isInstanceOf(IOException.class).hasMessageContaining("Maven timed out");
		final long elapsed = System.currentTimeMillis() - startTime;

		assertThat(elapsed)
			.as("Runner must enforce timeout — should return within ~5s, not wait the full 30")
			.isLessThan(8_000);

		// All descendants that appeared during the call must be gone (destroyed by executeRealCommand).
		final java.util.List<ProcessHandle> stillAlive = ProcessHandle.current().descendants()
			.filter(h -> !before.contains(h.pid()))
			.filter(ProcessHandle::isAlive)
			.toList();
		assertThat(stillAlive)
			.as("executeRealCommand must destroy every descendant it spawned")
			.isEmpty();
	}

	/** Exposes a second child on the next descendant snapshot and checks that cleanup reaches it. */
	@Test
	void sweepsAgainForHelpersForkedDuringCleanup() {
		final Process root = mock(Process.class);
		final ProcessHandle first = mock(ProcessHandle.class);
		final ProcessHandle late = mock(ProcessHandle.class);
		when(first.isAlive()).thenReturn(true, false);
		when(late.isAlive()).thenReturn(true, false);
		when(first.onExit()).thenReturn(CompletableFuture.completedFuture(first));
		when(late.onExit()).thenReturn(CompletableFuture.completedFuture(late));
		when(root.onExit()).thenReturn(CompletableFuture.completedFuture(root));
		final AtomicInteger snapshots = new AtomicInteger();
		when(root.descendants()).thenAnswer(call -> switch (snapshots.getAndIncrement()) {
			case 0 -> Stream.of(first);
			case 1 -> Stream.of(late);
			default -> Stream.empty();
		});

		ProcessBuilderMavenRunner.destroyProcessTree(root);

		verify(first).destroyForcibly();
		verify(late).destroyForcibly();
		verify(root).destroyForcibly();
	}

	/** Discards a symlink in the private output directory without reading or deleting its target. */
	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void shouldNotFollowSymlinkAtHarvestPath(@TempDir Path project) throws Exception {
		final Path elsewhere = Files.writeString(project.resolve("elsewhere.txt"), "/m2/unrelated.jar");
		final AtomicReference<Path> link = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(request -> {
			link.set(Files.createSymbolicLink(classpathOutput(request), elsewhere));
			return 0;
		});

		assertThat(runner.run(List.of("mvn"), project, 30)).isEmpty();
		assertThat(link.get().getParent()).doesNotExist();
		assertThat(elsewhere).hasContent("/m2/unrelated.jar");
	}

	/**
	 * Restricted-environment safety: {@link Process#descendants()} can throw under tight security
	 * policies or when handle inspection is unavailable. If that happens during a timeout or
	 * interrupt, the root Maven process must still be destroyed — otherwise the very subprocess
	 * we are trying to clean up leaks. The fix puts {@code root.destroyForcibly()} in a
	 * {@code finally} so it runs regardless of how descendant enumeration fails.
	 */
	@Test
	@DisplayName("destroyProcessTree still destroys the root when descendants() throws")
	void shouldDestroyRootEvenIfDescendantsThrows() {
		final java.util.concurrent.atomic.AtomicBoolean rootDestroyed = new java.util.concurrent.atomic.AtomicBoolean(false);

		// Stub Process: descendants() blows up to simulate a restricted env where handle inspection
		// is unavailable. Only the methods destroyProcessTree actually touches are overridden.
		final Process stub = new Process() {
			@Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
			@Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
			@Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
			@Override public int waitFor() { return 0; }
			@Override public int exitValue() { return 0; }
			@Override public void destroy() { /* unused by destroyProcessTree */ }
			@Override public Process destroyForcibly() {
				rootDestroyed.set(true);
				return this;
			}
			@Override public java.util.stream.Stream<ProcessHandle> descendants() {
				throw new SecurityException("simulated restricted environment");
			}
			@Override public java.util.concurrent.CompletableFuture<Process> onExit() {
				return java.util.concurrent.CompletableFuture.completedFuture(this);
			}
		};

		ProcessBuilderMavenRunner.destroyProcessTree(stub);

		assertThat(rootDestroyed.get())
			.as("Root process must be destroyed even when descendants() throws — no leaked Maven subprocess")
			.isTrue();
	}

	/**
	 * On Windows the wrapper script is {@code mvnw.cmd}, not {@code mvnw}. The provider must probe
	 * for both, preferring {@code mvnw} (Unix) when present and falling back to {@code mvnw.cmd} on
	 * Windows hosts. This test is meaningful only on a real Windows runner — the
	 * {@link EnabledOnOs} skips it elsewhere.
	 */
	@Test
	@DisplayName("prefers mvnw.cmd on Windows when no Unix mvnw wrapper exists")
	@EnabledOnOs(OS.WINDOWS)
	void shouldPreferMvnwCmdOnWindows(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final Path mvnwCmd = tmp.resolve("mvnw.cmd");
		Files.writeString(mvnwCmd, "@echo off\r\n");

		final AtomicReference<List<String>> capturedCmd = new AtomicReference<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> { capturedCmd.set(cmd); return List.of(); }
		);
		provider.discover();

		assertThat(capturedCmd.get().get(0)).endsWith("mvnw.cmd");
	}
}
