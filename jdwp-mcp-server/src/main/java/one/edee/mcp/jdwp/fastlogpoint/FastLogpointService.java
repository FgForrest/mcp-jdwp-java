package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.*;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import jakarta.annotation.PreDestroy;
import one.edee.mcp.jdwp.BreakpointTracker;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.JdiEventListener;
import one.edee.mcp.jdwp.evaluation.FastBreakpointProbeSource;
import one.edee.mcp.jdwp.evaluation.FastLogpointProbe;
import one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.RemoteCodeExecutor;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiClassDefinitionException;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns every fast logpoint ({@code jdwp_set_fast_logpoint}): registration, arming, reading the
 * in-target ring buffers, and restoring the original bytecode.
 * <p>
 * It owns every fast breakpoint ({@code jdwp_set_fast_breakpoint}) the same way: same arming,
 * same instrumentation (one class's probes of both kinds share its {@link InstrumentedClass}), same
 * restore. The probe evaluates the condition and calls its {@code stop()} method, which carries a
 * breakpoint this service owns ({@link #handleStopHit}). Its arming hit evaluates the condition
 * through the debugger: a hit that stops is published at once and installs nothing, because
 * redefining the method would leave the stopped frame in an obsolete method without locals.
 * <p>
 * <b>Lifecycle of one fast logpoint.</b>
 * <ol>
 *   <li>{@link #register} sets a one-shot ({@code addCountFilter(1)}) {@code SUSPEND_EVENT_THREAD}
 *       breakpoint at the line. State {@link State#PENDING}.</li>
 *   <li>{@link JdiEventListener} routes that breakpoint's event to {@link #handleArmingHit}. While
 *       the one thread is paused (method invocation needs a thread suspended by an event): the hit
 *       itself is logged through the debugger (it runs the old code), the class bytes are read from
 *       the class's loader and checked against the live bytecode, the probe class is compiled,
 *       defined in the declaring class's own loader and initialized (disabled), the class is
 *       redefined with a call to the probe at the line, the bytecode HotSpot installed is read back
 *       and recorded in the target, and the probe is enabled. State {@link State#ARMED}.</li>
 *   <li>{@link #drainAll} copies new ring entries into the {@link EventHistory} — static-field and
 *       array reads only, no suspended thread and no method invocation.</li>
 *   <li>{@link #clear} / {@link #disarmAll} flip the probe's kill switch, drain one last time and
 *       redefine the class back (minus this probe).</li>
 * </ol>
 * <b>Leftovers.</b> Each probe describes itself (target, expression, arming session, install state)
 * and the probes of a class share a {@link GenerationRecord} holding the original class file and the
 * bytecode installed with them. {@link #discover} runs on every attach and lists fast logpoints an
 * earlier session left behind — after a crash, or a teardown that timed out — as
 * {@link State#RECOVERED} (the class is exactly as that session left it) or {@link State#STRANDED}
 * (it is not, or the wiring is unknown). This session never owns them: clear only sets their kill
 * switch, teardown leaves them alone, and only {@link #restore} with the user's approval redefines
 * their class.
 * <p>
 * <b>Breakpoints survive redefinition.</b> JDI deletes every {@code BreakpointRequest} located in a
 * redefined class. {@link #redefine} snapshots the tracked line breakpoints and the pending arming
 * requests in the class first and re-creates them on the new bytecode, with the same ID, suspend
 * policy and enabled state.
 * <p>
 * <b>Threading.</b> Arming runs on the JDI event dispatcher thread while the listener thread keeps
 * draining events, so a breakpoint hit inside a method arming invokes is suppressed and resumed (see
 * {@link JdiEventListener}); everything else runs on MCP worker threads. Mutation of logpoint state
 * and every redefinition happens under this service's monitor.
 * The slow part of arming (evaluation, compilation, method invocations) runs <em>outside</em> it so a
 * teardown never waits behind a compile; the locked install step re-checks that the logpoint was
 * not cleared meanwhile. Teardown ({@link #disarmAll}) and discovery are bounded by
 * {@link #TEARDOWN_TIMEOUT_MS} because a JDWP call on a wedged VM would block forever.
 */
@Service
public class FastLogpointService {
    private static final Logger log = LoggerFactory.getLogger(FastLogpointService.class);

    /**
     * Ring capacity when the caller does not choose one.
     */
    public static final int DEFAULT_BUFFER_SIZE = 4096;
    /**
     * Smallest accepted ring capacity.
     */
    public static final int MIN_BUFFER_SIZE = 16;
    /**
     * Largest accepted ring capacity; each slot holds one string per logged hit.
     */
    public static final int MAX_BUFFER_SIZE = 65536;
    /**
     * Upper bound for one teardown ({@link #disarmAll}) or discovery ({@link #discover}).
     */
    private static final long TEARDOWN_TIMEOUT_MS = 5_000L;
    /**
     * An entry still missing this long after its sequence number was claimed counts as lost — the
     * writing thread died or failed between claiming the number and storing the entry.
     */
    private static final long STALLED_ENTRY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);
    /**
     * Chunk size of the remote {@code InputStream.read} loop that fetches class bytes.
     */
    private static final int CLASS_READ_CHUNK = 64 * 1024;
    /**
     * Refuse class files larger than this (the JVM limit is far lower in practice).
     */
    private static final int MAX_CLASS_FILE_BYTES = 16 * 1024 * 1024;
    /**
     * Discovery skips install records larger than this: a record holds one class file and the
     * code of its methods.
     */
    private static final int MAX_RECORD_BYTES = 3 * MAX_CLASS_FILE_BYTES;
    /**
     * Packages whose classes never host a fast logpoint.
     */
    private static final List<String> PLATFORM_PACKAGE_PREFIXES =
        List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");
    /**
     * Boxed primitive types whose arming-hit value is rendered by its {@code value} field.
     */
    private static final Set<String> BOXED_TYPES = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short",
        "java.lang.Byte", "java.lang.Character", "java.lang.Boolean", "java.lang.Float", "java.lang.Double");

    private final BreakpointTracker breakpointTracker;
    private final EventHistory eventHistory;
    private final EvaluationGuard evaluationGuard;
    private final JdiExpressionEvaluator expressionEvaluator;
    /**
     * Written into every probe this server arms ({@code MCP_ARMED_BY}): the product and an opaque
     * per-process session ID — no host name, no PID.
     */
    private final String armedBy;

    /**
     * Every fast logpoint by ID, regardless of state. Concurrent so {@link #forgetAll} can wipe it
     * without the monitor when a teardown is stuck inside a JDWP call.
     */
    private final Map<Integer, FastLogpoint> logpoints = new ConcurrentHashMap<>();
    /**
     * Pending arming request → fast logpoint, for O(1) routing of breakpoint events.
     */
    private final Map<EventRequest, FastLogpoint> byArmingRequest = new ConcurrentHashMap<>();
    /**
     * Stop request on a fast breakpoint probe's {@code stop()} → fast breakpoint.
     */
    private final Map<EventRequest, FastLogpoint> byStopRequest = new ConcurrentHashMap<>();
    /**
     * Stop requests deleted while an event of theirs may still be in flight: such a stop is resumed,
     * never published.
     */
    private final Set<EventRequest> retiredStopRequests = ConcurrentHashMap.newKeySet();
    /**
     * Instrumentation state per class this session redefined.
     */
    private final Map<ReferenceType, InstrumentedClass> instrumentedClasses = new ConcurrentHashMap<>();
    /**
     * Bumped whenever the session state is dropped; a discovery started before publishes nothing.
     */
    private final AtomicLong sessionEpoch = new AtomicLong();
    /**
     * Makes {@link #forgetAll}'s epoch bump and wipe atomic with {@link #publish}'s epoch check and
     * insertion. Never held across a JDWP call.
     */
    private final Object epochLock = new Object();
    /**
     * Set when the last discovery did not finish; {@link #discoverIfIncomplete} retries it.
     */
    private volatile boolean discoveryIncomplete;
    /**
     * Set when the last {@link #disarmAll} gave up on a VM that did not answer; its worker may still
     * hold the monitor.
     */
    private volatile boolean teardownStuck;

    public FastLogpointService(BreakpointTracker breakpointTracker, EventHistory eventHistory,
                               EvaluationGuard evaluationGuard, JdiExpressionEvaluator expressionEvaluator,
                               JdiEventListener eventListener, JDIConnectionService connectionService) {
        this.breakpointTracker = breakpointTracker;
        this.eventHistory = eventHistory;
        this.evaluationGuard = evaluationGuard;
        this.expressionEvaluator = expressionEvaluator;
        this.armedBy = "mcp-jdwp-java, session " + UUID.randomUUID().toString().substring(0, 8);
        // Same self-registration pattern as JDIConnectionService's VM-death hook: the listener routes
        // arming hits here, the connection service disarms before it disposes the VM.
        eventListener.setFastLogpointService(this);
        connectionService.setFastLogpointService(this);
    }

    /**
     * Whether a class may host a fast logpoint at all: not a platform class and not loaded by the
     * bootstrap loader (the probe must be defined in the declaring class's own loader).
     *
     * @return {@code null} when allowed, otherwise the reason it is not
     */
    @Nullable
    public static String rejectHost(ReferenceType refType) {
        final String name = refType.name();
        for (String prefix : PLATFORM_PACKAGE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return "platform classes (" + prefix + "*) cannot host a fast logpoint";
            }
        }
        if (refType.classLoader() == null) {
            return "classes loaded by the bootstrap class loader cannot host a fast logpoint";
        }
        return null;
    }

    /**
     * Registers a fast logpoint in {@link State#PENDING} state: a one-shot breakpoint at
     * {@code location} that arms the probe on its first hit.
     *
     * @return the new fast logpoint's ID (shared ID space with every other breakpoint kind)
     */
    public synchronized int register(VirtualMachine vm, Location location, String expression,
                                     @Nullable String condition, int bufferSize) {
        return registerEntry(vm, location, Kind.LOGPOINT, expression, condition, bufferSize,
            EventRequest.SUSPEND_EVENT_THREAD, false);
    }

    /**
     * Registers a fast breakpoint in {@link State#PENDING} state: a one-shot breakpoint at
     * {@code location}, with {@code suspendPolicy}, whose first hit evaluates the condition through
     * the debugger and then either stops or arms the probe (see {@link #handleArmingHit}).
     *
     * @param suspendPolicy {@link EventRequest#SUSPEND_ALL} or {@link EventRequest#SUSPEND_EVENT_THREAD};
     *                      used by the arming request and by the probe's stop request
     * @param stopOnce      whether only the first true hit of an epoch stops
     * @return the new fast breakpoint's ID (shared ID space with every other breakpoint kind)
     */
    public synchronized int registerBreakpoint(VirtualMachine vm, Location location, String condition, boolean stopOnce,
                                               int suspendPolicy, int bufferSize) {
        return registerEntry(vm, location, Kind.BREAKPOINT, "", condition, bufferSize, suspendPolicy, stopOnce);
    }

    private int registerEntry(VirtualMachine vm, Location location, Kind kind, String expression,
                              @Nullable String condition, int bufferSize, int suspendPolicy, boolean stopOnce) {
        final int id = breakpointTracker.allocateId();
        final Method method = location.method();
        final ReferenceType refType = location.declaringType();
        final FastLogpoint flp = new FastLogpoint(id, kind, vm, refType, refType.name(), location.lineNumber(),
            method.name(), method.signature(), expression, condition, bufferSize, suspendPolicy, stopOnce);
        final BreakpointRequest request = createArmingRequest(vm.eventRequestManager(), location, suspendPolicy);
        flp.armingRequest = request;
        logpoints.put(id, flp);
        byArmingRequest.put(request, flp);
        request.enable();
        log.info("[{}] #{} registered at {}:{} — arms on first hit", kind.tag, id, flp.className(), flp.line);
        return id;
    }

    /**
     * Whether {@code id} names a fast logpoint or a fast breakpoint (any state, including leftovers).
     */
    public boolean isFastLogpoint(int id) {
        return logpoints.containsKey(id);
    }

    /**
     * Whether a breakpoint event belongs to a fast logpoint's arming request. An event without a
     * request (its request was deleted while the event was in flight) is claimed when its location
     * is a fast logpoint's line, so the listener resumes the thread instead of parking it as an
     * untracked hit.
     */
    public boolean claimsArmingEvent(BreakpointEvent event) {
        final EventRequest request = event.request();
        if (request != null) {
            return byArmingRequest.containsKey(request);
        }
        final Location location = event.location();
        for (FastLogpoint flp : logpoints.values()) {
            if (!flp.isLeftover() && flp.refType.equals(location.declaringType())
                && flp.line == location.lineNumber()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Arms the fast logpoint or fast breakpoint whose arming breakpoint {@code event} reports. Runs
     * on the JDI event dispatcher thread with the event's thread suspended. Never throws — a failure
     * marks the entry {@link State#FAILED} and records {@code FAST_LOGPOINT_ARM_FAILED}.
     *
     * @return whether the hit was published as a fast-breakpoint stop and its set stays suspended;
     * otherwise the dispatcher resumes it
     */
    public boolean handleArmingHit(BreakpointEvent event) {
        final FastLogpoint flp = armingLogpoint(event);
        if (flp == null) {
            return false;
        }
        final ThreadReference thread = event.thread();
        if (evaluationGuard.isEvaluating(thread)) {
            // The thread is inside an MCP-driven method invocation; invoking on it again from here
            // could deadlock. The one-shot request is spent, so put a fresh one in its place.
            rearm(flp);
            return false;
        }
        final long threadId = thread.uniqueID();
        evaluationGuard.enter(threadId);
        try {
            return arm(flp, thread);
        } catch (Exception | LinkageError e) {
            markFailed(flp, describe(e), !(e instanceof UncertainRedefinitionException));
            return false;
        } finally {
            evaluationGuard.exit(threadId);
        }
    }

    /**
     * Whether a breakpoint event comes from a fast breakpoint probe's stop request, current or
     * retired. Such an event is never an ordinary breakpoint hit.
     */
    public boolean claimsStopEvent(BreakpointEvent event) {
        final EventRequest request = event.request();
        return request != null && (byStopRequest.containsKey(request) || retiredStopRequests.contains(request));
    }

    /**
     * Publishes the stop a fast breakpoint probe's {@code stop()} reports, unless it is stale: its
     * fast breakpoint was cleared, or (for a stop that claimed a gate) the gate was re-armed or
     * cancelled since. Runs on the JDI event dispatcher thread. Never throws.
     *
     * @return whether the stop was published and its set stays suspended; otherwise the dispatcher
     * resumes it
     */
    public boolean handleStopHit(BreakpointEvent event) {
        final EventRequest request = event.request();
        final FastLogpoint flp = request != null ? byStopRequest.get(request) : null;
        if (flp == null) {
            log.debug("[FastBreakpoint] Stop through a retired request at {} — resuming", event.location());
            return false;
        }
        final ThreadReference thread = event.thread();
        final StopArguments stop = stopArguments(thread);
        final Location location = userLocation(thread, event.location());
        synchronized (this) {
            if (logpoints.get(flp.id) != flp || flp.stopRequest != request) {
                return false;
            }
            if (stop != null && !isCurrent(flp, stop)) {
                eventHistory.record(new EventHistory.DebugEvent("FAST_BREAKPOINT_STALE",
                    String.format("[FastBreakpoint %d] a stop claimed before the breakpoint was re-armed reached the "
                        + "debugger late on thread %s — resumed, not reported", flp.id, thread.name()),
                    Map.of("breakpointId", String.valueOf(flp.id), "thread", thread.name())));
                return false;
            }
            final boolean failed = stop != null && stop.kind() == FastBreakpointProbeSource.STOP_CONDITION_ERROR;
            publishStop(flp, thread, location, failed, stop != null ? stop.error() : null, false);
            return true;
        }
    }

    /**
     * Handles a stop raised by a thread inside an MCP-driven evaluation, on the JDI listener thread:
     * the listener resumes it without reporting it. The gate the suppressed stop claimed is opened
     * again (its generation advances) unless it was re-armed meanwhile. Makes JDWP field reads and
     * writes only, never a method invocation. Never throws.
     */
    public void skipStopHit(BreakpointEvent event) {
        final EventRequest request = event.request();
        final FastLogpoint flp = request != null ? byStopRequest.get(request) : null;
        if (flp == null) {
            return;
        }
        final ThreadReference thread = event.thread();
        final StopArguments stop = stopArguments(thread);
        eventHistory.record(new EventHistory.DebugEvent("FAST_BREAKPOINT_SUPPRESSED",
            String.format("[FastBreakpoint %d] hit on thread %s inside an expression evaluation — not stopped",
                flp.id, thread.name()),
            Map.of("breakpointId", String.valueOf(flp.id), "thread", thread.name())));
        if (stop == null) {
            return;
        }
        synchronized (this) {
            if (logpoints.get(flp.id) != flp) {
                return;
            }
            // A true stop in every-hit mode claimed no gate; anything else re-opens the gate it claimed.
            final boolean errorGate = stop.kind() == FastBreakpointProbeSource.STOP_CONDITION_ERROR;
            if ((errorGate || flp.stopOnce) && isCurrent(flp, stop)) {
                advanceEpoch(flp, errorGate);
            }
        }
    }

    /**
     * Puts a fresh one-shot arming request in place of the one {@code event} spent, without arming:
     * the hit came from a thread inside an MCP-driven method invocation, whose events the listener
     * thread only suppresses (invoking on that thread again could deadlock). Never throws.
     */
    public void skipArmingHit(BreakpointEvent event) {
        final FastLogpoint flp = armingLogpoint(event);
        if (flp != null) {
            rearm(flp);
        }
    }

    /**
     * The fast logpoint whose arming request reported {@code event}, or {@code null}.
     */
    @Nullable
    private FastLogpoint armingLogpoint(BreakpointEvent event) {
        final EventRequest request = event.request();
        return request != null ? byArmingRequest.get(request) : null;
    }

    /**
     * Copies every new ring entry of every evaluating fast logpoint (own and leftover) into the
     * event history and records a {@code FAST_LOGPOINT_DROPPED} event wherever entries were lost.
     * Reads static fields and array slices only — no thread is suspended. Never throws.
     */
    public synchronized void drainAll() {
        for (FastLogpoint flp : logpoints.values()) {
            drain(flp);
        }
    }

    /**
     * Clears one fast logpoint: deletes a pending arming request; for an armed one flips the kill
     * switch, drains the ring one last time and redefines the class without its probe; for a
     * leftover of an earlier session only flips the kill switch (see {@link #restore}).
     *
     * @return a one-line report for the agent, or {@code null} when {@code id} is not a fast logpoint
     */
    @Nullable
    public synchronized String clear(int id) {
        final FastLogpoint flp = logpoints.get(id);
        return flp == null ? null : clearEntry(flp);
    }

    /**
     * {@link #clear} for one entry; caller holds the monitor.
     */
    private String clearEntry(FastLogpoint flp) {
        if (flp.isLeftover()) {
            return disableLeftover(flp);
        }
        final int id = flp.id;
        if (flp.state != State.ARMED && flp.state != State.DISABLED) {
            logpoints.remove(id);
            deleteArmingRequest(flp);
            deleteStopRequest(flp);
            flp.state = State.DISARMED;
            return String.format("%s %d cleared (it was %s%s)", flp.kind.label, id,
                flp.failureReason != null ? "FAILED" : "not armed yet",
                flp.classUntouched ? "; the class was never modified" : "; its redefinition may have been applied — "
                    + "the probe is disabled, and after a reconnect jdwp_overview shows whether the class calls it");
        }
        // Kill switch first: one static-field write stops the probe immediately, even if the
        // redefinition below fails.
        final boolean killed = setKillSwitch(flp);
        deleteStopRequest(flp);
        drain(flp);
        releaseRing(flp);
        try {
            uninstall(flp);
        } catch (Exception | LinkageError e) {
            // Still installed: keep it listed, so the overview shows it and a later clear retries.
            final String reason = describe(e);
            flp.state = State.DISABLED;
            flp.failureReason = "restoring the original bytecode failed: " + reason;
            recordWarning(flp, String.format("could not restore the original bytecode of %s: %s. %s",
                flp.className(), reason, killed
                    ? "The probe is disabled by its kill switch; the fast logpoint stays listed as DISABLED and "
                    + "jdwp_clear_breakpoint retries."
                    : "The kill switch could not be set either — the probe may still be evaluating."));
            return String.format("%s %d: restoring the original bytecode of %s FAILED: %s (kill switch %s). "
                + "It stays listed as DISABLED — jdwp_clear_breakpoint(%d) retries.",
                flp.kind.label, id, flp.className(), reason, killed ? "set" : "NOT set", id);
        }
        logpoints.remove(id);
        flp.state = State.DISARMED;
        if (killed) {
            retire(flp);
        } else {
            // Not retired: a later attach still sees it enabled and lists it.
            recordWarning(flp, "removed from " + flp.className() + ", but its kill switch could not be set — a thread "
                + "still running the pre-redefinition method can still call it");
        }
        eventHistory.record(new EventHistory.DebugEvent(flp.kind.eventPrefix + "_DISARMED",
            String.format("[%s %d] disarmed — original bytecode of %s restored (%d logged, %d dropped)",
                flp.kind.tag, id, flp.className(), flp.logged, flp.dropped),
            Map.of("breakpointId", String.valueOf(id), "class", flp.className(),
                "line", String.valueOf(flp.line))));
        return String.format("%s %d cleared — original bytecode of %s restored "
            + "(%d entries logged, %d dropped)", flp.kind.label, id, flp.className(), flp.logged, flp.dropped);
    }

    /**
     * Clears every fast logpoint this session armed before the session's VM handle goes away
     * (reset, disconnect, reconnect, server shutdown), then forgets all state, including leftovers
     * of earlier sessions, which this session never owns and leaves untouched. Bounded by
     * {@link #TEARDOWN_TIMEOUT_MS}: a wedged VM can block {@code redefineClasses} forever, so after
     * the deadline the remaining instrumentation is abandoned with a {@code FAST_LOGPOINT_WARNING}.
     *
     * @return how many of this session's fast logpoints were cleared or abandoned
     */
    public int disarmAll(String reason) {
        final List<FastLogpoint> own = new ArrayList<>();
        for (FastLogpoint flp : logpoints.values()) {
            if (!flp.isLeftover()) {
                own.add(flp);
            }
        }
        teardownStuck = false;
        if (own.isEmpty()) {
            forgetAll();
            return 0;
        }
        final ExecutorService executor = daemonExecutor("fast-logpoint-teardown");
        try {
            // The captured entries, not their IDs: a worker that outlives the deadline must not clear
            // a logpoint a later session registered under a reused ID.
            final Future<?> teardown = executor.submit(() -> {
                try {
                    for (FastLogpoint flp : own) {
                        clearIfCurrent(flp);
                    }
                } finally {
                    // A worker that outlived the deadline no longer holds the monitor from here on.
                    teardownStuck = false;
                }
            });
            teardown.get(TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandonAll(reason, "interrupted");
        } catch (TimeoutException e) {
            teardownStuck = true;
            abandonAll(reason, "no answer from the VM within " + TEARDOWN_TIMEOUT_MS + " ms");
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            abandonAll(reason, describe(cause != null ? cause : e));
        } finally {
            executor.shutdownNow();
        }
        // Whatever is left goes with the VM handle: own logpoints whose restore failed (the target
        // records them, so the next attach lists them) and leftovers of earlier sessions.
        forgetAll();
        log.info("[FastLogpoint] Disarmed {} fast logpoint(s) ({})", own.size(), reason);
        return own.size();
    }

    private synchronized void clearIfCurrent(FastLogpoint flp) {
        if (logpoints.get(flp.id) == flp) {
            clearEntry(flp);
        }
    }

    /**
     * Drops all state without touching the VM — for when the VM is gone or about to be released.
     * Does not take the service monitor: it must work while a stuck teardown holds it. The epoch
     * lock only serialises it with {@link #publish}, which never makes a JDWP call while holding it.
     */
    public void forgetAll() {
        synchronized (epochLock) {
            sessionEpoch.incrementAndGet();
            discoveryIncomplete = false;
            logpoints.clear();
            byArmingRequest.clear();
            byStopRequest.clear();
            retiredStopRequests.clear();
            instrumentedClasses.clear();
        }
    }

    /**
     * Best-effort disarm on server shutdown, so a clean exit never leaves instrumentation behind.
     */
    @PreDestroy
    public void shutdown() {
        disarmAll("MCP server shutdown");
    }

    /**
     * Snapshot of every fast logpoint for {@code jdwp_overview}. {@code written} is the live
     * sequence counter (a static-field read) of every probe that exists; {@code -1} when there is
     * none or it is unreadable. Read-only and deliberately unsynchronized, so the overview never
     * waits behind a teardown stuck in the VM; counters may be a moment stale.
     */
    public List<Info> list() {
        final List<Info> out = new ArrayList<>();
        for (FastLogpoint flp : logpoints.values()) {
            long written = -1;
            if (flp.seq != null) {
                try {
                    written = readSeq(flp);
                } catch (Exception ignored) {
                    // VM unreachable — the overview still renders the server-side counters.
                }
            }
            final Provenance provenance = flp.isLeftover()
                ? new Provenance(Instant.ofEpochMilli(flp.armedAt), Objects.requireNonNullElse(flp.armedBy, "?"),
                Objects.requireNonNullElse(flp.loader, "?"), flp.retained)
                : null;
            final BreakpointCounters counters = flp.kind == Kind.BREAKPOINT ? breakpointCounters(flp) : null;
            out.add(new Info(flp.id, flp.className(), flp.line, flp.expression, flp.condition, flp.state,
                flp.failureReason, flp.bufferSize, flp.logged, flp.dropped, written, flp.enabled, provenance, flp.kind,
                flp.suspendPolicy, flp.stopOnce, counters));
        }
        out.sort(Comparator.comparingInt(Info::id));
        return out;
    }

    // ── Leftovers of earlier sessions ──

    /**
     * Finds the fast logpoints earlier sessions left in {@code vm} and lists them under fresh IDs.
     * Read-only. The reads run on a worker bounded by {@link #TEARDOWN_TIMEOUT_MS} that holds no
     * lock; the results are published under the monitor only if the session was not dropped
     * meanwhile. Never throws.
     *
     * @return a notice block for the attach reply (leading blank line), or {@code ""} when nothing
     *         was found
     */
    public String discover(VirtualMachine vm) {
        if (teardownStuck) {
            discoveryIncomplete = true;
            return "\n\nLooking for fast logpoints left by an earlier session was skipped: an earlier teardown is still "
                + "waiting for the VM — jdwp_overview retries it.";
        }
        final long epoch = sessionEpoch.get();
        final ExecutorService executor = daemonExecutor("fast-logpoint-discovery");
        final List<Discovered> found;
        try {
            found = executor.submit(() -> scan(vm)).get(TEARDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            discoveryIncomplete = true;
            return "";
        } catch (TimeoutException e) {
            discoveryIncomplete = true;
            return "\n\nLooking for fast logpoints left by an earlier session did not finish within "
                + TEARDOWN_TIMEOUT_MS + " ms — jdwp_overview retries it.";
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[FastLogpoint] Discovery of leftover fast logpoints failed", cause);
            discoveryIncomplete = true;
            return "\n\nLooking for fast logpoints left by an earlier session failed (" + describe(cause)
                + ") — jdwp_overview retries it.";
        } finally {
            executor.shutdownNow();
        }
        discoveryIncomplete = false;
        return publish(vm, found, epoch);
    }

    /**
     * Re-runs {@link #discover} when the last run did not complete.
     *
     * @return the discovery notice, or {@code ""}
     */
    public String discoverIfIncomplete(VirtualMachine vm) {
        return discoveryIncomplete ? discover(vm) : "";
    }

    /**
     * Whether the last {@link #disarmAll} gave up on an unresponsive VM. Its worker may still hold
     * the monitor inside a JDWP call, so a caller about to discover again should skip it.
     */
    public boolean isTeardownStuck() {
        return teardownStuck;
    }

    /**
     * The refusal for arming in a class that still carries leftovers of an earlier session, or
     * {@code null} when it carries none. Read-only; arming calls it again under the monitor.
     */
    @Nullable
    public String leftoversIn(ReferenceType refType) {
        final List<String> names = new ArrayList<>();
        boolean restorable = false;
        for (FastLogpoint flp : logpoints.values()) {
            if (flp.isLeftover() && flp.refType.equals(refType)) {
                names.add("#" + flp.id + " (line " + flp.line + ", " + flp.state + ")");
                restorable |= flp.state == State.RECOVERED;
            }
        }
        if (names.isEmpty()) {
            return null;
        }
        Collections.sort(names);
        return "refusing to arm: " + refType.name() + " still carries fast logpoints left by an earlier debugging "
            + "session: " + String.join(", ", names) + ". "
            + (restorable
            ? "jdwp_restore_fast_logpoints(id) restores the class's original bytecode, but only with the user's "
            + "approval; jdwp_clear_breakpoint(id) only disables them and leaves the class instrumented."
            : "They cannot be removed until the JVM restarts (the class changed after they were installed).")
            + " Use jdwp_set_logpoint meanwhile.";
    }

    /**
     * Sets the kill switch of every leftover and forgets them — {@code jdwp_reset}. The class
     * bytecode is not touched. The caller re-runs {@link #discover} afterwards to list them again
     * under fresh IDs.
     *
     * @return how many leftovers were disabled
     */
    public synchronized int disableLeftovers() {
        int count = 0;
        for (FastLogpoint flp : new ArrayList<>(logpoints.values())) {
            if (flp.isLeftover()) {
                disableLeftover(flp);
                logpoints.remove(flp.id);
                count++;
            }
        }
        return count;
    }

    /**
     * Restores the original bytecode of the class carrying leftover {@code id} — every leftover in
     * that class goes. Without {@code approved} it changes nothing and returns the briefing the
     * agent must show the user.
     */
    public synchronized String restore(int id, boolean approved) {
        final FastLogpoint flp = logpoints.get(id);
        if (flp == null) {
            return "No fast logpoint with ID " + id + " — jdwp_overview lists them.";
        }
        if (!flp.isLeftover()) {
            return "Fast logpoint " + id + " was armed by this session — jdwp_clear_breakpoint(" + id
                + ") restores its class.";
        }
        final GenerationRecord record = flp.record;
        if (flp.state != State.RECOVERED || record == null) {
            return "Not restored: fast logpoint " + id + " is STRANDED — " + flp.failureReason + ". jdwp_clear_breakpoint("
                + id + ") disables it; the injected call stays until the JVM restarts.";
        }
        final List<FastLogpoint> group = new ArrayList<>();
        for (FastLogpoint other : logpoints.values()) {
            if (other.record == record) {
                group.add(other);
            }
        }
        group.sort(Comparator.comparingInt(other -> other.id));
        if (!approved) {
            return restoreBriefing(flp, group, record);
        }
        final String changed = firstDifference(flp.refType, record.installed());
        if (changed != null) {
            for (FastLogpoint member : group) {
                member.state = State.STRANDED;
                member.failureReason = LeftoverClassifier.CHANGED;
                member.record = null;
            }
            return "Not restored: the live bytecode of " + flp.className() + '.' + changed + " no longer matches what "
                + "the arming session recorded — " + LeftoverClassifier.CHANGED + '.';
        }
        for (FastLogpoint member : group) {
            if (!setKillSwitch(member)) {
                return "Not restored: the kill switch of fast logpoint " + member.id + " could not be set — the class "
                    + "was not changed.";
            }
            member.enabled = false;
            drain(member);
            releaseRing(member);
        }
        try {
            redefine(flp.vm, flp.refType, record.original());
        } catch (Exception | LinkageError e) {
            return "Restoring the original bytecode of " + flp.className() + " FAILED: " + describe(e) + ". Its fast "
                + "logpoints stay listed, disabled by their kill switches.";
        }
        final Map<String, byte[]> installed = readBack(flp.refType);
        final Set<String> wired = installed != null ? observeWiring(flp.refType, installed) : null;
        final List<String> ids = new ArrayList<>();
        final List<String> kept = new ArrayList<>();
        for (FastLogpoint member : group) {
            if (!provenRemoved(flp.refType, record.original(), installed, wired, member.probeType)) {
                // Kept listed (disabled): either a transformer kept the call, or the removal could not be observed.
                member.state = State.STRANDED;
                member.failureReason = installed != null && wired != null
                    ? "still called after restoring the class — an agent transforming the class kept the call"
                    : "the class was restored, but whether it still calls the probe could not be checked";
                member.record = null;
                kept.add("#" + member.id);
                continue;
            }
            retire(member);
            logpoints.remove(member.id);
            member.state = State.DISARMED;
            ids.add("#" + member.id);
            eventHistory.record(new EventHistory.DebugEvent("FAST_LOGPOINT_RESTORED",
                String.format("[FastLogpoint %d] left by an earlier session — removed; original bytecode of %s restored",
                    member.id, member.className()),
                Map.of("breakpointId", String.valueOf(member.id), "class", member.className(),
                    "line", String.valueOf(member.line))));
        }
        final String restored = String.format("Restored the class file of %s recorded by %s on %s — removed fast "
                + "logpoint(s) %s.", flp.className(), flp.armedBy, Instant.ofEpochMilli(flp.armedAt),
            ids.isEmpty() ? "none" : String.join(", ", ids));
        return kept.isEmpty() ? restored : restored + " Not confirmed removed: " + String.join(", ", kept)
            + " — they stay listed as STRANDED, disabled (jdwp_overview says why).";
    }

    private static String restoreBriefing(FastLogpoint flp, List<FastLogpoint> group, GenerationRecord record) {
        final StringBuilder out = new StringBuilder("RESTORE NOT PERFORMED — this needs the user's explicit approval.\n\n")
            .append("jdwp_restore_fast_logpoints redefines ").append(flp.className()).append(" (loader ")
            .append(flp.loader).append(") with the class file saved by the session that armed these fast logpoints (")
            .append(flp.armedBy).append(", ").append(Instant.ofEpochMilli(flp.armedAt)).append("). It removes:\n");
        for (FastLogpoint member : group) {
            out.append("  #").append(member.id).append(" line ").append(member.line).append(" in ")
                .append(member.methodName).append(" — expr=\"").append(member.expression).append('"')
                .append(member.enabled ? " [evaluating]" : " [kill switch set]").append('\n');
        }
        out.append("\nWhat is checked, and what is not:\n")
            .append("- The saved class file (").append(record.original().length).append(" bytes) is what the class ")
            .append("loader served when the fast logpoint was armed; then, every method's bytecode matched the live ")
            .append("class. Constants, exception tables, annotations and other attributes were not compared, so a ")
            .append("load-time agent's change confined to those would not have shown.\n")
            .append("- Every method's bytecode equals what that session installed, as JDWP showed it when this ")
            .append("session attached. JDWP never shows non-code attributes, and a later in-process agent change may ")
            .append("not show either; either would be undone.\n")
            .append("- Threads already inside a changed method finish on the old version.\n\n")
            .append("Ask the user. If they agree, call jdwp_restore_fast_logpoints(id=").append(flp.id)
            .append(", userApproved=true). Without restoring, jdwp_clear_breakpoint(").append(flp.id)
            .append(") only disables the probe.");
        return out.toString();
    }

    /**
     * Sets a leftover's kill switch and releases its ring; caller holds the monitor. A probe the
     * class provably no longer calls is retired and dropped; a wired one stays listed.
     */
    private String disableLeftover(FastLogpoint flp) {
        if (!setKillSwitch(flp)) {
            return String.format("Fast logpoint %d (left by an earlier session): its kill switch could not be set — it "
                + "may still be evaluating.", flp.id);
        }
        flp.enabled = false;
        drain(flp);
        releaseRing(flp);
        if (flp.provenUnwired) {
            retire(flp);
            logpoints.remove(flp.id);
            flp.state = State.DISARMED;
            return String.format("Fast logpoint %d (left by an earlier session) disabled and retired — the code of %s "
                + "no longer calls it.", flp.id, flp.className());
        }
        if (flp.state == State.RECOVERED) {
            return String.format("Fast logpoint %d (left by an earlier session) disabled — its kill switch is set, but "
                + "the injected call stays in %s. jdwp_restore_fast_logpoints(%d) restores the original bytecode, "
                + "only with the user's approval.", flp.id, flp.className(), flp.id);
        }
        return String.format("Fast logpoint %d (left by an earlier session) disabled — the injected call stays in %s "
            + "until the JVM restarts (%s).", flp.id, flp.className(), flp.failureReason);
    }

    /**
     * Reads every probe class in the VM and classifies them. Runs on the discovery worker without
     * any lock.
     */
    private static List<Discovered> scan(VirtualMachine vm) {
        final Map<TargetKey, List<FoundProbe>> byTarget = new LinkedHashMap<>();
        final List<Discovered> out = new ArrayList<>();
        for (ReferenceType type : vm.allClasses()) {
            if (!(type instanceof ClassType probeType) || !isProbeClassName(simpleName(type.name()))
                || !type.isPrepared() || type.fieldByName(FastLogpointProbeSource.FORMAT_FIELD) == null) {
                continue;
            }
            try {
                final FoundProbe probe = readProbe(probeType);
                byTarget.computeIfAbsent(new TargetKey(probe.targetClass(), probeType.classLoader()),
                    ignored -> new ArrayList<>()).add(probe);
            } catch (Exception e) {
                log.warn("[FastLogpoint] Leftover probe {} could not be read", type.name(), e);
            }
        }
        for (Map.Entry<TargetKey, List<FoundProbe>> group : byTarget.entrySet()) {
            final TargetKey key = group.getKey();
            ReferenceType target = null;
            Map<String, byte[]> live = null;
            String liveProblem = null;
            try {
                target = resolveTarget(vm, key.className(), key.loader());
                live = target != null ? liveCode(target) : null;
            } catch (Exception e) {
                liveProblem = "its target class could not be read: " + describe(e);
            }
            final Set<String> wired = target != null && live != null ? observeWiring(target, live) : null;
            final List<LeftoverClassifier.Probe> facts = new ArrayList<>();
            final Map<Object, GenerationRecord> records = new HashMap<>();
            for (FoundProbe probe : group.getValue()) {
                facts.add(probe.facts());
                final ArrayReference image = probe.image();
                if (image != null && !records.containsKey(image.uniqueID())) {
                    try {
                        if (image.length() > MAX_RECORD_BYTES) {
                            throw new FastLogpointException(image.length() + " bytes");
                        }
                        records.put(image.uniqueID(), GenerationRecord.decode(readBytes(image)));
                    } catch (Exception e) {
                        log.debug("[FastLogpoint] Unreadable install record on {}: {}", probe.type().name(), describe(e));
                    }
                }
            }
            final LeftoverClassifier.Result result =
                LeftoverClassifier.classify(key.className(), facts, live, wired, liveProblem, records);
            for (FoundProbe probe : group.getValue()) {
                final LeftoverClassifier.Verdict verdict = result.verdicts().get(probe.facts().name());
                if (verdict != null && verdict.kind() != LeftoverClassifier.Kind.HIDDEN) {
                    out.add(new Discovered(probe, verdict, target, result.record()));
                }
            }
        }
        out.sort(Comparator.comparingLong((Discovered d) -> d.probe().armedAt()).thenComparing(d -> d.probe().facts().name()));
        return out;
    }

    /**
     * Lists the discovered leftovers under fresh IDs unless the session was dropped meanwhile. Makes
     * no JDWP call and does not wait for the service monitor (a stuck teardown may hold it); the
     * epoch check and the insertion are atomic with {@link #forgetAll}.
     */
    private String publish(VirtualMachine vm, List<Discovered> discovered, long epoch) {
        if (discovered.isEmpty()) {
            return "";
        }
        final List<FastLogpoint> added = new ArrayList<>();
        synchronized (epochLock) {
            if (sessionEpoch.get() != epoch) {
                return "";
            }
            final Set<String> known = new HashSet<>();
            for (FastLogpoint flp : logpoints.values()) {
                final ClassType probeType = flp.probeType;
                if (probeType != null) {
                    known.add(probeType.name());
                }
            }
            for (Discovered found : discovered) {
                final FoundProbe probe = found.probe();
                if (!known.add(probe.facts().name())) {
                    continue;
                }
                final FastLogpoint flp = new FastLogpoint(breakpointTracker.allocateId(), probe.kind(), vm,
                    found.target() != null ? found.target() : probe.type(), probe.targetClass(), probe.line(),
                    probe.method(), probe.descriptor(), probe.expression(), probe.condition(), probe.bufferSize(),
                    EventRequest.SUSPEND_EVENT_THREAD, probe.stopOnce());
                flp.state = found.verdict().kind() == LeftoverClassifier.Kind.RECOVERED ? State.RECOVERED : State.STRANDED;
                flp.failureReason = found.verdict().reason();
                flp.provenUnwired = found.verdict().provenUnwired();
                flp.record = flp.state == State.RECOVERED ? found.record() : null;
                flp.attach(probe.type(), probe.ring(), probe.seq(), probe.seqValue());
                flp.enabled = probe.facts().enabled();
                flp.nextSeq = probe.written();
                flp.retained = Math.min(probe.written(), probe.bufferSize());
                flp.armedAt = probe.armedAt();
                flp.armedBy = probe.armedBy();
                flp.loader = probe.loader();
                logpoints.put(flp.id, flp);
                added.add(flp);
            }
        }
        for (FastLogpoint flp : added) {
            eventHistory.record(new EventHistory.DebugEvent(flp.kind.eventPrefix + "_RECOVERED",
                String.format("[%s %d] left by an earlier session at %s:%d — %s%s, %s (armed %s by %s)",
                    flp.kind.tag, flp.id, flp.className(), flp.line, flp.state,
                    flp.enabled ? ", evaluating" : ", kill switch set", describeUserText(flp),
                    Instant.ofEpochMilli(flp.armedAt), flp.armedBy),
                Map.of("breakpointId", String.valueOf(flp.id), "class", flp.className(),
                    "line", String.valueOf(flp.line), "state", flp.state.name(),
                    "probe", Objects.requireNonNull(flp.probeType).name())));
        }
        if (added.isEmpty()) {
            return "";
        }
        log.warn("[FastLogpoint] {} fast logpoint(s) left by an earlier session found", added.size());
        final StringBuilder out = new StringBuilder("\n\nFAST LOGPOINTS LEFT BY AN EARLIER SESSION — ")
            .append(added.size()).append(" found in this JVM:\n");
        for (FastLogpoint flp : added) {
            out.append("  #").append(flp.id).append(' ').append(flp.kind.label.toLowerCase(Locale.ROOT)).append(' ')
                .append(flp.className()).append(':').append(flp.line).append(' ')
                .append(flp.state).append(flp.enabled ? ", evaluating" : ", kill switch set").append(" — ")
                .append(describeUserText(flp)).append(" (armed ").append(Instant.ofEpochMilli(flp.armedAt)).append(" by ")
                .append(flp.armedBy).append(')');
            if (flp.state == State.STRANDED) {
                out.append("; ").append(flp.failureReason);
            }
            out.append('\n');
        }
        return out.append("Report them to the user and leave them alone unless asked: jdwp_clear_breakpoint(id) disables "
            + "one (no bytecode change); jdwp_restore_fast_logpoints(id) restores a RECOVERED class's original "
            + "bytecode, only with the user's approval.").toString();
    }

    private static FoundProbe readProbe(ClassType type) throws Exception {
        final int format = intStatic(type, FastLogpointProbeSource.FORMAT_FIELD, -1);
        final Field enabledField = type.fieldByName(FastLogpointProbeSource.ENABLED_FIELD);
        final boolean enabled = enabledField != null && type.getValue(enabledField) instanceof BooleanValue on && on.value();
        final Field imageField = type.fieldByName(FastLogpointProbeSource.IMAGE_FIELD);
        final ArrayReference image = imageField != null && type.getValue(imageField) instanceof ArrayReference array
            ? array : null;
        ArrayReference ring = null;
        ObjectReference seq = null;
        Field seqValue = null;
        long written = 0;
        final Field ringField = type.fieldByName(FastLogpointProbeSource.RING_FIELD);
        final Field seqField = type.fieldByName(FastLogpointProbeSource.SEQ_FIELD);
        if (ringField != null && seqField != null && type.getValue(ringField) instanceof ArrayReference ringArray
            && type.getValue(seqField) instanceof ObjectReference seqObject) {
            final Field value = seqObject.referenceType().fieldByName("value");
            if (value != null && seqObject.getValue(value) instanceof LongValue count) {
                ring = ringArray;
                seq = seqObject;
                seqValue = value;
                written = count.value();
            }
        }
        final String condition = stringStatic(type, FastLogpointProbeSource.CONDITION_FIELD, "");
        final Kind kind = FastBreakpointProbeSource.KIND.equals(stringStatic(type, FastLogpointProbeSource.KIND_FIELD,
            FastLogpointProbeSource.KIND)) ? Kind.BREAKPOINT : Kind.LOGPOINT;
        final Field onceField = type.fieldByName(FastBreakpointProbeSource.ONCE_FIELD);
        final boolean stopOnce = onceField != null && type.getValue(onceField) instanceof BooleanValue once && once.value();
        final Field armedAtField = type.fieldByName(FastLogpointProbeSource.ARMED_AT_FIELD);
        final long armedAt = armedAtField != null && type.getValue(armedAtField) instanceof LongValue at ? at.value() : 0L;
        return new FoundProbe(type, new LeftoverClassifier.Probe(type.name(), format, enabled,
            image != null ? image.uniqueID() : null),
            stringStatic(type, FastLogpointProbeSource.TARGET_CLASS_FIELD, "?"),
            stringStatic(type, FastLogpointProbeSource.TARGET_METHOD_FIELD, "?"),
            stringStatic(type, FastLogpointProbeSource.TARGET_DESCRIPTOR_FIELD, "?"),
            intStatic(type, FastLogpointProbeSource.TARGET_LINE_FIELD, -1),
            stringStatic(type, FastLogpointProbeSource.EXPRESSION_FIELD, "?"),
            condition.isEmpty() ? null : condition, armedAt,
            stringStatic(type, FastLogpointProbeSource.ARMED_BY_FIELD, "unknown session"),
            image, ring, seq, seqValue, written, ring != null ? ring.length() : 0, loaderLabel(type.classLoader()),
            kind, stopOnce);
    }

    private static int intStatic(ClassType type, String name, int fallback) {
        final Field field = type.fieldByName(name);
        return field != null && type.getValue(field) instanceof IntegerValue value ? value.value() : fallback;
    }

    private static String stringStatic(ClassType type, String name, String fallback) {
        final Field field = type.fieldByName(name);
        return field != null && type.getValue(field) instanceof StringReference value ? value.value() : fallback;
    }

    @Nullable
    private static ReferenceType resolveTarget(VirtualMachine vm, String className,
                                               @Nullable ClassLoaderReference loader) {
        for (ReferenceType candidate : vm.classesByName(className)) {
            if (Objects.equals(candidate.classLoader(), loader)) {
                return candidate;
            }
        }
        return null;
    }

    private static byte[] readBytes(ArrayReference array) {
        final List<Value> values = array.getValues();
        final byte[] bytes = new byte[values.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = ((ByteValue) values.get(i)).value();
        }
        return bytes;
    }

    private static String simpleName(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    private static String loaderLabel(@Nullable ClassLoaderReference loader) {
        return loader == null ? "bootstrap" : simpleName(loader.referenceType().name()) + '#' + loader.uniqueID();
    }

    // ── Arming ──

    /**
     * The arming procedure; see the class documentation. Everything up to the install step runs
     * without the monitor.
     */
    private boolean arm(FastLogpoint flp, ThreadReference thread) throws Exception {
        final VirtualMachine vm = flp.vm;
        final Location location = thread.frame(0).location();
        final Method method = location.method();
        final ReferenceType refType = location.declaringType();
        if (method.isStaticInitializer()) {
            throw new FastLogpointException("the line is in a static initializer (<clinit>), which runs once — "
                + "use jdwp_set_logpoint");
        }
        // In a constructor `this` may still be uninitialized at the line; passing it would not verify.
        final boolean includeThis = !method.isStatic() && !method.isConstructor();

        expressionEvaluator.configureCompilerClasspath(thread);
        if (flp.kind == Kind.LOGPOINT) {
            logArmingHit(flp, thread, location);
        } else if (evaluateArmingHit(flp, thread, location)) {
            return true;
        }

        // 1. Class bytes: the original class file, verified against what the VM runs.
        final InstrumentedClass existing = instrumentedClasses.get(refType);
        final byte[] original = existing != null
            ? existing.originalBytes
            : retryOnCollected(() -> readClassBytes(vm, thread, refType));
        checkLiveCode(refType, existing != null ? existing.installed : ClassFileMethods.codeByMethod(original),
            existing == null);

        // 2. Where the call goes, and which local slots it reads.
        final ProbeInstrumenter.InsertionPoint point =
            ProbeInstrumenter.locate(original, method.name(), method.signature(), flp.line);

        // 3. The probe: compiled for this frame, defined (disabled) in the declaring class's own loader.
        final ClassLoaderReference loader = refType.classLoader();
        if (loader == null) {
            throw new FastLogpointException("classes loaded by the bootstrap class loader cannot host a fast logpoint");
        }
        final DefinedProbe probe = retryOnCollected(
            () -> compileAndDefineProbe(flp, vm, thread, refType, loader, includeThis));
        final ProbeInstrumenter.ProbeSite site = buildSite(flp, method, probe, point);

        // 4. Install — under the monitor, after re-checking that nobody cleared the logpoint meanwhile.
        synchronized (this) {
            if (logpoints.get(flp.id) != flp || flp.state != State.PENDING) {
                log.info("[{}] #{} was cleared while arming — not installing", flp.kind.tag, flp.id);
                return false;
            }
            final String leftovers = leftoversIn(refType);
            if (leftovers != null) {
                throw new FastLogpointException(leftovers);
            }
            // Re-check: a clear may have redefined the class while the probe compiled.
            final InstrumentedClass current = instrumentedClasses.get(refType);
            checkLiveCode(refType, current != null ? current.installed : ClassFileMethods.codeByMethod(original),
                current == null);
            final InstrumentedClass target = current != null ? current : new InstrumentedClass(original);
            final Map<Integer, ProbeInstrumenter.ProbeSite> sites = new LinkedHashMap<>(target.sites);
            sites.put(flp.id, site);
            final byte[] instrumented = ProbeInstrumenter.instrument(target.originalBytes, sites.values());
            flp.attach(probe.type(), probe.ring(), probe.seq(), probe.seqValue());
            deleteArmingRequest(flp);
            if (flp.kind == Kind.BREAKPOINT) {
                // The gates first, then the stop request: nothing can reach stop() before the request
                // that reports it exists.
                writeGates(flp);
                createStopRequest(flp, probe.type());
            }
            try {
                redefine(vm, refType, instrumented);
            } catch (Exception | LinkageError e) {
                if (definitelyRejected(e)) {
                    throw e;
                }
                // The redefine command may have been applied before the failure (JDI cleans up
                // breakpoints and caches afterwards). The probe stays disabled; a later attach
                // observes whether the class calls it.
                throw new UncertainRedefinitionException("redefining " + refType.name() + " failed after it may "
                    + "already have been applied (" + describe(e) + ") — the probe stays disabled; after a "
                    + "reconnect jdwp_overview shows whether the class calls it", e);
            }
            // From here on the class is modified: a failure leaves the logpoint installed and listed.
            instrumentedClasses.putIfAbsent(refType, target);
            target.sites = sites;
            final Map<String, byte[]> installed = readBack(refType);
            // Without a read-back the guard falls back to the bytes sent (erring towards refusing the
            // next change); the wiring is then unknown — never decoded from the bytes sent.
            target.installed = installed != null ? installed : ClassFileMethods.codeByMethod(instrumented);
            final Set<String> wired = installed != null ? observeWiring(refType, installed) : null;
            final String unrecorded = recordInstall(vm, refType, target, wired);
            if (wired != null && !wired.contains(probe.type().name())) {
                flp.state = State.DISABLED;
                flp.failureReason = "after the redefinition the class does not call the probe — an agent transforming "
                    + "the class (a ClassFileTransformer) changed the bytecode; jdwp_clear_breakpoint restores the class";
                recordWarning(flp, flp.failureReason);
                return false;
            }
            final Set<String> installedProbes = probeNames(sites.keySet());
            if (wired != null && !wired.equals(installedProbes)) {
                recordWarning(flp, "the class calls the probes " + wired + ", not the ones this server installed ("
                    + installedProbes + ") — an agent transforming the class changed the bytecode");
            }
            try {
                setEnabled(flp, true);
            } catch (Exception e) {
                flp.state = State.DISABLED;
                flp.failureReason = "installed, but its kill switch could not be switched on: " + describe(e)
                    + " — jdwp_clear_breakpoint restores the class";
                recordWarning(flp, flp.failureReason);
                return false;
            }
            flp.enabled = true;
            if (unrecorded != null) {
                recordWarning(flp, "armed, but the install could not be recorded in the target JVM (" + unrecorded
                    + ") — if this server dies without restoring the class, a later session can only disable it");
            }
            // Recorded before the state flips, under the monitor: whoever observes ARMED (via list())
            // also finds the event.
            eventHistory.record(new EventHistory.DebugEvent(flp.kind.eventPrefix + "_ARMED",
                flp.kind == Kind.LOGPOINT
                    ? String.format("[FastLogpoint %d] armed at %s:%d in %s — probe %s, buffer %d. Hits on other "
                        + "threads during the arming pause were not recorded; threads already inside %s run the old "
                        + "code until they call it again.",
                    flp.id, flp.className(), flp.line, method.name(), probe.type().name(), flp.bufferSize,
                    method.name())
                    : String.format("[FastBreakpoint %d] armed at %s:%d in %s — probe %s, condition '%s', %s, "
                        + "suspend %s. Threads already inside %s run the old code until they call it again.",
                    flp.id, flp.className(), flp.line, method.name(), probe.type().name(), flp.condition,
                    flp.stopOnce ? "stops once" : "stops on every true hit",
                    flp.suspendPolicy == EventRequest.SUSPEND_ALL ? "all" : "thread", method.name()),
                Map.of("breakpointId", String.valueOf(flp.id), "class", flp.className(),
                    "line", String.valueOf(flp.line), "probe", probe.type().name())));
            flp.state = State.ARMED;
        }
        log.info("[{}] #{} armed at {}:{} (probe {})", flp.kind.tag, flp.id, flp.className(), flp.line,
            probe.type().name());
        return false;
    }

    /**
     * Logs the arming hit itself through the debugger: the arming thread runs the pre-instrumentation
     * code, so without this the very hit that triggered arming — possibly the rare one the user is
     * waiting for — would be lost.
     */
    private void logArmingHit(FastLogpoint flp, ThreadReference thread, Location location) {
        final String context = String.format(" at %s:%d (thread %s, arming hit — evaluated through the debugger)",
            flp.className(), flp.line, thread.name());
        try {
            if (flp.condition != null) {
                final Value passed = expressionEvaluator.evaluate(thread.frame(0), flp.condition);
                final Boolean result = asBoolean(passed);
                if (result == null) {
                    recordArmingHit(flp, "FAST_LOGPOINT_ERROR", "condition did not evaluate to a boolean: "
                        + renderArmingValue(passed), context, thread);
                    return;
                }
                if (!result) {
                    return;
                }
            }
            final Value value = expressionEvaluator.evaluate(thread.frame(0), flp.expression);
            recordArmingHit(flp, "FAST_LOGPOINT", renderArmingValue(value), context, thread);
        } catch (Exception e) {
            recordArmingHit(flp, "FAST_LOGPOINT_ERROR", describe(e), context, thread);
        }
        log.debug("[FastLogpoint] #{} arming hit at {}", flp.id, location);
    }

    private void recordArmingHit(FastLogpoint flp, String type, String result, String context, ThreadReference thread) {
        final String summary = "FAST_LOGPOINT".equals(type)
            ? String.format("[FastLogpoint %d] %s = %s%s", flp.id, flp.expression, result, context)
            : String.format("[FastLogpoint %d] Error evaluating '%s': %s%s", flp.id, flp.expression, result, context);
        eventHistory.record(new EventHistory.DebugEvent(type, summary,
            Map.of("breakpointId", String.valueOf(flp.id), "expression", flp.expression, "result", result,
                "class", flp.className(), "line", String.valueOf(flp.line), "thread", thread.name(),
                "armingHit", "true")));
    }

    /**
     * Runs {@code action}, retrying up to twice when a target object it created was garbage-collected
     * before use. Objects created through JDI (remote arrays, mirrored strings, returned streams)
     * are unreferenced in the target until pinned, so a busy target can collect them in between.
     * Every retried step is idempotent: a probe class already defined is reused by name.
     */
    private static <T> T retryOnCollected(Callable<T> action) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (attempt >= 3 || !causedByCollection(e)) {
                    throw e;
                }
                log.debug("[FastLogpoint] target object collected mid-arming (attempt {}), retrying", attempt);
            }
        }
    }

    private static boolean causedByCollection(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ObjectCollectedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders the arming hit's value the way the probe renders the rest: strings, boxed primitives
     * and enums by value, anything else in the debugger's {@code Object#id (type)} form.
     */
    private static String renderArmingValue(@Nullable Value value) {
        if (value instanceof ObjectReference object && !(value instanceof StringReference)) {
            final Field field = valueField(object.referenceType());
            if (field != null) {
                final Value inner = object.getValue(field);
                return inner instanceof StringReference string ? string.value() : String.valueOf(inner);
            }
        }
        return JdiEventListener.formatLogpointResult(value);
    }

    /**
     * The field holding the printable value of a boxed primitive ({@code value}) or an enum
     * constant ({@code name}); {@code null} for any other type.
     */
    @Nullable
    private static Field valueField(ReferenceType type) {
        if (BOXED_TYPES.contains(type.name())) {
            return type.fieldByName("value");
        }
        if (type instanceof ClassType classType && classType.isEnum()) {
            return type.fieldByName("name");
        }
        return null;
    }

    @Nullable
    private static Boolean asBoolean(@Nullable Value value) {
        if (value instanceof BooleanValue booleanValue) {
            return booleanValue.value();
        }
        if (value instanceof ObjectReference boxed && "java.lang.Boolean".equals(boxed.referenceType().name())) {
            final Field valueField = boxed.referenceType().fieldByName("value");
            if (valueField != null && boxed.getValue(valueField) instanceof BooleanValue inner) {
                return inner.value();
            }
        }
        return null;
    }

    /**
     * Fetches the class file of {@code refType} through {@code Class.getResourceAsStream} in the
     * target — the declaring loader's view, which also covers classes in named modules.
     */
    private static byte[] readClassBytes(VirtualMachine vm, ThreadReference thread, ReferenceType refType)
        throws Exception {
        final ClassObjectReference classObject = refType.classObject();
        final ClassType classClass = (ClassType) classObject.referenceType();
        final Method getResource = classClass.concreteMethodByName("getResourceAsStream",
            "(Ljava/lang/String;)Ljava/io/InputStream;");
        if (getResource == null) {
            throw new FastLogpointException("java.lang.Class#getResourceAsStream not found in the target VM");
        }
        final String resource = '/' + refType.name().replace('.', '/') + ".class";
        final Value stream = classObject.invokeMethod(thread, getResource, List.of(vm.mirrorOf(resource)),
            ObjectReference.INVOKE_SINGLE_THREADED);
        if (!(stream instanceof ObjectReference in)) {
            throw new FastLogpointException("the class file " + resource + " is not available from its class loader "
                + "(generated or dynamically defined class?)");
        }
        final ArrayType byteArrayType = (ArrayType) vm.classesByName("byte[]").get(0);
        final ArrayReference buffer = byteArrayType.newInstance(CLASS_READ_CHUNK);
        buffer.disableCollection();
        in.disableCollection();
        try {
            final ClassType streamType = (ClassType) in.referenceType();
            final Method read = streamType.concreteMethodByName("read", "([BII)I");
            final Method close = streamType.concreteMethodByName("close", "()V");
            if (read == null) {
                throw new FastLogpointException("InputStream#read(byte[],int,int) not found on " + streamType.name());
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                while (true) {
                    final Value count = in.invokeMethod(thread, read,
                        List.of(buffer, vm.mirrorOf(0), vm.mirrorOf(CLASS_READ_CHUNK)), ObjectReference.INVOKE_SINGLE_THREADED);
                    final int n = count instanceof IntegerValue intValue ? intValue.value() : -1;
                    if (n < 0) {
                        break;
                    }
                    for (Value b : buffer.getValues(0, n)) {
                        out.write(((ByteValue) b).value());
                    }
                    if (out.size() > MAX_CLASS_FILE_BYTES) {
                        throw new FastLogpointException("class file larger than " + MAX_CLASS_FILE_BYTES + " bytes");
                    }
                }
            } finally {
                if (close != null) {
                    try {
                        in.invokeMethod(thread, close, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
                    } catch (Exception ignored) {
                        // Closing a resource stream is best-effort.
                    }
                }
            }
            return out.toByteArray();
        } finally {
            buffer.enableCollection();
            in.enableCollection();
        }
    }

    /**
     * Live code of every method of {@code refType} with a body, by {@code name + descriptor}. Right
     * after this client redefined the class, JDI has dropped its cached methods and bytecodes, so
     * the result is fresh; otherwise it is JDI's cached view of this connection.
     */
    static Map<String, byte[]> liveCode(ReferenceType refType) {
        final Map<String, byte[]> code = new HashMap<>();
        for (Method method : refType.methods()) {
            if (!method.isAbstract() && !method.isNative()) {
                code.put(method.name() + method.signature(), method.bytecodes());
            }
        }
        return code;
    }

    /**
     * The first method whose live code differs from {@code expected}, {@code "(method set)"} when
     * methods were added or removed, or {@code null} when the class matches.
     */
    @Nullable
    private static String firstDifference(ReferenceType refType, Map<String, byte[]> expected) {
        int liveMethodsWithCode = 0;
        for (Method method : refType.methods()) {
            if (method.isAbstract() || method.isNative()) {
                continue;
            }
            liveMethodsWithCode++;
            final String key = method.name() + method.signature();
            if (!Arrays.equals(expected.get(key), method.bytecodes())) {
                return key;
            }
        }
        return liveMethodsWithCode != expected.size() ? "(method set)" : null;
    }

    /**
     * The guard against stripping other agents' changes: every method's live code must match
     * {@code expected} — the class file from the loader on first arm, or what HotSpot installed at
     * this service's last redefinition when the class is already instrumented.
     */
    private static void checkLiveCode(ReferenceType refType, Map<String, byte[]> expected, boolean expectedIsClassFile)
        throws FastLogpointException {
        final String differing = firstDifference(refType, expected);
        if (differing == null) {
            return;
        }
        if (expectedIsClassFile) {
            throw new FastLogpointException("refusing to arm: the class bytes from getResourceAsStream differ from "
                + "the live bytecode of " + refType.name() + '.' + differing + " — another agent (JaCoCo, the Mockito "
                + "inline mock maker, a Hibernate/Spring enhancer, an APM agent) changed the class, or the VM loaded a "
                + "different class file. Redefining from these bytes would silently strip those changes; use "
                + "jdwp_set_logpoint instead.");
        }
        throw new FastLogpointException("refusing to arm: the live bytecode of " + refType.name() + '.' + differing
            + " differs from what this server installed — something else redefined the class since.");
    }

    /**
     * Reads back what HotSpot installed — it renumbers constant-pool entries, and rewrites their
     * operands, when the new pool is not an extension of the old one.
     *
     * @return the live code per method, or {@code null} when it could not be read
     */
    @Nullable
    private static Map<String, byte[]> readBack(ReferenceType refType) {
        try {
            return liveCode(refType);
        } catch (Exception e) {
            log.warn("[FastLogpoint] Could not read back the bytecode installed in {}: {}", refType.name(), describe(e));
            return null;
        }
    }

    /**
     * Whether the class provably no longer calls {@code probeType} after a redefinition: its code is
     * the original's (which never called a probe — identical code bytes reference identical
     * constants), or the decoded wiring omits it. Unknown counts as still called.
     */
    private static boolean provenRemoved(ReferenceType refType, byte[] original, @Nullable Map<String, byte[]> installed,
                                         @Nullable Set<String> wired, @Nullable ClassType probeType) {
        if (installed == null || probeType == null) {
            return false;
        }
        if (sameCode(ClassFileMethods.codeByMethod(original), installed)) {
            return true;
        }
        return wired != null && !wired.contains(probeType.name());
    }

    private static boolean sameCode(Map<String, byte[]> expected, Map<String, byte[]> live) {
        if (expected.size() != live.size()) {
            return false;
        }
        for (Map.Entry<String, byte[]> method : expected.entrySet()) {
            if (!Arrays.equals(method.getValue(), live.get(method.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes the class's install record — the original, the installed snapshot and the probes the
     * class calls ({@code wired}, or the tracked sites when unobservable) — into each of those
     * probes this session tracks. Caller holds the monitor.
     *
     * @return {@code null} on success, otherwise why the record could not be written
     */
    @Nullable
    private String recordInstall(VirtualMachine vm, ReferenceType refType, InstrumentedClass target,
                                 @Nullable Set<String> wired) {
        try {
            final Set<String> calls = wired != null ? wired : probeNames(target.sites.keySet());
            final List<ClassType> members = new ArrayList<>();
            for (int id : target.sites.keySet()) {
                final FastLogpoint member = logpoints.get(id);
                final ClassType probeType = member != null ? member.probeType : null;
                if (probeType != null && calls.contains(probeType.name())) {
                    members.add(probeType);
                }
            }
            final byte[] encoded = new GenerationRecord(refType.name(), target.originalBytes, List.copyOf(calls),
                target.installed).encode();
            final ArrayReference image = uploadPinned(vm, encoded);
            try {
                for (ClassType probeType : members) {
                    probeType.setValue(probeField(probeType, FastLogpointProbeSource.IMAGE_FIELD), image);
                }
            } finally {
                enableCollectionQuietly(image);
            }
            return null;
        } catch (Exception e) {
            log.warn("[FastLogpoint] Could not record the install of {} in the target: {}", refType.name(), describe(e));
            return describe(e);
        }
    }

    /**
     * Binary names of the probes of this session's fast logpoints {@code ids}.
     */
    private Set<String> probeNames(Collection<Integer> ids) {
        final Set<String> names = new TreeSet<>();
        for (int id : ids) {
            final FastLogpoint member = logpoints.get(id);
            final ClassType probeType = member != null ? member.probeType : null;
            if (probeType != null) {
                names.add(probeType.name());
            }
        }
        return names;
    }

    /**
     * The probe classes {@code refType}'s code calls, decoded from {@code code} and the class's
     * live constant pool; {@code null} when that cannot be observed. Never throws.
     */
    @Nullable
    private static Set<String> observeWiring(ReferenceType refType, Map<String, byte[]> code) {
        try {
            if (!refType.virtualMachine().canGetConstantPool()) {
                return null;
            }
            return ProbeCallScanner.probeCalls(refType.constantPool(), refType.constantPoolCount(), code.values());
        } catch (Exception e) {
            log.warn("[FastLogpoint] Could not decode which probes {} calls: {}", refType.name(), describe(e));
            return null;
        }
    }

    /**
     * Whether a {@code redefineClasses} failure is a rejection by the VM before anything was
     * installed — JDI maps those JDWP errors to linkage errors and
     * {@link UnsupportedOperationException}. Anything else may have happened after the command was
     * applied.
     */
    private static boolean definitelyRejected(Throwable failure) {
        return failure instanceof LinkageError || failure instanceof UnsupportedOperationException;
    }

    /**
     * Creates a {@code byte[]} in the target holding {@code data}, pinned against collection; the
     * caller re-enables collection once a static field references it.
     */
    private static ArrayReference uploadPinned(VirtualMachine vm, byte[] data) throws Exception {
        final List<Value> mirrors = new ArrayList<>(data.length);
        for (byte b : data) {
            mirrors.add(vm.mirrorOf(b));
        }
        return retryOnCollected(() -> {
            final ArrayType byteArrayType = (ArrayType) vm.classesByName("byte[]").get(0);
            final ArrayReference array = byteArrayType.newInstance(data.length);
            array.disableCollection();
            try {
                if (data.length > 0) {
                    array.setValues(0, mirrors, 0, data.length);
                }
                return array;
            } catch (Exception e) {
                enableCollectionQuietly(array);
                throw e;
            }
        });
    }

    private static void enableCollectionQuietly(ObjectReference object) {
        try {
            object.enableCollection();
        } catch (Exception ignored) {
            // Collected or VM gone — nothing left to unpin.
        }
    }

    /**
     * Compiles the probe and defines it in {@code loader}, in the declaring class's package (so
     * package-private types and members are reachable and the probe joins the class's module).
     * When the loader refuses that package (sealed / signed), retries once in the isolated default
     * package — but only for classes in the unnamed module: a named module cannot read the loader's
     * unnamed module, so the call site would fail to link.
     */
    private DefinedProbe compileAndDefineProbe(FastLogpoint flp, VirtualMachine vm, ThreadReference thread,
                                               ReferenceType refType, ClassLoaderReference loader,
                                               boolean includeThis) throws Exception {
        final String preferred = probePackage(refType.name());
        try {
            return compileAndDefineProbeIn(preferred, flp, vm, thread, loader, includeThis);
        } catch (JdiClassDefinitionException rejected) {
            if (preferred.equals(JdiExpressionEvaluator.DEFAULT_EVALUATION_PACKAGE) || inNamedModule(vm, refType)) {
                throw rejected;
            }
            log.warn("[FastLogpoint] #{}: defining the probe into '{}' failed ({}); retrying in '{}'",
                flp.id, preferred, rejected.getMessage(), JdiExpressionEvaluator.DEFAULT_EVALUATION_PACKAGE);
            return compileAndDefineProbeIn(JdiExpressionEvaluator.DEFAULT_EVALUATION_PACKAGE, flp, vm, thread, loader,
                includeThis);
        }
    }

    /**
     * Compiles the probe into {@code probePackage}, defines and initializes it through
     * {@code loader}, and resolves the mirrors of its ring-buffer fields.
     */
    private DefinedProbe compileAndDefineProbeIn(String probePackage, FastLogpoint flp, VirtualMachine vm,
                                                 ThreadReference thread, ClassLoaderReference loader,
                                                 boolean includeThis) throws Exception {
        final FastLogpointProbeSource.Metadata metadata = new FastLogpointProbeSource.Metadata(flp.className(),
            flp.methodName, flp.methodSignature, flp.line, flp.expression, flp.condition, System.currentTimeMillis(),
            armedBy);
        // Fresh frame: any earlier method invocation on the thread invalidated the previous one.
        final FastLogpointProbe compiled = flp.kind == Kind.LOGPOINT
            ? expressionEvaluator.compileFastLogpointProbe(thread.frame(0), includeThis, probePackage, flp.expression,
            flp.condition, flp.bufferSize, metadata)
            : expressionEvaluator.compileFastBreakpointProbe(thread.frame(0), includeThis, probePackage,
            Objects.requireNonNull(flp.condition), flp.stopOnce, flp.bufferSize, metadata);
        final ClassType type = RemoteCodeExecutor.defineAndInitialize(vm, thread, loader, compiled.className(),
            compiled.bytecode());
        final Field ring = type.fieldByName(FastLogpointProbeSource.RING_FIELD);
        final Field seq = type.fieldByName(FastLogpointProbeSource.SEQ_FIELD);
        if (ring == null || seq == null || type.fieldByName(FastLogpointProbeSource.ENABLED_FIELD) == null
            || !(type.getValue(ring) instanceof ArrayReference ringArray)
            || !(type.getValue(seq) instanceof ObjectReference seqObject)) {
            throw new FastLogpointException("probe class " + type.name() + " is missing its ring-buffer fields");
        }
        final Field seqValue = seqObject.referenceType().fieldByName("value");
        if (seqValue == null) {
            throw new FastLogpointException("AtomicLong#value not found in the target VM");
        }
        return new DefinedProbe(type, compiled, ringArray, seqObject, seqValue);
    }

    private static String probePackage(String className) {
        final int dollar = className.indexOf('$');
        final String topLevel = dollar < 0 ? className : className.substring(0, dollar);
        final int dot = topLevel.lastIndexOf('.');
        return dot < 0 ? JdiExpressionEvaluator.DEFAULT_EVALUATION_PACKAGE : topLevel.substring(0, dot);
    }

    private static boolean inNamedModule(VirtualMachine vm, ReferenceType refType) {
        try {
            return vm.canGetModuleInfo() && refType.module().name() != null;
        } catch (Exception e) {
            return true; // unknown — assume the restrictive case
        }
    }

    /**
     * Maps the probe's {@code hit} parameters to {@code xLOAD}s of the instrumented method's local
     * slots, checking each against the {@code LocalVariableTable} at the insertion point.
     */
    private static ProbeInstrumenter.ProbeSite buildSite(FastLogpoint flp, Method method, DefinedProbe probe,
                                                         ProbeInstrumenter.InsertionPoint point)
        throws FastLogpointException {
        final String hitDescriptor = ProbeInstrumenter.methodDescriptor(probe.compiled().bytecode(),
            FastLogpointProbeSource.HIT_METHOD);
        final Type[] parameterTypes = Type.getArgumentTypes(hitDescriptor);
        final List<String> names = probe.compiled().parameterNames();
        if (parameterTypes.length != names.size()) {
            throw new FastLogpointException("probe signature " + hitDescriptor + " does not match its parameters " + names);
        }
        final List<ProbeInstrumenter.ArgumentLoad> loads = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            final Type parameterType = parameterTypes[i];
            if ("_this".equals(name)) {
                if (point.staticMethod()) {
                    throw new FastLogpointException("probe expects `this`, but " + method.name() + " is static");
                }
                loads.add(new ProbeInstrumenter.ArgumentLoad(Opcodes.ALOAD, 0));
                continue;
            }
            final ProbeInstrumenter.LocalSlot local = point.local(name);
            if (local == null) {
                throw new FastLogpointException("local variable '" + name + "' is visible to the debugger but not "
                    + "in the class file's LocalVariableTable at line " + flp.line);
            }
            final Type localType = Type.getType(local.descriptor());
            final boolean bothReferences = isReference(localType) && isReference(parameterType);
            if (!bothReferences && !localType.equals(parameterType)) {
                throw new FastLogpointException("local variable '" + name + "' has type " + localType.getClassName()
                    + " but the probe takes " + parameterType.getClassName());
            }
            loads.add(new ProbeInstrumenter.ArgumentLoad(ProbeInstrumenter.loadOpcode(local.descriptor()), local.slot()));
        }
        return new ProbeInstrumenter.ProbeSite(flp.id, method.name(), method.signature(), flp.line,
            probe.type().name().replace('.', '/'), FastLogpointProbeSource.HIT_METHOD, hitDescriptor, loads);
    }

    private static boolean isReference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    // ── Redefinition ──

    /**
     * Redefines {@code refType} with {@code classFile} and re-creates the breakpoints JDI deletes in
     * the process: every tracked line breakpoint in the class (same ID, suspend policy and enabled
     * state, re-bound to the line's first location in the same method) and the arming requests of
     * other pending fast logpoints there. Caller holds the monitor.
     */
    private void redefine(VirtualMachine vm, ReferenceType refType, byte[] classFile) throws Exception {
        final List<RecreatedBreakpoint> toRecreate = new ArrayList<>();
        for (Map.Entry<Integer, BreakpointRequest> entry : breakpointTracker.getAllBreakpoints().entrySet()) {
            final BreakpointRequest request = entry.getValue();
            try {
                final Location location = request.location();
                if (location.declaringType().equals(refType)) {
                    toRecreate.add(new RecreatedBreakpoint(entry.getKey(), request, null, location.method().name(),
                        location.method().signature(), location.lineNumber(), request.suspendPolicy(),
                        request.isEnabled()));
                }
            } catch (Exception skipped) {
                // A request whose VM went away — nothing to preserve.
            }
        }
        for (FastLogpoint other : logpoints.values()) {
            final BreakpointRequest arming = other.armingRequest;
            if (other.state == State.PENDING && arming != null && other.refType.equals(refType)) {
                toRecreate.add(new RecreatedBreakpoint(other.id, arming, other, other.methodName, other.methodSignature,
                    other.line, other.suspendPolicy, true));
            }
        }

        vm.redefineClasses(Map.of(refType, classFile));

        final EventRequestManager erm = vm.eventRequestManager();
        for (RecreatedBreakpoint bp : toRecreate) {
            try {
                final Location location = firstLocationOfLine(refType, bp.methodName(), bp.methodSignature(), bp.line());
                final FastLogpoint armingOwner = bp.armingOwner();
                if (armingOwner != null) {
                    byArmingRequest.remove(bp.previous());
                    final BreakpointRequest fresh = createArmingRequest(erm, location, bp.suspendPolicy());
                    armingOwner.armingRequest = fresh;
                    byArmingRequest.put(fresh, armingOwner);
                    fresh.enable();
                } else {
                    final BreakpointRequest fresh = erm.createBreakpointRequest(location);
                    fresh.setSuspendPolicy(bp.suspendPolicy());
                    if (breakpointTracker.replaceBreakpointRequest(bp.id(), bp.previous(), fresh)) {
                        fresh.setEnabled(bp.enabled());
                    } else {
                        // Cleared concurrently while the class was being redefined.
                        erm.deleteEventRequest(fresh);
                    }
                }
            } catch (Exception e) {
                eventHistory.record(new EventHistory.DebugEvent("FAST_LOGPOINT_WARNING",
                    String.format("Breakpoint #%d at %s:%d could not be re-created after redefining the class: %s",
                        bp.id(), refType.name(), bp.line(), describe(e)),
                    Map.of("breakpointId", String.valueOf(bp.id()), "class", refType.name(),
                        "line", String.valueOf(bp.line()))));
            }
        }
    }

    private static Location firstLocationOfLine(ReferenceType refType, String methodName, String methodSignature,
                                                int line) throws Exception {
        Location best = null;
        for (Location location : refType.locationsOfLine(line)) {
            final Method method = location.method();
            if (method.name().equals(methodName) && method.signature().equals(methodSignature)
                && (best == null || location.codeIndex() < best.codeIndex())) {
                best = location;
            }
        }
        if (best == null) {
            throw new FastLogpointException("line " + line + " no longer has code in " + methodName);
        }
        return best;
    }

    /**
     * Redefines the class without {@code flp}'s probe: back to the original bytes when it was the
     * last one, otherwise re-instrumented with the remaining sites. Refuses when the live code is
     * no longer what this service installed — restoring would undo someone else's change. Caller
     * holds the monitor.
     */
    private void uninstall(FastLogpoint flp) throws Exception {
        final ReferenceType refType = flp.refType;
        final InstrumentedClass target = instrumentedClasses.get(refType);
        if (target == null || !target.sites.containsKey(flp.id)) {
            return;
        }
        final String changed = firstDifference(refType, target.installed);
        if (changed != null) {
            throw new FastLogpointException("the live bytecode of " + refType.name() + '.' + changed + " differs from "
                + "what this server installed — something else redefined the class since, and restoring would undo "
                + "that change");
        }
        final Map<Integer, ProbeInstrumenter.ProbeSite> remaining = new LinkedHashMap<>(target.sites);
        remaining.remove(flp.id);
        final byte[] classFile = remaining.isEmpty()
            ? target.originalBytes
            : ProbeInstrumenter.instrument(target.originalBytes, remaining.values());
        redefine(flp.vm, refType, classFile);
        final Map<String, byte[]> installed = readBack(refType);
        final Set<String> wired = installed != null ? observeWiring(refType, installed) : null;
        // A transformer running during the redefinition can keep the call this server removed, and a
        // removal that cannot be observed is not taken for one.
        final boolean removed = provenRemoved(refType, target.originalBytes, installed, wired, flp.probeType);
        if (remaining.isEmpty() && removed) {
            instrumentedClasses.remove(refType);
            return;
        }
        if (removed) {
            target.sites = remaining;
        }
        target.installed = installed != null ? installed : ClassFileMethods.codeByMethod(classFile);
        final String unrecorded = recordInstall(flp.vm, refType, target, wired);
        if (!removed) {
            throw new FastLogpointException(installed != null && wired != null
                ? "after the redefinition " + refType.name() + " still calls the probe — an agent transforming the "
                + "class (a ClassFileTransformer) kept the call"
                : "the redefinition of " + refType.name() + " went through, but whether the class still calls the "
                + "probe could not be checked");
        }
        if (unrecorded != null) {
            recordWarning(flp, "the remaining fast logpoints of " + refType.name() + " could not be re-recorded in the "
                + "target JVM (" + unrecorded + ") — if this server dies without restoring the class, a later "
                + "session can only disable them");
        }
    }

    // ── Reading the ring ──

    /**
     * Copies the new entries of one evaluating fast logpoint into the event history; see
     * {@link ProbeRing} for the slot rules. Caller holds the monitor. Never throws.
     */
    private void drain(FastLogpoint flp) {
        if (flp.ring == null || flp.seq == null || !(flp.state == State.ARMED || flp.isLeftover())) {
            return;
        }
        try {
            final long written = readSeq(flp);
            final ProbeRing.Window window = ProbeRing.plan(flp.nextSeq, written, flp.bufferSize, EventHistory.MAX_EVENTS);
            long consumed = window.from();
            long lapped = 0;
            long lost = 0;
            if (window.to() > window.from()) {
                final List<Value> slots = readSlots(flp, window.from(), (int) (window.to() - window.from()));
                for (int i = 0; i < slots.size(); i++) {
                    final long seq = window.from() + i;
                    final String raw;
                    try {
                        raw = slots.get(i) instanceof StringReference string ? string.value() : null;
                    } catch (ObjectCollectedException replaced) {
                        // The string was collected between the array read and the string read — the
                        // slot had been overwritten.
                        lapped++;
                        consumed = seq + 1;
                        continue;
                    }
                    final ProbeRing.Entry entry = ProbeRing.parse(raw);
                    final ProbeRing.SlotState state = ProbeRing.classify(seq, entry);
                    if (state == ProbeRing.SlotState.READY && entry != null) {
                        recordEntry(flp, entry);
                        flp.logged++;
                    } else if (state == ProbeRing.SlotState.OVERWRITTEN) {
                        lapped++;
                    } else if (flp.stalledSeq == seq && System.nanoTime() - flp.stalledSince > STALLED_ENTRY_TIMEOUT_NANOS) {
                        lost++;
                    } else {
                        if (flp.stalledSeq != seq) {
                            flp.stalledSeq = seq;
                            flp.stalledSince = System.nanoTime();
                        }
                        break;
                    }
                    consumed = seq + 1;
                }
            }
            flp.nextSeq = consumed;
            final long dropped = window.overrun() + lapped + window.skipped() + lost;
            if (dropped > 0) {
                flp.dropped += dropped;
                recordDropped(flp, window.overrun() + lapped, window.skipped(), lost);
            }
        } catch (Exception e) {
            log.debug("[FastLogpoint] #{} drain failed: {}", flp.id, describe(e));
        }
    }

    private static long readSeq(FastLogpoint flp) {
        final ObjectReference seq = Objects.requireNonNull(flp.seq);
        return ((LongValue) seq.getValue(Objects.requireNonNull(flp.seqValue))).value();
    }

    /**
     * Reads {@code count} slots starting at sequence number {@code fromSeq}, splitting the read
     * where it wraps around the end of the array.
     */
    private static List<Value> readSlots(FastLogpoint flp, long fromSeq, int count) {
        final ArrayReference ring = Objects.requireNonNull(flp.ring);
        final int start = (int) (fromSeq & (flp.bufferSize - 1));
        if (start + count <= flp.bufferSize) {
            return ring.getValues(start, count);
        }
        final List<Value> slots = new ArrayList<>(ring.getValues(start, flp.bufferSize - start));
        slots.addAll(ring.getValues(0, count - (flp.bufferSize - start)));
        return slots;
    }

    private void recordEntry(FastLogpoint flp, ProbeRing.Entry entry) {
        if (flp.kind == Kind.BREAKPOINT) {
            eventHistory.record(new EventHistory.DebugEvent(Instant.ofEpochMilli(entry.epochMillis()),
                "FAST_BREAKPOINT_ERROR",
                String.format("[FastBreakpoint %d] condition '%s' failed: %s at %s:%d (thread %s, seq %d)", flp.id,
                    flp.condition, entry.value(), flp.className(), flp.line, entry.thread(), entry.seq()),
                Map.of("breakpointId", String.valueOf(flp.id), "condition", String.valueOf(flp.condition),
                    "error", entry.value(), "class", flp.className(), "line", String.valueOf(flp.line),
                    "thread", entry.thread(), "seq", String.valueOf(entry.seq()),
                    "nanoTime", String.valueOf(entry.nanoTime())),
                EventHistory.DebugEvent.UNSTAMPED));
            return;
        }
        final boolean value = entry.kind() == 'V';
        final String phase = entry.kind() == 'C' ? "condition" : "expression";
        final String summary = value
            ? String.format("[FastLogpoint %d] %s = %s at %s:%d (thread %s, seq %d)",
                flp.id, flp.expression, entry.value(), flp.className(), flp.line, entry.thread(), entry.seq())
            : String.format("[FastLogpoint %d] Error evaluating %s of '%s': %s at %s:%d (thread %s, seq %d)",
                flp.id, phase, flp.expression, entry.value(), flp.className(), flp.line, entry.thread(), entry.seq());
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("breakpointId", String.valueOf(flp.id));
        details.put("expression", flp.expression);
        details.put(value ? "result" : "error", entry.value());
        if (!value) {
            details.put("phase", phase);
        }
        details.put("class", flp.className());
        details.put("line", String.valueOf(flp.line));
        details.put("thread", entry.thread());
        details.put("seq", String.valueOf(entry.seq()));
        details.put("nanoTime", String.valueOf(entry.nanoTime()));
        eventHistory.record(new EventHistory.DebugEvent(Instant.ofEpochMilli(entry.epochMillis()),
            value ? "FAST_LOGPOINT" : "FAST_LOGPOINT_ERROR", summary, Map.copyOf(details),
            EventHistory.DebugEvent.UNSTAMPED));
    }

    private void recordDropped(FastLogpoint flp, long overrun, long skipped, long lost) {
        final List<String> reasons = new ArrayList<>();
        if (overrun > 0) {
            reasons.add(overrun + " overwritten before they were read (buffer overrun — call jdwp_get_events "
                + "more often, raise bufferSize or narrow the condition)");
        }
        if (skipped > 0) {
            reasons.add(skipped + " skipped because one read ingests at most " + EventHistory.MAX_EVENTS
                + " entries (the event-history capacity)");
        }
        if (lost > 0) {
            reasons.add(lost + " never completed by the writing thread");
        }
        eventHistory.record(new EventHistory.DebugEvent("FAST_LOGPOINT_DROPPED",
            String.format("[FastLogpoint %d] %d entries dropped: %s", flp.id, overrun + skipped + lost,
                String.join("; ", reasons)),
            Map.of("breakpointId", String.valueOf(flp.id), "overrun", String.valueOf(overrun),
                "skipped", String.valueOf(skipped), "lost", String.valueOf(lost))));
    }

    // ── Helpers ──

    private static BreakpointRequest createArmingRequest(EventRequestManager erm, Location location, int suspendPolicy) {
        final BreakpointRequest request = erm.createBreakpointRequest(location);
        request.setSuspendPolicy(suspendPolicy);
        // Exactly one hit suspends; concurrent hits on other threads are filtered in the target.
        request.addCountFilter(1);
        return request;
    }

    /**
     * Replaces a spent arming request with a fresh one at the same location.
     */
    private synchronized void rearm(FastLogpoint flp) {
        if (flp.state != State.PENDING || logpoints.get(flp.id) != flp) {
            return;
        }
        try {
            final Location location = firstLocationOfLine(flp.refType, flp.methodName, flp.methodSignature, flp.line);
            deleteArmingRequest(flp);
            final BreakpointRequest fresh = createArmingRequest(flp.vm.eventRequestManager(), location,
                flp.suspendPolicy);
            flp.armingRequest = fresh;
            byArmingRequest.put(fresh, flp);
            fresh.enable();
        } catch (Exception e) {
            markFailed(flp, "could not re-arm after a hit inside an MCP evaluation: " + describe(e), true);
        }
    }

    private void deleteArmingRequest(FastLogpoint flp) {
        final BreakpointRequest request = flp.armingRequest;
        flp.armingRequest = null;
        if (request != null) {
            byArmingRequest.remove(request);
            try {
                flp.vm.eventRequestManager().deleteEventRequest(request);
            } catch (Exception ignored) {
                // Already deleted (redefinition) or VM gone.
            }
        }
    }

    private synchronized void markFailed(FastLogpoint flp, String reason, boolean classUntouched) {
        deleteArmingRequest(flp);
        if (classUntouched) {
            // Only an untouched class certainly never calls the probe; otherwise a later attach
            // observes the wiring, and a stop must not park a thread unreported meanwhile.
            deleteStopRequest(flp);
        }
        if (flp.state == State.PENDING) {
            flp.state = State.FAILED;
            flp.failureReason = reason;
            flp.classUntouched = classUntouched;
        }
        eventHistory.record(new EventHistory.DebugEvent(flp.kind.eventPrefix + "_ARM_FAILED",
            String.format("[%s %d] arming at %s:%d failed%s: %s", flp.kind.tag, flp.id, flp.className(), flp.line,
                classUntouched ? " — the class was not modified" : "", reason),
            Map.of("breakpointId", String.valueOf(flp.id), "class", flp.className(),
                "line", String.valueOf(flp.line), "reason", reason)));
        log.warn("[{}] #{} arming failed: {}", flp.kind.tag, flp.id, reason);
    }

    private static Field probeField(ClassType probeType, String name) throws FastLogpointException {
        final Field field = probeType.fieldByName(name);
        if (field == null) {
            throw new FastLogpointException("probe class " + probeType.name() + " has no field " + name);
        }
        return field;
    }

    private static void setEnabled(FastLogpoint flp, boolean enabled) throws Exception {
        final ClassType probeType = Objects.requireNonNull(flp.probeType);
        probeType.setValue(probeField(probeType, FastLogpointProbeSource.ENABLED_FIELD), flp.vm.mirrorOf(enabled));
    }

    private boolean setKillSwitch(FastLogpoint flp) {
        try {
            setEnabled(flp, false);
            flp.enabled = false;
            return true;
        } catch (Exception e) {
            log.warn("[FastLogpoint] #{} kill switch could not be set: {}", flp.id, describe(e));
            return false;
        }
    }

    /**
     * Drops the install record of a probe that is disabled and no longer called, freeing the
     * record's memory in the target once no probe references it. Best-effort; later sessions skip
     * the probe either way (it is uncalled and disabled).
     */
    private static void retire(FastLogpoint flp) {
        final ClassType probeType = flp.probeType;
        if (probeType == null) {
            return;
        }
        try {
            probeType.setValue(probeField(probeType, FastLogpointProbeSource.IMAGE_FIELD), null);
        } catch (Exception e) {
            log.debug("[FastLogpoint] #{} not retired: {}", flp.id, describe(e));
        }
    }

    /**
     * Nulls the ring's slots after the final drain and stops further drains. The probe class stays
     * loaded as long as its class loader lives, and its static ring would otherwise keep up to
     * {@code bufferSize} entry strings reachable in the target for good. Best-effort — one array write.
     */
    private static void releaseRing(FastLogpoint flp) {
        final ArrayReference ring = flp.ring;
        if (ring == null) {
            return;
        }
        flp.ring = null;
        try {
            ring.setValues(0, Collections.nCopies(flp.bufferSize, null), 0, flp.bufferSize);
        } catch (Exception e) {
            log.debug("[FastLogpoint] #{} ring not released: {}", flp.id, describe(e));
        }
    }

    private void recordWarning(FastLogpoint flp, String message) {
        eventHistory.record(new EventHistory.DebugEvent(flp.kind.eventPrefix + "_WARNING",
            String.format("[%s %d] %s", flp.kind.tag, flp.id, message),
            Map.of("breakpointId", String.valueOf(flp.id), "class", flp.className(),
                "line", String.valueOf(flp.line))));
        log.warn("[{}] #{} {}", flp.kind.tag, flp.id, message);
    }

    private void abandonAll(String reason, String cause) {
        for (FastLogpoint flp : new ArrayList<>(logpoints.values())) {
            if (flp.state == State.ARMED || flp.state == State.DISABLED) {
                recordWarning(flp, String.format("could not be disarmed during %s (%s) — the instrumentation of %s "
                    + "stays in the target JVM; the next attach lists it", reason, cause, flp.className()));
            }
        }
        forgetAll();
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private static String describe(Throwable e) {
        final String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    // ── Fast breakpoints ──

    /**
     * Whether {@code simpleName} is the simple name of a probe class this server generates.
     */
    public static boolean isProbeClassName(String simpleName) {
        return simpleName.startsWith(FastLogpointProbeSource.CLASS_PREFIX)
            || simpleName.startsWith(FastBreakpointProbeSource.CLASS_PREFIX);
    }

    /**
     * The fast breakpoint's arming hit, evaluated through the debugger: this execution runs the old
     * code, so the probe never sees it. A hit that stops (the condition holds or fails, and its gate
     * is open) is published at once and installs nothing — redefining the method now would leave this
     * stopped frame in an obsolete method without locals. Any other hit installs the probe. Runs
     * outside the monitor apart from the gate decision; never invokes under it.
     *
     * @return whether the hit was published as a stop
     */
    private boolean evaluateArmingHit(FastLogpoint flp, ThreadReference thread, Location location) {
        String error = null;
        boolean passed = false;
        try {
            final Value value = expressionEvaluator.evaluate(thread.frame(0), Objects.requireNonNull(flp.condition));
            final Boolean result = asBoolean(value);
            if (result == null) {
                error = "condition did not evaluate to a boolean: " + renderArmingValue(value);
            } else {
                passed = result;
            }
        } catch (Exception e) {
            error = describe(e);
        }
        synchronized (this) {
            if (logpoints.get(flp.id) != flp || flp.state != State.PENDING) {
                return false;
            }
            if (error != null) {
                recordArmingError(flp, error, thread);
                if (flp.errorClaimedEpoch >= flp.errorEpoch) {
                    return false;
                }
                flp.errorClaimedEpoch = flp.errorEpoch;
                publishStop(flp, thread, location, true, error, true);
                rearm(flp);
                return true;
            }
            if (!passed) {
                return false;
            }
            if (!flp.stopOnce) {
                publishStop(flp, thread, location, false, null, true);
                rearm(flp);
                return true;
            }
            if (flp.claimedEpoch >= flp.epoch) {
                return false;
            }
            // Spent before it was ever installed: no arming until a re-arm opens the next epoch.
            flp.claimedEpoch = flp.epoch;
            deleteArmingRequest(flp);
            publishStop(flp, thread, location, false, null, true);
            return true;
        }
    }

    /**
     * Publishes a fast-breakpoint stop the way the listener publishes a breakpoint hit: the tracker
     * snapshot, then the {@code FAST_BREAKPOINT} history entry, then the next-event latch. Caller
     * holds the monitor.
     *
     * @param location   the user's location (the first frame below the probe's)
     * @param armingHit  whether the hit was evaluated through the debugger
     */
    private void publishStop(FastLogpoint flp, ThreadReference thread, Location location, boolean failed,
                             @Nullable String error, boolean armingHit) {
        final String className = location.declaringType().name();
        final int line = location.lineNumber();
        final String threadName = thread.name();
        final String reason = failed ? "condition failed: " + error : "condition true";
        breakpointTracker.setLastBreakpointThread(thread, flp.id);
        final Map<String, String> details = new LinkedHashMap<>();
        details.put("breakpointId", String.valueOf(flp.id));
        details.put("class", className);
        details.put("line", String.valueOf(line));
        details.put("thread", threadName);
        details.put("condition", String.valueOf(flp.condition));
        details.put("reason", reason);
        if (armingHit) {
            details.put("armingHit", "true");
        }
        eventHistory.record(new EventHistory.DebugEvent("FAST_BREAKPOINT",
            String.format("Fast breakpoint %d hit at %s:%d on thread %s (%s%s)", flp.id, className, line, threadName,
                reason, armingHit ? "; first hit, evaluated through the debugger" : ""),
            Map.copyOf(details)));
        log.info("[FastBreakpoint] #{} hit on thread {} at {}:{} ({})", flp.id, threadName, className, line, reason);
        breakpointTracker.fireNextEvent();
    }

    private void recordArmingError(FastLogpoint flp, String error, ThreadReference thread) {
        eventHistory.record(new EventHistory.DebugEvent("FAST_BREAKPOINT_ERROR",
            String.format("[FastBreakpoint %d] condition '%s' failed: %s at %s:%d (thread %s, first hit — evaluated "
                + "through the debugger)", flp.id, flp.condition, error, flp.className(), flp.line, thread.name()),
            Map.of("breakpointId", String.valueOf(flp.id), "condition", String.valueOf(flp.condition), "error", error,
                "class", flp.className(), "line", String.valueOf(flp.line), "thread", thread.name(),
                "armingHit", "true")));
    }

    /**
     * Whether a stop the probe reported still belongs to its gate's current generation. A true stop
     * in every-hit mode claims no gate and is always current.
     */
    private static boolean isCurrent(FastLogpoint flp, StopArguments stop) {
        if (stop.kind() == FastBreakpointProbeSource.STOP_CONDITION_ERROR) {
            return stop.epoch() == flp.errorEpoch;
        }
        return !flp.stopOnce || stop.epoch() == flp.epoch;
    }

    /**
     * Opens the next generation of a gate, here and in the probe. Caller holds the monitor.
     */
    private void advanceEpoch(FastLogpoint flp, boolean errorGate) {
        final int current = errorGate ? flp.errorEpoch : flp.epoch;
        if (current == Integer.MAX_VALUE) {
            recordWarning(flp, "its " + (errorGate ? "error" : "stop") + " gate cannot be re-opened any more — clear it "
                + "and set it again");
            return;
        }
        if (errorGate) {
            flp.errorEpoch = current + 1;
        } else {
            flp.epoch = current + 1;
        }
        final ClassType probeType = flp.probeType;
        if (probeType == null) {
            return;
        }
        try {
            probeType.setValue(probeField(probeType, errorGate
                    ? FastBreakpointProbeSource.ERROR_EPOCH_FIELD : FastBreakpointProbeSource.EPOCH_FIELD),
                flp.vm.mirrorOf(current + 1));
        } catch (Exception e) {
            recordWarning(flp, "its gate could not be re-opened in the target JVM: " + describe(e));
        }
    }

    /**
     * Copies the server's gate state into a freshly defined probe, before anything can call it.
     */
    private static void writeGates(FastLogpoint flp) throws Exception {
        final ClassType probeType = Objects.requireNonNull(flp.probeType);
        probeType.setValue(probeField(probeType, FastBreakpointProbeSource.EPOCH_FIELD), flp.vm.mirrorOf(flp.epoch));
        probeType.setValue(probeField(probeType, FastBreakpointProbeSource.ERROR_EPOCH_FIELD),
            flp.vm.mirrorOf(flp.errorEpoch));
        setAtomicInteger(flp, probeType, FastBreakpointProbeSource.CLAIMED_FIELD, flp.claimedEpoch);
        setAtomicInteger(flp, probeType, FastBreakpointProbeSource.ERROR_CLAIMED_FIELD, flp.errorClaimedEpoch);
    }

    private static void setAtomicInteger(FastLogpoint flp, ClassType probeType, String name, int value) throws Exception {
        if (!(probeType.getValue(probeField(probeType, name)) instanceof ObjectReference atomic)) {
            throw new FastLogpointException("probe field " + name + " is not initialized");
        }
        final Field inner = atomic.referenceType().fieldByName("value");
        if (inner == null) {
            throw new FastLogpointException("AtomicInteger#value not found in the target VM");
        }
        atomic.setValue(inner, flp.vm.mirrorOf(value));
    }

    /**
     * Creates and enables the stop request on the probe's {@code stop()}, with the entry's policy.
     */
    private void createStopRequest(FastLogpoint flp, ClassType probeType) throws FastLogpointException {
        final List<Method> stops = probeType.methodsByName(FastBreakpointProbeSource.STOP_METHOD);
        if (stops.isEmpty()) {
            throw new FastLogpointException("probe class " + probeType.name() + " has no stop() method");
        }
        final BreakpointRequest request = flp.vm.eventRequestManager().createBreakpointRequest(stops.get(0).location());
        request.setSuspendPolicy(flp.suspendPolicy);
        flp.stopRequest = request;
        byStopRequest.put(request, flp);
        request.enable();
    }

    /**
     * Deletes the stop request; one of its events may still be in flight, so the request is retired
     * rather than forgotten.
     */
    private void deleteStopRequest(FastLogpoint flp) {
        final BreakpointRequest request = flp.stopRequest;
        flp.stopRequest = null;
        if (request == null) {
            return;
        }
        byStopRequest.remove(request);
        retiredStopRequests.add(request);
        try {
            flp.vm.eventRequestManager().deleteEventRequest(request);
        } catch (Exception ignored) {
            // VM gone.
        }
    }

    /**
     * The arguments of the probe's {@code stop(kind, epoch, error)} in frame 0, or {@code null} when
     * they cannot be read. {@code getArgumentValues} needs no local-variable table.
     */
    @Nullable
    private static StopArguments stopArguments(ThreadReference thread) {
        try {
            final List<Value> args = thread.frame(0).getArgumentValues();
            if (args.size() == 3 && args.get(0) instanceof IntegerValue kind && args.get(1) instanceof IntegerValue epoch) {
                final String error = args.get(2) instanceof StringReference text ? text.value() : null;
                return new StopArguments(kind.value(), epoch.value(), error);
            }
        } catch (Exception e) {
            log.debug("[FastBreakpoint] stop() arguments unreadable: {}", describe(e));
        }
        return null;
    }

    /**
     * The location of the first frame that does not belong to a probe class: where the user's code
     * stopped. Falls back to {@code fallback}.
     */
    private static Location userLocation(ThreadReference thread, Location fallback) {
        try {
            for (StackFrame frame : thread.frames()) {
                final Location location = frame.location();
                if (!isProbeClassName(simpleName(location.declaringType().name()))) {
                    return location;
                }
            }
        } catch (Exception e) {
            log.debug("[FastBreakpoint] user frame unresolvable: {}", describe(e));
        }
        return fallback;
    }

    /**
     * Reads a fast breakpoint's counters from its probe; unread values are {@code -1}.
     */
    private static BreakpointCounters breakpointCounters(FastLogpoint flp) {
        final ClassType probeType = flp.probeType;
        if (probeType == null) {
            return new BreakpointCounters(-1, -1, -1, flp.stopOnce && flp.claimedEpoch >= flp.epoch);
        }
        final long trueHits = atomicValue(probeType, FastBreakpointProbeSource.TRUE_HITS_FIELD);
        final long errors = atomicValue(probeType, FastBreakpointProbeSource.ERRORS_FIELD);
        final long stops = atomicValue(probeType, FastBreakpointProbeSource.STOPS_FIELD);
        final boolean spent = flp.stopOnce && atomicValue(probeType, FastBreakpointProbeSource.CLAIMED_FIELD) >= flp.epoch;
        return new BreakpointCounters(trueHits, errors, stops, spent);
    }

    /**
     * The value of the probe's {@code AtomicInteger} / {@code AtomicLong} field {@code name}, or
     * {@code -1} when it cannot be read.
     */
    private static long atomicValue(ClassType probeType, String name) {
        try {
            final Field field = probeType.fieldByName(name);
            if (field != null && probeType.getValue(field) instanceof ObjectReference atomic) {
                final Field inner = atomic.referenceType().fieldByName("value");
                if (inner != null && atomic.getValue(inner) instanceof PrimitiveValue value) {
                    return value.longValue();
                }
            }
        } catch (Exception ignored) {
            // VM unreachable.
        }
        return -1;
    }

    /**
     * The user's text of an entry for listings: the expression of a fast logpoint, the condition of
     * a fast breakpoint.
     */
    private static String describeUserText(FastLogpoint flp) {
        return flp.kind == Kind.LOGPOINT ? "expr=\"" + flp.expression + '"' : "condition=\"" + flp.condition + '"';
    }

    /**
     * What a fast breakpoint probe's {@code stop()} was called with.
     */
    private record StopArguments(int kind, int epoch, @Nullable String error) {
    }

    // ── State ──

    /**
     * Fast-logpoint states.
     */
    public enum State {
        /**
         * Registered; waiting for the first hit to arm.
         */
        PENDING,
        /**
         * The class runs instrumented bytecode and the probe fills its ring.
         */
        ARMED,
        /**
         * Installed by this session but not evaluating: enabling it failed, or restoring the class
         * failed after the kill switch was set. Clear retries the restore.
         */
        DISABLED,
        /**
         * Arming failed; the class was not modified. The reason is kept for the overview.
         */
        FAILED,
        /**
         * Cleared.
         */
        DISARMED,
        /**
         * Left by an earlier session; the class is exactly as that session recorded it. Clear sets
         * the kill switch; {@link #restore} restores the class with the user's approval.
         */
        RECOVERED,
        /**
         * Left by an earlier session; the class changed since, or the probe's wiring is unknown.
         * Only the kill switch is offered.
         */
        STRANDED
    }

    /**
     * Read-only view of one fast logpoint for {@code jdwp_overview}.
     *
     * @param written    live value of the probe's sequence counter, or {@code -1} when unavailable
     * @param enabled    whether the probe's kill switch lets it evaluate, as last set or read
     * @param provenance who armed it, for leftovers of an earlier session; {@code null} for this session's
     */
    public record Info(int id, String className, int line, String expression, @Nullable String condition,
                       State state, @Nullable String failureReason, int bufferSize, long logged, long dropped,
                       long written, boolean enabled, @Nullable Provenance provenance, Kind kind, int suspendPolicy,
                       boolean stopOnce, @Nullable BreakpointCounters counters) {
    }

    /**
     * What a fast breakpoint counted so far.
     *
     * @param trueHits how often the condition held ({@code -1} when the probe is unreadable or not installed)
     * @param errors   how often the condition failed ({@code -1} likewise)
     * @param stops    how often its {@code stop()} was reached ({@code -1} likewise)
     * @param spent    whether a once-mode breakpoint already stopped in its current epoch
     */
    public record BreakpointCounters(long trueHits, long errors, long stops, boolean spent) {
    }

    /**
     * The two kinds of in-target probe this service owns.
     */
    public enum Kind {
        LOGPOINT("Fast logpoint", "FastLogpoint", "FAST_LOGPOINT"),
        BREAKPOINT("Fast breakpoint", "FastBreakpoint", "FAST_BREAKPOINT");

        /**
         * Name in sentences, e.g. "Fast logpoint 7 cleared".
         */
        public final String label;
        /**
         * Bracketed tag in event summaries, e.g. "[FastLogpoint 7]".
         */
        final String tag;
        /**
         * Prefix of the event-history types, e.g. {@code FAST_LOGPOINT_ARMED}.
         */
        final String eventPrefix;

        Kind(String label, String tag, String eventPrefix) {
            this.label = label;
            this.tag = tag;
            this.eventPrefix = eventPrefix;
        }
    }

    /**
     * Where a leftover came from.
     *
     * @param armedAt  when its probe was generated
     * @param armedBy  the arming server ({@code MCP_ARMED_BY})
     * @param loader   the class loader of the instrumented class
     * @param retained upper bound of the ring entries written before this session found it (not replayed)
     */
    public record Provenance(Instant armedAt, String armedBy, String loader, long retained) {
    }

    /**
     * Mutable state of one fast logpoint; guarded by the service monitor.
     */
    private static final class FastLogpoint {
        final int id;
        final Kind kind;
        final VirtualMachine vm;
        final ReferenceType refType;
        final String className;
        final int line;
        final String methodName;
        final String methodSignature;
        final String expression;
        @Nullable
        final String condition;
        final int bufferSize;
        /**
         * Suspend policy of the arming request and, for a fast breakpoint, of its stop request.
         */
        final int suspendPolicy;
        /**
         * Fast breakpoints: whether only the first true hit of an epoch stops.
         */
        final boolean stopOnce;

        // Fast breakpoints, under the service monitor. The generations of the true-hit and the error
        // gate (the server is their only writer, the probe gets a copy) and, before the probe exists,
        // the generations claimed by arming hits.
        int epoch = 1;
        int errorEpoch = 1;
        int claimedEpoch;
        int errorClaimedEpoch;
        @Nullable
        BreakpointRequest stopRequest;

        // Read by the event threads and MCP workers; written under the service monitor.
        volatile State state = State.PENDING;
        @Nullable
        volatile String failureReason;
        @Nullable
        BreakpointRequest armingRequest;

        @Nullable
        ClassType probeType;
        @Nullable
        ArrayReference ring;
        @Nullable
        ObjectReference seq;
        @Nullable
        Field seqValue;
        /**
         * The kill switch as last set or read.
         */
        boolean enabled;
        /**
         * For a FAILED arm: whether the class was certainly left unmodified.
         */
        boolean classUntouched = true;

        /**
         * First sequence number not yet copied into the event history.
         */
        long nextSeq;
        long logged;
        long dropped;
        /**
         * Sequence number a drain last found unwritten, and since when ({@link System#nanoTime()}).
         */
        long stalledSeq = -1;
        long stalledSince;

        // Leftovers only.
        @Nullable
        GenerationRecord record;
        boolean provenUnwired;
        long armedAt;
        @Nullable
        String armedBy;
        @Nullable
        String loader;
        long retained;

        FastLogpoint(int id, Kind kind, VirtualMachine vm, ReferenceType refType, String className, int line,
                     String methodName, String methodSignature, String expression, @Nullable String condition,
                     int bufferSize, int suspendPolicy, boolean stopOnce) {
            this.id = id;
            this.kind = kind;
            this.vm = vm;
            this.refType = refType;
            this.className = className;
            this.line = line;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.expression = expression;
            this.condition = condition;
            this.bufferSize = bufferSize;
            this.suspendPolicy = suspendPolicy;
            this.stopOnce = stopOnce;
        }

        String className() {
            return className;
        }

        boolean isLeftover() {
            return state == State.RECOVERED || state == State.STRANDED;
        }

        void attach(ClassType probeType, @Nullable ArrayReference ring, @Nullable ObjectReference seq,
                    @Nullable Field seqValue) {
            this.probeType = probeType;
            this.ring = ring;
            this.seq = seq;
            this.seqValue = seqValue;
        }
    }

    /**
     * Original bytes of a class this session redefined, what HotSpot installed at the last
     * redefinition, and the probe sites in it.
     */
    private static final class InstrumentedClass {
        final byte[] originalBytes;
        // Written under the service monitor; volatile because arming reads them before taking it.
        volatile Map<String, byte[]> installed;
        volatile Map<Integer, ProbeInstrumenter.ProbeSite> sites = new LinkedHashMap<>();

        InstrumentedClass(byte[] originalBytes) {
            this.originalBytes = originalBytes;
            this.installed = ClassFileMethods.codeByMethod(originalBytes);
        }
    }

    /**
     * A probe class defined in the target, with the mirrors {@link #drain} reads.
     */
    private record DefinedProbe(ClassType type, FastLogpointProbe compiled, ArrayReference ring,
                                ObjectReference seq, Field seqValue) {
    }

    /**
     * A breakpoint deleted by a redefinition, to be re-created on the new bytecode.
     *
     * @param armingOwner the pending fast logpoint when this is an arming request, else {@code null}
     */
    private record RecreatedBreakpoint(int id, BreakpointRequest previous, @Nullable FastLogpoint armingOwner,
                                       String methodName, String methodSignature, int line, int suspendPolicy,
                                       boolean enabled) {
    }

    /**
     * A target class name within one class loader.
     */
    private record TargetKey(String className, @Nullable ClassLoaderReference loader) {
    }

    /**
     * Everything discovery reads from one probe class.
     */
    private record FoundProbe(ClassType type, LeftoverClassifier.Probe facts, String targetClass, String method,
                              String descriptor, int line, String expression, @Nullable String condition,
                              long armedAt, String armedBy, @Nullable ArrayReference image,
                              @Nullable ArrayReference ring, @Nullable ObjectReference seq, @Nullable Field seqValue,
                              long written, int bufferSize, String loader, Kind kind, boolean stopOnce) {
    }

    /**
     * One listed leftover: the probe, its verdict, the resolved target class and the matching record.
     */
    private record Discovered(FoundProbe probe, LeftoverClassifier.Verdict verdict, @Nullable ReferenceType target,
                              @Nullable GenerationRecord record) {
    }

    /**
     * A redefinition that failed after it may already have been applied.
     */
    private static final class UncertainRedefinitionException extends FastLogpointException {
        UncertainRedefinitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
