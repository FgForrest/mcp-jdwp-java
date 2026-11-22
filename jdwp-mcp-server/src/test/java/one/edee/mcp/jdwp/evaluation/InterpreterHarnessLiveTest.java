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
import one.edee.mcp.jdwp.evaluation.fixture.TypeBindingFixture;
import one.edee.mcp.jdwp.evaluation.interpret.EvaluationRouter;
import one.edee.mcp.jdwp.evaluation.interpret.Interpreter;
import one.edee.mcp.jdwp.evaluation.interpret.Planner;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.assertj.core.api.SoftAssertions;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final Set<String> FIXTURE_GOLDENS = Set.of("private-field", "private-map", "private-map-call",
        "private-session", "private-method", "super-call", "super-field", "erased-field-navigation", "generic-receiver", "outer-this", "outer-implicit", "nested-class-literal", "exception-detail");
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

    record Outcome(String kind, String javaClass, String rendered, @Nullable InterpreterValueFacts facts) {
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

    private static <T> T withFixture(Path javaHome, FixtureAction<T> action) throws Exception {
        try (var target = InterpreterTargetVm.start(javaHome);
             var wire = CountingJdwpConnection.attach(target.port())) {
            final var vm = Bootstrap.virtualMachineManager().createVirtualMachine(wire);
            try {
                final var guard = new EvaluationGuard();
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

    @Test
    void recordsCompiledOracleAndBaseline() throws Exception {
        Files.createDirectories(REPORTS);
        final var vmStart = new StringBuilder();
        final List<Entry> synthetic;
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("interpreter-corpus.jsonl")),
                StandardCharsets.UTF_8)) {
            synthetic = InterpreterCorpus.read(reader, "interpreter-corpus.jsonl");
        }
        assertThat(synthetic).allSatisfy(entry -> {
            assertThat(entry.stop()).isIn(STOPS);
            assertThat(entry.expectedPlan()).as(entry.id()).isIn(Planner.KindOfPlan.NAVIGATE,
                Planner.KindOfPlan.COMPILE, Planner.KindOfPlan.ERROR);
        });

        final var entries = new ArrayList<>(synthetic);
        final String external = System.getProperty("jdwp.live.corpus");
        if (external != null) {
            try (Reader reader = Files.newBufferedReader(Path.of(external))) {
                entries.addAll(InterpreterCorpus.read(reader, external));
            }
            assertThat(entries).extracting(Entry::id).doesNotHaveDuplicates();
        }

        final List<Path> javaHomes = InterpreterTargetVm.javaHomes();
        final Entry probe = synthetic.stream().filter(e -> BASELINE_ID.equals(e.id())).findFirst().orElseThrow();
        final String baselineRows = baseline(javaHomes, probe, false);
        final String navigationRows = baseline(javaHomes, probe, true);
        for (Path javaHome : javaHomes) {
            final Path report = withFixture(javaHome, fixture -> writeOracle(fixture, entries, vmStart));
            assertThat(report).isRegularFile();
        }
        InterpreterBaseline.write(REPORTS.resolve("interp-baseline.md"), baselineRows + navigationRows, vmStart.toString());
    }

    @Test
    void comparesTheRouterWithTheCompiledAndGoldenObservations() throws Exception {
        final List<Entry> entries;
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("interpreter-corpus.jsonl")),
                StandardCharsets.UTF_8)) {
            entries = InterpreterCorpus.read(reader, "interpreter-corpus.jsonl");
        }
        for (Path javaHome : InterpreterTargetVm.javaHomes()) {
            final var goldens = new LinkedHashMap<String, Observation>();
            final var oracle = withFixture(javaHome, fixture -> replay(fixture.vm(), entries, fixture.connection(),
                compiledEngine(fixture.evaluator(new InMemoryJavaCompiler())), null, new StringBuilder(), goldens));
            final var candidate = withFixture(javaHome, fixture -> {
                final var compiler = fixture.evaluator(new InMemoryJavaCompiler());
                final var strict = new EvaluationRouter(compiler, EvaluationRouter.Mode.INTERPRETER);
                final var auto = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
                return replay(fixture.vm(), entries, fixture.connection(), routerEngine(strict, auto),
                    null, new StringBuilder(), null);
            });
            for (Entry entry : entries) {
                final Observation result = candidate.get(entry.id());
                if (entry.expectedPlan() == Planner.KindOfPlan.ERROR) {
                    assertThat(result.outcome().kind()).as("%s: %s", javaHome, entry.id()).isEqualTo("planner error");
                } else {
                    final Observation expected = entry.expectedPlan() == Planner.KindOfPlan.NAVIGATE
                        ? goldens.getOrDefault(entry.id(), oracle.get(entry.id())) : oracle.get(entry.id());
                    assertThat(result).as("%s: %s", javaHome, entry.id()).isEqualTo(expected);
                }
            }
            System.out.printf("Differential %s: %s%n", javaHome.getFileName(),
                entries.stream().collect(java.util.stream.Collectors.groupingBy(Entry::expectedPlan,
                    java.util.TreeMap::new, java.util.stream.Collectors.counting())));
        }
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

    /** Checks lexical bindings and fallback plans, comparing navigated results with javac. */
    @Test
    void checksLexicalTypeBindings() throws Exception {
        final String pkg = TypeBindingFixture.class.getPackageName() + ".";
        final String localFrame = TypeBindingFixture.class.getName() + "$LocalFrame";
        final Map<String, List<String>> expressions = Map.of(
            pkg + "HiddenOuter$Frame", List.of("Scope.Token.class", "String.class",
                "java.lang.String.class", "java.lang.Integer.MAX_VALUE", "(java.lang.String) x"),
            pkg + "EnclosingOuter$Middle$Frame", List.of("Shared.class"),
            localFrame, List.of("Marker.class"),
            TypeBindingFixture.class.getName() + "$GenericFrame", List.of("((Marker) value).code"),
            pkg + "TypeBindingArrayFrame", List.of("Integer.class", "Integer.MAX_VALUE", "Integer[][].class"),
            pkg + "GenericOuter$1Frame", List.of("((Marker) value).code"),
            pkg + "SelfNameFrame", List.of("SelfNameFrame.class", "SelfNameFrame.CONSTANT"));
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
    private static Engine routerEngine(EvaluationRouter strict, EvaluationRouter auto) {
        return context -> {
            final Entry entry = context.entry();
            if (entry.expectedPlan() == Planner.KindOfPlan.ERROR) {
                return plannerError(context);
            }
            final EvaluationRouter router = entry.expectedPlan() == Planner.KindOfPlan.COMPILE ? auto : strict;
            final Planner.Plan plan = router.prepare(router.parse(entry.expression()), context.thread(), context::frame,
                context.bindings());
            assertThat(plan.kind()).as("%s: %s (%s)", entry.id(), entry.expression(), plan)
                .isEqualTo(entry.expectedPlan());
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

    private static JDIConnectionService connectionFor(VirtualMachine vm, EvaluationGuard guard) {
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

    private static String baseline(List<Path> javaHomes, Entry probe, boolean navigate) throws Exception {
        final var targets = new ArrayList<TargetSamples>();
        // Finish warming ECJ and both target JDK images before collecting any sample.
        for (Path javaHome : javaHomes) {
            final Trial warmup = withFixture(javaHome, fixture -> measureTrial(fixture, probe, navigate));
            targets.add(new TargetSamples(javaHome, warmup.version(), new ArrayList<>(), new ArrayList<>()));
        }
        for (int i = 0; i < COLD_TRIALS; i++) {
            for (TargetSamples target : targets) {
                final Trial trial = withFixture(target.javaHome(), fixture -> measureTrial(fixture, probe, navigate));
                assertThat(trial.version()).isEqualTo(target.version());
                target.cold().add(trial.cold());
                target.hits().addAll(trial.hits());
            }
        }
        final var rows = new StringBuilder();
        for (TargetSamples target : targets) {
            rows.append(InterpreterBaseline.row(target.version(), navigate ? "navigate first" : "compiled first",
                target.cold()));
            rows.append(InterpreterBaseline.row(target.version(), navigate ? "navigate repeat" : "compiled hit",
                target.hits()));
        }
        return rows.toString();
    }

    private static Trial measureTrial(AttachedFixture fixture, Entry probe, boolean navigate) throws Exception {
        final var compiler = new InterpreterBaseline.Compiler();
        final var evaluator = fixture.evaluator(compiler);
        final var samples = new ArrayList<InterpreterBaseline.Sample>();
        replay(fixture.vm(), List.of(), fixture.connection(), compiledEngine(evaluator), thread -> {
            final var context = new EvaluationContext(probe, thread, Map.of(), fixture.connection(), Map.of());
            samples.addAll(navigate ? measureNavigation(evaluator, context, fixture.wire())
                : measure(evaluator, compiler, context, fixture.wire()));
        }, new StringBuilder(), null);
        assertThat(samples).hasSize(1 + HITS_PER_TRIAL);
        return new Trial(fixture.vm().version(), samples.get(0), List.copyOf(samples.subList(1, samples.size())));
    }

    private static List<InterpreterBaseline.Sample> measureNavigation(JdiExpressionEvaluator evaluator,
                                                                      EvaluationContext context,
                                                                      CountingJdwpConnection wire) throws Exception {
        final var router = new EvaluationRouter(evaluator, EvaluationRouter.Mode.INTERPRETER);
        final var samples = new ArrayList<InterpreterBaseline.Sample>();
        for (int i = 0; i <= HITS_PER_TRIAL; i++) {
            final var before = wire.snapshot();
            final long start = System.nanoTime();
            final String rendered = router.evaluateEvent(context.entry().expression(), context.thread(),
                context.bindings(), context.connection()::formatFieldValue);
            final long nanos = System.nanoTime() - start;
            final var sample = new InterpreterBaseline.Sample(before, before, wire.snapshot(), 0, 0, nanos, 0);
            sample.verifyNavigation();
            assertThat(rendered).isEqualTo("3");
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
                        return Outcome.targetException(invoked.exception().referenceType().name());
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

    private static void verifyFixtureSemantics(String id, Observation observation) {
        switch (id) {
            case "short-circuit-and", "short-circuit-or" ->
                assertThat(observation.calls()).as(id).isEqualTo(new Calls(0, ""));
            case "lazy-ternary" -> assertThat(observation.calls()).isEqualTo(new Calls(1, "T"));
            case "receiver-and-arguments" -> assertThat(observation.calls()).isEqualTo(new Calls(4, "RABM"));
            case "call-future-stop" -> {
                assertThat(observation.outcome().rendered()).isEqualTo("1");
                assertThat(observation.calls()).isEqualTo(new Calls(1, "I"));
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
