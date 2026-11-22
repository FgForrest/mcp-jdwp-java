package one.edee.mcp.jdwp.evaluation.interpret;

import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses snippets without a compiler environment, classpath or VM connection. */
public final class ExpressionParser {
    /** Parse result: an expression, a statement block, or a syntax error message; exactly one is set. */
    public record Parsed(@Nullable Expression expression, @Nullable List<Statement> statements,
                         @Nullable String error) {}

    /** Not instantiable; the class only holds static parsing. */
    private ExpressionParser() {}

    /** Parses the snippet as a statement block when it is one, otherwise as a single expression. */
    public static Parsed parse(String source) {
        final CompilerOptions options = new CompilerOptions(Map.of(
            CompilerOptions.OPTION_Source, CompilerOptions.VERSION_21,
            CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_21,
            CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_21));
        // Preserve the operands of literal concatenations for planning and constant tracking.
        final Parser parser = new Parser(new ProblemReporter(DefaultErrorHandlingPolicies.proceedWithAllProblems(),
            options, new DefaultProblemFactory(Locale.ROOT)), false);
        if (JdiExpressionEvaluator.isBlockMode(source)) {
            final CompilationUnit input = new CompilationUnit(
                ("class Snippet { Object evaluate() " + source + "\n}").toCharArray(), "Snippet.java", null);
            final CompilationResult result = new CompilationResult(input, 0, 1, 100);
            final CompilationUnitDeclaration unit = parser.dietParse(input, result);
            parser.getMethodBodies(unit);
            if (result.hasErrors()) {
                return error(source, result);
            }
            for (AbstractMethodDeclaration method : unit.types[0].methods) {
                if (method instanceof MethodDeclaration body) {
                    return new Parsed(null, body.statements == null ? List.of() : List.of(body.statements), null);
                }
            }
            return error(source, result);
        }

        final CompilationResult result = new CompilationResult("Snippet.java".toCharArray(), 0, 1, 100);
        final CompilationUnitDeclaration unit = new CompilationUnitDeclaration(parser.problemReporter(), result, source.length());
        final Expression expression = parser.parseExpression(source.toCharArray(), 0, source.length(), unit, false);
        return expression == null || result.hasErrors() ? error(source, result) : new Parsed(expression, null, null);
    }

    /** Builds the syntax error result, noting HTML entities that often explain the failure. */
    private static Parsed error(String source, CompilationResult result) {
        final CategorizedProblem[] errors = result.getErrors();
        String message = errors == null || errors.length == 0 ? "invalid Java snippet" : errors[0].getMessage();
        for (String entity : List.of("&amp;", "&lt;", "&gt;")) {
            if (source.contains(entity)) {
                message += " (found HTML entity " + entity + " in Java source)";
                break;
            }
        }
        return new Parsed(null, null, "Syntax error: " + message);
    }
}
