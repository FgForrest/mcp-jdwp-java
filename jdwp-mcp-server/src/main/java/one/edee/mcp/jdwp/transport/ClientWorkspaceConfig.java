package one.edee.mcp.jdwp.transport;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Lets the server learn the MCP client's working directory (see {@link ClientWorkspace}).
 *
 * <ul>
 *   <li>Declares the experimental capability {@value ClientWorkspace#CODEX_SANDBOX_STATE_META}, so
 *       Codex attaches its sandbox state, including the session directory, to every tool call.
 *       Spring AI's {@code capabilitiesBuilder} bean is {@code @ConditionalOnMissingBean}; this one
 *       replaces it, and the auto-configuration still adds the tools, resources, prompts,
 *       completions and logging capabilities to it.</li>
 *   <li>Wraps the call handler of every annotated tool so that the request's {@code _meta} reaches
 *       {@link ClientWorkspace#observe} before the tool runs. Other clients ignore the capability
 *       and send no such {@code _meta}; for them the wrapper does nothing.</li>
 * </ul>
 */
@Configuration
public class ClientWorkspaceConfig {

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
     * tool call reports its {@code _meta} to {@link ClientWorkspace} first. Static and lazy on
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
     * workspace and then delegates to the original handler.
     */
    static SyncToolSpecification observingWorkspace(SyncToolSpecification tool,
                                                    ObjectProvider<ClientWorkspace> clientWorkspace) {
        final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
            tool.callHandler();
        return new SyncToolSpecification(tool.tool(), (exchange, request) -> {
            clientWorkspace.getObject().observe(request.meta());
            return handler.apply(exchange, request);
        });
    }
}
