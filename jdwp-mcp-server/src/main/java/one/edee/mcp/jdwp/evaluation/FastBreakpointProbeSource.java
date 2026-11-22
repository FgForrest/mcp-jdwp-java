package one.edee.mcp.jdwp.evaluation;

import org.jspecify.annotations.Nullable;

import java.util.List;

import static one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource.ENABLED_FIELD;
import static one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource.HIT_METHOD;
import static one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource.KIND_FIELD;
import static one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource.literal;

/**
 * Generates the Java source of a fast-breakpoint probe — the class that {@code jdwp_set_fast_breakpoint}
 * defines in the target VM and calls from the instrumented line.
 * <p>
 * The probe's {@code public static void hit(...)} runs on the application thread that executes the
 * line and evaluates the condition there. After a true hit passes its gate, it renders the optional
 * capture and passes it to {@code stop()} with the stop kind and generation. An error stop carries
 * the condition failure only. The server puts its JDI breakpoint on that empty method. A method that
 * holds a breakpoint is never compiled or inlined by HotSpot, so the breakpoint lives in
 * {@code stop()}. Both {@code hit()} and the instrumented method stay eligible for JIT compilation
 * and inlining, and a false condition never involves the debugger.
 * <p>
 * Besides the fields every probe shares (see {@link FastLogpointProbeSource}), it carries:
 * <ul>
 *   <li>{@value #ONCE_FIELD} — whether only the first true hit of an epoch stops.</li>
 *   <li>{@value #EPOCH_FIELD} / {@value #ERROR_EPOCH_FIELD} — the generations of the true-hit and the
 *       error gate. Only the server writes them; re-arming or cancelling a suppressed claim advances
 *       one of them.</li>
 *   <li>{@value #CLAIMED_FIELD} / {@value #ERROR_CLAIMED_FIELD} — the last generation whose stop was
 *       claimed. A gate moves only upwards, to the generation the claiming thread read before it
 *       evaluated the condition, so an evaluation that started before a re-arm can never claim the
 *       new generation. The true-hit gate is used in once mode only; the first error of each error
 *       generation stops in both modes, later ones are only recorded.</li>
 *   <li>{@value #TRUE_HITS_FIELD}, {@value #ERRORS_FIELD}, {@value #STOPS_FIELD} — counters: the
 *       condition held, the condition failed, {@code stop()} was called. The stop count is taken just
 *       before the call, so {@code stop()} stays empty: a step into it from the stop has nothing to
 *       enter.</li>
 * </ul>
 * The ring buffer holds condition failures only ({@code C} entries). The failure text is also
 * passed to {@code stop()}, so the stop reports it even after its ring slot was overwritten. Nothing
 * the condition throws, and no failure while recording it, propagates into the application.
 */
public final class FastBreakpointProbeSource {
    /**
     * Simple-name prefix of every fast-breakpoint probe class.
     */
    public static final String CLASS_PREFIX = "McpFastBreakpoint_";
    /**
     * Value of the probe's {@code MCP_KIND} constant.
     */
    public static final String KIND = "BREAKPOINT";
    /**
     * Name of the static method the server's breakpoint sits on.
     */
    public static final String STOP_METHOD = "stop";
    /**
     * Static {@code boolean} constant: stop on the first true hit of an epoch only.
     */
    public static final String ONCE_FIELD = "ONCE";
    /**
     * Static {@code volatile int}: generation of the true-hit gate.
     */
    public static final String EPOCH_FIELD = "EPOCH";
    /**
     * Static {@code volatile int}: generation of the error gate.
     */
    public static final String ERROR_EPOCH_FIELD = "ERROR_EPOCH";
    /**
     * Static {@code AtomicInteger}: last true-hit generation claimed.
     */
    public static final String CLAIMED_FIELD = "CLAIMED";
    /**
     * Static {@code AtomicInteger}: last error generation claimed.
     */
    public static final String ERROR_CLAIMED_FIELD = "ERROR_CLAIMED";
    /**
     * Static {@code AtomicLong}: how often the condition held.
     */
    public static final String TRUE_HITS_FIELD = "TRUE_HITS";
    /**
     * Static {@code AtomicLong}: how often the condition threw or yielded no boolean.
     */
    public static final String ERRORS_FIELD = "ERRORS";
    /**
     * Static {@code AtomicLong}: how often {@code stop()} was reached.
     */
    public static final String STOPS_FIELD = "STOPS";
    /**
     * {@code stop()}'s {@code kind}: the condition held.
     */
    public static final int STOP_CONDITION_TRUE = 1;
    /**
     * {@code stop()}'s {@code kind}: the condition failed; {@code error} describes how.
     */
    public static final int STOP_CONDITION_ERROR = 2;
    /** Prefix shared by the generated capture code and the debugger's capture diagnostics. */
    private static final String CAPTURE_FAILURE_PREFIX = "capture failed: ";
    /** Description stored in the generated probe's {@code MCP_ABOUT} constant. */
    private static final String ABOUT = "Fast-breakpoint probe injected by mcp-jdwp-java (jdwp_set_fast_breakpoint): "
        + "MCP_TARGET_CLASS calls hit() at MCP_TARGET_LINE, which calls stop() when MCP_CONDITION holds and passes "
        + "the rendered MCP_EXPRESSION if a capture was configured. The "
        + "debugger's breakpoint sits on stop(). Without that debugger stop() does nothing. Setting ENABLED to false "
        + "stops it; attaching mcp-jdwp-java again lists it and can restore the original class.";

