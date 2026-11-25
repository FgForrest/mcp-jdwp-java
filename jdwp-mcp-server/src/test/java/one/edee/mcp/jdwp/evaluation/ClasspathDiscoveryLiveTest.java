package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.PathSearchingVirtualMachine;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.BreakpointTracker;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.InvocationRunner;
import one.edee.mcp.jdwp.JdiEventListener;
import one.edee.mcp.jdwp.SuspensionSession;
import one.edee.mcp.jdwp.evaluation.CountingJdwpConnection.Command;
import one.edee.mcp.jdwp.evaluation.fixture.ClasspathTarget;
import one.edee.mcp.jdwp.evaluation.fixture.InterpreterTarget;
import one.edee.mcp.jdwp.evaluation.interpret.EvaluationRouter;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Measures the first production breakpoint and compiled evaluation with container-like loaders. */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@Timeout(90)
class ClasspathDiscoveryLiveTest {
    /** JDWP ClassType.InvokeMethod. */
    private static final Command CLASS_INVOKE = new Command(3, 3);
    /** JDWP ObjectReference.InvokeMethod. */
    private static final Command OBJECT_INVOKE = new Command(9, 6);
    /** JDWP VirtualMachine.ClassPaths. */
    private static final Command CLASS_PATHS = new Command(1, 13);

