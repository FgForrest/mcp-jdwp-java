package one.edee.mcp.jdwp.core;

/**
 * Hard limits that keep debugger snapshots bounded and safe for large scheduling objects.
 */
public record SnapshotLimits(int maxDepth, int maxItems, int maxStringLength) {
    public static final SnapshotLimits DEFAULT = new SnapshotLimits(2, 20, 2_000);

    public SnapshotLimits {
        if (maxDepth < 0 || maxDepth > 10) {
            throw new IllegalArgumentException("maxDepth must be between 0 and 10");
        }
        if (maxItems < 1 || maxItems > 1_000) {
            throw new IllegalArgumentException("maxItems must be between 1 and 1000");
        }
        if (maxStringLength < 16 || maxStringLength > 1_000_000) {
            throw new IllegalArgumentException("maxStringLength must be between 16 and 1000000");
        }
    }
}
