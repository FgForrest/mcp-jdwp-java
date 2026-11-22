package one.edee.mcp.jdwp.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ProcessBuilderMavenRunner}: the {@code .jdwp-mcp-classpath} harvester (parent
 * must be {@code target/}, content split on the host path separator, files never deleted), the
 * non-zero exit propagation, and Windows-specific wrapper preference.
 */
@DisplayName("ProcessBuilderMavenRunner — harvest, no-delete, exit propagation")
class ProcessBuilderMavenRunnerTest {

	@Test
	@DisplayName("only harvests .jdwp-mcp-classpath files whose direct parent is a target/ directory")
	void shouldOnlyHarvestOutputFilesUnderTargetParent(@TempDir Path tmp) throws Exception {
		// Per the file-safety policy, the harvester accepts a candidate ONLY when its direct
		// parent directory is named `target/`. Maven writes there because mdep.outputFile is
		// `target/.jdwp-mcp-classpath`. Anything else is NOT ours and must be ignored.
		Files.createDirectories(tmp.resolve("target"));
		// Use File.pathSeparator (':' on Unix, ';' on Windows) so the fixture matches what the
		// harvester actually splits on; a hard-coded ':' breaks the test on Windows runners.
		Files.writeString(tmp.resolve("target/.jdwp-mcp-classpath"),
			String.join(java.io.File.pathSeparator, "/m2/foo.jar", "/m2/bar.jar"));
		Files.createDirectories(tmp.resolve("module-a/target"));
		Files.writeString(tmp.resolve("module-a/target/.jdwp-mcp-classpath"),
			String.join(java.io.File.pathSeparator, "/m2/baz.jar", "/m2/foo.jar")); // overlap — must dedupe

		// SAFETY: a file with the same name OUTSIDE a target/ dir (e.g. left over from a previous
		// buggy run, or a name collision with something the user has) MUST be ignored.
		Files.writeString(tmp.resolve(".jdwp-mcp-classpath"),
			"/should/not/be/harvested.jar");

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);

		final List<String> result = runner.run(List.of("echo"), tmp, 10);

		assertThat(result).containsExactlyInAnyOrder("/m2/foo.jar", "/m2/bar.jar", "/m2/baz.jar");
		assertThat(result).doesNotContain("/should/not/be/harvested.jar");
	}

	@Test
	@DisplayName("does not delete harvested files (re-runs of Maven overwrite them in place)")
	void shouldNotDeleteHarvestedFiles(@TempDir Path tmp) throws Exception {
		// File-safety invariant: the runner reads but never deletes. Re-runs of Maven overwrite
		// the file in place; `mvn clean` is the only thing that removes it.
		Files.createDirectories(tmp.resolve("target"));
		final Path output = tmp.resolve("target/.jdwp-mcp-classpath");
		Files.writeString(output, "/m2/foo.jar");

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);
		runner.run(List.of("echo"), tmp, 10);

		assertThat(Files.exists(output))
			.as("Harvester must NEVER delete files — see file-safety policy")
			.isTrue();
	}

	@Test
	@DisplayName("reports a non-zero Maven exit code")
	void shouldReportNonzeroMavenExit(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target"));
		Files.writeString(tmp.resolve("target/.jdwp-mcp-classpath"), "/m2/foo.jar");
		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 1);

		assertThatThrownBy(() -> runner.run(List.of("false"), tmp, 10))
			.isInstanceOf(IOException.class).hasMessageContaining("Maven exited with code 1");
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
		assertThatThrownBy(() -> runner.run(List.of("sleep", "30"), tmp, 1))
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

	/**
	 * File-safety: the harvester reads files only when they are regular non-link files. A symlink
	 * named {@code .jdwp-mcp-classpath} living inside a {@code target/} directory must NOT be
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

		// target/.jdwp-mcp-classpath is a symlink to the file outside the project.
		Files.createDirectories(tmp.resolve("target"));
		Files.createSymbolicLink(
			tmp.resolve("target/.jdwp-mcp-classpath"),
			elsewhere
		);

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);

		final List<String> result = runner.run(List.of("echo"), tmp, 10);

		assertThat(result)
			.as("Symlinks at the harvest path must not be followed — file-safety invariant")
			.doesNotContain("/from/outside/should-not-be-harvested.jar");
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
		Files.writeString(tmp.resolve("target/.jdwp-mcp-classpath"), "/real/m2/foo.jar");
		Files.createDirectories(tmp.resolve("node_modules/foo/target"));
		Files.writeString(tmp.resolve("node_modules/foo/target/.jdwp-mcp-classpath"),
			"/from/node_modules/should-not-be-harvested.jar");

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);

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
		Files.writeString(boundaryModule.resolve(".jdwp-mcp-classpath"),
			String.join(java.io.File.pathSeparator, "/m2/deep.jar"));

		final ProcessBuilderMavenRunner runner = new ProcessBuilderMavenRunner(req -> 0);

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
