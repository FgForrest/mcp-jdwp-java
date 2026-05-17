package one.edee.mcp.jdwp.marks;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Central registry of synthetic binding names reserved by the MCP server. A user-supplied mark
 * label cannot collide with any of these or it would shadow / be shadowed by the auto-injected
 * binding the listener already produces for that event kind.
 * <p>
 * The set is the union of every name that ends up in an {@code extraBindings} map passed to
 * {@link one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator#evaluate}:
 * <ul>
 *   <li>{@code exception} — bound to the thrown {@code Throwable} at exception logpoints</li>
 *   <li>{@code oldValue}, {@code newValue}, {@code object}, {@code fieldName}, {@code mode}
 *       — bound at field watchpoint events</li>
 *   <li>{@code _this} — the rewritten {@code this} keyword used by every frame evaluation</li>
 * </ul>
 * Stored WITHOUT the {@code $} sigil so the validation paths can be expressed once for the bare
 * identifier — sigil handling lives at the binding callsite.
 */
public final class ReservedBindings {

    /**
     * Names that MUST NOT be used as user-supplied mark labels. See class javadoc for provenance.
     */
    public static final Set<String> RESERVED_LABELS = Set.of(
        "exception",
        "oldValue",
        "newValue",
        "object",
        "fieldName",
        "mode",
        "_this"
    );

    /**
     * Java identifier rule used to validate mark labels. The label becomes a parameter name in the
     * generated wrapper class, so anything that is not a legal Java identifier would fail to compile.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    /**
     * Java language reserved words. Using one as a label produces a wrapper that does not compile,
     * which surfaces to the agent as an opaque JDT error — reject up front instead.
     */
    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while",
        "true", "false", "null", "var", "yield", "record", "sealed", "non-sealed", "permits"
    );

    private ReservedBindings() {
    }

    /**
     * Validates a mark label, throwing {@link IllegalArgumentException} with a descriptive message
     * on any rule violation. Accepted labels are non-blank Java identifiers that are neither a Java
     * keyword nor one of the {@link #RESERVED_LABELS}.
     *
     * @param label candidate label (without the {@code $} sigil)
     * @throws IllegalArgumentException when {@code label} is unusable
     */
    public static void requireValidLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Mark label must not be blank.");
        }
        if (!IDENTIFIER.matcher(label).matches()) {
            throw new IllegalArgumentException(
                "Mark label '" + label + "' is not a valid Java identifier. "
                    + "Use letters, digits, and underscores; must not start with a digit.");
        }
        if (JAVA_KEYWORDS.contains(label)) {
            throw new IllegalArgumentException(
                "Mark label '" + label + "' is a Java reserved word and cannot be used.");
        }
        if (RESERVED_LABELS.contains(label)) {
            throw new IllegalArgumentException(
                "Mark label '" + label + "' is reserved by the MCP server (auto-injected at "
                    + "exception / field watchpoint events). Pick a different name.");
        }
    }
}
