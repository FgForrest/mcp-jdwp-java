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
    /** Engine selection: interpret supported expressions and compile the rest, always compile, or never compile. */
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

    /** Signals that the tool must leave the frame reservation and take the invocation path. */
    public static final class NeedsInvocation extends Exception {
        /** Serialization version of this exception. */
        @Serial
        private static final long serialVersionUID = 1L;

        /** Compiler fallback reason, or null for an interpreted invocation. */
        private final @Nullable String reason;

        /** Creates the signal without a stack trace; it is control flow, not a failure. */
        NeedsInvocation(@Nullable String reason) {
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
    /** Number of interpreted evaluations that executed no target call. */
    private final LongAdder navigated = new LongAdder();
    /** Number of interpreted evaluations that executed at least one target call. */
    private final LongAdder invoked = new LongAdder();
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
            return syntax;
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
                return Planner.navigation(vm, frame, location, bindings, Objects.requireNonNull(parsed.expression()));
            } catch (InvalidStackFrameException invalidated) {
                if (!retry || attempt == FRAME_READ_ATTEMPTS) {
                    throw invalidated;
                }
                failure = invalidated;
            }
        }
    }

    /** Fails with {@link NeedsInvocation} when the compiler is forced, so a read-only tool refuses early. */
    public void requireReadOnlyMode() throws NeedsInvocation {
        if (mode == Mode.COMPILER) {
            throw new NeedsInvocation("compiler selected");
        }
    }

    /** Signals that an interpreted call or a compiler fallback needs the invocation runner. */
    public void requireReadOnly(Planner.Plan plan) throws NeedsInvocation {
        if (plan instanceof Planner.Invoke) {
            throw new NeedsInvocation(null);
        }
        if (mode != Mode.INTERPRETER && plan instanceof Planner.Compile fallback) {
            throw new NeedsInvocation(fallback.reason());
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
                return readOnly(e.plan, result);
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

    /** Re-plans on the invocation worker and configures classpath on its first compiled expression. */
    public final class ToolJob {
        /** Thread the tool call evaluates on. */
        private final ThreadReference thread;
        /** Check that the thread is still at the stop the call started from. */
        private final Runnable requireOriginalStop;
        /** Whether the compiler classpath was already configured for this job. */
        private boolean configured;

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
            return run(input, thread, frames, bindings, plan, this::configure, result);
        }

        /** Configures the compiler classpath once per job. */
        private void configure() throws JdiEvaluationException {
            if (!configured) {
                compiler.configureCompilerClasspath(thread);
                configured = true;
            }
        }
    }

    /** Evaluates while the dispatcher owns the event set that anchors any invocation lease. */
    public <T> T evaluateEvent(String expression, ThreadReference thread, Map<String, Value> bindings,
                               Interpreter.Result<T> result) throws Exception {
        final Input input = parse(expression);
        final FrameSource frames = () -> thread.frame(0);
        final Planner.Plan plan = prepare(input, thread, frames, bindings, true);
        return run(input, thread, frames, bindings, plan,
            () -> compiler.configureCompilerClasspathForEvent(thread), result);
    }

    /** Compiler classpath setup step that differs between tool calls and dispatcher events. */
    @FunctionalInterface
    private interface ConfigureClasspath {
        /** Configures the compiler classpath. */
        void run() throws JdiEvaluationException;
    }

    /**
     * Runs the plan with the interpreter and falls back to the compiler, attaching the fallback reason to compiler failures.
     */
    private <T> T run(Input input, ThreadReference thread, FrameSource frames, Map<String, Value> bindings, Planner.Plan plan,
                      ConfigureClasspath configure, Interpreter.Result<T> result) throws Exception {
        if (plan instanceof Planner.Invoke invocation) {
            return Interpreter.execute(invocation, thread, compiler, this::recordInvocation, result);
        }
        try {
            return readOnly(plan, result);
        } catch (NeedsInvocation needs) {
            final String reason = Objects.requireNonNull(needs.reason);
            if (mode != Mode.COMPILER) {
                recordReason(reason);
            }
            compiled.increment();
            final @Nullable Value value;
            try {
                configure.run();
                final StackFrame frame = frames.read();
                if (frame == null) {
                    throw new JdiEvaluationException("No stack frame is available for compilation");
                }
                value = compiler.evaluate(frame, input.source(), bindings);
            } catch (Exception failure) {
                if (mode == Mode.COMPILER || failure instanceof InterruptedException) {
                    throw failure;
                }
                final String message = failure.getMessage();
                throw new JdiEvaluationException(withReason(
                    message == null ? failure.getClass().getSimpleName() : message, reason), failure);
            }
            return result.accept(value);
        }
    }

    /** Attach the reason when the tool's invocation guard refused a fallback before it ran. */
    public String refused(String message, NeedsInvocation need) {
        final String reason = need.reason;
        if (mode == Mode.COMPILER || reason == null) {
            return message;
        }
        recordReason(reason);
        return withReason(message, reason);
    }

    /** Appends the reason the interpreter declined to a message. */
    private static String withReason(String message, String reason) {
        return message + "\nnot interpreted: " + reason;
    }

    /** Records whether execution reached a call, even when the plan was conditional. */
    private void recordInvocation(int calls) {
        if (calls == 0) {
            navigated.increment();
        } else {
            invoked.increment();
        }
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
        final StringBuilder out = new StringBuilder(
            "Expression evaluation: interpreted %d, compiled %d (mode %s), with invocations %d, without invocations %d%n"
                .formatted(navigated.sum() + invoked.sum(), compiled.sum(), mode.name().toLowerCase(Locale.ROOT),
                    invoked.sum(), navigated.sum()));
        reasons.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum()).reversed()
                .thenComparing(Map.Entry::getKey))
            .limit(5)
            .forEach(e -> out.append("  not interpreted: ").append(e.getKey()).append(" (").append(e.getValue())
                .append(")\n"));
        return out.toString();
    }
}
