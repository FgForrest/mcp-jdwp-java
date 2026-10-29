package one.edee.mcp.jdwp;

import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

import static org.mockito.Mockito.mock;

/**
 * Shared scaffolding for tests that exercise {@link JDIConnectionService} in isolation. Removes the
 * boilerplate of wiring the listener / tracker / event history / watcher manager / evaluation
 * guard collaborators by hand in every {@code @BeforeEach}. Two factories are provided:
 *
 * <ul>
 *   <li>{@link #newServiceWithMocks()} — every collaborator is either a real no-arg instance
 *       (when cheap and side-effect free, like {@link BreakpointTracker}, {@link EventHistory},
 *       {@link WatcherManager}, {@link EvaluationGuard}) or a Mockito mock (the
 *       {@link JdiEventListener}, whose constructor pulls in compilation machinery).</li>
 *   <li>{@link #newServiceWithCollaborators(JdiEventListener, BreakpointTracker, EventHistory,
 *       WatcherManager, EvaluationGuard)} — accepts caller-supplied collaborators so a test can
 *       hand in Mockito spies / verify interactions on the tracker or watcher manager.</li>
 * </ul>
 *
 * <p>Also exposes {@link #setVm(JDIConnectionService, VirtualMachine)} so tests can plant a mocked
 * {@link VirtualMachine} into the service without going through the full {@code connect()} path,
 * which would otherwise require a live JDWP server. This is necessary for tests of
 * {@link JDIConnectionService#notifyVmDied()} and similar methods that branch on the
 * {@code vm != null} state.
 *
 * <p>Mirrors the {@link JdiEventListenerTestSupport} pattern used by the listener tests.
 */
final class JDIConnectionServiceTestSupport {

	private JDIConnectionServiceTestSupport() {
	}

	/**
	 * Builds a {@link JDIConnectionService} with default collaborators: real {@link BreakpointTracker},
	 * {@link EventHistory}, {@link WatcherManager}, {@link EvaluationGuard}, and a mocked
	 * {@link JdiEventListener}. Suitable for tests that only need the service's local state — they
	 * do not assert on the collaborators.
	 */
	static JDIConnectionService newServiceWithMocks() {
		return new JDIConnectionService(
			mock(JdiEventListener.class),
			new BreakpointTracker(),
			new EventHistory(),
			new WatcherManager(),
			new EvaluationGuard(),
			new MarkedInstanceRegistry(),
			new JdiHealthMonitor()
		);
	}

	/**
	 * Builds a {@link JDIConnectionService} with caller-supplied collaborators. Use this when the
	 * test needs to verify interactions (e.g., that {@code BreakpointTracker.reset()} was called)
	 * or pre-populate state on a collaborator before invoking the service.
	 */
	static JDIConnectionService newServiceWithCollaborators(JdiEventListener listener,
			BreakpointTracker tracker, EventHistory history, WatcherManager watchers,
			EvaluationGuard guard) {
		return new JDIConnectionService(listener, tracker, history, watchers, guard, new MarkedInstanceRegistry(), new JdiHealthMonitor());
	}

	/**
	 * Same as {@link #newServiceWithCollaborators(JdiEventListener, BreakpointTracker, EventHistory,
	 * WatcherManager, EvaluationGuard)} but lets the caller hand in a {@link MarkedInstanceRegistry}
	 * — needed by tests that verify {@code clearAll()} is invoked on lifecycle events.
	 */
	static JDIConnectionService newServiceWithCollaborators(JdiEventListener listener,
			BreakpointTracker tracker, EventHistory history, WatcherManager watchers,
			EvaluationGuard guard, MarkedInstanceRegistry marks) {
		return new JDIConnectionService(listener, tracker, history, watchers, guard, marks, new JdiHealthMonitor());
	}

	/**
	 * Builds a {@link JDIConnectionService} with a real {@link BreakpointTracker} /
	 * {@link EventHistory} / {@link WatcherManager} / {@link EvaluationGuard} but a real
	 * {@link JdiEventListener} backed by a mocked {@link JdiExpressionEvaluator}. Use this when the
	 * test exercises code paths (like the failed-attach path) that touch the listener's identity
	 * (e.g., {@code eventListener.setVmDeathHook}) but never actually drive its event loop.
	 */
	static JDIConnectionService newServiceWithRealListener() {
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final JdiEventListener listener = new JdiEventListener(tracker, history, evaluator, guard, null, new MarkedInstanceRegistry());
		return new JDIConnectionService(listener, tracker, history, watchers, guard, new MarkedInstanceRegistry(), new JdiHealthMonitor());
	}

	/**
	 * Plants {@code vm} into the service's private {@code vm} field via reflection. Lets a test
	 * exercise methods that branch on the {@code vm != null} state without going through the full
	 * {@code connect()} path (which would require a live JDWP server). Pass {@code null} to clear
	 * the field.
	 */
	static void setVm(JDIConnectionService service, @Nullable VirtualMachine vm) {
		try {
			final Field field = JDIConnectionService.class.getDeclaredField("vm");
			field.setAccessible(true);
			field.set(service, vm);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Failed to plant 'vm' field via reflection", e);
		}
	}

	/**
	 * Plants the auto-reconnect seed ({@code lastHost} / {@code lastPort} private fields) on the
	 * service so tests can verify {@link JDIConnectionService#notifyVmDied()} preserves them
	 * without requiring a real successful attach.
	 */
	static void setLastSuccessfulAttach(JDIConnectionService service, String host, int port) {
		try {
			final Field hostField = JDIConnectionService.class.getDeclaredField("lastHost");
			hostField.setAccessible(true);
			hostField.set(service, host);
			final Field portField = JDIConnectionService.class.getDeclaredField("lastPort");
			portField.setAccessible(true);
			portField.set(service, port);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Failed to plant 'lastHost'/'lastPort' fields via reflection", e);
		}
	}
}
