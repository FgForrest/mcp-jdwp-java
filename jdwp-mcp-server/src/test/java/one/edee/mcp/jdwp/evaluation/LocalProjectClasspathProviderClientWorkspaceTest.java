package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link LocalProjectClasspathProvider} scans the directory an MCP client reports (Codex
 * starts the server in the plugin's install directory, not in the project) and falls back to the
 * launch directory when no client reports one (Claude Code starts the server in the project).
 */
@DisplayName("LocalProjectClasspathProvider — project directory reported by the MCP client")
class LocalProjectClasspathProviderClientWorkspaceTest {

	@Test
	@DisplayName("without a report the launch directory is scanned")
	void shouldScanLaunchDirectoryWithoutReport(@TempDir Path tmp) throws Exception {
		final Path launch = project(tmp.resolve("launch"));
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			launch, new ClientWorkspace(), envName -> null, (cmd, cwd, t) -> List.of());

		assertThat(provider.getWorkingDirectory()).isEqualTo(launch);
		assertThat(provider.isWorkingDirectoryReportedByClient()).isFalse();
		assertThat(provider.discover()).containsExactly(launch.resolve("target/classes").toString());
	}

	@Test
	@DisplayName("a reported session directory replaces the launch directory for the scan, Maven and pom check")
	void shouldScanReportedDirectory(@TempDir Path tmp) throws Exception {
		final Path launch = Files.createDirectories(tmp.resolve("plugin-root"));
		final Path session = project(tmp.resolve("session"));
		final ClientWorkspace workspace = reportedAt(session);
		final List<Path> mavenDirectories = new ArrayList<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			launch, workspace, envName -> null,
			(cmd, cwd, t) -> { mavenDirectories.add(cwd); return List.of("/m2/dep.jar"); });

		assertThat(provider.getWorkingDirectory()).isEqualTo(session);
		assertThat(provider.isWorkingDirectoryReportedByClient()).isTrue();
		assertThat(provider.hasPomAtRoot()).isTrue();
		assertThat(provider.discover())
			.containsExactly(session.resolve("target/classes").toString(), "/m2/dep.jar");
		assertThat(mavenDirectories).containsExactly(session);
	}

	@Test
	@DisplayName("a report arriving after a scan of the launch directory triggers a new scan")
	void shouldRescanWhenDirectoryChanges(@TempDir Path tmp) throws Exception {
		final Path launch = project(tmp.resolve("launch"));
		final Path session = project(tmp.resolve("session"));
		final ClientWorkspace workspace = new ClientWorkspace();
		final List<Path> mavenDirectories = new ArrayList<>();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			launch, workspace, envName -> null,
			(cmd, cwd, t) -> { mavenDirectories.add(cwd); return List.of(); });

		assertThat(provider.discover()).containsExactly(launch.resolve("target/classes").toString());
		assertThat(provider.peekCachedBreakdown()).isNotNull();

		workspace.observe(codexMeta(session));

		assertThat(provider.peekCachedBreakdown())
			.as("the cached breakdown belongs to the launch directory and must not be shown for the session")
			.isNull();
		assertThat(provider.discover()).containsExactly(session.resolve("target/classes").toString());
		assertThat(provider.discover()).containsExactly(session.resolve("target/classes").toString());
		assertThat(mavenDirectories).containsExactly(launch, session);
	}

	/** Creates a Maven-looking project: {@code pom.xml} plus {@code target/classes}. */
	private static Path project(Path dir) throws Exception {
		Files.createDirectories(dir.resolve("target/classes"));
		Files.writeString(dir.resolve("pom.xml"), "<project/>");
		return dir;
	}

	private static ClientWorkspace reportedAt(Path dir) {
		final ClientWorkspace workspace = new ClientWorkspace();
		workspace.observe(codexMeta(dir));
		return workspace;
	}

	private static Map<String, Object> codexMeta(Path dir) {
		return Map.of("codex/sandbox-state", Map.of("sandboxCwd", dir.toUri().toString()));
	}
}
