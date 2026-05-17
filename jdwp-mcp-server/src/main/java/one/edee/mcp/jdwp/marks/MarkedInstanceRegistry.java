package one.edee.mcp.jdwp.marks;

import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent-facing registry of "marked instances": user-chosen labels pinned to live JDI
 * {@link ObjectReference} mirrors. Each mark is exposed to expression evaluation as a
 * synthetic {@code $label} binding by {@link #buildBindings()}; every MCP evaluation
 * call site (conditional breakpoints AND logpoint / watcher / exception / field-logpoint
 * expressions) merges that map into its existing {@code extraBindings} before invoking
 * {@code JdiExpressionEvaluator.evaluate(...)}.
 * <p>
 * Lifecycle parallels the object cache in {@code JDIConnectionService}: marks live until
 * the underlying VM dies, the session is reset, or the agent explicitly unmarks. On mark,
 * the registry calls {@link ObjectReference#disableCollection()} so the target-heap object
 * cannot be reclaimed while the label is live — matches the IntelliJ "Mark Object" semantic
 * and means a label set at one breakpoint is still meaningful several events later. The
 * {@link #unmark}/{@link #clearAll} paths always re-enable collection so the registry cannot
 * leak target-heap pressure.
 * <p>
 * Thread-safety: the public mutators are {@code synchronized} so pin/unpin and map updates
 * stay atomic against each other; the read paths ({@link #get}, {@link #list},
 * {@link #buildBindings}) read the {@link ConcurrentHashMap} directly.
 */
@Service
public class MarkedInstanceRegistry {
    private static final Logger log = LoggerFactory.getLogger(MarkedInstanceRegistry.class);

    // Sigil prepended to a stored label to form the wrapper-method parameter name —
    // mirrors the convention already used by $exception / $oldValue / etc.
    private static final String SIGIL = "$";

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Registers a new mark under {@code label}. When {@code pin} is true the target-heap object
     * is pinned via {@link ObjectReference#disableCollection()} so the mark remains meaningful
     * across subsequent events even if the application drops every other reference.
     *
     * @throws IllegalArgumentException if the label is blank, not a Java identifier, a Java
     *                                  keyword, or one of the reserved binding names — see
     *                                  {@link ReservedBindings#requireValidLabel}
     * @throws IllegalStateException    if another mark already uses {@code label}; callers should
     *                                  {@link #unmark} or {@link #rename} first
     * @throws ObjectCollectedException if {@code reference} is already collected in the target VM
     */
    public synchronized void mark(String label, ObjectReference reference, boolean pin) {
        ReservedBindings.requireValidLabel(label);
        if (entries.containsKey(label)) {
            throw new IllegalStateException(
                "Mark '" + label + "' already exists. Unmark or rename it first.");
        }
        if (reference.isCollected()) {
            throw new ObjectCollectedException(
                "Cannot mark object " + reference.uniqueID() + " — it has already been collected in the target VM.");
        }
        boolean pinned = false;
        if (pin) {
            try {
                reference.disableCollection();
                pinned = true;
            } catch (ObjectCollectedException collectedDuringPin) {
                throw collectedDuringPin;
            } catch (Exception e) {
                // disableCollection() can also throw UnsupportedOperationException on a VM that
                // doesn't support it. Refuse to register a mark that wouldn't be enforceable —
                // a silent unpinned fallback would surprise the agent later.
                throw new IllegalStateException(
                    "Cannot pin object " + reference.uniqueID() + ": " + e.getMessage(), e);
            }
        }
        entries.put(label, new Entry(reference, pinned));
        log.debug("[Marks] Registered '{}' -> Object#{} (pinned={})",
            label, reference.uniqueID(), pinned);
    }

    /**
     * Removes the mark for {@code label}. If the underlying object was pinned the pin is released
     * via {@link ObjectReference#enableCollection()}. Best-effort on the unpin: a VM that died
     * between mark and unmark may throw, but the registry slot is always cleared so the label is
     * immediately available for reuse.
     *
     * @return true when a mark was removed, false when no mark existed under that label
     */
    public synchronized boolean unmark(String label) {
        final Entry removed = entries.remove(label);
        if (removed == null) {
            return false;
        }
        releasePin(label, removed);
        return true;
    }

    /**
     * Renames an existing mark. The pinned state of the underlying object is preserved without
     * an unpin/repin cycle. Throws if the source does not exist or the target name is invalid or
     * already taken.
     */
    public synchronized void rename(String oldLabel, String newLabel) {
        // Existence check runs FIRST so rename(ghost, ghost) on an unregistered label still rejects
        // — the same-label shortcut must not bypass validation and silently report success.
        final Entry existing = entries.get(oldLabel);
        if (existing == null) {
            throw new IllegalArgumentException("No mark named '" + oldLabel + "'.");
        }
        if (oldLabel.equals(newLabel)) {
            return;
        }
        ReservedBindings.requireValidLabel(newLabel);
        if (entries.containsKey(newLabel)) {
            throw new IllegalStateException(
                "Mark '" + newLabel + "' already exists. Unmark or rename it first.");
        }
        entries.put(newLabel, existing);
        entries.remove(oldLabel);
        log.debug("[Marks] Renamed '{}' -> '{}'", oldLabel, newLabel);
    }

    /**
     * Read-only snapshot of one mark, or {@code null} when no such label is registered.
     */
    @Nullable
    public MarkInfo get(String label) {
        final Entry entry = entries.get(label);
        return entry == null ? null : entry.toInfo(label);
    }

    /**
     * Read-only snapshot of every live mark. Insertion order is NOT guaranteed (backed by a
     * {@link ConcurrentHashMap}). Callers that need ordering should sort by {@link MarkInfo#label()}.
     */
    public List<MarkInfo> list() {
        final List<MarkInfo> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            out.add(e.getValue().toInfo(e.getKey()));
        }
        return out;
    }

    /**
     * Reports whether the registry holds zero marks.
     *
     * @return true if no marks are registered
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Reports the number of registered marks (including any whose underlying object has since
     * been collected).
     *
     * @return number of registered marks
     */
    public int size() {
        return entries.size();
    }

    /**
     * Builds the synthetic-binding map injected by every MCP evaluation call site. Each live entry
     * becomes a {@code "$label" -> ObjectReference} pair. Entries whose underlying object has been
     * collected are silently skipped so the expression compiler doesn't trip on a dead reference;
     * the dead mark stays in the registry until explicitly unmarked so the agent can still see it
     * in {@code jdwp_overview} and decide what to do with it.
     * <p>
     * Returns a {@link LinkedHashMap} for deterministic iteration order at the wrapper-class
     * cache key — same rationale as the {@code LinkedHashMap} contract documented on
     * {@code JdiExpressionEvaluator.buildContext}.
     */
    public Map<String, Value> buildBindings() {
        if (entries.isEmpty()) {
            return Map.of();
        }
        final Map<String, Value> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            final ObjectReference ref = e.getValue().reference;
            try {
                if (!ref.isCollected()) {
                    bindings.put(SIGIL + e.getKey(), ref);
                }
            } catch (Exception ex) {
                // Mirror dead — leave the entry registered so the agent can diagnose, but skip
                // the binding so the expression compiler doesn't see a stale reference.
                log.debug("[Marks] Skipping '{}': {}", e.getKey(), ex.getMessage());
            }
        }
        return bindings;
    }

    /**
     * Clears every mark, releasing pins along the way. Invoked from
     * {@code JDIConnectionService.notifyVmDied()}, {@code cleanupSessionState()}, and
     * {@code jdwp_reset} so marks share the lifecycle of the object cache they ride on top of.
     */
    public synchronized void clearAll() {
        if (entries.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            releasePin(e.getKey(), e.getValue());
        }
        entries.clear();
    }

    /**
     * Best-effort unpin — never throws so the caller can finish removing the registry slot.
     */
    private void releasePin(String label, Entry entry) {
        if (!entry.pinned) {
            return;
        }
        try {
            entry.reference.enableCollection();
        } catch (Exception e) {
            log.debug("[Marks] Could not re-enable collection for '{}': {}", label, e.getMessage());
        }
    }

    private record Entry(ObjectReference reference, boolean pinned) {
        MarkInfo toInfo(String label) {
            boolean isCollected;
            try {
                isCollected = reference.isCollected();
            } catch (Exception e) {
                isCollected = true;
            }
            return new MarkInfo(label, reference, pinned, isCollected);
        }
    }
}
