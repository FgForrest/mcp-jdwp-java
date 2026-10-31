package one.edee.mcp.jdwp.evaluation;

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
 * line and evaluates the condition there. Only when the condition holds (and its gate lets it
 * through) does it call {@code public static void stop(int kind, int epoch, String error)}, an
 * empty method the server puts its JDI breakpoint on. A method that holds a breakpoint is never
 * compiled or inlined by HotSpot, so the breakpoint lives in {@code stop()}, not in {@code hit()} or
 * the instrumented method: both stay eligible for JIT compilation and inlining, and a false
 * condition never involves the debugger.
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
    private static final String ABOUT = "Fast-breakpoint probe injected by mcp-jdwp-java (jdwp_set_fast_breakpoint): "
        + "MCP_TARGET_CLASS calls hit() at MCP_TARGET_LINE, which calls stop() when MCP_CONDITION holds; the "
        + "debugger's breakpoint sits on stop(). Without that debugger stop() does nothing. Setting ENABLED to false "
        + "stops it; attaching mcp-jdwp-java again lists it and can restore the original class.";

    private FastBreakpointProbeSource() {
    }

    /**
     * Generates the probe source.
     *
     * @param className  binary name of the probe class (its package becomes the package declaration)
     * @param parameters the {@code hit} parameters in order — the receiver as {@code _this} plus the
     *                   locals visible at the instrumented line, with wrapper-reachable types
     * @param condition  the condition to compile (possibly rewritten); {@code { block }} syntax is supported
     * @param stopOnce   whether only the first true hit of an epoch stops
     * @param bufferSize capacity of the condition-failure ring, a power of two
     * @param metadata   what the probe records about itself for later sessions
     */
    public static String generate(String className, List<FastLogpointProbeSource.Parameter> parameters,
                                  String condition, boolean stopOnce, int bufferSize,
                                  FastLogpointProbeSource.Metadata metadata) {
        FastLogpointProbeSource.requirePowerOfTwo(bufferSize);
        final int lastDot = className.lastIndexOf('.');
        final String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);
        final String simpleName = lastDot < 0 ? className : className.substring(lastDot + 1);
        final String declaredParameters = FastLogpointProbeSource.declaredParameters(parameters);
        final String arguments = FastLogpointProbeSource.arguments(parameters);

        final StringBuilder source = new StringBuilder();
        FastLogpointProbeSource.appendClassStart(source, packageName, simpleName, "jdwp_set_fast_breakpoint");
        source.append("    public static final String ").append(KIND_FIELD).append(" = ").append(literal(KIND)).append(";\n")
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
        source.append("    public static void ").append(HIT_METHOD).append('(').append(declaredParameters).append(") {\n")
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
            .append("        final Object __mcpPassed;\n")
            .append("        try {\n")
            .append("            __mcpPassed = __mcpCondition(").append(arguments).append(");\n")
            .append("        } catch (Throwable __mcpFailure) {\n")
            .append("            __mcpError(__mcpFailure, null, __mcpErrorEpoch);\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        if (!(__mcpPassed instanceof Boolean)) {\n")
            .append("            __mcpError(null, __mcpPassed, __mcpErrorEpoch);\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        if (!((Boolean) __mcpPassed).booleanValue()) {\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        ").append(TRUE_HITS_FIELD).append(".incrementAndGet();\n")
            .append("        if (").append(ONCE_FIELD).append(" && !__mcpClaim(").append(CLAIMED_FIELD).append(", __mcpEpoch)) {\n")
            .append("            return;\n")
            .append("        }\n")
            .append("        ").append(STOPS_FIELD).append(".incrementAndGet();\n")
            // Last statement: a step from the stop leaves hit() without running anything else of it.
            .append("        ").append(STOP_METHOD).append('(').append(STOP_CONDITION_TRUE).append(", __mcpEpoch, null);\n")
            .append("    }\n\n")
            // Empty: the breakpoint sits on its only instruction, and a step into it enters nothing.
            .append("    public static void ").append(STOP_METHOD).append("(int kind, int epoch, String error) {\n")
            .append("    }\n\n")
            .append("    private static Object __mcpCondition(").append(declaredParameters).append(") throws Throwable {\n")
            .append(JdiExpressionEvaluator.generateMethodBody(condition))
            .append("    }\n\n")
            // Records a failed condition and stops on the first failure of error generation epoch, read
            // before the condition ran. Never throws: at worst the record is missing.
            .append("    private static void __mcpError(Throwable failure, Object result, int epoch) {\n")
            .append("        try {\n")
            .append("            ").append(ERRORS_FIELD).append(".incrementAndGet();\n")
            .append("            final String text = failure != null ? __mcpDescribe(failure)\n")
            .append("                : \"condition did not evaluate to a boolean: \" + __mcpRender(result);\n")
            .append("            __mcpRecord('C', text);\n")
            .append("            if (__mcpClaim(").append(ERROR_CLAIMED_FIELD).append(", epoch)) {\n")
            .append("                ").append(STOPS_FIELD).append(".incrementAndGet();\n")
            .append("                ").append(STOP_METHOD).append('(').append(STOP_CONDITION_ERROR).append(", epoch, text);\n")
            .append("            }\n")
            .append("        } catch (Throwable ignored) {\n")
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
        FastLogpointProbeSource.appendRecorder(source);
        return source.append("}\n").toString();
    }
}
