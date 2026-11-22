package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.evaluation.PinnedMirrors;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;

import java.io.Serial;
import java.util.Objects;

/** Runs a typed navigation plan and validates the frame that supplied its values. */
public final class Interpreter {
    /** Not instantiable; the class only holds static entry points. */
    private Interpreter() {}

    /** One executable step of a navigation plan. */
    @FunctionalInterface
    interface Read {
        /** Evaluates this step and returns the resulting mirror, or null for a Java null. */
        @Nullable Value run(Context context);
    }

    /** Execution state of one plan run: the target VM and the pins that keep created strings alive. */
    record Context(VirtualMachine vm, PinnedMirrors pins) {
        /** Converts a host-side literal or folded constant into a mirror in the target VM. */
        Value mirror(Object value) {
            if (value instanceof Boolean v) {
                return vm.mirrorOf(v.booleanValue());
            }
            if (value instanceof Byte v) {
                return vm.mirrorOf(v.byteValue());
            }
            if (value instanceof Short v) {
                return vm.mirrorOf(v.shortValue());
            }
            if (value instanceof Character v) {
                return vm.mirrorOf(v.charValue());
            }
            if (value instanceof Integer v) {
                return vm.mirrorOf(v.intValue());
            }
            if (value instanceof Long v) {
                return vm.mirrorOf(v.longValue());
            }
            if (value instanceof Float v) {
                return vm.mirrorOf(v.floatValue());
            }
            if (value instanceof Double v) {
                return vm.mirrorOf(v.doubleValue());
            }
            if (value instanceof String v) {
                return pins.string(vm, v);
            }
            throw new IllegalArgumentException("Not a Java literal value: " + value);
        }
    }

    /** Consumer of the final value of a plan run. */
    @FunctionalInterface
    public interface Result<T> {
        /** Converts the final value, which is null for a Java null, into the caller's result. */
        T accept(@Nullable Value value) throws Exception;
    }

    /** Executes and formats the result with its strings pinned, discarding reads from an invalidated frame. */
    static <T> T execute(Planner.Navigate plan, Result<T> result) throws Exception {
        try (PinnedMirrors pins = new PinnedMirrors()) {
            final @Nullable Value value;
            try {
                value = plan.read().run(new Context(plan.vm(), pins));
            } catch (NullPointerException | ArithmeticException | ClassCastException | IndexOutOfBoundsException e) {
                throw new RaisedException(e);
            } finally {
                validateFrame(plan.frame());
            }
            try {
                return result.accept(value);
            } finally {
                // Formatting can also read target values.
                validateFrame(plan.frame());
            }
        }
    }

    /** Checks that the original frame survived, even when the target has since suspended again. */
    private static void validateFrame(@Nullable StackFrame frame) {
        if (frame != null) {
            frame.location();
        }
    }

    /**
     * Signals that executing the expression raised a Java exception on the interpreter's side, for example a null dereference.
     */
    public static final class RaisedException extends JdiEvaluationException {
        /** Serialization version of this exception. */
        @Serial
        private static final long serialVersionUID = 1L;

        /** Wraps the raised host exception and names its type in the message. */
        RaisedException(RuntimeException cause) {
            super("Interpreter raised " + cause.getClass().getName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()), cause);
        }
    }

    /**
     * Reads a primitive mirror, or the primitive held by a box object when {@code unbox} names its value field, as a host object.
     */
    static Object primitive(@Nullable Value value, @Nullable Field unbox) {
        final Value unboxed = unbox == null ? Objects.requireNonNull(value)
            : Objects.requireNonNull(((ObjectReference) Objects.requireNonNull(value)).getValue(unbox));
        if (unboxed instanceof BooleanValue v) {
            return v.value();
        }
        if (unboxed instanceof ByteValue v) {
            return v.value();
        }
        if (unboxed instanceof ShortValue v) {
            return v.value();
        }
        if (unboxed instanceof CharValue v) {
            return v.value();
        }
        if (unboxed instanceof IntegerValue v) {
            return v.value();
        }
        if (unboxed instanceof LongValue v) {
            return v.value();
        }
        if (unboxed instanceof FloatValue v) {
            return v.value();
        }
        if (unboxed instanceof DoubleValue v) {
            return v.value();
        }
        throw new IllegalArgumentException("Not a primitive mirror: " + unboxed);
    }

    /** Formats a value for string concatenation: "null", the string content, or the primitive's text. */
    static String string(@Nullable Value value) {
        if (value == null) {
            return "null";
        }
        return value instanceof StringReference s ? s.value() : primitive(value, null).toString();
    }

    /** Reference comparison ({@code ==}) of two mirrors. */
    static boolean identity(@Nullable Value left, @Nullable Value right) {
        return Objects.equals(left, right);
    }
}
