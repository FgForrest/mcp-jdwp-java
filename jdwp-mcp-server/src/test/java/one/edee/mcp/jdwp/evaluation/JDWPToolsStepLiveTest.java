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
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies step landings against HotSpot's JDWP agent on each configured JDK. */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@Timeout(60)
class JDWPToolsStepLiveTest {
    private static final String TARGET = Target.class.getName();
    private static final int WAIT_MS = 5_000;

    private BreakpointTracker tracker;
    private JDIConnectionService connection;
    private JDWPTools tools;

    /** Wires the tools to the live connection and event listener. */
    @BeforeEach
    void setUp() {
        tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final EvaluationGuard guard = new EvaluationGuard();
        final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
        final WatcherManager watchers = new WatcherManager();
        final JdiHealthMonitor health = new JdiHealthMonitor();
        final JvmDiscoveryService discovery = mock(JvmDiscoveryService.class);
        final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter router = new EvaluationRouter(evaluator);
        final JdiEventListener listener = new JdiEventListener(tracker, history, evaluator, guard, null, marks, router);
        connection =
            new JDIConnectionService(listener, tracker, history, watchers, guard, marks, health, discovery);
        final FastLogpointService fast = mock(FastLogpointService.class);
        when(fast.retryPendingRemovals()).thenReturn("");
        tools = new JDWPTools(connection, tracker, watchers, router, history, guard, discovery, marks,
            health, InterpreterHarnessLiveTest.noLocalClasspath(), fast);
    }

    /** Exercises platform calls, callbacks, and an explicit stop inside the JDK. */
    @ParameterizedTest
    @MethodSource("one.edee.mcp.jdwp.evaluation.InterpreterTargetVm#javaHomes")
    void stepsThroughPlatformCodeAndIntoApplicationCode(Path javaHome) throws Exception {
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

    /** Returns through a pool, a lambda adapter, and a thread entry point. */
    @ParameterizedTest
    @MethodSource("one.edee.mcp.jdwp.evaluation.InterpreterTargetVm#javaHomes")
    void stepsFromClosingBraces(Path javaHome) throws Exception {
        try (final InterpreterTargetVm target = InterpreterTargetVm.startClass(javaHome, Target.class, "closures")) {
            start(target);
            try {
                stopAt(tools, TARGET + "$1", "pool runnable return");
                assertThat(stepInto()).contains("Event fired at java.util.concurrent.", "via=step");

                stopAt(tools, "pool lambda body");
                final ThreadReference lambda = Objects.requireNonNull(tracker.getLastBreakpointThread());
                assertThat(tools.jdwp_step_over(null)).startsWith("Step over started");
                assertStepAt(tools.jdwp_resume_until_event(1_000, lambda.uniqueID()), "pool lambda return");
                assertThat(stepInto()).contains(TARGET + "$$Lambda", "via=step");
                assertThat(stepInto()).contains("Event fired at java.util.concurrent.", "via=step");

                stopAt(tools, Target.Worker.class.getName(), "thread run return");
                assertThat(stepInto()).contains("Event fired at java.lang.Thread:", "via=step");

                stopAt(tools, TARGET + "$2", "forEach return");
                assertStepAt(stepInto(), "after forEach");
            } finally {
                connection.disconnect();
            }
        }
    }

    /** A call on the return line can run into the pool's park with a stop-all still held. */
    @ParameterizedTest
    @MethodSource("one.edee.mcp.jdwp.evaluation.InterpreterTargetVm#javaHomes")
    void callableTimeoutExplainsHowToReleaseTheStopAll(Path javaHome) throws Exception {
        try (final InterpreterTargetVm target = InterpreterTargetVm.startClass(javaHome, Target.class, "timeout")) {
            start(target);
            try {
                final int line = lineOf("callable return");
                assertThat(tools.jdwp_set_breakpoint(Target.SizeTask.class.getName(), line, "all",
                    null, null, null, null)).contains("ID:");
                assertThat(tools.jdwp_resume_until_event(WAIT_MS)).contains(":" + line + ". Thread:");
                setBreakpoint(tools, TARGET, lineOf("after callable"));

                final String step = tools.jdwp_step_into(null, true);
                assertThat(step).contains("[TIMEOUT]", "returned from the last application method",
                    "jdwp_resume releases them, and the step stays armed");
                final ThreadReference worker = Objects.requireNonNull(tracker.getLastBreakpointThread());
                assertThat(tools.jdwp_resume_until_event(20)).contains("[TIMEOUT]", "released nothing",
                    worker.name() + " (ID=" + worker.uniqueID() + ")", "jdwp_resume releases the stops already seen",
                    "step stays armed");
                assertThat(tools.jdwp_resume()).startsWith("Resumed:");
                assertThat(tools.jdwp_resume_until_event(WAIT_MS)).startsWith("Event fired at " + TARGET + ':'
                    + lineOf("after callable") + ". Thread:");
            } finally {
                connection.disconnect();
            }
        }
    }

    /** An ended step reports its outcome and no longer prevents global releases. */
    @ParameterizedTest
    @MethodSource("one.edee.mcp.jdwp.evaluation.InterpreterTargetVm#javaHomes")
    void retiredStepLetsTheNextGlobalWaitResume(Path javaHome) throws Exception {
        try (final InterpreterTargetVm target = InterpreterTargetVm.startClass(javaHome, Target.class, "ends")) {
            start(target);
            try {
                stopAt(tools, Target.ShortLivedWorker.class.getName(), "worker last line");
                final ThreadReference worker = Objects.requireNonNull(tracker.getLastBreakpointThread());
                setBreakpoint(tools, TARGET, lineOf("after worker"));
                setBreakpoint(tools, TARGET, lineOf("next stop"));
                assertThat(tools.jdwp_step_into(null)).startsWith("Step into started");
                assertThat(tools.jdwp_resume_until_event(1_000, worker.uniqueID()))
                    .contains("[NO_EVENT] The step ended without publishing a stop.");
                assertThat(connection.getVM().eventRequestManager().stepRequests()).isEmpty();
                assertThat(tools.jdwp_resume_until_event(WAIT_MS)).startsWith("Event fired at " + TARGET + ':'
                    + lineOf("after worker") + ". Thread:");
                assertThat(tools.jdwp_resume_until_event(WAIT_MS)).startsWith("Event fired at " + TARGET + ':'
                    + lineOf("next stop") + ". Thread:");
            } finally {
                connection.disconnect();
            }
        }
    }

    /** Attaches and presents the VM-start stop. */
    private void start(InterpreterTargetVm target) throws Exception {
        assertThat(connection.connect("127.0.0.1", target.port())).startsWith("Connected");
        assertThat(tools.jdwp_resume_until_event(WAIT_MS)).contains("via=vm_start");
    }

    /** Starts a step into and waits with a short deadline. */
    private String stepInto() {
        final ThreadReference thread = Objects.requireNonNull(tracker.getLastBreakpointThread());
        assertThat(tools.jdwp_step_into(null)).startsWith("Step into started");
        return tools.jdwp_resume_until_event(1_000, thread.uniqueID());
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

        /** Enters application code directly from the thread's JDK entry point. */
        private static final class Worker implements Runnable {
            @Override
            public void run() {
                sink = "worker";
            } // MARK: thread run return
        }

        /** Ends on the same source line as its last JDK call. */
        private static final class ShortLivedWorker extends Thread {
            @Override
            public void run() {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50)); } // MARK: worker last line
        }

