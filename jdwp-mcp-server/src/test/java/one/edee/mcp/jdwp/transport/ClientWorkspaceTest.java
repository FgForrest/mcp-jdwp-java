package one.edee.mcp.jdwp.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests how {@link ClientWorkspace} reads the session directory Codex attaches to tool calls as
 * {@code _meta["codex/sandbox-state"].sandboxCwd}.
 */
@DisplayName("ClientWorkspace — session directory from tool-call _meta")
class ClientWorkspaceTest {

	@Test
	@DisplayName("reports nothing until a client sends a session directory")
	void shouldReportNothingInitially() {
		assertThat(new ClientWorkspace().reportedDirectory()).isNull();
	}

	@Test
	@DisplayName("takes the directory from a file:// sandboxCwd")
	void shouldTakeDirectoryFromFileUri(@TempDir Path tmp) {
		final ClientWorkspace workspace = new ClientWorkspace();

		workspace.observe(codexMeta(tmp.toUri().toString()));

		assertThat(workspace.reportedDirectory()).isEqualTo(tmp.normalize());
	}

	@Test
	@DisplayName("accepts a plain absolute path and normalises it")
	void shouldAcceptPlainAbsolutePath(@TempDir Path tmp) throws Exception {
		Files.createDirectory(tmp.resolve("module"));
		final ClientWorkspace workspace = new ClientWorkspace();

		workspace.observe(codexMeta(tmp.resolve("module").resolve("..").toString()));

		assertThat(workspace.reportedDirectory()).isEqualTo(tmp.normalize());
	}

	@Test
	@DisplayName("the latest valid report wins")
	void shouldFollowLaterReports(@TempDir Path tmp) throws Exception {
		final Path first = Files.createDirectory(tmp.resolve("first"));
		final Path second = Files.createDirectory(tmp.resolve("second"));
		final ClientWorkspace workspace = new ClientWorkspace();

		workspace.observe(codexMeta(first.toUri().toString()));
		workspace.observe(codexMeta(second.toUri().toString()));

		assertThat(workspace.reportedDirectory()).isEqualTo(second);
	}

	@Test
	@DisplayName("ignores a directory that does not exist on this host and keeps the previous report")
	void shouldIgnoreMissingDirectory(@TempDir Path tmp) {
		final ClientWorkspace workspace = new ClientWorkspace();
		workspace.observe(codexMeta(tmp.toUri().toString()));

		workspace.observe(codexMeta(tmp.resolve("does-not-exist").toUri().toString()));

		assertThat(workspace.reportedDirectory()).isEqualTo(tmp.normalize());
	}

	@Test
	@DisplayName("ignores a regular file, a relative path, another URI scheme and a blank value")
	void shouldIgnoreValuesThatAreNotLocalDirectories(@TempDir Path tmp) throws Exception {
		final Path file = Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final ClientWorkspace workspace = new ClientWorkspace();

		workspace.observe(codexMeta(file.toUri().toString()));
		workspace.observe(codexMeta("relative/dir"));
		workspace.observe(codexMeta("https://example.com/project"));
		workspace.observe(codexMeta("  "));

		assertThat(workspace.reportedDirectory()).isNull();
	}

	@Test
	@DisplayName("ignores missing, null and wrongly typed _meta without throwing")
	void shouldIgnoreMalformedMeta() {
		final ClientWorkspace workspace = new ClientWorkspace();
		final Map<String, Object> wrongStateType = Map.of(ClientWorkspace.CODEX_SANDBOX_STATE, "not-a-map");
		final Map<String, Object> wrongCwdType = Map.of(
			ClientWorkspace.CODEX_SANDBOX_STATE, Map.of(ClientWorkspace.SANDBOX_CWD, 42));
		final Map<String, Object> malformedUri = codexMeta("file://host:port:bad/%%");

		assertThatCode(() -> {
			workspace.observe(null);
			workspace.observe(Map.of());
			workspace.observe(Map.of("progressToken", 0));
			workspace.observe(wrongStateType);
			workspace.observe(wrongCwdType);
			workspace.observe(malformedUri);
		}).doesNotThrowAnyException();
		assertThat(workspace.reportedDirectory()).isNull();
	}

	/** Builds the {@code _meta} shape Codex sends with each tool call. */
	private static Map<String, Object> codexMeta(String sandboxCwd) {
		final Map<String, Object> state = new HashMap<>();
		state.put(ClientWorkspace.SANDBOX_CWD, sandboxCwd);
		state.put("permissionProfile", Map.of("type", "disabled"));
		return Map.of(ClientWorkspace.CODEX_SANDBOX_STATE, state, "progressToken", 0);
	}
}
