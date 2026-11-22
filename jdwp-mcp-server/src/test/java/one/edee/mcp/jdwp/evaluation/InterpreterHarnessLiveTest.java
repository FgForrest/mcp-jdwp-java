package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.*;
import one.edee.mcp.jdwp.evaluation.InterpreterCorpus.Entry;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@Timeout(180)
class InterpreterHarnessLiveTest {
    static final Path REPORTS = Path.of("..", "target");
    private static final String TARGET = InterpreterTargetVm.CLASS;
    private static final String PACKAGE_STOP = TARGET.substring(0, TARGET.lastIndexOf('.') + 1) + "PackageStop";
    private static final Map<String, String> INSPECT_STOPS = Map.of(
        TARGET, "main", PACKAGE_STOP, "package", TARGET + "$Inner", "inner");
    private static final Set<String> STOPS = Set.of("main", "package", "inner", "exception", "field");
    private static final String BASELINE_ID = "local-arithmetic";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FIXTURE_GOLDENS = Set.of("private-field", "private-map", "private-map-call",
        "private-session", "private-method", "super-call", "generic-receiver", "outer-this", "exception-detail");
    private static final Map<String, String> PRIMITIVES = Map.of(
        "java.lang.Boolean", "boolean", "java.lang.Byte", "byte", "java.lang.Character", "char",
        "java.lang.Short", "short", "java.lang.Integer", "int", "java.lang.Long", "long",
        "java.lang.Float", "float", "java.lang.Double", "double");

    /** A candidate engine can read a fresh frame and use the same bindings the oracle saw. */
    record EvaluationContext(Entry entry, ThreadReference thread, Map<String, Value> bindings,
                             JDIConnectionService connection) {
        StackFrame frame() throws IncompatibleThreadStateException {
            return thread.frame(0);
        }

        Outcome render(@Nullable Value value) {
            final String type = value == null ? "null" : value.type().name();
            String rendered = connection.formatFieldValue(value);
            if (value instanceof ObjectReference && !(value instanceof StringReference)) {
                rendered = rendered.replaceFirst("^(Object|Array)#\\d+ ", "$1#<id> ");
            }
            if ("char-array-string".equals(entry.id())) {
                rendered = rendered.replaceFirst("^\"\\[C@[0-9a-f]+\"$", "\"[C@<hash>\"");
            }
            return new Outcome("value", PRIMITIVES.getOrDefault(type, type), rendered);
        }
    }

    @FunctionalInterface
    interface Engine {
        Outcome evaluate(EvaluationContext context) throws Exception;
    }

    record Outcome(String kind, String javaClass, String rendered) {
        static Outcome targetException(String javaClass) {
            return new Outcome("exception", javaClass, javaClass);
        }

        String expectedClass() {
            return "compile error".equals(kind) ? rendered : javaClass;
        }
    }

    record Calls(int count, String order) {
    }

    record Observation(Outcome outcome, Calls calls) {
    }

    record Row(String id, @Nullable String stop, @Nullable Observation compiled,
               @Nullable Observation golden, @Nullable String skipped) {
    }

    @FunctionalInterface
    interface BeforeMain {
        void measure(EvaluationContext context) throws Exception;
    }

