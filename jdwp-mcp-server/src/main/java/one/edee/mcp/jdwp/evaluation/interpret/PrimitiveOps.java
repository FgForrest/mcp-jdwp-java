package one.edee.mcp.jdwp.evaluation.interpret;

import static org.eclipse.jdt.internal.compiler.ast.OperatorIds.*;

/** Java primitive conversions and operators, using the host JVM's arithmetic. */
final class PrimitiveOps {
    /** Primitive kinds, ordered so that a larger ordinal is the wider numeric type. */
    enum Kind {
        BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, VOID;

        /** Whether arithmetic is defined for this kind. */
        boolean numeric() {
            return this != BOOLEAN && this != VOID;
        }

        /** Whether this kind is an integer type, including char. */
        boolean integral() {
            return numeric() && this != FLOAT && this != DOUBLE;
        }

        /** Unary numeric promotion: byte, short and char become int. */
        Kind promote() {
            return switch (this) {
                case BYTE, SHORT, CHAR -> INT;
                default -> this;
            };
        }

        /** Binary numeric promotion of this kind and {@code other}. */
        Kind promote(Kind other) {
            return ordinal() > other.ordinal() ? promote() : other.promote();
        }

        /** Converts a boxed host value to this kind, narrowing or widening as a Java cast would. */
        Object convert(Object value) {
            if (this == BOOLEAN) {
                return (Boolean) value;
            }
            final Number number = value instanceof Character c ? Integer.valueOf(c) : (Number) value;
            return switch (this) {
                case BYTE -> (byte) number.intValue();
                case SHORT -> (short) number.intValue();
                case CHAR -> (char) number.intValue();
                case INT -> number.intValue();
                case LONG -> number.longValue();
                case FLOAT -> number.floatValue();
                case DOUBLE -> number.doubleValue();
                default -> throw new IllegalArgumentException("Cannot convert to " + this);
            };
        }

        /** Whether an int constant fits this narrow kind without loss. */
        boolean represents(int number) {
            return switch (this) {
                case BYTE -> number == (byte) number;
                case SHORT -> number == (short) number;
                case CHAR -> number == (char) number;
                default -> false;
            };
        }
    }

    /** Not instantiable; the class only holds static operations. */
    private PrimitiveOps() {}

    /** Applies a unary operator to an operand of the given promoted kind. */
    static Object unary(int operator, Kind kind, Object operand) {
        final Object a = kind.convert(operand);
        if (operator == PLUS) {
            return a;
        }
        if (operator == NOT) {
            return !(Boolean) a;
        }
        if (operator == TWIDDLE) {
            if (kind == Kind.LONG) {
                return ~((Long) a).longValue();
            }
            return ~((Integer) a).intValue();
        }
        return switch (kind) {
            case INT -> -((Integer) a).intValue();
            case LONG -> -((Long) a).longValue();
            case FLOAT -> -((Float) a).floatValue();
            case DOUBLE -> -((Double) a).doubleValue();
            default -> throw new IllegalArgumentException("Cannot negate " + kind);
        };
    }

    /** Whether the operator is {@code ==} or {@code !=}. */
    static boolean equality(int op) {
        return op == EQUAL_EQUAL || op == NOT_EQUAL;
    }

    /** Whether the operator yields a boolean from an ordering or equality test. */
    static boolean comparison(int op) {
        return equality(op) || op == LESS || op == LESS_EQUAL || op == GREATER || op == GREATER_EQUAL;
    }

    /** Whether the operator is a shift. */
    static boolean shift(int op) {
        return op == LEFT_SHIFT || op == RIGHT_SHIFT || op == UNSIGNED_RIGHT_SHIFT;
    }

    /** Whether the operator is {@code &}, {@code |} or {@code ^}. */
    static boolean bitwise(int op) {
        return op == AND || op == OR || op == XOR;
    }

    /** Applies a binary operator to operands that were promoted to the given kind. */
    static Object binary(int op, Kind kind, Object left, Object right) {
        final Object a = kind.convert(left);
        // Shift distance is masked by the promoted left type, independently of the right type.
        final Object b = (shift(op) ? Kind.INT : kind).convert(right);
        return switch (kind) {
            case BOOLEAN -> bool(op, (Boolean) a, (Boolean) b);
            case INT -> ints(op, (Integer) a, (Integer) b);
            case LONG -> longs(op, (Long) a, ((Number) b).longValue());
            case FLOAT -> floats(op, (Float) a, (Float) b);
            case DOUBLE -> doubles(op, (Double) a, (Double) b);
            default -> throw new IllegalArgumentException("Operands must undergo promotion");
        };
    }

    /** Boolean operators. */
    private static boolean bool(int op, boolean a, boolean b) {
        return switch (op) {
            case AND, AND_AND -> a & b;
            case OR, OR_OR -> a | b;
            case XOR, NOT_EQUAL -> a ^ b;
            case EQUAL_EQUAL -> a == b;
            default -> throw unsupported(op);
        };
    }

    /** Int arithmetic, bitwise, shift and comparison operators. */
    private static Object ints(int op, int a, int b) {
        return switch (op) {
            case PLUS -> a + b;
            case MINUS -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> a / b;
            case REMAINDER -> a % b;
            case AND -> a & b;
            case OR -> a | b;
            case XOR -> a ^ b;
            case LEFT_SHIFT -> a << b;
            case RIGHT_SHIFT -> a >> b;
            case UNSIGNED_RIGHT_SHIFT -> a >>> b;
            default -> compare(op, Integer.compare(a, b));
        };
    }

    /** Long arithmetic, bitwise, shift and comparison operators. */
    private static Object longs(int op, long a, long b) {
        return switch (op) {
            case PLUS -> a + b;
            case MINUS -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> a / b;
            case REMAINDER -> a % b;
            case AND -> a & b;
            case OR -> a | b;
            case XOR -> a ^ b;
            case LEFT_SHIFT -> a << b;
            case RIGHT_SHIFT -> a >> b;
            case UNSIGNED_RIGHT_SHIFT -> a >>> b;
            default -> compare(op, Long.compare(a, b));
        };
    }

    /** Float arithmetic, with comparisons delegated to the double operators. */
    private static Object floats(int op, float a, float b) {
        return switch (op) {
            case PLUS -> a + b;
            case MINUS -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> a / b;
            case REMAINDER -> a % b;
            default -> doubles(op, a, b);
        };
    }

    /** Double arithmetic and comparison operators. */
    private static Object doubles(int op, double a, double b) {
        return switch (op) {
            case PLUS -> a + b;
            case MINUS -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> a / b;
            case REMAINDER -> a % b;
            case EQUAL_EQUAL -> a == b;
            case NOT_EQUAL -> a != b;
            case LESS -> a < b;
            case LESS_EQUAL -> a <= b;
            case GREATER -> a > b;
            case GREATER_EQUAL -> a >= b;
            default -> throw unsupported(op);
        };
    }

    /** Evaluates a comparison operator against a three-way comparison result. */
    private static boolean compare(int op, int order) {
        return switch (op) {
            case EQUAL_EQUAL -> order == 0;
            case NOT_EQUAL -> order != 0;
            case LESS -> order < 0;
            case LESS_EQUAL -> order <= 0;
            case GREATER -> order > 0;
            case GREATER_EQUAL -> order >= 0;
            default -> throw unsupported(op);
        };
    }

    /** Builds the exception for an operator the operand kind does not support. */
    private static IllegalArgumentException unsupported(int op) {
        return new IllegalArgumentException("Unsupported operator " + op);
    }
}
