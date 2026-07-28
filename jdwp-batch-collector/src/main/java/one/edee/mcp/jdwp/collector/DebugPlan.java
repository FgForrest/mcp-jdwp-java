package one.edee.mcp.jdwp.collector;

import one.edee.mcp.jdwp.core.SnapshotLimits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON-serializable collection plan. Public fields intentionally keep the CLI format transparent.
 */
public final class DebugPlan {
    public String sessionId = "jdwp-collection";
    public Target target = new Target();
    public boolean resumeOnAttach = true;
    public long idleTimeoutMillis = 120_000;
    public int maxEvents = 100_000;
    public List<Tracepoint> tracepoints = new ArrayList<>();

    public void validate() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        target.validate();
        if (idleTimeoutMillis < 1_000) {
            throw new IllegalArgumentException("idleTimeoutMillis must be at least 1000");
        }
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        if (tracepoints == null || tracepoints.isEmpty()) {
            throw new IllegalArgumentException("at least one tracepoint is required");
        }
        Set<String> ids = new HashSet<>();
        for (Tracepoint tracepoint : tracepoints) {
            tracepoint.validate();
            if (!ids.add(tracepoint.id)) {
                throw new IllegalArgumentException("duplicate tracepoint id: " + tracepoint.id);
            }
        }
    }

    public static final class Target {
        public String host = "localhost";
        public int port = 5005;

        void validate() {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("target.host must not be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("target.port must be between 1 and 65535");
            }
        }
    }

    public static final class Tracepoint {
        public String id;
        public String className;
        public int line;
        public String methodName;
        public int maxHits = 10_000;
        public Capture capture = new Capture();

        void validate() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("tracepoint.id must not be blank");
            }
            if (className == null || className.isBlank()) {
                throw new IllegalArgumentException("tracepoint.className must not be blank: " + id);
            }
            if (line < 1) {
                throw new IllegalArgumentException("tracepoint.line must be positive: " + id);
            }
            if (maxHits < 1) {
                throw new IllegalArgumentException("tracepoint.maxHits must be positive: " + id);
            }
            if (capture == null) {
                capture = new Capture();
            }
            capture.validate(id);
        }
    }

    public static final class Capture {
        public boolean locals = true;
        public boolean stack = true;
        public int maxFrames = 8;
        public int maxDepth = SnapshotLimits.DEFAULT.maxDepth();
        public int maxItems = SnapshotLimits.DEFAULT.maxItems();
        public int maxStringLength = SnapshotLimits.DEFAULT.maxStringLength();

        void validate(String tracepointId) {
            if (maxFrames < 1 || maxFrames > 256) {
                throw new IllegalArgumentException("capture.maxFrames must be between 1 and 256: " + tracepointId);
            }
            new SnapshotLimits(maxDepth, maxItems, maxStringLength);
        }

        SnapshotLimits limits() {
            return new SnapshotLimits(maxDepth, maxItems, maxStringLength);
        }
    }
}