    private FastBreakpointProbeSource() {
    }

    /**
     * Returns Java source for a fast-breakpoint probe with the optional capture expression.
     *
     * @param className  binary name of the probe class (its package becomes the package declaration)
     * @param parameters the probe bindings in order — the optional receiver followed by the
     *                   locals visible at the instrumented line, with wrapper-reachable types
     * @param condition  the condition to compile (possibly rewritten); {@code { block }} syntax is supported
     * @param capture    optional capture to compile (possibly rewritten); {@code { block }} syntax is supported
     * @param stopOnce   whether only the first true hit of an epoch stops
     * @param bufferSize capacity of the condition-failure ring, a power of two
     * @param metadata   what the probe records about itself for later sessions
     * @throws IllegalArgumentException unless the ring capacity is a positive power of two
     */
    public static String generate(String className, List<FastLogpointProbeSource.Parameter> parameters,
                                  String condition, @Nullable String capture, boolean stopOnce, int bufferSize,
                                  FastLogpointProbeSource.Metadata metadata) {
        return generate(className, parameters, condition, capture, null, stopOnce, bufferSize, metadata);
    }

    /**
     * Returns Java source for the dispatcher to compile, with a fixed diagnostic when the capture failed to compile.
     * @throws IllegalArgumentException unless the ring capacity is a positive power of two
     */
    static String generate(String className, List<FastLogpointProbeSource.Parameter> parameters, String condition,
                           @Nullable String capture, @Nullable String captureCompileError, boolean stopOnce,
                           int bufferSize, FastLogpointProbeSource.Metadata metadata) {
        FastLogpointProbeSource.requirePowerOfTwo(bufferSize);
        final int lastDot = className.lastIndexOf('.');
        final String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);
        final String simpleName = lastDot < 0 ? className : className.substring(lastDot + 1);
        final String declaredParameters = FastLogpointProbeSource.declaredParameters(parameters);
        final List<FastLogpointProbeSource.Parameter> forwarded = FastLogpointProbeSource.forwardedParameters(parameters);
        final String arguments = FastLogpointProbeSource.arguments(forwarded);
        final String compileFailure = captureCompileError == null ? null : literal(CAPTURE_FAILURE_PREFIX + captureCompileError);
        final boolean reportsCapture = capture != null || compileFailure != null;