        /** Uses an erased return signature so there is no application bridge frame. */
        private static final class SizeTask implements Callable<Object> {
            private final List<Integer> list = List.of(1);

            @Override
            public Object call() {
                return list.size(); // MARK: callable return
            }
        }

        /** Runs callbacks both with and without an application caller beneath the JDK. */
        private static void closures() throws Exception {
            final ExecutorService pool = Executors.newFixedThreadPool(1);
            try {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        sink = "runnable";
                    } // MARK: pool runnable return
                }).get();
                pool.submit(() -> {
                    sink = "lambda"; // MARK: pool lambda body
                }).get(); // MARK: pool lambda return
            } finally {
                pool.shutdownNow();
            }
            final Thread worker = new Thread(new Worker());
            worker.start();
            worker.join();
            List.<Object>of(1).forEach(new Consumer<Object>() {
                @Override
                public void accept(Object value) {
                    sink = value;
                } // MARK: forEach return
            });
            sink = "done"; // MARK: after forEach
        }

        /** Lets the executor park after its task returns, while the main thread is stopped. */
        private static void timeout() throws Exception {
            final ExecutorService pool = Executors.newFixedThreadPool(1);
            try {
                sink = pool.submit(new SizeTask()).get();
                sink = "task finished"; // MARK: after callable
            } finally {
                pool.shutdownNow();
            }
        }

        /** Keeps the VM alive after the stepped thread ends. */
        private static void ends() throws Exception {
            final Thread worker = new ShortLivedWorker();
            worker.start();
            worker.join();
            sink = "worker finished"; // MARK: after worker
            sink = "next"; // MARK: next stop
        }

        /** Calls through the JDK before and after stops in application code. */
        public static void main(String[] args) throws Exception {
            if (args.length > 0) {
                switch (args[0]) {
                    case "closures" -> closures();
                    case "timeout" -> timeout();
                    case "ends" -> ends();
                    default -> throw new IllegalArgumentException(args[0]);
                }
                System.in.read();
                return;
            }
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
