package one.edee.mcp.jdwp.transport;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

	/** Appends one warning to the existing text and preserves the result and content metadata. */
	@Test
	void shouldWarnAboutOneUnknownParameterWithoutChangingResultShape() {
		final Map<String, Object> textMeta = Map.of("text-source", "tool");
		final Map<String, Object> resultMeta = Map.of("result-source", "tool");
		final Map<String, Object> structured = Map.of("status", "example");
		final McpSchema.ImageContent image = new McpSchema.ImageContent(null, "AAAA", "image/png");
		final McpSchema.TextContent first = new McpSchema.TextContent("first");
		final McpSchema.TextContent last = new McpSchema.TextContent(null, "last", textMeta);
		final McpSchema.CallToolResult original =
			new McpSchema.CallToolResult(List.of(first, last, image), true, structured, resultMeta);

		final McpSchema.CallToolResult reply = callWithArguments(Map.of("frame", 2, "typo", 3), original);

		assertThat(reply.content()).containsExactly(first, new McpSchema.TextContent(null,
			"last\n⚠ Ignored unknown parameter(s): typo. jdwp_probe accepts: frame, threadId.", textMeta), image);
		assertThat(reply.isError()).isTrue();
		assertThat(reply.structuredContent()).isEqualTo(structured);
		assertThat(reply.meta()).isEqualTo(resultMeta);
	}

	/** Reports several unknown names in one line and excludes the reserved metadata key. */
	@Test
	void shouldWarnAboutSeveralUnknownParameters() {
		final McpSchema.CallToolResult reply = callWithArguments(
			Map.of("zzz\nextra", 1, "_meta", Map.of(), "aaa", 2), result("ok"));

		assertThat(reply.content()).containsExactly(new McpSchema.TextContent(
			"ok\n⚠ Ignored unknown parameter(s): aaa, zzz\\nextra. jdwp_probe accepts: frame, threadId."));
	}

	/** Leaves calls with only declared or reserved names untouched. */
	@Test
	void shouldLeaveKnownReservedAndEmptyArgumentsAlone() {
		final McpSchema.CallToolResult original = result("ok");
		assertThat(callWithArguments(Map.of("threadId", 1L, "_meta", Map.of(), "progressToken", "call-1"), original)).isSameAs(original);
		assertThat(callWithArguments(Map.of(), original)).isSameAs(original);
	}

	/** Leaves a call with omitted arguments untouched. */
	@Test
	void shouldLeaveNullArgumentsAlone() {
		final McpSchema.CallToolResult original = result("ok");
		assertThat(callWithArguments(null, original)).isSameAs(original);
	}

	/** Calls a wrapped tool with a fixed schema and verifies that the handler still runs. */
	private static McpSchema.CallToolResult callWithArguments(
		@Nullable Map<String, Object> arguments, McpSchema.CallToolResult original
	) {
		final Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("frame", Map.of("type", "integer"));
		properties.put("threadId", Map.of("type", "integer"));
		final McpSchema.Tool tool = McpSchema.Tool.builder().name("jdwp_probe")
			.inputSchema(new McpSchema.JsonSchema("object", properties, List.of(), false, null, null))
			.build();
		final AtomicReference<McpSchema.CallToolRequest> seen = new AtomicReference<>();
		final SyncToolSpecification wrapped = ClientWorkspaceConfig.observingWorkspace(
			new SyncToolSpecification(tool, (exchange, request) -> {
				seen.set(request);
				return original;
			}), provider(new ClientWorkspace()));
		final McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("jdwp_probe", arguments);
		final McpSchema.CallToolResult reply = wrapped.callHandler().apply(null, request);
		assertThat(seen.get()).isSameAs(request);
		return reply;
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
