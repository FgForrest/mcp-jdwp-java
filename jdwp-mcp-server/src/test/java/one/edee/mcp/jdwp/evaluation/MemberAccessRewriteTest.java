package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.evaluation.fixture.PrivateMembers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

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
        receiver /* before */ . amount | ((int) __mcpGet(receiver, "OWNER", "amount"))
        (receiver.amount) | (((int) __mcpGet(receiver, "OWNER", "amount")))
        OWNER.TOTAL | ((int) __mcpGet(null, "OWNER", "TOTAL"))
        receiver.select("abc") | ((int) __mcpInvoke(receiver, "OWNER", "select", new java.lang.String[]{"java.lang.String"}, new java.lang.Object[]{(java.lang.String) ("abc")}))
        receiver.select(3) | ((int) __mcpInvoke(receiver, "OWNER", "select", new java.lang.String[]{"long"}, new java.lang.Object[]{(long) (3)}))
        new OWNER(5) | ((OWNER) __mcpConstruct("OWNER", new java.lang.String[]{"int"}, new java.lang.Object[]{(int) (5)}))
        receiver.next.amount | ((OWNER) __mcpGet(receiver, "OWNER", "next")).amount
        """)
    void rewritesOnlyTheFailingAccess(String expression, String expected, @TempDir Path directory) throws Exception {
        final String original = source("return /* keep */ " + owners(expression) + ";");
        final Path file = Files.writeString(directory.resolve("Probe.java"), original);
        final String rewritten = MemberAccessRewrite.rewrite(NAME, file, original, CONFIG, true);
        assertThat(rewritten).contains("    return /* keep */ " + owners(expected) + ";\n");
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
