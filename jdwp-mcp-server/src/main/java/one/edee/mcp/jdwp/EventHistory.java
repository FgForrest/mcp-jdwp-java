package one.edee.mcp.jdwp;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded ring buffer of debug events for post-mortem inspection via the `jdwp_get_events` tool.
 * Most events originate from the JDI event queue drained by {@link JdiEventListener}, but it is NOT
 * the sole producer: synchronous install-time diagnostics are recorded from MCP worker threads too
 * (e.g. {@code BP_MULTI_LOCATION} from {@link JDWPTools}, {@code RECONNECT} from
 * {@link JDIConnectionService}).
 * <p>
 * Behaviour:
 * - FIFO ring buffer with a fixed cap of {@link #MAX_EVENTS} entries; oldest entries are dropped first.
 * - Thread-safe via {@link ConcurrentLinkedDeque}: the listener thread and the MCP worker threads may
 * record concurrently, and concurrent readers never block writers. Because there are multiple
 * producers, no global ordering is guaranteed beyond each individual {@code record} call.
 * - Cleared on `jdwp_reset`, `jdwp_clear_events`, and {@link JDIConnectionService#cleanupSessionState}.
 * <p>
 * Documented event type strings (exhaustive for production code — keep in sync; tests may record
 * ad-hoc types that are intentionally not listed here): `BREAKPOINT`, `BREAKPOINT_SUPPRESSED`, `STEP`, `STEP_SUPPRESSED`,
 * `EXCEPTION`, `EXCEPTION_SUPPRESSED`, `EXCEPTION_LOG`, `EXCEPTION_LOG_ERROR`, `LOGPOINT`,
 * `LOGPOINT_ERROR`, `CONDITION_ERROR`, `FIELD_ACCESS`, `FIELD_MODIFICATION`, `FIELD_BREAKPOINT_SUPPRESSED`,
 * `FIELD_LOGPOINT`, `FIELD_LOGPOINT_ERROR`, `CHAIN_ARMED`, `CHAIN_DISARMED`, `CHAIN_BROKEN`,
 * `CLASS_PREPARE`, `BP_MULTI_LOCATION`, `BP_PROMOTION_FAILED`, `RECONNECT`, `VM_START`, `VM_DEATH`,
 * `FAST_LOGPOINT`, `FAST_LOGPOINT_ERROR`, `FAST_LOGPOINT_DROPPED`, `FAST_LOGPOINT_ARMED`,
 * `FAST_LOGPOINT_ARM_FAILED`, `FAST_LOGPOINT_DISARMED`, `FAST_LOGPOINT_WARNING`, `FAST_LOGPOINT_RECOVERED`,
 * `FAST_LOGPOINT_RESTORED`.
 * These are the keys clients can grep on or filter by.
 */
@Service
public class EventHistory {

    /**
     * Hard cap on retained events; not configurable by design (the buffer is for human-readable history, not telemetry).
     */
    public static final int MAX_EVENTS = 500;
    private final Deque<DebugEvent> events = new ConcurrentLinkedDeque<>();

    /**
     * The session epoch stamped onto newly recorded events. Bumped by {@link #beginNewSession()} on
     * each successful VM attach. {@code volatile} for visibility to the listener thread; the
     * increment has a single writer (it runs only under the connection-service monitor in
     * {@link JDIConnectionService}), so a plain {@code ++} is safe.
     */
    private volatile int currentEpoch = 0;

    /**
     * Appends an event and evicts the oldest entries until the buffer is at or below {@link #MAX_EVENTS}.
     * Non-blocking, and safe to call from any thread: most records come from the JDI event listener
     * thread ({@link JdiEventListener}), but MCP worker threads also record install-time diagnostics
     * (e.g. {@code BP_MULTI_LOCATION}, {@code RECONNECT}). The backing {@link ConcurrentLinkedDeque}
     * tolerates these concurrent producers.
     *
     * <p>Events arrive {@link DebugEvent#UNSTAMPED} (producers don't know the live epoch) and are
     * stamped with {@link #currentEpoch} here, so a {@code VM_DEATH} preserved across an
     * auto-reconnect stays tagged with the old session while the fresh session's events get the new
     * epoch — letting {@code jdwp_get_events} segment them.
     */
    public void record(DebugEvent event) {
        if (SuspensionSession.buffer(event)) {
            // Appended when the set the dispatcher processes finishes (dropped if it went stale).
            return;
        }
        final DebugEvent stamped = event.sessionEpoch() == DebugEvent.UNSTAMPED
            ? event.withEpoch(currentEpoch)
            : event;
        events.addLast(stamped);
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }

    /**
     * Advances to a new session epoch and returns it. Called on each successful VM attach
     * (connect / reconnect) so events recorded afterwards are tagged as a distinct session.
     */
    public int beginNewSession() {
        return ++currentEpoch;
    }

    /** The session epoch currently stamped onto new events (0 before the first attach). */
    public int currentEpoch() {
        return currentEpoch;
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
    public record DebugEvent(Instant timestamp, String type, String summary, Map<String, String> details, int sessionEpoch) {

        /**
         * Sentinel epoch for an event constructed by a producer that doesn't know the live session
         * (the common case). {@link EventHistory#record} replaces it with the current epoch; a
         * caller that already carries a real epoch (e.g. a re-stamped copy) is left untouched.
         */
        public static final int UNSTAMPED = -1;

        public DebugEvent(String type, String summary) {
            this(Instant.now(), type, summary, Map.of(), UNSTAMPED);
        }

        public DebugEvent(String type, String summary, Map<String, String> details) {
            this(Instant.now(), type, summary, details, UNSTAMPED);
        }

        /** Returns a copy stamped with {@code epoch}; used by {@link EventHistory#record}. */
        DebugEvent withEpoch(int epoch) {
            return new DebugEvent(timestamp, type, summary, details, epoch);
        }
    }
}
