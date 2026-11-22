package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.PinnedMirrors;
import one.edee.mcp.jdwp.evaluation.RemoteCodeExecutor;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;

import java.io.Serial;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Executes typed plans using captured frame values and JDI mirrors. */
public final class Interpreter {
    /** Not instantiable; the class only holds static entry points. */
    private Interpreter() {}

    /** One executable step of an interpreted plan. */
    @FunctionalInterface
    interface Read {
        /** Evaluates this step and returns the resulting mirror, or null for a Java null. */
        @Nullable Value run(Context context);
    }

    /** Per-run mirrors, pattern bindings and invocation state. */
    static final class Context {
        /** Target VM used to mirror values. */
        private final VirtualMachine vm;
        /** Mirrors pinned through result formatting. */
        private final PinnedMirrors pins;
        /** Thread admitted to invoke, or null for a navigation plan. */
        private final @Nullable ThreadReference thread;
        /** Lazily allocated slots for successful instanceof matches. */
        private @Nullable Map<Object, Value> patterns;
        /** Count of completed target calls, including helpers and calls that threw in the target. */
        private int calls;

        /** Creates a context for one execution. */
        Context(VirtualMachine vm, PinnedMirrors pins, @Nullable ThreadReference thread) {
            this.vm = vm;
            this.pins = pins;
            this.thread = thread;
        }

        /** Retains a value across subsequent invocations. */
        @Nullable Value keep(@Nullable Value value) {
            return thread != null && value instanceof ObjectReference object ? pins.keep(object) : value;
        }

        /** Stores a successful pattern match. */
        void bind(Object slot, Value value) {
            if (patterns == null) {
                patterns = new HashMap<>();
            }
            patterns.put(slot, value);
        }

        /** Reads the value of a definitely matched pattern. */
        Value pattern(Object slot) {
            return Objects.requireNonNull(Objects.requireNonNull(patterns).get(slot));
        }

        /** Runs a bound JDI call and pins its result without retrying the call. */
        @Nullable Value call(Method method, @Nullable Value receiver, List<@Nullable Value> args,
                             boolean nonvirtual, boolean constructor) {
            final ThreadReference current = Objects.requireNonNull(thread);
            final ReferenceType owner = method.declaringType();
            final int single = ObjectReference.INVOKE_SINGLE_THREADED;
            final Value value;
            try {
                if (constructor) {
                    value = ((ClassType) owner).newInstance(current, method, args, single);
                } else if (method.isStatic() && owner instanceof ClassType cls) {
                    value = cls.invokeMethod(current, method, args, single);
                } else if (method.isStatic() && owner instanceof InterfaceType itf) {
                    value = itf.invokeMethod(current, method, args, single);
                } else {
                    final int options = nonvirtual ? single | ObjectReference.INVOKE_NONVIRTUAL : single;
                    final ObjectReference object = (ObjectReference) Objects.requireNonNull(receiver);
                    value = object.invokeMethod(current, method, args, options);
                }
            } catch (InvocationException e) {
                calls++;
                throw new CallFailure(RemoteCodeExecutor.targetException(e));
            } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
                throw new CallFailure(new JdiEvaluationException("Failed to execute remote code: " + e.getMessage(), e));
            }
            calls++;
            return keep(value);
        }

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
                value = plan.read().run(new Context(plan.vm(), pins, null));
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

    /** Executes a validated snapshot under one lease, including result formatting and pin cleanup. */
    static <T> T execute(Planner.Invoke plan, ThreadReference thread, JdiExpressionEvaluator evaluator,
                         IntConsumer evaluated, Result<T> result) throws Exception {
        try (EvaluationGuard.Lease lease = Objects.requireNonNull(evaluator.invocationLease(thread));
             PinnedMirrors pins = new PinnedMirrors()) {
            final Context context = new Context(plan.vm(), pins, thread);
            final @Nullable Value value;
            try {
                value = context.keep(plan.read().run(context));
            } catch (CallFailure failure) {
                throw failure.failure;
            } catch (Planner.Refusal refusal) {
                refuseAfterCalls(refusal, context.calls);
                throw refusal;
            } catch (ClassNotPreparedException | ObjectCollectedException failure) {
                final Planner.Refusal refusal = Planner.compile(failure instanceof ObjectCollectedException
                    ? "object was collected" : "type is not prepared");
                refuseAfterCalls(refusal, context.calls);
                throw refusal;
            } catch (NullPointerException | ArithmeticException | ClassCastException | IndexOutOfBoundsException e) {
                throw new RaisedException(e);
            } finally {
                evaluated.accept(context.calls);
            }
            return result.accept(value);
        }
    }

    /** Reports a refusal as an error once even one call has completed. */
    private static void refuseAfterCalls(Planner.Refusal refusal, int calls) throws JdiEvaluationException {
        if (calls != 0 && refusal.plan instanceof Planner.Compile fallback) {
            throw new JdiEvaluationException("Interpreter refused after " + calls
                + (calls == 1 ? " invocation: " : " invocations: ") + fallback.reason());
        }
    }

    /** Transports a checked JDI call failure through the interpretation steps. */
    private static final class CallFailure extends RuntimeException {
        /** Serialization version of this exception. */
        @Serial
        private static final long serialVersionUID = 1L;

        /** Diagnostic presented to the caller. */
        private final JdiEvaluationException failure;

        /** Keeps the cause without a second stack trace. */
        private CallFailure(JdiEvaluationException failure) {
            super(null, failure, false, false);
            this.failure = failure;
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
