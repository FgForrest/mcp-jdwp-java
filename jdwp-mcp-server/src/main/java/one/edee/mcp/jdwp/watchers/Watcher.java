package one.edee.mcp.jdwp.watchers;

import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;

import java.util.UUID;

/**
 * Immutable model for a watcher attached to a breakpoint. Watchers are MCP-side only — they are
 * NOT JDI requests; they live in {@link WatcherManager} and are evaluated on demand by
 * `jdwp_evaluate_watchers` whenever the parent breakpoint hits.
 * <p>
 * The `id` is a freshly-generated random UUID; watchers do NOT survive MCP server restarts because
 * the IDs aren't persisted anywhere. Each watcher carries a single Java expression evaluated by
 * {@link JdiExpressionEvaluator} against the breakpoint's stack frame.
 */
public class Watcher {
    /** Number of UUID characters shown in abbreviated watcher IDs. */
    public static final int DISPLAY_ID_LENGTH = 8;

    private final String id;
    private final String label;
    private final int breakpointId;
    private final String expression;

    /**
     * Creates a new watcher with a generated UUID.
     *
     * @param label        User-friendly description (e.g., "Trace entity ID")
     * @param breakpointId JDWP breakpoint request ID this watcher is attached to
     * @param expression   Java expression to evaluate (e.g., "entity.id", "users.stream().count()")
     */
    public Watcher(String label, int breakpointId, String expression) {
        this.id = UUID.randomUUID().toString();
        this.label = label;
        this.breakpointId = breakpointId;
        this.expression = expression;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public int getBreakpointId() {
        return breakpointId;
    }

    public String getExpression() {
        return expression;
    }

    @Override
    public String toString() {
        return String.format("Watcher{id='%s', label='%s', breakpointId=%d, expression='%s'}",
            id, label, breakpointId, expression);
    }
}
