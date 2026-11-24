package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import one.edee.mcp.jdwp.transport.ClientWorkspaceConfig;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Calls every published tool without its required parameters, as an agent that guessed a parameter
 * name does: the reply names them instead of leaking the failed conversion of a null.
 */
@DisplayName("Required parameters of the published tools")
class JDWPToolsRequiredParametersTest {

	private BreakpointTracker tracker;
	private JDIConnectionService connection;
	private List<SyncToolSpecification> tools;

	/** Builds the tool specifications the way the server publishes them. */
	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		connection = mock(JDIConnectionService.class);
		final JDWPTools jdwpTools = JDWPToolsTestSupport.newTools(connection, tracker,
			mock(WatcherManager.class), mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			new JvmDiscoveryService());
		final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		beans.registerSingleton("clientWorkspace", new ClientWorkspace());
		final List<?> wrapped = (List<?>) Objects.requireNonNull(ClientWorkspaceConfig
			.clientWorkspaceToolWrapper(beans.getBeanProvider(ClientWorkspace.class))
			.postProcessAfterInitialization(
				new SyncMcpToolProvider(List.of(jdwpTools)).getToolSpecifications(), "toolSpecs"));
		tools = wrapped.stream().map(SyncToolSpecification.class::cast).toList();
	}

	@Test
	@DisplayName("an empty call is refused with the name of every required parameter")
	void shouldNameRequiredParametersOfEveryTool() {
		final List<SyncToolSpecification> requiring = tools.stream()
			.filter(tool -> !tool.tool().inputSchema().required().isEmpty()).toList();

		assertThat(requiring).hasSizeGreaterThan(20);
		for (SyncToolSpecification tool : requiring) {
			final McpSchema.CallToolResult reply = call(tool, Map.of());
			assertThat(text(reply)).as(tool.tool().name())
				.startsWith("Error: " + tool.tool().name() + " did not run. Missing required parameter(s):")
				.contains(tool.tool().inputSchema().required().stream().map(name -> "\n  " + name + " — ").toList());
			assertThat(reply.isError()).as(tool.tool().name()).isTrue();
		}
	}

	@Test
	@DisplayName("a misspelled breakpointId leaves the breakpoint set, the right name clears it")
	void shouldKeepBreakpointOnMisspelledId() {
		final int id = tracker.registerPendingBreakpoint("com.example.Order", 42, 2, "all");
		final SyncToolSpecification clear = tools.stream()
			.filter(tool -> tool.tool().name().equals("jdwp_clear_breakpoint")).findFirst().orElseThrow();

		assertThat(text(call(clear, Map.of("id", id)))).contains("\n  breakpointId — ", "unknown parameter(s): id.");
		assertThat(tracker.getAllPendingBreakpoints()).containsKey(id);

		assertThat(text(call(clear, Map.of("breakpointId", id)))).doesNotContain("did not run");
		assertThat(tracker.getAllPendingBreakpoints()).doesNotContainKey(id);
	}

	/** An omitted scope reaches the watcher tool as null. Blank and omitted scope use frame zero. */
	@Test
	void shouldDefaultWatcherScopeWhenOmittedOrBlank() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final ThreadReference thread = mock(ThreadReference.class);
		when(connection.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		final SyncToolSpecification watchers = tools.stream()
			.filter(tool -> tool.tool().name().equals("jdwp_evaluate_watchers")).findFirst().orElseThrow();

		assertThat(text(call(watchers, Map.of("threadId", 1L, "scope", "  ")))).contains("Scope: current_frame");
		assertThat(text(call(watchers, Map.of("threadId", 1L)))).contains("Scope: current_frame");
	}

	private static McpSchema.CallToolResult call(SyncToolSpecification tool, Map<String, Object> arguments) {
		return tool.callHandler().apply(null, new McpSchema.CallToolRequest(tool.tool().name(), arguments));
	}

	private static String text(McpSchema.CallToolResult reply) {
		return ((McpSchema.TextContent) reply.content().get(0)).text();
	}
}
