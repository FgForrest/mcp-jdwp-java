package one.edee.mcp.jdwp.transport;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Observes client workspace metadata and reports undeclared arguments on tool calls.
 *
 * <ul>
 *   <li>Declares the experimental capability {@value ClientWorkspace#CODEX_SANDBOX_STATE_META}, so
 *       Codex attaches its sandbox state, including the session directory, to every tool call.
 *       Spring AI's {@code capabilitiesBuilder} bean is {@code @ConditionalOnMissingBean}; this one
 *       replaces it, and the auto-configuration still adds the tools, resources, prompts,
 *       completions and logging capabilities to it.</li>
 *   <li>Wraps the call handler of every annotated tool so that the request's {@code _meta} reaches
 *       {@link ClientWorkspace#observe} before the tool runs. Other clients ignore the capability
 *       and send no such {@code _meta}. The wrapper also appends a text warning when a request
 *       supplies undeclared arguments.</li>
 * </ul>
 */
@Configuration
public class ClientWorkspaceConfig {

    /** MCP request metadata keys, also tolerated if a client puts them in arguments. */
    private static final Set<String> RESERVED_ARGUMENTS = Set.of("_meta", "progressToken");

    /**
     * Server capabilities builder that starts with the Codex sandbox-state capability.
     *
     * @return the builder Spring AI's {@code McpServerAutoConfiguration} completes and builds
     */
    @Bean
    public McpSchema.ServerCapabilities.Builder capabilitiesBuilder() {
        return McpSchema.ServerCapabilities.builder()
            .experimental(Map.of(ClientWorkspace.CODEX_SANDBOX_STATE_META, Map.of()));
    }

    /**
     * Wraps every {@link SyncToolSpecification} list bean (Spring AI's {@code toolSpecs}) so each
     * tool call reports its {@code _meta} to {@link ClientWorkspace} first and warns about
     * undeclared arguments after the handler runs. Static and lazy on
     * {@link ClientWorkspace}, as a {@link BeanPostProcessor} must not pull ordinary beans into
     * early initialisation.
     *
     * @param clientWorkspace provider of the workspace holder
     * @return the post-processor
     */
    @Bean
    public static BeanPostProcessor clientWorkspaceToolWrapper(ObjectProvider<ClientWorkspace> clientWorkspace) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof List<?> list) || list.isEmpty()) {
                    return bean;
                }
                final List<SyncToolSpecification> wrapped = new ArrayList<>(list.size());
                for (Object element : list) {
                    // Only a list made entirely of tool specifications is Spring AI's toolSpecs.
                    if (!(element instanceof SyncToolSpecification tool)) {
                        return bean;
                    }
                    wrapped.add(observingWorkspace(tool, clientWorkspace));
                }
                return wrapped;
            }
        };
    }

    /**
     * Returns a copy of {@code tool} whose handler reports the request's {@code _meta} to the
     * workspace, delegates to the original handler, and warns about undeclared argument names.
     */
    static SyncToolSpecification observingWorkspace(SyncToolSpecification tool,
                                                    ObjectProvider<ClientWorkspace> clientWorkspace) {
        final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
            tool.callHandler();
        return new SyncToolSpecification(tool.tool(), (exchange, request) -> {
            clientWorkspace.getObject().observe(request.meta());
            final McpSchema.CallToolResult result = handler.apply(exchange, request);
            return warnAboutUnknownArguments(tool.tool(), request.arguments(), result);
        });
    }

    /** Adds one warning to the last existing text block, preserving every other part of the result. */
    private static McpSchema.CallToolResult warnAboutUnknownArguments(
        McpSchema.Tool tool, @Nullable Map<String, Object> arguments, McpSchema.CallToolResult result
    ) {
        final McpSchema.JsonSchema schema = tool.inputSchema();
        if (arguments == null || arguments.isEmpty() || schema == null) {
            return result;
        }
        final Map<String, Object> properties = schema.properties();
        final Set<String> accepted = properties == null ? Set.of() : properties.keySet();
        final List<String> unknown = arguments.keySet().stream()
            .filter(name -> !RESERVED_ARGUMENTS.contains(name) && !accepted.contains(name))
            .sorted()
            .map(name -> name.replace("\r", "\\r").replace("\n", "\\n"))
            .toList();
        final List<McpSchema.Content> content = result.content();
        if (unknown.isEmpty() || content == null) {
            return result;
        }
        for (int i = content.size() - 1; i >= 0; i--) {
            if (content.get(i) instanceof McpSchema.TextContent text) {
                final String names = accepted.isEmpty() ? "no parameters" : String.join(", ", accepted);
                final String warning = "⚠ Ignored unknown parameter(s): " + String.join(", ", unknown)
                    + ". " + tool.name() + " accepts: " + names + ".";
                final List<McpSchema.Content> amended = new ArrayList<>(content);
                final String original = Objects.toString(text.text(), "");
                final String separator = original.isEmpty() || original.endsWith("\n") ? "" : "\n";
                amended.set(i, new McpSchema.TextContent(text.annotations(), original + separator + warning, text.meta()));
                return new McpSchema.CallToolResult(amended, result.isError(), result.structuredContent(), result.meta());
            }
        }
        return result;
    }
}
