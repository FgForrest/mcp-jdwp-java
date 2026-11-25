package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;
import one.edee.mcp.jdwp.BreakpointTracker;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.JDWPTools;
import one.edee.mcp.jdwp.JdiEventListener;
import one.edee.mcp.jdwp.JdiHealthMonitor;
import one.edee.mcp.jdwp.SuspensionSession;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.interpret.EvaluationRouter;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies step landings against HotSpot's JDWP agent on each configured JDK. */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@Timeout(60)
class JDWPToolsStepLiveTest {
    private static final String TARGET = Target.class.getName();
    private static final int WAIT_MS = 5_000;

    /** Exercises platform calls, callbacks, and an explicit stop inside the JDK. */
    @ParameterizedTest
    @MethodSource("one.edee.mcp.jdwp.evaluation.InterpreterTargetVm#javaHomes")
    void stepsThroughPlatformCodeAndIntoApplicationCode(Path javaHome) throws Exception {
        final BreakpointTracker tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final EvaluationGuard guard = new EvaluationGuard();
        final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
        final WatcherManager watchers = new WatcherManager();
        final JdiHealthMonitor health = new JdiHealthMonitor();
        final JvmDiscoveryService discovery = mock(JvmDiscoveryService.class);
        final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter router = new EvaluationRouter(evaluator);
        final JdiEventListener listener = new JdiEventListener(tracker, history, evaluator, guard, null, marks, router);
        final JDIConnectionService connection =
            new JDIConnectionService(listener, tracker, history, watchers, guard, marks, health, discovery);
        final FastLogpointService fast = mock(FastLogpointService.class);
        when(fast.retryPendingRemovals()).thenReturn("");
        final JDWPTools tools = new JDWPTools(connection, tracker, watchers, router, history, guard, discovery, marks,
            health, InterpreterHarnessLiveTest.noLocalClasspath(), fast);

        try (final InterpreterTargetVm target = InterpreterTargetVm.startClass(javaHome, Target.class)) {
            assertThat(connection.connect("127.0.0.1", target.port())).startsWith("Connected");
            try {
                assertThat(tools.jdwp_resume_until_event(WAIT_MS)).contains("via=vm_start");

                stopAt(tools, "concat");
                assertStepAt(tools.jdwp_step_into(null, true), "arguments");
                assertStepAt(tools.jdwp_step_into(null, true), "take body");

                stopAt(tools, "sort");
                assertStepAt(tools.jdwp_step_into(null, true), "compare body");

                // A method reference returns to its application-side adapter before unwinding the JDK.
                // Use a comparator with an erased signature to exercise the return through JDK frames alone.
                stopAt(tools, Target.ObjectComparator.class.getName(), "object compare");
                assertStepAt(tools.jdwp_step_into(null, true), "after sort");

                stopAt(tools, "reflect");
                assertStepAt(tools.jdwp_step_into(null, true), "hello body");

                stopAt(tools, "proxy");
                assertStepAt(tools.jdwp_step_into(null, true), "handler body");

                stopAt(tools, "poll");
                final ThreadReference main = Objects.requireNonNull(tracker.getLastBreakpointThread());
                final long threadId = main.uniqueID();
                assertThat(tools.jdwp_step_into(threadId)).startsWith("Step into started");
                final SuspensionSession session = Objects.requireNonNull(tracker.suspensionSession());
                // Drive the same session wait with a short deadline while poll() is still blocking.
                assertThat(session.nextStop(20, main).outcome()).isEqualTo(SuspensionSession.Outcome.TIMEOUT);
                assertStepAt(tools.jdwp_resume_until_event(WAIT_MS, threadId), "direct");
                assertStepAt(tools.jdwp_step_into(null, true), "hello body");

                final Location sort = connection.getVM().classesByName("java.util.ArrayList").get(0)
                    .methodsByName("sort", "(Ljava/util/Comparator;)V").get(0).location();
                setBreakpoint(tools, sort.declaringType().name(), sort.lineNumber());
                assertThat(tools.jdwp_resume_until_event(WAIT_MS, threadId))
                    .startsWith("Event fired at java.util.ArrayList:" + sort.lineNumber() + ". Thread:");
                final String landing = tools.jdwp_step_into(threadId, true);
                assertThat(landing).contains("Event fired at java.", "via=step");
                assertThat(main.frame(0).location()).isNotEqualTo(sort);
            } finally {
                connection.disconnect();
            }
        }
    }

