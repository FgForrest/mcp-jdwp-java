package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.*;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.interpret.EvaluationRouter;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/** Exercises interpreted exception logpoints through the production listener on both target JDKs. */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@Timeout(90)
class InterpreterExceptionLogpointLiveTest {

    /** Calls an accessor on the actual event binding under dispatcher admission. */
    @Test
    void bindsTheThrownObjectInTheProductionExceptionLogpoint() throws Exception {
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (InterpreterTargetVm target = InterpreterTargetVm.start(javaHome)) {
                checkExceptionLogpoint(target.port(), "$exception.getMessage()", true, null);
            }
        }
    }

    /** Reads the thrown object's private message without a call. */
    @Test
    void navigatesTheThrownObjectsPrivateMessageField() throws Exception {
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (InterpreterTargetVm target = InterpreterTargetVm.start(javaHome)) {
                checkExceptionLogpoint(target.port(), "$exception.detailMessage", false, null);
            }
        }
    }

    /** A failing interpreted condition still logs the result of the logpoint block. */
    @Test
    void logsAfterABlockConditionRaisesAnException() throws Exception {
        for (final Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (InterpreterTargetVm target = InterpreterTargetVm.start(javaHome)) {
                checkExceptionLogpoint(target.port(), "{ var message = $exception.detailMessage; return message; }",
                    false, "{ int quotient = 1 / 0; return quotient == 0; }");
            }
        }
    }

    /** Verifies the logpoint result and the condition's fail-safe event when a condition is supplied. */
    private static void checkExceptionLogpoint(int port, String expression, boolean invokes, @Nullable String condition)
        throws Exception {
        final BreakpointTracker tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final EvaluationGuard guard = new EvaluationGuard();
        final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
        final WatcherManager watchers = new WatcherManager();
        final JdiHealthMonitor health = new JdiHealthMonitor();
        final LocalProjectClasspathProvider noLocalClasspath = InterpreterHarnessLiveTest.noLocalClasspath();
        // The server uses a lazy dependency for this evaluator/listener cycle, as does LiveSession.
        final AtomicReference<JdiExpressionEvaluator> delegate = new AtomicReference<>();
        final JdiExpressionEvaluator lazyEvaluator = mock(JdiExpressionEvaluator.class, invocation -> {
            try {
                return invocation.getMethod().invoke(delegate.get(), invocation.getArguments());
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        final EvaluationRouter router = new EvaluationRouter(lazyEvaluator, EvaluationRouter.Mode.AUTO);
        final JdiEventListener listener = new JdiEventListener(tracker, history, lazyEvaluator, guard, null, marks, router);
        final JDIConnectionService connection = new JDIConnectionService(listener, tracker, history, watchers, guard, marks, health,
            mock(JvmDiscoveryService.class));
        final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(new InMemoryJavaCompiler(), connection, guard, noLocalClasspath);
        delegate.set(evaluator);
        final FastLogpointService fast = new FastLogpointService(tracker, history, guard, evaluator, listener, connection);
        final JDWPTools tools = new JDWPTools(connection, tracker, watchers, router, history, guard,
            mock(JvmDiscoveryService.class), marks, health, noLocalClasspath, fast);
        try {
            assertThat(connection.connect("127.0.0.1", port)).startsWith("Connected");
            assertThat(tools.jdwp_resume_until_event(10_000)).contains("via=vm_start");
            assertThat(tools.jdwp_set_exception_logpoint("java.lang.IllegalArgumentException", expression,
                condition, true, false, null, null, false, null, null)).contains("ID:").doesNotContain("Error");
            assertThat(tools.jdwp_resume()).startsWith("Resumed:");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(history.getRecent(20)).anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo("EXCEPTION_LOG");
                    assertThat(event.details()).containsEntry("result", "fixture exception");
                }));
            if (condition != null) {
                assertThat(history.getRecent(20)).anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo("CONDITION_ERROR");
                    assertThat(event.details().get("error")).contains("ArithmeticException");
                });
                assertThat(router.diagnostics()).contains("interpreted 2, compiled 0",
                    "with invocations 0, without invocations 2");
            } else {
                assertThat(router.diagnostics()).contains("interpreted 1, compiled 0",
                    invokes ? "with invocations 1, without invocations 0" : "with invocations 0, without invocations 1");
            }
        } finally {
            connection.disconnect();
        }
    }
}
