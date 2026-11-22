package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.VirtualMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
			assertThat(replacementOutput.get()).isNotEqualTo(cancelledOutput.get()).doesNotExist();
			awaitFileRemoval(cancelledOutput.get());
		} finally {
			replacementWrote.countDown();
			provider.stopForConnection(second);
			provider.stopForConnection(first);
		}
	}

	/** Resolves the Maven output path from the command the test executor receives. */
	private static Path classpathOutput(ProcessBuilderMavenRunner.CommandRequest request) {
		final String prefix = "-Dmdep.outputFile=";
		return request.workingDirectory().resolve(request.command().stream()
			.filter(argument -> argument.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length()));
	}

	/** Waits for cleanup on a cancelled daemon without joining it from the attach caller. */
	private static void awaitFileRemoval(Path output) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (Files.exists(output) && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(output).doesNotExist();
	}

	/** Exercises group cleanup on both normal exit and timeout with a reparented stdout helper. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@EnabledOnOs(OS.LINUX)
	void mavenGroupCleanupReachesAnAdoptedStdoutHelper(boolean timeout, @TempDir Path project) throws Exception {
		assumeTrue(Files.isExecutable(Path.of("/usr/bin/setsid")));
		final Path wrapper = Files.writeString(project.resolve("mvnw"), """
			#!/bin/sh
			group=$(ps -o pgid= -p "$$")
			(sleep 90 & printf '%s\\n' "$!" > helper.pid)
			printf '%s %s %s\\n' "$group" "$$" "$(cat helper.pid)" > pids
			if [ "$1" = "true" ]; then
				exec sleep 90
			fi
			while [ ! -f finish ]; do sleep 0.05; done
			""");
		assertThat(wrapper.toFile().setExecutable(true)).isTrue();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner();
		final List<String> command = List.of(wrapper.toString(), Boolean.toString(timeout));
		final int budget = timeout ? 5 : 30;
		final FutureTask<List<String>> run = new FutureTask<>(() -> runner.run(command, project, budget));
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
			if (timeout) {
				assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
					.hasRootCauseExactlyInstanceOf(IOException.class)
					.hasRootCauseMessage("Maven timed out after " + budget + " seconds");
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
		final Path good = Files.createDirectories(project.resolve("good/target"));
		final Path bad = Files.createDirectories(project.resolve("bad/target"));
		final Path malformedPath = Files.createDirectories(project.resolve("nul/target"));
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			new ProcessBuilderMavenRunner(request -> {
				final Path name = classpathOutput(request).getFileName();
				Files.writeString(good.resolve(name), "/m2/good.jar");
				Files.write(bad.resolve(name), new byte[] {(byte) 0xff});
				Files.writeString(malformedPath.resolve(name), "/m2/bad.jar\0");
				return 0;
			}));

		final LocalProjectClasspathProvider.Breakdown breakdown = provider.discoverWithBreakdown();

		assertThat(breakdown.all()).containsExactly("/m2/good.jar");
		assertThat(breakdown.maven()).isEqualTo(1);
		assertThat(breakdown.failure()).contains("Maven classpath discovery failed", "additional output read failures")
			.containsAnyOf(bad.toString(), malformedPath.toString());
	}

	/** Harvests and removes the run's files only when their direct parent is a target directory. */
	@Test
	@DisplayName("only harvests .jdwp-mcp-classpath files whose direct parent is a target/ directory")
	void shouldOnlyHarvestOutputFilesUnderTargetParent(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target"));
		final Path moduleTarget = Files.createDirectories(tmp.resolve("module-a/target"));
		final AtomicReference<Path> outputName = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> {
			final Path output = classpathOutput(req);
			final Path name = output.getFileName();
			outputName.set(name);
			// Use the host separator and repeat one entry across modules to test deduplication.
			Files.writeString(output, String.join(java.io.File.pathSeparator, "/m2/foo.jar", "/m2/bar.jar"));
			Files.writeString(moduleTarget.resolve(name),
				String.join(java.io.File.pathSeparator, "/m2/baz.jar", "/m2/foo.jar"));
			Files.writeString(tmp.resolve(name), "/should/not/be/harvested.jar");
			return 0;
		});

		final List<String> result = runner.run(List.of("echo"), tmp, 10);

		assertThat(result).containsExactlyInAnyOrder("/m2/foo.jar", "/m2/bar.jar", "/m2/baz.jar");
		assertThat(tmp.resolve("target").resolve(outputName.get())).doesNotExist();
		assertThat(moduleTarget.resolve(outputName.get())).doesNotExist();
		assertThat(tmp.resolve(outputName.get())).hasContent("/should/not/be/harvested.jar");
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
		assertThat(output.get()).doesNotExist();
	}

	/**
	 * Process-ownership invariant — the executor is responsible for cleaning up the child process
	 * IT spawned; the {@code run()} wrapper must NOT consult {@code ProcessHandle.current().descendants()}
	 * to kill processes by PID diff, since that would forcibly destroy any unrelated subprocess
	 * another server component happened to start in the same window. This test asserts the
	 * non-overreach: a sibling subprocess started by the test stays alive even when the
	 * executor throws {@link InterruptedException}.
	 *
	 * <p>The real "destroy MY child on interrupt" behaviour lives in
	 * {@link ProcessBuilderMavenRunner#executeRealCommand} and is exercised by
	 * {@link #shouldDestroyOwnedProcessTreeOnTimeout} below using the production code path.
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
			assertThat(sibling.isAlive())
				.as("Sibling subprocess must NOT be destroyed by run() — process ownership is scoped to the executor")
				.isTrue();
		} finally {
			sibling.destroyForcibly();
			sibling.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	/**
	 * The production {@link ProcessBuilderMavenRunner#executeRealCommand} (used via the default,
	 * no-arg constructor) must destroy the spawned process and its descendants on timeout. This
	 * exercises the real ProcessBuilder code path with a short timeout against {@code sleep 30}.
	 */
	@Test
	@org.junit.jupiter.api.condition.EnabledOnOs({
		org.junit.jupiter.api.condition.OS.LINUX,
		org.junit.jupiter.api.condition.OS.MAC
	})
	@DisplayName("executeRealCommand destroys its own process tree on timeout")
	void shouldDestroyOwnedProcessTreeOnTimeout(@TempDir Path tmp) throws Exception {
		// Real production runner — exercises executeRealCommand via the default constructor.
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner();

		// Snapshot PIDs of the current JVM's descendants before the call so we can verify
		// after-state.
		final java.util.Set<Long> before = ProcessHandle.current().descendants()
			.map(ProcessHandle::pid)
			.collect(java.util.stream.Collectors.toSet());

		final long startTime = System.currentTimeMillis();
		// 1s timeout against a 30s sleep — the runner must terminate the child within a few
		// seconds of the budget, not 30s later.
		assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "exec sleep 30"), tmp, 1))
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

	/**
	 * File-safety: the harvester reads files only when they are regular non-link files. A symlink
	 * matching the run's output name inside a {@code target/} directory must NOT be
	 * followed — it could point to any file on the host, breaking the "we only read Maven-written
	 * output we found in-tree" invariant.
	 */
	@Test
	@org.junit.jupiter.api.condition.EnabledOnOs({
		org.junit.jupiter.api.condition.OS.LINUX,
		org.junit.jupiter.api.condition.OS.MAC
	})
	@DisplayName("does not follow a symlink at target/.jdwp-mcp-classpath")
	void shouldNotFollowSymlinkAtHarvestPath(@TempDir Path tmp) throws Exception {
		// Outside-the-tree file the symlink would point at.
		final Path elsewhere = tmp.resolve("somewhere-else.txt");
		Files.writeString(elsewhere, "/from/outside/should-not-be-harvested.jar");

		Files.createDirectories(tmp.resolve("target"));
		final AtomicReference<Path> link = new AtomicReference<>();
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> {
			link.set(Files.createSymbolicLink(classpathOutput(req), elsewhere));
			return 0;
		});

		final List<String> result = runner.run(List.of("echo"), tmp, 10);

		assertThat(result)
			.as("Symlinks at the harvest path must not be followed — file-safety invariant")
			.doesNotContain("/from/outside/should-not-be-harvested.jar");
		assertThat(link.get()).isSymbolicLink();
		assertThat(elsewhere).hasContent("/from/outside/should-not-be-harvested.jar");
	}

	/**
	 * {@link Files#walk(Path, int, java.nio.file.FileVisitOption...)} descends into every directory
	 * up to the depth limit. The harvester must skip non-Maven trees ({@code node_modules},
	 * {@code .git}, {@code .idea}, etc.) so a stray
	 * {@code node_modules/foo/target/.jdwp-mcp-classpath} (e.g. a packaged JS module shipping its own
	 * Maven artifact) is NOT picked up.
	 */
	@Test
	@DisplayName("does not harvest .jdwp-mcp-classpath files under SKIP_DIRS (e.g. node_modules)")
	void shouldSkipNodeModulesDuringHarvest(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target"));
		final Path skipped = Files.createDirectories(tmp.resolve("node_modules/foo/target"));
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> {
			final Path output = classpathOutput(req);
			Files.writeString(output, "/real/m2/foo.jar");
			Files.writeString(skipped.resolve(output.getFileName()), "/from/node_modules/should-not-be-harvested.jar");
			return 0;
		});

		final List<String> result = runner.run(List.of("echo"), tmp, 10);

		assertThat(result)
			.contains("/real/m2/foo.jar")
			.doesNotContain("/from/node_modules/should-not-be-harvested.jar");
	}

	/**
	 * Deep-reactor regression: a module discovered at the filesystem scan's depth boundary (5 levels
	 * below {@code workingDirectory}) puts its {@code target/.jdwp-mcp-classpath} two more levels
	 * down — total depth 7. A shallower harvest cap silently drops Maven-resolved dependencies for
	 * legitimate reactor layouts.
	 */
	@Test
	@DisplayName("harvests .jdwp-mcp-classpath at reactor-boundary depth (module at scan depth 5 → file at depth 7)")
	void shouldHarvestOutputFileAtReactorBoundaryDepth(@TempDir Path tmp) throws Exception {
		// Path: <tmp>/a/b/c/d/e/target/.jdwp-mcp-classpath
		// Counting from <tmp> as depth 0: a=1, b=2, c=3, d=4, e=5 (matches MAX_SCAN_DEPTH=5),
		// target=6, file=7. The harvest cap must reach depth 7.
		final Path boundaryModule = tmp.resolve("a/b/c/d/e/target");
		Files.createDirectories(boundaryModule);
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> {
			Files.writeString(boundaryModule.resolve(classpathOutput(req).getFileName()), "/m2/deep.jar");
			return 0;
		});

		final List<String> result = runner.run(List.of("echo"), tmp, 10);

		assertThat(result)
			.as("Maven output at <root>/a/b/c/d/e/target/.jdwp-mcp-classpath must be reachable — depth 7")
			.containsExactly("/m2/deep.jar");
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
