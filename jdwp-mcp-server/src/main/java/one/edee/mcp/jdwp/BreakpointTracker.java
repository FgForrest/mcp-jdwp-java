package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.request.*;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central registry for breakpoints owned by the MCP server and a parking spot for the last thread
 * that hit a suspending event. JDI's `BreakpointRequest` has no user-facing identifier, so this
 * service mints synthetic monotonic integer IDs and exposes them to the MCP client.
 * <p>
 * Maintains six parallel state maps:
 * - `breakpointsById` — line breakpoints already bound to a JDI `BreakpointRequest`.
 * - `pendingBreakpointsById` — line breakpoints whose target class is not yet loaded.
 * - `exceptionBreakpointsById` — exception breakpoints bound to a JDI `ExceptionRequest`.
 * - `pendingExceptionBreakpointsById` — exception breakpoints whose exception class is not yet loaded.
 * - `fieldBreakpointsById` — field watchpoints already bound to JDI `WatchpointRequest`(s) —
 *   `BOTH`-mode binds two requests (one access, one modification) under one synthetic ID.
 * - `pendingFieldBreakpointsById` — field watchpoints whose declaring class is not yet loaded.
 * <p>
 * Pending entries are promoted to active either by `JdiEventListener.handleClassPrepareEvent` (the
 * normal path) or by the {@link #tryPromotePending} safety net (called from {@link JDIConnectionService#getVM()}
 * before every tool call) for classes loaded before any debugger event was delivered.
 * <p>
 * <b>Thread-safety:</b> the contract is layered, not uniform — readers should not assume "every
 * public mutator is {@code synchronized}". The actual rules:
 * <ul>
 *   <li>The state maps are {@link ConcurrentHashMap}, so single-map mutations
 *       ({@code registerBreakpoint}, {@code unregisterByRequest}, {@code markPendingFailed},
 *       {@code registerClassPrepareRequest}, {@code setCondition}, {@code setLogpointExpression},
 *       {@code registerExceptionBreakpoint}, {@code registerFieldBreakpoint},
 *       {@code promotePendingFieldToActive}, …) rely on the map's own atomicity and are
 *       intentionally not {@code synchronized}.</li>
 *   <li>{@code synchronized} on this tracker guards operations that must mutate <i>multiple maps
 *       atomically</i> — promotion (remove from pending + insert into active + index the request),
 *       removal that spans both maps, {@code clearAll}, and the pending-side registrations whose
 *       atomicity matters for {@link #tryPromotePending}'s recheck-and-mutate critical section.</li>
 *   <li>{@link #tryPromotePending} is the documented exception to the "single critical section"
 *       pattern: it snapshots the pending maps under the monitor, releases it across each
 *       per-entry passive lookup (via {@link JDIConnectionService#findLoadedClass}), then
 *       re-acquires it for a recheck-and-mutate critical section. The lookup is intentionally
 *       passive — driving force-loads from this safety net was the root cause of the
 *       deferred-class-load deadlock (GH issue #2) and the breakpoint-induced behaviour change
 *       (GH issue #3). The {@code promote*} methods are the authoritative already-promoted guard;
 *       losers in the race tear down their orphan JDI request.</li>
 *   <li>The {@code lastBreakpoint*} fields are {@code volatile} for cross-thread visibility from
 *       the JDI listener thread to MCP worker threads.</li>
 * </ul>
 * When adding new public mutators, decide consciously which bucket they fall into — a method that
 * touches more than one map atomically must take the monitor; a single-map mutation should not.
 */
@Service
public class BreakpointTracker {
    private static final Logger log = LoggerFactory.getLogger(BreakpointTracker.class);

    /**
     * Monotonic synthetic ID source shared by every register-* call; reset by {@link #clearAll} / {@link #reset}.
     */
    private final AtomicInteger idCounter = new AtomicInteger(1);
    /**
     * Active line breakpoints keyed by synthetic ID; populated by {@link #registerBreakpoint}.
     */
    private final ConcurrentHashMap<Integer, BreakpointRequest> breakpointsById = new ConcurrentHashMap<>();
    /**
     * Reverse index for {@link #breakpointsById} keyed by JDI request identity (not equals — the JDI
     * implementation does not promise structural equality across requests). Populated and torn down
     * in lockstep with {@link #breakpointsById} so {@link #findIdByRequest} and
     * {@link #unregisterByRequest} are O(1) instead of scanning the values map. The JDI listener
     * thread calls {@code findIdByRequest} on every BP hit, so a linear scan here would be a hot
     * spot under high BP volume.
     */
    private final ConcurrentHashMap<BreakpointRequest, Integer> breakpointIdsByRequest = new ConcurrentHashMap<>();
    /**
     * Line breakpoints awaiting class load; populated by {@link #registerPendingBreakpoint} via `jdwp_set_breakpoint`.
     */
    private final ConcurrentHashMap<Integer, PendingBreakpoint> pendingBreakpointsById = new ConcurrentHashMap<>();
    /**
     * ClassPrepare requests keyed by class name; one per class with at least one pending BP referencing it.
     */
    private final ConcurrentHashMap<String, ClassPrepareRequest> classPrepareRequests = new ConcurrentHashMap<>();
    /**
     * Optional condition / logpoint expression metadata indexed by breakpoint ID.
     */
    private final ConcurrentHashMap<Integer, BreakpointMetadata> breakpointMetadata = new ConcurrentHashMap<>();
    /**
     * Active exception breakpoints keyed by synthetic ID; populated by {@link #registerExceptionBreakpoint}.
     */
    private final ConcurrentHashMap<Integer, ExceptionBreakpointInfo> exceptionBreakpointsById = new ConcurrentHashMap<>();
    /**
     * Reverse index for {@link #exceptionBreakpointsById} keyed by the JDI {@link ExceptionRequest}
     * identity. Hot path: {@link JdiEventListener#handleExceptionEvent} calls
     * {@link #findExceptionInfoByRequest} on every exception event, and log-only BPs matching broad
     * supertypes (e.g. {@code Throwable}) can fire frequently. Maintained in lockstep with the
     * primary map.
     */
    private final ConcurrentHashMap<ExceptionRequest, ExceptionBreakpointInfo> exceptionInfoByRequest = new ConcurrentHashMap<>();
    /**
     * Reverse index from JDI {@link ExceptionRequest} to its synthetic ID. Mirrors
     * {@link #breakpointIdsByRequest} so chain handlers can recover the user-facing ID from the
     * firing request without scanning {@link #exceptionBreakpointsById}.
     */
    private final ConcurrentHashMap<ExceptionRequest, Integer> exceptionIdsByRequest = new ConcurrentHashMap<>();
    /**
     * Exception breakpoints awaiting class load; promoted via {@link #promotePendingExceptionToActive}.
     */
    private final ConcurrentHashMap<Integer, PendingExceptionBreakpoint> pendingExceptionBreakpointsById = new ConcurrentHashMap<>();
    /**
     * Active field watchpoints keyed by synthetic ID. A {@code BOTH}-mode entry binds one synthetic
     * ID to two underlying JDI requests (one access, one modification) — the {@link FieldBreakpointInfo}
     * holds both. Populated by {@link #registerFieldBreakpoint}.
     */
    private final ConcurrentHashMap<Integer, FieldBreakpointInfo> fieldBreakpointsById = new ConcurrentHashMap<>();
    /**
     * Reverse index from JDI {@link WatchpointRequest} (either access or modification) to its info
     * record. {@code BOTH}-mode contributes two entries pointing at the same info. Hot path: the
     * listener consults this on every watchpoint event to recover the BP record without scanning.
     */
    private final ConcurrentHashMap<WatchpointRequest, FieldBreakpointInfo> fieldInfoByRequest = new ConcurrentHashMap<>();
    /**
     * Reverse index from JDI {@link WatchpointRequest} to its synthetic ID — mirrors
     * {@link #fieldInfoByRequest}. Used by chain handlers to recover the user-facing ID from the
     * firing request.
     */
    private final ConcurrentHashMap<WatchpointRequest, Integer> fieldIdsByRequest = new ConcurrentHashMap<>();
    /**
     * Field watchpoints awaiting class load; promoted via {@link #promotePendingFieldToActive} once
     * the declaring class is loaded by the target JVM.
     */
    private final ConcurrentHashMap<Integer, PendingFieldBreakpoint> pendingFieldBreakpointsById = new ConcurrentHashMap<>();

    /**
     * Atomic snapshot of the last suspending JDI event: the firing {@link ThreadReference}, the
     * {@link EventKind} that produced the suspension, and (for {@link EventKind#BREAKPOINT} and
     * STEP-inherited contexts) the synthetic breakpoint id. Stored in a single volatile field
     * so readers cannot observe a torn triple from two different writes. {@code null} until the
     * first event lands or after a reset.
     */
    @Nullable
    private volatile LastBreakpoint lastBreakpoint;
    /**
     * Suspension ownership of the current connection; see {@link #openSuspensionSession}.
     */
    @Nullable
    private volatile SuspensionSession suspensionSession;

    /**
     * Single-shot latch backing {@link JDWPTools#jdwp_resume_until_event(Integer)}.
     * Lifecycle: armed by {@link #armNextEventLatch()} immediately before `vm.resume()`,
     * counted down by {@link #fireNextEvent()} from the JDI listener thread when the next
     * suspending event (BP / step / exception) lands, and released-then-cleared by
     * {@link #clearAll(EventRequestManager)} / {@link #reset()} so a `jdwp_reset` or
     * `jdwp_disconnect` from another caller does not leave a waiter hanging.
     * <p>
     * `volatile` for cross-thread visibility; mutating methods that touch the field are
     * `synchronized` so the arm-then-fire ordering is atomic.
     */
    @Nullable
    private volatile CountDownLatch nextEventLatch;

    /**
     * "An event fired and no waiter has consumed the signal yet" flag. Set by {@link #fireNextEvent()}
     * regardless of latch state, cleared by {@link #consumePendingFire()}. Required so that a tool
     * sequence like {@code step_X → otherTool() → resume_until_event} doesn't lose the STEP signal:
     * the STEP fires while no latch is armed (or while a stale latch is in the slot), the flag
     * latches the fact that something happened, and {@link JDWPTools#jdwp_resume_until_event}
     * can return immediately based on {@link #lastBreakpoint} instead of issuing a second
     * {@code vm.resume()} that would overshoot the suspended state.
     */
    private volatile boolean pendingFire = false;

    /**
     * Dependent → trigger relationship for chained breakpoints. A dependent BP is registered with
     * {@code setEnabled(false)} and stays disarmed until its trigger fires. With {@code oneShot=false}
     * (the default, "sticky" mode) the dependent stays armed forever after the first trigger fire;
     * with {@code oneShot=true} the dependent disarms again after each hit so the next trigger fire
     * re-arms it (IntelliJ-style). Re-engaging the chain after a sticky fire is done via
     * {@code jdwp_disarm_until_trigger} which simply disables the dependent again.
     */
    private final ConcurrentHashMap<Integer, TriggerLink> dependencyByDependent = new ConcurrentHashMap<>();
    /**
     * Reverse index for {@link #dependencyByDependent}: trigger ID → set of dependents waiting on
     * it. Hot path: the JDI listener queries this on every BP/exception event to decide who to arm.
     * Maintained in lockstep with the primary map; a {@link ConcurrentHashMap#newKeySet()} backed
     * set provides safe concurrent reads without contending with mutators.
     */
    private final ConcurrentHashMap<Integer, Set<Integer>> dependentsByTrigger = new ConcurrentHashMap<>();
    /**
     * BPs that have fired at least once since attach. Populated by {@link #markTriggerFired} from
     * the JDI listener after every BP/exception hit and queried by the pending → active promotion
     * path so a chained dependent whose trigger ALREADY fired comes up armed instead of disarmed.
     * Without this memory, a dependent that was registered while still pending would silently miss
     * its trigger's earlier fires. Cleared by {@link #reset} / {@link #clearAll} so a session-level
     * wipe resets the chain memory; per-BP cleanup is handled inline (see {@link #removeBreakpoint}).
     */
    private final Set<Integer> triggersFiredAtLeastOnce = ConcurrentHashMap.newKeySet();

    // ── Active breakpoint operations ──

    /**
     * Best-effort delete of a JDI event request — swallows any exception (e.g., VM already disconnected).
     */
    private static void deleteQuietly(EventRequestManager erm, EventRequest req) {
        try {
            erm.deleteEventRequest(req);
        } catch (Exception e) {
            // VM may already be disconnected
        }
    }

    /**
     * Register a breakpoint and return a synthetic integer ID.
     */
    public int registerBreakpoint(BreakpointRequest bp) {
        final int id = idCounter.getAndIncrement();
        breakpointsById.put(id, bp);
        breakpointIdsByRequest.put(bp, id);
        return id;
    }

    /**
     * Mints a synthetic ID from the shared breakpoint ID space for a breakpoint kind this tracker
     * does not store itself (fast logpoints), so {@code jdwp_clear_breakpoint(id)} can route every
     * kind by one number.
     */
    public int allocateId() {
        return idCounter.getAndIncrement();
    }

    /**
     * Swaps the JDI request behind active breakpoint {@code id} — used after a class redefinition,
     * which deletes every {@link BreakpointRequest} in the redefined class, so the re-created request
     * keeps the user-facing ID, condition, logpoint expression and chain edges. A no-op returning
     * {@code false} when {@code id} no longer maps to {@code expected} (cleared concurrently); the
     * caller then deletes {@code replacement}.
     */
    public synchronized boolean replaceBreakpointRequest(int id, BreakpointRequest expected,
                                                         BreakpointRequest replacement) {
        if (!breakpointsById.replace(id, expected, replacement)) {
            return false;
        }
        breakpointIdsByRequest.remove(expected);
        breakpointIdsByRequest.put(replacement, id);
        return true;
    }

    /**
     * Lookup a breakpoint by its synthetic ID.
     */
    @Nullable
    public BreakpointRequest getBreakpoint(int id) {
        return breakpointsById.get(id);
    }

    /**
     * Remove a breakpoint by ID — checks active first, then pending. Also clears any condition or
     * logpoint metadata associated with the synthetic ID, the chain edge that references the
     * removed BP as a dependent, and the "trigger has fired" flag if the removed BP was itself a
     * trigger — when the trigger goes away, its fire history must not influence future dependents
     * that happen to reuse the same synthetic ID.
     *
     * @return true if found and removed
     */
    public synchronized boolean removeBreakpoint(int id) {
        // Try active breakpoints first
        final BreakpointRequest bp = breakpointsById.remove(id);
        if (bp != null) {
            // Delete before forgetting the request: a hit still queued then finds either its ID or
            // a disabled request, never an enabled one nobody knows — the listener would keep that
            // thread stopped as an untracked breakpoint. The metadata goes last, after the request
            // is deleted, which lets the listener tell a hit whose metadata vanished meanwhile.
            try {
                bp.virtualMachine().eventRequestManager().deleteEventRequest(bp);
            } catch (Exception e) {
                // VM may already be disconnected
            }
            breakpointIdsByRequest.remove(bp);
            breakpointMetadata.remove(id);
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }

        // Try pending breakpoints
        final PendingBreakpoint pending = pendingBreakpointsById.remove(id);
        if (pending != null) {
            cleanupClassPrepareRequestIfNeeded(pending.getClassName());
            breakpointMetadata.remove(id);
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }

        return false;
    }

    /**
     * Removes the in-memory tracking entry for the given JDI request via identity comparison and
     * clears any condition / logpoint metadata associated with its synthetic ID, so a freshly
     * minted BP that reuses the deleted ID cannot inherit the previous BP's condition or logpoint
     * expression.
     * <p>
     * Does NOT call `EventRequestManager.deleteEventRequest`. Callers (currently
     * {@link JDWPTools#jdwp_clear_breakpoint}) are responsible for deleting the underlying JDI request
     * before invoking this method, otherwise the request stays alive in the target VM.
     */
    public void unregisterByRequest(BreakpointRequest bp) {
        final Integer id = breakpointIdsByRequest.remove(bp);
        if (id != null) {
            breakpointsById.remove(id);
            breakpointMetadata.remove(id);
            clearDependency(id);
        }
    }

    /**
     * Find the synthetic ID for a given JDI BreakpointRequest. O(1) via the reverse index.
     *
     * @return the ID, or null if not tracked
     */
    @Nullable
    public Integer findIdByRequest(BreakpointRequest bp) {
        return breakpointIdsByRequest.get(bp);
    }

    /**
     * Return all tracked active breakpoints (unmodifiable view).
     */
    public Map<Integer, BreakpointRequest> getAllBreakpoints() {
        return Collections.unmodifiableMap(breakpointsById);
    }

    /**
     * Build a location map: "className:lineNumber" -> the IDs of every active breakpoint at that
     * line, ascending — a breakpoint and a logpoint, or two with different filters, can share one.
     */
    public Map<String, List<Integer>> getBreakpointLocationMap() {
        final Map<String, List<Integer>> map = new HashMap<>();
        for (Map.Entry<Integer, BreakpointRequest> entry : breakpointsById.entrySet()) {
            final Location loc = entry.getValue().location();
            final String key = loc.declaringType().name() + ':' + loc.lineNumber();
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getKey());
        }
        for (List<Integer> ids : map.values()) {
            Collections.sort(ids);
        }
        return map;
    }

    /**
     * Clean shutdown variant: clears every state map (active line BPs, pending line BPs, exception BPs,
     * pending exception BPs, active field watchpoints, pending field watchpoints, breakpoint metadata,
     * ClassPrepare requests) and deletes the underlying JDI event requests via `erm` — including BOTH
     * halves of a {@code BOTH}-mode field watchpoint. Also releases any pending `resume_until_event`
     * waiter so a `jdwp_reset` or `jdwp_disconnect` from another caller does not leave it hanging
     * until timeout.
     * <p>
     * Counterpart of {@link #reset()}, which performs the same in-memory cleanup but skips the JDI
     * calls — used when the target VM is already gone.
     */
    public synchronized void clearAll(EventRequestManager erm) {
        // Release any awaiter BEFORE we touch state — see fireNextEvent() for why this matters.
        fireNextEvent();
        for (BreakpointRequest bp : breakpointsById.values()) {
            deleteQuietly(erm, bp);
        }
        for (ClassPrepareRequest cpr : classPrepareRequests.values()) {
            deleteQuietly(erm, cpr);
        }
        for (ExceptionBreakpointInfo info : exceptionBreakpointsById.values()) {
            deleteQuietly(erm, info.request);
        }
        for (FieldBreakpointInfo info : fieldBreakpointsById.values()) {
            if (info.getAccessRequest() != null) {
                deleteQuietly(erm, info.getAccessRequest());
            }
            if (info.getModificationRequest() != null) {
                deleteQuietly(erm, info.getModificationRequest());
            }
        }
        clearAllInMemoryStateLocked();
    }

    /**
     * Wipes every in-memory map and resets the ID counter. Shared between {@link #clearAll} and
     * {@link #reset}; the only difference between the two is whether the underlying JDI requests
     * are deleted before the wipe. Caller MUST hold the tracker's intrinsic lock — both call sites
     * are {@code synchronized} methods so the helper does not re-acquire it.
     */
    private void clearAllInMemoryStateLocked() {
        breakpointsById.clear();
        breakpointIdsByRequest.clear();
        pendingBreakpointsById.clear();
        breakpointMetadata.clear();
        classPrepareRequests.clear();
        exceptionBreakpointsById.clear();
        exceptionInfoByRequest.clear();
        exceptionIdsByRequest.clear();
        pendingExceptionBreakpointsById.clear();
        fieldBreakpointsById.clear();
        fieldInfoByRequest.clear();
        fieldIdsByRequest.clear();
        pendingFieldBreakpointsById.clear();
        dependencyByDependent.clear();
        dependentsByTrigger.clear();
        triggersFiredAtLeastOnce.clear();
        lastBreakpoint = null;
        // Drop any unconsumed "pending fire" signal — a state wipe invalidates the snapshot it
        // was about to be processed against.
        pendingFire = false;
        idCounter.set(1);
    }

    // ── Reconnect snapshot / restore ──

    /**
     * Captures every breakpoint spec, metadata row, chain edge, and trigger-fire memory currently
     * held by the tracker. Used by {@link JDIConnectionService} during {@code jdwp_reconnect} to
     * preserve user state across {@code vm.dispose()}.
     *
     * <p>The snapshot records only data that survives the VM going away: synthetic IDs (which the
     * tracker mints client-side), specs (which the tracker stores client-side), metadata (likewise),
     * and chain edges (likewise). Object references, suspended-thread context, and live JDI
     * request handles are deliberately omitted — they cannot survive a {@code vm.dispose()} and
     * pretending otherwise would mislead the caller.
     *
     * <p>For active line BPs the {@code className} and {@code lineNumber} are extracted from the
     * live {@link BreakpointRequest#location()} so the spec can be replayed against a fresh VM;
     * the {@code suspendPolicy} round-trips through the JDI request as well. Active exception and
     * field BPs already store their full {@link ExceptionBreakpointSpec} / {@link FieldBreakpointSpec},
     * so the snapshot just references them directly.
     */
    public synchronized ReconnectSnapshot snapshotForReconnect() {
        final List<LineBreakpointEntry> lineEntries = new ArrayList<>();
        for (Map.Entry<Integer, BreakpointRequest> e : breakpointsById.entrySet()) {
            final Location loc = e.getValue().location();
            lineEntries.add(new LineBreakpointEntry(
                e.getKey(),
                loc.declaringType().name(),
                loc.lineNumber(),
                e.getValue().suspendPolicy(),
                suspendPolicyLabel(e.getValue().suspendPolicy())
            ));
        }
        for (Map.Entry<Integer, PendingBreakpoint> e : pendingBreakpointsById.entrySet()) {
            final PendingBreakpoint pb = e.getValue();
            lineEntries.add(new LineBreakpointEntry(
                e.getKey(), pb.getClassName(), pb.getLineNumber(),
                pb.getSuspendPolicy(), pb.getSuspendPolicyLabel()
            ));
        }

        final List<ExceptionBreakpointEntry> exceptionEntries = new ArrayList<>();
        for (Map.Entry<Integer, ExceptionBreakpointInfo> e : exceptionBreakpointsById.entrySet()) {
            exceptionEntries.add(new ExceptionBreakpointEntry(e.getKey(), e.getValue().getSpec()));
        }
        for (Map.Entry<Integer, PendingExceptionBreakpoint> e : pendingExceptionBreakpointsById.entrySet()) {
            exceptionEntries.add(new ExceptionBreakpointEntry(e.getKey(), e.getValue().getSpec()));
        }

        final List<FieldBreakpointEntry> fieldEntries = new ArrayList<>();
        for (Map.Entry<Integer, FieldBreakpointInfo> e : fieldBreakpointsById.entrySet()) {
            fieldEntries.add(new FieldBreakpointEntry(e.getKey(), e.getValue().getSpec()));
        }
        for (Map.Entry<Integer, PendingFieldBreakpoint> e : pendingFieldBreakpointsById.entrySet()) {
            fieldEntries.add(new FieldBreakpointEntry(e.getKey(), e.getValue().getSpec()));
        }

        final Map<Integer, MetadataSnapshot> metadataSnapshot = new HashMap<>();
        for (Map.Entry<Integer, BreakpointMetadata> e : breakpointMetadata.entrySet()) {
            metadataSnapshot.put(e.getKey(),
                new MetadataSnapshot(e.getValue().condition, e.getValue().logpointExpression));
        }

        return new ReconnectSnapshot(
            List.copyOf(lineEntries),
            List.copyOf(exceptionEntries),
            List.copyOf(fieldEntries),
            Map.copyOf(metadataSnapshot),
            Map.copyOf(dependencyByDependent),
            Set.copyOf(triggersFiredAtLeastOnce)
        );
    }

    /**
     * Wipes every map (as {@link #reset()} does) and replays the snapshot as pending entries. The
     * synthetic IDs from the original session are preserved verbatim so watchers and chain edges
     * keyed by ID survive the reconnect.
     *
     * <p>The {@link #idCounter} is reset to {@code max(restored ids) + 1} so any subsequent
     * register-* call mints an ID that does not collide with a restored one.
     *
     * <p>This method establishes the "everything is pending" baseline; the caller is responsible
     * for the JDI side — registering {@link ClassPrepareRequest}s for the pending class names and
     * calling {@link #tryPromotePending} so classes already loaded by the fresh VM bind
     * immediately.
     *
     * @param snapshot  the immutable capture returned by {@link #snapshotForReconnect()}; the
     *                  tracker mutates only its own internal state during replay — the snapshot
     *                  itself is untouched
     */
    public synchronized void restoreFromSnapshotAsPending(ReconnectSnapshot snapshot) {
        clearAllInMemoryStateLocked();

        int maxId = 0;
        for (LineBreakpointEntry entry : snapshot.lineBreakpoints()) {
            pendingBreakpointsById.put(entry.id(),
                new PendingBreakpoint(entry.className(), entry.lineNumber(),
                    entry.suspendPolicy(), entry.suspendPolicyLabel()));
            maxId = Math.max(maxId, entry.id());
        }
        for (ExceptionBreakpointEntry entry : snapshot.exceptionBreakpoints()) {
            pendingExceptionBreakpointsById.put(entry.id(), new PendingExceptionBreakpoint(entry.spec()));
            maxId = Math.max(maxId, entry.id());
        }
        for (FieldBreakpointEntry entry : snapshot.fieldBreakpoints()) {
            pendingFieldBreakpointsById.put(entry.id(), new PendingFieldBreakpoint(entry.spec()));
            maxId = Math.max(maxId, entry.id());
        }

        for (Map.Entry<Integer, MetadataSnapshot> e : snapshot.metadata().entrySet()) {
            final BreakpointMetadata meta = getOrCreateMetadata(e.getKey());
            meta.condition = e.getValue().condition();
            meta.logpointExpression = e.getValue().logpointExpression();
        }

        for (Map.Entry<Integer, TriggerLink> e : snapshot.dependencies().entrySet()) {
            dependencyByDependent.put(e.getKey(), e.getValue());
            dependentsByTrigger
                .computeIfAbsent(e.getValue().triggerId(), k -> ConcurrentHashMap.newKeySet())
                .add(e.getKey());
        }

        triggersFiredAtLeastOnce.addAll(snapshot.triggersFiredAtLeastOnce());

        idCounter.set(maxId + 1);
    }

    /**
     * Translates a JDI {@link EventRequest#suspendPolicy()} constant back to the human-readable
     * label used by {@code jdwp_set_breakpoint}. Inverse of the label-parsing logic in the tool
     * layer, lifted into the tracker because the snapshot path needs to round-trip the label
     * through a fresh {@link PendingBreakpoint} without re-deriving it elsewhere.
     */
    private static String suspendPolicyLabel(int policy) {
        return switch (policy) {
            case EventRequest.SUSPEND_ALL -> "all";
            case EventRequest.SUSPEND_EVENT_THREAD -> "thread";
            case EventRequest.SUSPEND_NONE -> "none";
            default -> "all";
        };
    }

    // ── Pending breakpoint operations ──

    /**
     * Register a pending (deferred) breakpoint for a class not yet loaded.
     *
     * @param className          fully qualified class name to monitor for loading
     * @param lineNumber         source line number where the breakpoint should be set
     * @param suspendPolicy      JDI suspend policy constant (e.g. {@link EventRequest#SUSPEND_ALL})
     * @param suspendPolicyLabel human-readable label for the suspend policy (e.g. "all", "thread")
     */
    public synchronized int registerPendingBreakpoint(
        String className, int lineNumber, int suspendPolicy, String suspendPolicyLabel) {
        final int id = idCounter.getAndIncrement();
        pendingBreakpointsById.put(id, new PendingBreakpoint(className, lineNumber, suspendPolicy, suspendPolicyLabel));
        return id;
    }

    /**
     * Looks up a pending breakpoint by its synthetic ID.
     */
    @Nullable
    public PendingBreakpoint getPendingBreakpoint(int id) {
        return pendingBreakpointsById.get(id);
    }

    /**
     * Removes a pending breakpoint by ID and cleans up its ClassPrepareRequest if no longer needed.
     * Also drops any chain edge that referenced this BP as a dependent and any condition / logpoint
     * metadata recorded against its synthetic ID, so a direct removal of a pending BP leaves no
     * ghost state behind. Mirrors the cleanup performed by {@link #removeBreakpoint} for active
     * line BPs.
     *
     * @return true if found and removed
     */
    public synchronized boolean removePendingBreakpoint(int id) {
        final PendingBreakpoint removed = pendingBreakpointsById.remove(id);
        if (removed != null) {
            cleanupClassPrepareRequestIfNeeded(removed.getClassName());
            breakpointMetadata.remove(id);
            clearDependency(id);
            return true;
        }
        return false;
    }

    /**
     * Get all pending breakpoints for a given class name.
     */
    public List<Map.Entry<Integer, PendingBreakpoint>> getPendingBreakpointsForClass(String className) {
        return pendingBreakpointsById.entrySet().stream()
            .filter(e -> e.getValue().getClassName().equals(className))
            .toList();
    }

    /**
     * Returns all pending breakpoints (unmodifiable view).
     */
    public Map<Integer, PendingBreakpoint> getAllPendingBreakpoints() {
        return Collections.unmodifiableMap(pendingBreakpointsById);
    }

    /**
     * Promote a pending breakpoint to active: remove from pending, add to active with the same ID.
     * Returns {@code true} if the promotion happened. Returns {@code false} when the synthetic id
     * already has an active entry (the listener path and the {@link #tryPromotePending} safety net
     * race for the same pending entries, and the second arrival must not overwrite the winner) or
     * when the pending entry has already been consumed — e.g. the user cleared it between the
     * class-prepare snapshot and this call. Callers that receive {@code false} are responsible for
     * tearing down the now-orphan {@link BreakpointRequest} they just created
     * (via {@code erm.deleteEventRequest(bp)}).
     */
    public synchronized boolean promotePendingToActive(int id, BreakpointRequest bp) {
        if (breakpointsById.containsKey(id)) {
            return false;
        }
        final PendingBreakpoint pending = pendingBreakpointsById.remove(id);
        if (pending == null) {
            return false;
        }
        breakpointsById.put(id, bp);
        breakpointIdsByRequest.put(bp, id);
        return true;
    }

    /**
     * Mark a pending breakpoint as failed (e.g., no executable code at line).
     */
    public void markPendingFailed(int id, @Nullable String reason) {
        final PendingBreakpoint pending = pendingBreakpointsById.get(id);
        if (pending != null) {
            pending.setFailureReason(reason);
        }
    }

    // ── ClassPrepareRequest tracking ──

    /**
     * Registers a ClassPrepareRequest so deferred breakpoints can be activated when the class loads.
     */
    public void registerClassPrepareRequest(String className, ClassPrepareRequest cpr) {
        classPrepareRequests.put(className, cpr);
    }

    /**
     * Checks whether a ClassPrepareRequest is already registered for the given class.
     */
    public boolean hasClassPrepareRequest(String className) {
        return classPrepareRequests.containsKey(className);
    }

    /**
     * Removes and returns the ClassPrepareRequest for the given class, or null if none exists.
     */
    @Nullable
    public ClassPrepareRequest removeClassPrepareRequest(String className) {
        return classPrepareRequests.remove(className);
    }

    /**
     * If no more pending entries of any kind (line, exception, or field) reference this class, delete
     * the ClassPrepareRequest.
     */
    private void cleanupClassPrepareRequestIfNeeded(String className) {
        final boolean hasOthers = pendingBreakpointsById.values().stream()
            .anyMatch(p -> p.getClassName().equals(className))
            || pendingExceptionBreakpointsById.values().stream()
            .anyMatch(p -> p.getExceptionClass().equals(className))
            || pendingFieldBreakpointsById.values().stream()
            .anyMatch(p -> p.getSpec().className().equals(className));
        if (!hasOthers) {
            final ClassPrepareRequest cpr = classPrepareRequests.remove(className);
            if (cpr != null) {
                try {
                    cpr.virtualMachine().eventRequestManager().deleteEventRequest(cpr);
                } catch (Exception e) {
                    // VM may already be disconnected
                }
            }
        }
    }

    // ── Breakpoint metadata (conditions, logpoints) ──

    /**
     * Records a condition expression for the given breakpoint. Blank/null conditions are silently
     * ignored — no metadata row is created. Conditions are evaluated against frame 0 by
     * {@link JdiEventListener#evaluateCondition} on every BP hit; if the expression evaluates to
     * false the listener auto-resumes without notifying the user.
     */
    public void setCondition(int breakpointId, @Nullable String condition) {
        if (condition != null && !condition.isBlank()) {
            getOrCreateMetadata(breakpointId).condition = condition;
        }
    }

    /**
     * Returns the condition expression, or {@code null} if none is set.
     */
    @Nullable
    public String getCondition(int breakpointId) {
        final BreakpointMetadata meta = breakpointMetadata.get(breakpointId);
        return meta != null ? meta.condition : null;
    }

    /**
     * Marks the breakpoint as a logpoint by attaching an expression to evaluate on each hit.
     * Blank/null expressions are silently ignored — no metadata row is created. A non-null logpoint
     * expression flips the breakpoint's behaviour: {@link JdiEventListener#handleBreakpointEvent}
     * evaluates the expression via {@link JdiExpressionEvaluator}, records a
     * `LOGPOINT` (or `LOGPOINT_ERROR`) entry in {@link EventHistory}, and auto-resumes the thread.
     */
    public void setLogpointExpression(int breakpointId, @Nullable String expression) {
        if (expression != null && !expression.isBlank()) {
            getOrCreateMetadata(breakpointId).logpointExpression = expression;
        }
    }

    /**
     * Returns the logpoint expression, or {@code null} if not a logpoint.
     */
    @Nullable
    public String getLogpointExpression(int breakpointId) {
        final BreakpointMetadata meta = breakpointMetadata.get(breakpointId);
        return meta != null ? meta.logpointExpression : null;
    }

    /**
     * Returns {@code true} if the breakpoint has a logpoint expression.
     */
    public boolean isLogpoint(int breakpointId) {
        return getLogpointExpression(breakpointId) != null;
    }

    private BreakpointMetadata getOrCreateMetadata(int breakpointId) {
        return breakpointMetadata.computeIfAbsent(breakpointId, k -> new BreakpointMetadata());
    }

    // ── Exception breakpoint operations ──

    /**
     * Registers an active exception breakpoint and returns its synthetic ID. Twin of
     * {@link #registerBreakpoint} for the exception side of the state machine.
     *
     * @param req  the JDI exception request already created and enabled
     * @param spec the user-facing options bundle (class, caught/uncaught flags, logOnly, expression)
     */
    public int registerExceptionBreakpoint(ExceptionRequest req, ExceptionBreakpointSpec spec) {
        final int id = idCounter.getAndIncrement();
        final ExceptionBreakpointInfo info = new ExceptionBreakpointInfo(spec, req);
        exceptionBreakpointsById.put(id, info);
        exceptionInfoByRequest.put(req, info);
        exceptionIdsByRequest.put(req, id);
        return id;
    }

    /**
     * Looks up the {@link ExceptionBreakpointInfo} for a given JDI {@link ExceptionRequest}, or
     * {@code null} if the request is not tracked. O(1) via the reverse index. Used by
     * {@link JdiEventListener#handleExceptionEvent} to read the {@code logOnly} flag and the
     * optional log expression from the firing request on every exception event.
     */
    @Nullable
    public ExceptionBreakpointInfo findExceptionInfoByRequest(ExceptionRequest req) {
        return exceptionInfoByRequest.get(req);
    }

    /**
     * Returns the synthetic ID for a given JDI {@link ExceptionRequest}. O(1) via the reverse
     * index — used by chain handlers in {@link JdiEventListener} to recover the user-facing ID
     * from the firing request without scanning {@link #exceptionBreakpointsById}.
     */
    @Nullable
    public Integer findExceptionIdByRequest(ExceptionRequest req) {
        return exceptionIdsByRequest.get(req);
    }

    /**
     * Removes an exception breakpoint by ID — checks active first, then pending. Active removals
     * also delete the underlying JDI `ExceptionRequest`; pending removals additionally clean up the
     * `ClassPrepareRequest` if no other pending item still references the same exception class.
     * Also forgets the "trigger has fired" flag for the removed ID so a future BP that reuses the
     * synthetic ID does not inherit the previous BP's fire history.
     *
     * @return `true` if found and removed, `false` if no entry exists for the given ID
     */
    public synchronized boolean removeExceptionBreakpoint(int id) {
        final ExceptionBreakpointInfo info = exceptionBreakpointsById.remove(id);
        if (info != null) {
            // Delete first, as removeBreakpoint does.
            try {
                info.request.virtualMachine().eventRequestManager().deleteEventRequest(info.request);
            } catch (Exception e) {
                // VM may already be disconnected
            }
            exceptionInfoByRequest.remove(info.request);
            exceptionIdsByRequest.remove(info.request);
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }
        final PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.remove(id);
        if (pending != null) {
            cleanupClassPrepareRequestIfNeeded(pending.getExceptionClass());
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }
        return false;
    }

    /**
     * Returns an unmodifiable snapshot of all currently-active exception breakpoints.
     */
    public Map<Integer, ExceptionBreakpointInfo> getAllExceptionBreakpoints() {
        return Collections.unmodifiableMap(exceptionBreakpointsById);
    }

    /**
     * Registers a pending exception breakpoint for a class not yet loaded. Twin of
     * {@link #registerPendingBreakpoint} for the exception side of the state machine. The caller
     * is expected to also register a `ClassPrepareRequest` so {@link JdiEventListener#handleClassPrepareEvent}
     * can promote it via {@link #promotePendingExceptionToActive}.
     */
    public synchronized int registerPendingExceptionBreakpoint(ExceptionBreakpointSpec spec) {
        final int id = idCounter.getAndIncrement();
        pendingExceptionBreakpointsById.put(id, new PendingExceptionBreakpoint(spec));
        return id;
    }

    /**
     * Returns every pending exception breakpoint that targets the given class name. Used by the
     * class-prepare handler to know which entries to promote when the class loads.
     */
    public List<Map.Entry<Integer, PendingExceptionBreakpoint>> getPendingExceptionBreakpointsForClass(String exceptionClass) {
        return pendingExceptionBreakpointsById.entrySet().stream()
            .filter(e -> e.getValue().getExceptionClass().equals(exceptionClass))
            .toList();
    }

    /**
     * Returns an unmodifiable snapshot of all currently-pending exception breakpoints.
     */
    public Map<Integer, PendingExceptionBreakpoint> getAllPendingExceptionBreakpoints() {
        return Collections.unmodifiableMap(pendingExceptionBreakpointsById);
    }

    /**
     * Promotes a pending exception breakpoint to active by removing it from the pending map and
     * inserting an {@link ExceptionBreakpointInfo} under the same synthetic ID. Returns {@code true}
     * if the promotion happened. Returns {@code false} when the synthetic id already has an active
     * entry (the listener and the {@link #tryPromotePending} safety net raced for the same pending
     * entry — the second arrival must not overwrite the winner) or when the pending entry has
     * already been consumed. Callers that receive {@code false} are responsible for tearing down
     * the now-orphan {@link ExceptionRequest} they just created.
     */
    public synchronized boolean promotePendingExceptionToActive(int id, ExceptionRequest req) {
        if (exceptionBreakpointsById.containsKey(id)) {
            return false;
        }
        final PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.remove(id);
        if (pending == null) {
            return false;
        }
        final ExceptionBreakpointInfo info = new ExceptionBreakpointInfo(pending.getSpec(), req);
        exceptionBreakpointsById.put(id, info);
        exceptionInfoByRequest.put(req, info);
        exceptionIdsByRequest.put(req, id);
        return true;
    }

    /**
     * Records why a pending exception breakpoint could not be activated (e.g., the exception class
     * exists but cannot be force-loaded). The pending entry stays in the map so the failure reason
     * is visible to `jdwp_overview(types="exception_breakpoint")`.
     */
    public void markPendingExceptionFailed(int id, @Nullable String reason) {
        final PendingExceptionBreakpoint pending = pendingExceptionBreakpointsById.get(id);
        if (pending != null) {
            pending.setFailureReason(reason);
        }
    }

    // ── Field breakpoint operations ──

    /**
     * Registers an active field watchpoint and returns its synthetic ID. {@code accessReq} and
     * {@code modReq} are individually optional but at least one must be non-null — a
     * {@code BOTH}-mode entry passes both, an access-only entry passes {@code modReq=null}, and
     * a modification-only entry passes {@code accessReq=null}. Both reverse indices are populated
     * in lockstep so the listener can recover the info / ID from either request kind on every hit.
     *
     * @throws IllegalArgumentException when both requests are {@code null}
     */
    public int registerFieldBreakpoint(FieldBreakpointSpec spec,
                                       @Nullable AccessWatchpointRequest accessReq,
                                       @Nullable ModificationWatchpointRequest modReq) {
        if (accessReq == null && modReq == null) {
            throw new IllegalArgumentException(
                "At least one of accessReq / modReq must be non-null");
        }
        final int id = idCounter.getAndIncrement();
        final FieldBreakpointInfo info = new FieldBreakpointInfo(spec, accessReq, modReq);
        fieldBreakpointsById.put(id, info);
        if (accessReq != null) {
            indexFieldRequest(accessReq, info, id);
        }
        if (modReq != null) {
            indexFieldRequest(modReq, info, id);
        }
        return id;
    }

    /**
     * Looks up the {@link FieldBreakpointInfo} for a given JDI {@link WatchpointRequest}, or
     * {@code null} if the request is not tracked. O(1) via the reverse index.
     */
    @Nullable
    public FieldBreakpointInfo findFieldInfoByRequest(WatchpointRequest req) {
        return fieldInfoByRequest.get(req);
    }

    /**
     * Returns the synthetic ID for a given JDI {@link WatchpointRequest}. O(1) via the reverse index.
     */
    @Nullable
    public Integer findFieldIdByRequest(WatchpointRequest req) {
        return fieldIdsByRequest.get(req);
    }

    /**
     * Returns an unmodifiable snapshot of all currently-active field watchpoints.
     */
    public Map<Integer, FieldBreakpointInfo> getAllFieldBreakpoints() {
        return Collections.unmodifiableMap(fieldBreakpointsById);
    }

    /**
     * Registers a pending field watchpoint for a class not yet loaded. Twin of
     * {@link #registerPendingExceptionBreakpoint} for the field side of the state machine. The caller
     * is expected to also register a {@link ClassPrepareRequest} so {@link JdiEventListener#handleClassPrepareEvent}
     * can promote it via {@link #promotePendingFieldToActive}.
     */
    public synchronized int registerPendingFieldBreakpoint(FieldBreakpointSpec spec) {
        final int id = idCounter.getAndIncrement();
        pendingFieldBreakpointsById.put(id, new PendingFieldBreakpoint(spec));
        return id;
    }

    /**
     * Returns every pending field watchpoint targeting {@code className}. Used by the class-prepare
     * handler to know which entries to promote when the class loads.
     */
    public List<Map.Entry<Integer, PendingFieldBreakpoint>> getPendingFieldBreakpointsForClass(String className) {
        return pendingFieldBreakpointsById.entrySet().stream()
            .filter(e -> e.getValue().getSpec().className().equals(className))
            .toList();
    }

    /**
     * Returns an unmodifiable snapshot of all currently-pending field watchpoints.
     */
    public Map<Integer, PendingFieldBreakpoint> getAllPendingFieldBreakpoints() {
        return Collections.unmodifiableMap(pendingFieldBreakpointsById);
    }

    /**
     * Promotes a pending field watchpoint to active by removing it from the pending map and
     * inserting a {@link FieldBreakpointInfo} under the same synthetic ID with the supplied JDI
     * requests. No-op if the ID is unknown.
     */
    public void promotePendingFieldToActive(int id,
                                            @Nullable AccessWatchpointRequest accessReq,
                                            @Nullable ModificationWatchpointRequest modReq) {
        final PendingFieldBreakpoint pending = pendingFieldBreakpointsById.remove(id);
        if (pending != null) {
            final FieldBreakpointInfo info = new FieldBreakpointInfo(pending.getSpec(), accessReq, modReq);
            fieldBreakpointsById.put(id, info);
            if (accessReq != null) {
                indexFieldRequest(accessReq, info, id);
            }
            if (modReq != null) {
                indexFieldRequest(modReq, info, id);
            }
        }
    }

    /**
     * Records why a pending field watchpoint could not be activated. The pending entry stays in the
     * map so the failure reason is visible to {@code jdwp_overview(types="field_breakpoint")}.
     */
    public void markPendingFieldFailed(int id, @Nullable String reason) {
        final PendingFieldBreakpoint pending = pendingFieldBreakpointsById.get(id);
        if (pending != null) {
            pending.setFailureReason(reason);
        }
    }

    /**
     * Removes a field watchpoint by ID — checks active first, then pending. Active removals also
     * delete the underlying JDI {@link WatchpointRequest}(s) — both for a {@code BOTH}-mode entry.
     * Pending removals additionally clean up the {@link ClassPrepareRequest} if no other pending
     * item still references the same declaring class.
     *
     * @return {@code true} if found and removed, {@code false} otherwise
     */
    public synchronized boolean removeFieldBreakpoint(int id) {
        final FieldBreakpointInfo info = fieldBreakpointsById.remove(id);
        if (info != null) {
            if (info.getAccessRequest() != null) {
                tearDownFieldRequestQuietly(info.getAccessRequest());
            }
            if (info.getModificationRequest() != null) {
                tearDownFieldRequestQuietly(info.getModificationRequest());
            }
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }
        final PendingFieldBreakpoint pending = pendingFieldBreakpointsById.remove(id);
        if (pending != null) {
            cleanupClassPrepareRequestIfNeeded(pending.getSpec().className());
            clearDependency(id);
            triggersFiredAtLeastOnce.remove(id);
            return true;
        }
        return false;
    }

    // ── Opportunistic promotion ──

    /**
     * Re-attempts to promote every pending entry — line BPs, exception BPs, and field watchpoints —
     * by re-querying {@link JDIConnectionService#findLoadedClass} (passive,
     * side-effect free). This is the safety net for cases where {@link ClassPrepareRequest} does
     * not fire — most notably bootstrap classes loaded by the JVM before any debugger event is
     * delivered.
     * <p>
     * Called from {@link JDIConnectionService#getVM()} (every MCP tool call) and
     * from {@link JdiEventListener} (after every JDI event), so any user interaction
     * gives pending items another chance to bind. Best-effort; transient failures are logged at
     * debug and the item stays pending for the next retry.
     * <p>
     * The lookup is intentionally <i>passive</i>: it never invokes {@code Class.forName} in the
     * target VM. The safety net's job is to catch classes that loaded while we weren't looking,
     * not to drive new loads. Driving loads here was the root cause of the deferred-class-load
     * deadlock pattern (GH issue #2) and changed target-application behaviour (GH issue #3); both
     * are eliminated by making this strictly observational.
     * <p>
     * Field BPs additionally roll back any partially-created JDI requests if promotion fails
     * mid-flight (a {@code BOTH}-mode entry creates two requests, either of which can throw) and
     * mark the pending entry failed so subsequent cycles do not retry the same orphan-prone path.
     * <p>
     * <b>Concurrency:</b> the method is intentionally not {@code synchronized}. The pending maps
     * are snapshotted under the tracker monitor here, then each entry's per-class lookup runs
     * without the monitor (cheap and side-effect free), and the per-entry mutation step re-acquires
     * the tracker monitor with a recheck that the entry is still pending and that no other path
     * (listener or a sibling {@link #tryPromotePending} caller) has already promoted it. The
     * recheck is defense in depth on top of the already-promoted guard inside the promote* methods
     * themselves. Orphan JDI requests that lose the race are deleted from the
     * {@link EventRequestManager}.
     *
     * @return number of items promoted in this call
     */
    public int tryPromotePending(@Nullable JDIConnectionService jdiService) {
        if (jdiService == null) {
            return 0;
        }

        final VirtualMachine vm;
        final EventRequestManager erm;
        try {
            vm = jdiService.getRawVM();
            if (vm == null) {
                return 0;
            }
            erm = vm.eventRequestManager();
        } catch (Exception e) {
            return 0;
        }

        final List<Map.Entry<Integer, PendingBreakpoint>> lineSnapshot;
        final List<Map.Entry<Integer, PendingExceptionBreakpoint>> exceptionSnapshot;
        final List<Map.Entry<Integer, PendingFieldBreakpoint>> fieldSnapshot;
        synchronized (this) {
            lineSnapshot = new ArrayList<>(pendingBreakpointsById.entrySet());
            exceptionSnapshot = new ArrayList<>(pendingExceptionBreakpointsById.entrySet());
            fieldSnapshot = new ArrayList<>(pendingFieldBreakpointsById.entrySet());
        }

        int promoted = 0;

        for (Map.Entry<Integer, PendingBreakpoint> entry : lineSnapshot) {
            final int id = entry.getKey();
            final PendingBreakpoint pending = entry.getValue();
            if (pending.getFailureReason() != null) {
                continue;
            }

            try {
                // Passive lookup only — the listener path is the authoritative promoter for the
                // natural-load case, and force-loading from the safety net would re-introduce the
                // very side effects this codebase moved away from in GH issue #3 (early <clinit>,
                // masked lazy-load diagnostics, the deferred-class-load deadlock pattern).
                final ReferenceType refType = jdiService.findLoadedClass(pending.getClassName());
                if (refType == null) {
                    continue;
                }

                synchronized (this) {
                    // Recheck: the listener path (JdiEventListener.handleClassPrepareEvent) may
                    // have promoted or removed this entry between the unsynchronized passive
                    // lookup above and our re-acquire of the tracker monitor here.
                    if (pendingBreakpointsById.get(id) != pending
                        || pending.getFailureReason() != null
                        || breakpointsById.containsKey(id)) {
                        continue;
                    }

                    final List<Location> locations = refType.locationsOfLine(pending.getLineNumber());
                    if (locations.isEmpty()) {
                        continue;
                    }

                    final BreakpointRequest bp = erm.createBreakpointRequest(locations.get(0));
                    bp.setSuspendPolicy(pending.getSuspendPolicy());
                    bp.enable();
                    disarmIfChained(id, bp);
                    if (!promotePendingToActive(id, bp)) {
                        // Defense in depth: the recheck above already short-circuits in the common
                        // case, but promotePendingToActive is the authoritative guard. If the active
                        // map gained an entry between the recheck and this call (impossible while we
                        // hold the monitor, but cheap to assert), tear the orphan request down.
                        deleteQuietly(erm, bp);
                        continue;
                    }
                    promoted++;
                    log.info("[Tracker] Opportunistically promoted pending breakpoint {} for {}:{}",
                        id, pending.getClassName(), pending.getLineNumber());
                }
            } catch (AbsentInformationException e) {
                // Class loaded but no debug info — try again later in case a different version arrives
            } catch (Exception e) {
                log.debug("[Tracker] Failed to promote pending breakpoint {}: {}", id, e.getMessage());
            }
        }

        for (Map.Entry<Integer, PendingExceptionBreakpoint> entry : exceptionSnapshot) {
            final int id = entry.getKey();
            final PendingExceptionBreakpoint pending = entry.getValue();
            if (pending.getFailureReason() != null) {
                continue;
            }

            try {
                final ReferenceType refType = jdiService.findLoadedClass(pending.getExceptionClass());
                if (refType == null) {
                    continue;
                }

                synchronized (this) {
                    if (pendingExceptionBreakpointsById.get(id) != pending
                        || pending.getFailureReason() != null
                        || exceptionBreakpointsById.containsKey(id)) {
                        continue;
                    }

                    final ExceptionRequest exReq = erm.createExceptionRequest(
                        refType, pending.isCaught(), pending.isUncaught());
                    // Always SUSPEND_EVENT_THREAD: even logOnly BPs need a brief suspend so the
                    // listener can read the exception object / evaluate the optional expression.
                    // Auto-resume happens in JdiEventListener.handleExceptionEvent.
                    exReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    exReq.enable();
                    disarmIfChained(id, exReq);
                    if (!promotePendingExceptionToActive(id, exReq)) {
                        // Defense in depth — see the line BP arm above.
                        deleteQuietly(erm, exReq);
                        continue;
                    }
                    promoted++;
                    log.info("[Tracker] Opportunistically promoted pending exception breakpoint {} for {}",
                        id, pending.getExceptionClass());
                }
            } catch (Exception e) {
                log.debug("[Tracker] Failed to promote pending exception breakpoint {}: {}", id, e.getMessage());
            }
        }

        for (Map.Entry<Integer, PendingFieldBreakpoint> entry : fieldSnapshot) {
            final int id = entry.getKey();
            final PendingFieldBreakpoint pending = entry.getValue();
            if (pending.getFailureReason() != null) {
                continue;
            }
            try {
                final ReferenceType refType = jdiService.findLoadedClass(pending.getSpec().className());
                if (refType == null) {
                    continue;
                }
                // promoteSinglePendingField mutates state and is shared with the listener-side
                // path; the synchronized block below makes the recheck + creation + map mutation
                // atomic so a concurrent promotePendingFieldsForClass cannot wedge a duplicate
                // watchpoint onto the target VM.
                synchronized (this) {
                    if (pendingFieldBreakpointsById.get(id) != pending
                        || pending.getFailureReason() != null) {
                        continue;
                    }
                    if (promoteSinglePendingField(id, pending, refType, vm, erm, jdiService)) {
                        promoted++;
                    }
                }
            } catch (Exception e) {
                log.debug("[Tracker] Failed to promote pending field BP {}: {}", id, e.getMessage());
            }
        }

        return promoted;
    }

    /**
     * Synchronously promotes every pending field watchpoint that targets {@code refType.name()}.
     * Called from {@link JdiEventListener#handleClassPrepareEvent} so a field BP registered before
     * its class loaded catches static-initializer reads/writes too — the loading thread auto-resumes
     * immediately after this returns and runs {@code <clinit>}, so the promotion must be in place
     * before that point or the first events on the field are lost.
     *
     * @return number of pending field BPs successfully promoted
     */
    public synchronized int promotePendingFieldsForClass(ReferenceType refType, VirtualMachine vm,
                                                         EventRequestManager erm,
                                                         JDIConnectionService jdiService) {
        final String className = refType.name();
        int promoted = 0;
        // Snapshot the matching pending entries before iterating — promoteSinglePendingField mutates
        // pendingFieldBreakpointsById on success and on terminal failure.
        for (Map.Entry<Integer, PendingFieldBreakpoint> entry :
            new ArrayList<>(getPendingFieldBreakpointsForClass(className))) {
            final int id = entry.getKey();
            final PendingFieldBreakpoint pending = entry.getValue();
            if (pending.getFailureReason() != null) {
                continue;
            }
            try {
                if (promoteSinglePendingField(id, pending, refType, vm, erm, jdiService)) {
                    promoted++;
                }
            } catch (Exception e) {
                log.debug("[Tracker] Failed to promote pending field BP {} on class-load: {}", id, e.getMessage());
            }
        }
        return promoted;
    }

    /**
     * Resolves the field declared on {@code refType}, creates the appropriate watchpoint request(s)
     * per the spec mode, applies suspend policy / thread / instance filters, registers them with the
     * reverse indexes, and flips the pending entry to active. Returns {@code true} on success,
     * {@code false} when the field cannot be resolved (e.g. ambiguous / missing — pending is marked
     * failed with an explanatory reason). The {@code BOTH}-mode partial-creation rollback is
     * preserved so a half-armed pair never leaks onto the target VM.
     *
     * <p>Shared by {@link #tryPromotePending} (safety-net path) and
     * {@link #promotePendingFieldsForClass} (class-prepare path).
     */
    private boolean promoteSinglePendingField(int id, PendingFieldBreakpoint pending,
                                              ReferenceType refType, VirtualMachine vm,
                                              EventRequestManager erm, JDIConnectionService jdiService) {
        final FieldBreakpointSpec spec = pending.getSpec();

        final List<Field> candidates = refType.allFields().stream()
            .filter(f -> f.name().equals(spec.fieldName()))
            .toList();
        if (candidates.isEmpty()) {
            markPendingFieldFailed(id, String.format(
                "Field '%s' not found on %s or its supertypes", spec.fieldName(), spec.className()));
            return false;
        }
        if (candidates.size() > 1) {
            markPendingFieldFailed(id, String.format(
                "Field '%s' is ambiguous on %s (declared on %d types) — use a more specific className",
                spec.fieldName(), spec.className(), candidates.size()));
            return false;
        }
        final Field field = candidates.get(0);
        if (spec.objectFilterId() != null && field.isStatic()) {
            markPendingFieldFailed(id, String.format(
                "Field '%s' on %s is static; objectId filter does not apply",
                spec.fieldName(), spec.className()));
            return false;
        }

        // Two-step creation for BOTH-mode is split across two erm calls plus two
        // configureFieldRequest calls — any of them can throw on a VM in mid-transition. The
        // inner try/catch tracks every JDI request handed out so a half-creation can roll
        // back via erm.deleteEventRequest before re-throwing into the outer catch. Without
        // the rollback the access request would stay armed on the target VM with no tracker
        // entry pointing at it, and events on it would be delivered but immediately
        // discarded (findFieldInfoByRequest returns null).
        final List<EventRequest> createdRequests = new ArrayList<>(2);
        try {
            AccessWatchpointRequest accessReq = null;
            ModificationWatchpointRequest modReq = null;
            if (spec.mode() == FieldWatchMode.ACCESS || spec.mode() == FieldWatchMode.BOTH) {
                accessReq = erm.createAccessWatchpointRequest(field);
                createdRequests.add(accessReq);
                configureFieldRequest(accessReq, spec, vm, jdiService);
            }
            if (spec.mode() == FieldWatchMode.MODIFICATION || spec.mode() == FieldWatchMode.BOTH) {
                modReq = erm.createModificationWatchpointRequest(field);
                createdRequests.add(modReq);
                configureFieldRequest(modReq, spec, vm, jdiService);
            }

            if (accessReq != null) {
                disarmIfChained(id, accessReq);
            }
            if (modReq != null) {
                disarmIfChained(id, modReq);
            }
            promotePendingFieldToActive(id, accessReq, modReq);
            log.info("[Tracker] Promoted pending field BP {} for {}.{}",
                id, spec.className(), spec.fieldName());
            return true;
        } catch (Exception inner) {
            // Delete every half-created request so a retry cycle does not see orphan JDI
            // requests on the target VM. Per-request delete failures are swallowed — the
            // outer log entry below is enough diagnostic noise for what is already a
            // best-effort rollback path.
            for (EventRequest leaked : createdRequests) {
                try {
                    erm.deleteEventRequest(leaked);
                } catch (Exception ignore) {
                    // No-op: best-effort cleanup, do not mask the original failure.
                }
            }
            // Mark the pending entry failed so future promotion attempts skip it instead of
            // retrying the same orphan-prone path on every tool call.
            markPendingFieldFailed(id, "Failed during promotion: " + inner.getMessage());
            log.debug("[Tracker] Field BP {} promotion rolled back: {}", id, inner.getMessage());
            return false;
        }
    }

    /**
     * Applies suspend policy, thread filter, and instance filter to a freshly created
     * {@link WatchpointRequest}-style request, then enables it. Shared by the access and
     * modification creation paths during pending → active promotion. Throws when the spec
     * references a thread or object that cannot be resolved against the live VM.
     */
    private static void configureFieldRequest(WatchpointRequest req, FieldBreakpointSpec spec,
                                              VirtualMachine vm, JDIConnectionService jdiService)
        throws IllegalStateException {
        req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        if (spec.threadFilterId() != null) {
            final ThreadReference thread = vm.allThreads().stream()
                .filter(t -> t.uniqueID() == spec.threadFilterId())
                .findFirst().orElse(null);
            if (thread == null) {
                throw new IllegalStateException(
                    "Thread #" + spec.threadFilterId() + " no longer alive — cannot apply thread filter");
            }
            req.addThreadFilter(thread);
        }
        if (spec.objectFilterId() != null) {
            final ObjectReference instance = jdiService.getCachedObject(spec.objectFilterId());
            if (instance == null) {
                throw new IllegalStateException(
                    "Object #" + spec.objectFilterId() + " not in cache — cannot apply instance filter");
            }
            req.addInstanceFilter(instance);
        }
        req.enable();
    }

    /**
     * Adds the {@code req → info} and {@code req → id} entries to the field watchpoint reverse
     * indices in lockstep. Shared by {@link #registerFieldBreakpoint} and
     * {@link #promotePendingFieldToActive}; callers null-guard before invocation so a {@code BOTH}-mode
     * entry contributes two calls (one access, one modification) and a single-mode entry one.
     */
    private void indexFieldRequest(WatchpointRequest req, FieldBreakpointInfo info, int id) {
        fieldInfoByRequest.put(req, info);
        fieldIdsByRequest.put(req, id);
    }

    /**
     * Best-effort deletes the JDI request of the given field watchpoint — exceptions are swallowed
     * so a removal can still complete against a VM in mid-disconnect — then removes its
     * {@code req → info} and {@code req → id} reverse-index entries, in that order for the reason
     * {@link #removeBreakpoint} gives. Shared by the access and modification arms of
     * {@link #removeFieldBreakpoint}; callers null-guard before invocation.
     */
    private void tearDownFieldRequestQuietly(WatchpointRequest req) {
        try {
            req.virtualMachine().eventRequestManager().deleteEventRequest(req);
        } catch (Exception e) {
            // VM may already be disconnected
        }
        fieldInfoByRequest.remove(req);
        fieldIdsByRequest.remove(req);
    }

    // ── Chain (dependency) operations ──

    /**
     * Registers a chain edge: {@code dependentId} is only armed after {@code triggerId} fires.
     * Stores the relationship in both the primary map and the reverse index. Does NOT touch the
     * underlying JDI request — callers are responsible for {@code setEnabled(false)} on the
     * dependent's request right after registering, so the dependent is disarmed from the very
     * first event the JVM might deliver.
     * <p>
     * Idempotent for the {@code dependent → trigger} edge — re-registering the same pair simply
     * replaces the {@link TriggerLink} (e.g., to toggle {@code oneShot}). If a previous trigger
     * existed for this dependent, it is silently overwritten and the old reverse-index entry is
     * cleaned up.
     * <p>
     * Validates that the trigger is still a known BP at registration time (closing a TOCTOU window
     * where the boundary-level existence check at {@link JDWPTools} could be invalidated by a
     * concurrent removal before the edge is written) and that adding the edge does not introduce a
     * cycle. Multi-hop cycles like {@code 2 → 1 → 2} are rejected by walking the existing graph
     * from {@code triggerId} up to a chain root; if {@code dependentId} is encountered along the
     * way the registration is refused.
     *
     * @throws ChainRegistrationException when the trigger no longer exists or the edge would form
     *                                    a cycle
     */
    public synchronized void registerDependency(int dependentId, int triggerId, boolean oneShot) {
        if (!isKnownBreakpointId(triggerId)) {
            throw ChainRegistrationException.triggerMissing(triggerId);
        }
        final List<Integer> cyclePath = findCyclePath(dependentId, triggerId);
        if (cyclePath != null) {
            throw ChainRegistrationException.cycle(cyclePath);
        }
        final TriggerLink previous = dependencyByDependent.put(dependentId, new TriggerLink(triggerId, oneShot));
        if (previous != null && previous.triggerId() != triggerId) {
            removeReverseIndexEntry(previous.triggerId(), dependentId);
        }
        dependentsByTrigger.computeIfAbsent(triggerId, k -> ConcurrentHashMap.newKeySet()).add(dependentId);
    }

    /**
     * Walks the existing {@code dependency → trigger} chain starting at {@code triggerId} and
     * follows {@link #dependencyByDependent} edges until a chain root (no further dependency) is
     * reached. If {@code dependentId} is encountered along the way, adding the new edge
     * {@code dependentId → triggerId} would close a cycle. Returns the chain that would form the
     * cycle (starting at {@code dependentId}, ending back at {@code dependentId}) when a cycle is
     * detected, or {@code null} when the new edge is safe to add. The walk is bounded by the size
     * of {@link #dependencyByDependent} so it terminates even in the (currently impossible)
     * presence of a pre-existing cycle.
     */
    @Nullable
    private List<Integer> findCyclePath(int dependentId, int triggerId) {
        final int maxSteps = dependencyByDependent.size() + 1;
        final List<Integer> path = new ArrayList<>();
        path.add(dependentId);
        path.add(triggerId);
        int cursor = triggerId;
        for (int step = 0; step < maxSteps; step++) {
            if (cursor == dependentId) {
                return path;
            }
            final TriggerLink upstream = dependencyByDependent.get(cursor);
            if (upstream == null) {
                return null;
            }
            cursor = upstream.triggerId();
            path.add(cursor);
        }
        return null;
    }

    /**
     * Removes the chain edge for {@code dependentId}. Returns the previous {@link TriggerLink} so
     * callers can decide whether to log a {@code CHAIN_BROKEN} event. No-op when the dependent has
     * no chain configured.
     */
    @Nullable
    public synchronized TriggerLink clearDependency(int dependentId) {
        final TriggerLink previous = dependencyByDependent.remove(dependentId);
        if (previous != null) {
            removeReverseIndexEntry(previous.triggerId(), dependentId);
        }
        return previous;
    }

    /**
     * Removes {@code dependentId} from the reverse-index set keyed by {@code triggerId} and drops
     * the now-empty entry. Callers must already hold the tracker's intrinsic lock — the helper
     * relies on the surrounding {@code synchronized} method to keep the get/remove sequence atomic
     * against concurrent {@link #registerDependency} / {@link #clearDependency} calls.
     */
    private void removeReverseIndexEntry(int triggerId, int dependentId) {
        final Set<Integer> deps = dependentsByTrigger.get(triggerId);
        if (deps != null) {
            deps.remove(dependentId);
            if (deps.isEmpty()) {
                dependentsByTrigger.remove(triggerId);
            }
        }
    }

    /**
     * Returns the trigger relationship for {@code dependentId}, or {@code null} if the BP is not
     * chained. O(1) lookup used by the listener after every BP/exception fire to decide whether the
     * firing BP should re-disarm itself (one-shot mode).
     */
    @Nullable
    public TriggerLink getDependencyOfDependent(int dependentId) {
        return dependencyByDependent.get(dependentId);
    }

    /**
     * Returns an unmodifiable snapshot of the dependent IDs waiting on {@code triggerId} (empty if
     * none). Used by the JDI listener on every BP/exception event to find who to arm. The returned
     * set is a defensive copy — NOT a live view — so the caller can iterate without worrying about
     * concurrent mutations from another {@link #registerDependency} / {@link #clearDependency} call.
     */
    public Set<Integer> getDependentsOfTrigger(int triggerId) {
        final Set<Integer> deps = dependentsByTrigger.get(triggerId);
        return deps == null ? Set.of() : Set.copyOf(deps);
    }

    /**
     * Drops every chain edge whose trigger is {@code triggerId} and returns the dependent IDs that
     * were detached. Called from the trigger-removal code path in {@link JDWPTools} so the caller
     * can arm each dependent and emit a {@code CHAIN_BROKEN} event per detached edge.
     */
    public synchronized Set<Integer> clearDependentsOfTrigger(int triggerId) {
        final Set<Integer> deps = dependentsByTrigger.remove(triggerId);
        if (deps == null) {
            return Set.of();
        }
        for (Integer depId : deps) {
            dependencyByDependent.remove(depId);
        }
        return Set.copyOf(deps);
    }

    /**
     * Returns the JDI {@link EventRequest} for the given synthetic ID — checks active line BPs,
     * active exception BPs, then active field BPs (in that order). For a {@code BOTH}-mode field
     * BP, returns the access request when present, otherwise the modification request — chain
     * machinery treats them as one logical entity so either request suffices for {@code setEnabled}.
     * Returns {@code null} when the ID is pending, unknown, or already removed.
     */
    @Nullable
    public EventRequest getEventRequestById(int id) {
        final BreakpointRequest bp = breakpointsById.get(id);
        if (bp != null) {
            return bp;
        }
        final ExceptionBreakpointInfo exInfo = exceptionBreakpointsById.get(id);
        if (exInfo != null) {
            return exInfo.getRequest();
        }
        final FieldBreakpointInfo fieldInfo = fieldBreakpointsById.get(id);
        if (fieldInfo != null) {
            return fieldInfo.getAccessRequest() != null
                ? fieldInfo.getAccessRequest() : fieldInfo.getModificationRequest();
        }
        return null;
    }


    /**
     * Kind-agnostic enable/disable of every JDI request that backs the logical breakpoint
     * identified by {@code id}. Chain machinery treats a synthetic ID as "the BP" rather than
     * "one of its underlying JDI requests", and a {@code BOTH}-mode field BP binds one ID to two
     * requests (access + modification). Toggling only one half — what a single
     * {@link #getEventRequestById} result plus {@code setEnabled} would do — leaves the chain in
     * a split state where the next event of the unflipped kind still goes through (when arming)
     * or still fires (when disarming).
     * <p>
     * Lookup order matches {@link #getEventRequestById}: line BP, exception BP, field BP. For a
     * field BP both the access and modification requests are toggled when present. Pending and
     * unknown IDs are silent no-ops — the chain hot path must never throw on a stale ID because
     * a concurrent removal happened between the listener decision and the enable/disable call.
     * <p>
     * Per-request failures are caught and logged at trace level to match the chain handler's
     * tolerance for the "request was concurrently removed" race; the loop does not abort on a
     * single half failing.
     */
    public void setBreakpointEnabledById(int id, boolean enabled) {
        final BreakpointRequest bp = breakpointsById.get(id);
        if (bp != null) {
            try {
                bp.setEnabled(enabled);
            } catch (InvalidRequestStateException e) {
                log.trace("[Tracker] Line BP #{} setEnabled({}) ignored — request already removed: {}",
                    id, enabled, e.getMessage());
            }
            return;
        }
        final ExceptionBreakpointInfo exInfo = exceptionBreakpointsById.get(id);
        if (exInfo != null) {
            try {
                exInfo.getRequest().setEnabled(enabled);
            } catch (InvalidRequestStateException e) {
                log.trace("[Tracker] Exception BP #{} setEnabled({}) ignored — request already removed: {}",
                    id, enabled, e.getMessage());
            }
            return;
        }
        final FieldBreakpointInfo fieldInfo = fieldBreakpointsById.get(id);
        if (fieldInfo != null) {
            if (fieldInfo.getAccessRequest() != null) {
                try {
                    fieldInfo.getAccessRequest().setEnabled(enabled);
                } catch (InvalidRequestStateException e) {
                    log.trace("[Tracker] Field BP #{} access half setEnabled({}) ignored — request already removed: {}",
                        id, enabled, e.getMessage());
                }
            }
            if (fieldInfo.getModificationRequest() != null) {
                try {
                    fieldInfo.getModificationRequest().setEnabled(enabled);
                } catch (InvalidRequestStateException e) {
                    log.trace("[Tracker] Field BP #{} modification half setEnabled({}) ignored — request already removed: {}",
                        id, enabled, e.getMessage());
                }
            }
        }
    }

    /**
     * Returns {@code true} when {@code id} matches any tracked breakpoint — active line, active
     * exception, active field, pending line, pending exception, or pending field. Used by
     * {@link JDWPTools} to validate trigger IDs supplied to chain-aware tools without forcing the
     * caller to query six separate maps.
     */
    public boolean isKnownBreakpointId(int id) {
        return getEventRequestById(id) != null
            || pendingBreakpointsById.containsKey(id)
            || pendingExceptionBreakpointsById.containsKey(id)
            || pendingFieldBreakpointsById.containsKey(id);
    }

    /**
     * If {@code id} is registered as a chain dependent, disable the supplied JDI request so the
     * dependent stays disarmed until its trigger fires. Used during pending-to-active promotion —
     * a dependent that was chained while still pending must come up disabled, otherwise the very
     * first event could fire before the trigger ever has.
     * <p>
     * Honours the "trigger already fired" memory: if the dependent's trigger has fired at least
     * once before the promotion happens, the dependent comes up armed instead of disarmed. This
     * catches the case where the user installs a chained dependent for a class that gets loaded
     * only AFTER the trigger has already executed once — without this check, the dependent would
     * silently miss subsequent firings until the trigger fires again.
     * <p>
     * Any exception from {@link EventRequest#setEnabled} is allowed to propagate so the caller's
     * surrounding try/catch can mark the promotion failed and emit the appropriate log entry; this
     * is the historical behaviour of both call sites.
     *
     * @return {@code true} when the request was disabled (the BP was chained and its trigger has
     *         not fired yet), {@code false} otherwise
     */
    boolean disarmIfChained(int id, EventRequest request) {
        final TriggerLink link = dependencyByDependent.get(id);
        if (link == null) {
            return false;
        }
        if (triggersFiredAtLeastOnce.contains(link.triggerId())) {
            // Trigger has already fired at least once — leave the dependent enabled so it catches
            // the next hit at its location. The sticky/one-shot distinction still applies on the
            // listener side, but at the promotion boundary the dependent is treated as armed.
            return false;
        }
        request.setEnabled(false);
        return true;
    }

    /**
     * Records that {@code triggerId} has fired at least once. Called by the JDI listener after
     * every BP/exception hit, regardless of whether the BP currently has any dependents — a
     * future {@link #registerDependency} call may add one, and the dependent should still benefit
     * from the historical fire.
     * <p>
     * Note: {@link #registerDependency} deliberately does NOT auto-arm a freshly-registered
     * dependent even if {@code hasTriggerFired(triggerId)} is true. Arming-on-registration would
     * violate the user's explicit intent to "wait for the next trigger fire" — the persistence is
     * a safety net for pending → active promotion, not a shortcut for the chain CRUD path.
     */
    public void markTriggerFired(int triggerId) {
        triggersFiredAtLeastOnce.add(triggerId);
    }

    /**
     * Returns {@code true} when {@code triggerId} has fired at least once since attach (or since
     * the last {@link #reset} / {@link #clearAll}). Used by the pending → active promotion path
     * to decide whether a chained dependent should come up armed.
     */
    public boolean hasTriggerFired(int triggerId) {
        return triggersFiredAtLeastOnce.contains(triggerId);
    }

    // ── Thread tracking ──

    /**
     * Records the thread + BP id of a {@link EventKind#BREAKPOINT} hit so
     * {@link #getLastBreakpointThread} and {@code jdwp_get_current_thread} can resolve a default
     * thread when none is supplied. Use {@link #setLastSuspendingEvent} for STEP / EXCEPTION
     * events; the {@code -1} sentinel is preserved here only for back-compat with pre-{@link
     * EventKind} callers and is auto-classified to {@link EventKind#EXCEPTION} (with the id
     * normalised to {@code null}) by the {@link LastBreakpoint} compact constructor.
     * <p>
     * Publishes the snapshot atomically via a single volatile reference so concurrent readers
     * cannot observe a crossed pair (thread from event N with id from event N+1).
     *
     * @param thread       the thread that hit the BP
     * @param breakpointId synthetic breakpoint ID
     * @see #setLastSuspendingEvent(ThreadReference, EventKind)
     */
    public void setLastBreakpointThread(ThreadReference thread, int breakpointId) {
        publishOrCandidate(new LastBreakpoint(thread, breakpointId));
    }

    /**
     * Kind-aware variant of {@link #setLastBreakpointThread(ThreadReference, int)}. Used by the
     * JDI listener for STEP and EXCEPTION events so the renderer can label the suspension
     * source ("via=step" / "via=exception") instead of echoing the last-hit BP id verbatim.
     * <p>
     * STEP snapshots <b>inherit</b> the previous snapshot's BP id when one exists. The renderer
     * keys on {@link EventKind} (not on the id) when formatting the tag, so the inherited id is
     * invisible in user-facing output — but {@link #getLastBreakpointId()} still resolves to the
     * BP the user stepped FROM, which is the contract {@code jdwp_evaluate_watchers} relies on
     * when no explicit breakpointId is supplied. EXCEPTION snapshots carry {@code id=null}
     * because there is no associated BP context. Without the STEP id-inheritance, watchers
     * attached to a BP silently stopped resolving after the first step on the suspended thread.
     *
     * @param thread the thread that hit the suspending event
     * @param kind   the {@link EventKind} that produced the suspension (use the two-arg form for
     *               {@link EventKind#BREAKPOINT})
     * @see #setLastBreakpointThread(ThreadReference, int)
     */
    public void setLastSuspendingEvent(ThreadReference thread, EventKind kind) {
        final LastBreakpoint prior = this.lastBreakpoint;
        final Integer inheritedId = (kind == EventKind.STEP && prior != null) ? prior.id() : null;
        publishOrCandidate(new LastBreakpoint(thread, inheritedId, kind));
    }

    /**
     * While the dispatcher processes an event set under a {@link SuspensionSession}, a handler's
     * snapshot is a stop <em>candidate</em> of that set, published once the whole set is processed;
     * otherwise (no session — unit tests of single handlers) it is written at once.
     */
    private void publishOrCandidate(LastBreakpoint stop) {
        if (!SuspensionSession.candidate(stop)) {
            this.lastBreakpoint = stop;
        }
    }

    /**
     * Records a stop the session has just published as the latest snapshot; called by
     * {@link SuspensionSession}. Tools that default their thread use the session's live current
     * stop, not this snapshot.
     */
    void published(LastBreakpoint report) {
        this.lastBreakpoint = report;
    }

    /**
     * Opens the suspension-ownership session of a new connection, closing the previous one; called
     * by {@link JdiEventListener#start}.
     */
    public SuspensionSession openSuspensionSession(EventHistory history) {
        final SuspensionSession previous = suspensionSession;
        if (previous != null) {
            previous.close();
        }
        final SuspensionSession session = new SuspensionSession(this, history);
        suspensionSession = session;
        return session;
    }

    /**
     * The suspension-ownership session of the current connection, or {@code null} before the first
     * connection.
     */
    @Nullable
    public SuspensionSession suspensionSession() {
        return suspensionSession;
    }

    /**
     * Returns the last thread that hit a suspending event, or {@code null} if none.
     */
    @Nullable
    public ThreadReference getLastBreakpointThread() {
        final LastBreakpoint snapshot = lastBreakpoint;
        return snapshot != null ? snapshot.thread() : null;
    }

    /**
     * Returns the synthetic ID of the last breakpoint hit, or {@code null} if none.
     */
    @Nullable
    public Integer getLastBreakpointId() {
        final LastBreakpoint snapshot = lastBreakpoint;
        return snapshot != null ? snapshot.id() : null;
    }

    /**
     * Atomic snapshot of the last suspending JDI event. Callers that need a consistent
     * {@code (thread, id)} pair (e.g. {@code jdwp_get_current_thread}) must use this method
     * rather than the individual getters, which can otherwise observe values from two different
     * writes when called consecutively.
     *
     * @return the most recent {@link LastBreakpoint} snapshot, or {@code null} if no event has
     * fired since the last reset
     */
    @Nullable
    public LastBreakpoint getLastBreakpoint() {
        return lastBreakpoint;
    }

    /**
     * Arms a fresh single-shot latch that will be released the next time {@link #fireNextEvent()}
     * is called. Returns the latch — callers should arm BEFORE resuming the VM and then await on
     * the returned latch to avoid the race where the event fires between resume and arm.
     * <p>
     * Used by {@link JDWPTools#jdwp_resume_until_event(Integer)} to implement synchronous
     * "resume and wait for next stop". Replaces any previously-armed latch.
     */
    public synchronized CountDownLatch armNextEventLatch() {
        final CountDownLatch latch = new CountDownLatch(1);
        this.nextEventLatch = latch;
        return latch;
    }

    /**
     * Releases the currently-armed latch (if any) and clears it. Called by {@link JdiEventListener}
     * after every BP/step/exception event so that `jdwp_resume_until_event` can return.
     * <p>
     * `synchronized` so an arm + fire pair is atomic — without this lock, the JDI listener thread
     * could read a stale `nextEventLatch`, count down the wrong latch, and leave a fresh awaiter
     * hanging.
     */
    public synchronized void fireNextEvent() {
        if (SuspensionSession.isProcessing()) {
            // Publication at the end of the set notifies the session's waiters.
            return;
        }
        // Signal "something happened" independently of whether a latch is currently armed. A
        // waiter that arrives AFTER this method runs will still see the flag via
        // consumePendingFire() and short-circuit without re-resuming the VM.
        pendingFire = true;
        final CountDownLatch latch = this.nextEventLatch;
        if (latch != null) {
            latch.countDown();
            this.nextEventLatch = null;
        }
    }

    /**
     * Atomically reads and clears the "pending fire" flag. Returns {@code true} when an event
     * (BP / step / exception / VM_DEATH) has fired since the last call to this method and no
     * other waiter consumed it. The waiter is responsible for processing
     * {@link #getLastBreakpoint()} or the latest {@link EventHistory} entry based on the
     * returned value.
     * <p>
     * Used by {@link JDWPTools#jdwp_resume_until_event(Integer)} to close the
     * "event fires between two tool calls → no waiter armed yet → next resume_until_event
     * overshoots the just-suspended thread" window: the flag is set unconditionally by
     * {@link #fireNextEvent} so a late waiter sees the event without re-resuming the VM.
     */
    public synchronized boolean consumePendingFire() {
        if (pendingFire) {
            pendingFire = false;
            return true;
        }
        return false;
    }

    /**
     * Best-effort, in-memory-only state wipe. Counterpart to {@link #clearAll(EventRequestManager)}
     * for the "VM is dead" path: skips the JDI `deleteEventRequest` calls because they would fail
     * anyway when the target VM is unreachable. Called from {@link JDIConnectionService#cleanupSessionState}
     * during disconnect cleanup.
     */
    public synchronized void reset() {
        // Release any awaiter BEFORE we touch state — see fireNextEvent() for why this matters.
        fireNextEvent();
        clearAllInMemoryStateLocked();
    }

    /**
     * Classifies the JDI event behind a {@link LastBreakpoint} snapshot so the renderer can
     * distinguish "thread is parked because BP #N fired" from "thread is parked because a step
     * landed" or "…because an exception was thrown". Without this discriminator a {@link #STEP}
     * landing would inherit and re-render the previous {@code breakpoint=N} from the prior
     * snapshot, misleading the user about why the thread is parked.
     */
    public enum EventKind {
        /** A line / field / conditional breakpoint hit. */
        BREAKPOINT,
        /** A {@code StepRequest} landed (step into / over / out). No BP id is associated. */
        STEP,
        /** An exception event matched a tracked filter. No BP id is associated. */
        EXCEPTION,
        /** The VM is suspended at start ({@code suspend=y}). No BP id is associated. */
        VM_START
    }

    /**
     * Immutable {@code (thread, id, kind)} triple published atomically in {@link #lastBreakpoint}.
     * Using a record means readers either see a complete value from one write or no value at all
     * — never a crossed triple from two different writes. The {@code id} is the synthetic
     * breakpoint id when {@code kind == BREAKPOINT}; {@code null} for STEP / EXCEPTION snapshots.
     *
     * @param thread the thread that hit the suspending event
     * @param id     synthetic BP id for {@link EventKind#BREAKPOINT}; inherited from the prior
     *               snapshot for {@link EventKind#STEP}; always {@code null} for
     *               {@link EventKind#EXCEPTION} (normalised by the canonical constructor)
     * @param kind   the {@link EventKind} that produced the suspension
     */
    public record LastBreakpoint(ThreadReference thread, @Nullable Integer id, EventKind kind) {
        /**
         * Canonical constructor. Normalises {@code (kind, id)} consistency: EXCEPTION snapshots
         * always carry {@code id=null} because there is no associated BP context. BREAKPOINT and
         * STEP can carry an id (BREAKPOINT for the BP that fired; STEP for the BP the user
         * stepped FROM, inherited by {@link #setLastSuspendingEvent}).
         */
        public LastBreakpoint {
            if (kind == EventKind.EXCEPTION || kind == EventKind.VM_START) {
                id = null;
            }
        }

        /**
         * Back-compat alternate constructor for callers (and existing tests) that predate
         * {@link EventKind}. Implicit kind is {@link EventKind#BREAKPOINT}. A planted
         * {@code id == -1} from the legacy exception sentinel is auto-upgraded to
         * {@link EventKind#EXCEPTION} (the canonical constructor will then null the id).
         */
        public LastBreakpoint(ThreadReference thread, @Nullable Integer id) {
            this(thread, id, id != null && id == -1 ? EventKind.EXCEPTION : EventKind.BREAKPOINT);
        }
    }

    /**
     * Signals that {@link #registerDependency} refused a chain edge. Two reasons are distinguished:
     * the trigger no longer exists at registration time (atomic validation closing the TOCTOU
     * window with the boundary check) or adding the edge would close a cycle (multi-hop cycle
     * detection). Carries enough context for {@link JDWPTools} to translate the rejection into a
     * user-facing error string without re-walking the graph.
     */
    public static class ChainRegistrationException extends RuntimeException {
        public enum Reason {MISSING_TRIGGER, CYCLE}

        private final Reason reason;
        private final int triggerId;
        @Nullable
        private final List<Integer> cyclePath;

        private ChainRegistrationException(Reason reason, int triggerId, @Nullable List<Integer> cyclePath,
                                           String message) {
            super(message);
            this.reason = reason;
            this.triggerId = triggerId;
            this.cyclePath = cyclePath;
        }

        static ChainRegistrationException triggerMissing(int triggerId) {
            return new ChainRegistrationException(Reason.MISSING_TRIGGER, triggerId, null,
                String.format("Trigger breakpoint #%d no longer exists", triggerId));
        }

        static ChainRegistrationException cycle(List<Integer> path) {
            final StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) {
                    sb.append(" → ");
                }
                sb.append('#').append(path.get(i));
            }
            return new ChainRegistrationException(Reason.CYCLE, path.get(path.size() - 1),
                List.copyOf(path),
                String.format("Adding this dependency would create a cycle: %s", sb));
        }

        public Reason reason() {
            return reason;
        }

        public int triggerId() {
            return triggerId;
        }

        @Nullable
        public List<Integer> cyclePath() {
            return cyclePath;
        }
    }

    /**
     * Immutable snapshot of every breakpoint spec, metadata row, chain edge, and trigger-fire
     * memory currently held by the tracker. Returned by {@link #snapshotForReconnect()} and
     * replayed via {@link #restoreFromSnapshotAsPending(ReconnectSnapshot)}.
     *
     * <p>Lists / maps / sets are deep-copied via {@code List.copyOf} / {@code Map.copyOf} /
     * {@code Set.copyOf} so callers cannot mutate the snapshot after capture.
     *
     * @param lineBreakpoints            every line BP (active and pending), in capture order
     * @param exceptionBreakpoints       every exception BP (active and pending), in capture order
     * @param fieldBreakpoints           every field watchpoint (active and pending), in capture order
     * @param metadata                   per-BP condition / logpoint metadata, keyed by synthetic ID
     * @param dependencies               chain edges — dependent ID → {@link TriggerLink}
     * @param triggersFiredAtLeastOnce   set of trigger IDs that have already fired at least once
     */
    public record ReconnectSnapshot(
        List<LineBreakpointEntry> lineBreakpoints,
        List<ExceptionBreakpointEntry> exceptionBreakpoints,
        List<FieldBreakpointEntry> fieldBreakpoints,
        Map<Integer, MetadataSnapshot> metadata,
        Map<Integer, TriggerLink> dependencies,
        Set<Integer> triggersFiredAtLeastOnce
    ) {}

    /**
     * Captured line breakpoint spec. Used by {@link ReconnectSnapshot} so the reconnect path can
     * replay an active OR pending line BP under its original synthetic ID without losing the
     * suspend policy.
     *
     * @param id                  synthetic BP id from the previous session — restored verbatim
     * @param className           fully qualified class name the BP is set in
     * @param lineNumber          source line number where the BP is set
     * @param suspendPolicy       JDI {@link EventRequest} {@code SUSPEND_*} constant
     * @param suspendPolicyLabel  human-readable label ("all" / "thread" / "none") round-tripped
     *                            by {@code suspendPolicyLabel(int)}
     */
    public record LineBreakpointEntry(
        int id, String className, int lineNumber, int suspendPolicy, String suspendPolicyLabel
    ) {}

    /**
     * Captured exception breakpoint spec — wraps the existing {@link ExceptionBreakpointSpec}.
     *
     * @param id    synthetic BP id from the previous session — restored verbatim
     * @param spec  the immutable exception BP spec, replayed against the fresh VM as-is
     */
    public record ExceptionBreakpointEntry(int id, ExceptionBreakpointSpec spec) {}

    /**
     * Captured field watchpoint spec — wraps the existing {@link FieldBreakpointSpec}.
     *
     * @param id    synthetic BP id from the previous session — restored verbatim
     * @param spec  the immutable field watchpoint spec, replayed against the fresh VM as-is
     */
    public record FieldBreakpointEntry(int id, FieldBreakpointSpec spec) {}

    /**
     * Captured metadata row from {@link BreakpointMetadata}. Both fields nullable because each is
     * independently set: a BP can have a condition without a logpoint expression and vice versa.
     *
     * @param condition           user-supplied conditional expression, or {@code null} when none
     * @param logpointExpression  user-supplied logpoint expression, or {@code null} when the BP is
     *                            not a logpoint
     */
    public record MetadataSnapshot(@Nullable String condition, @Nullable String logpointExpression) {}

    /**
     * Chain edge metadata: which trigger BP must fire before the dependent is armed, plus the
     * {@code oneShot} flag controlling whether the dependent re-disarms itself after each hit.
     * <ul>
     *   <li>{@code oneShot=false} (default, "sticky"): the dependent stays armed forever after the
     *       trigger first fires. To re-engage the chain, call {@code jdwp_disarm_until_trigger}.</li>
     *   <li>{@code oneShot=true} (IntelliJ-style): after each dependent fire, the listener disarms
     *       the dependent again, so the next trigger fire re-arms it.</li>
     * </ul>
     */
    public record TriggerLink(int triggerId, boolean oneShot) {
    }

    // ── Inner class ──

    /**
     * A breakpoint registered for a class that is not yet loaded by the JVM.
     * Will be promoted to an active breakpoint when the class loads.
     */
    public static class PendingBreakpoint {
        private final String className;
        private final int lineNumber;
        private final int suspendPolicy;
        private final String suspendPolicyLabel;
        @Nullable
        private volatile String failureReason;

        public PendingBreakpoint(String className, int lineNumber, int suspendPolicy, String suspendPolicyLabel) {
            this.className = className;
            this.lineNumber = lineNumber;
            this.suspendPolicy = suspendPolicy;
            this.suspendPolicyLabel = suspendPolicyLabel;
        }

        public String getClassName() {
            return className;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public int getSuspendPolicy() {
            return suspendPolicy;
        }

        public String getSuspendPolicyLabel() {
            return suspendPolicyLabel;
        }

        @Nullable
        public String getFailureReason() {
            return failureReason;
        }

        /**
         * Records why this pending breakpoint could not be activated (e.g., no executable code at line).
         */
        public void setFailureReason(@Nullable String failureReason) {
            this.failureReason = failureReason;
        }
    }

    /**
     * Metadata for a breakpoint: optional condition expression and/or logpoint expression.
     */
    public static class BreakpointMetadata {
        /**
         * Boolean expression evaluated against frame 0 by {@link JdiEventListener#evaluateCondition}; `null` until set.
         */
        @Nullable
        volatile String condition;
        /**
         * Logpoint expression — when non-null, the breakpoint auto-resumes and records the result to {@link EventHistory}.
         */
        @Nullable
        volatile String logpointExpression;
    }

    /**
     * Tracks an exception breakpoint created via JDI ExceptionRequest. When {@code spec.logOnly()}
     * is true, {@link JdiEventListener#handleExceptionEvent} records an {@code EXCEPTION_LOG} entry
     * and auto-resumes the firing thread instead of leaving it suspended; the optional
     * {@code spec.expression()} is evaluated against the throwing frame with {@code $exception}
     * bound to the thrown object and the result is attached to the log entry (or recorded as
     * {@code EXCEPTION_LOG_ERROR} on evaluation failure).
     */
    public static class ExceptionBreakpointInfo {
        final ExceptionRequest request;
        private final ExceptionBreakpointSpec spec;

        public ExceptionBreakpointInfo(ExceptionBreakpointSpec spec, ExceptionRequest request) {
            this.spec = spec;
            this.request = request;
        }

        public ExceptionBreakpointSpec getSpec() {
            return spec;
        }

        public String getExceptionClass() {
            return spec.exceptionClass();
        }

        public boolean isCaught() {
            return spec.caught();
        }

        public boolean isUncaught() {
            return spec.uncaught();
        }

        public boolean isLogOnly() {
            return spec.logOnly();
        }

        @Nullable
        public String getExpression() {
            return spec.expression();
        }

        public ExceptionRequest getRequest() {
            return request;
        }
    }

    /**
     * An exception breakpoint registered for a class that is not yet loaded by the JVM.
     * Will be promoted to an active exception breakpoint when the class loads.
     */
    public static class PendingExceptionBreakpoint {
        private final ExceptionBreakpointSpec spec;
        @Nullable
        private volatile String failureReason;

        public PendingExceptionBreakpoint(ExceptionBreakpointSpec spec) {
            this.spec = spec;
        }

        public ExceptionBreakpointSpec getSpec() {
            return spec;
        }

        public String getExceptionClass() {
            return spec.exceptionClass();
        }

        public boolean isCaught() {
            return spec.caught();
        }

        public boolean isUncaught() {
            return spec.uncaught();
        }

        public boolean isLogOnly() {
            return spec.logOnly();
        }

        @Nullable
        public String getExpression() {
            return spec.expression();
        }

        @Nullable
        public String getFailureReason() {
            return failureReason;
        }

        /**
         * Records why this pending exception breakpoint could not be activated (mirrors {@link PendingBreakpoint#setFailureReason}).
         */
        public void setFailureReason(@Nullable String failureReason) {
            this.failureReason = failureReason;
        }
    }

    /**
     * User-facing options bundle for an exception breakpoint. Captures the same flags accepted by
     * {@code jdwp_set_exception_breakpoint}, in a shape that travels through the active and pending
     * tracker records and across pending → active promotion without callers having to repeat
     * positional arguments. Construct via {@link #suspending} or {@link #logOnly} for the common
     * cases.
     */
    public record ExceptionBreakpointSpec(
        String exceptionClass,
        boolean caught,
        boolean uncaught,
        boolean logOnly,
        @Nullable String expression
    ) {
        /**
         * Default suspending behaviour: the throwing thread is parked at the throw site for
         * inspection and no expression is evaluated.
         */
        public static ExceptionBreakpointSpec suspending(String exceptionClass, boolean caught, boolean uncaught) {
            return new ExceptionBreakpointSpec(exceptionClass, caught, uncaught, false, null);
        }

        /**
         * Log-only behaviour: the listener records an {@code EXCEPTION_LOG} event and auto-resumes
         * the throwing thread. {@code expression} (optional, may be {@code null}) is evaluated
         * against the throwing frame with {@code $exception} bound to the thrown object — pass
         * {@code null} for pure log-only, or e.g. {@code "$exception.getMessage()"} for an enriched
         * trace.
         */
        public static ExceptionBreakpointSpec logOnly(String exceptionClass, boolean caught, boolean uncaught,
                                                      @Nullable String expression) {
            return new ExceptionBreakpointSpec(exceptionClass, caught, uncaught, true, expression);
        }
    }

    /**
     * Watch direction for a field watchpoint:
     * <ul>
     *   <li>{@code ACCESS} — fires on every read of the field</li>
     *   <li>{@code MODIFICATION} — fires on every write of the field</li>
     *   <li>{@code BOTH} — binds one synthetic ID to two JDI requests (one of each kind);
     *       increments / compound assignments fire two events per source line</li>
     * </ul>
     */
    public enum FieldWatchMode {ACCESS, MODIFICATION, BOTH}

    /**
     * User-facing options bundle for a field watchpoint. Captures the same flags accepted by
     * {@code jdwp_set_field_breakpoint} and {@code jdwp_set_field_logpoint} in a shape that travels
     * through the active and pending tracker records and across pending → active promotion.
     * Construct via {@link #suspending} or {@link #logOnly}.
     */
    public record FieldBreakpointSpec(
        String className,
        String fieldName,
        FieldWatchMode mode,
        @Nullable String condition,
        boolean logOnly,
        @Nullable String expression,
        @Nullable Long threadFilterId,
        @Nullable Long objectFilterId,
        boolean excludeConstructors
    ) {
        /**
         * Default suspending behaviour: the firing thread is parked at the field access / modification
         * site for inspection. {@code condition} (optional, may be {@code null}) is evaluated with
         * the field-event synthetic bindings ({@code $oldValue}, {@code $newValue}, {@code $object},
         * {@code $fieldName}, {@code $mode}) bound on each hit — false skips the suspend.
         */
        public static FieldBreakpointSpec suspending(String className, String fieldName, FieldWatchMode mode,
                                                     @Nullable Long threadFilterId, @Nullable Long objectFilterId,
                                                     @Nullable String condition) {
            return suspending(className, fieldName, mode, threadFilterId, objectFilterId, condition, false);
        }

        /**
         * Suspending variant with {@code excludeConstructors}: when {@code true}, writes that happen
         * inside the declaring class's {@code <init>} / {@code <clinit>} are filtered out by the
         * listener (no event recorded, no chain trigger, no suspend) so the BP only fires on
         * post-construction writes. Useful when a field is set by many constructors and you only care
         * about later mutations.
         */
        public static FieldBreakpointSpec suspending(String className, String fieldName, FieldWatchMode mode,
                                                     @Nullable Long threadFilterId, @Nullable Long objectFilterId,
                                                     @Nullable String condition, boolean excludeConstructors) {
            return new FieldBreakpointSpec(className, fieldName, mode, condition, false, null,
                threadFilterId, objectFilterId, excludeConstructors);
        }

        /**
         * Log-only behaviour: the listener records a {@code FIELD_LOGPOINT} event and auto-resumes
         * the firing thread. {@code expression} is evaluated against the firing frame with the
         * field-event synthetic bindings bound; {@code condition} (optional) gates whether the
         * expression is evaluated and the event recorded.
         */
        public static FieldBreakpointSpec logOnly(String className, String fieldName, FieldWatchMode mode,
                                                  String expression, @Nullable Long threadFilterId,
                                                  @Nullable Long objectFilterId, @Nullable String condition) {
            return logOnly(className, fieldName, mode, expression, threadFilterId, objectFilterId, condition, false);
        }

        /**
         * Log-only variant with {@code excludeConstructors}: identical to {@link #logOnly} but
         * suppresses logpoint hits that happen inside the declaring class's {@code <init>} /
         * {@code <clinit>}.
         */
        public static FieldBreakpointSpec logOnly(String className, String fieldName, FieldWatchMode mode,
                                                  String expression, @Nullable Long threadFilterId,
                                                  @Nullable Long objectFilterId, @Nullable String condition,
                                                  boolean excludeConstructors) {
            return new FieldBreakpointSpec(className, fieldName, mode, condition, true, expression,
                threadFilterId, objectFilterId, excludeConstructors);
        }
    }

    /**
     * Tracks a field watchpoint created via one or two JDI {@link WatchpointRequest}s. {@code BOTH}
     * mode populates both {@code accessRequest} and {@code modificationRequest}; the other two
     * modes populate exactly one. Listener handlers consult {@link #getSpec()} to decide between
     * suspending and log-only paths and to read filter / expression metadata.
     */
    public static class FieldBreakpointInfo {
        private final FieldBreakpointSpec spec;
        @Nullable
        private final AccessWatchpointRequest accessRequest;
        @Nullable
        private final ModificationWatchpointRequest modificationRequest;

        /**
         * Precondition: at least one of {@code accessRequest} / {@code modificationRequest} is
         * non-null; {@code BOTH} mode passes both.
         */
        public FieldBreakpointInfo(FieldBreakpointSpec spec,
                                   @Nullable AccessWatchpointRequest accessRequest,
                                   @Nullable ModificationWatchpointRequest modificationRequest) {
            this.spec = spec;
            this.accessRequest = accessRequest;
            this.modificationRequest = modificationRequest;
        }

        public FieldBreakpointSpec getSpec() {
            return spec;
        }

        @Nullable
        public AccessWatchpointRequest getAccessRequest() {
            return accessRequest;
        }

        @Nullable
        public ModificationWatchpointRequest getModificationRequest() {
            return modificationRequest;
        }
    }

    /**
     * A field watchpoint registered for a declaring class that is not yet loaded. Will be promoted
     * to an active {@link FieldBreakpointInfo} when the class loads.
     */
    public static class PendingFieldBreakpoint {
        private final FieldBreakpointSpec spec;
        @Nullable
        private volatile String failureReason;

        /**
         * {@code failureReason} starts {@code null} and is populated later by
         * {@link #setFailureReason} via {@link BreakpointTracker#markPendingFieldFailed}.
         */
        public PendingFieldBreakpoint(FieldBreakpointSpec spec) {
            this.spec = spec;
        }

        public FieldBreakpointSpec getSpec() {
            return spec;
        }

        /**
         * @return the recorded failure reason from a prior promotion attempt, or {@code null} when
         *         this pending entry has not yet failed promotion
         */
        @Nullable
        public String getFailureReason() {
            return failureReason;
        }

        /**
         * Records why this pending field watchpoint could not be activated.
         */
        public void setFailureReason(@Nullable String failureReason) {
            this.failureReason = failureReason;
        }
    }
}
