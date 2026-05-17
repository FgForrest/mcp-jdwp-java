package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;

import static org.mockito.Mockito.mock;

/**
 * Shared scaffolding for tests that build a {@link JDWPTools} instance. Removes the
 * seven-argument-constructor boilerplate that every JDWPTools test file had to copy. Two
 * factories are provided:
 *
 * <ul>
 *   <li>{@link #newToolsWithMocks()} — every collaborator is mocked. Suitable for tests that
 *       drive the tool surface without asserting on internal collaborators (e.g.
 *       primitive-parsing tests).</li>
 *   <li>{@link #newTools(JDIConnectionService, BreakpointTracker, WatcherManager,
 *       JdiExpressionEvaluator, EventHistory, EvaluationGuard, JvmDiscoveryService)} — caller
 *       supplies every collaborator. Use when a test needs a real {@link BreakpointTracker} or
 *       wants to verify interactions on a collaborator mock.</li>
 * </ul>
 *
 * <p>Mirrors the {@link JDIConnectionServiceTestSupport} and {@link JdiEventListenerTestSupport}
 * patterns used by other test classes in this package.
 */
final class JDWPToolsTestSupport {

	private JDWPToolsTestSupport() {
	}

	/**
	 * Builds a {@link JDWPTools} with every collaborator mocked. The {@link EvaluationGuard} is
	 * still a real instance because it carries no I/O and tests routinely rely on its actual
	 * enter/exit bookkeeping to verify reentrancy paths.
	 */
	static JDWPTools newToolsWithMocks() {
		return new JDWPTools(
			mock(JDIConnectionService.class),
			mock(BreakpointTracker.class),
			mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class),
			mock(EventHistory.class),
			new EvaluationGuard(),
			mock(JvmDiscoveryService.class),
			new MarkedInstanceRegistry()
		);
	}

	/**
	 * Builds a {@link JDWPTools} from the caller-supplied collaborators. Centralises the
	 * seven-argument constructor call so that future signature changes only need to update this
	 * factory.
	 */
	static JDWPTools newTools(
		JDIConnectionService jdiService,
		BreakpointTracker breakpointTracker,
		WatcherManager watcherManager,
		JdiExpressionEvaluator expressionEvaluator,
		EventHistory eventHistory,
		EvaluationGuard evaluationGuard,
		JvmDiscoveryService jvmDiscoveryService
	) {
		return new JDWPTools(
			jdiService,
			breakpointTracker,
			watcherManager,
			expressionEvaluator,
			eventHistory,
			evaluationGuard,
			jvmDiscoveryService,
			new MarkedInstanceRegistry()
		);
	}

	/**
	 * Overload that accepts a caller-supplied {@link MarkedInstanceRegistry} — needed by tests that
	 * verify mark-related behaviour through the tool surface (overview, clear, locals footer).
	 */
	static JDWPTools newTools(
		JDIConnectionService jdiService,
		BreakpointTracker breakpointTracker,
		WatcherManager watcherManager,
		JdiExpressionEvaluator expressionEvaluator,
		EventHistory eventHistory,
		EvaluationGuard evaluationGuard,
		JvmDiscoveryService jvmDiscoveryService,
		MarkedInstanceRegistry markedInstances
	) {
		return new JDWPTools(
			jdiService,
			breakpointTracker,
			watcherManager,
			expressionEvaluator,
			eventHistory,
			evaluationGuard,
			jvmDiscoveryService,
			markedInstances
		);
	}
}