    /** Sets a breakpoint at a fixture line and presents its stop through the normal tool. */
    private static void stopAt(JDWPTools tools, String marker) throws IOException {
        stopAt(tools, TARGET, marker);
    }

    /** Sets and presents a line breakpoint in the selected fixture class. */
    private static void stopAt(JDWPTools tools, String className, String marker) throws IOException {
        final int line = lineOf(marker);
        setBreakpoint(tools, className, line);
        assertThat(tools.jdwp_resume_until_event(WAIT_MS))
            .startsWith("Event fired at " + className + ':' + line + ". Thread:");
    }

    /** Registers an ordinary line breakpoint even when the fixture class is still unloaded. */
    private static void setBreakpoint(JDWPTools tools, String className, int line) {
        assertThat(tools.jdwp_set_breakpoint(className, line, "thread", null, null, null, null)).contains("ID:");
    }

    /** Verifies that a step's report names the exact fixture line. */
    private static void assertStepAt(String reply, String marker) throws IOException {
        assertThat(reply).as(marker).contains("Event fired at " + TARGET + ':' + lineOf(marker) + ". Thread:", "via=step")
            .doesNotContain("while the step is pending");
    }

    /** Finds the fixture line marked by the comment. */
    private static int lineOf(String marker) throws IOException {
        final List<String> lines = Files.readAllLines(Path.of(
            "src/test/java/one/edee/mcp/jdwp/evaluation/JDWPToolsStepLiveTest.java"));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).endsWith("// MARK: " + marker)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException(marker);
    }

    /** Runs calls that exercise the platform filters and then stays alive for debugger cleanup. */
    public static final class Target {
        private static Object sink;

        /** Provides an interface for a JDK-generated proxy. */
        public interface Greeter {
            /** Dispatches to the invocation handler. */
            void greet();
        }

        /** Compares integers with a signature that requires no generated bridge method. */
        private static final class ObjectComparator implements Comparator<Object> {
            /** Returns directly to the JDK sorting code. */
            @Override
            public int compare(Object left, Object right) {
                return Integer.compare((Integer) left, (Integer) right); // MARK: object compare
            }
        }

        /** Calls through the JDK before and after stops in application code. */
        public static void main(String[] args) throws Exception {
            final String base = "https://example.test";
            final int n = args.length;
            final String url = base + "/orders" + n; // MARK: concat
            take(YearMonth.of(2026, 10)); // MARK: arguments
            final List<Integer> list = new ArrayList<>(List.of(2, 1));
            list.sort(Target::compare); // MARK: sort
            list.sort(new ObjectComparator());
            sink = url; // MARK: after sort
            Target.class.getMethod("hello", String.class).invoke(null, "r"); // MARK: reflect
            final Greeter greeter = (Greeter) Proxy.newProxyInstance(Target.class.getClassLoader(),
                new Class<?>[]{Greeter.class}, (proxy, method, arguments) -> {
                    sink = proxy; // MARK: handler body
                    return null;
                });
            greeter.greet(); // MARK: proxy
            new LinkedBlockingQueue<String>().poll(750, TimeUnit.MILLISECONDS); // MARK: poll
            hello("last"); // MARK: direct
            list.sort(Target::compare);
            System.in.read();
        }

        /** Receives the result of the nested JDK call. */
        private static void take(YearMonth month) {
            sink = month; // MARK: take body
        }

        /** Compares two elements with no intervening application line before the return. */
        private static int compare(Integer left, Integer right) {
            return Integer.compare(left, right); // MARK: compare body
        }

        /** Serves as both a reflective and a direct call destination. */
        public static void hello(String message) {
            sink = message; // MARK: hello body
        }
    }
}
