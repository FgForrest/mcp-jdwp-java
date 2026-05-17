package one.edee.mcp.jdwp.watchers;

import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CRUD service for {@link Watcher} instances. Watchers are stored MCP-side (not in the target JVM)
 * and evaluated on demand by `JDWPTools.jdwp_evaluate_watchers` using
 * {@link JdiExpressionEvaluator}.
 * <p>
 * Two indexes are maintained for efficient lookup:
 * - {@link #watchersById} — primary, by watcher UUID for direct CRUD.
 * - {@link #watchersByBreakpoint} — secondary, by breakpoint id for evaluation triggered by a hit.
 * <p>
 * The secondary index is auto-cleaned: when a breakpoint's watcher list becomes empty its entry is
 * removed, so {@link #getStats()}'s `breakpointsWithWatchers` count never drifts.
 * <p>
 * Lifecycle: cleared by `jdwp_reset`, `jdwp_disconnect`, and `jdwp_clear(types="watcher")`. Entries are
 * also auto-removed when their parent breakpoint is cleared (`JDWPTools.jdwp_clear_breakpoint`
 * calls {@link #deleteWatchersForBreakpoint}).
 * <p>
 * Thread-safety: maps are {@link ConcurrentHashMap}; the public mutators are `synchronized` so
 * the dual-index updates remain atomic against concurrent reads.
 */
@Service
public class WatcherManager {

    /**
     * Primary index: watcher UUID to {@link Watcher}.
     */
    private final Map<String, Watcher> watchersById = new ConcurrentHashMap<>();

    /**
     * Secondary index: breakpoint id to its watcher list. Empty lists are removed eagerly so
     * {@link #getStats()}'s `breakpointsWithWatchers` count stays accurate.
     */
    private final Map<Integer, List<Watcher>> watchersByBreakpoint = new ConcurrentHashMap<>();

    /**
     * Creates and registers a new watcher.
     *
     * @param label        User-friendly description
     * @param breakpointId JDWP breakpoint request ID
     * @param expression   Java expression to evaluate
     * @return The unique ID of the created watcher
     */
    public synchronized String createWatcher(String label, int breakpointId, String expression) {
        final Watcher watcher = new Watcher(label, breakpointId, expression);

        // Add to primary index
        watchersById.put(watcher.getId(), watcher);

        // Add to secondary index
        watchersByBreakpoint
            .computeIfAbsent(breakpointId, k -> new ArrayList<>())
            .add(watcher);

        return watcher.getId();
    }

    /**
     * Retrieves a watcher by its unique ID.
     *
     * @param watcherId UUID of the watcher
     * @return The watcher, or null if not found
     */
    @Nullable
    public Watcher getWatcher(String watcherId) {
        return watchersById.get(watcherId);
    }

    /**
     * Retrieves all watchers attached to a specific breakpoint.
     *
     * @param breakpointId JDWP breakpoint request ID
     * @return List of watchers (empty if none attached)
     */
    public List<Watcher> getWatchersForBreakpoint(int breakpointId) {
        final List<Watcher> list = watchersByBreakpoint.get(breakpointId);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    /**
     * Retrieves all watchers.
     *
     * @return List of all registered watchers
     */
    public List<Watcher> getAllWatchers() {
        return new ArrayList<>(watchersById.values());
    }

    /**
     * Deletes a watcher by its unique ID.
     *
     * @param watcherId UUID of the watcher to delete
     * @return true if deleted, false if not found
     */
    public synchronized boolean deleteWatcher(String watcherId) {
        final Watcher watcher = watchersById.remove(watcherId);
        if (watcher == null) {
            return false;
        }

        // Remove from secondary index
        final List<Watcher> breakpointWatchers = watchersByBreakpoint.get(watcher.getBreakpointId());
        if (breakpointWatchers != null) {
            breakpointWatchers.removeIf(w -> w.getId().equals(watcherId));
            if (breakpointWatchers.isEmpty()) {
                watchersByBreakpoint.remove(watcher.getBreakpointId());
            }
        }

        return true;
    }

    /**
     * Deletes all watchers attached to a specific breakpoint.
     *
     * @param breakpointId JDWP breakpoint request ID
     * @return Number of watchers deleted
     */
    public synchronized int deleteWatchersForBreakpoint(int breakpointId) {
        final List<Watcher> watchers = watchersByBreakpoint.remove(breakpointId);
        if (watchers == null || watchers.isEmpty()) {
            return 0;
        }

        // Remove from primary index
        watchers.forEach(w -> watchersById.remove(w.getId()));

        return watchers.size();
    }

    /**
     * Clears all watchers.
     */
    public synchronized void clearAll() {
        watchersById.clear();
        watchersByBreakpoint.clear();
    }

    /**
     * Gets statistics about registered watchers.
     *
     * @return Map with statistics (totalWatchers, breakpointsWithWatchers, etc.)
     */
    public Map<String, Object> getStats() {
        final Map<String, Object> stats = new HashMap<>();
        stats.put("totalWatchers", watchersById.size());
        stats.put("breakpointsWithWatchers", watchersByBreakpoint.size());
        stats.put("avgWatchersPerBreakpoint",
            watchersByBreakpoint.isEmpty() ? 0.0 :
                (double) watchersById.size() / watchersByBreakpoint.size());
        return stats;
    }
}