    /** Compiles against target-only classes and measures a large launch classpath's first read. */
    @ParameterizedTest
    @ValueSource(strings = {"manifest", "relative", "400 jars"})
    void compilesAgainstLocalLaunchPaths(String mode, @TempDir Path targetDirectory) throws Exception {
        final Path classes = Files.createDirectory(targetDirectory.resolve("launch classes"));
        final Path main = Files.writeString(targetDirectory.resolve("Main.java"), """
            package launchfixture;
            public class Main {
                public static void main(String[] args) { stop(); }
                public static void stop() {}
            }
            """);
        final Path dependency = Files.writeString(targetDirectory.resolve("Dependency.java"), """
            package launchfixture;
            public class Dependency { public static int answer() { return 42; } }
            """);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
            "-d", classes.toString(), main.toString(), dependency.toString())).isZero();
        final List<String> launch;
        final List<String> expected = new ArrayList<>();
        if (mode.equals("manifest")) {
            final Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "launchfixture.Main");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "launch%20classes/");
            final Path jar = targetDirectory.resolve("launch.jar");
            try (final JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
                output.flush();
            }
            launch = List.of("-jar", jar.toString());
            expected.add(jar.toString());
            expected.add(classes.toString());
        } else {
            final List<String> relative = new ArrayList<>(List.of("launch classes"));
            expected.add(classes.toString());
            if (mode.equals("400 jars")) {
                for (int i = 0; i < 400; i++) {
                    final String name = "dependency-" + i + ".jar";
                    final Path jar = targetDirectory.resolve(name);
                    ClasspathDiscovererTest.writeLaunchJar(jar, "");
                    relative.add(name);
                    expected.add(jar.toString());
                }
            }
            launch = List.of("-cp", String.join(File.pathSeparator, relative), "launchfixture.Main");
        }
        assertThat(Path.of("").toAbsolutePath()).isNotEqualTo(targetDirectory);
        for (final Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (final InterpreterTargetVm target = InterpreterTargetVm.startIn(javaHome, targetDirectory, launch);
                 final CountingJdwpConnection wire = CountingJdwpConnection.attach(target.port())) {
                withTarget(wire, "launchfixture.Main", mode, "stop", EventRequest.SUSPEND_EVENT_THREAD,
                    (vm, tracker, history, router, connection, session, type) -> {
                        final ThreadReference thread = waitForStop(session).thread();
                        final Map<Command, Long> before = wire.snapshot();
                        final long start = System.nanoTime();
                        final String discovered = connection.discoverClasspath(thread);
                        final long elapsed = System.nanoTime() - start;
                        final Map<Command, Long> after = wire.snapshot();
                        assertThat(((PathSearchingVirtualMachine) vm).baseDirectory()).isEqualTo(targetDirectory.toString());
                        assertThat(wire.snapshot()).isEqualTo(after);
                        System.out.printf("JDK %s, %s: first classpath discovery %.3f ms, %d packets%n",
                            vm.version(), mode, elapsed / 1_000_000.0, packets(after) - packets(before));
                        assertThat(after.getOrDefault(CLASS_PATHS, 0L)).isEqualTo(1L);
                        assertThat(invocations(before, after)).isZero();
                        assertThat(router.evaluateEvent("java.util.stream.IntStream.of(1).map(i -> "
                            + "launchfixture.Dependency.answer()).sum()", thread, Map.of(), connection::formatFieldValue))
                            .isEqualTo("42");
                        assertThat(discovered).isEqualTo(String.join(File.pathSeparator, expected));
                        assertThat(wire.snapshot().getOrDefault(CLASS_PATHS, 0L)).isEqualTo(1L);
                    });
            }
        }
    }

    /** Checks the captured legacy classpath, lazy discovery, and the stop-to-result packet budget. */
    @Test
    void measuresFirstStopAndCompilationWithChildLoaders(@TempDir Path root) throws Exception {
        final Path child = Files.createDirectory(root.resolve("child classpath"));
        final Path parent = Files.createDirectory(root.resolve("webapp classes"));
        final Path fixtureClasses = Path.of(InterpreterTarget.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        final List<String> legacyEntries = List.of(fixtureClasses.toString(), child.toUri().getPath(),
            parent.toUri().getPath());
        final SoftAssertions checks = new SoftAssertions();
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (InterpreterTargetVm target = InterpreterTargetVm.start(javaHome, child.toString(), parent.toString());
                 CountingJdwpConnection wire = CountingJdwpConnection.attach(target.port())) {
                final VirtualMachine vm = Bootstrap.virtualMachineManager().createVirtualMachine(wire);
                try {
                    checkFirstStop(vm, wire, legacyEntries, checks);
                } finally {
                    vm.dispose();
                }
            }
        }
        checks.assertAll();
    }

    /** Runs the real dispatcher while recording packets in the stop and evaluation windows. */
    private static void checkFirstStop(VirtualMachine vm, CountingJdwpConnection wire, List<String> legacyEntries,
                                       SoftAssertions checks) throws Exception {
        final String version = vm.version();
        final BreakpointTracker tracker = new BreakpointTracker();
        final EvaluationGuard guard = new EvaluationGuard();
        final JDIConnectionService connection = InterpreterHarnessLiveTest.connectionFor(vm, guard);
        final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(new InMemoryJavaCompiler(),
            connection, guard, InterpreterHarnessLiveTest.noLocalClasspath());
        final EvaluationRouter router = new EvaluationRouter(evaluator, EvaluationRouter.Mode.COMPILER);
        final JdiEventListener listener = new JdiEventListener(tracker, new EventHistory(), evaluator, guard,
            null, new MarkedInstanceRegistry(), router);
        final EventSet prepared = armFixtureBreakpoint(vm, tracker);
        listener.start(vm);
        try {
            final SuspensionSession session = Objects.requireNonNull(tracker.suspensionSession());
            final Map<Command, Long> beforeStop = wire.snapshot();
            prepared.resume();
            await().atMost(Duration.ofSeconds(15)).until(() -> session.unseenCount() == 1);
            final ThreadReference thread = Objects.requireNonNull(session.presentCurrent()).thread();
            final Map<Command, Long> atStop = wire.snapshot();
            final long stopInvocations = invocations(beforeStop, atStop);
            final String jdkAtStop = connection.getDiscoveredJdkPath();

            final String result = router.evaluateEvent("n + 1", thread, Map.of(), connection::formatFieldValue);
            final Map<Command, Long> evaluated = wire.snapshot();
            final long evaluationPackets = packets(evaluated) - packets(atStop);
            final long evaluationInvocations = invocations(atStop, evaluated);
            final String classpath = connection.discoverClasspath(thread);
            final long stopPackets = packets(atStop) - packets(beforeStop);
            System.out.printf("JDK %s: first stop %d packets (%d invocations), first compiled evaluation %d packets "
                    + "(%d invocations), classpath %s%n", version, stopPackets,
                stopInvocations, evaluationPackets, evaluationInvocations, classpath);
            checks.assertThat(stopInvocations).as("first stop invocations on Java %s", version).isZero();
            checks.assertThat(jdkAtStop).as("first stop's JDK discovery on Java %s", version).isNull();
            checks.assertThat(result).isEqualTo("3");
            // The legacy container fixture used 98 packets through the stop and 168 through evaluation.
            checks.assertThat(stopPackets + evaluationPackets).as("stop-to-result packets on Java %s", version)
                .isLessThan(266);
            // The wrapper uses three invocations. The extra one is the container's getURLs().
            checks.assertThat(evaluationInvocations).as("container fallback plus wrapper calls on Java %s", version)
                .isEqualTo(4);
            checks.assertThat(classpath).isEqualTo(String.join(File.pathSeparator, legacyEntries));
        } finally {
            listener.stop();
        }
    }

    /** Leaves the fixture paused at class preparation so the next event is the tracked breakpoint. */
    private static EventSet armFixtureBreakpoint(VirtualMachine vm, BreakpointTracker tracker) throws Exception {
        final EventRequestManager requests = vm.eventRequestManager();
        final ClassPrepareRequest prepare = requests.createClassPrepareRequest();
        prepare.addClassFilter(InterpreterTargetVm.CLASS);
        prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        prepare.enable();
        Objects.requireNonNull(vm.eventQueue().remove(15_000)).resume();
        final EventSet prepared = Objects.requireNonNull(vm.eventQueue().remove(15_000));
        final ClassPrepareEvent event = (ClassPrepareEvent) prepared.iterator().next();
        requests.deleteEventRequest(prepare);
        final BreakpointRequest breakpoint = requests.createBreakpointRequest(
            event.referenceType().methodsByName("inspect").get(0).location());
        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        tracker.registerBreakpoint(breakpoint);
        breakpoint.enable();
        return prepared;
    }

    /** Counts all completed debugger commands, including error replies. */
    private static long packets(Map<Command, Long> snapshot) {
        return snapshot.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Counts both JDWP method invocation commands in the window. */
    private static long invocations(Map<Command, Long> before, Map<Command, Long> after) {
        return after.getOrDefault(CLASS_INVOKE, 0L) - before.getOrDefault(CLASS_INVOKE, 0L)
            + after.getOrDefault(OBJECT_INVOKE, 0L) - before.getOrDefault(OBJECT_INVOKE, 0L);
    }

    /** Resolves isolated classes on the first compile or after the connection has compiled elsewhere. */
    @ParameterizedTest
    @ValueSource(strings = {"standard", "lazy", "later", "override"})
    void seesIsolatedRepositories(String mode, @TempDir Path temp) throws Exception {
        final Path sources = Files.createDirectory(temp.resolve("sources"));
        final Path classes = Files.createDirectory(temp.resolve("isolated classpath"));
        final Path isolated = Files.writeString(sources.resolve("Isolated.java"),
            "package classpath.fixture; public class Isolated { public static void stop() {} }");
        final Path dependency = Files.writeString(sources.resolve("Dependency.java"),
            "package classpath.fixture; public class Dependency { public static int answer() { return 42; } }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
            "-d", classes.toString(), isolated.toString(), dependency.toString())).isZero();

        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withTarget(javaHome, mode, classes.toString(), "firstPlainStop", EventRequest.SUSPEND_EVENT_THREAD,
                (vm, tracker, history, router, connection, session, type) -> {
                    final BreakpointTracker.LastBreakpoint initial = waitForStop(session);
                    if (mode.equals("later")) {
                        assertThat(router.evaluateEvent("1 + 1", initial.thread(), Map.of(),
                            connection::formatFieldValue)).isEqualTo("2");
                    }
                    if (mode.equals("override")) {
                        assertThat(router.evaluateEvent("1 + 1", initial.thread(), Map.of(),
                            connection::formatFieldValue)).isEqualTo("2");
                        assertThat(connection.getClasspathDiscoveryProblem()).contains("getContextClassLoader()", "pending");
                        // This app-loader frame can see the repository only through the overridden getter.
                        // ECJ still resolves the short-circuited type reference.
                        final ExecutorService pool = InvocationRunner.newPool();
                        try {
                            final ThreadReference thread = initial.thread();
                            final InvocationRunner runner = new InvocationRunner(pool, InvocationRunner.TIMEOUT_MS,
                                history, () -> session);
                            final EvaluationRouter.ToolJob job = router.toolJob(thread, () -> {});
                            final EvaluationRouter.Input input = router.parse("true || classpath.fixture.Dependency.answer() == 42");
                            assertThat(runner.run(thread, "evaluating a repository dependency",
                                () -> job.evaluate(input, () -> thread.frame(0), Map.of(), connection::formatFieldValue)))
                                .isEqualTo("true");
                            assertThat(connection.getClasspathDiscoveryProblem()).isNull();
                        } finally {
                            pool.shutdownNow();
                        }
                    }
                    final ReferenceType isolatedType = vm.classesByName("classpath.fixture.Isolated").get(0);
                    final int isolatedStop = arm(vm, tracker, isolatedType, "stop", EventRequest.SUSPEND_EVENT_THREAD);
                    tracker.setCondition(isolatedStop, "classpath.fixture.Dependency.answer() == 42");
                    final String jdkBefore = connection.getDiscoveredJdkPath();
                    session.releaseAll();
                    final BreakpointTracker.LastBreakpoint next = waitForStop(session);
                    assertThat(next.thread().frame(0).location().declaringType().name()).isEqualTo("classpath.fixture.Isolated");
                    final List<String> errors = history.getRecent(20).stream()
                        .filter(event -> event.type().equals("CONDITION_ERROR")).map(EventHistory.DebugEvent::summary)
                        .toList();
                    System.out.printf("%s JDK %s: JDK after first plain stop = %s, condition errors = %s%n",
                        mode, vm.version(), jdkBefore, errors);
                    assertThat(errors).isEmpty();
                    assertThat(router.evaluateEvent("classpath.fixture.Dependency.answer()", next.thread(),
                        Map.of(), connection::formatFieldValue)).isEqualTo("42");
                });
        }
    }

    /** Keeps a stop-all condition from entering a private helper that waits on a suspended worker. */
    @Test
    void stopAllCompiledConditionDoesNotRunPrivateLoaderHelper() throws Exception {
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withTarget(javaHome, "private-accessor", "", "conditionStop", EventRequest.SUSPEND_ALL,
                (vm, tracker, history, router, connection, session, type) -> {
                    await().atMost(Duration.ofSeconds(15)).until(() -> session.unseenCount() == 1 || entered(type));
                    final ThreadReference holder = vm.allThreads().stream()
                        .filter(thread -> thread.name().equals("repository-worker")).findFirst().orElseThrow();
                    System.out.printf("JDK %s: private helper entered=%s, repository worker suspended=%s, "
                            + "unseen stops=%d, events=%s%n", vm.version(), entered(type), holder.isSuspended(),
                        session.unseenCount(), history.getRecent(12).stream().map(EventHistory.DebugEvent::type).toList());
                    assertThat(session.unseenCount())
                        .as("published stop (private loader helper entered=%s)", entered(type)).isEqualTo(1);
                    assertThat(entered(type)).isFalse();
                    assertThat(holder.isSuspended()).isTrue();
                    assertThat(history.getRecent(12).stream().map(EventHistory.DebugEvent::type))
                        .doesNotContain("CONDITION_ERROR");
                });
        }
    }

    /** A trivial condition should publish a stop even if a context getter can wait on a bystander. */
    @Test
    void stopAllConditionPublishesWhenContextGetterDependsOnBystander() throws Exception {
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withTarget(javaHome, "blocking-context-getter", "", "conditionStop", EventRequest.SUSPEND_ALL,
                (vm, tracker, history, router, connection, session, type) -> {
                    final com.sun.jdi.Field entered = Objects.requireNonNull(type.fieldByName("contextGetterEntered"));
                    await().atMost(Duration.ofSeconds(15)).until(() ->
                        session.unseenCount() == 1 || ((BooleanValue) type.getValue(entered)).value());
                    final ThreadReference worker = vm.allThreads().stream()
                        .filter(thread -> thread.name().equals("repository-worker")).findFirst().orElseThrow();
                    System.out.printf("JDK %s: context getter entered=%s, repository worker suspended=%s, "
                            + "unseen stops=%d, events=%s%n", vm.version(), ((BooleanValue) type.getValue(entered)).value(),
                        worker.isSuspended(), session.unseenCount(),
                        history.getRecent(12).stream().map(EventHistory.DebugEvent::type).toList());
                    assertThat(session.unseenCount()).as("published stop-all condition").isEqualTo(1);
                    assertThat(((BooleanValue) type.getValue(entered)).value()).isFalse();
                    assertThat(worker.isSuspended()).isTrue();
                    assertThat(connection.getClasspathDiscoveryProblem()).contains("getContextClassLoader()", "pending");
                });
        }
    }

    /** Reports whether the private helper ran. */
    private static boolean entered(ReferenceType type) {
        return ((BooleanValue) type.getValue(Objects.requireNonNull(type.fieldByName("privateAccessorEntered")))).value();
    }

    /** Waits for the dispatcher to publish and presents the stop to the caller. */
    private static BreakpointTracker.LastBreakpoint waitForStop(SuspensionSession session) {
        await().atMost(Duration.ofSeconds(15)).until(() -> session.unseenCount() == 1);
        return Objects.requireNonNull(session.presentCurrent());
    }

    /** Checks a fixture driven by the production listener. */
    private interface Check {
        /** Runs the assertions after class preparation is released. */
        void run(VirtualMachine vm, BreakpointTracker tracker, EventHistory history, EvaluationRouter router,
                 JDIConnectionService connection, SuspensionSession session, ReferenceType type)
            throws Exception;
    }

    /** Attaches the counting transport and starts the dispatcher at a prepared breakpoint. */
    private static void withTarget(Path javaHome, String mode, String classpath, String stopMethod, int policy,
                                   Check check) throws Exception {
        try (final InterpreterTargetVm target = InterpreterTargetVm.startClass(javaHome, ClasspathTarget.class,
                 mode, classpath);
             final CountingJdwpConnection wire = CountingJdwpConnection.attach(target.port())) {
            withTarget(wire, ClasspathTarget.class.getName(), mode, stopMethod, policy, check);
        }
    }

    /** Runs the dispatcher against a target with a caller-supplied Java launch. */
    private static void withTarget(CountingJdwpConnection wire, String mainClass, String mode, String stopMethod,
                                   int policy, Check check) throws Exception {
        final VirtualMachine vm = Bootstrap.virtualMachineManager().createVirtualMachine(wire);
        final BreakpointTracker tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final EvaluationGuard guard = new EvaluationGuard();
        final JDIConnectionService connection = InterpreterHarnessLiveTest.connectionFor(vm, guard);
        final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(new InMemoryJavaCompiler(), connection,
            guard, InterpreterHarnessLiveTest.noLocalClasspath());
        final EvaluationRouter router = new EvaluationRouter(evaluator, EvaluationRouter.Mode.COMPILER);
        final JdiEventListener listener = new JdiEventListener(tracker, history, evaluator, guard,
            null, new MarkedInstanceRegistry(), router);
        try {
            // Pause at class preparation so the breakpoint is armed before fixture code runs.
            final ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(mainClass);
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            prepare.enable();
            Objects.requireNonNull(vm.eventQueue().remove(15_000)).resume();
            final EventSet prepared = Objects.requireNonNull(vm.eventQueue().remove(15_000));
            final ReferenceType type = ((ClassPrepareEvent) prepared.iterator().next()).referenceType();
            vm.eventRequestManager().deleteEventRequest(prepare);
            final int first = arm(vm, tracker, type, stopMethod, policy);
            if (mode.equals("private-accessor") || mode.equals("blocking-context-getter")) {
                tracker.setCondition(first, "java.lang.Integer.parseInt(\"1\") == 1");
            }
            listener.start(vm);
            final SuspensionSession session = Objects.requireNonNull(tracker.suspensionSession());
            prepared.resume();
            check.run(vm, tracker, history, router, connection, session, type);
        } finally {
            try {
                vm.dispose();
            } finally {
                listener.stop();
            }
        }
    }

    /** Registers a tracked breakpoint at the first instruction of a fixture method. */
    private static int arm(VirtualMachine vm, BreakpointTracker tracker, ReferenceType type, String method,
                           int policy) {
        final BreakpointRequest breakpoint = vm.eventRequestManager().createBreakpointRequest(
            type.methodsByName(method).get(0).location());
        breakpoint.setSuspendPolicy(policy);
        final int id = tracker.registerBreakpoint(breakpoint);
        breakpoint.enable();
        return id;
    }
}
