package one.edee.mcp.jdwp.marks;

import com.sun.jdi.ObjectReference;

/**
 * Snapshot of one marked instance for read-only consumers (overview rendering, locals footer,
 * unit tests). Captures the user-supplied label, the underlying JDI mirror, whether the registry
 * pinned the object in the target heap via {@code disableCollection()}, and a frozen
 * {@code collected} flag sampled at construction time.
 * <p>
 * Identity (the {@link ObjectReference}) is preserved so callers needing the live mirror can
 * still reach it; rendering helpers should prefer the convenience accessors and the
 * {@link #typeName()} / {@link #objectId()} pair so the rest of the MCP layer doesn't have to
 * import JDI types.
 */
public record MarkInfo(String label, ObjectReference reference, boolean pinned, boolean collected) {

    /**
     * Returns the JDI unique object ID for cross-tool reference — same value the object cache
     * uses as its primary key.
     *
     * @return JDI unique object ID for cross-tool reference
     */
    public long objectId() {
        return reference.uniqueID();
    }

    /**
     * Returns the fully-qualified runtime type name of the marked object, or
     * {@code "<unknown>"} when the mirror is no longer reachable.
     *
     * @return runtime type name, or {@code "<unknown>"} on a dead mirror
     */
    public String typeName() {
        try {
            return reference.referenceType().name();
        } catch (Exception e) {
            return "<unknown>";
        }
    }
}
