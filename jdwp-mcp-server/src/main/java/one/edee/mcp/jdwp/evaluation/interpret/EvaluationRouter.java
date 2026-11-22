package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.Serial;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Front door for ordinary evaluation. Fast probes depend on the compiled evaluator directly. */
@Service
public final class EvaluationRouter {
    /** Engine selection: interpret what can be navigated and compile the rest, always compile, or never compile. */
    public enum Mode { AUTO, COMPILER, INTERPRETER }

    /**
     * An expression's source with its parse result, which is null when the compiler was selected and parsing is skipped.
     */
    public record Input(String source, ExpressionParser.@Nullable Parsed parsed) {}

    /** Supplies the stack frame an evaluation runs against. */
    @FunctionalInterface
    public interface FrameSource {
        /** Reads the current frame, or returns null when there is none. */
        @Nullable StackFrame read() throws Exception;
    }

    /** Signals that the read-only interpreter cannot evaluate the expression and the compiler must. */
    public static final class NeedsCompiler extends Exception {
        /** Serialization version of this exception. */
        @Serial
        private static final long serialVersionUID = 1L;

        /** Why the interpreter declined the expression. */
        private final String reason;

        /** Creates the signal without a stack trace; it is control flow, not a failure. */
        NeedsCompiler(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    /** How many times planning re-reads a frame that was invalidated while it was being planned. */
    private static final int FRAME_READ_ATTEMPTS = 3;
    /** Compiling evaluator used for everything the interpreter cannot navigate. */
    private final JdiExpressionEvaluator compiler;
    /** Selected engine mode. */
    private final Mode mode;
    /** Number of expressions that were interpreted. */
    private final LongAdder navigated = new LongAdder();
    /** Number of expressions that fell through to the compiler. */
    private final LongAdder compiled = new LongAdder();
    /** Counts of fallback reasons, grouped by category. */
    private final Map<String, LongAdder> reasons = new ConcurrentHashMap<>();

    /** Creates the router with the engine mode taken from the {@code JDWP_EVAL_ENGINE} environment variable. */
    @Autowired
    public EvaluationRouter(@Lazy JdiExpressionEvaluator compiler) {
        this(compiler, environmentMode());
    }

    /** Creates the router with an explicit engine mode. */
    public EvaluationRouter(JdiExpressionEvaluator compiler, Mode mode) {
        this.compiler = compiler;
        this.mode = mode;
    }

    /** Reads the engine mode from {@code JDWP_EVAL_ENGINE}, defaulting to {@link Mode#AUTO}. */
    private static Mode environmentMode() {
        final String configured = System.getenv("JDWP_EVAL_ENGINE");
        if (configured == null || configured.isBlank()) {
            return Mode.AUTO;
        }
        try {
            return Mode.valueOf(configured.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("JDWP_EVAL_ENGINE must be auto, compiler or interpreter", e);
        }
    }

    /** Parses the expression unless the compiler is forced, which needs no parse. */
    public Input parse(String expression) {
        return new Input(expression, mode == Mode.COMPILER ? null : ExpressionParser.parse(expression));
    }

    /** Plans the evaluation of the input in the thread's current frame, without frame-read retries. */
    public Planner.Plan prepare(Input input, ThreadReference thread, FrameSource frames,
                                Map<String, Value> bindings) throws Exception {
        return prepare(input, thread, frames, bindings, false);
    }

    /**
     * Plans the evaluation; with {@code retry} a frame invalidated during planning is re-read from the anchored location.
     */
    private Planner.Plan prepare(Input input, ThreadReference thread, FrameSource frames,
                                 Map<String, Value> bindings, boolean retry) throws Exception {
        final ExpressionParser.Parsed parsed = input.parsed();
        if (parsed == null) {
            return new Planner.Compile("compiler selected");
        }
        final Planner.Plan syntax = SyntaxShape.routing(parsed);
        if (syntax != null) {
            return effective(syntax);
        }
        final VirtualMachine vm = thread.virtualMachine();
        @Nullable Location anchor = null;
        @Nullable InvalidStackFrameException failure = null;
        for (int attempt = 1; ; attempt++) {
            try {
                final StackFrame frame = anchor == null ? frames.read()
                    : JdiExpressionEvaluator.sameFrame(thread, anchor);
                if (frame == null && failure != null) {
                    throw failure;
                }
                final Location location = frame == null ? null : frame.location();
                if (retry) {
                    anchor = location;
                }
                return effective(Planner.navigation(vm, frame, location, bindings,
                    Objects.requireNonNull(parsed.expression())));
            } catch (InvalidStackFrameException invalidated) {
                if (!retry || attempt == FRAME_READ_ATTEMPTS) {
                    throw invalidated;
                }
                failure = invalidated;
            }
        }
    }

    /** INVOKE plans use the compiler in phase 1. */
    public static Planner.Plan effective(Planner.Plan plan) {
        return plan instanceof Planner.Invoke invoke ? new Planner.Compile(invoke.reason()) : plan;
    }

    /** Fails with {@link NeedsCompiler} when the compiler is forced, so a read-only tool refuses early. */
    public void requireReadOnlyMode() throws NeedsCompiler {
        if (mode == Mode.COMPILER) {
            throw new NeedsCompiler("compiler selected");
        }
    }

    /** Fails with {@link NeedsCompiler} when the plan needs the compiler and the interpreter is not forced. */
    public void requireReadOnly(Planner.Plan plan) throws NeedsCompiler {
        if (mode != Mode.INTERPRETER && plan instanceof Planner.Compile fallback) {
            throw new NeedsCompiler(fallback.reason());
        }
    }

    /** Executes a plan with the interpreter only and hands the value to {@code result}. */
    public <T> T readOnly(Planner.Plan plan, Interpreter.Result<T> result) throws Exception {
        requireReadOnly(plan);
        if (plan instanceof Planner.Error error) {
            throw new JdiEvaluationException(error.message());
        }
        if (plan instanceof Planner.Navigate navigation) {
            navigated.increment();
            try {
                return Interpreter.execute(navigation, result);
            } catch (Planner.Refusal e) {
                return readOnly(effective(e.plan), result);
            } catch (ClassNotPreparedException | ObjectCollectedException e) {
                return readOnly(new Planner.Compile(e instanceof ObjectCollectedException
                    ? "object was collected" : "type is not prepared"), result);
            }
        }
        final String reason = ((Planner.Compile) plan).reason();
        recordReason(reason);
        throw new JdiEvaluationException("not interpreted: " + reason);
    }

    /**
     * Creates a job for one tool call; {@code requireOriginalStop} verifies the thread is still at the stop being inspected.
     */
    public ToolJob toolJob(ThreadReference thread, Runnable requireOriginalStop) {
        return new ToolJob(thread, requireOriginalStop);
    }

    /** Re-plans on the invocation worker and checks the classpath of each compiled frame. */
    public final class ToolJob {
        /** Thread the tool call evaluates on. */
        private final ThreadReference thread;
        /** Check that the thread is still at the stop the call started from. */
        private final Runnable requireOriginalStop;
        /** Created by {@link #toolJob}. */
        private ToolJob(ThreadReference thread, Runnable requireOriginalStop) {
            this.thread = thread;
            this.requireOriginalStop = requireOriginalStop;
        }

        /** Plans and runs the input, falling back to the compiler when the interpreter cannot handle it. */
        public <T> T evaluate(Input input, FrameSource frames, Map<String, Value> bindings,
                              Interpreter.Result<T> result) throws Exception {
            final Planner.Plan plan = prepare(input, thread, frames, bindings, true);
            if (plan instanceof Planner.Navigate) {
                requireOriginalStop.run();
            }
            return run(input, frames, bindings, plan, this::configure, result);
        }

        /** Verifies the original stop and configures the classpath for the selected loader. */
        private void configure(@Nullable ClassLoaderReference loader) throws JdiEvaluationException {
            requireOriginalStop.run();
            if (loader == null) {
                compiler.configureCompilerClasspath(thread);
            } else {
                compiler.configureCompilerClasspath(thread, loader);
            }
        }
    }

    /** Evaluate while the dispatcher owns the event set. Only the compiled path can take a lease. */
    public <T> T evaluateEvent(String expression, ThreadReference thread, Map<String, Value> bindings,
                               Interpreter.Result<T> result) throws Exception {
        final Input input = parse(expression);
        final FrameSource frames = () -> thread.frame(0);
        final Planner.Plan plan = prepare(input, thread, frames, bindings, true);
        return run(input, frames, bindings, plan, loader -> {
            if (loader == null) {
                compiler.configureCompilerClasspathForEvent(thread);
            } else {
                compiler.configureCompilerClasspathForEvent(thread, loader);
            }
        }, result);
    }

    /** Compiler classpath setup step that differs between tool calls and dispatcher events. */
    @FunctionalInterface
    private interface ConfigureClasspath {
        /** Configures the classpath with the selected frame's defining loader. */
        void run(@Nullable ClassLoaderReference loader) throws JdiEvaluationException;
    }

    /**
     * Runs the plan with the interpreter and falls back to the compiler, attaching the fallback reason to compiler failures.
     */
    private <T> T run(Input input, FrameSource frames, Map<String, Value> bindings, Planner.Plan plan,
                      ConfigureClasspath configure, Interpreter.Result<T> result) throws Exception {
        try {
            return readOnly(plan, result);
        } catch (NeedsCompiler needs) {
            if (mode != Mode.COMPILER) {
                recordReason(needs.reason);
            }
            compiled.increment();
            final @Nullable Value value;
            try {
                final StackFrame frame = requireCompilationFrame(frames);
                final Location location = frame.location();
                final ReferenceType type = location == null ? null : location.declaringType();
                configure.run(type == null ? null : type.classLoader());
                // The optional classpath accessors can resume the thread and invalidate the frame.
                value = compiler.evaluate(requireCompilationFrame(frames), input.source(), bindings);
            } catch (Exception failure) {
                if (mode == Mode.COMPILER || failure instanceof InterruptedException) {
                    throw failure;
                }
                final String message = failure.getMessage();
                throw new JdiEvaluationException(withReason(
                    message == null ? failure.getClass().getSimpleName() : message, needs), failure);
            }
            return result.accept(value);
        }
    }

    /** Requires an available frame before selecting a loader or compiling the expression. */
    private static StackFrame requireCompilationFrame(FrameSource frames) throws Exception {
        final StackFrame frame = frames.read();
        if (frame == null) {
            throw new JdiEvaluationException("No stack frame is available for compilation");
        }
        return frame;
    }

    /** Attach the reason when the tool's invocation guard refused a fallback before it ran. */
    public String refused(String message, NeedsCompiler need) {
        if (mode == Mode.COMPILER) {
            return message;
        }
        recordReason(need.reason);
        return withReason(message, need);
    }

    /** Appends the reason the interpreter declined to a message. */
    private static String withReason(String message, NeedsCompiler need) {
        return message + "\nnot interpreted: " + need.reason;
    }

    /** Counts a fallback under its category. */
    private void recordReason(String reason) {
        // Keep statistics bounded by reason category. Names and user source belong in the reply.
        final int detail = reason.indexOf(": ");
        final String category = detail < 0 ? reason : reason.substring(0, detail);
        reasons.computeIfAbsent(category, key -> new LongAdder()).increment();
    }

    /** Summarizes how many expressions were interpreted or compiled and the five most common fallback reasons. */
    public String diagnostics() {
        final StringBuilder out = new StringBuilder("Expression evaluation: interpreted %d, compiled %d (mode %s)%n"
            .formatted(navigated.sum(), compiled.sum(), mode.name().toLowerCase(Locale.ROOT)));
        reasons.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum()).reversed()
                .thenComparing(Map.Entry::getKey))
            .limit(5)
            .forEach(e -> out.append("  not interpreted: ").append(e.getKey()).append(" (").append(e.getValue())
                .append(")\n"));
        return out.toString();
    }
}
