package one.edee.mcp.jdwp.evaluation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Maven {@code dependency:build-classpath} source of
 * {@link LocalProjectClasspathProvider}. Pins the command-line shape (quiet mode, runtime scope,
 * output file under {@code target/}), the {@code mvnw} preference rule, the silent-skip when no
 * {@code pom.xml} is present, and the timeout the runner is invoked with.
 */
@DisplayName("LocalProjectClasspathProvider — Maven dependency:build-classpath")
class LocalProjectClasspathProviderMavenTest {

	@Test
	@DisplayName("invokes Maven dependency:build-classpath when pom.xml is at the working directory")
	void shouldInvokeMavenWhenPomXmlExists(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");

		final AtomicReference<List<String>> capturedCmd = new AtomicReference<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp,
			envName -> null,
			(cmd, cwd, t) -> {
				capturedCmd.set(cmd);
				return List.of(
					"/home/user/.m2/repository/org/foo/foo-1.0.jar",
					"/home/user/.m2/repository/org/bar/bar-2.0.jar"
				);
			}
		);

		final Set<String> entries = provider.discover();

		assertThat(capturedCmd.get()).anyMatch(s -> s.contains("dependency:build-classpath"));
		// SAFETY: the output file MUST land under target/ (Maven-owned) — never bare in module root.
		// This assertion enforces the file-safety policy at the design level: if anyone changes
		// the flag to land elsewhere, this test fails.
		assertThat(capturedCmd.get())
			.as("mdep.outputFile must point under target/ — file-safety policy")
			.anyMatch(s -> s.equals("-Dmdep.outputFile=target/.jdwp-mcp-classpath"));
		assertThat(entries).contains(
			"/home/user/.m2/repository/org/foo/foo-1.0.jar",
			"/home/user/.m2/repository/org/bar/bar-2.0.jar"
		);
	}

	@Test
	@DisplayName("explains a successful Maven run that contributes no dependency jars")
	void shouldExplainSuccessfulEmptyMavenResult(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of());
		final Logger logger = (Logger) LoggerFactory.getLogger(LocalProjectClasspathProvider.class);
		final ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.start();
		logger.addAppender(captured);
		try {
			assertThat(provider.discoverWithBreakdown().failure()).isNull();
		} finally {
			logger.detachAppender(captured);
			captured.stop();
		}

		assertThat(captured.list).anySatisfy(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.INFO);
			assertThat(event.getFormattedMessage()).contains("Maven contributed 0 dependency jars", "no runtime dependencies");
		});
	}

	/**
	 * Quiet mode (-q) is essential: Maven's default output is verbose enough to drown the MCP
	 * server's diagnostic stream. The runner's stdout capture keeps a size-capped buffer for
	 * the WARN log on failure, so the quiet flag does not blind operators when something breaks.
	 */
	@Test
	@DisplayName("Maven command includes -q (quiet) to suppress Maven CLI noise")
	void shouldPassQuietFlagToMaven(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");

		final AtomicReference<List<String>> capturedCmd = new AtomicReference<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> { capturedCmd.set(cmd); return List.of(); }
		);
		provider.discover();

		assertThat(capturedCmd.get()).contains("-q");
	}

	/**
	 * {@code -DincludeScope=runtime} matches what is actually on the app's runtime classpath at
	 * the breakpoint — Maven's default would include test-scope deps too, which routinely
	 * differ from what the target VM actually sees.
	 */
	@Test
	@DisplayName("Maven command pins -DincludeScope=runtime to match the target VM's runtime classpath")
	void shouldPassIncludeScopeRuntimeToMaven(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");

		final AtomicReference<List<String>> capturedCmd = new AtomicReference<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> { capturedCmd.set(cmd); return List.of(); }
		);
		provider.discover();

		assertThat(capturedCmd.get()).contains("-DincludeScope=runtime");
	}

	/**
	 * Cold-cache Maven invocations may need to download dependencies on first run, which routinely
	 * takes 1-3 minutes. The provider must hand the runner a 180s timeout that accommodates the
	 * common cold case rather than a shorter default that would fire spuriously on a fresh machine.
	 */
	@Test
	@DisplayName("Maven runner is invoked with a 180-second timeout (cold-cache safety margin)")
	void shouldInvokeMavenRunnerWithA180SecondTimeout(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");

		final AtomicInteger capturedTimeout = new AtomicInteger(-1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> { capturedTimeout.set(t); return List.of(); }
		);
		provider.discover();

		assertThat(capturedTimeout.get()).isEqualTo(180);
	}

	/**
	 * Symlink-safety for the Maven wrapper: a {@code mvnw} that is itself a symlink (e.g. to a
	 * script outside the working directory) must NOT be selected as the executable. The provider
	 * must fall back to PATH {@code mvn} instead, matching the symlinks-never-followed invariant
	 * enforced everywhere else in this class. Linux/macOS only — Windows can't create symlinks
	 * without elevated rights, and the Windows path is exercised by its own test.
	 */
	@Test
	@org.junit.jupiter.api.condition.EnabledOnOs({
		org.junit.jupiter.api.condition.OS.LINUX,
		org.junit.jupiter.api.condition.OS.MAC
	})
	@DisplayName("rejects a symlinked mvnw wrapper and falls back to PATH 'mvn'")
	void shouldRejectSymlinkedMvnwAndFallBackToPathMvn(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");

		// Real wrapper script in a sibling dir; the working directory hosts only a SYMLINK to it.
		final Path realMvnwDir = tmp.resolve("real-tools");
		Files.createDirectories(realMvnwDir);
		final Path realMvnw = realMvnwDir.resolve("mvnw");
		Files.writeString(realMvnw, "#!/bin/sh\necho 'should not be invoked'\n");
		realMvnw.toFile().setExecutable(true);

		Files.createSymbolicLink(tmp.resolve("mvnw"), realMvnw);

		final AtomicReference<List<String>> capturedCmd = new AtomicReference<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> { capturedCmd.set(cmd); return List.of(); }
		);
		provider.discover();

		assertThat(capturedCmd.get().get(0))
			.as("Symlinked mvnw must be rejected — symlinks-not-followed invariant; fall back to PATH 'mvn'")
			.isEqualTo("mvn");
	}

	@Test
	@DisplayName("prefers an executable ./mvnw wrapper over a bare 'mvn'")
	void shouldPreferMvnwOverMvn(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final Path mvnw = tmp.resolve("mvnw");
		Files.writeString(mvnw, "#!/bin/sh\n");
		mvnw.toFile().setExecutable(true);

		final AtomicReference<List<String>> capturedCmd = new AtomicReference<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> { capturedCmd.set(cmd); return List.of(); }
		);
		provider.discover();

		assertThat(capturedCmd.get().get(0)).endsWith("mvnw");
	}

	@Test
	@DisplayName("skips Maven entirely when no pom.xml is at the working directory")
	void shouldSkipMavenWhenNoPomXml(@TempDir Path tmp) {
		final AtomicReference<Boolean> invoked = new AtomicReference<>(false);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> { invoked.set(true); return List.of(); }
		);
		provider.discover();
		assertThat(invoked.get()).isFalse();
	}

	/**
	 * Symlink-safety: a {@code pom.xml} that is itself a symbolic link must NOT trigger Maven
	 * discovery. Maven would resolve the link and pull dependencies from the linked target, which
	 * may live outside the configured working directory — the provider's documented invariant is
	 * "symlinks are never followed".
	 */
	@Test
	@org.junit.jupiter.api.condition.EnabledOnOs({
		org.junit.jupiter.api.condition.OS.LINUX,
		org.junit.jupiter.api.condition.OS.MAC
	})
	@DisplayName("skips Maven when pom.xml at the working directory is a symlink")
	void shouldSkipMavenWhenPomXmlIsSymlink(@TempDir Path tmp) throws Exception {
		// Real pom in a sibling directory; the provider's working directory holds a SYMLINK to it.
		final Path realPomDir = tmp.resolve("real-project");
		Files.createDirectories(realPomDir);
		Files.writeString(realPomDir.resolve("pom.xml"), "<project/>");

		final Path workDir = tmp.resolve("workdir");
		Files.createDirectories(workDir);
		Files.createSymbolicLink(workDir.resolve("pom.xml"), realPomDir.resolve("pom.xml"));

		final AtomicReference<Boolean> invoked = new AtomicReference<>(false);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			workDir, envName -> null,
			(cmd, cwd, t) -> { invoked.set(true); return List.of(); }
		);

		provider.discover();

		assertThat(invoked.get())
			.as("Symlinked pom.xml must not trigger Maven — symlinks-not-followed invariant")
			.isFalse();
		assertThat(provider.hasPomAtRoot())
			.as("hasPomAtRoot() must also report false for a symlinked pom.xml — same invariant")
			.isFalse();
	}

	/**
	 * A {@code pom.xml} that exists but is structurally empty is a real-world case (e.g. partial
	 * sync, broken build) — the provider must still invoke Maven (it's the runner's job to deal
	 * with the failure) and tolerate an empty result list without throwing.
	 */
	@Test
	@DisplayName("invokes Maven when pom.xml is present even if empty; tolerates empty result list")
	void shouldInvokeMavenWhenPomXmlIsEmpty(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "");

		final AtomicReference<Boolean> invoked = new AtomicReference<>(false);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> { invoked.set(true); return List.of(); }
		);

		final Set<String> entries = provider.discover();

		assertThat(invoked.get()).isTrue();
		assertThat(entries).isEmpty();
	}
}
