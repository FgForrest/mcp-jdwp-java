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
 * Observes client workspace metadata and checks the argument names of tool calls.
 *
 * <ul>
 *   <li>Declares the experimental capability {@value ClientWorkspace#CODEX_SANDBOX_STATE_META}, so
 *       Codex attaches its sandbox state, including the session directory, to every tool call.
 *       Spring AI's {@code capabilitiesBuilder} bean is {@code @ConditionalOnMissingBean}; this one
 *       replaces it, and the auto-configuration still adds the tools, resources, prompts,
 *       completions and logging capabilities to it.</li>
 *   <li>Wraps the call handler of every annotated tool so that the request's {@code _meta} reaches
 *       {@link ClientWorkspace#observe} before the tool runs. Other clients ignore the capability
 *       and send no such {@code _meta}. The wrapper also refuses a request that lacks a required
 *       argument and appends a text warning when one supplies undeclared arguments.</li>
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
     * tool call reports its {@code _meta} to {@link ClientWorkspace} first, runs only with its
     * required arguments, and warns about undeclared ones. Static and lazy on
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
     * workspace, delegates to the original handler unless a required argument is missing, and warns
     * about undeclared argument names.
     */
    static SyncToolSpecification observingWorkspace(SyncToolSpecification tool,
                                                    ObjectProvider<ClientWorkspace> clientWorkspace) {
        final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
            tool.callHandler();
        return new SyncToolSpecification(tool.tool(), (exchange, request) -> {
            clientWorkspace.getObject().observe(request.meta());
            final McpSchema.CallToolResult refusal = refuseMissingArguments(tool.tool(), request.arguments());
            final McpSchema.CallToolResult result = refusal != null ? refusal : handler.apply(exchange, request);
            return warnAboutUnknownArguments(tool.tool(), request.arguments(), result);
        });
    }

    /**
     * The error reply to a call without a value for a required argument, or {@code null} when the
     * tool can run. Nothing below checks this: Spring AI passes the tool method a {@code null}, and
     * a primitive parameter fails on unboxing it before the method can name the argument.
     */
    private static McpSchema.@Nullable CallToolResult refuseMissingArguments(
        McpSchema.Tool tool, @Nullable Map<String, Object> arguments
    ) {
        final McpSchema.JsonSchema schema = tool.inputSchema();
        if (schema == null || schema.required() == null) {
            return null;
        }
        final StringBuilder missing = new StringBuilder();
        for (String name : schema.required()) {
            if (arguments == null || arguments.get(name) == null) {
                missing.append("\n  ").append(name).append(describe(schema, name));
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        final String reply = "Error: " + tool.name() + " did not run. Missing required parameter(s):" + missing;
        return McpSchema.CallToolResult.builder().isError(true).textContent(List.of(reply)).build();
    }

    /** The schema's description of an argument as {@code " — description"}, or an empty string. */
    private static String describe(McpSchema.JsonSchema schema, String name) {
        final Map<String, Object> properties = schema.properties();
        if (properties != null && properties.get(name) instanceof Map<?, ?> property
            && property.get("description") instanceof String description) {
            return " — " + description;
        }
        return "";
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