    @Test
    void recordsCompiledOracleAndBaseline() throws Exception {
        Files.createDirectories(REPORTS);
        final var rows = new StringBuilder();
        final var vmStart = new StringBuilder();
        final List<Entry> synthetic;
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("interpreter-corpus.jsonl")),
                StandardCharsets.UTF_8)) {
            synthetic = InterpreterCorpus.read(reader, "interpreter-corpus.jsonl");
        }
        assertThat(synthetic).allSatisfy(entry -> assertThat(entry.stop()).isIn(STOPS));

        final var entries = new ArrayList<>(synthetic);
        final String external = System.getProperty("jdwp.live.corpus");
        if (external != null) {
            try (Reader reader = Files.newBufferedReader(Path.of(external))) {
                entries.addAll(InterpreterCorpus.read(reader, external));
            }
            assertThat(entries).extracting(Entry::id).doesNotHaveDuplicates();
        }

        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (var target = InterpreterTargetVm.start(javaHome);
                 var wire = CountingJdwpConnection.attach(target.port())) {
                final VirtualMachine vm = Bootstrap.virtualMachineManager().createVirtualMachine(wire);
                try {
                    run(vm, wire, entries, rows, vmStart);
                } finally {
                    try {
                        vm.dispose();
                    } catch (VMDisconnectedException ignored) {
                        // The fixture may have exited before the replay completed.
                    }
                }
            }
        }
        InterpreterBaseline.write(REPORTS.resolve("interp-baseline.md"), rows.toString(), vmStart.toString());
    }

    private static void run(VirtualMachine vm, CountingJdwpConnection wire, List<Entry> entries,
                            StringBuilder rows, StringBuilder vmStart) throws Exception {
        final var guard = new EvaluationGuard();
        final var connection = new JDIConnectionService(mock(JdiEventListener.class),
            new BreakpointTracker(), new EventHistory(), new WatcherManager(), guard,
            new MarkedInstanceRegistry(), new JdiHealthMonitor()) {
            @Override
            public VirtualMachine getVM() {
                return vm;
            }
        };
        final var compiler = new InterpreterBaseline.Compiler();
        final var evaluator = new JdiExpressionEvaluator(compiler, connection, guard, noLocalClasspath());
        final Engine compiled = context -> {
            evaluator.configureCompilerClasspath(context.thread());
            return compiledResult(evaluator, context);
        };

        final String version = vm.version();
        final int major = Integer.parseInt(version.split("\\.")[0]);
        final var golden = new LinkedHashMap<String, Observation>();
        final var observations = replay(vm, entries, connection, compiled,
            context -> measure(evaluator, compiler, context, wire, version, rows), vmStart, golden);

        final var jsonl = new StringBuilder();
        for (Entry entry : entries) {
            final Observation observation = observations.get(entry.id());
            final @Nullable String skip = observation != null ? null
                : entry.stop() == null ? "no fixture stop specified" : "unknown fixture stop";
            jsonl.append(JSON.writeValueAsString(
                new Row(entry.id(), entry.stop(), observation, golden.get(entry.id()), skip))).append('\n');
        }
        Files.writeString(REPORTS.resolve("interp-oracle-jdk" + major + ".jsonl"), jsonl);
    }

    /** Replay an engine on a freshly attached fixture. Pass no baseline or goldens for a candidate. */
    static Map<String, Observation> replay(VirtualMachine vm, List<Entry> entries, JDIConnectionService connection,
                                          Engine engine, @Nullable BeforeMain baseline, StringBuilder vmStart,
                                          @Nullable Map<String, Observation> goldens) throws Exception {
        final var marks = new MarkedInstanceRegistry();
        final var observations = new LinkedHashMap<String, Observation>();
        final var requests = vm.eventRequestManager();
        for (String className : INSPECT_STOPS.keySet()) {
            final var request = requests.createClassPrepareRequest();
            request.addClassFilter(className);
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            request.enable();
        }

        final Set<String> seen = new HashSet<>();
        while (!seen.equals(STOPS)) {
            final EventSet events = vm.eventQueue().remove(15_000);
            assertThat(events).as("next fixture event after stops %s", seen).isNotNull();
            try {
                for (Event event : events) {
                    if (event instanceof VMStartEvent start) {
                        checkVmStart(vm, start, vmStart);
                    } else if (event instanceof ClassPrepareEvent prepare) {
                        requests.deleteEventRequest(prepare.request());
                        final var location = prepare.referenceType().methodsByName("inspect").get(0).location();
                        final var request = requests.createBreakpointRequest(location);
                        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                        request.enable();
                    } else if (event instanceof BreakpointEvent breakpoint) {
                        requests.deleteEventRequest(breakpoint.request());
                        final String stop = Objects.requireNonNull(
                            INSPECT_STOPS.get(breakpoint.location().declaringType().name()));
                        final ThreadReference thread = breakpoint.thread();
                        final ClassType fixture = fixture(vm);
                        if ("main".equals(stop)) {
                            if (baseline != null) {
                                final Entry probe = entries.stream().filter(e -> BASELINE_ID.equals(e.id()))
                                    .findFirst().orElseThrow();
                                baseline.measure(new EvaluationContext(probe, thread, Map.of(), connection));
                                checkPrivateAccess(thread);
                            }
                            marks.mark("target", Objects.requireNonNull(thread.frame(0).thisObject()), true);
                        }
                        evaluateStop(entries, stop, thread, marks.buildBindings(), fixture, connection,
                            engine, observations, goldens);
                        if ("inner".equals(stop)) {
                            final var exception = requests.createExceptionRequest(null, true, false);
                            exception.addClassFilter(TARGET);
                            exception.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                            exception.enable();
                        }
                        assertThat(seen.add(stop)).as(stop).isTrue();
                    } else if (event instanceof ExceptionEvent exception) {
                        requests.deleteEventRequest(exception.request());
                        assertThat(exception.location().method().name()).isEqualTo("throwAtStop");
                        final Map<String, Value> bindings = new LinkedHashMap<>(marks.buildBindings());
                        bindings.put("$exception", exception.exception());
                        final ClassType fixture = fixture(vm);
                        evaluateStop(entries, "exception", exception.thread(), bindings, fixture, connection,
                            engine, observations, goldens);
                        assertThat(seen.add("exception")).isTrue();
                        final var request = requests.createModificationWatchpointRequest(fixture.fieldByName("hidden"));
                        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                        request.enable();
                    } else if (event instanceof ModificationWatchpointEvent modification) {
                        requests.deleteEventRequest(modification.request());
                        assertThat(modification.location().method().name()).isEqualTo("changeAtStop");
                        try (var pins = new PinnedMirrors()) {
                            final Map<String, Value> bindings = new LinkedHashMap<>(marks.buildBindings());
                            bindings.put("$oldValue", modification.valueCurrent());
                            bindings.put("$newValue", modification.valueToBe());
                            bindings.put("$object", modification.object());
                            bindings.put("$fieldName", pins.string(vm, modification.field().name()));
                            bindings.put("$mode", pins.string(vm, "modification"));
                            evaluateStop(entries, "field", modification.thread(), bindings, fixture(vm), connection,
                                engine, observations, goldens);
                        }
                        assertThat(seen.add("field")).isTrue();
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        throw new AssertionError("Fixture exited after stops " + seen);
                    }
                }
            } finally {
                events.resume();
            }
        }
        marks.unmark("target");
        return observations;
    }

    private static void checkVmStart(VirtualMachine vm, VMStartEvent start, StringBuilder report)
        throws Exception {
        assertThat(start.thread().isSuspended()).isTrue();
        final List<String> frames = start.thread().frames().stream()
            .map(f -> f.location().declaringType().name() + "." + f.location().method().name()).toList();
        assertThat(frames).noneMatch(frame -> frame.startsWith(TARGET));
        assertThat(vm.classesByName(TARGET)).isEmpty();
        assertThat(((PathSearchingVirtualMachine) vm).classPath()).anyMatch(path -> path.endsWith("target/test-classes"));
        report.append("- JDK ").append(vm.version()).append(" VMStart thread `").append(start.thread().name())
            .append("` frames ").append(frames).append(". JDWP ClassPaths already exposes the fixture classpath.\n");
    }

    private static void checkPrivateAccess(ThreadReference thread) throws Exception {
        final ObjectReference receiver = Objects.requireNonNull(thread.frame(0).thisObject());
        final Field secret = receiver.referenceType().fieldByName("secret");
        final Method privateMethod = receiver.referenceType().methodsByName("privateMethod").get(0);
        assertThat(secret.isPrivate()).isTrue();
        assertThat(((IntegerValue) receiver.getValue(secret)).value()).isEqualTo(21);
        assertThat(privateMethod.isPrivate()).isTrue();
        assertThat(((IntegerValue) receiver.invokeMethod(thread, privateMethod, List.of(),
            ObjectReference.INVOKE_SINGLE_THREADED)).value()).isEqualTo(22);
    }

    private static void measure(JdiExpressionEvaluator evaluator, InterpreterBaseline.Compiler compiler,
                                EvaluationContext context, CountingJdwpConnection wire, String version,
                                StringBuilder rows) throws Exception {
        assertThat(context.connection().getDiscoveredJdkPath()).isNull();
        final VirtualMachine vm = context.thread().virtualMachine();
        final var beforeMirrors = wire.snapshot();
        for (int i = 0; i < 256; i++) {
            vm.mirrorOf((byte) i);
        }
        assertThat(wire.snapshot()).as("primitive byte mirrors are host-side objects").isEqualTo(beforeMirrors);

        for (boolean cold : List.of(true, false)) {
            final var before = wire.snapshot();
            final int compilations = compiler.compilations;
            final int bytes = compiler.bytes;
            final long compileNanos = compiler.nanos;
            final long start = System.nanoTime();
            evaluator.configureCompilerClasspath(context.thread());
            final long classpathNanos = System.nanoTime() - start;
            final var configured = wire.snapshot();
            final Outcome result = compiledResult(evaluator, context);
            final long totalNanos = System.nanoTime() - start;
            final var sample = new InterpreterBaseline.Sample(before, configured, wire.snapshot(),
                classpathNanos, compiler.nanos - compileNanos, totalNanos, compiler.bytes - bytes);
            assertThat(result).isEqualTo(new Outcome("value", "int", "3"));
            assertThat(compiler.compilations - compilations).isEqualTo(cold ? 1 : 0);
            sample.verify(cold);
            rows.append(sample.row(version, cold ? "first use" : "hit"));
        }
    }

    private static Outcome compiledResult(JdiExpressionEvaluator evaluator, EvaluationContext context)
        throws Exception {
        try {
            return context.render(evaluator.evaluate(context.frame(), context.entry().expression(), context.bindings()));
        } catch (JdiEvaluationException e) {
            if (e.getCause() instanceof InvocationException invoked
                    && e.getMessage().startsWith("Target VM threw exception:")) {
                return Outcome.targetException(invoked.exception().referenceType().name());
            }
            if (e.getMessage().startsWith("Compilation failed:")) {
                final String name = e.getClass().getSimpleName();
                return new Outcome("compile error", name, "compile error: " + name);
            }
            throw e;
        }
    }

    private static void evaluateStop(List<Entry> entries, String stop, ThreadReference thread,
                                     Map<String, Value> bindings, ClassType fixture, JDIConnectionService connection,
                                     Engine engine, Map<String, Observation> observations,
                                     @Nullable Map<String, Observation> goldens) throws Exception {
        for (Entry entry : entries) {
            if (!stop.equals(entry.stop())) {
                continue;
            }
            final var context = new EvaluationContext(entry, thread, bindings, connection);
            final Observation result = observe(context, fixture, engine);
            observations.put(entry.id(), result);
            if (goldens != null) {
                final String expectedClass = entry.expectedClass();
                if (expectedClass != null) {
                    assertThat(result.outcome().expectedClass()).as(entry.id()).isEqualTo(expectedClass);
                }
                verifyFixtureSemantics(entry.id(), fixture, result);
                if (FIXTURE_GOLDENS.contains(entry.id())) {
                    final Observation golden = observe(context, fixture, InterpreterHarnessLiveTest::golden);
                    assertThat(golden.outcome().kind()).as(entry.id()).isEqualTo("value");
                    goldens.put(entry.id(), golden);
                }
            }
        }
    }

    static Observation observe(EvaluationContext context, ClassType fixture, Engine engine) throws Exception {
        final var vm = fixture.virtualMachine();
        final Field calls = fixture.fieldByName("calls");
        final Field trace = fixture.fieldByName("trace");
        fixture.setValue(calls, vm.mirrorOf(0));
        fixture.setValue(trace, vm.mirrorOf(""));
        final Outcome outcome = engine.evaluate(context);
        return new Observation(outcome, new Calls(((IntegerValue) fixture.getValue(calls)).value(),
            ((StringReference) fixture.getValue(trace)).value()));
    }

    private static void verifyFixtureSemantics(String id, ClassType fixture, Observation observation) {
        switch (id) {
            case "short-circuit-and", "short-circuit-or" ->
                assertThat(observation.calls()).as(id).isEqualTo(new Calls(0, ""));
            case "lazy-ternary" -> assertThat(observation.calls()).isEqualTo(new Calls(1, "T"));
            case "receiver-and-arguments" -> assertThat(observation.calls()).isEqualTo(new Calls(4, "RABM"));
            case "block-assign-frame-local" -> assertThat(observation.outcome().rendered()).isEqualTo("7");
            case "block-keeps-frame-local" -> assertThat(observation.outcome().rendered()).isEqualTo("2");
            case "cold-static-counter" -> assertThat(observation.outcome().rendered()).isEqualTo("1");
            case "field-values" -> assertThat(observation.outcome().rendered()).isEqualTo("16");
            case "field-metadata" -> assertThat(observation.outcome().rendered()).isEqualTo("\"hidden:modification\"");
            case "cold-constant", "cold-static" -> {
                final boolean initialized = fixture.virtualMachine().classesByName(TARGET + "$Cold").get(0).isInitialized();
                assertThat(initialized).as(id).isEqualTo(id.equals("cold-static"));
            }
            default -> {
                // Other rows are recorded for later engine comparisons.
            }
        }
    }

    private static Outcome golden(EvaluationContext context) throws Exception {
        final VirtualMachine vm = context.thread().virtualMachine();
        final String id = context.entry().id();
        if ("exception-detail".equals(id) || "generic-receiver".equals(id)) {
            final boolean exception = "exception-detail".equals(id);
            final String name = exception ? "exceptionGolden" : "genericGolden";
            final StackFrame frame = context.frame();
            final Value argument = exception ? context.bindings().get("$exception")
                : frame.getValue(frame.visibleVariableByName("sessions"));
            final ClassType fixture = fixture(vm);
            return context.render(fixture.invokeMethod(context.thread(), fixture.methodsByName(name).get(0),
                List.of(argument), ClassType.INVOKE_SINGLE_THREADED));
        }
        final ObjectReference receiver = Objects.requireNonNull(context.frame().thisObject());
        final boolean outer = "outer-this".equals(id);
        final Method method = receiver.referenceType().methodsByName(outer ? "goldenOuter" : "golden").get(0);
        return context.render(receiver.invokeMethod(context.thread(), method,
            outer ? List.of() : List.of(vm.mirrorOf(id)), ObjectReference.INVOKE_SINGLE_THREADED));
    }

    private static ClassType fixture(VirtualMachine vm) {
        return (ClassType) vm.classesByName(TARGET).get(0);
    }

    static LocalProjectClasspathProvider noLocalClasspath() {
        return new LocalProjectClasspathProvider(REPORTS, name -> null,
            (command, workingDirectory, timeoutSeconds) -> {
                throw new AssertionError("Fixture must not invoke Maven for classpath discovery");
            });
    }
}
