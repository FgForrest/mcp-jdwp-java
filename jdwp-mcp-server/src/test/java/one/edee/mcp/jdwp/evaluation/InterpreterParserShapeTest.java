package one.edee.mcp.jdwp.evaluation;

import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class InterpreterParserShapeTest {

    static Stream<Arguments> expressions() {
        return Stream.of(
            Arguments.of("$exception", SingleNameReference.class),
            Arguments.of("store.map", QualifiedNameReference.class),
            Arguments.of("this.store", FieldReference.class),
            Arguments.of("Outer.this", QualifiedThisReference.class),
            Arguments.of("int[].class", ClassLiteralAccess.class),
            Arguments.of("items[i]", ArrayReference.class),
            Arguments.of("items.length", QualifiedNameReference.class),
            Arguments.of("-2147483648", IntLiteralMinValue.class),
            Arguments.of("~n", UnaryExpression.class),
            Arguments.of("a + b + c + d", CombinedBinaryExpression.class),
            Arguments.of("a == b", EqualExpression.class),
            Arguments.of("a || b", OR_OR_Expression.class),
            Arguments.of("a ? b : c", ConditionalExpression.class),
            Arguments.of("(byte) n", CastExpression.class),
            Arguments.of("value instanceof String", InstanceOfExpression.class),
            Arguments.of("s::length", ReferenceExpression.class),
            Arguments.of("new String(chars)", AllocationExpression.class),
            Arguments.of("new Runnable() { public void run() {} }", QualifiedAllocationExpression.class),
            Arguments.of("\"\"\"\ntext\n\"\"\"", TextBlock.class)
        );
    }

    @ParameterizedTest
    @MethodSource("expressions")
    void parsesTheUnboundExpression(String source, Class<? extends Expression> shape) {
        assertThat(parse(source)).isExactlyInstanceOf(shape);
    }

    @Test
    void preservesReceiversPatternsAndLambdaArgumentsInTheTree() {
        final FieldReference role = (FieldReference) parse("store.map.get(key).role");
        final MessageSend superCall = (MessageSend) parse("Outer.super.dispatch()");
        assertThat(superCall.receiver).isExactlyInstanceOf(QualifiedSuperReference.class);

        final MessageSend get = (MessageSend) role.receiver;
        assertThat(get.selector).isEqualTo("get".toCharArray());
        assertThat(get.receiver).isExactlyInstanceOf(QualifiedNameReference.class);

        final AND_AND_Expression and = (AND_AND_Expression) parse("o instanceof String s && s.isEmpty()");
        assertThat(((InstanceOfExpression) and.left).pattern).isExactlyInstanceOf(TypePattern.class);
        assertThat(and.right).isExactlyInstanceOf(MessageSend.class);

        final MessageSend call = (MessageSend) parse("values.stream().map(x -> x + 1)");
        assertThat(call.arguments).hasSize(1);
        assertThat(call.arguments[0]).isExactlyInstanceOf(LambdaExpression.class);
    }

    @Test
    void recoversBlockStatementsThroughAMethodBody() {
        final Parser parser = parser();
        final var source = new CompilationUnit("""
            class Sample {
                Object evaluate() {
                    int n = 0;
                    n = 2;
                    if (n > 0) n = 1; else n = -1;
                    for (String s : new String[0]) n++;
                    try { return n; } finally {}
                }
            }
            """.toCharArray(), "Sample.java", null);
        final var result = new CompilationResult(source, 0, 1, 100);
        final var unit = parser.dietParse(source, result);
        parser.getMethodBodies(unit);
        assertThat(result.hasErrors()).isFalse();
        final var method = (MethodDeclaration) unit.types[0].methods[1];
        assertThat(method.statements).extracting(Object::getClass).containsExactly(
            LocalDeclaration.class, Assignment.class, IfStatement.class, ForeachStatement.class, TryStatement.class);
        assertThat(((TryStatement) method.statements[4]).tryBlock.statements[0])
            .isExactlyInstanceOf(ReturnStatement.class);
    }

    @Test
    void reportsSyntaxErrorsWithoutACompilerEnvironment() {
        final var result = new CompilationResult("Sample.java".toCharArray(), 0, 1, 100);
        final var parser = parser();
        final var unit = new CompilationUnitDeclaration(parser.problemReporter(), result, 3);
        assertThat(parser.parseExpression("x +".toCharArray(), 0, 3, unit, false)).isNull();
        assertThat(result.getErrors()).hasSize(1);
    }

    private static Expression parse(String source) {
        final var result = new CompilationResult("Sample.java".toCharArray(), 0, 1, 100);
        final var parser = parser();
        final var unit = new CompilationUnitDeclaration(parser.problemReporter(), result, source.length());
        final Expression expression = parser.parseExpression(source.toCharArray(), 0, source.length(), unit, false);
        assertThat(result.hasErrors()).as(source).isFalse();
        return expression;
    }

    private static Parser parser() {
        final var options = new CompilerOptions(Map.of(
            CompilerOptions.OPTION_Source, CompilerOptions.VERSION_17,
            CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_17,
            CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_17));
        return new Parser(new ProblemReporter(DefaultErrorHandlingPolicies.proceedWithAllProblems(),
            options, new DefaultProblemFactory()), false);
    }
}
