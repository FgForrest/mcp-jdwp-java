package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.evaluation.fixture.PrivateMembers;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Exercises ECJ's diagnostic spans and the residual Java compiled after each rewrite. */
class MemberAccessRewriteTest {
    private static final String NAME = "wrapper.Probe";
    private static final String OWNER = PrivateMembers.class.getName();
    private static final InMemoryJavaCompiler.Configuration CONFIG =
        new InMemoryJavaCompiler.Configuration(System.getProperty("java.home"), "target/test-classes", 17);

    /** Checks the exact span replaced and leaves nearby source, including comments, intact. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        receiver /* before */ . amount | ((int) __mcpGet(receiver, "OWNER", "amount", "Cannot read field \\"amount\\" because \\"receiver\\" is null", 0))
        (receiver.amount) | (((int) __mcpGet(receiver, "OWNER", "amount", "Cannot read field \\"amount\\" because \\"receiver\\" is null", 0)))
        OWNER.TOTAL | ((int) 12)
        receiver.select("abc") | ((int) __mcpInvoke(receiver, "OWNER", "select", new java.lang.String[]{"java.lang.String"}, new java.lang.Object[]{(java.lang.String) ("abc")}, "Cannot invoke \\"OWNER.select(String)\\" because \\"receiver\\" is null", 0))
        receiver.select(3) | ((int) __mcpInvoke(receiver, "OWNER", "select", new java.lang.String[]{"long"}, new java.lang.Object[]{(long) (3)}, "Cannot invoke \\"OWNER.select(long)\\" because \\"receiver\\" is null", 0))
        new OWNER(5) | ((OWNER) __mcpConstruct(__mcpInitialize("OWNER"), new java.lang.String[]{"int"}, new java.lang.Object[]{(int) (5)}))
        receiver.next.amount | ((OWNER) __mcpGet(receiver, "OWNER", "next", "Cannot read field \\"next\\" because \\"receiver\\" is null", 0)).amount
        """)
    void rewritesOnlyTheFailingAccess(String expression, String expected, @TempDir Path directory) throws Exception {
        final String original = source("return /* keep */ " + owners(expression) + ";");
        final Path file = Files.writeString(directory.resolve("Probe.java"), original);
        final String rewritten = MemberAccessRewrite.rewrite(NAME, file, original, CONFIG, true, new ArrayList<>());
        assertThat(rewritten).contains("    return /* keep */ " + owners(expected) + ";\n");
    }

    /** A discarded result is a helper invocation statement without a cast or typed bridge. */
    @Test
    void discardsInvocationResultsWithoutACast(@TempDir Path directory) throws Exception {
        final String original = source(owners("receiver.select(3); new OWNER(5); return null;"));
        final Path file = Files.writeString(directory.resolve("Probe.java"), original);
        final String rewritten = MemberAccessRewrite.rewrite(NAME, file, original, CONFIG, true, new ArrayList<>());
        assertThat(rewritten)
            .contains("    __mcpInvoke(receiver,", "; __mcpConstruct(__mcpInitialize(")
            .doesNotContain("__mcpBridge");
    }

    /** Compiles successive accesses and terminates with the first diagnostics if the budget is exhausted. */
    @Test
    void boundsTheNumberOfRetries() throws Exception {
        final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler();
        assertThat(InMemoryJavaCompiler.compileEvaluation(compiler, NAME, source("return receiver.next.next.amount;"), CONFIG)
            .classes()).hasSize(1);
        final String longChain = source("return receiver" + ".next".repeat(8) + ".amount;");
        assertOriginalFailure(compiler, longChain);
    }

    /** Keeps all diagnostic text when an independent error prevents the retry from succeeding. */
    @Test
    void preservesOriginalDiagnosticsForNonAccessErrors() {
        final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler();
        assertOriginalFailure(compiler, source("int broken = \"text\"; return receiver.amount;"));
        assertOriginalFailure(compiler, source("return receiver.next.missing;"));
        assertOriginalFailure(compiler, source("return receiver.fail();"));
        assertOriginalFailure(compiler, source("return receiver.select(1, 2);"));
    }

    /** A private field on an assignment's left side retains the ordinary diagnostic. */
    @Test
    void preservesOriginalDiagnosticsForFieldWrites() {
        assertOriginalFailure(new InMemoryJavaCompiler(), source("receiver.amount = 4; return null;"));
    }

    /** A fresh loader gives each javac or ECJ run its own class-initialization state and trace. */
    private record Observation(String outcome, String calls) {}

    /** Runs one comparison against a fixture type from the current isolated loader. */
    @FunctionalInterface
    private interface Evaluation {
        Object evaluate(Class<?> fixture) throws Throwable;
    }

    /** Compares successful rewrites with javac and refused rewrites with the original ECJ diagnostics. */
    @Test
    void preservesJavaSemanticsOrOriginalDiagnostics() {
        final String fixtureName = PrivateMembers.Semantics.class.getCanonicalName();
        final Set<String> refused = Set.of("widened-field", "widened-method", "widened-array",
            "widened-generic-field", "hidden-method", "both-hidden", "write", "compound-write", "qualified-write",
            "increment", "decrement", "array-write", "accessible-write", "expression-constant", "instance-constant");
        final Map<String, String> cases = Map.ofEntries(
            Map.entry("widened-field", "F.choose(x.hidden)"),
            Map.entry("widened-method", "F.choose(x.privateHidden())"),
            Map.entry("widened-array", "F.choose(x.hiddenArray[0])"),
            Map.entry("widened-generic-field", "F.choose(x.hiddenList.get(0))"),
            Map.entry("hidden-method", "x.hidden.method()"),
            Map.entry("both-hidden", "x.both(x.privateHidden())"),
            Map.entry("cold-constant", "return F.Cold.CONSTANT;"),
            Map.entry("string-constant", "F.Cold.TEXT"),
            Map.entry("char-constant", "F.Cold.CHARACTER"),
            Map.entry("zero-constant", "Double.doubleToRawLongBits(F.Cold.NEGATIVE_ZERO)"),
            Map.entry("infinite-constant", "F.Cold.INFINITY"),
            Map.entry("instance-constant", "new F.Child().shadowed"),
            Map.entry("hidden-parameter", "x.both(x.publicHidden())"),
            Map.entry("constructor-order", "new F.ColdConstruct(x.argument(\"argument\")); return 9;"),
            Map.entry("write", "(x.child).shared = 99; return x.written();"),
            Map.entry("compound-write", "(x.child).shared += 5; return x.written();"),
            Map.entry("increment", "++(x.child).shared; return x.written();"),
            Map.entry("decrement", "(x.child).shared--; return x.written();"),
            Map.entry("accessible-write", "(x.visibleChild).shared = 99; return 0;"),
            Map.entry("array-write", "x.hiddenArray[0] = null; return 0;"),
            Map.entry("expression-constant", "x.coldReceiver().CONSTANT"),
            Map.entry("null-local", "final F receiver = x.nullReceiver(); return receiver.value;"),
            Map.entry("null-static-field", "F.staticNull.value"),
            Map.entry("null-private-static-field", "F.privateStaticNull.value"),
            Map.entry("null-private-instance-field", "x.privateInstanceNull.value"),
            Map.entry("null-private-method-result", "x.privateNullReceiver().value"),
            Map.entry("null-instance-field", "x.instanceNull.value"),
            Map.entry("null-parameter", "x.value"),
            Map.entry("qualified-write", "x.child.shared = 99; return x.written();"),
            Map.entry("non-private", "x.get(\"visible\").argument(\"after\")"),
            Map.entry("null-method", "x.nullReceiver().order(x.argument(\"first\"), x.argument(\"second\"))"),
            Map.entry("null-field", "x.nullReceiver().value"),
            Map.entry("null-caught", "try { return x.nullReceiver().order(x.argument(\"first\"), x.argument(\"second\")); } "
                + "catch (NullPointerException expected) { F.mark(\"catch(NullPointerException)\"); return expected.getMessage(); } "),
            Map.entry("bad-receiver", "x.badReceiver().order(x.argument(\"first\"), x.argument(\"second\"))"),
            Map.entry("bad-argument", "x.get(\"receiver\").order(x.badArgument(), x.argument(\"second\"))"),
            Map.entry("throws", "x.explode()"),
            Map.entry("box-reference", "x.select(Integer.valueOf(4))"),
            Map.entry("box-primitive", "x.select(4)"),
            Map.entry("specific", "x.select(\"s\")"),
            Map.entry("static-type", "F.kind(x.argument(\"argument\"))"),
            Map.entry("static-null-qualifier", "x.nullReceiver().kind(x.argument(\"argument\"))"),
            Map.entry("instance-overload", "x.get(\"receiver\").kind(\"string\")"),
            Map.entry("varargs", "x.varargs(\"a\", \"b\")"),
            Map.entry("generic", "x.generic(\"g\").length()"),
            Map.entry("shadow", "((F.Base) new F.Child()).shadowed"),
            Map.entry("bridge", "new F.Child().bridge()"),
            Map.entry("interface", "((F.Face) new F.Face() {}).own()"),
            Map.entry("lazy", "false && x.get(\"receiver\").value > 0"),
            Map.entry("nested-lambda", "((java.util.function.Supplier<java.util.function.Supplier<Integer>>) () -> () -> x.get(\"receiver\").select(\"s\")).get().get()"),
            Map.entry("comments-unicode-text-block", "x /* preserve */ . s" + "\\u0065" + "lect(\"\"\"\ns\n\"\"\")")
        );
        final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler();
        final SoftAssertions assertions = new SoftAssertions();
        for (final Map.Entry<String, String> entry : new TreeMap<>(cases).entrySet()) {
            final String id = entry.getKey();
            final String expression = entry.getValue().replaceAll("\\bF\\b", fixtureName);
            final String body = expression.endsWith(";") || expression.startsWith("try {")
                ? expression : "return " + expression + ";";
            final String source = "package wrapper; public class Probe { public static Object evaluate("
                + fixtureName + " x) throws Throwable {\n" + body + "\n}}";
            final Observation actual = observe(type -> run(
                InMemoryJavaCompiler.compileEvaluation(compiler, NAME, source, CONFIG).classes(), type, id));
            if (refused.contains(id)) {
                final Observation direct = observe(type -> run(compiler.compile(NAME, source, CONFIG), type, id));
                assertions.assertThat(direct.outcome()).as(id).startsWith("Compilation failed:");
                assertions.assertThat(actual).as(id).isEqualTo(direct);
            } else {
                final Observation golden = observe(type -> MethodHandles.publicLookup().findStatic(type, "golden",
                        MethodType.methodType(Object.class, String.class, type))
                    .invokeWithArguments(id, type.getConstructor().newInstance()));
                assertions.assertThat(actual).as(id).isEqualTo(golden);
            }
        }
        assertions.assertAll();
    }

    /** Compares assignment, generic-result, and receiver-site cases against javac or the original diagnostics. */
    @Test
    void preservesErasedDiscardsAndReceiverSites() {
        final Class<?> fixture = PrivateMembers.AccessSites.class;
        final Map<String, String> cases = new TreeMap<>();
        cases.put("array-target", "x.array[0] = x.argument(); return x.array[0];");
        cases.put("index-target", "int[] a = {7}; a[x.index] = x.argument(); return a[0];");
        cases.put("nested-target", "((x.array[0]))++; return x.array[0];");
        cases.put("generic", "return F.pick(x.generic());");
        cases.put("array", "return F.pick(x.array);");
        cases.put("primitive", "return F.pick(x.primitive);");
        cases.put("boxed", "return F.pick(x.boxed);");
        cases.put("cold-hidden", "return F.HiddenCold.CONSTANT;");
        cases.put("constant-expression", "return F.pick(F.Cold.CONSTANT + 1);");
        cases.put("string", "return F.constantIdentity(F.Cold.STRING + \"!\", \"string!\");");
        cases.put("constructor", "new F.Outer(new F.Inner(x.argument())); return \"ok\";");
        cases.put("chained-null", "return x.chain().absent().value;");
        cases.put("array-null", "return x.elements()[0].value;");
        cases.put("discard-statement", "x.genericChild.retrieve(); return \"ok\";");
        cases.put("discard-lambda", "Runnable call = () -> x.genericChild.retrieve(); call.run(); return \"ok\";");
        cases.put("discard-argument", "F.consume(() -> x.genericChild.retrieve()); return \"ok\";");
        cases.put("discard-cast", "((Runnable) () -> x.genericChild.retrieve()).run(); return \"ok\";");
        cases.put("ambiguous-lambda", "return F.choose(() -> x.genericChild.retrieve());");
        cases.put("method-description", "try { if (F.takeFirst()) return F.A.noReceiver().value; "
            + "return F.B.noReceiver().value; } catch (NullPointerException e) { F.mark(\"getMessage\"); return e.getMessage(); }");
        cases.put("field-description", "try { if (F.takeFirst()) return F.A.empty.value; "
            + "return F.B.empty.value; } catch (NullPointerException e) { F.mark(\"getMessage\"); return e.getMessage(); }");
        final Set<String> refused = Set.of("array-target", "index-target", "nested-target", "cold-hidden",
            "ambiguous-lambda");
        final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler();
        final SoftAssertions assertions = new SoftAssertions();
        for (final Map.Entry<String, String> row : cases.entrySet()) {
            final String id = row.getKey();
            final String body = row.getValue().replaceAll("\\bF\\b", fixture.getCanonicalName());
            final String wrapper = "package wrapper; public class Probe { public static Object evaluate("
                + fixture.getCanonicalName() + " x) throws Throwable {\n" + body + "\n}}";
            final Observation actual = observe(fixture, type -> run(
                InMemoryJavaCompiler.compileEvaluation(compiler, NAME, wrapper, CONFIG).classes(), type, id));
            if (refused.contains(id)) {
                final Observation direct = observe(fixture, type -> run(compiler.compile(NAME, wrapper, CONFIG), type, id));
                assertions.assertThat(direct.outcome()).as(id).startsWith("Compilation failed:");
                assertions.assertThat(actual).as(id).isEqualTo(direct);
            } else if ("array-null".equals(id)) {
                assertions.assertThat(actual).as(id).isEqualTo(new Observation(
                    "java.lang.NullPointerException: Cannot read field \"value\" because the receiver is null", "elements"));
            } else {
                final Observation golden = observe(fixture, type -> MethodHandles.publicLookup().findStatic(type, "golden",
                        MethodType.methodType(Object.class, String.class, type))
                    .invokeWithArguments(id, type.getConstructor().newInstance()));
                assertions.assertThat(actual).as(id).isEqualTo(golden);
            }
        }
        assertions.assertAll();
    }

    /** Loads the generated classes and calls them without wrapping application exceptions. */
    private static Object run(Map<String, byte[]> classes, Class<?> fixture, String id) throws Throwable {
        final ClassLoader loader = new ClassLoader(fixture.getClassLoader()) {
            /** Defines a class file from the ECJ result, using the binary name from its header. */
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                for (final byte[] bytes : classes.values()) {
                    if (new ClassReader(bytes).getClassName().replace('/', '.').equals(name)) {
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                }
                throw new ClassNotFoundException(name);
            }
        };
        final Object receiver = "null-parameter".equals(id) ? null : fixture.getConstructor().newInstance();
        return MethodHandles.publicLookup().findStatic(loader.loadClass(NAME), "evaluate",
            MethodType.methodType(Object.class, fixture)).invokeWithArguments(receiver);
    }

    /** Captures Java exceptions and ECJ errors together with side effects in the same loader. */
    private static Observation observe(Evaluation evaluation) {
        return observe(PrivateMembers.Semantics.class, evaluation);
    }

    /** Isolates initialization effects of the selected javac fixture. */
    private static Observation observe(Class<?> fixtureClass, Evaluation evaluation) {
        final URL fixtureLocation = fixtureClass.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{fixtureLocation}, ClassLoader.getPlatformClassLoader())) {
            final Class<?> fixture = loader.loadClass(fixtureClass.getName());
            final java.lang.reflect.Method trace = fixture.getMethod("trace");
            try {
                return new Observation(String.valueOf(evaluation.evaluate(fixture)), trace.invoke(null).toString().strip());
            } catch (JdiEvaluationException failure) {
                return new Observation(failure.getMessage(), trace.invoke(null).toString().strip());
            } catch (Throwable failure) {
                final String stack = "deliberate failure".equals(failure.getMessage())
                    ? " at " + failure.getStackTrace()[0] : "";
                return new Observation(failure + stack, trace.invoke(null).toString().strip());
            }
        } catch (ReflectiveOperationException | IOException harnessFailure) {
            throw new AssertionError(harnessFailure);
        }
    }

    /** Compares the complete first diagnostic, including its original source line. */
    private static void assertOriginalFailure(InMemoryJavaCompiler compiler, String source) {
        final JdiEvaluationException original = catchThrowableOfType(JdiEvaluationException.class,
            () -> compiler.compile(NAME, source, CONFIG));
        final JdiEvaluationException retried = catchThrowableOfType(JdiEvaluationException.class,
            () -> InMemoryJavaCompiler.compileEvaluation(compiler, NAME, source, CONFIG));
        assertThat(original).isNotNull();
        assertThat(retried).hasMessage(original.getMessage());
    }

    /** Builds a wrapper whose parameter type comes from an actual class file. */
    private static String source(String body) {
        return "package wrapper;\npublic class Probe {\n"
            + "  public static Object evaluate(" + OWNER + " receiver) {\n"
            + "    " + body + "\n  }\n}\n";
    }

    /** Expands the short fixture placeholder used in the table. */
    private static String owners(String text) {
        return text.replace("OWNER", OWNER);
    }
}
