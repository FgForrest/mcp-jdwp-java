package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.evaluation.InMemoryJavaCompiler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InterpreterPrimitiveOracleTest {
    private static final Map<Class<?>, Class<? extends PrimitiveValue>> MIRRORS = Map.of(
        Boolean.class, BooleanValue.class, Byte.class, ByteValue.class, Short.class, ShortValue.class,
        Character.class, CharValue.class, Integer.class, IntegerValue.class, Long.class, LongValue.class,
        Float.class, FloatValue.class, Double.class, DoubleValue.class);

    @Test
    void matchesEcjForGeneratedOperatorsPromotionsCastsAndConditionalTypes() throws Exception {
        final List<String> expressions = expressions();
        final var source = new StringBuilder("public class PrimitiveOracle {\n"
            + "public static Object evaluate(int n) { return switch (n) {\n");
        for (int i = 0; i < expressions.size(); i++) {
            source.append("case ").append(i).append(" -> (Object) (").append(expressions.get(i)).append(");\n");
        }
        source.append("default -> throw new AssertionError(n); }; } }");
        final var compiler = new InMemoryJavaCompiler();
        compiler.configure(System.getProperty("java.home"), "", 17);
        final byte[] bytes = compiler.compile("PrimitiveOracle", source.toString()).get("PrimitiveOracle");
        final var loader = new ClassLoader() {
            Class<?> loadOracle() {
                return defineClass("PrimitiveOracle", bytes, 0, bytes.length);
            }
        };
        final var oracle = loader.loadOracle().getMethod("evaluate", int.class);
        final VirtualMachine vm = mock(VirtualMachine.class, call -> {
            if (call.getMethod().getName().equals("mirrorOf")) {
                final Object value = call.getArgument(0);
                final Class<?> type = MIRRORS.get(value.getClass());
                return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                    if (method.getName().equals("value")) {
                        return value;
                    }
                    throw new AssertionError(method);
                });
            }
            throw new AssertionError(call.getMethod());
        });

        for (int i = 0; i < expressions.size(); i++) {
            final String expression = expressions.get(i);
            final var plan = Planner.plan(vm, null, Map.of(), ExpressionParser.parse(expression));
            assertThat(plan).as(expression).isInstanceOf(Planner.Navigate.class);
            Object expected;
            try {
                expected = oracle.invoke(null, i);
            } catch (InvocationTargetException e) {
                expected = e.getCause().getClass();
            }
            Object actual;
            try {
                actual = Interpreter.execute((Planner.Navigate) plan, value -> Interpreter.primitive(value, null));
            } catch (Interpreter.RaisedException e) {
                actual = e.getCause().getClass();
            }
            assertThat(actual).as(expression).isEqualTo(expected);
        }
    }

    private static List<String> expressions() {
        final var random = new Random(0x51a9eL);
        final var expressions = new ArrayList<>(List.of("2147483647 + 1", "-2147483648 / -1",
            "-9223372036854775808L % -1", "1 / (2 - 2)", "0.0 / 0.0", "-0.0 * 2", "0.0 == -0.0",
            "(float)(9223372036854775807L)", "(byte)(1.0 / 0.0)", "(char)(-1.0 / 0.0)", "0.0 / 0.0 != 0.0 / 0.0",
            "true ? (byte) 7 : (short) 3", "false ? (char) 8 : (1 + 2)", "true ? (byte) 1 : 128",
            "(byte) (false ? (1 / 0) : 33)", "(true || 1 / 0 > 0) && !false",
            "true ? (false ? (short) 4 : (byte) 8) : 1", "(char) -1 >>> 0x21L"));
        final List<String> ops = List.of("+", "-", "*", "/", "%", "<", "<=", ">", ">=", "==", "!=");
        final List<String> integral = List.of("&", "|", "^", "<<", ">>", ">>>");
        final List<String> types = List.of("byte", "short", "char", "int", "long", "float", "double");
        for (int i = 0; i < 160; i++) {
            final int left = random.nextInt(types.size());
            final int right = random.nextInt(types.size());
            final List<String> operators = left < 5 && right < 5 && random.nextBoolean() ? integral : ops;
            final String a = "(" + types.get(left) + ") " + random.nextInt();
            final String b = "(" + types.get(right) + ") " + random.nextLong() + "L";
            final String operator = operators.get(random.nextInt(operators.size()));
            final String arithmetic = "(" + a + " " + operator + " " + b + ")";
            expressions.add(i % 5 == 0 ? "(1 < 2 ? " + a + " : " + b + ")" : arithmetic);
        }
        return expressions;
    }
}
