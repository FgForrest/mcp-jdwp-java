package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
			new MarkedInstanceRegistry(),
			new JdiHealthMonitor(),
			defaultEmptyClasspathProvider(),
			fastLogpointsMock()
		);
	}

	/**
	 * A mock fast-logpoint service whose pending-removal retry, which the resume tools call after
	 * every release, restores nothing.
	 */
	static FastLogpointService fastLogpointsMock() {
		final FastLogpointService fastLogpoints = mock(FastLogpointService.class);
		when(fastLogpoints.retryPendingRemovals()).thenReturn("");
		return fastLogpoints;
	}

	/**
	 * Builds a deterministic {@link LocalProjectClasspathProvider} that contributes zero entries
	 * from every source. Working directory is a guaranteed-non-existent path so the depth-5
	 * filesystem scan short-circuits at the first {@code isDirectory} probe — keeps the diagnose
	 * path fast and reproducible regardless of what {@code user.dir} happens to contain.
	 */
	static LocalProjectClasspathProvider defaultEmptyClasspathProvider() {
		// Portable non-existent path: tmpdir is platform-correct (e.g. /tmp on Linux,
		// C:\Users\...\AppData\Local\Temp on Windows); appending a UUID-named child that we
		// never create gives us a guaranteed-absent root for the depth-5 scan to short-circuit on.
		return new LocalProjectClasspathProvider(
			Path.of(System.getProperty("java.io.tmpdir"), "jdwp-mcp-default-empty-" + java.util.UUID.randomUUID()),
			name -> null,
			(command, workingDirectory, timeoutSeconds) -> List.of()
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
			new MarkedInstanceRegistry(),
			new JdiHealthMonitor(),
			defaultEmptyClasspathProvider(),
			fastLogpointsMock()
		);
	}

	/**
	 * Overload that accepts a caller-supplied {@link LocalProjectClasspathProvider} — needed by
	 * tests that verify the local-classpath diagnose section. Defaults the other "extra" seams
	 * (MarkedInstanceRegistry, JdiHealthMonitor) to fresh instances.
	 */
	static JDWPTools newTools(
		JDIConnectionService jdiService,
		BreakpointTracker breakpointTracker,
		WatcherManager watcherManager,
		JdiExpressionEvaluator expressionEvaluator,
		EventHistory eventHistory,
		EvaluationGuard evaluationGuard,
		JvmDiscoveryService jvmDiscoveryService,
		LocalProjectClasspathProvider localClasspathProvider
	) {
		return new JDWPTools(
			jdiService,
			breakpointTracker,
			watcherManager,
			expressionEvaluator,
			eventHistory,
			evaluationGuard,
			jvmDiscoveryService,
			new MarkedInstanceRegistry(),
			new JdiHealthMonitor(),
			localClasspathProvider,
			fastLogpointsMock()
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
			markedInstances,
			new JdiHealthMonitor(),
			defaultEmptyClasspathProvider(),
			fastLogpointsMock()
		);
	}

	/**
	 * Overload that accepts a caller-supplied {@link JdiHealthMonitor} — needed by reconnect /
	 * health-state tests that need to inject a pre-stubbed monitor or assert on its lifecycle.
	 */
	static JDWPTools newTools(
		JDIConnectionService jdiService,
		BreakpointTracker breakpointTracker,
		WatcherManager watcherManager,
		JdiExpressionEvaluator expressionEvaluator,
		EventHistory eventHistory,
		EvaluationGuard evaluationGuard,
		JvmDiscoveryService jvmDiscoveryService,
		MarkedInstanceRegistry markedInstances,
		JdiHealthMonitor healthMonitor
	) {
		return new JDWPTools(
			jdiService,
			breakpointTracker,
			watcherManager,
			expressionEvaluator,
			eventHistory,
			evaluationGuard,
			jvmDiscoveryService,
			markedInstances,
			healthMonitor,
			defaultEmptyClasspathProvider(),
			fastLogpointsMock()
		);
	}

	/**
	 * Overload with caller-supplied watchers, evaluator and {@link FastLogpointService} — needed by
	 * tests of watchers on fast breakpoints.
	 */
	static JDWPTools newTools(
		JDIConnectionService jdiService,
		BreakpointTracker breakpointTracker,
		WatcherManager watcherManager,
		JdiExpressionEvaluator expressionEvaluator,
		FastLogpointService fastLogpoints
	) {
		return new JDWPTools(
			jdiService,
			breakpointTracker,
			watcherManager,
			expressionEvaluator,
			new EventHistory(),
			new EvaluationGuard(),
			mock(JvmDiscoveryService.class),
			new MarkedInstanceRegistry(),
			new JdiHealthMonitor(),
			defaultEmptyClasspathProvider(),
			fastLogpoints
		);
	}

	/**
	 * Overload that accepts a caller-supplied {@link FastLogpointService} — needed by tests of the
	 * fast-logpoint tool surface (consent gate, routing of clear / get_events / reset).
	 */
	static JDWPTools newTools(
		JDIConnectionService jdiService,
		BreakpointTracker breakpointTracker,
		EventHistory eventHistory,
		FastLogpointService fastLogpoints
	) {
		return new JDWPTools(
			jdiService,
			breakpointTracker,
			mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class),
			eventHistory,
			new EvaluationGuard(),
			mock(JvmDiscoveryService.class),
			new MarkedInstanceRegistry(),
			new JdiHealthMonitor(),
			defaultEmptyClasspathProvider(),
			fastLogpoints
		);
	}
}
