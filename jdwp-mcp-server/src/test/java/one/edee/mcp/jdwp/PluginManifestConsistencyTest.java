package one.edee.mcp.jdwp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the plugin's two manifests in step. Claude Code reads {@code .claude-plugin/} (marketplace +
 * plugin manifest); Codex and other Agent Plugins hosts read the root {@code plugin.json} and
 * {@code mcp.json} and prefer them over {@code .claude-plugin/plugin.json}.
 * A release bumps the version in three places, and a host that gets a stale or malformed manifest
 * fails quietly — Codex skips an {@code mcp.json} without {@code $schema} or with an unknown field
 * in a server entry without telling the user.
 *
 * <p>Surefire runs with the module directory as the working directory, so the repository root is
 * its parent.
 */
@DisplayName("plugin manifests — Claude Code and Agent Plugins (Codex) stay consistent")
class PluginManifestConsistencyTest {

	private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** Fields Codex accepts in a portable stdio server entry; any other field makes it skip the entry. */
	private static final Set<String> PORTABLE_STDIO_FIELDS = Set.of("type", "command", "args", "env", "cwd");

	@Test
	@DisplayName("the marketplace, its plugin entry and the root plugin.json carry the same version")
	void shouldCarryOneVersion() {
		final JsonNode marketplace = read(".claude-plugin/marketplace.json");
		final JsonNode portable = read("plugin.json");

		final String version = marketplace.path("version").stringValue();
		assertThat(version).matches("\\d+\\.\\d+\\.\\d+");
		assertThat(marketplace.path("plugins").path(0).path("version").stringValue()).isEqualTo(version);
		assertThat(portable.path("version").stringValue())
			.as("root plugin.json version — the release skill must bump it with marketplace.json")
			.isEqualTo(version);
	}

	@Test
	@DisplayName("every manifest names the same plugin")
	void shouldNameOnePlugin() {
		final String name = read(".claude-plugin/plugin.json").path("name").stringValue();

		assertThat(name).isEqualTo("jdwp-debugging");
		assertThat(read(".claude-plugin/marketplace.json").path("plugins").path(0).path("name").stringValue())
			.isEqualTo(name);
		assertThat(read("plugin.json").path("name").stringValue()).isEqualTo(name);
	}

	@Test
	@DisplayName("both manifests register the jdwp-inspector server, so tool names match across hosts")
	void shouldRegisterOneServerName() {
		assertThat(fieldNames(read(".claude-plugin/plugin.json").path("mcpServers"))).containsExactly("jdwp-inspector");
		assertThat(fieldNames(read("mcp.json").path("mcpServers"))).containsExactly("jdwp-inspector");
	}

	@Test
	@DisplayName("the portable manifests declare $schema, which Codex requires to read them")
	void shouldDeclareSchemas() {
		assertThat(read("plugin.json").path("$schema").stringValue())
			.isEqualTo("https://agent-plugins.org/schemas/1.0.0/plugin.schema.json");
		assertThat(read("mcp.json").path("$schema").stringValue())
			.isEqualTo("https://agent-plugins.org/schemas/1.0.0/mcp.schema.json");
	}

	@Test
	@DisplayName("mcp.json starts the launcher script through sh, with only fields Codex accepts")
	void shouldLaunchThroughTheLauncherScript() {
		final JsonNode server = read("mcp.json").path("mcpServers").path("jdwp-inspector");

		assertThat(fieldNames(server)).allMatch(PORTABLE_STDIO_FIELDS::contains);
		assertThat(server.path("type").stringValue()).isEqualTo("stdio");
		assertThat(server.path("command").stringValue()).isEqualTo("sh");
		assertThat(server.path("args").size()).isEqualTo(1);
		final String script = server.path("args").path(0).stringValue();
		assertThat(script).startsWith("${PLUGIN_ROOT}/");
		final Path launcher = ROOT.resolve(script.substring("${PLUGIN_ROOT}/".length()));
		assertThat(launcher).isRegularFile();
		assertThat(ROOT.resolve("hooks/ensure-server-jar.sh")).isRegularFile();
	}

	private static JsonNode read(String relativePath) {
		final Path file = ROOT.resolve(relativePath);
		assertThat(file).as("manifest %s", relativePath).isRegularFile();
		try {
			return JSON.readTree(Files.readString(file));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> fieldNames(JsonNode node) {
		final List<String> names = new ArrayList<>();
		node.propertyNames().forEach(names::add);
		return names;
	}
}