        final StringBuilder source = new StringBuilder();
        FastLogpointProbeSource.appendClassStart(source, packageName, simpleName, "jdwp_set_fast_breakpoint");
        source.append("    public static final java.lang.String ").append(KIND_FIELD).append(" = ").append(literal(KIND)).append(";\n")
            .append("    public static final boolean ").append(ONCE_FIELD).append(" = ").append(stopOnce).append(";\n")
            .append("    public static volatile int ").append(EPOCH_FIELD).append(" = 1;\n")
            .append("    public static volatile int ").append(ERROR_EPOCH_FIELD).append(" = 1;\n")
            .append("    public static final java.util.concurrent.atomic.AtomicInteger ").append(CLAIMED_FIELD)
            .append(" = new java.util.concurrent.atomic.AtomicInteger();\n")
            .append("    public static final java.util.concurrent.atomic.AtomicInteger ").append(ERROR_CLAIMED_FIELD)
            .append(" = new java.util.concurrent.atomic.AtomicInteger();\n")
            .append("    public static final java.util.concurrent.atomic.AtomicLong ").append(TRUE_HITS_FIELD)
            .append(" = new java.util.concurrent.atomic.AtomicLong();\n")
            .append("    public static final java.util.concurrent.atomic.AtomicLong ").append(ERRORS_FIELD)
            .append(" = new java.util.concurrent.atomic.AtomicLong();\n")
            .append("    public static final java.util.concurrent.atomic.AtomicLong ").append(STOPS_FIELD)
            .append(" = new java.util.concurrent.atomic.AtomicLong();\n");
        FastLogpointProbeSource.appendCommonFields(source, simpleName, bufferSize, metadata, ABOUT);
        source.append("    public static void ").append(HIT_METHOD).append('(')
            .append(FastLogpointProbeSource.declaredParameters(forwarded)).append(") {\n")
            .append("        if (!").append(ENABLED_FIELD).append(") {\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        final int __mcpEpoch = ").append(EPOCH_FIELD).append(";\n")
            // Read before the condition runs, like the true-hit generation: a failure of an evaluation
            // that started before a re-arm must not claim the error generation the re-arm opened.
            .append("        final int __mcpErrorEpoch = ").append(ERROR_EPOCH_FIELD).append(";\n")
            .append("        if (").append(ONCE_FIELD).append(" && ").append(CLAIMED_FIELD).append(".get() >= __mcpEpoch) {\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        final java.lang.Object __mcpPassed;\n")
            .append("        try {\n")
            .append("            __mcpPassed = __mcpCondition(").append(arguments).append(");\n")
            .append("        } catch (java.lang.Throwable __mcpFailure) {\n")
            .append("            __mcpError(__mcpFailure, null, __mcpErrorEpoch);\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        if (!(__mcpPassed instanceof java.lang.Boolean)) {\n")
            .append("            __mcpError(null, __mcpPassed, __mcpErrorEpoch);\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        if (!((java.lang.Boolean) __mcpPassed).booleanValue()) {\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        ").append(TRUE_HITS_FIELD).append(".incrementAndGet();\n")
            .append("        if (").append(ONCE_FIELD).append(" && !__mcpClaim(").append(CLAIMED_FIELD).append(", __mcpEpoch)) {\n")
            .append("            return;\n")
            .append("        }\n");
        if (capture != null) {
            // A clear or re-arm during the condition may already have superseded this stop.
            source.append("        if (!").append(ENABLED_FIELD).append(" || (").append(ONCE_FIELD)
                .append(" && ").append(EPOCH_FIELD).append(" != __mcpEpoch)) {\n")
                .append("            return;\n")
                .append("        }\n")
                .append("        final java.lang.String __mcpCaptured = __mcpCapture(").append(arguments).append(");\n");
        }
        source.append("        ").append(STOPS_FIELD).append(".incrementAndGet();\n")
            // Last statement: a step from the stop leaves hit() without running anything else of it.
            .append("        ").append(STOP_METHOD).append('(').append(STOP_CONDITION_TRUE).append(", __mcpEpoch, null");
        if (reportsCapture) {
            source.append(", ").append(capture != null ? "__mcpCaptured" : compileFailure);
        }
        source.append(");\n    }\n\n")
            // Empty: the breakpoint sits on its only instruction, and a step into it enters nothing.
            .append("    public static void ").append(STOP_METHOD).append("(int kind, int epoch, java.lang.String error")
            .append(reportsCapture ? ", java.lang.String captured) {\n" : ") {\n")
            .append("    }\n\n")
            .append("    private static java.lang.Object __mcpCondition(").append(declaredParameters).append(") throws java.lang.Throwable {\n")
            .append(JdiExpressionEvaluator.generateMethodBody(condition))
            .append("    }\n\n")
            // Records a failed condition and stops on the first failure of error generation epoch, read
            // before the condition ran. Never throws: at worst the record is missing.
            .append("    private static void __mcpError(java.lang.Throwable failure, java.lang.Object result, int epoch) {\n")
            .append("        try {\n")
            .append("            ").append(ERRORS_FIELD).append(".incrementAndGet();\n")
            .append("            final java.lang.String text = failure != null ? __mcpDescribe(failure)\n")
            .append("                : \"condition did not evaluate to a boolean: \" + __mcpRender(result);\n")
            .append("            __mcpRecord('C', text);\n")
            .append("            if (__mcpClaim(").append(ERROR_CLAIMED_FIELD).append(", epoch)) {\n")
            .append("                ").append(STOPS_FIELD).append(".incrementAndGet();\n")
            .append("                ").append(STOP_METHOD).append('(').append(STOP_CONDITION_ERROR).append(", epoch, text");
        if (reportsCapture) {
            source.append(", ").append(compileFailure == null ? "null" : compileFailure);
        }
        source.append(");\n")
            .append("            }\n")
            .append("        } catch (java.lang.Throwable ignored) {\n")
            .append("        }\n")
            .append("    }\n\n")
            .append("    private static boolean __mcpClaim(java.util.concurrent.atomic.AtomicInteger gate, int epoch) {\n")
            .append("        for (int seen = gate.get(); seen < epoch; seen = gate.get()) {\n")
            .append("            if (gate.compareAndSet(seen, epoch)) {\n")
            .append("                return true;\n")
            .append("            }\n")
            .append("        }\n")
            .append("        return false;\n")
            .append("    }\n\n");
        if (capture != null) {
            appendCapture(source, "private static java.lang.String __mcpCapture", parameters, capture);
        }
        FastLogpointProbeSource.appendRecorder(source);
        return source.append("}\n").toString();
    }

    /**
     * Appends the capture evaluator shared by the arming wrapper and the installed probe.
     * Both classes supply the fast-logpoint value and failure renderers.
     */
    static void appendCapture(StringBuilder source, String declaration,
                              List<FastLogpointProbeSource.Parameter> parameters, String capture) {
        final List<FastLogpointProbeSource.Parameter> forwarded = FastLogpointProbeSource.forwardedParameters(parameters);
        source.append("    ").append(declaration).append('(')
            .append(FastLogpointProbeSource.declaredParameters(forwarded)).append(") {\n")
            .append("        try {\n")
            .append("            return __mcpBoundCapture(__mcpRender(__mcpCaptureExpression(")
            .append(FastLogpointProbeSource.arguments(forwarded)).append(")));\n")
            .append("        } catch (java.lang.Throwable __mcpFailure) {\n")
            .append("            try {\n")
            .append("                return __mcpBoundCapture(").append(literal(CAPTURE_FAILURE_PREFIX))
            .append(" + __mcpDescribe(__mcpFailure));\n")
            .append("            } catch (java.lang.Throwable ignored) {\n")
            .append("                return \"capture failed: failure text could not be rendered\";\n")
            .append("            }\n")
            .append("        }\n")
            .append("    }\n\n")
            .append("    private static java.lang.Object __mcpCaptureExpression(")
            .append(FastLogpointProbeSource.declaredParameters(parameters)).append(") throws java.lang.Throwable {\n")
            .append(JdiExpressionEvaluator.generateMethodBody(capture))
            .append("    }\n\n")
            .append("    private static java.lang.String __mcpBoundCapture(java.lang.String text) {\n")
            .append("        int end = ").append(FastLogpointProbeSource.MAX_METADATA_CHARS).append(";\n")
            .append("        if (text.length() <= end) {\n")
            .append("            return text;\n")
            .append("        }\n")
            .append("        if (java.lang.Character.isHighSurrogate(text.charAt(end - 1))) {\n")
            .append("            end--;\n")
            .append("        }\n")
            .append("        return text.substring(0, end) + ").append(literal(FastLogpointProbeSource.TRUNCATION_MARKER)).append(";\n")
            .append("    }\n\n");
    }

    /**
     * Formats a debugger-side arming capture failure with the same prefix and limit as the probe.
     */
    public static String captureFailure(String reason) {
        return FastLogpointProbeSource.boundText(CAPTURE_FAILURE_PREFIX + reason);
    }
}
