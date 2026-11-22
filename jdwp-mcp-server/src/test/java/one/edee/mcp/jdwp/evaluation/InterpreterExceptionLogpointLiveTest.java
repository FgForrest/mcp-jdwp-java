package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.*;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@Timeout(90)
class InterpreterExceptionLogpointLiveTest {

    @Test
    void bindsTheThrownObjectInTheProductionExceptionLogpoint() throws Exception {
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (var target = InterpreterTargetVm.start(javaHome)) {
                checkExceptionLogpoint(target.port());
            }
        }
    }

    private static void checkExceptionLogpoint(int port) throws Exception {
        final var tracker = new BreakpointTracker();
        final var history = new EventHistory();
        final var guard = new EvaluationGuard();
        final var marks = new MarkedInstanceRegistry();
        final var watchers = new WatcherManager();
        final var health = new JdiHealthMonitor();
        final var noLocalClasspath = InterpreterHarnessLiveTest.noLocalClasspath();
        // The server uses a lazy dependency for this evaluator/listener cycle, as does LiveSession.
        final var delegate = new AtomicReference<JdiExpressionEvaluator>();
        final var lazyEvaluator = mock(JdiExpressionEvaluator.class, invocation -> {
            try {
                return invocation.getMethod().invoke(delegate.get(), invocation.getArguments());
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        final var listener = new JdiEventListener(tracker, history, lazyEvaluator, guard, null, marks);
        final var connection = new JDIConnectionService(listener, tracker, history, watchers, guard, marks, health);
        final var evaluator = new JdiExpressionEvaluator(new InMemoryJavaCompiler(), connection, guard, noLocalClasspath);
        delegate.set(evaluator);
        final var fast = new FastLogpointService(tracker, history, guard, evaluator, listener, connection);
        final var tools = new JDWPTools(connection, tracker, watchers, evaluator, history, guard,
            mock(JvmDiscoveryService.class), marks, health, noLocalClasspath, fast);
        try {
            assertThat(connection.connect("127.0.0.1", port)).startsWith("Connected");
            assertThat(tools.jdwp_resume_until_event(10_000)).contains("via=vm_start");
            assertThat(tools.jdwp_set_exception_logpoint("java.lang.IllegalArgumentException", "$exception.getMessage()",
                null, true, false, null, null, false)).contains("ID:").doesNotContain("Error");
            assertThat(tools.jdwp_resume()).startsWith("Resumed:");
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(history.getRecent(20)).anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo("EXCEPTION_LOG");
                    assertThat(event.details()).containsEntry("result", "fixture exception");
                }));
        } finally {
            connection.disconnect();
        }
    }
}
