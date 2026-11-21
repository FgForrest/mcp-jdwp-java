package one.edee.mcp.jdwp.transport;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the two pieces of {@link ClientWorkspaceConfig}: the capabilities builder that declares the
 * Codex sandbox-state capability, and the post-processor that routes every tool call's
 * {@code _meta} to {@link ClientWorkspace} before the tool runs.
 */
@DisplayName("ClientWorkspaceConfig — capability and tool-call wrapping")
class ClientWorkspaceConfigTest {

	@Test
	@DisplayName("capabilities declare codex/sandbox-state-meta and keep what the auto-config adds")
	void shouldDeclareCodexCapabilityAlongsideTools() {
		final McpSchema.ServerCapabilities capabilities = new ClientWorkspaceConfig().capabilitiesBuilder()
			.tools(true)
			.logging()
			.build();

		assertThat(capabilities.experimental()).containsOnlyKeys(ClientWorkspace.CODEX_SANDBOX_STATE_META);
		assertThat(capabilities.tools()).isNotNull();
		assertThat(capabilities.logging()).isNotNull();
	}

	@Test
	@DisplayName("a wrapped tool sees the session directory reported with its own call")
	void shouldObserveMetaBeforeDelegating(@TempDir Path tmp) {
		final ClientWorkspace workspace = new ClientWorkspace();
		final AtomicReference<Path> seenByTool = new AtomicReference<>();
		final SyncToolSpecification tool = tool("jdwp_probe", (exchange, request) -> {
			seenByTool.set(workspace.reportedDirectory());
			return result("ok");
		});

		final List<?> wrapped = postProcess(List.of(tool), workspace);
		final SyncToolSpecification wrappedTool = (SyncToolSpecification) wrapped.get(0);
		final McpSchema.CallToolResult callResult = wrappedTool.callHandler().apply(null,
			new McpSchema.CallToolRequest("jdwp_probe", Map.of(), Map.of(
				ClientWorkspace.CODEX_SANDBOX_STATE, Map.of(ClientWorkspace.SANDBOX_CWD, tmp.toUri().toString()))));

		assertThat(seenByTool.get()).isEqualTo(tmp.normalize());
		assertThat(callResult).isEqualTo(result("ok"));
		assertThat(wrappedTool.tool()).isSameAs(tool.tool());
	}

	@Test
	@DisplayName("every tool of a specification list is wrapped, in order")
	void shouldWrapEveryToolInOrder() {
		final SyncToolSpecification first = tool("first", (exchange, request) -> result("1"));
		final SyncToolSpecification second = tool("second", (exchange, request) -> result("2"));

		final List<?> wrapped = postProcess(new ArrayList<>(List.of(first, second)), new ClientWorkspace());

		assertThat(wrapped).hasSize(2);
		assertThat(wrapped).extracting(spec -> ((SyncToolSpecification) spec).tool().name())
			.containsExactly("first", "second");
		assertThat(wrapped.get(0)).isNotSameAs(first);
		assertThat(((SyncToolSpecification) wrapped.get(1)).callHandler()
			.apply(null, new McpSchema.CallToolRequest("second", Map.of())))
			.isEqualTo(result("2"));
	}

	@Test
	@DisplayName("a call without _meta still reaches the tool and reports nothing")
	void shouldPassCallsWithoutMeta() {
		final ClientWorkspace workspace = new ClientWorkspace();
		final SyncToolSpecification tool = tool("plain", (exchange, request) -> result("plain"));

		final SyncToolSpecification wrapped = (SyncToolSpecification) postProcess(List.of(tool), workspace).get(0);

		assertThat(wrapped.callHandler().apply(null, new McpSchema.CallToolRequest("plain", Map.of())))
			.isEqualTo(result("plain"));
		assertThat(workspace.reportedDirectory()).isNull();
	}

	@Test
	@DisplayName("beans that are not lists of tool specifications pass through untouched")
	void shouldLeaveOtherBeansAlone() {
		final BeanPostProcessor processor = ClientWorkspaceConfig.clientWorkspaceToolWrapper(provider(new ClientWorkspace()));
		final List<String> strings = List.of("a");
		final List<Object> empty = List.of();
		final List<Object> mixed = List.of(tool("t", (exchange, request) -> result("t")), "not-a-tool");
		final Object plain = new Object();

		assertThat(processor.postProcessAfterInitialization(strings, "strings")).isSameAs(strings);
		assertThat(processor.postProcessAfterInitialization(empty, "empty")).isSameAs(empty);
		assertThat(processor.postProcessAfterInitialization(mixed, "mixed")).isSameAs(mixed);
		assertThat(processor.postProcessAfterInitialization(plain, "plain")).isSameAs(plain);
	}

	private static List<?> postProcess(List<?> toolSpecs, ClientWorkspace workspace) {
		final Object processed = ClientWorkspaceConfig.clientWorkspaceToolWrapper(provider(workspace))
			.postProcessAfterInitialization(toolSpecs, "toolSpecs");
		assertThat(processed).isInstanceOf(List.class);
		return (List<?>) processed;
	}

	private static ObjectProvider<ClientWorkspace> provider(ClientWorkspace workspace) {
		final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		beanFactory.registerSingleton("clientWorkspace", workspace);
		return beanFactory.getBeanProvider(ClientWorkspace.class);
	}

	private static SyncToolSpecification tool(
		String name,
		BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler
	) {
		final McpSchema.Tool tool = McpSchema.Tool.builder()
			.name(name)
			.inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
			.build();
		return new SyncToolSpecification(tool, handler);
	}

	private static McpSchema.CallToolResult result(String text) {
		return McpSchema.CallToolResult.builder().textContent(List.of(text)).build();
	}
}
