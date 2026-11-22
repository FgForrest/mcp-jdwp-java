package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.interpret.ExpressionParser;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        final var parsed = ExpressionParser.parse("""
            {
                int n = 0;
                n = 2;
                if (n > 0) n = 1; else n = -1;
                for (String s : new String[0]) n++;
                try { return n; } finally {}
            }
            """);
        assertThat(parsed.error()).isNull();
        final var statements = java.util.Objects.requireNonNull(parsed.statements());
        assertThat(statements).extracting(Object::getClass).containsExactly(
            LocalDeclaration.class, Assignment.class, IfStatement.class, ForeachStatement.class, TryStatement.class);
        assertThat(((TryStatement) statements.get(4)).tryBlock.statements[0]).isExactlyInstanceOf(ReturnStatement.class);
    }

    @Test
    void productionParserDistinguishesBlocksAndSyntaxErrors() {
        assertThat(ExpressionParser.parse("{ return 1; }").statements()).hasSize(1);
        assertThat(ExpressionParser.parse("this.secret").expression()).isInstanceOf(FieldReference.class);
        assertThat(ExpressionParser.parse("a &amp;&amp; b").error()).contains("HTML entity");
        assertThat(ExpressionParser.parse("x +").error()).contains("Syntax error");
        assertThat(ExpressionParser.parse("{ return ; + }").error()).contains("Syntax error");
    }

    private static Expression parse(String source) {
        final var parsed = ExpressionParser.parse(source);
        assertThat(parsed.error()).as(source).isNull();
        return java.util.Objects.requireNonNull(parsed.expression());
    }
}
