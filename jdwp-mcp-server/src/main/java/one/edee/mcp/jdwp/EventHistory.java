package one.edee.mcp.jdwp;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded ring buffer of debug events captured from the JDI event queue. Records every interesting
 * event for post-mortem inspection via the `jdwp_get_events` tool.
 * <p>
 * Behaviour:
 * - FIFO ring buffer with a fixed cap of {@link #MAX_EVENTS} entries; oldest entries are dropped first.
 * - Thread-safe via {@link ConcurrentLinkedDeque}; the producer ({@link JdiEventListener}) and consumers
 * (MCP tool calls on worker threads) never block each other.
 * - Cleared on `jdwp_reset`, `jdwp_clear_events`, and {@link JDIConnectionService#cleanupSessionState}.
 * <p>
 * Documented event type strings: `BREAKPOINT`, `STEP`, `EXCEPTION`, `EXCEPTION_LOG`,
 * `EXCEPTION_LOG_ERROR`, `LOGPOINT`, `LOGPOINT_ERROR`, `FIELD_ACCESS`, `FIELD_MODIFICATION`,
 * `FIELD_LOGPOINT`, `FIELD_LOGPOINT_ERROR`, `BREAKPOINT_SUPPRESSED`, `EXCEPTION_SUPPRESSED`,
 * `FIELD_BREAKPOINT_SUPPRESSED`, `CHAIN_ARMED`, `CHAIN_DISARMED`, `CHAIN_BROKEN`, `VM_START`,
 * `VM_DEATH`. These are the keys clients can grep on or filter by.
 */
@Service
public class EventHistory {

    /**
     * Hard cap on retained events; not configurable by design (the buffer is for human-readable history, not telemetry).
     */
    private static final int MAX_EVENTS = 500;
    private final Deque<DebugEvent> events = new ConcurrentLinkedDeque<>();

    /**
     * Appends an event and evicts the oldest entries until the buffer is at or below {@link #MAX_EVENTS}.
     * Called exclusively from {@link JdiEventListener} on the JDI event listener thread; non-blocking.
     */
    public void record(DebugEvent event) {
        events.addLast(event);
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }

    /**
     * Returns up to `count` newest events as a snapshot copy. May return fewer entries if the buffer
     * holds less than `count`. The returned list is a detached copy — safe to iterate without
     * holding any locks and unaffected by concurrent {@link #record} calls.
     */
    public List<DebugEvent> getRecent(int count) {
        final List<DebugEvent> all = new ArrayList<>(events);
        final int from = Math.max(0, all.size() - count);
        return all.subList(from, all.size());
    }

    public void clear() {
        events.clear();
    }

    public int size() {
        return events.size();
    }

    /**
     * A single debug event captured from the JDI event queue or logpoint evaluation. The `type`
     * field is one of the documented strings on the enclosing class. The `details` map carries
     * structured key/value data consumed by the MCP client (e.g., breakpoint location, exception
     * message, logpoint value).
     * <p>
     * The convenience constructors default `timestamp` to `Instant.now()` and (for the two-arg form)
     * `details` to an empty map.
     */
    public record DebugEvent(Instant timestamp, String type, String summary, Map<String, String> details) {
        public DebugEvent(String type, String summary) {
            this(Instant.now(), type, summary, Map.of());
        }

        public DebugEvent(String type, String summary, Map<String, String> details) {
            this(Instant.now(), type, summary, details);
        }
    }
}
