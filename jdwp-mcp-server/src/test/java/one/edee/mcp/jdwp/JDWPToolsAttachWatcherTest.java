package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link JDWPTools#jdwp_attach_watcher}: rejection of empty expressions, rejection of
 * blank labels, and the normal attach path.
 */
@DisplayName("jdwp_attach_watcher")
class JDWPToolsAttachWatcherTest {

	private WatcherManager watcherManager;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker tracker = mock(BreakpointTracker.class);
		watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
	}

	@Test
	@DisplayName("rejects an empty expression")
	void shouldRejectEmptyExpression() {
		final String result = tools.jdwp_attach_watcher(1, "label", "   ");

		assertThat(result).startsWith("Error: No expression provided");
	}

	@Test
	@DisplayName("happy path — registers the watcher and returns its id")
	void shouldRegisterWatcherAndReturnId() {
		final String result = tools.jdwp_attach_watcher(1, "trace-entity-id", "entity.id");

		assertThat(result).contains("Watcher attached successfully");
		assertThat(result).contains("Label: trace-entity-id");
		assertThat(result).contains("Expression: entity.id");
		assertThat(watcherManager.getWatchersForBreakpoint(1)).hasSize(1);
	}

	/**
	 * A blank ({@code ""} or whitespace-only) label is rejected with the same kind of error used
	 * for empty expressions. Without this guard, the watcher would be created with an empty label
	 * and become unidentifiable in {@code jdwp_list_all_watchers}.
	 */
	@Test
	@DisplayName("rejects a blank label")
	void shouldAcceptBlankLabel() {
		final String result = tools.jdwp_attach_watcher(1, "   ", "entity.id");

		assertThat(result).startsWith("Error: No label provided");
		assertThat(watcherManager.getWatchersForBreakpoint(1)).isEmpty();
	}
}
