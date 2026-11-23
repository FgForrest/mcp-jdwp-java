package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.*;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.InterpreterCorpus.Entry;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.evaluation.fixture.CompanionTrackingLoader;
import one.edee.mcp.jdwp.evaluation.fixture.PrivateMembers;
import one.edee.mcp.jdwp.evaluation.fixture.TypeBindingFixture;
import one.edee.mcp.jdwp.evaluation.fixture.InvocationTarget;
import one.edee.mcp.jdwp.evaluation.interpret.EvaluationRouter;
import one.edee.mcp.jdwp.evaluation.interpret.Interpreter;
import one.edee.mcp.jdwp.evaluation.interpret.Planner;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.assertj.core.api.SoftAssertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import tools.jackson.databind.json.JsonMapper;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Compares interpreter and compiler behavior in fresh target JVMs. */
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
    private static final int COLD_TRIALS = 5;
    private static final int HITS_PER_TRIAL = 5;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Rows whose expected observation comes from a javac-compiled fixture method. */
    private static final Set<String> FIXTURE_GOLDENS = Set.of("private-field", "private-map", "private-map-call",
        "private-session", "private-method", "super-call", "super-field", "erased-field-navigation",
        "generic-receiver", "outer-this", "outer-implicit", "nested-class-literal", "exception-detail",
        "unqualified-instance", "concat-evaluation-order", "block-private-field");
    private static final String COLD_CLASS = TARGET + "$Cold";

    /** A candidate engine can read a fresh frame and use the same bindings the oracle saw. */
    record EvaluationContext(Entry entry, ThreadReference thread, Map<String, Value> bindings,
                             JDIConnectionService connection, Map<String, ObjectReference> fixtureReferences) {
        StackFrame frame() throws IncompatibleThreadStateException {
            return thread.frame(0);
        }

        Outcome render(@Nullable Value value) {
            String rendered = connection.formatFieldValue(value);
            if (value instanceof ObjectReference && !(value instanceof StringReference)) {
                rendered = rendered.replaceFirst("^(Object|Array)#\\d+ ", "$1#<id> ");
            }
            final boolean normalizeCharArrayString = "char-array-string".equals(entry.id());
            if (normalizeCharArrayString) {
                rendered = rendered.replaceFirst("^\"\\[C@[0-9a-f]+\"$", "\"[C@<hash>\"");
            }
            final var facts = InterpreterValueFacts.capture(value, fixtureReferences, normalizeCharArrayString);
            return new Outcome("value", facts.javaClass(), rendered, facts);
        }
    }

    @FunctionalInterface
    interface Engine {
        Outcome evaluate(EvaluationContext context) throws Exception;
    }

    /** One rendered result or Java exception observed during replay. */
    record Outcome(String kind, String javaClass, String rendered, @Nullable InterpreterValueFacts facts) {
        /** Keeps the exact evaluator text for exceptions thrown by target code. */
        static Outcome targetException(String javaClass, String reply) {
            return new Outcome("exception", javaClass, reply, null);
        }

        static Outcome targetException(String javaClass) {
            return new Outcome("exception", javaClass, javaClass, null);
        }

        String expectedClass() {
            return "compile error".equals(kind) ? rendered : javaClass;
        }
    }

    record Calls(int count, String order) {
    }

    record Observation(Outcome outcome, Calls calls, Map<String, Boolean> initializedClasses) {
    }

    record Row(String id, @Nullable String stop, @Nullable Observation compiled,
               @Nullable Observation golden, @Nullable String skipped) {
    }

    @FunctionalInterface
    interface BeforeMain {
        void run(ThreadReference thread) throws Exception;
    }

    private record AttachedFixture(VirtualMachine vm, CountingJdwpConnection wire, EvaluationGuard guard,
                                   JDIConnectionService connection) {
        JdiExpressionEvaluator evaluator(InMemoryJavaCompiler compiler) {
            return new JdiExpressionEvaluator(compiler, connection, guard, noLocalClasspath());
        }
    }

    @FunctionalInterface
    private interface FixtureAction<T> {
        T run(AttachedFixture fixture) throws Exception;
    }

    private record Trial(String version, InterpreterBaseline.Sample cold, List<InterpreterBaseline.Sample> hits) {
    }

    private record TargetSamples(Path javaHome, String version, List<InterpreterBaseline.Sample> cold,
                                 List<InterpreterBaseline.Sample> hits) {
    }

    @Test
    void distinguishesReferenceMeaningAcrossReplays() throws Exception {
        final var distinct = List.of(mainRow("class-literal", "int[].class"), mainRow("array-value", "items"),
            mainRow("array-identity", "items"), mainRow("private-map", "store.map"));
        final var entries = new ArrayList<>(distinct);
        entries.addAll(List.of(mainRow("boxed", "boxed"), mainRow("string", "text"),
            mainRow("bounded-array", "new int[20]")));
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            final var oracle = replayTestEngine(javaHome, entries, context -> testReference(context, false));
            final var wrong = replayTestEngine(javaHome, entries, context -> testReference(context, true));
            for (Entry entry : distinct) {
                assertThat(wrong.get(entry.id()).outcome().rendered())
                    .as(entry.id()).isEqualTo(oracle.get(entry.id()).outcome().rendered());
                assertThat(wrong.get(entry.id())).as(entry.id()).isNotEqualTo(oracle.get(entry.id()));
            }
            assertThat(wrong.get("boxed")).isEqualTo(oracle.get("boxed"));
            assertThat(facts(oracle, "boxed").scalar()).isEqualTo("1000");
            assertThat(wrong.get("string")).isEqualTo(oracle.get("string"));
            assertThat(facts(oracle, "string").scalar()).isEqualTo("abc");
            final var array = Objects.requireNonNull(facts(oracle, "bounded-array").array());
            assertThat(array.length()).isEqualTo(20);
            assertThat(array.elements()).hasSize(16).allSatisfy(element -> assertThat(element.scalar()).isEqualTo("0"));
        }
    }

    @Test
    void distinguishesPrematureInitializationEvenWhenValuesAgree() throws Exception {
        final var entries = List.of(mainRow("cold-constant", TARGET + ".Cold.CONSTANT"),
            mainRow("cold-static", TARGET + ".Cold.initialized"), mainRow("cold-static-counter", "initializations"));
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            final var oracle = replayTestEngine(javaHome, entries, coldTestEngine(false));
            final var wrong = replayTestEngine(javaHome, entries, coldTestEngine(true));
            for (Entry entry : entries) {
                assertThat(wrong.get(entry.id()).outcome()).as(entry.id()).isEqualTo(oracle.get(entry.id()).outcome());
            }
            assertThat(wrong.get("cold-constant")).isNotEqualTo(oracle.get("cold-constant"));
            assertThat(oracle.get("cold-constant").initializedClasses()).containsEntry(COLD_CLASS, false);
            assertThat(wrong.get("cold-constant").initializedClasses()).containsEntry(COLD_CLASS, true);
            assertThat(wrong.get("cold-static")).isEqualTo(oracle.get("cold-static"));
            assertThat(wrong.get("cold-static-counter")).isEqualTo(oracle.get("cold-static-counter"));
        }
    }

    @Test
    void evaluatingAStopHasNoSuspendingRequestForAFutureStop() throws Exception {
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            replayTestEngine(javaHome, List.of(mainRow("main-probe", "1")), context -> {
                final var requests = context.thread().virtualMachine().eventRequestManager();
                assertThat(requests.breakpointRequests()).isEmpty();
                assertThat(requests.classPrepareRequests()).noneMatch(EventRequest::isEnabled);
                return context.render(context.thread().virtualMachine().mirrorOf(1));
            });
        }
    }

    /** Verifies private access in ordinary compiled blocks and lambdas on each target JDK. */
    @Test
    void compilesPrivateMembersAndPreservesTargetFailures() throws Exception {
        final String owner = PrivateMembers.class.getName();
        final String internal = "{ for (int i = 0; i < 1; i++) return text.value; return null; }";
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withFixture(javaHome, fixture -> {
                final JdiExpressionEvaluator evaluator = fixture.evaluator(new InMemoryJavaCompiler());
                replay(fixture.vm(), List.of(), fixture.connection(), compiledEngine(evaluator), thread -> {
                    evaluator.configureCompilerClasspath(thread);
                    assertThat(evaluateBlock(evaluator, thread, "{ int sum = 0; for (int i = 0; i < 2; i++) "
                        + "sum += new " + owner + "().amount; return sum; }")).isEqualTo("14");
                    assertThat(evaluateBlock(evaluator, thread,
                        "((java.util.function.IntSupplier) () -> this.privateMethod()).getAsInt()")).isEqualTo("22");
                    assertThat(evaluateBlock(evaluator, thread, "{ return " + owner + ".hidden().hiddenAmount; }"))
                        .isEqualTo("41");
                    assertThat(evaluateBlock(evaluator, thread, "{ return " + owner + ".TOTAL; }")).isEqualTo("12");
                    assertThat(evaluateBlock(evaluator, thread, "{ return new " + owner + "(5).amount; }"))
                        .isEqualTo("5");
                    assertThat(evaluateBlock(evaluator, thread, "{ int sum = 0; for (" + owner + " entry : new "
                        + owner + "().entries) sum += entry.amount; return sum; }")).isEqualTo("7");
                    assertThatThrownBy(() -> evaluateBlock(evaluator, thread,
                        "{ return new " + owner + "().hidden.hiddenAmount; }"))
                        .hasMessageContaining("is not visible");
                    assertThat(evaluateBlock(evaluator, thread, "{ return switch (n) { case 2 -> new "
                        + owner + "().select(\"abc\"); default -> 0; }; }")).isEqualTo("3");
                    assertThat(evaluateBlock(evaluator, thread, "{ try { new " + owner + "().checked(); "
                        + "} catch (java.io.IOException expected) { return 45; } return 0; }")).isEqualTo("45");
                    assertThat(evaluateBlock(evaluator, thread, "{ " + owner + ".receiver().ordered("
                        + owner + ".argument(\"A\"), " + owner + ".argument(\"B\")); return " + owner + ".trace(); }"))
                        .isEqualTo("RABM");
                    final String direct = "{ new " + owner + "().throwDirectly(); return null; }";
                    final String reflected = "{ ((Runnable) () -> new " + owner + "().fail()).run(); return null; }";
                    final Throwable directFailure = org.assertj.core.api.Assertions.catchThrowable(
                        () -> evaluateBlock(evaluator, thread, direct));
                    assertThat(directFailure).hasMessage("Target VM threw exception: java.lang.IllegalStateException");
                    assertThatThrownBy(() -> evaluateBlock(evaluator, thread, reflected))
                        .hasMessage(directFailure.getMessage());
                    assertThatThrownBy(() -> evaluateBlock(evaluator, thread,
                        "{ new " + owner + "().failInaccessible(); return null; }"))
                        .hasMessage("Target VM threw exception: java.lang.reflect.InaccessibleObjectException");
                    final String caught = "{ try { return text.value; } catch (RuntimeException e) { return null; } }";
                    for (String inaccessible : List.of(internal, internal, caught)) {
                        assertThatThrownBy(() -> evaluateBlock(evaluator, thread, inaccessible))
                            .hasMessageContaining("The field java.lang.String.value is not visible")
                            .hasMessageContaining("The member is in a JDK module that is not open.")
                            .hasMessageNotContaining("InaccessibleObjectException");
                    }
                }, new StringBuilder(), null);
                return true;
            });
        }
    }

    /** Compares an untaken branch with the primary-only execution used on main. */
    @Test
    void untakenCompanionsDoNotRunTheTargetLoader(@TempDir Path directory) throws Exception {
        final String owner = PrivateMembers.AccessSites.class.getCanonicalName();
        final String body = "if (" + owner + ".companionGuard()) { Object first = new "
            + CompanionTrackingLoader.ABSENT + "() {}; Object second = new Object() {}; "
            + "return first.toString() + second.toString(); } "
            + "return ((java.util.function.Supplier<String>) () -> " + owner + ".companionLambda()).get();";
        final Path optional = Files.writeString(directory.resolve("Absent.java"),
            "package optionalfixture; public class Absent {}");
        final Path optionalClasses = Files.createDirectory(directory.resolve("compile-only"));
        final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, "-d", optionalClasses.toString(), optional.toString())).isZero();
        for (final Path home : InterpreterTargetVm.javaHomes()) {
            final String main = evaluateUntakenCompanion(home, optionalClasses, body, true);
            assertThat(main).as("primary-only execution on %s", home).isEqualTo("ok | guard -> lambda");
            assertThat(evaluateUntakenCompanion(home, optionalClasses, body, false))
                .as("ordinary evaluation on %s", home).isEqualTo(main);
        }
    }

    /** Executes in a fresh target, seeding the compiler with a superclass absent from the target loader. */
    private static String evaluateUntakenCompanion(Path javaHome, Path optionalClasses, String body, boolean primaryOnly)
        throws Exception {
        return withFixture(javaHome, fixture -> {
            // The primary-only variant gives the comparison the same bytecode input as the original evaluator.
            final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler() {
                /** Gives ECJ the absent superclass and optionally exposes only the primary output. */
                @Override
                Map<String, byte[]> compile(String name, String source, @Nullable Configuration config)
                    throws JdiEvaluationException {
                    final @Nullable Configuration optional = config == null ? null : new Configuration(config.jdkPath(),
                        config.classpath() + File.pathSeparator + optionalClasses, config.targetMajorVersion());
                    final Map<String, byte[]> classes = super.compile(name, source, optional);
                    if (!primaryOnly || classes.size() < 2) {
                        return classes;
                    }
                    for (final byte[] bytes : classes.values()) {
                        if (new ClassReader(bytes).getClassName().replace('/', '.').equals(name)) {
                            return Map.of(name, bytes);
                        }
                    }
                    throw new AssertionError("Missing primary wrapper");
                }
            };
            final JdiExpressionEvaluator evaluator = fixture.evaluator(compiler);
            final StringBuilder result = new StringBuilder();
            replay(fixture.vm(), List.of(), fixture.connection(), compiledEngine(evaluator), thread -> {
                evaluator.configureCompilerClasspath(thread);
                result.append(evaluateBlock(evaluator, thread, "{ " + body + " }")).append(" | ")
                    .append(evaluateBlock(evaluator, thread,
                        "System.getProperty(\"" + CompanionTrackingLoader.TRACE + "\", \"\")"));
            }, new StringBuilder(), null);
            return result.toString();
        }, "-Djava.system.class.loader=" + CompanionTrackingLoader.class.getName());
    }

    /** Evaluates with a fresh frame after each prior invocation invalidated its mirror. */
    private static String evaluateBlock(JdiExpressionEvaluator evaluator, ThreadReference thread, String source)
        throws Exception {
        final Value value = evaluator.evaluate(thread.frame(0), source);
        if (value instanceof StringReference string) {
            return string.value();
        }
        if (value instanceof ObjectReference boxed) {
            final Field field = boxed.referenceType().fieldByName("value");
            if (field != null) {
                return String.valueOf(boxed.getValue(field));
            }
        }
        return String.valueOf(value);
    }

    private static Entry mainRow(String id, String expression) {
        return new Entry(id, "jdwp_evaluate_expression", "expression", expression, "main", null, null);
    }

    private static InterpreterValueFacts facts(Map<String, Observation> observations, String id) {
        return Objects.requireNonNull(observations.get(id).outcome().facts());
    }

    private static Outcome testReference(EvaluationContext context, boolean wrong) throws Exception {
        final VirtualMachine vm = context.thread().virtualMachine();
        final StackFrame frame = context.frame();
        final var items = (ArrayReference) frame.getValue(frame.visibleVariableByName("items"));
        return switch (context.entry().id()) {
            case "class-literal" -> context.render(wrong
                ? vm.classesByName("java.lang.String").get(0).classObject() : items.referenceType().classObject());
            case "array-value", "array-identity" -> {
                if (!wrong && context.entry().id().equals("array-identity")) {
                    yield context.render(items);
                }
                final var copy = ((ArrayType) items.referenceType()).newInstance(items.length());
                copy.setValues(!wrong || context.entry().id().equals("array-identity") ? items.getValues()
                    : List.of(vm.mirrorOf(-1), vm.mirrorOf(-1), vm.mirrorOf(-1)));
                yield context.render(copy);
            }
            case "private-map" -> {
                final ObjectReference receiver = Objects.requireNonNull(frame.thisObject());
                final var store = (ObjectReference) receiver.getValue(receiver.referenceType().fieldByName("store"));
                final var map = (ObjectReference) store.getValue(store.referenceType().fieldByName("map"));
                final var type = (ClassType) map.referenceType();
                yield context.render(wrong ? type.newInstance(context.thread(),
                    type.methodsByName("<init>", "(Ljava/util/Map;)V").get(0),
                    List.of(map), ClassType.INVOKE_SINGLE_THREADED) : map);
            }
            case "boxed" -> context.render(wrong ? vm.mirrorOf(1000)
                : frame.getValue(frame.visibleVariableByName("boxed")));
            case "string" -> context.render(wrong ? vm.mirrorOf("abc")
                : frame.getValue(frame.visibleVariableByName("text")));
            case "bounded-array" -> context.render(((ArrayType) items.referenceType()).newInstance(20));
            default -> throw new AssertionError(context.entry().id());
        };
    }

    private static Engine coldTestEngine(boolean initializeEarly) {
        return context -> {
            final var evaluator = new JdiExpressionEvaluator(new InMemoryJavaCompiler(), context.connection(),
                new EvaluationGuard(), noLocalClasspath());
            evaluator.configureCompilerClasspath(context.thread());
            if (initializeEarly && "cold-constant".equals(context.entry().id())) {
                evaluator.evaluate(context.frame(), TARGET + ".Cold.initialized", context.bindings());
            }
            return compiledResult(evaluator, context);
        };
    }

    private static Map<String, Observation> replayTestEngine(Path javaHome, List<Entry> entries, Engine engine)
        throws Exception {
        return withFixture(javaHome, fixture -> replay(fixture.vm(), entries, fixture.connection(), engine, null,
            new StringBuilder(), null));
    }

    /** Attaches to a fresh fixture started with the given target JVM options. */
    private static <T> T withFixture(Path javaHome, FixtureAction<T> action, String... options) throws Exception {
        try (InterpreterTargetVm target = InterpreterTargetVm.startWithOptions(javaHome, options);
             CountingJdwpConnection wire = CountingJdwpConnection.attach(target.port())) {
            final VirtualMachine vm = Bootstrap.virtualMachineManager().createVirtualMachine(wire);
            try {
                final EvaluationGuard guard = new EvaluationGuard();
                return action.run(new AttachedFixture(vm, wire, guard, connectionFor(vm, guard)));
            } finally {
                try {
                    vm.dispose();
                } catch (VMDisconnectedException ignored) {
                    // The fixture may have exited before the replay completed.
                }
            }
        }
    }

    /** Records the production compiler oracle and JDWP measurements for all three execution paths. */
    @Test
    void recordsCompiledOracleAndBaseline() throws Exception {
        Files.createDirectories(REPORTS);
        final StringBuilder vmStart = new StringBuilder();
        final List<Entry> synthetic;
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("interpreter-corpus.jsonl")),
                StandardCharsets.UTF_8)) {
            synthetic = InterpreterCorpus.read(reader, "interpreter-corpus.jsonl");
        }
        assertThat(synthetic).allSatisfy(entry -> {
            assertThat(entry.stop()).isIn(STOPS);
            assertThat(entry.expectedPlan()).as(entry.id()).isIn(Planner.KindOfPlan.NAVIGATE,
                Planner.KindOfPlan.INVOKE, Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.ERROR);
        });

        final List<Entry> entries = new ArrayList<>(synthetic);
        final String external = System.getProperty("jdwp.live.corpus");
        if (external != null) {
            try (Reader reader = Files.newBufferedReader(Path.of(external))) {
                entries.addAll(InterpreterCorpus.read(reader, external));
            }
            assertThat(entries).extracting(Entry::id).doesNotHaveDuplicates();
        }

        final List<Path> javaHomes = InterpreterTargetVm.javaHomes();
        final Entry probe = synthetic.stream().filter(e -> BASELINE_ID.equals(e.id())).findFirst().orElseThrow();
        final Entry callProbe = synthetic.stream().filter(e -> "private-map-call".equals(e.id())).findFirst().orElseThrow();
        final String baselineRows = baseline(javaHomes, probe, Planner.KindOfPlan.COMPILE);
        final String navigationRows = baseline(javaHomes, probe, Planner.KindOfPlan.NAVIGATE);
        final String callRows = baseline(javaHomes, callProbe, Planner.KindOfPlan.INVOKE);
        for (Path javaHome : javaHomes) {
            final Path report = withFixture(javaHome, fixture -> writeOracle(fixture, entries, vmStart));
            assertThat(report).isRegularFile();
        }
        InterpreterBaseline.write(REPORTS.resolve("interp-baseline.md"), baselineRows + navigationRows + callRows, vmStart.toString());
    }

    /** Compares results and call traces on each target JDK and checks every checked-in plan. */
    @Test
    void comparesTheRouterWithTheCompiledAndGoldenObservations() throws Exception {
        final List<Entry> entries;
        try (InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("interpreter-corpus.jsonl")),
                StandardCharsets.UTF_8)) {
            entries = InterpreterCorpus.read(reader, "interpreter-corpus.jsonl");
        }
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            final Map<String, Observation> goldens = new LinkedHashMap<>();
            final Map<String, Integer> reasons = new TreeMap<>();
            final Map<String, Observation> oracle = withFixture(javaHome, fixture -> replay(fixture.vm(), entries, fixture.connection(),
                compiledEngine(fixture.evaluator(new InMemoryJavaCompiler())), null, new StringBuilder(), goldens));
            final Map<String, Observation> candidate = withFixture(javaHome, fixture -> {
                final JdiExpressionEvaluator compiler = fixture.evaluator(new InMemoryJavaCompiler());
                final EvaluationRouter strict = new EvaluationRouter(compiler, EvaluationRouter.Mode.INTERPRETER);
                final EvaluationRouter auto = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
                return replay(fixture.vm(), entries, fixture.connection(), routerEngine(strict, auto, reasons),
                    null, new StringBuilder(), null);
            });
            for (Entry entry : entries) {
                final Observation result = candidate.get(entry.id());
                if (entry.expectedPlan() == Planner.KindOfPlan.ERROR) {
                    assertThat(result.outcome().kind()).as("%s: %s", javaHome, entry.id()).isEqualTo("planner error");
                } else {
                    final Observation expected = goldens.getOrDefault(entry.id(), oracle.get(entry.id()));
                    assertThat(result).as("%s: %s", javaHome, entry.id()).isEqualTo(expected);
                }
            }
            System.out.printf("Differential %s: %s%n", javaHome.getFileName(),
                entries.stream().collect(java.util.stream.Collectors.groupingBy(Entry::expectedPlan,
                    java.util.TreeMap::new, java.util.stream.Collectors.counting())));
            System.out.printf("COMPILE reasons %s: %s%n", javaHome.getFileName(), reasons);
        }
    }

    /** Target class of the isolated invocation cases. */
    private static final String INVOCATION_TARGET = InvocationTarget.class.getName();
    /** Prefix used for fixture types and static calls in expressions. */
    private static final String INVOCATION_PREFIX = INVOCATION_TARGET + ".";

    /**
     * An expression paired with its fixture oracle.
     *
     * @param id case key used by the compiled fixture
     * @param expression source evaluated at the stop
     */
    private record InvocationRow(String id, String expression) {}

    /** Regressions and controls for target calls. */
    private static final List<InvocationRow> INVOCATION_ROWS = List.of(
        new InvocationRow("helper-retry", INVOCATION_PREFIX + "takesInteger(" + INVOCATION_PREFIX + "uncachedInteger())"),
        new InvocationRow("float-constant", INVOCATION_PREFIX + "reflectString(\"x\" + 1e23)"),
        new InvocationRow("polluted-receiver", INVOCATION_PREFIX + "polluted.get().mark()"),
        new InvocationRow("polluted-field", INVOCATION_PREFIX + "polluted.get().flag"),
        new InvocationRow("polluted-argument", INVOCATION_PREFIX + "takesPayload(" + INVOCATION_PREFIX
            + "substitutedPolluted.get(), " + INVOCATION_PREFIX + "later())"),
        new InvocationRow("polluted-widening-argument", INVOCATION_PREFIX + "takesObject(" + INVOCATION_PREFIX
            + "substitutedPolluted.get())"),
        new InvocationRow("polluted-interface-argument", INVOCATION_PREFIX + "takesTagged(" + INVOCATION_PREFIX
            + "interfacePolluted.get())"),
        new InvocationRow("polluted-declared-bound", INVOCATION_PREFIX + "boundedPolluted.get().flag"),
        new InvocationRow("capture-ordered-interface", INVOCATION_PREFIX + "ordered.get().mark()"),
        new InvocationRow("capture-ordered-declared", INVOCATION_PREFIX + "declaredSpecific.get().mark()"),
        new InvocationRow("capture-multiple-declared", INVOCATION_PREFIX + "multiple.get().chooseBound(1)"),
        new InvocationRow("pattern-target-field", "(Object) " + INVOCATION_PREFIX
            + "TOKEN instanceof java.lang.String p && " + INVOCATION_PREFIX + "sameToken(p)"),
        new InvocationRow("pattern-target-result", "(Object) " + INVOCATION_PREFIX
            + "reflectString(\"token\") instanceof java.lang.String p && " + INVOCATION_PREFIX + "sameToken(p)"),
        new InvocationRow("pattern-string-receiver", "(Object) (\"to\" + \"ken\") instanceof java.lang.String p && "
            + INVOCATION_PREFIX + "sameToken(p.toString())"),
        new InvocationRow("float-fragment", INVOCATION_PREFIX + "reflectString((\"x\" + 1e23) + "
            + INVOCATION_PREFIX + "later())"),
        new InvocationRow("float-conditional-constant", INVOCATION_PREFIX + "reflectString((1.25f > 0 ? \"token\" : \"other\"))"),
        new InvocationRow("capture-intersection", INVOCATION_PREFIX + "intersection.get().chooseBound(null)"),
        new InvocationRow("pattern-constant", "(Object) (\"to\" + \"ken\") instanceof java.lang.String p && "
            + INVOCATION_PREFIX + "sameToken(p)"),
        new InvocationRow("pattern-surrogate", "(Object) (\"\" + " + INVOCATION_PREFIX
            + "isolatedChar()) instanceof java.lang.String p ? " + INVOCATION_PREFIX + "codeUnit(p) : -1"),
        new InvocationRow("interface-private", INVOCATION_PREFIX + "childPrivate.probe(1)"),
        new InvocationRow("own-private", INVOCATION_PREFIX + "ownPrivate.own(1)"),
        new InvocationRow("static-hiding", INVOCATION_PREFIX + "HidingChild.hidden(1)"),
        new InvocationRow("nested-negation", "(!(!(" + INVOCATION_PREFIX + "slot instanceof "
            + INVOCATION_PREFIX + "Payload shadow))) && shadow.mark()"),
        new InvocationRow("false-pattern", "(!(" + INVOCATION_PREFIX + "slot instanceof "
            + INVOCATION_PREFIX + "Payload shadow)) ? false : shadow.mark()"),
        new InvocationRow("parenthesised-and", "(((" + INVOCATION_PREFIX + "slot instanceof "
            + INVOCATION_PREFIX + "Payload p))) && (p.mark())"),
        new InvocationRow("external-constant", INVOCATION_PREFIX + "sameToken(" + INVOCATION_PREFIX + "Tokens.TOKEN)"),
        new InvocationRow("external-dynamic", INVOCATION_PREFIX + "sameToken(" + INVOCATION_PREFIX + "Tokens.DYNAMIC)"),
        new InvocationRow("constant-conditional", INVOCATION_PREFIX + "sameToken((2 > 1 ? (\"to\" + \"ken\") : \"different\"))"),
        new InvocationRow("dynamic-integer-concat", INVOCATION_PREFIX + "sameOne(\"\" + (" + INVOCATION_PREFIX + "later() - 2))"),
        new InvocationRow("string-method-rebind", INVOCATION_PREFIX + "takesCodeUnit((\"a\" + \"\").charAt(8))"),
        new InvocationRow("pattern-pin", INVOCATION_PREFIX + "slot instanceof " + INVOCATION_PREFIX + "Payload p && "
            + INVOCATION_PREFIX + "clearAndCollect() && p.mark()"),
        new InvocationRow("pattern-false", INVOCATION_PREFIX + "slot instanceof " + INVOCATION_PREFIX
            + "Payload shadow ? true : shadow.mark()"),
        new InvocationRow("pattern-shadow", "!(" + INVOCATION_PREFIX + "slot instanceof " + INVOCATION_PREFIX + "Payload shadow) || shadow.mark()"),
        new InvocationRow("literal-identity", INVOCATION_PREFIX + "sameToken(\"token\")"),
        new InvocationRow("constant-concat-identity", INVOCATION_PREFIX + "sameToken(\"to\" + \"ken\")"),
        new InvocationRow("wildcard-overload", INVOCATION_PREFIX + "source.get().ping()"),
        new InvocationRow("wildcard-unbounded", INVOCATION_PREFIX + "unbounded.get().ping()"),
        new InvocationRow("wildcard-lower", INVOCATION_PREFIX + "lower.get().ping()"),
        new InvocationRow("constant-receiver", INVOCATION_PREFIX + "sameToken((\"to\" + \"ken\").toString())"),
        new InvocationRow("constant-field", INVOCATION_PREFIX + "sameToken(" + INVOCATION_PREFIX + "TOKEN)"),
        new InvocationRow("dynamic-final-field", INVOCATION_PREFIX + "sameToken(" + INVOCATION_PREFIX + "DYNAMIC)"),
        new InvocationRow("conditional-token", INVOCATION_PREFIX + "sameToken(" + INVOCATION_PREFIX
            + "yes ? (String) \"token\" : " + INVOCATION_PREFIX + "DYNAMIC)"),
        new InvocationRow("safe-integer-concat", INVOCATION_PREFIX + "codeUnit(\"\" + " + INVOCATION_PREFIX + "later())"),
        new InvocationRow("surrogate-constructor", "new " + INVOCATION_PREFIX + "StringInput(\"\" + "
            + INVOCATION_PREFIX + "isolatedChar()).code"),
        new InvocationRow("constant-constructor", "new " + INVOCATION_PREFIX + "StringInput(\"to\" + \"ken\").same"),
        new InvocationRow("erased-field", INVOCATION_PREFIX + "erased.get().flag"),
        new InvocationRow("erased-call", INVOCATION_PREFIX + "erased.get().mark()"),
        new InvocationRow("private-varargs", INVOCATION_PREFIX + "choice.pick(1)"),
        new InvocationRow("private-default", INVOCATION_PREFIX + "defaultChild.sample()"),
        new InvocationRow("unqualified-static", "ownString()"),
        new InvocationRow("nested-generics", INVOCATION_PREFIX + "nested.get().get().ping()"),
        new InvocationRow("generic-bridge", INVOCATION_PREFIX + "bridge.choose(\"arg\")"),
        new InvocationRow("super-default", "super.inheritedDefault()"),
        new InvocationRow("interface-default", INVOCATION_PREFIX + "api.inheritedDefault()"),
        new InvocationRow("interface-object", INVOCATION_PREFIX + "api.toString()"),
        new InvocationRow("surrogate-object", INVOCATION_PREFIX + "codeUnit(" + INVOCATION_PREFIX + "isolatedText() + \"\")"),
        new InvocationRow("surrogate-char", INVOCATION_PREFIX + "codeUnit(\"\" + " + INVOCATION_PREFIX + "isolatedChar())"),
        new InvocationRow("surrogate-string-intrinsic", INVOCATION_PREFIX + "isolatedString().length()"),
        new InvocationRow("surrogate-folded-char", INVOCATION_PREFIX + "codeUnit(\"\" + (char) 0xD800)"),
        new InvocationRow("float-double", "\"\" + " + INVOCATION_PREFIX + "fraction + \"/\" + " + INVOCATION_PREFIX + "decimal"),
        new InvocationRow("constructor", "new " + one.edee.mcp.jdwp.evaluation.fixture.InterpreterTarget.class.getName() + ".OnlyConstructor(" + INVOCATION_PREFIX + "later()).number"),
        new InvocationRow("unboxing-null", INVOCATION_PREFIX + "take(" + INVOCATION_PREFIX + "missing, " + INVOCATION_PREFIX + "later())"),
        new InvocationRow("target-exception", INVOCATION_PREFIX + "explode()"),
        new InvocationRow("cold-null-object", "new " + one.edee.mcp.jdwp.evaluation.fixture.InterpreterTarget.class.getName() + ".NullString() + " + INVOCATION_PREFIX + "ownString()"),
        new InvocationRow("boxing-cache", "this.choose(127) == this.choose(127) && this.choose(128) != this.choose(128)"),
        new InvocationRow("block-store-explicit", "{ " + INVOCATION_PREFIX + "Payload item = " + INVOCATION_PREFIX
            + "polluted.get(); return " + INVOCATION_PREFIX + "later(); }"),
        new InvocationRow("block-store-inferred", "{ var item = " + INVOCATION_PREFIX + "polluted.get(); return "
            + INVOCATION_PREFIX + "later(); }"),
        new InvocationRow("block-store-assignment", "{ " + INVOCATION_PREFIX + "Payload item = null; item = "
            + INVOCATION_PREFIX + "polluted.get(); return " + INVOCATION_PREFIX + "later(); }"),
        new InvocationRow("block-store-before-call", "{ var item = " + INVOCATION_PREFIX + "polluted.get(); return item.mark(); }"),
        new InvocationRow("block-store-wide", "{ Object item = " + INVOCATION_PREFIX + "polluted.get(); return item; }"),
        new InvocationRow("block-store-float", "{ final int exponent = (int) 3.5; String text = \"x\" + exponent; return "
            + INVOCATION_PREFIX + "reflectString(text); }"),
        new InvocationRow("block-dead-call", "{ if (false) " + INVOCATION_PREFIX + "later(); return 1; }"),
        new InvocationRow("block-dead-boxing", "{ if (false) { Object count = 3; return count; } return 1; }"),
        new InvocationRow("block-dead-consumer", "{ String text = \"token\"; if (false) return "
            + INVOCATION_PREFIX + "sameToken(text); return 1; }"),
        new InvocationRow("block-constant-reachability", "{ int count; if (false) return " + INVOCATION_PREFIX
            + "explode(); if (true) count = 3; return count; }"),
        new InvocationRow("block-call-condition", "{ String text = " + INVOCATION_PREFIX + "isolatedString(); if ("
            + INVOCATION_PREFIX + "sameToken(\"token\")) return text.length(); return 0; }"),
        new InvocationRow("block-local-constant", "{ String text = \"to\" + \"ken\"; Object alias = text; return "
            + INVOCATION_PREFIX + "sameToken((String) alias); }"),
        new InvocationRow("block-pattern-constant", "{ Object text = \"token\"; if (text instanceof String matched) return "
            + INVOCATION_PREFIX + "sameToken(matched); return false; }"),
        new InvocationRow("block-unsafe-concat", "{ String text; if (" + INVOCATION_PREFIX + "yes) text = \"\" + "
            + INVOCATION_PREFIX + "isolatedChar(); else text = \"safe\"; String alias = text; return "
            + INVOCATION_PREFIX + "codeUnit(alias); }"),
        new InvocationRow("block-safe-concat", "{ int value = " + INVOCATION_PREFIX + "later(); return "
            + INVOCATION_PREFIX + "codeUnit(\"\" + value); }"),
        new InvocationRow("block-after-guard", "{ if (!(" + INVOCATION_PREFIX + "slot instanceof " + INVOCATION_PREFIX
            + "Payload shadow)) return false; return shadow.mark(); }"),
        new InvocationRow("block-inherited-membership", "{ " + INVOCATION_PREFIX + "ChoiceChild copy = "
            + INVOCATION_PREFIX + "choice; return copy.pick(1); }"),
        new InvocationRow("result-gc", INVOCATION_PREFIX + "fresh().mark()")
    );

    /** Stops only the event thread and checks invocation results, traces and pin handoff. */
    @Test
    void preservesJavaInvocationSemantics(@TempDir Path temp) throws Exception {
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            final Path gcResult = Files.createTempDirectory(temp, "target-").resolve("gc-done");
            try (InterpreterTargetVm target = InterpreterTargetVm.startClass(javaHome, InvocationTarget.class, gcResult.toString());
                 CountingJdwpConnection wire = CountingJdwpConnection.attach(target.port())) {
                final VirtualMachine vm = Bootstrap.virtualMachineManager().createVirtualMachine(wire);
                try {
                    final EventRequestManager requests = vm.eventRequestManager();
                    final ClassPrepareRequest load = requests.createClassPrepareRequest();
                    load.addClassFilter(INVOCATION_TARGET);
                    load.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    load.enable();
                    boolean checked = false;
                    while (!checked) {
                        final EventSet events = vm.eventQueue().remove(30_000);
                        assertThat(events).as("events before the fixture stop").isNotNull();
                        try {
                            for (Event event : events) {
                                if (event instanceof ClassPrepareEvent prepare) {
                                    requests.deleteEventRequest(prepare.request());
                                    final BreakpointRequest stop = requests.createBreakpointRequest(
                                        prepare.referenceType().methodsByName("invocationStop").get(0).location());
                                    stop.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                                    stop.enable();
                                } else if (event instanceof BreakpointEvent breakpoint) {
                                    requests.deleteEventRequest(breakpoint.request());
                                    checkStop(vm, breakpoint, wire, target, gcResult, javaHome);
                                    checked = true;
                                } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                                    throw new AssertionError("fixture exited before the stop");
                                }
                            }
                        } finally {
                            events.resume();
                        }
                    }
                } finally {
                    vm.dispose();
                }
            }
        }
    }

    /** Runs each engine from the same fixture state at the stop. */
    private static void checkStop(VirtualMachine vm, BreakpointEvent breakpoint, CountingJdwpConnection wire,
                                  InterpreterTargetVm target, Path gcResult, Path javaHome) throws Exception {
        final ThreadReference thread = breakpoint.thread();
        final ClassType owner = (ClassType) breakpoint.location().declaringType();
        final ObjectReference receiver = thread.frame(0).thisObject();
        final Field trace = owner.fieldByName("trace");
        final Field calls = owner.fieldByName("calls");
        final Method reset = owner.methodsByName("reset").get(0);
        final Method golden = owner.methodsByName("invocationGolden").get(0);
        final EvaluationGuard guard = new EvaluationGuard();
        final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(new InMemoryJavaCompiler(), connectionFor(vm, guard),
            guard, InterpreterHarnessLiveTest.noLocalClasspath());
        final EvaluationRouter auto = new EvaluationRouter(evaluator, EvaluationRouter.Mode.AUTO);
        final EvaluationRouter compiler = new EvaluationRouter(evaluator, EvaluationRouter.Mode.COMPILER);
        final EvaluationRouter.ToolJob compiledJob = compiler.toolJob(thread, () -> {});
        final SoftAssertions checks = new SoftAssertions();
        checkPatternApplicability(vm, thread, owner, trace, calls, reset, auto, javaHome, gcResult.getParent(), checks);
        for (InvocationRow row : INVOCATION_ROWS) {
            owner.invokeMethod(thread, reset, List.of(), ClassType.INVOKE_SINGLE_THREADED);
            final String expected = observe(owner, trace, calls, () -> renderInvocationValue(receiver.invokeMethod(thread, golden,
                List.of(vm.mirrorOf(row.id())), ObjectReference.INVOKE_SINGLE_THREADED)));
            owner.invokeMethod(thread, reset, List.of(), ClassType.INVOKE_SINGLE_THREADED);
            final Planner.Plan plan = auto.prepare(auto.parse(row.expression()), thread, () -> thread.frame(0), Map.of());
            final int before = thread.suspendCount();
            // Count only the auto run's static invocations when checking the boxing retry.
            final CountingJdwpConnection.Command staticInvoke = new CountingJdwpConnection.Command(3, 3);
            final long invokesBefore = wire.snapshot().getOrDefault(staticInvoke, 0L);
            if (row.id().equals("result-gc") || row.id().equals("helper-retry")) {
                Files.deleteIfExists(gcResult);
                wire.beforeNextPin(() -> collectBeforePin(target, gcResult));
            }
            final String actual = observe(owner, trace, calls,
                () -> auto.evaluateEvent(row.expression(), thread, Map.of(), InterpreterHarnessLiveTest::renderInvocationValue));
            if (row.id().equals("helper-retry")) {
                final long invokes = wire.snapshot().getOrDefault(staticInvoke, 0L) - invokesBefore;
                checks.assertThat(invokes).as("user methods once and Integer.valueOf retried").isEqualTo(4);
            }
            assertThat(thread.suspendCount()).as(row.id()).isEqualTo(before);
            assertThat(guard.isEvaluating(thread)).as(row.id()).isFalse();
            owner.invokeMethod(thread, reset, List.of(), ClassType.INVOKE_SINGLE_THREADED);
            final String compiled = observe(owner, trace, calls, () -> compiledJob.evaluate(compiler.parse(row.expression()),
                () -> thread.frame(0), Map.of(), InterpreterHarnessLiveTest::renderInvocationValue));
            checks.assertThat(thread.suspendCount()).as(row.id() + " suspend count").isEqualTo(before);
            switch (row.id()) {
                case "capture-intersection", "capture-multiple-declared", "pattern-constant", "pattern-surrogate",
                     "pattern-string-receiver", "block-pattern-constant",
                     "float-conditional-constant", "block-store-float", "interface-private", "block-dead-call",
                     "block-dead-boxing", "block-dead-consumer", "block-constant-reachability" -> {
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.COMPILE);
                    checks.assertThat(actual).as(row.id()).isEqualTo(expected).isEqualTo(compiled);
                }
                case "float-constant", "float-fragment" -> {
                    // The fixture golden uses javac's build JDK, which can fold floating-point text differently from ECJ's host JDK.
                    final String traceSuffix = row.id().equals("float-constant") ? "[1 reflectString>]" : "[2 later>reflectString>]";
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.COMPILE);
                    checks.assertThat(actual).as(row.id()).isEqualTo(compiled).endsWith(traceSuffix);
                }
                case "polluted-receiver", "polluted-field", "polluted-argument", "polluted-declared-bound",
                     "block-store-explicit", "block-store-inferred", "block-store-assignment", "block-store-before-call" -> {
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.INVOKE);
                    checks.assertThat(actual).as(row.id()).contains("Interpreter raised java.lang.ClassCastException")
                        .endsWith("[1 Box.get>]");
                    checks.assertThat(expected).contains("ClassCastException").endsWith("[1 Box.get>]");
                    checks.assertThat(compiled).contains("ClassCastException").endsWith("[1 Box.get>]");
                }
                case "nested-negation", "false-pattern" -> {
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.COMPILE);
                    checks.assertThat(actual).as(row.id()).endsWith("[0 ]");
                }
                case "string-method-rebind" -> {
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.INVOKE);
                    checks.assertThat(actual).as(row.id()).contains("IndexOutOfBoundsException").endsWith("[0 ]");
                    checks.assertThat(expected).contains("IndexOutOfBoundsException").endsWith("[0 ]");
                }
                case "result-gc" -> {
                    checks.assertThat(plan.kind()).isEqualTo(Planner.KindOfPlan.INVOKE);
                    checks.assertThat(actual).isEqualTo("JdiEvaluationException: The result was collected by the target's "
                        + "garbage collector before the debugger could pin it. 1 target call already ran. "
                        + "Re-running the expression repeats that call. [1 fresh>]");
                    checks.assertThat(compiled).isEqualTo(expected);
                }
                case "pattern-shadow", "pattern-false", "erased-field", "erased-call" -> {
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.COMPILE);
                    checks.assertThat(actual).as(row.id()).endsWith("[0 ]");
                }
                case "surrogate-object", "surrogate-char", "surrogate-constructor", "surrogate-folded-char", "private-varargs" -> {
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.COMPILE);
                    checks.assertThat(actual).as(row.id()).isEqualTo(expected);
                }
                case "block-after-guard" -> {
                    checks.assertThat(plan).isEqualTo(new Planner.Compile("pattern variable is outside the supported scope: shadow"));
                    checks.assertThat(actual).as(row.id()).isEqualTo(expected);
                }
                case "block-unsafe-concat", "block-inherited-membership" -> {
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.COMPILE);
                    checks.assertThat(actual).as(row.id()).isEqualTo(expected);
                }
                case "unboxing-null" -> {
                    checks.assertThat(plan.kind()).isEqualTo(Planner.KindOfPlan.INVOKE);
                    checks.assertThat(actual).contains("Interpreter raised java.lang.NullPointerException").endsWith("[0 ]");
                    checks.assertThat(expected).endsWith("[0 ]");
                }
                default -> {
                    checks.assertThat(plan.kind()).as(row.id()).isEqualTo(Planner.KindOfPlan.INVOKE);
                    checks.assertThat(actual).as(row.id()).isEqualTo(expected);
                }
            }
        }
        checks.assertAll();
    }

    /**
     * A binding-pattern expression and its expectations on the supported targets.
     *
     * @param id case name used in assertion failures
     * @param source expression with abbreviated fixture qualifiers
     * @param java17 expected Java 17 evaluation plan
     * @param java21 expected Java 21 evaluation plan
     * @param javac17Diagnostic required Java 17 javac error fragment, or null when not checked
     * @param javac21Diagnostic required Java 21 javac error fragment, or null when not checked
     */
    private record PatternRow(String id, String source, Planner.KindOfPlan java17, Planner.KindOfPlan java21,
                              @Nullable String javac17Diagnostic, @Nullable String javac21Diagnostic) {
        /** Expands the fixture's short type qualifier to a name in scope for both compilers. */
        String expression() {
            return source.replace("T.", INVOCATION_PREFIX);
        }
    }

    /** Patterns that require source-type or target-version checks. */
    private static final List<PatternRow> PATTERN_ROWS = List.of(
        new PatternRow("disjoint-generic-pattern", "T.source instanceof T.StringBox matched ? 1 : T.later()",
            Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.COMPILE, "cannot be converted to", "cannot be converted to"),
        new PatternRow("intersection-pattern", "(T.ChoiceBound & T.ExtraChoice) T.bothChoices instanceof "
            + "T.ExtraChoice matched ? 1 : 2",
            Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.COMPILE, "is a subtype of pattern type", null),
        new PatternRow("block-disjoint-generic-pattern",
            "{ var value = T.source; if (value instanceof T.StringBox matched) return 1; return T.later(); }",
            Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.COMPILE, "cannot be converted to", "cannot be converted to"),
        new PatternRow("block-intersection-pattern",
            "{ var value = (T.ChoiceBound & T.ExtraChoice) T.bothChoices; "
                + "if (value instanceof T.ExtraChoice matched) return 1; return 2; }",
            Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.COMPILE, "is a subtype of pattern type", null),
        new PatternRow("block-unconditional-pattern",
            "{ if (T.shadow instanceof T.Payload matched) return matched.flag; return false; }",
            Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.NAVIGATE, "is a subtype of pattern type", null),
        new PatternRow("raw-pattern", "((T.Box) T.source) instanceof T.StringBox matched ? 1 : 2",
            Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.COMPILE, null, null),
        new PatternRow("captured-result-pattern", "T.source.get() instanceof T.Service matched ? 1 : T.later()",
            Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.COMPILE, "is a subtype of pattern type", null),
        new PatternRow("unconditional-pattern", "T.shadow instanceof T.Payload matched && matched.flag",
            Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.NAVIGATE, "is a subtype of pattern type", null),
        new PatternRow("plain-instanceof", "T.shadow instanceof T.Payload",
            Planner.KindOfPlan.NAVIGATE, Planner.KindOfPlan.NAVIGATE, null, null)
    );

    /** Checks expression and block pattern routing, Java version gating, and fixture call counts. */
    private static void checkPatternApplicability(VirtualMachine vm, ThreadReference thread, ClassType owner,
                                                   Field trace, Field calls, Method reset, EvaluationRouter auto,
                                                   Path javaHome, Path scratch, SoftAssertions checks) throws Exception {
        final boolean java17 = Runtime.Version.parse(vm.version()).feature() == 17;
        for (final PatternRow row : PATTERN_ROWS) {
            final String source = row.expression();
            final boolean block = JdiExpressionEvaluator.isBlockMode(source);
            final Planner.KindOfPlan expectedPlan = java17 ? row.java17() : row.java21();
            final String diagnostic = java17 ? row.javac17Diagnostic() : row.javac21Diagnostic();
            if (diagnostic != null) {
                assertPatternRejectedByJavac(javaHome, scratch, source, diagnostic);
            }
            owner.invokeMethod(thread, reset, List.of(), ClassType.INVOKE_SINGLE_THREADED);
            final Planner.Plan plan = auto.prepare(auto.parse(source), thread, () -> thread.frame(0), Map.of());
            checks.assertThat(plan.kind()).as(row.id()).isEqualTo(expectedPlan);
            final int suspendCount = thread.suspendCount();
            // Replay blocks, rejected rows and read-only controls. The other cases assert routing only.
            if (block || diagnostic != null || expectedPlan == Planner.KindOfPlan.NAVIGATE) {
                final String actual = observe(owner, trace, calls,
                    () -> auto.evaluateEvent(source, thread, Map.of(), InterpreterHarnessLiveTest::renderInvocationValue));
                checks.assertThat(actual).as(row.id()).endsWith("[0 ]");
                if (diagnostic != null) {
                    checks.assertThat(actual).as(row.id()).contains("Compilation failed");
                } else if (expectedPlan == Planner.KindOfPlan.NAVIGATE) {
                    checks.assertThat(actual).as(row.id()).isEqualTo("true [0 ]");
                }
                checks.assertThat(thread.suspendCount()).as(row.id()).isEqualTo(suspendCount);
            }
        }
    }

    /** Checks the exact rejected expression or block against the target JDK's javac. */
    private static void assertPatternRejectedByJavac(Path javaHome, Path scratch, String source, String diagnostic)
        throws Exception {
        final Path input = scratch.resolve("PatternSnippet.java");
        final String body = JdiExpressionEvaluator.isBlockMode(source) ? source : "{ return " + source + "; }";
        Files.writeString(input, "class PatternSnippet { Object evaluate() " + body + " }");
        final String classpath = Path.of(InvocationTarget.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toString();
        final Process javac = new ProcessBuilder(javaHome.resolve("bin/javac").toString(), "-classpath", classpath,
            "-d", scratch.toString(), input.toString()).directory(scratch.toFile()).redirectErrorStream(true).start();
        final String output = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(javac.waitFor()).as(output).isNotZero();
        assertThat(output).contains(diagnostic);
    }

    /** The public tool evaluates a read-only block on a manually held thread without invoking. */
    @Test
    void readsABlockOnAManualHold(@TempDir Path temp) throws Exception {
        for (final Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (InterpreterTargetVm target = InterpreterTargetVm.startClass(javaHome, InvocationTarget.class,
                temp.resolve("gc-done").toString())) {
                checkManualHold(target.port());
            }
        }
    }

    /** Replays the read-only control and the constant dead branches on an actual manual hold. */
    private static void checkManualHold(int port) throws Exception {
        final BreakpointTracker tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final EvaluationGuard guard = new EvaluationGuard();
        final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
        final WatcherManager watchers = new WatcherManager();
        final JdiHealthMonitor health = new JdiHealthMonitor();
        final LocalProjectClasspathProvider classpath = noLocalClasspath();
        final JvmDiscoveryService discovery = mock(JvmDiscoveryService.class);
        // The listener needs an evaluator before the evaluator can use the connection.
        final AtomicReference<JdiExpressionEvaluator> delegate = new AtomicReference<>();
        final JdiExpressionEvaluator lazy = mock(JdiExpressionEvaluator.class, invocation -> {
            try {
                return invocation.getMethod().invoke(delegate.get(), invocation.getArguments());
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        final EvaluationRouter router = new EvaluationRouter(lazy, EvaluationRouter.Mode.AUTO);
        final JdiEventListener listener = new JdiEventListener(tracker, history, lazy, guard, null, marks, router);
        final JDIConnectionService connection = new JDIConnectionService(listener, tracker, history, watchers, guard,
            marks, health, discovery);
        final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(new InMemoryJavaCompiler(), connection,
            guard, classpath);
        delegate.set(evaluator);
        final FastLogpointService fast = new FastLogpointService(tracker, history, guard, evaluator, listener, connection);
        final JDWPTools tools = new JDWPTools(connection, tracker, watchers, router, history, guard, discovery,
            marks, health, classpath, fast);
        try {
            assertThat(connection.connect("127.0.0.1", port)).startsWith("Connected");
            assertThat(tools.jdwp_resume_until_event(10_000)).contains("via=vm_start");
            assertThat(tools.jdwp_resume()).startsWith("Resumed:");
            final VirtualMachine vm = connection.getVM();
            final ThreadReference main = vm.allThreads().stream().filter(t -> t.name().equals("main"))
                .findFirst().orElseThrow();
            await().atMost(Duration.ofSeconds(15)).until(() -> main.status() == ThreadReference.THREAD_STATUS_WAIT
                && vm.allThreads().stream().anyMatch(t -> t.name().equals("fixture-gc")));
            assertThat(tools.jdwp_suspend_thread(main.uniqueID())).contains("suspended.");
            // Evaluate in the fixture frame beneath the frames that implement the wait.
            final List<StackFrame> frames = main.frames();
            int index = 0;
            while (!frames.get(index).location().declaringType().name().equals(INVOCATION_TARGET)) {
                index++;
            }
            final int frameIndex = index;
            final int suspendCount = main.suspendCount();
            final String control = "{ boolean copy = " + INVOCATION_PREFIX + "yes; if (copy) return 1; return 2; }";
            assertThat(tools.jdwp_evaluate_expression(main.uniqueID(), control, frameIndex)).endsWith(": 1");
            for (final InvocationRow row : INVOCATION_ROWS) {
                if (row.id().equals("block-dead-call") || row.id().equals("block-dead-boxing")) {
                    final Planner.Plan plan = router.prepare(router.parse(row.expression()), main,
                        () -> main.frame(frameIndex), Map.of());
                    assertThat(plan).isEqualTo(new Planner.Compile("constant block condition"));
                    assertThat(tools.jdwp_evaluate_expression(main.uniqueID(), row.expression(), frameIndex))
                        .contains("constant block condition");
                }
            }
            assertThat(router.diagnostics()).contains("interpreted 1, compiled 0", "with invocations 0");
            assertThat(main.suspendCount()).isEqualTo(suspendCount);
        } finally {
            connection.disconnect();
        }
    }

    /** Wakes the running GC worker and waits for it to confirm collection before forwarding the pin. */
    private static void collectBeforePin(InterpreterTargetVm target, Path gcResult) throws IOException {
        target.process().getOutputStream().write(1);
        target.process().getOutputStream().flush();
        final long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < timeout) {
            if (Files.exists(gcResult)) {
                final String outcome = Files.readString(gcResult);
                if (!outcome.isEmpty()) {
                    if (!outcome.equals("collected")) {
                        throw new IOException("GC worker retained the result");
                    }
                    return;
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        throw new IOException("Target GC worker did not finish");
    }

    /** Action measured with the fixture trace. */
    @FunctionalInterface
    private interface Evaluation {
        /** Evaluates and renders an expression. */
        String run() throws Exception;
    }

    /** Records the result and the target trace, including failures. */
    private static String observe(ClassType owner, Field trace, Field calls, Evaluation evaluation) {
        String value;
        try {
            value = evaluation.run();
        } catch (InvocationException e) {
            value = RemoteCodeExecutor.targetException(e).getMessage();
        } catch (Exception e) {
            final String message = String.valueOf(e.getMessage()).replaceAll("\\R+", " ").trim();
            value = message.startsWith("Target VM threw exception:") ? message
                : e.getClass().getSimpleName() + ": " + message;
        }
        return value + " [" + ((IntegerValue) owner.getValue(calls)).value() + " "
            + ((StringReference) owner.getValue(trace)).value() + "]";
    }

    /** Renders target strings and boxed primitive values for direct comparison. */
    private static String renderInvocationValue(@Nullable Value value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof StringReference string) {
            return string.value();
        }
        if (value instanceof ObjectReference object) {
            final Field field = object.referenceType().fieldByName("value");
            return field == null ? object.referenceType().name() : object.getValue(field).toString();
        }
        return value.toString();
    }

    /** Checks that an unloaded hiding member triggers the compiled fallback on each target JDK. */
    @Test
    void fallsBackForAnUnloadedHidingMemberType() throws Exception {
        final Entry entry = mainRow("unloaded-hiding-member", "HiddenBadge.tag");
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withFixture(javaHome, fixture -> {
                final JdiExpressionEvaluator evaluator = fixture.evaluator(new InMemoryJavaCompiler());
                final EvaluationRouter auto = new EvaluationRouter(evaluator, EvaluationRouter.Mode.AUTO);
                final EvaluationRouter compiler = new EvaluationRouter(evaluator, EvaluationRouter.Mode.COMPILER);
                return replay(fixture.vm(), List.of(entry), fixture.connection(), context -> {
                    assertThat(fixture.vm().classesByName(TARGET + "$HiddenBadge")).isEmpty();
                    assertThat(fixture.vm().classesByName(TARGET + "Base$HiddenBadge")).hasSize(1);
                    final Planner.Plan plan = auto.prepare(auto.parse(entry.expression()), context.thread(),
                        context::frame, context.bindings());
                    final Outcome result = evaluationOutcome(() -> auto.evaluateEvent(entry.expression(),
                        context.thread(), context.bindings(), context::render));
                    final Outcome expected = evaluationOutcome(() -> compiler.evaluateEvent(entry.expression(),
                        context.thread(), context.bindings(), context::render));
                    assertThat(result).as("%s, %s", javaHome.getFileName(), plan).isEqualTo(expected);
                    assertThat(plan).isEqualTo(new Planner.Compile("type is not loaded: " + TARGET + "$HiddenBadge"));
                    assertThat(golden(context).rendered()).isEqualTo("\"sub\"");
                    return result;
                }, null, new StringBuilder(), null);
            });
        }
    }

    /** Refuses to bind a base member type when the hiding qualifier is unloaded. */
    @Test
    void fallsBackForAnUnloadedHidingQualifier() throws Exception {
        final Entry entry = mainRow("unloaded-qualified-member", "Scope.Badge.class");
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withFixture(javaHome, fixture -> {
                final EvaluationRouter auto = new EvaluationRouter(fixture.evaluator(new InMemoryJavaCompiler()),
                    EvaluationRouter.Mode.AUTO);
                return replay(fixture.vm(), List.of(entry), fixture.connection(), context -> {
                    assertThat(fixture.vm().classesByName(TARGET + "$Scope")).isEmpty();
                    assertThat(fixture.vm().classesByName(TARGET + "$AlternateScope$Badge")).isEmpty();
                    assertThat(fixture.vm().classesByName(TARGET + "Base$Scope$Badge")).hasSize(1);
                    final Planner.Plan plan = auto.prepare(auto.parse(entry.expression()), context.thread(),
                        context::frame, context.bindings());
                    final Outcome result = evaluationOutcome(() -> auto.evaluateEvent(entry.expression(),
                        context.thread(), context.bindings(), context::render));
                    final InterpreterValueFacts facts = result.facts();
                    if (facts != null) {
                        assertThat(facts.reflectedType()).isNotEqualTo(TARGET + "Base$Scope$Badge");
                    }
                    assertThat(plan).as("%s", javaHome.getFileName())
                        .isEqualTo(new Planner.Compile("type is not loaded: " + TARGET + "$Scope"));
                    return result;
                }, null, new StringBuilder(), null);
            });
        }
    }

    /** Resolves loaded members through lexical or fully qualified names and defers imported simple names. */
    @Test
    void navigatesLoadedQualifiedMemberTypes() throws Exception {
        final List<Entry> entries = List.of(
            mainRow("loaded-qualified-member", "LoadedScope.Badge.class"),
            mainRow("qualified-member-cast", "(LoadedScope.Badge) LOADED_SCOPE[1]"),
            mainRow("qualified-member-field", "LoadedScope.Badge.CODE"),
            mainRow("imported-qualified-member", "Map.Entry.class"),
            mainRow("fully-qualified-member", "java.util.Map.Entry.class"));
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withFixture(javaHome, fixture -> {
                final EvaluationRouter strict = new EvaluationRouter(mock(JdiExpressionEvaluator.class),
                    EvaluationRouter.Mode.INTERPRETER);
                return replay(fixture.vm(), entries, fixture.connection(), context -> {
                    final Entry entry = context.entry();
                    assertThat(fixture.vm().classesByName(TARGET + "$LoadedScope")).hasSize(1);
                    assertThat(fixture.vm().classesByName(TARGET + "$LoadedAlternateScope$Badge")).hasSize(1);
                    assertThat(fixture.vm().classesByName(TARGET + "Base$LoadedScope$Badge")).hasSize(1);
                    final Planner.Plan plan = strict.prepare(strict.parse(entry.expression()), context.thread(),
                        context::frame, context.bindings());
                    if (entry.id().equals("imported-qualified-member")) {
                        assertThat(plan).isEqualTo(
                            new Planner.Compile("type name needs an import or the frame's package: Map"));
                        return golden(context);
                    }
                    assertThat(plan.kind()).as("%s, %s, %s", javaHome.getFileName(), entry.expression(), plan)
                        .isEqualTo(Planner.KindOfPlan.NAVIGATE);
                    final Outcome result = strict.evaluateEvent(entry.expression(), context.thread(),
                        context.bindings(), context::render);
                    assertThat(result).as("%s, %s", javaHome.getFileName(), entry.expression())
                        .isEqualTo(golden(context));
                    return result;
                }, null, new StringBuilder(), null);
            });
        }
    }

    /** Checks lexical bindings, including detached local frames, and compares navigated results with javac. */
    @Test
    void checksLexicalTypeBindings() throws Exception {
        final String pkg = TypeBindingFixture.class.getPackageName() + ".";
        final String localFrame = TypeBindingFixture.class.getName() + "$LocalFrame";
        final String detached = pkg + "DetachedOuter";
        final Map<String, List<String>> expressions = Map.of(
            pkg + "HiddenOuter$Frame", List.of("Scope.Token.class", "String.class",
                "java.lang.String.class", "java.lang.Integer.MAX_VALUE", "(java.lang.String) x"),
            pkg + "EnclosingOuter$Middle$Frame", List.of("Shared.class"),
            localFrame, List.of("Marker.class"),
            TypeBindingFixture.class.getName() + "$GenericFrame", List.of("((Marker) value).code"),
            pkg + "TypeBindingArrayFrame", List.of("Integer.class", "Integer.MAX_VALUE", "Integer[][].class"),
            pkg + "GenericOuter$1Frame", List.of("((Marker) value).code"),
            pkg + "SelfNameFrame", List.of("SelfNameFrame.class", "SelfNameFrame.CONSTANT"),
            detached + "$1Frame", List.of("((Marker) value).code", "((Marker) null).CONSTANT",
                "java.lang.Integer.MAX_VALUE"),
            detached + "$1SiblingFrame", List.of("((Marker) value).code"));
        final Set<String> navigate = Set.of("java.lang.String.class", "java.lang.Integer.MAX_VALUE",
            "(java.lang.String) x", "SelfNameFrame.class", "SelfNameFrame.CONSTANT");
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withFixture(javaHome, fixture -> {
                final VirtualMachine vm = fixture.vm();
                final EventRequestManager requests = vm.eventRequestManager();
                for (String owner : expressions.keySet()) {
                    final ClassPrepareRequest request = requests.createClassPrepareRequest();
                    request.addClassFilter(owner);
                    request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    request.enable();
                }
                final EvaluationRouter router = new EvaluationRouter(mock(JdiExpressionEvaluator.class),
                    EvaluationRouter.Mode.INTERPRETER);
                final Set<String> seen = new HashSet<>();
                final SoftAssertions checks = new SoftAssertions();
                while (seen.size() < expressions.size()) {
                    final EventSet events = vm.eventQueue().remove(15_000);
                    assertThat(events).as("next lexical-binding stop after %s", seen).isNotNull();
                    try {
                        for (Event event : events) {
                            if (event instanceof ClassPrepareEvent prepare) {
                                requests.deleteEventRequest(prepare.request());
                                final Method method = prepare.referenceType().methodsByName("inspect").get(0);
                                Location stop = method.location();
                                if (prepare.referenceType().name().equals(localFrame)) {
                                    final List<Location> lines = method.allLineLocations();
                                    stop = lines.get(lines.size() - 1);
                                }
                                final BreakpointRequest breakpoint = requests.createBreakpointRequest(stop);
                                breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                                breakpoint.enable();
                            } else if (event instanceof BreakpointEvent hit) {
                                requests.deleteEventRequest(hit.request());
                                final String owner = hit.location().declaringType().name();
                                if (owner.equals(pkg + "HiddenOuter$Frame")) {
                                    assertThat(vm.classesByName(pkg + "HiddenOuter")).isEmpty();
                                    assertThat(vm.classesByName(pkg + "HiddenOuter$Scope")).isEmpty();
                                    assertThat(vm.classesByName(pkg + "HiddenOuter$Scope$Token")).hasSize(1);
                                } else if (owner.equals(pkg + "EnclosingOuter$Middle$Frame")) {
                                    assertThat(vm.classesByName(pkg + "EnclosingOuter$Middle")).isEmpty();
                                    assertThat(vm.classesByName(pkg + "EnclosingOuter")).hasSize(1);
                                } else if (owner.equals(localFrame)) {
                                    assertThat(vm.classesByName(localFrame + "$1Marker")).hasSize(1);
                                } else if (owner.startsWith(detached + "$1")) {
                                    assertThat(vm.classesByName(detached)).as("enclosing class at %s", owner).isEmpty();
                                    assertThat(vm.classesByName(detached + "$Marker")).hasSize(1);
                                    if (owner.endsWith("SiblingFrame")) {
                                        assertThat(vm.classesByName(detached + "$1Marker")).hasSize(1);
                                    } else {
                                        assertThat(vm.classesByName(detached + "$Actual")).hasSize(1);
                                    }
                                }
                                for (String source : Objects.requireNonNull(expressions.get(owner))) {
                                    checkLexicalBinding(router, hit, source, navigate.contains(source), checks);
                                }
                                seen.add(owner);
                            } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                                throw new AssertionError("fixture exited after lexical-binding stops " + seen);
                            }
                        }
                    } finally {
                        events.resume();
                    }
                }
                checks.assertAll();
                return seen;
            });
        }
    }

    /** Checks the plan and compares a navigated value with the fixture method compiled by javac. */
    private static void checkLexicalBinding(EvaluationRouter router, BreakpointEvent hit, String source,
                                            boolean navigate, SoftAssertions checks) throws Exception {
        final ThreadReference thread = hit.thread();
        final ClassType owner = (ClassType) hit.location().declaringType();
        final Planner.Plan plan = router.prepare(router.parse(source), thread, () -> thread.frame(0), Map.of());
        final Planner.KindOfPlan expected = navigate ? Planner.KindOfPlan.NAVIGATE : Planner.KindOfPlan.COMPILE;
        checks.assertThat(plan.kind()).as("%s in %s on %s: %s", source, owner.name(),
            thread.virtualMachine().version(), plan).isEqualTo(expected);
        if (navigate && plan.kind() == Planner.KindOfPlan.NAVIGATE) {
            final InterpreterValueFacts result = router.evaluateEvent(source, thread, Map.of(),
                value -> InterpreterValueFacts.capture(value, Map.of(), false));
            final StackFrame frame = thread.frame(0);
            final Value x = source.equals("(java.lang.String) x")
                ? frame.getValue(frame.visibleVariableByName("x")) : null;
            final Value golden = owner.invokeMethod(thread, owner.methodsByName("golden").get(0),
                Arrays.asList(thread.virtualMachine().mirrorOf(source), x), ClassType.INVOKE_SINGLE_THREADED);
            checks.assertThat(result).as("%s in %s agrees with javac", source, owner.name())
                .isEqualTo(InterpreterValueFacts.capture(golden, Map.of(), false));
        }
    }

    /** Exercises the two String transport cases and both forms of type shadowing on each target JDK. */
    @Test
    void checksSurrogateTransportAndShadowedTypeNames() throws Exception {
        final List<Entry> entries = List.of(
            mainRow("mirrored-unpaired-surrogate", "\"\\uD800\".charAt(0) == (char) 55296"),
            mainRow("target-unpaired-surrogate", "this.malformed.charAt(0) == (char) 55296"),
            mainRow("classpath-import-shadow", "ProcessBuilder.class"),
            mainRow("inherited-type-shadow", "StringBuilder.class"));
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            withFixture(javaHome, fixture -> {
                final JdiExpressionEvaluator compiler = fixture.evaluator(new InMemoryJavaCompiler());
                final EvaluationRouter auto = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
                final EvaluationRouter strict = new EvaluationRouter(compiler, EvaluationRouter.Mode.INTERPRETER);
                return replay(fixture.vm(), entries, fixture.connection(), context -> {
                    final Entry entry = context.entry();
                    final Planner.Plan plan = auto.prepare(auto.parse(entry.expression()), context.thread(),
                        context::frame, context.bindings());
                    switch (entry.id()) {
                        case "mirrored-unpaired-surrogate" -> assertThat(plan)
                            .isEqualTo(new Planner.Compile("literal contains an unpaired surrogate"));
                        case "target-unpaired-surrogate" -> {
                            assertThat(plan.kind()).isEqualTo(Planner.KindOfPlan.NAVIGATE);
                            assertThatThrownBy(() -> strict.evaluateEvent(entry.expression(), context.thread(),
                                context.bindings(), context::render)).isInstanceOf(JdiEvaluationException.class)
                                .hasMessage("not interpreted: String transfer may have replaced a code unit");
                        }
                        case "classpath-import-shadow" -> {
                            assertThat(plan).isEqualTo(new Planner.Compile("ambiguous type name: ProcessBuilder"));
                            final Outcome imported = golden(context);
                            assertThat(Objects.requireNonNull(imported.facts()).reflectedType())
                                .isEqualTo("one.edee.mcp.jdwp.evaluation.fixture.shadow.ProcessBuilder");
                            return imported;
                        }
                        case "inherited-type-shadow" -> {
                            assertThat(plan.kind()).isEqualTo(Planner.KindOfPlan.NAVIGATE);
                            final Outcome result = strict.evaluateEvent(entry.expression(), context.thread(),
                                context.bindings(), context::render);
                            assertThat(result).isEqualTo(golden(context));
                            return result;
                        }
                        default -> throw new AssertionError(entry.id());
                    }
                    final Outcome result = auto.evaluateEvent(entry.expression(), context.thread(),
                        context.bindings(), context::render);
                    assertThat(result.rendered()).as(entry.id()).isEqualTo("true");
                    return result;
                }, null, new StringBuilder(), null);
            });
        }
    }

    /** Checks each expected plan and uses an isolated compiler mock for each planner-error row. */
    private static Engine routerEngine(EvaluationRouter strict, EvaluationRouter auto, Map<String, Integer> reasons) {
        return context -> {
            final Entry entry = context.entry();
            if (entry.expectedPlan() == Planner.KindOfPlan.ERROR) {
                return plannerError(context);
            }
            // Strict mode makes an accidental compiler fallback fail an interpreted row.
            final EvaluationRouter router = entry.expectedPlan() == Planner.KindOfPlan.COMPILE ? auto : strict;
            final Planner.Plan plan = router.prepare(router.parse(entry.expression()), context.thread(), context::frame,
                context.bindings());
            assertThat(plan.kind()).as("%s: %s (%s)", entry.id(), entry.expression(), plan)
                .isEqualTo(entry.expectedPlan());
            if (plan instanceof Planner.Compile fallback) {
                final int details = fallback.reason().indexOf(": ");
                final String category = details < 0 ? fallback.reason() : fallback.reason().substring(0, details);
                reasons.merge(category, 1, Integer::sum);
            }
            return evaluationOutcome(() -> router.evaluateEvent(entry.expression(), context.thread(), context.bindings(),
                context::render));
        };
    }

    /** Requires the planner's exact diagnostic and independently checks that the compiler was untouched. */
    private static Outcome plannerError(EvaluationContext context) throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter router = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final Entry entry = context.entry();
        final Planner.Plan plan = router.prepare(router.parse(entry.expression()), context.thread(), context::frame,
            context.bindings());
        assertThat(plan).as(entry.id()).isInstanceOf(Planner.Error.class);
        final String expected = ((Planner.Error) plan).message();
        final String diagnostic = switch (entry.id()) {
            case "syntax-error" -> "Syntax error:";
            case "unresolved-name" -> "Unresolved variable 'missingLocal'";
            default -> throw new AssertionError("Missing expected diagnostic for " + entry.id());
        };
        assertThat(expected).startsWith(diagnostic);
        assertThatThrownBy(() -> router.evaluateEvent(entry.expression(), context.thread(),
            context.bindings(), context::render)).isInstanceOf(JdiEvaluationException.class)
            .hasMessage(expected);
        verifyNoInteractions(compiler);
        return new Outcome("planner error", "", expected, null);
    }

    private static Path writeOracle(AttachedFixture fixture, List<Entry> entries, StringBuilder vmStart)
        throws Exception {
        final String version = fixture.vm().version();
        final int major = Integer.parseInt(version.split("\\.")[0]);
        final var golden = new LinkedHashMap<String, Observation>();
        final var observations = replay(fixture.vm(), entries, fixture.connection(),
            compiledEngine(fixture.evaluator(new InMemoryJavaCompiler())), InterpreterHarnessLiveTest::checkPrivateAccess,
            vmStart, golden);

        final var jsonl = new StringBuilder();
        for (Entry entry : entries) {
            final Observation observation = observations.get(entry.id());
            final @Nullable String skip = observation != null ? null
                : entry.stop() == null ? "no fixture stop specified" : "unknown fixture stop";
            jsonl.append(JSON.writeValueAsString(
                new Row(entry.id(), entry.stop(), observation, golden.get(entry.id()), skip))).append('\n');
        }
        return Files.writeString(REPORTS.resolve("interp-oracle-jdk" + major + ".jsonl"), jsonl);
    }

    private static Engine compiledEngine(JdiExpressionEvaluator evaluator) {
        return context -> {
            evaluator.configureCompilerClasspath(context.thread());
            return compiledResult(evaluator, context);
        };
    }

    /** Plants a VM attached through the packet-counting transport into a connection service. */
    static JDIConnectionService connectionFor(VirtualMachine vm, EvaluationGuard guard) {
        final JDIConnectionService connection = new JDIConnectionService(mock(JdiEventListener.class),
            new BreakpointTracker(), new EventHistory(), new WatcherManager(), guard,
            new MarkedInstanceRegistry(), new JdiHealthMonitor(), mock(JvmDiscoveryService.class)) {
            @Override
            public VirtualMachine getVM() {
                return vm;
            }
        };
        // Classpath discovery publishes its result only for the VM the connection holds, so the
        // fixture's VM is planted as the connection's own, as a real attach would do.
        try {
            final java.lang.reflect.Field field = JDIConnectionService.class.getDeclaredField("vm");
            field.setAccessible(true);
            field.set(connection, vm);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not plant the fixture VM into the connection", e);
        }
        return connection;
    }

    /** Replay an engine on a freshly attached fixture. Pass no pre-replay check or goldens for a candidate. */
    static Map<String, Observation> replay(VirtualMachine vm, List<Entry> entries, JDIConnectionService connection,
                                          Engine engine, @Nullable BeforeMain beforeMain, StringBuilder vmStart,
                                          @Nullable Map<String, Observation> goldens) throws Exception {
        final var marks = new MarkedInstanceRegistry();
        final var observations = new LinkedHashMap<String, Observation>();
        final var requests = vm.eventRequestManager();
        final var prepareMain = requests.createClassPrepareRequest();
        prepareMain.addClassFilter(TARGET);
        prepareMain.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        prepareMain.enable();

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
                        armInspect(requests, prepare.referenceType());
                    } else if (event instanceof BreakpointEvent breakpoint) {
                        requests.deleteEventRequest(breakpoint.request());
                        final String stop = Objects.requireNonNull(
                            INSPECT_STOPS.get(breakpoint.location().declaringType().name()));
                        final ThreadReference thread = breakpoint.thread();
                        final ClassType fixture = fixture(vm);
                        if ("main".equals(stop)) {
                            if (beforeMain != null) {
                                beforeMain.run(thread);
                            }
                            marks.mark("target", Objects.requireNonNull(thread.frame(0).thisObject()), true);
                        }
                        evaluateStop(entries, stop, thread, marks.buildBindings(), fixture, connection,
                            engine, observations, goldens);
                        // The main method preloads the other fixture classes. Arm the next stop only
                        // after all evaluations at this one return, so they can call its methods.
                        switch (stop) {
                            case "main" -> armInspect(requests, vm.classesByName(PACKAGE_STOP).get(0));
                            case "package" -> armInspect(requests, vm.classesByName(TARGET + "$Inner").get(0));
                            case "inner" -> {
                                final var exception = requests.createExceptionRequest(null, true, false);
                                exception.addClassFilter(TARGET);
                                exception.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                                exception.enable();
                            }
                            default -> throw new AssertionError(stop);
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

    private static void armInspect(EventRequestManager requests, ReferenceType type) {
        final var request = requests.createBreakpointRequest(type.methodsByName("inspect").get(0).location());
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.enable();
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

    /** Measures fresh and repeated evaluations on the same connection for each target. */
    private static String baseline(List<Path> javaHomes, Entry probe, Planner.KindOfPlan kind) throws Exception {
        final List<TargetSamples> targets = new ArrayList<>();
        // Finish warming ECJ and both target JDK images before collecting any sample.
        for (Path javaHome : javaHomes) {
            final Trial warmup = withFixture(javaHome, fixture -> measureTrial(fixture, probe, kind));
            targets.add(new TargetSamples(javaHome, warmup.version(), new ArrayList<>(), new ArrayList<>()));
        }
        for (int i = 0; i < COLD_TRIALS; i++) {
            for (TargetSamples target : targets) {
                final Trial trial = withFixture(target.javaHome(), fixture -> measureTrial(fixture, probe, kind));
                assertThat(trial.version()).isEqualTo(target.version());
                target.cold().add(trial.cold());
                target.hits().addAll(trial.hits());
            }
        }
        final String label = switch (kind) {
            case COMPILE -> "compiled";
            case NAVIGATE -> "navigate";
            case INVOKE -> "invoke";
            case ERROR -> throw new IllegalArgumentException("No benchmark for an error plan");
        };
        final StringBuilder rows = new StringBuilder();
        for (TargetSamples target : targets) {
            rows.append(InterpreterBaseline.row(target.version(), label + " first", target.cold()));
            rows.append(InterpreterBaseline.row(target.version(), label + (kind == Planner.KindOfPlan.COMPILE
                ? " hit" : " repeat"), target.hits()));
        }
        return rows.toString();
    }

    /** Stops a fresh target at the benchmark frame and captures all samples. */
    private static Trial measureTrial(AttachedFixture fixture, Entry probe, Planner.KindOfPlan kind) throws Exception {
        final InterpreterBaseline.Compiler compiler = new InterpreterBaseline.Compiler();
        final JdiExpressionEvaluator evaluator = fixture.evaluator(compiler);
        final List<InterpreterBaseline.Sample> samples = new ArrayList<>();
        replay(fixture.vm(), List.of(), fixture.connection(), compiledEngine(evaluator), thread -> {
            final EvaluationContext context = new EvaluationContext(probe, thread, Map.of(), fixture.connection(), Map.of());
            samples.addAll(kind == Planner.KindOfPlan.COMPILE
                ? measure(evaluator, compiler, context, fixture.wire())
                : measureInterpreter(evaluator, context, fixture.wire(), kind));
        }, new StringBuilder(), null);
        assertThat(compiler.compilations).isEqualTo(kind == Planner.KindOfPlan.COMPILE ? 1 : 0);
        assertThat(samples).hasSize(1 + HITS_PER_TRIAL);
        return new Trial(fixture.vm().version(), samples.get(0), List.copyOf(samples.subList(1, samples.size())));
    }

    /** Counts JDI commands through production formatting for an interpreted expression. */
    private static List<InterpreterBaseline.Sample> measureInterpreter(JdiExpressionEvaluator evaluator,
        EvaluationContext context, CountingJdwpConnection wire, Planner.KindOfPlan kind) throws Exception {
        final EvaluationRouter router = new EvaluationRouter(evaluator, EvaluationRouter.Mode.INTERPRETER);
        final List<InterpreterBaseline.Sample> samples = new ArrayList<>();
        for (int i = 0; i <= HITS_PER_TRIAL; i++) {
            final Map<CountingJdwpConnection.Command, Long> before = wire.snapshot();
            final long start = System.nanoTime();
            final String rendered = router.evaluateEvent(context.entry().expression(), context.thread(),
                context.bindings(), context.connection()::formatFieldValue);
            final long nanos = System.nanoTime() - start;
            final InterpreterBaseline.Sample sample =
                new InterpreterBaseline.Sample(before, before, wire.snapshot(), 0, 0, nanos, 0);
            if (kind == Planner.KindOfPlan.INVOKE) {
                sample.verifySingleInvocation();
                assertThat(rendered).isEqualTo("1");
            } else {
                sample.verifyNavigation();
                assertThat(rendered).isEqualTo("3");
            }
            samples.add(sample);
        }
        return samples;
    }

    private static List<InterpreterBaseline.Sample> measure(JdiExpressionEvaluator evaluator,
                                                           InterpreterBaseline.Compiler compiler,
                                                           EvaluationContext context, CountingJdwpConnection wire)
        throws Exception {
        assertThat(context.connection().getDiscoveredJdkPath()).isNull();
        final VirtualMachine vm = context.thread().virtualMachine();
        final var beforeMirrors = wire.snapshot();
        for (int i = 0; i < 256; i++) {
            vm.mirrorOf((byte) i);
        }
        assertThat(wire.snapshot()).as("primitive byte mirrors are host-side objects").isEqualTo(beforeMirrors);

        final var samples = new ArrayList<InterpreterBaseline.Sample>();
        for (int i = 0; i <= HITS_PER_TRIAL; i++) {
            final boolean cold = i == 0;
            final var before = wire.snapshot();
            final int compilations = compiler.compilations;
            final int bytes = compiler.bytes;
            final long compileNanos = compiler.nanos;
            final long start = System.nanoTime();
            evaluator.configureCompilerClasspath(context.thread());
            final long classpathNanos = System.nanoTime() - start;
            final var configured = wire.snapshot();
            final String rendered = context.connection().formatFieldValue(
                evaluator.evaluate(context.frame(), context.entry().expression(), context.bindings()));
            final long totalNanos = System.nanoTime() - start;
            final var sample = new InterpreterBaseline.Sample(before, configured, wire.snapshot(),
                classpathNanos, compiler.nanos - compileNanos, totalNanos, compiler.bytes - bytes);
            assertThat(rendered).isEqualTo("3");
            assertThat(compiler.compilations - compilations).isEqualTo(cold ? 1 : 0);
            sample.verify(cold);
            samples.add(sample);
        }
        return samples;
    }

    /** Executes the compiled engine and records its formatted outcome. */
    private static Outcome compiledResult(JdiExpressionEvaluator evaluator, EvaluationContext context)
        throws Exception {
        return evaluationOutcome(() -> context.render(
            evaluator.evaluate(context.frame(), context.entry().expression(), context.bindings())));
    }

    /** Normalizes target exceptions and compiled diagnostics for the differential oracle. */
    private static Outcome evaluationOutcome(java.util.concurrent.Callable<Outcome> evaluate) throws Exception {
        try {
            return evaluate.call();
        } catch (Interpreter.RaisedException e) {
            return Outcome.targetException(e.getCause().getClass().getName());
        } catch (JdiEvaluationException e) {
            if (e.getMessage().startsWith("Target VM threw exception:")) {
                for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                    if (cause instanceof InvocationException invoked) {
                        final String type = invoked.exception().referenceType().name();
                        // Host-raised exceptions have their own text; the call fixture compares target text verbatim.
                        return type.equals("java.lang.IllegalStateException")
                            ? Outcome.targetException(type, e.getMessage()) : Outcome.targetException(type);
                    }
                }
            }
            if (e.getMessage().startsWith("Compilation failed:")) {
                // A compile error that more classpath entries might fix is a JdiEvaluationException
                // subclass the evaluator uses internally; the oracle records the public type.
                final String name = JdiEvaluationException.class.getSimpleName();
                return new Outcome("compile error", name, "compile error: " + name, null);
            }
            throw e;
        }
    }

    private static void evaluateStop(List<Entry> entries, String stop, ThreadReference thread,
                                     Map<String, Value> bindings, ClassType fixture, JDIConnectionService connection,
                                     Engine engine, Map<String, Observation> observations,
                                     @Nullable Map<String, Observation> goldens) throws Exception {
        final var stopEntries = entries.stream().filter(entry -> stop.equals(entry.stop())).toList();
        if (stopEntries.isEmpty()) {
            return;
        }
        final var fixtureReferences = fixtureReferences(thread.frame(0), bindings);
        for (Entry entry : stopEntries) {
            final var context = new EvaluationContext(entry, thread, bindings, connection, fixtureReferences);
            final Observation result = observe(context, fixture, engine);
            observations.put(entry.id(), result);
            if (goldens != null) {
                final String expectedClass = entry.expectedClass();
                if (expectedClass != null) {
                    assertThat(result.outcome().expectedClass()).as(entry.id()).isEqualTo(expectedClass);
                }
                verifyFixtureSemantics(entry.id(), result);
                if (FIXTURE_GOLDENS.contains(entry.id())) {
                    final Observation golden = observe(context, fixture, InterpreterHarnessLiveTest::golden);
                    assertThat(golden.outcome().kind()).as(entry.id()).isEqualTo("value");
                    goldens.put(entry.id(), golden);
                }
            }
        }
    }

    private static Map<String, ObjectReference> fixtureReferences(StackFrame frame, Map<String, Value> bindings)
        throws AbsentInformationException {
        final var references = new HashMap<String, ObjectReference>();
        bindings.forEach((name, value) -> {
            if (value instanceof ObjectReference object) {
                references.put(name, object);
            }
        });
        final @Nullable ObjectReference receiver = frame.thisObject();
        if (receiver != null) {
            references.put("this", receiver);
        }
        if (bindings.get("$target") instanceof ObjectReference target) {
            final var store = (ObjectReference) target.getValue(target.referenceType().fieldByName("store"));
            final var map = (ObjectReference) store.getValue(store.referenceType().fieldByName("map"));
            references.put("$target.store.map", map);
        }
        frame.getValues(frame.visibleVariables()).forEach((local, value) -> {
            if (value instanceof ObjectReference object) {
                references.put(local.name(), object);
            }
        });
        return Map.copyOf(references);
    }

    static Observation observe(EvaluationContext context, ClassType fixture, Engine engine) throws Exception {
        final var vm = fixture.virtualMachine();
        final Field calls = fixture.fieldByName("calls");
        final Field trace = fixture.fieldByName("trace");
        fixture.setValue(calls, vm.mirrorOf(0));
        fixture.setValue(trace, vm.mirrorOf(""));
        final Outcome outcome = engine.evaluate(context);
        final boolean coldInitialized = vm.classesByName(COLD_CLASS).stream().anyMatch(ReferenceType::isInitialized);
        return new Observation(outcome, new Calls(((IntegerValue) fixture.getValue(calls)).value(),
            ((StringReference) fixture.getValue(trace)).value()), Map.of(COLD_CLASS, coldInitialized));
    }

    /** Checks concrete call traces and values in addition to comparing both engines. */
    private static void verifyFixtureSemantics(String id, Observation observation) {
        switch (id) {
            case "short-circuit-and", "short-circuit-or" ->
                assertThat(observation.calls()).as(id).isEqualTo(new Calls(0, ""));
            case "target-exception" -> {
                assertThat(observation.outcome().rendered()).isEqualTo("Target VM threw exception: java.lang.IllegalStateException");
                assertThat(observation.calls()).isEqualTo(new Calls(1, "E"));
            }
            case "boxing-cache-hit", "boxing-cache-miss" -> {
                assertThat(observation.outcome().rendered()).isEqualTo(Boolean.toString(id.equals("boxing-cache-hit")));
                assertThat(observation.calls()).isEqualTo(new Calls(2, "BB"));
            }
            case "object-null-tostring" -> {
                assertThat(observation.outcome().rendered()).isEqualTo("\"null\"");
                assertThat(observation.calls()).isEqualTo(new Calls(1, "N"));
            }
            case "single-constructor" -> assertThat(observation.calls()).isEqualTo(new Calls(1, "C"));
            case "concat-evaluation-order" -> assertThat(observation.calls()).isEqualTo(new Calls(2, "NS"));
            case "lazy-ternary" -> assertThat(observation.calls()).isEqualTo(new Calls(1, "T"));
            case "receiver-and-arguments" -> assertThat(observation.calls()).isEqualTo(new Calls(4, "RABM"));
            case "call-future-stop" -> {
                assertThat(observation.outcome().rendered()).isEqualTo("1");
                assertThat(observation.calls()).isEqualTo(new Calls(1, "I"));
            }
            case "block-call-order" -> {
                assertThat(observation.outcome().rendered()).isEqualTo("5");
                assertThat(observation.calls()).isEqualTo(new Calls(4, "ABCR"));
            }
            case "block-skipped-call" -> {
                assertThat(observation.outcome().rendered()).isEqualTo("2");
                assertThat(observation.calls()).isEqualTo(new Calls(0, ""));
            }
            case "block-assign-frame-local" -> assertThat(observation.outcome().rendered()).isEqualTo("7");
            case "block-keeps-frame-local" -> assertThat(observation.outcome().rendered()).isEqualTo("2");
            case "cold-static-counter" -> assertThat(observation.outcome().rendered()).isEqualTo("1");
            case "field-values" -> assertThat(observation.outcome().rendered()).isEqualTo("16");
            case "field-metadata" -> assertThat(observation.outcome().rendered()).isEqualTo("\"hidden:modification\"");
            case "cold-constant", "cold-static" ->
                assertThat(observation.initializedClasses()).containsEntry(COLD_CLASS, id.equals("cold-static"));
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
        final boolean outer = "outer-this".equals(id) || "outer-implicit".equals(id);
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
