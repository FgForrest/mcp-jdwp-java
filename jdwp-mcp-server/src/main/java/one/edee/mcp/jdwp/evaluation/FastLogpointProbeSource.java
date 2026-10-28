package one.edee.mcp.jdwp.evaluation;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates the Java source of a fast-logpoint probe — the class that {@code jdwp_set_fast_logpoint}
 * defines in the target VM and calls from the instrumented line.
 * <p>
 * The probe's {@code public static void hit(...)} runs on the application thread that executes
 * the line. It evaluates the optional condition and then the expression, and appends one entry to
 * a lock-free ring buffer held in its own static fields. The MCP server reads that buffer through
 * plain JDWP field and array reads, without suspending any thread:
 * <ul>
 *   <li>{@value #ENABLED_FIELD} — kill switch. Starts {@code false}; the server sets it to
 *       {@code true} once the instrumentation is installed and recorded, and back to {@code false}
 *       before it restores the original bytecode, and on a failed disarm.</li>
 *   <li>{@value #SEQ_FIELD} — {@code AtomicLong} holding the number of entries ever written.</li>
 *   <li>{@value #RING_FIELD} — {@code String[]} of power-of-two length; entry {@code s} lives in
 *       slot {@code s & (length - 1)}.</li>
 * </ul>
 * <b>Registry.</b> The probe also describes itself, so a later debugging session can find it after
 * the session that armed it died without restoring the class: {@code MCP_*} compile-time constants
 * name the target, the expression and the arming session ({@link Metadata}), and
 * {@value #IMAGE_FIELD} holds the target class's current install record (original class file plus
 * the bytecode installed with this probe). {@value #FORMAT_FIELD} and {@value #ENABLED_FIELD} keep
 * their meaning in every later format.
 * Each entry is one immutable string — {@code seq, epochMillis, nanoTime, kind, thread, value}
 * separated by a NUL character — so a single array store publishes it atomically
 * and the embedded sequence number tells the reader whether a slot was overwritten. {@code kind} is
 * {@code V} (value), {@code E} (the expression threw) or {@code C} (the condition threw or did not
 * yield a boolean).
 * <p>
 * Nothing the user code throws propagates into the application: every {@link Throwable} is caught
 * and recorded as an error entry. Results are rendered without calling {@code toString()} on
 * arbitrary objects — strings, boxed primitives, {@code BigDecimal} / {@code BigInteger} (exact
 * classes, not subclasses) and enums print their value, anything else prints as
 * {@code type@identityHash}; a thrown application exception is recorded by class name only (JDK
 * exceptions with their message) — so the probe never runs user code the expression did not ask for.
 */
public final class FastLogpointProbeSource {
    /**
     * Simple-name prefix of every probe class; the full name is {@code <package>.<prefix><UUID>}.
     */
    public static final String CLASS_PREFIX = "McpFastLogpoint_";
    /**
     * Name of the static method the instrumented line calls.
     */
    public static final String HIT_METHOD = "hit";
    /**
     * Static {@code volatile boolean} kill switch.
     */
    public static final String ENABLED_FIELD = "ENABLED";
    /**
     * Static {@code AtomicLong} counting written entries.
     */
    public static final String SEQ_FIELD = "SEQ";
    /**
     * Static {@code String[]} ring buffer.
     */
    public static final String RING_FIELD = "RING";
    /**
     * Separator between the fields of one ring entry.
     */
    public static final char SEPARATOR = '\0';
    /**
     * Static {@code int} constant: the probe layout version.
     */
    public static final String FORMAT_FIELD = "MCP_FORMAT";
    /**
     * The layout this class generates.
     */
    public static final int FORMAT = 1;
    /**
     * Static {@code volatile byte[]}: the target class's install record while the class calls the
     * probe; {@code null} otherwise.
     */
    public static final String IMAGE_FIELD = "MCP_IMAGE";
    /**
     * Metadata constant names; see {@link Metadata}.
     */
    public static final String TARGET_CLASS_FIELD = "MCP_TARGET_CLASS";
    public static final String TARGET_METHOD_FIELD = "MCP_TARGET_METHOD";
    public static final String TARGET_DESCRIPTOR_FIELD = "MCP_TARGET_DESCRIPTOR";
    public static final String TARGET_LINE_FIELD = "MCP_TARGET_LINE";
    public static final String EXPRESSION_FIELD = "MCP_EXPRESSION";
    public static final String CONDITION_FIELD = "MCP_CONDITION";
    public static final String ARMED_AT_FIELD = "MCP_ARMED_AT";
    public static final String ARMED_BY_FIELD = "MCP_ARMED_BY";
    public static final String ABOUT_FIELD = "MCP_ABOUT";
    /**
     * Longest metadata string kept verbatim; longer text is cut and marked. Even at three bytes per
     * char this stays far below the 65,535-byte limit of a class-file string constant.
     */
    public static final int MAX_METADATA_CHARS = 2048;
    /**
     * Appended to metadata cut at {@link #MAX_METADATA_CHARS}.
     */
    public static final String TRUNCATION_MARKER = "…[truncated]";
    private static final String ABOUT = "Fast-logpoint probe injected by mcp-jdwp-java (jdwp_set_fast_logpoint): "
        + "MCP_TARGET_CLASS calls hit() at MCP_TARGET_LINE. Setting ENABLED to false stops it; attaching "
        + "mcp-jdwp-java again lists it and can restore the original class.";

    private FastLogpointProbeSource() {
    }

    /**
     * Generates the probe source.
     *
     * @param className  binary name of the probe class (its package becomes the package declaration)
     * @param parameters the {@code hit} parameters in order — the receiver as {@code _this} plus the
     *                   locals visible at the instrumented line, with wrapper-reachable types
     * @param expression the expression to compile (possibly rewritten); {@code { block }} syntax is supported
     * @param condition  optional condition to compile; {@code { block }} syntax is supported
     * @param bufferSize ring-buffer capacity, a power of two
     * @param metadata   what the probe records about itself for later sessions
     */
    public static String generate(String className, List<Parameter> parameters, String expression,
                                  @Nullable String condition, int bufferSize, Metadata metadata) {
        if (bufferSize <= 0 || Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of two, got " + bufferSize);
        }
        final int lastDot = className.lastIndexOf('.');
        final String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);
        final String simpleName = lastDot < 0 ? className : className.substring(lastDot + 1);

        final String declaredParameters = parameters.stream()
            .map(p -> p.type() + ' ' + p.name())
            .collect(Collectors.joining(", "));
        final String arguments = parameters.stream()
            .map(Parameter::name)
            .collect(Collectors.joining(", "));

        final StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append("// Automatically generated by mcp-jdwp-java for jdwp_set_fast_logpoint\n")
            .append("public final class ").append(simpleName).append(" {\n")
            .append("    public static volatile boolean ").append(ENABLED_FIELD).append(" = false;\n")
            .append("    public static volatile byte[] ").append(IMAGE_FIELD).append(";\n")
            .append("    public static final int ").append(FORMAT_FIELD).append(" = ").append(FORMAT).append(";\n")
            .append("    public static final String ").append(TARGET_CLASS_FIELD).append(" = ")
            .append(literal(metadata.targetClass())).append(";\n")
            .append("    public static final String ").append(TARGET_METHOD_FIELD).append(" = ")
            .append(literal(metadata.targetMethod())).append(";\n")
            .append("    public static final String ").append(TARGET_DESCRIPTOR_FIELD).append(" = ")
            .append(literal(metadata.targetDescriptor())).append(";\n")
            .append("    public static final int ").append(TARGET_LINE_FIELD).append(" = ")
            .append(metadata.targetLine()).append(";\n")
            .append("    public static final String ").append(EXPRESSION_FIELD).append(" = ")
            .append(literal(metadata.expression())).append(";\n")
            .append("    public static final String ").append(CONDITION_FIELD).append(" = ")
            .append(literal(metadata.condition() != null ? metadata.condition() : "")).append(";\n")
            .append("    public static final long ").append(ARMED_AT_FIELD).append(" = ")
            .append(metadata.armedAt()).append("L;\n")
            .append("    public static final String ").append(ARMED_BY_FIELD).append(" = ")
            .append(literal(metadata.armedBy())).append(";\n")
            .append("    public static final String ").append(ABOUT_FIELD).append(" = ").append(literal(ABOUT)).append(";\n")
            .append("    public static final java.util.concurrent.atomic.AtomicLong ").append(SEQ_FIELD)
            .append(" = new java.util.concurrent.atomic.AtomicLong();\n")
            .append("    public static final String[] ").append(RING_FIELD)
            .append(" = new String[").append(bufferSize).append("];\n")
            .append("    private static final long __MCP_MASK = ").append(bufferSize - 1).append("L;\n\n")
            .append("    private ").append(simpleName).append("() {\n    }\n\n")
            .append("    public static void ").append(HIT_METHOD).append('(').append(declaredParameters).append(") {\n")
            .append("        if (!").append(ENABLED_FIELD).append(") {\n")
            .append("            return;\n")
            .append("        }\n");
        if (condition != null) {
            source.append("        try {\n")
                .append("            final Object __mcpPassed = __mcpCondition(").append(arguments).append(");\n")
                .append("            if (!(__mcpPassed instanceof Boolean)) {\n")
                .append("                __mcpRecord('C', \"condition did not evaluate to a boolean: \" + __mcpRender(__mcpPassed));\n")
                .append("                return;\n")
                .append("            }\n")
                .append("            if (!((Boolean) __mcpPassed).booleanValue()) {\n")
                .append("                return;\n")
                .append("            }\n")
                .append("        } catch (Throwable __mcpFailure) {\n")
                .append("            __mcpRecordFailure('C', __mcpFailure);\n")
                .append("            return;\n")
                .append("        }\n");
        }
        source.append("        try {\n")
            .append("            __mcpRecord('V', __mcpRender(__mcpExpression(").append(arguments).append(")));\n")
            .append("        } catch (Throwable __mcpFailure) {\n")
            .append("            __mcpRecordFailure('E', __mcpFailure);\n")
            .append("        }\n")
            .append("    }\n\n");
        if (condition != null) {
            source.append("    private static Object __mcpCondition(").append(declaredParameters).append(") {\n")
                .append(JdiExpressionEvaluator.generateMethodBody(condition))
                .append("    }\n\n");
        }
        source.append("    private static Object __mcpExpression(").append(declaredParameters).append(") {\n")
            .append(JdiExpressionEvaluator.generateMethodBody(expression))
            .append("    }\n\n")
            // Recording a failure must not fail in turn (e.g. OutOfMemoryError while concatenating).
            // JDK throwables print their message; an application's own Throwable may override
            // toString()/getMessage(), so only its class name is recorded.
            .append("    private static void __mcpRecordFailure(char kind, Throwable failure) {\n")
            .append("        try {\n")
            .append("            final String type = failure.getClass().getName();\n")
            .append("            __mcpRecord(kind, type.startsWith(\"java.\") || type.startsWith(\"javax.\")\n")
            .append("                ? String.valueOf(failure) : type + \" (message not rendered: application exception type)\");\n")
            .append("        } catch (Throwable ignored) {\n")
            .append("        }\n")
            .append("    }\n\n")
            .append("    private static void __mcpRecord(char kind, String value) {\n")
            .append("        final long seq = ").append(SEQ_FIELD).append(".getAndIncrement();\n")
            .append("        ").append(RING_FIELD).append("[(int) (seq & __MCP_MASK)] = seq + \"\\0\" + System.currentTimeMillis()")
            .append(" + \"\\0\" + System.nanoTime() + \"\\0\" + kind + \"\\0\" + Thread.currentThread().getName()")
            .append(" + \"\\0\" + value;\n")
            .append("    }\n\n")
            .append("    private static String __mcpRender(Object value) {\n")
            .append("        if (value == null) {\n")
            .append("            return \"null\";\n")
            .append("        }\n")
            // Exact JDK value types only: an application subclass of Number could override toString().
            .append("        final Class<?> type = value.getClass();\n")
            .append("        if (type == String.class || type == Integer.class || type == Long.class || type == Short.class\n")
            .append("            || type == Byte.class || type == Double.class || type == Float.class || type == Boolean.class\n")
            .append("            || type == Character.class || type == java.math.BigDecimal.class\n")
            .append("            || type == java.math.BigInteger.class) {\n")
            .append("            return String.valueOf(value);\n")
            .append("        }\n")
            .append("        if (value instanceof Enum) {\n")
            .append("            return ((Enum<?>) value).name();\n")
            .append("        }\n")
            .append("        return type.getName() + '@' + Integer.toHexString(System.identityHashCode(value));\n")
            .append("    }\n")
            .append("}\n");
        return source.toString();
    }

    /**
     * Renders {@code text} as a Java string literal, cut at {@link #MAX_METADATA_CHARS}. Backslashes
     * are escaped first, so a backslash-u sequence in user text cannot become a Unicode escape (which
     * the compiler translates before lexing); control characters become three-digit octal escapes, so
     * a following digit cannot extend them.
     */
    static String literal(String text) {
        String bounded = text;
        if (text.length() > MAX_METADATA_CHARS) {
            int end = MAX_METADATA_CHARS;
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            bounded = text.substring(0, end) + TRUNCATION_MARKER;
        }
        final StringBuilder out = new StringBuilder(bounded.length() + 2).append('"');
        for (int i = 0; i < bounded.length(); i++) {
            final char c = bounded.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '"') {
                out.append("\\\"");
            } else if (c < 0x20 || c == 0x7F) {
                out.append('\\').append((char) ('0' + (c >> 6))).append((char) ('0' + ((c >> 3) & 7)))
                    .append((char) ('0' + (c & 7)));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * One {@code hit} parameter.
     *
     * @param name {@code _this} or a local-variable name
     * @param type a source type name the probe can reference
     */
    public record Parameter(String name, String type) {
    }

    /**
     * What a probe records about itself, as compile-time constants, for a later debugging session.
     *
     * @param targetClass      binary name of the instrumented class
     * @param targetMethod     name of the instrumented method
     * @param targetDescriptor JVM descriptor of the instrumented method
     * @param targetLine       source line of the call
     * @param expression       the user's expression as entered
     * @param condition        the user's condition as entered, or {@code null}
     * @param armedAt          epoch millis when the probe was generated
     * @param armedBy          the arming server: product and an opaque session ID (no host, no PID)
     */
    public record Metadata(String targetClass, String targetMethod, String targetDescriptor, int targetLine,
                           String expression, @Nullable String condition, long armedAt, String armedBy) {
    }
}
