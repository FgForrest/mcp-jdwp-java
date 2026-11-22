package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.WatchpointEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Who owns each suspension of one JDI connection (issue #45; {@code PROPOSAL-suspension-ownership.md}).
 * <p>
 * Every routed event set is a {@link Context}. While it is queued or processed the dispatcher owns
 * its suspension and releases it when processing keeps no stop. A stop is published once, when the
 * whole set has been processed, and becomes a <em>hold</em>: only the user releases it, and only
 * after a tool has presented it. Tools never resume blindly — {@code vm.resume()} runs only as a
 * context's own {@link EventSet#resume()}, after re-suspending the threads a user release took out
 * of it, so the release nets zero on them.
 * <p>
 * One monitor guards all state; it spans the session's short JDWP commands (suspend, resume) but
 * never event processing, an invocation or a wait (waits use the monitor's own condition). A closed
 * session ignores every call, so a late completion from an old dispatcher cannot touch the next
 * connection's state.
 */
public final class SuspensionSession {
    private static final Logger log = LoggerFactory.getLogger(SuspensionSession.class);

    /**
     * Request property carrying the generation a request was created in; see {@link #stamp}.
     */
    public static final String GENERATION_PROPERTY = "mcp.generation";
    /**
     * The manual hold in {@link #holdsOn}; context ids start at 1.
     */
    public static final long MANUAL_HOLD = 0L;
    /**
     * A step in flight in {@link #heldThreads}: the thread runs, but stops again where the user looks.
     */
    public static final long STEP_HOLD = -1L;
    /**
     * A suspension nothing in this session accounts for in {@link #heldThreads}: a set raised but
     * not routed yet.
     */
    public static final long UNKNOWN_HOLD = -2L;
    /**
     * How long a {@link #lease} waits for a frame tool's {@link #reserve} of its thread to end.
     */
    static final long RESERVATION_WAIT_MS = 2_000;
    /**
     * Request property marking a request whose events need their thread still stopped when the
     * dispatcher handles them even to skip them (a fast breakpoint's stop reads its arguments from
     * the frame): the watchdog never cancels a set with such an event.
     */
    public static final String KEEPS_THREAD_PROPERTY = "mcp.keepsThread";

    /**
     * Life cycle of a context.
     */
    public enum State {
        /**
         * Routed, waiting for the dispatcher.
         */
        QUEUED,
        /**
         * Being processed by the dispatcher (or, for a death set, the listener).
         */
        PROCESSING,
        /**
         * Published as a stop; the user owns it.
         */
        HELD,
        /**
         * Finished without a stop, but still owns its suspension after its automatic resume failed.
         */
        RELEASE_FAILED,
        /**
         * Its suspension has been released.
         */
        RELEASED
    }

    /**
     * One routed event set and the suspension it holds.
     */
    public static final class Context {
        private final long id;
        private final long generation;
        private final EventSet eventSet;
        private final int policy;
        @Nullable
        private final ThreadReference thread;
        private final boolean vmStart;
        private final boolean staleAtRouting;
        /**
         * Whether a handler may publish a stop, as judged at routing: a set of logpoints, say, never
         * does, so its threads are not held for the user (the redefinition rule, {@link #heldThreads}).
         */
        private final boolean mayStop;
        private State state = State.QUEUED;
        private boolean reported;
        private @Nullable StopReport report;
        private final List<BreakpointTracker.LastBreakpoint> candidates = new ArrayList<>();
        private final List<EventHistory.DebugEvent> history = new ArrayList<>();
        /**
         * Threads a user release took out of this stop-all context; re-suspended before its own
         * resume so the release nets zero on them.
         */
        private final Set<ThreadReference> released = new LinkedHashSet<>();
        /**
         * Leased threads taken out of this context so their invocation can run; each goes back at
         * its lease's end, or is re-suspended before this context's own resume.
         */
        private final Set<ThreadReference> exempt = new LinkedHashSet<>();
        /**
         * Temporary re-suspensions for EventSet.resume(). Only those whose rollback also failed
         * can survive a release attempt. The next attempt reuses or undoes them.
         */
        private final Set<ThreadReference> compensated = new LinkedHashSet<>();
        /**
         * The most recent failure to release this finished set, present in RELEASE_FAILED.
         */
        @Nullable
        private RuntimeException automaticReleaseFailure;
        /**
         * Failed watchdog releases, keyed by the thread whose share was being released.
         */
        private final Map<ThreadReference, WatchdogReleaseFailure> watchdogFailures = new LinkedHashMap<>();
        /**
         * Threads the watchdog released from this stop-all so an evaluation could finish (A1); they
         * are in {@link #released} too. A stop published with any is partial.
         */
        private final Set<ThreadReference> unblocked = new LinkedHashSet<>();
        /**
         * The watchdog released the event thread before the set was processed: no handler may use
         * the thread or publish a stop; {@link SuspensionSession#finish} settles it.
         */
        private boolean cancelled;

        private BreakpointTracker.@Nullable LastBreakpoint snapshot() {
            final StopReport published = report;
            return published == null ? null : published.stop();
        }

        private Context(long id, long generation, EventSet eventSet, int policy, @Nullable ThreadReference thread,
                        boolean vmStart, boolean staleAtRouting, boolean mayStop) {
            this.id = id;
            this.generation = generation;
            this.eventSet = eventSet;
            this.policy = policy;
            this.thread = thread;
            this.vmStart = vmStart;
            this.staleAtRouting = staleAtRouting;
            this.mayStop = mayStop;
        }

        public long id() {
            return id;
        }

        public int policy() {
            return policy;
        }

        @Nullable
        public ThreadReference thread() {
            return thread;
        }

        /**
         * Whether a method can be invoked on this context's event thread: the JDWP agent enables
         * invocation for the event thread of an ordinary suspending event, but not for VMStart and
         * not for a set that suspends nothing.
         */
        public boolean invocable() {
            return !vmStart && policy != EventRequest.SUSPEND_NONE && thread != null;
        }

        @Override
        public String toString() {
            return "context#" + id + '[' + state + (reported ? ", reported" : "") + ", policy=" + policy
                + (thread != null ? ", thread=" + safeName(thread) : "") + ']';
        }
    }

    /**
     * One release that threw: the holder it tried to release and the JDI failure, with any failed
     * rollback of temporary suspensions attached as suppressed exceptions.
     *
     * @param holder        the holder whose release failed, in words ("breakpoint 3 on thread main")
     * @param cause         what JDI threw
     * @param retryThreadId the event thread or debtor to target for recovery, if available
     */
    public record ReleaseFailure(String holder, RuntimeException cause, @Nullable Long retryThreadId) {
        /** Records a failure without a thread-specific recovery hint. */
        public ReleaseFailure(String holder, RuntimeException cause) {
            this(holder, cause, null);
        }

        /**
         * The holder, any retry thread ID, the exception and every failed rollback.
         */
        public String describe() {
            final StringBuilder out = new StringBuilder(holder).append(" (");
            if (retryThreadId != null) {
                out.append("thread ID ").append(retryThreadId).append(", ");
            }
            out.append(brief(cause));
            for (Throwable rollback : cause.getSuppressed()) {
                out.append("; ").append(rollback.getMessage());
            }
            return out.append(')').toString();
        }
    }

    /**
     * What a release did.
     *
     * @param released the stops released, oldest first
     * @param unseen   stops still held because no tool has presented them yet
     * @param pending  sets still waiting for or being processed; their threads stay parked
     * @param manual   threads whose manual hold was released
     * @param recovered finished sets, watchdog releases and lease debts successfully retried
     * @param failures attempted releases that threw
     * @param closed   the session is closed
     */
    public record ReleaseResult(List<BreakpointTracker.LastBreakpoint> released,
                                List<BreakpointTracker.LastBreakpoint> unseen, int pending,
                                List<ThreadReference> manual, List<String> recovered,
                                List<ReleaseFailure> failures, boolean closed) {
        /**
         * Whether anything was released.
         */
        public boolean releasedAnything() {
            return !released.isEmpty() || !manual.isEmpty() || !recovered.isEmpty();
        }
    }

    /**
     * Result of attempting to release a thread's holds. An empty list with no refusal means
     * there was nothing to release.
     *
     * @param refusal  why ordinary holds could not be released, or {@code null} when they were eligible
     * @param holders  what it released, in words
     * @param failures attempted releases that threw
     * @param closed   the session is closed
     */
    public record ThreadRelease(@Nullable String refusal, List<String> holders, List<ReleaseFailure> failures,
                                boolean closed) {
        public boolean released() {
            return !holders.isEmpty();
        }
    }

    /**
     * What {@link #reset} released.
     *
     * @param stops held stops, seen or not
     * @param holds {@code jdwp_suspend_thread} holds
     * @param recovered finished sets, watchdog releases and lease debts successfully retried
     * @param failures attempted releases that threw
     * @param closed whether the session was closed by the end of the pass
     */
    public record ResetResult(int stops, int holds, List<String> recovered, List<ReleaseFailure> failures,
                              boolean closed) {
        /** Result when there was no session to reset. */
        public static final ResetResult NONE = new ResetResult(0, 0);

        /**
         * A reset whose releases all succeeded.
         */
        public ResetResult(int stops, int holds) {
            this(stops, holds, List.of(), List.of(), false);
        }
    }

    /**
     * Outcome of {@link #nextStop}.
     */
    public enum Outcome {
        /**
         * A stop is presented.
         */
        STOPPED,
        /** The step's stop was already presented by another call. */
        ALREADY_PRESENTED,
        /** The step was cancelled or replaced before publishing a stop. */
        STEP_ENDED,
        /**
         * The deadline passed without a stop.
         */
        TIMEOUT,
        /**
         * The session closed (disconnect, death, reconnect).
         */
        CLOSED,
        /**
         * A release failed on the first pass or a recovery became necessary while waiting.
         */
        RESUME_FAILED,
        /**
         * {@code jdwp_reset} ran while waiting.
         */
        RESET,
        /**
         * A targeted wait could not release the selected thread.
         */
        REFUSED
    }

    /**
     * Result of {@link #nextStop}.
     *
     * @param outcome       what ended the wait
     * @param report        the selected stop, including its metadata as published
     * @param released      global release progress and recovery failures, if any
     * @param threadRelease targeted release progress and recovery failures, if any
     * @param refusal       the blocker that ended a targeted wait, if any
     * @param otherUnseen   unseen stops of other threads when a thread was selected
     */
    public record NextStop(Outcome outcome, @Nullable StopReport report, @Nullable ReleaseResult released,
                           @Nullable ThreadRelease threadRelease, @Nullable String refusal, int otherUnseen) {
        /** The selected stop without its captured presentation details. */
        public BreakpointTracker.@Nullable LastBreakpoint stop() {
            return report == null ? null : report.stop();
        }
    }

    /** Data the tool can report after the target thread resumes or the transport goes away. */
    public record StopReport(BreakpointTracker.LastBreakpoint stop, long threadId, String threadName,
                             String locationSuffix, String unblockedNote) {
    }

    /**
     * Installs a step request; called under the session monitor by {@link #step}.
     */
    @FunctionalInterface
    public interface StepInstaller {
        /**
         * Deletes the thread's previous step request and creates, configures and enables a new one.
         */
        StepRequest install() throws Exception;
    }

    /**
     * The set the current thread is processing (dispatcher, or the listener for a suppressed set).
     */
    private static final ThreadLocal<Processing> PROCESSING = new ThreadLocal<>();

    private record Processing(SuspensionSession session, Context context) {
    }

    /**
     * The thread an {@link InvocationRunner} job runs for, and the holds it was admitted under — on
     * the job's pool thread, for {@link #lease}.
     */
    private static final ThreadLocal<Admitted> ADMITTED = new ThreadLocal<>();

    private record Admitted(ThreadReference thread, Set<Long> holds) {
    }

    /**
     * An invocation chain on {@code thread}: the context whose suspension it borrows, the contexts it
     * took the thread out of, whether it took the thread's manual hold out too, and the shares of
     * released contexts it holds on to until it ends.
     */
    private static final class Lease {
        private final ThreadReference thread;
        private final Context anchor;
        private final List<Context> exemptFrom = new ArrayList<>();
        private boolean manualExempt;
        /**
         * Suspensions of {@link #thread} that released contexts handed over to this lease (see
         * {@link #release}); resumed when the lease ends.
         */
        private int debt;
        /**
         * Taken by the dispatcher for the set it processes: only such an invocation can be stuck
         * where no tool reaches it, so only it is watched (A1).
         */
        private final boolean dispatcher;

        private Lease(ThreadReference thread, Context anchor, boolean dispatcher) {
            this.thread = thread;
            this.anchor = anchor;
            this.dispatcher = dispatcher;
        }
    }

    /**
     * A dispatcher invocation in flight, for {@link SuspensionWatchdog}: its {@code token} names this
     * very invocation chain — a later one on the same thread has another.
     */
    public record Invocation(Object token, ThreadReference thread) {
    }

    /**
     * A failed attempt to free the monitor owner, with the original lease and monitor needed for
     * revalidating a retry.
     */
    private record WatchdogReleaseFailure(Lease lease, ObjectReference monitor, RuntimeException cause) {
    }

    /**
     * The lease's remaining suspension counts after a failed repayment. The hold ID stays stable
     * until the last count is released.
     */
    private record LeaseDebt(long id, int count, RuntimeException cause) {
    }

    private final BreakpointTracker tracker;
    private final EventHistory history;
    private final List<Context> contexts = new ArrayList<>();
    private final Set<ThreadReference> manual = new LinkedHashSet<>();
    private final Map<ThreadReference, Lease> leases = new LinkedHashMap<>();
    /** Lease suspensions still owned by the session after their invocation ended. */
    private final Map<ThreadReference, LeaseDebt> unpaidLeaseDebts = new LinkedHashMap<>();
    /**
     * Threads a frame tool works on, with how many tools: the watchdog never releases them (A2).
     */
    private final Map<ThreadReference, Integer> reserved = new LinkedHashMap<>();
    /**
     * How often a routed stop-all took a leased thread out of it, for diagnostics.
     */
    private long routeExemptions;
    private final Map<ThreadReference, ArmedStep> pendingSteps = new LinkedHashMap<>();
    /**
     * How many targeted waits ({@link #nextStop(long, ThreadReference)}, {@link #awaitStepStop}) are
     * parked per thread, so a publication can wake them and their blocker checks run again.
     */
    private final Map<ThreadReference, Integer> targetedWaiters = new LinkedHashMap<>();
    private long nextId = 1;
    private long generation = 1;
    private boolean closed;
    /**
     * Whether a VMStart set has finished (published or, with policy NONE, released).
     */
    private boolean vmStartSeen;
    @Nullable
    private Context current;

    /** The JDI connection's identity, without retaining the VM just to compare identities. */
    private final WeakReference<VirtualMachine> vm;
    /** The history epoch assigned to this connection before the listener started. */
    private final int epoch;

    /** Creates suspension ownership for a single JDI connection. */
    SuspensionSession(VirtualMachine vm, BreakpointTracker tracker, EventHistory history) {
        this.vm = new WeakReference<>(vm);
        this.tracker = tracker;
        this.history = history;
        this.epoch = history.currentEpoch();
    }

    /** Whether the supplied VM is the connection this session was opened for. */
    boolean matchesVm(VirtualMachine requestedVm) {
        return vm.get() == requestedVm;
    }

    /** Identifies this connection in event history and exit snapshots. */
    int epoch() {
        return epoch;
    }

    // ── Listener and dispatcher ──

    /**
     * Marks the calling thread as processing {@code context} until {@link #endProcessing}: the
     * handlers' snapshots become stop candidates of the set and their history entries are buffered.
     */
    public void beginProcessing(Context context) {
        PROCESSING.set(new Processing(this, context));
    }

    /**
     * Ends {@link #beginProcessing}.
     */
    public static void endProcessing() {
        PROCESSING.remove();
    }

    /**
     * Whether the calling thread processes a set under a session.
     */
    public static boolean isProcessing() {
        return PROCESSING.get() != null;
    }

    /**
     * Adds {@code stop} as a candidate of the set the calling thread processes.
     *
     * @return {@code false} when the thread processes no set
     */
    static boolean candidate(BreakpointTracker.LastBreakpoint stop) {
        final Processing processing = PROCESSING.get();
        if (processing == null) {
            return false;
        }
        processing.session().candidate(processing.context(), stop);
        return true;
    }

    /**
     * Buffers {@code event} for the set the calling thread processes.
     *
     * @return {@code false} when the thread processes no set
     */
    static boolean buffer(EventHistory.DebugEvent event) {
        final Processing processing = PROCESSING.get();
        if (processing == null) {
            return false;
        }
        processing.session().record(processing.context(), event);
        return true;
    }

    /**
     * How many candidates {@code context} has; with {@link #dropCandidatesAfter} a handler that
     * decides not to stop withdraws the candidates it added.
     */
    public synchronized int candidateCount(Context context) {
        return context.candidates.size();
    }

    /**
     * Withdraws the candidates of {@code context} beyond the first {@code count}.
     */
    public synchronized void dropCandidatesAfter(Context context, int count) {
        while (context.candidates.size() > count) {
            context.candidates.remove(context.candidates.size() - 1);
        }
    }

    /**
     * Registers a routed set as a QUEUED context.
     *
     * @param thread  the set's event thread, if it has one
     * @param vmStart whether the set reports VM start
     */
    public synchronized Context route(EventSet eventSet, @Nullable ThreadReference thread, boolean vmStart) {
        return route(eventSet, thread, vmStart, true);
    }

    /**
     * {@link #route(EventSet, ThreadReference, boolean)} for a set judged beforehand.
     * A newly routed set wakes targeted waiters whose threads it covers.
     *
     * @param mayStop whether a handler of the set may publish a stop; {@code false} for one that never
     *                does (logpoints, say), whose threads the user never sees stopped
     */
    public synchronized Context route(EventSet eventSet, @Nullable ThreadReference thread, boolean vmStart,
                                      boolean mayStop) {
        boolean stale = false;
        for (Event event : eventSet) {
            stale |= isStale(event.request());
        }
        final Context context = new Context(nextId++, generation, eventSet, eventSet.suspendPolicy(), thread, vmStart,
            stale, mayStop);
        if (!closed) {
            contexts.add(context);
            if (context.policy == EventRequest.SUSPEND_ALL) {
                // The stop-all also suspended every thread in the middle of an invocation; take them
                // out of it, or the invocation never completes and its caller never processes this set.
                for (Lease lease : leases.values()) {
                    if (!lease.thread.equals(thread)) {
                        exempt(context, lease);
                        routeExemptions++;
                    }
                }
            }
            if (targetedWaiters.keySet().stream().anyMatch(waiter -> covers(context, waiter))) {
                notifyAll();
            }
        }
        return context;
    }

    /**
     * The dispatcher starts processing {@code context}.
     *
     * @return {@code false} for a set the watchdog cancelled: its thread runs, so no handler may use
     *     it; skip the handlers that need it and {@link #finish} the set
     */
    public synchronized boolean begin(Context context) {
        if (context.cancelled) {
            return false;
        }
        if (!closed && context.state == State.QUEUED) {
            context.state = State.PROCESSING;
        }
        return true;
    }

    /**
     * A handler of {@code context} wants a stop. The first candidate is the stop's report; a set is
     * published at most once, in {@link #finish}.
     */
    public synchronized void candidate(Context context, BreakpointTracker.LastBreakpoint stop) {
        context.candidates.add(stop);
    }

    /**
     * A handler of {@code context} records a history entry; it is appended when the set finishes,
     * unless the session closed or was reset meanwhile.
     */
    public synchronized void record(Context context, EventHistory.DebugEvent event) {
        context.history.add(event);
    }

    /**
     * Ends processing: publishes the first candidate as a stop, or releases the set. A set of an
     * earlier generation (reset meanwhile, or a request created before the reset), a set that
     * suspends nothing, and a set routed as one that never stops never publish — the last not even
     * a handler's fail-safe stop: the redefinition rule did not count its thread, so the method it
     * stopped in may have been redefined meanwhile, its frame obsolete.
     * A failed automatic release keeps the context for retry and wakes next-stop waiters.
     *
     * @return whether a stop was published
     */
    public synchronized boolean finish(Context context) {
        if (closed || (context.state != State.PROCESSING && context.state != State.QUEUED)) {
            return false;
        }
        vmStartSeen |= context.vmStart;
        final boolean current = context.generation == generation && !context.staleAtRouting;
        if (current) {
            for (EventHistory.DebugEvent event : context.history) {
                history.record(event);
            }
        }
        context.history.clear();
        if (current && !context.cancelled && !context.candidates.isEmpty()
            && context.policy != EventRequest.SUSPEND_NONE) {
            if (context.mayStop) {
                publish(context);
                return true;
            }
            log.warn("[Suspension] {} was routed as never stopping; its handler's fail-safe stop is dropped", context);
        }
        try {
            release(context);
        } catch (RuntimeException e) {
            context.state = State.RELEASE_FAILED;
            context.automaticReleaseFailure = e;
            if (e instanceof VMDisconnectedException) {
                close();
            }
            notifyAll();
            log.warn("[Suspension] Releasing {} failed: {}", context, brief(e));
        }
        if (context.vmStart) {
            notifyAll();
        }
        return false;
    }

    private void publish(Context context) {
        final BreakpointTracker.LastBreakpoint report = context.candidates.get(0);
        context.report = new StopReport(report, report.thread().uniqueID(), safeName(report.thread()),
            stopLocation(context, report), unblockedNote(context));
        context.state = State.HELD;
        tracker.published(report);
        final ThreadReference thread = context.thread;
        if (thread != null) {
            final ArmedStep pending = pendingSteps.remove(thread);
            if (pending != null) {
                pending.endingStop = context;
                if (report.kind() != BreakpointTracker.EventKind.STEP) {
                    // Another stop on the stepping thread ends the step, as in an IDE.
                    deleteQuietly(pending.request);
                    final String name = context.report.threadName();
                    history.record(new EventHistory.DebugEvent("STEP_INTERRUPTED",
                        String.format("The step on thread %s was interrupted by a stop", name), Map.of("thread", name)));
                }
            }
        }
        notifyAll();
    }

    // ── Presentation ──

    /**
     * The stop the getters show: the current stop while it is still held on its event thread,
     * otherwise the oldest stop no tool has presented yet (which becomes current). {@code null}
     * when nothing is held.
     */
    public synchronized BreakpointTracker.@Nullable LastBreakpoint presentCurrent() {
        final StopReport report = presentCurrentReport();
        return report == null ? null : report.stop();
    }

    /** Presents the current stop with the immutable metadata captured when it was published. */
    synchronized @Nullable StopReport presentCurrentReport() {
        if (closed) {
            return null;
        }
        final Context live = current;
        if (live != null && isLive(live)) {
            return live.report;
        }
        current = null;
        final Context unseen = oldestUnseen(null);
        return unseen != null ? present(unseen) : null;
    }

    /**
     * Whether this exact report still owns a hold on its thread and its frames are available
     * for inspection. Another stop on the same thread cannot satisfy this check.
     */
    synchronized boolean holdsStop(StopReport report) {
        final ThreadReference thread = report.stop().thread();
        if (closed || leases.containsKey(thread)) {
            return false;
        }
        for (Context context : contexts) {
            if (context.report == report) {
                return context.state == State.HELD && covers(context, thread);
            }
        }
        return false;
    }

    /**
     * Selects a matching published report for a terminal reply when live thread lookup is unavailable.
     * This can read a closed session and neither releases a hold nor waits for a new event.
     */
    synchronized @Nullable StopReport presentPublishedStop(long threadId) {
        final Context unseen = oldestUnseen(threadId);
        if (unseen != null) {
            return present(unseen);
        }
        for (Context context : contexts) {
            final StopReport report = context.report;
            if (report != null && report.threadId() == threadId && context.state == State.HELD) {
                return report;
            }
        }
        return null;
    }

    /**
     * The current stop if it is still held on its event thread, without presenting anything: what
     * tools that default their thread act on. {@code null} when there is none.
     */
    public synchronized BreakpointTracker.@Nullable LastBreakpoint currentStop() {
        final Context live = current;
        return !closed && live != null && isLive(live) ? live.snapshot() : null;
    }

    /** Resolves session-owned threads, including debtors and virtual threads JDI may omit from enumeration. */
    synchronized @Nullable ThreadReference knownThread(long id) {
        final VirtualMachine targetVm = vm.get();
        if (closed || targetVm == null) {
            return null;
        }
        for (Context context : contexts) {
            final StopReport report = context.report;
            if (report != null && report.threadId() == id && report.stop().thread().virtualMachine() == targetVm) {
                return report.stop().thread();
            }
            final ThreadReference thread = context.thread;
            if (thread != null && thread.uniqueID() == id && thread.virtualMachine() == targetVm) {
                return thread;
            }
            for (ThreadReference owner : context.watchdogFailures.keySet()) {
                if (owner.uniqueID() == id && owner.virtualMachine() == targetVm) {
                    return owner;
                }
            }
        }
        for (ThreadReference thread : pendingSteps.keySet()) {
            if (thread.uniqueID() == id && thread.virtualMachine() == targetVm) {
                return thread;
            }
        }
        for (ThreadReference thread : manual) {
            if (thread.uniqueID() == id && thread.virtualMachine() == targetVm) {
                return thread;
            }
        }
        for (ThreadReference thread : unpaidLeaseDebts.keySet()) {
            if (thread.uniqueID() == id && thread.virtualMachine() == targetVm) {
                return thread;
            }
        }
        return null;
    }

    /**
     * How many published stops no tool has presented yet.
     */
    public synchronized int unseenCount() {
        return unseenCountExcept(null);
    }

    /**
     * Unseen stops whose event thread is not {@code threadId} (all unseen stops when null).
     */
    private int unseenCountExcept(@Nullable Long threadId) {
        int count = 0;
        for (Context context : contexts) {
            if (context.state == State.HELD && !context.reported
                && (threadId == null || Objects.requireNonNull(context.report).threadId() != threadId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * {@code jdwp_resume_until_event}: presents the oldest unseen stop at once if there is one;
     * otherwise releases the presented stops (unless a step is in flight, whose landing it waits
     * for without releasing anything) and waits for the next stop. Every wake re-selects; a
     * notification never implies that a particular stop exists. Outstanding recoveries are reported
     * even while waiting for a step.
     */
    public synchronized NextStop nextStop(long timeoutMs) throws InterruptedException {
        return nextStop(timeoutMs, null);
    }

    /**
     * Waits for the oldest unseen stop of {@code thread} (any thread when null). A targeted
     * release uses the same blocker checks as {@link #releaseThread}. If there are no holds
     * to release or the thread has a pending step, the call just waits.
     */
    public synchronized NextStop nextStop(long timeoutMs, @Nullable ThreadReference thread) throws InterruptedException {
        return waitForStop(timeoutMs, thread, generation, null);
    }

    /**
     * Waits for this step's own outcome. The retained ending context may have been presented
     * and released by the time this call runs.
     */
    synchronized NextStop awaitStepStop(long timeoutMs, ArmedStep step) throws InterruptedException {
        return waitForStop(timeoutMs, step.thread, step.generation, step);
    }

    /**
     * The wait behind {@link #nextStop} and {@link #awaitStepStop}, under the session monitor.
     * A reset invalidates a step's retained outcome, even when already presented.
     * Recovery holds end a targeted wait only when they cover its thread.
     *
     * @param thread          the thread whose stop is awaited, or {@code null} for any thread
     * @param startGeneration the generation the wait belongs to; a later one ends it with {@link Outcome#RESET}
     * @param step            a step whose ending stop to report, with no release pass
     */
    private NextStop waitForStop(long timeoutMs, @Nullable ThreadReference thread, long startGeneration,
                                 @Nullable ArmedStep step) throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        final Long threadId = thread == null ? null : thread.uniqueID();
        boolean releasePending = step == null;
        ReleaseResult released = null;
        ThreadRelease threadRelease = null;
        List<ReleaseFailure> recoveryFailures = List.of();
        StopReport report = null;
        String refusal = null;
        final Outcome outcome;
        while (true) {
            if (step != null && generation != startGeneration) {
                outcome = Outcome.RESET;
                break;
            }
            Context selected = null;
            if (step != null) {
                selected = step.endingStop;
            } else if (threadId == null || generation == startGeneration) {
                selected = oldestUnseen(threadId);
            }
            if (selected != null && step != null && selected.reported) {
                report = Objects.requireNonNull(selected.report);
                outcome = Outcome.ALREADY_PRESENTED;
                break;
            }
            if (selected != null && selected.state == State.HELD) {
                // Keep the selected report even if the session closed before we could present it.
                report = present(selected);
                outcome = closed ? Outcome.CLOSED : Outcome.STOPPED;
                break;
            }
            if (closed) {
                outcome = Outcome.CLOSED;
                break;
            }
            if (generation != startGeneration) {
                outcome = Outcome.RESET;
                break;
            }
            if (step != null && pendingSteps.get(step.thread) != step) {
                outcome = Outcome.STEP_ENDED;
                break;
            }
            if (thread != null) {
                recoveryFailures = waitingRecoveryFailures(thread);
                if (!recoveryFailures.isEmpty()) {
                    outcome = Outcome.RESUME_FAILED;
                    break;
                }
                refusal = releaseRefusal(thread, step != null);
                if (refusal != null) {
                    outcome = Outcome.REFUSED;
                    break;
                }
            }
            if (releasePending) {
                releasePending = false;
                if (thread == null) {
                    if (pendingSteps.isEmpty()) {
                        released = releaseAllLocked();
                    }
                } else if (!pendingSteps.containsKey(thread)) {
                    threadRelease = releaseHolders(thread, new ArrayList<>(), new ArrayList<>());
                }
            }
            if (closed) {
                outcome = Outcome.CLOSED;
                break;
            }
            if ((released != null && !released.failures().isEmpty())
                || (threadRelease != null && !threadRelease.failures().isEmpty())) {
                outcome = Outcome.RESUME_FAILED;
                break;
            }
            if (thread == null) {
                recoveryFailures = waitingRecoveryFailures(null);
                if (!recoveryFailures.isEmpty()) {
                    outcome = Outcome.RESUME_FAILED;
                    break;
                }
            }
            final long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                outcome = Outcome.TIMEOUT;
                break;
            }
            waitForChange(remainingMs, thread);
        }
        return endWait(outcome, report, released, threadRelease, refusal, threadId, recoveryFailures);
    }

    /**
     * Parks on the session monitor until routing, publication, reset, close or timeout. A targeted wait is
     * counted in {@link #targetedWaiters} while it is parked.
     */
    private void waitForChange(long remainingMs, @Nullable ThreadReference thread) throws InterruptedException {
        if (thread == null) {
            wait(remainingMs);
            return;
        }
        targetedWaiters.merge(thread, 1, Integer::sum);
        try {
            wait(remainingMs);
        } finally {
            targetedWaiters.computeIfPresent(thread, (key, waiters) -> waiters == 1 ? null : waiters - 1);
        }
    }

    // ── Releases ──

    /**
     * {@code jdwp_resume}: releases every presented stop and every manual hold. Stops not yet
     * presented stay held. Retries failed automatic releases, lease debts, and eligible failed
     * watchdog releases.
     */
    public synchronized ReleaseResult releaseAll() {
        return closed ? new ReleaseResult(List.of(), List.of(), 0, List.of(), List.of(), List.of(), true)
            : releaseAllLocked();
    }

    /** Releases eligible holds in context order and records the confirmed releases and failures. */
    private ReleaseResult releaseAllLocked() {
        final List<BreakpointTracker.LastBreakpoint> releasedStops = new ArrayList<>();
        final List<BreakpointTracker.LastBreakpoint> unseen = new ArrayList<>();
        final List<String> recovered = new ArrayList<>();
        final List<ReleaseFailure> failures = new ArrayList<>();
        int pending = 0;
        for (Context context : new ArrayList<>(contexts)) {
            if (closed) {
                break;
            }
            switch (context.state) {
                case HELD -> {
                    final BreakpointTracker.LastBreakpoint stop = Objects.requireNonNull(context.report).stop();
                    if (context.reported) {
                        if (attemptRelease(() -> release(context), () -> describeStop(stop), failures)) {
                            releasedStops.add(stop);
                        }
                    } else {
                        unseen.add(stop);
                    }
                }
                case RELEASE_FAILED -> retryAutomaticRelease(context, recovered, failures);
                case QUEUED, PROCESSING -> {
                    retryWatchdogReleases(context, null, recovered, failures);
                    if (context.state != State.RELEASED) {
                        pending++;
                    }
                }
                case RELEASED -> {
                }
            }
        }
        final List<ThreadReference> manualReleased = releaseManualHolds(failures);
        retryLeaseDebts(recovered, failures);
        return new ReleaseResult(releasedStops, unseen, pending, manualReleased, recovered, failures, closed);
    }

    /**
     * {@code jdwp_resume_thread}: releases what the user holds on {@code thread} — its manual hold,
     * its own presented stop, and its share of presented stop-all stops. Refuses while a set
     * covering the thread is still queued or processed, or while it holds a stop nobody has seen.
     * Retries failed automatic releases on their event thread, eligible watchdog releases and
     * unpaid lease debt before checking for ordinary release blockers.
     */
    public synchronized ThreadRelease releaseThread(ThreadReference thread) {
        if (closed) {
            return new ThreadRelease("the debugging session is closed", List.of(), List.of(), true);
        }
        final List<String> holders = new ArrayList<>();
        final List<ReleaseFailure> failures = new ArrayList<>();
        for (Context context : List.copyOf(contexts)) {
            if (closed) {
                break;
            }
            if (context.state == State.RELEASE_FAILED && thread.equals(context.thread)) {
                retryAutomaticRelease(context, holders, failures);
            } else {
                retryWatchdogReleases(context, thread, holders, failures);
            }
        }
        retryLeaseDebt(thread, holders, failures);
        if (closed) {
            return new ThreadRelease(null, holders, failures, true);
        }
        final String refusal = releaseRefusal(thread);
        if (refusal != null) {
            return new ThreadRelease(refusal, holders, failures, false);
        }
        final ThreadRelease result = releaseHolders(thread, holders, failures);
        return result.released() || !result.failures().isEmpty() ? result
            : new ThreadRelease("nothing holds thread " + safeName(thread) + " — it was not suspended by this "
                + "debugging session's stops or jdwp_suspend_thread", List.of(), List.of(), false);
    }

    /**
     * {@code jdwp_suspend_thread}: a manual hold. Holds do not stack.
     *
     * @return {@code null} when suspended, otherwise why not
     */
    @Nullable
    public synchronized String suspendThread(ThreadReference thread) {
        if (closed) {
            return "the debugging session is closed";
        }
        if (thread.isSuspended()) {
            return "thread " + safeName(thread) + " is already suspended";
        }
        thread.suspend();
        manual.add(thread);
        return null;
    }

    /**
     * One armed request and the context that ended it. The caller keeps this handle after the
     * pending entry is removed. Its ending stop is written and read under the session monitor.
     */
    public static final class ArmedStep {
        private final ThreadReference thread;
        private final StepRequest request;
        private final long generation;
        private @Nullable Context endingStop;

        private ArmedStep(ThreadReference thread, StepRequest request, long generation) {
            this.thread = thread;
            this.request = request;
            this.generation = generation;
        }
    }

    /**
     * Outcome of {@link #step}.
     *
     * @param refusal           why the step was not started, or {@code null} when it was
     * @param remainingHolders  the other thread-specific holds, grouped by holder kind
     * @param remainingStopAlls summaries of stop-alls still in effect, grouped and counted
     *                          even if JDI lists no other threads
     * @param recoveryPending   whether any recovery hold remains after starting the step
     * @param releaseFailure    the full release outcome when the step request was removed after a failure
     * @param armed             the handle for waiting for an armed step, or {@code null} when refused
     */
    public record StepResult(@Nullable String refusal, List<String> remainingHolders, List<String> remainingStopAlls,
                             boolean recoveryPending, @Nullable ThreadRelease releaseFailure, @Nullable ArmedStep armed) {
        /** Refuses a step before any release is attempted. */
        private static StepResult refused(String reason) {
            return new StepResult(reason, List.of(), List.of(), false, null, null);
        }

        /** Refuses a step and preserves everything its release pass confirmed. */
        private static StepResult refused(ThreadRelease release) {
            return new StepResult("the thread's holds could not all be released", List.of(), List.of(), false, release, null);
        }
    }

    /**
     * A step on {@code thread}, atomically: admission, installing the request, then releasing the
     * thread. A refused or failed step leaves no request behind. A failed lease repayment must be
     * retried before stepping. A refused release includes all its successes and failures.
     * Thread enumeration illustrates surviving stop-alls when available.
     *
     * @return the started step's remaining holds and handle, or a refusal saying why it did not start
     */
    public synchronized StepResult step(ThreadReference thread, Supplier<List<ThreadReference>> allThreads,
                                        StepInstaller installer) throws Exception {
        if (closed) {
            return StepResult.refused("the debugging session is closed");
        }
        final String refusal = releaseRefusal(thread);
        if (refusal != null) {
            return StepResult.refused(refusal);
        }
        if (unpaidLeaseDebts.containsKey(thread)) {
            return StepResult.refused("thread " + safeName(thread) + " has unpaid lease debt — retry with jdwp_resume_thread");
        }
        if (!holds(thread)) {
            return StepResult.refused("nothing holds thread " + safeName(thread)
                + " — step from a stop or a jdwp_suspend_thread hold");
        }
        final List<ThreadReference> vmThreads = stopAllThreadSample(allThreads);
        final StepRequest request = installer.install();
        stamp(request);
        final ArmedStep armed = new ArmedStep(thread, request, generation);
        try {
            if (pendingSteps.put(thread, armed) != null) {
                notifyAll();
            }
            final ThreadRelease release = releaseHolders(thread, new ArrayList<>(), new ArrayList<>());
            if (release.closed() || !release.failures().isEmpty()) {
                pendingSteps.remove(thread);
                deleteQuietly(request);
                return StepResult.refused(release);
            }
        } catch (RuntimeException e) {
            pendingSteps.remove(thread);
            deleteQuietly(request);
            throw e;
        }
        return describeRemainingHolders(vmThreads, armed);
    }

    /** Samples the threads a stop-all holds, without making that diagnostic a step prerequisite. */
    private List<ThreadReference> stopAllThreadSample(Supplier<List<ThreadReference>> allThreads) {
        if (contexts.stream().noneMatch(context -> context.policy == EventRequest.SUSPEND_ALL)) {
            return List.of();
        }
        try {
            return allThreads.get();
        } catch (RuntimeException e) {
            log.debug("[Suspension] Could not list threads for the step's stop-all summary", e);
            return List.of();
        }
    }

    /**
     * Summarizes the remaining holds under the session monitor. Equivalent stop-alls share their
     * state and origin. The displayed sample covers the union of JDI-enumerated threads they hold.
     * Recovery holds share the name budget with the ordinary hold groups.
     *
     * @param vmThreads threads enumerated by JDI, which can omit virtual threads
     * @param armed     the step that was just armed
     * @return the grouped stop-all and thread-specific holds, with one display budget,
     *         and the armed step
     */
    private StepResult describeRemainingHolders(List<ThreadReference> vmThreads, ArmedStep armed) {
        final Map<String, Set<ThreadReference>> groups = new LinkedHashMap<>();
        final Map<StopAllGroup, StopAllSummary> stopAlls = new LinkedHashMap<>();
        boolean recoveryPending = !unpaidLeaseDebts.isEmpty();
        for (Context context : contexts) {
            final ThreadReference eventThread = context.thread;
            recoveryPending |= context.state == State.RELEASE_FAILED;
            if (context.policy == EventRequest.SUSPEND_ALL) {
                final String state = switch (context.state) {
                    case HELD -> seenWord(context);
                    case RELEASE_FAILED -> "failed automatic release";
                    default -> "in-progress";
                };
                stopAlls.computeIfAbsent(new StopAllGroup(state, eventThread), ignored -> new StopAllSummary())
                    .add(context, vmThreads);
            } else if (context.policy == EventRequest.SUSPEND_EVENT_THREAD
                && eventThread != null && covers(context, eventThread)) {
                final String label = switch (context.state) {
                    case HELD -> seenWord(context) + " thread stops";
                    case RELEASE_FAILED -> "failed automatic releases";
                    default -> "events still being processed";
                };
                groups.computeIfAbsent(label, ignored -> new LinkedHashSet<>()).add(eventThread);
            }
        }
        groups.put("jdwp_suspend_thread holds", manual);
        groups.put("lease debt", unpaidLeaseDebts.keySet());
        final StepThreadNames names = new StepThreadNames();
        final List<String> stopAllDescriptions = describeStopAlls(stopAlls, names);
        final List<String> threadHolds = groups.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> entry.getKey() + ": " + names.describeGroup(entry.getValue()))
            .toList();
        return new StepResult(null, threadHolds, stopAllDescriptions, recoveryPending, null, armed);
    }

    /** Combines stop-alls with the same presentation state and originating thread. */
    private record StopAllGroup(String state, @Nullable ThreadReference origin) {}

    /** Counts the contexts in one group and keeps the distinct threads JDI lists for that group. */
    private static final class StopAllSummary {
        /** Number of stop-all contexts in the group. */
        private int count;
        /** Enumerated threads held by at least one context in this group. */
        private final Set<ThreadReference> listed = new LinkedHashSet<>();

        /** Adds one context and its share of the enumerated threads. */
        private void add(Context context, List<ThreadReference> vmThreads) {
            count++;
            for (ThreadReference thread : vmThreads) {
                if (covers(context, thread)) {
                    listed.add(thread);
                }
            }
        }
    }

    /** Reports named stop-all groups and combines origins beyond the name budget by state. */
    private static List<String> describeStopAlls(Map<StopAllGroup, StopAllSummary> stopAlls, StepThreadNames names) {
        final List<String> descriptions = new ArrayList<>();
        final Map<String, Integer> additional = new LinkedHashMap<>();
        for (Map.Entry<StopAllGroup, StopAllSummary> entry : stopAlls.entrySet()) {
            final StopAllGroup key = entry.getKey();
            final StopAllSummary group = entry.getValue();
            final ThreadReference thread = key.origin();
            if (thread != null && names.exhausted()) {
                additional.merge(key.state(), group.count, Integer::sum);
                continue;
            }
            final String origin = thread == null ? "" : " from thread " + names.take(thread);
            final String sample = group.listed.isEmpty() ? "" : " (JDI lists " + names.describeGroup(group.listed) + ')';
            if (group.count == 1) {
                descriptions.add("The " + stopAllLabel(key.state(), 1) + origin + " still covers other threads" + sample + '.');
            } else {
                descriptions.add(group.count + " " + stopAllLabel(key.state(), group.count) + origin
                    + " still cover other threads" + sample + '.');
            }
        }
        additional.forEach((state, count) -> {
            if (count == 1) {
                descriptions.add("One additional " + stopAllLabel(state, count) + " still covers other threads.");
            } else {
                descriptions.add(count + " additional " + stopAllLabel(state, count) + " still cover other threads.");
            }
        });
        return List.copyOf(descriptions);
    }

    /** Names an ordinary or failed stop-all without adding another thread to the sample. */
    private static String stopAllLabel(String state, int count) {
        final String noun = count == 1 ? "stop-all" : "stop-alls";
        if ("failed automatic release".equals(state)) {
            return noun + " with a failed automatic release";
        }
        return state + " " + noun;
    }

    /**
     * Whether a held context's stop has been presented by a tool, in the words the step reply uses.
     */
    private static String seenWord(Context context) {
        return context.reported ? "presented" : "unseen";
    }

    /** Tracks the shared name budget for the entire step reply. */
    private static final class StepThreadNames {
        /** The reply header already spends one of the five names on the stepping thread. */
        private int remaining = 4;

        /** Whether subsequent groups must be described by count alone. */
        private boolean exhausted() {
            return remaining == 0;
        }

        /** Spends one slot on a known thread when room remains. */
        private String take(ThreadReference thread) {
            remaining--;
            return safeName(thread);
        }

        /** Shows a sample of the group, or just its size when the name budget is exhausted. */
        private String describeGroup(Set<ThreadReference> threads) {
            if (exhausted()) {
                return threads.size() == 1 ? "1 thread" : threads.size() + " threads";
            }
            final int shown = Math.min(remaining, threads.size());
            final String names = String.join(", ", threads.stream().limit(shown).map(this::take).toList());
            return threads.size() > shown ? names + " and " + (threads.size() - shown) + " more" : names;
        }
    }

    /**
     * Whether a step is in flight on {@code thread}.
     */
    public synchronized boolean stepPending(ThreadReference thread) {
        return pendingSteps.containsKey(thread);
    }

    /**
     * The holds this session knows on {@code thread}: the ids of its contexts and unpaid lease debt,
     * and {@link #MANUAL_HOLD} for a manual hold. A lease's exemption still counts — its invocation
     * returns to the same frames. While one hold lasts, the thread cannot run past its stop, so a
     * hold present in two snapshots proves its frames belong to the same activations in between
     * (an {@link InvocationRunner} job's admission). Context ids are never reused.
     */
    public synchronized Set<Long> holdsOn(ThreadReference thread) {
        return holdsOn(thread, false);
    }

    /**
     * {@link #holdsOn}, limited to the holds only a tool ends: the published stops holding
     * {@code thread}, a manual hold or unpaid lease debt. Nothing else releases those — the dispatcher releases only
     * the sets it processes, the watchdog never touches a published stop or a manual hold, and tools
     * run one at a time — so one of them found by a tool still holds the thread when the tool next
     * reads its frames. A set still queued or processed does not vouch for that: the dispatcher may
     * release it at any moment, and the thread run on (a frame tool's retry, #47 § 4).
     */
    public synchronized Set<Long> userHoldsOn(ThreadReference thread) {
        return holdsOn(thread, true);
    }

    /** Collects stable hold IDs, optionally excluding contexts that a tool did not present. */
    private Set<Long> holdsOn(ThreadReference thread, boolean publishedOnly) {
        if (closed) {
            return Set.of();
        }
        final Set<Long> holds = new LinkedHashSet<>();
        for (Context context : contexts) {
            if ((!publishedOnly || context.state == State.HELD) && claims(context, thread)) {
                holds.add(context.id);
            }
        }
        if (manual.contains(thread)) {
            holds.add(MANUAL_HOLD);
        }
        final LeaseDebt debt = unpaidLeaseDebts.get(thread);
        if (debt != null) {
            holds.add(debt.id());
        }
        return holds;
    }

    /**
     * The threads the user may see stopped, with what holds each — for the redefinition rule (#42
     * proposal § 6): a method a held thread has a frame in is never redefined, or the frame would
     * turn obsolete and lose its locals. A hold is a context that is shown or may still be (HELD, or
     * QUEUED or PROCESSING in the current generation, not cancelled, and able to stop), a manual hold
     * ({@link #MANUAL_HOLD}), unpaid lease debt, a step in flight ({@link #STEP_HOLD}), or a suspension nothing here
     * accounts for ({@link #UNKNOWN_HOLD}: a set raised but not routed yet). As in {@link #holdsOn},
     * a lease's exemption still counts. The set the calling thread processes counts only with
     * {@code countProcessing} — an arming hit's own set, which resumes without being shown unless
     * it has a stop.
     *
     * @param threads        the target's threads; every thread this session knows is added
     * @param callerSuspends how often the caller itself suspended every thread ({@code vm.suspend()}),
     *                       which the check for unknown suspensions discounts
     */
    public synchronized Map<ThreadReference, Set<Long>> heldThreads(Collection<ThreadReference> threads,
                                                                     boolean countProcessing, int callerSuspends) {
        if (closed) {
            return Map.of();
        }
        final Processing processing = PROCESSING.get();
        final Context own = processing != null && processing.session() == this && !countProcessing
            ? processing.context() : null;
        final Set<ThreadReference> candidates = new LinkedHashSet<>(threads);
        for (Context context : contexts) {
            if (context.thread != null) {
                candidates.add(context.thread);
            }
        }
        candidates.addAll(manual);
        candidates.addAll(unpaidLeaseDebts.keySet());
        candidates.addAll(leases.keySet());
        candidates.addAll(pendingSteps.keySet());
        final Map<ThreadReference, Set<Long>> held = new LinkedHashMap<>();
        for (ThreadReference thread : candidates) {
            final Set<Long> holds = new LinkedHashSet<>();
            // Every suspension still on the thread, shown or not: what its count should be.
            int suspensions = callerSuspends;
            for (Context context : contexts) {
                if (covers(context, thread)) {
                    suspensions++;
                }
                if (context != own && claims(context, thread) && mayBeShown(context)) {
                    holds.add(context.id);
                }
            }
            if (manual.contains(thread)) {
                holds.add(MANUAL_HOLD);
                suspensions++;
            }
            final LeaseDebt debt = unpaidLeaseDebts.get(thread);
            if (debt != null) {
                holds.add(debt.id());
                suspensions += debt.count();
            }
            if (pendingSteps.containsKey(thread)) {
                holds.add(STEP_HOLD);
            }
            // A leased thread's count is in flux (its invocation borrows one), so the count tells
            // nothing — its holds are known while its anchor lasts. Once the anchor was released (its
            // share handed to the lease), no known hold explains what may still keep the thread, say
            // a set not routed yet: it counts as unknown until the evaluation ends.
            final Lease lease = leases.get(thread);
            if (lease != null ? lease.debt > 0 : suspendCountOf(thread) > suspensions) {
                holds.add(UNKNOWN_HOLD);
            }
            if (!holds.isEmpty()) {
                held.put(thread, holds);
            }
        }
        return held;
    }

    /**
     * What the attach handshake found.
     */
    public enum StartState {
        /**
         * The VM is suspended at start; the attach reply acknowledges that stop, so it counts as seen.
         */
        SUSPENDED,
        /**
         * The start event does not currently hold the VM.
         */
        RUNNING,
        /**
         * No VMStart within the wait: a re-attach (the JDWP agent reports VMStart only to its first
         * connection), or a late one — which then is a stop not yet seen.
         */
        NOT_SEEN
    }

    /**
     * The attach handshake: waits at most {@code timeoutMs} for the VMStart set and, if it suspended
     * the VM, marks that stop as seen — the attach reply says so.
     */
    public synchronized StartState acknowledgeStart(long timeoutMs) throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!closed) {
            for (Context context : contexts) {
                if (context.vmStart && context.state == State.HELD) {
                    if (!context.reported) {
                        present(context);
                    }
                    return StartState.SUSPENDED;
                }
            }
            if (vmStartSeen) {
                return StartState.RUNNING;
            }
            final long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                break;
            }
            wait(remainingMs);
        }
        return StartState.NOT_SEEN;
    }

    // ── Leases ──

    /**
     * Admits an invocation chain on {@code thread} and takes the thread out of every other context
     * that suspends it, so the count the invocation borrows is the thread's only one (issue #45,
     * proposal §§ 6–7). Pair with {@link #unlease}; {@link EvaluationGuard#lease} does both.
     * <p>
     * The dispatcher may invoke on the event thread of the set it processes. Any other caller needs
     * the thread stopped at an event of its own that a tool has shown, and no set covering it still
     * queued, processed or unseen. A tool's job must also still find one of the holds it was started
     * under ({@link #admittedUnder}). While a frame tool reads the thread ({@link #reserve}), the
     * lease waits for it: the invocation would run the thread under the tool, and the frames the tool
     * reads next would be the invoked code's (#47 § 4).
     *
     * @throws EvaluationRefusedException when the invocation is not admitted, including unpaid lease debt, or a frame tool still
     *                                    reads the thread after {@link #RESERVATION_WAIT_MS}
     */
    public synchronized void lease(ThreadReference thread) {
        awaitUnreserved(thread);
        if (closed) {
            return;
        }
        if (unpaidLeaseDebts.containsKey(thread)) {
            throw new EvaluationRefusedException("thread " + safeName(thread)
                + " has unpaid lease debt — retry with jdwp_resume_thread");
        }
        // A job that outlived its tool's wait runs only at the stop it was started at: once none of
        // its holds is left, the thread may have run on, and a later stop at the same place is
        // another activation.
        final Admitted admitted = ADMITTED.get();
        if (admitted != null && admitted.thread().equals(thread) && !admitted.holds().isEmpty()
            && Collections.disjoint(admitted.holds(), holdsOn(thread))) {
            throw new EvaluationRefusedException("thread " + safeName(thread) + " was released from the stop this "
                + "evaluation started at, and may have run on since — evaluate again at the stop it is at now");
        }
        final Context anchor = leaseAnchor(thread);
        final List<Context> others = new ArrayList<>();
        for (Context context : contexts) {
            if (context.compensated.contains(thread)) {
                throw new EvaluationRefusedException("a failed resume left a temporary suspension on thread "
                    + safeName(thread) + " — retry jdwp_resume");
            }
            if (context != anchor && covers(context, thread)) {
                others.add(context);
            }
        }
        final boolean manualHold = manual.contains(thread);
        // The thread's count must carry every hold this session knows of: one fewer means a hold was
        // undone behind the session's back, and taking the thread out of all of them would let it
        // run. More is a set not routed yet — routing takes the thread out of it.
        final int known = 1 + others.size() + (manualHold ? 1 : 0);
        final int count = suspendCountOf(thread);
        if (count < known) {
            throw new EvaluationRefusedException("thread " + safeName(thread) + " is suspended " + count + " time(s), "
                + "fewer than the " + known + " holds this debugging session knows of — something resumed it "
                + "outside the session; jdwp_reset releases its stops");
        }
        final Processing processing = PROCESSING.get();
        final Lease lease = new Lease(thread, anchor, processing != null && processing.context() == anchor);
        for (Context other : others) {
            exempt(other, lease);
        }
        if (manualHold) {
            resumeQuietly(thread);
            lease.manualExempt = true;
        }
        leases.put(thread, lease);
    }

    /**
     * Runs an {@link InvocationRunner} job's {@code work} for {@code thread}, admitted under
     * {@code holds} ({@link #holdsOn} when the tool started it): every lease of the thread inside is
     * refused once none of those holds is left. Empty {@code holds} check nothing.
     */
    public static <T> T admittedUnder(ThreadReference thread, Set<Long> holds, Callable<T> work) throws Exception {
        final Admitted previous = ADMITTED.get();
        ADMITTED.set(new Admitted(thread, holds));
        try {
            return work.call();
        } finally {
            if (previous != null) {
                ADMITTED.set(previous);
            } else {
                ADMITTED.remove();
            }
        }
    }

    /**
     * Ends the invocation chain on {@code thread}: it goes back into each context it was taken out
     * of that still holds. A context released meanwhile has re-suspended it already. If restoring
     * an exemption fails, an available debt count can fund the hold. The session repays only the
     * remaining debt.
     */
    public synchronized void unlease(ThreadReference thread) {
        final Lease lease = leases.remove(thread);
        if (lease == null || closed) {
            return;
        }
        for (Context context : lease.exemptFrom) {
            if (closed) {
                return;
            }
            if (context.state != State.RELEASED && context.exempt.contains(thread)
                && (context.compensated.remove(thread) || restoreLeaseCount(thread, lease))) {
                context.exempt.remove(thread);
            }
        }
        if (!closed && lease.manualExempt && manual.contains(thread)) {
            restoreLeaseCount(thread, lease);
        }
        // Last: the shares released contexts handed over (the invocation's restore put them back).
        if (!closed && lease.debt > 0) {
            repayLeaseDebt(thread, lease.debt);
        }
    }

    /**
     * Restores one count by suspending the thread or retaining one count of the lease's debt.
     * If neither succeeds, logs the failure and leaves the exemption uncommitted.
     *
     * @return whether a confirmed count backs the hold
     */
    private boolean restoreLeaseCount(ThreadReference thread, Lease lease) {
        try {
            thread.suspend();
            return true;
        } catch (VMDisconnectedException e) {
            close();
        } catch (RuntimeException e) {
            if (lease.debt > 0) {
                lease.debt--;
                return true;
            }
            log.debug("[Suspension] Restoring suspension on {} failed: {}", safeName(thread), brief(e));
        }
        return false;
    }

    // ── Watchdog (A1) and reservations (A2) ──

    /**
     * A frame tool starts working on {@code thread}: the watchdog leaves it alone, and a
     * {@link #lease} of it waits, until {@link #unreserve}. A frame tool never invokes on the thread
     * it reserves.
     */
    public synchronized void reserve(ThreadReference thread) {
        reserved.merge(thread, 1, Integer::sum);
    }

    /**
     * Ends a {@link #reserve}.
     */
    public synchronized void unreserve(ThreadReference thread) {
        reserved.computeIfPresent(thread, (key, tools) -> tools == 1 ? null : tools - 1);
        notifyAll();
    }

    /**
     * Waits, releasing the monitor, until no frame tool reserves {@code thread}. A tool's reads are a
     * few JDWP commands; one still reading after {@link #RESERVATION_WAIT_MS} refuses the lease.
     */
    private void awaitUnreserved(ThreadReference thread) {
        final long deadline = System.nanoTime() + RESERVATION_WAIT_MS * 1_000_000L;
        while (!closed && reserved.containsKey(thread)) {
            final long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                throw new EvaluationRefusedException("a tool is reading the frames of thread " + safeName(thread)
                    + " — evaluate again when it has finished");
            }
            try {
                wait(remainingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EvaluationRefusedException("interrupted while a tool read the frames of thread "
                    + safeName(thread));
            }
        }
    }

    /**
     * The dispatcher invocations in flight, for the watchdog.
     */
    public synchronized List<Invocation> dispatcherInvocations() {
        final List<Invocation> invocations = new ArrayList<>();
        if (!closed) {
            for (Lease lease : leases.values()) {
                if (lease.dispatcher) {
                    invocations.add(new Invocation(lease, lease.thread));
                }
            }
        }
        return invocations;
    }

    /**
     * Releases {@code owner} — the owner of the monitor the dispatcher invocation {@code token} is
     * stuck on — from the sets holding it, when all of them are still queued or being processed:
     * nobody else can release those while the dispatcher is stuck (A1). Everything is re-checked
     * under the monitor: the invocation is still {@code token}'s; the owner is not leased, reserved,
     * manually held, or held by a published stop; its count is exactly those sets' holds, so they
     * are all it waits for — a hold the session does not know (a set not routed yet, a VM-wide
     * suspend in progress) is never released; and, the owner thus proven parked, it still owns
     * {@code monitor} — not a monitor it left before it stopped at a new event. The caller keeps the
     * invoking thread suspended meanwhile, so the invocation still waits for that monitor. A
     * bystander of a stop-all joins its {@code released} and makes the stop partial; the event thread
     * of a queued set cancels it ({@link #cancel}). Each failed resume keeps the original owner and
     * can be retried after the same ownership checks.
     *
     * @return what was released, or {@code null} when nothing was
     */
    @Nullable
    public synchronized String unblock(Object token, ThreadReference owner, ObjectReference monitor) {
        if (!(token instanceof Lease lease)) {
            return null;
        }
        final List<String> from = new ArrayList<>();
        for (Context context : watchdogHolds(lease, owner, monitor)) {
            if (closed) {
                break;
            }
            final String released = attemptWatchdogRelease(context, owner, lease, monitor);
            if (released != null) {
                from.add(released);
            }
        }
        if (from.isEmpty()) {
            return null;
        }
        return recordWatchdogRelease(lease, owner, from);
    }

    /** Checks that the monitor owner has only the verified holds eligible for watchdog release. */
    private List<Context> watchdogHolds(Lease lease, ThreadReference owner, ObjectReference monitor) {
        if (closed || leases.get(lease.thread) != lease || !lease.dispatcher || leases.containsKey(owner)
            || reserved.containsKey(owner) || manual.contains(owner) || unpaidLeaseDebts.containsKey(owner)) {
            return List.of();
        }
        final List<Context> holding = new ArrayList<>();
        for (Context context : contexts) {
            if (!covers(context, owner)) {
                continue;
            }
            if (context.state == State.HELD || context.state == State.RELEASE_FAILED
                || (owner.equals(context.thread) && !cancellable(context))) {
                return List.of();
            }
            holding.add(context);
        }
        if (holding.isEmpty() || suspendCountOf(owner) != holding.size() || !owner.equals(owningThreadOf(monitor))) {
            return List.of();
        }
        return holding;
    }

    /** Attempts one authorized watchdog release and remembers the failure if JDI rejects it. */
    @Nullable
    private String attemptWatchdogRelease(Context context, ThreadReference owner, Lease lease, ObjectReference monitor) {
        try {
            final String released;
            if (owner.equals(context.thread)) {
                released = cancel(context, lease.thread);
            } else {
                final String set = describeSet(context);
                owner.resume();
                context.released.add(owner);
                context.unblocked.add(owner);
                released = set + " (its other threads stay parked)";
            }
            context.watchdogFailures.remove(owner);
            return released;
        } catch (RuntimeException e) {
            context.watchdogFailures.put(owner, new WatchdogReleaseFailure(lease, monitor, e));
            if (e instanceof VMDisconnectedException) {
                close();
            }
            log.debug("[Suspension] Watchdog release of context #{} failed: {}", context.id, brief(e));
            return null;
        }
    }

    /** Records the watchdog releases that actually succeeded. */
    private String recordWatchdogRelease(Lease lease, ThreadReference owner, List<String> from) {
        final String summary = String.format("thread %s, which holds the monitor the evaluation on thread %s waits "
            + "for, from %s", safeName(owner), safeName(lease.thread), String.join("; ", from));
        history.record(new EventHistory.DebugEvent("EVALUATION_UNBLOCKED", "The watchdog released " + summary,
            Map.of("thread", safeName(owner), "evaluatingThread", safeName(lease.thread))));
        return summary;
    }

    @Nullable
    private static ThreadReference owningThreadOf(ObjectReference monitor) {
        try {
            return monitor.owningThread();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether the watchdog may cancel {@code context} to release its event thread: a set still
     * queued, not VM start, with no event whose handling needs the thread stopped.
     */
    private static boolean cancellable(Context context) {
        if (context.state != State.QUEUED || context.vmStart) {
            return false;
        }
        for (Event event : context.eventSet) {
            final EventRequest request = event.request();
            if (request != null && request.getProperty(KEEPS_THREAD_PROPERTY) != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Cancels the queued {@code context} and releases its event thread: the set will neither use
     * the thread nor publish a stop. A thread-only set is settled now — the thread's resume is the
     * set's own release — so its later processing has nothing left to release and cannot spend the
     * count of a stop the thread reaches meanwhile (§ 19 review, finding 5). A stop-all keeps
     * holding its other threads until it is processed; the thread joins its {@code released}.
     * Cancellation and step cleanup commit only after the resume succeeds.
     *
     * @return what was cancelled
     */
    private String cancel(Context context, ThreadReference evaluating) {
        final ThreadReference thread = Objects.requireNonNull(context.thread);
        final String what = describeSet(context);
        final ArmedStep step = pendingSteps.get(thread);
        final boolean landsStep = step != null && reports(context, step.request);
        if (context.policy == EventRequest.SUSPEND_ALL) {
            thread.resume();
            context.released.add(thread);
        } else {
            release(context);
        }
        context.cancelled = true;
        if (step != null && landsStep) {
            // The step's landing: it never lands now.
            pendingSteps.remove(thread);
            deleteQuietly(step.request);
            notifyAll();
        }
        history.record(new EventHistory.DebugEvent("EVENT_CANCELLED", String.format("%s was cancelled before it was "
                + "processed — no condition, logpoint or stop for it: its thread held the monitor the evaluation on "
                + "thread %s waits for", what, safeName(evaluating)),
            Map.of("thread", safeName(thread))));
        return what + " (cancelled)";
    }

    private static boolean reports(Context context, EventRequest request) {
        for (Event event : context.eventSet) {
            if (event.request() == request) {
                return true;
            }
        }
        return false;
    }

    /**
     * What {@code context}'s set reports, for the records: "breakpoint at a.B:42 on thread t".
     */
    private static String describeSet(Context context) {
        final List<String> events = new ArrayList<>();
        for (Event event : context.eventSet) {
            events.add(kindOf(event) + locationOf(event));
        }
        final ThreadReference thread = context.thread;
        return (events.isEmpty() ? "a set" : String.join(", ", events))
            + (thread != null ? " on thread " + safeName(thread) : "");
    }

    private static String kindOf(Event event) {
        if (event instanceof BreakpointEvent) {
            return "breakpoint";
        }
        if (event instanceof StepEvent) {
            return "step";
        }
        if (event instanceof ExceptionEvent) {
            return "exception";
        }
        if (event instanceof WatchpointEvent) {
            return "field watchpoint";
        }
        if (event instanceof ClassPrepareEvent) {
            return "class prepare";
        }
        return "event";
    }

    private static String locationOf(Event event) {
        try {
            final Location location = event instanceof LocatableEvent located ? located.location() : null;
            return location != null ? " at " + location.declaringType().name() + ':' + location.lineNumber() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * The " at Class:line" suffix of a stop, captured at publication so a reply can name the stop
     * even when the thread can no longer be read.
     */
    private String stopLocation(Context context, BreakpointTracker.LastBreakpoint report) {
        final String sourceSite = report.sourceSite();
        if (sourceSite != null) {
            return " at " + sourceSite;
        }
        final Integer id = report.id();
        final EventRequest request = report.kind() == BreakpointTracker.EventKind.BREAKPOINT && id != null
            ? tracker.getEventRequestById(id) : null;
        String location = "";
        boolean ambiguous = false;
        for (Event event : context.eventSet) {
            if (!(event instanceof LocatableEvent located) || !report.thread().equals(located.thread())) {
                continue;
            }
            final boolean matches = switch (report.kind()) {
                case BREAKPOINT -> event instanceof BreakpointEvent || event instanceof WatchpointEvent;
                case EXCEPTION -> event instanceof ExceptionEvent;
                case STEP -> event instanceof StepEvent;
                case VM_START -> false;
            };
            if (matches) {
                final String candidate = locationOf(event);
                if (request != null && request.equals(event.request())) {
                    return candidate;
                }
                if (!candidate.isEmpty()) {
                    ambiguous |= !location.isEmpty() && !location.equals(candidate);
                    location = candidate;
                }
            }
        }
        return ambiguous ? "" : location;
    }

    /**
     * " — partial stop: …" when the watchdog released threads from the presented stop, else "".
     */
    public synchronized String unblockedNote() {
        final Context presented = current;
        return presented == null ? "" : Objects.requireNonNull(presented.report).unblockedNote();
    }

    /**
     * The note on threads the watchdog released from this stop-all before it was presented, or empty.
     */
    private static String unblockedNote(Context context) {
        if (context.unblocked.isEmpty()) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (ThreadReference thread : context.unblocked) {
            names.add(safeName(thread));
        }
        return " — partial stop: the watchdog released thread(s) " + String.join(", ", names) + " from it so an "
            + "evaluation could finish (EVALUATION_UNBLOCKED in jdwp_get_events)";
    }

    // ── Reset and close ──

    /**
     * {@code jdwp_reset}: a new generation — sets of the old one can no longer publish — then every
     * held stop (seen or not) and every manual hold is released and pending steps are dropped.
     * Retries outstanding recoveries and records completed releases even if the VM disconnects.
     *
     * @return released stops, manual holds, successful retries, failures and connection state
     */
    public synchronized ResetResult reset() {
        if (closed) {
            return new ResetResult(0, 0, List.of(), List.of(), true);
        }
        generation++;
        int released = 0;
        final List<String> recovered = new ArrayList<>();
        final List<ReleaseFailure> failures = new ArrayList<>();
        for (Context context : new ArrayList<>(contexts)) {
            if (closed) {
                break;
            }
            switch (context.state) {
                case HELD -> {
                    if (attemptRelease(() -> release(context), () -> releaseLabel(context), failures)) {
                        released++;
                    }
                }
                case RELEASE_FAILED -> retryAutomaticRelease(context, recovered, failures);
                case QUEUED, PROCESSING -> retryWatchdogReleases(context, null, recovered, failures);
                case RELEASED -> {
                }
            }
        }
        final int holds = releaseManualHolds(failures).size();
        retryLeaseDebts(recovered, failures);
        for (ArmedStep step : pendingSteps.values()) {
            deleteQuietly(step.request);
        }
        pendingSteps.clear();
        current = null;
        notifyAll();
        return new ResetResult(released, holds, recovered, failures, closed);
    }

    /**
     * The connection ended: every later call is a no-op. Nothing is resumed — {@code vm.dispose()}
     * releases everything in the target.
     */
    public synchronized void close() {
        closed = true;
        current = null;
        notifyAll();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Stamps {@code request} with the current generation, so an event of it that is routed after
     * {@code jdwp_reset} can be told apart.
     */
    public synchronized void stamp(EventRequest request) {
        request.putProperty(GENERATION_PROPERTY, generation);
    }

    /**
     * Whether {@code request} was created in an earlier generation. Unstamped requests (created
     * outside the session) count as current.
     */
    public synchronized boolean isStale(@Nullable EventRequest request) {
        if (request == null) {
            return false;
        }
        final Object stamped = request.getProperty(GENERATION_PROPERTY);
        return stamped instanceof Long stampedGeneration && stampedGeneration != generation;
    }

    // ── Internals (caller holds the monitor) ──

    /** Explains why ordinary holds of the thread are ineligible for a user release or step. */
    @Nullable
    private String releaseRefusal(ThreadReference thread) {
        return releaseRefusal(thread, false);
    }

    @Nullable
    private String releaseRefusal(ThreadReference thread, boolean awaitingOwnStep) {
        if (!awaitingOwnStep && leases.containsKey(thread)) {
            return "an evaluation is running on thread " + safeName(thread) + " — try again when it has finished";
        }
        for (Context context : contexts) {
            if (!covers(context, thread)) {
                continue;
            }
            // A step waiter can keep waiting while the dispatcher handles its own landing or
            // interruption. A different thread's set can prevent the step from reaching either.
            if (awaitingOwnStep && thread.equals(context.thread)
                && (context.state == State.QUEUED || context.state == State.PROCESSING)) {
                continue;
            }
            switch (context.state) {
                case QUEUED -> {
                    return "context#" + context.id + " (" + describeSet(context)
                        + ") is waiting to be processed — try again";
                }
                case PROCESSING -> {
                    return "context#" + context.id + " (" + describeSet(context)
                        + ") is being processed — try again";
                }
                case RELEASE_FAILED -> {
                    return automaticReleaseFailure(context).describe() + " — retry with jdwp_resume";
                }
                case HELD -> {
                    if (!context.reported) {
                        final ThreadReference eventThread = context.thread;
                        return "thread " + safeName(thread) + " is held by " + describe(context)
                            + (eventThread == null ? "" : " on thread " + safeName(eventThread))
                            + " (context#" + context.id + ") that you have not seen yet — call jdwp_resume_until_event()";
                    }
                }
                case RELEASED -> {
                }
            }
        }
        return null;
    }

    private boolean holds(ThreadReference thread) {
        if (manual.contains(thread)) {
            return true;
        }
        for (Context context : contexts) {
            if (context.state == State.HELD && covers(context, thread)) {
                return true;
            }
        }
        return false;
    }

    /** Releases ordinary thread holds and appends their outcomes with a thread-specific retry ID. */
    private ThreadRelease releaseHolders(ThreadReference thread, List<String> holders, List<ReleaseFailure> failures) {
        final String manualHold = "its jdwp_suspend_thread hold";
        final @Nullable Long retryId = retryThreadId(thread);
        if (manual.contains(thread) && attemptRelease(() -> releaseManual(thread), () -> manualHold, failures, retryId)) {
            holders.add(manualHold);
        }
        for (Context context : new ArrayList<>(contexts)) {
            if (closed) {
                break;
            }
            if (context.state != State.HELD) {
                continue;
            }
            if (context.compensated.contains(thread)) {
                final String label = "temporary suspension from releasing " + describe(context);
                if (attemptRelease(() -> undoCompensation(context, thread), () -> label, failures, retryId)) {
                    holders.add(label);
                }
            }
            if (closed || !covers(context, thread)) {
                continue;
            }
            final String label = describe(context);
            if (context.policy == EventRequest.SUSPEND_ALL) {
                if (attemptRelease(() -> thread.resume(), () -> "its share of " + label, failures, retryId)) {
                    context.released.add(thread);
                    if (context == current && thread.equals(context.thread)) {
                        current = null;
                    }
                    holders.add(label + " (the other threads stay parked)");
                }
            } else if (attemptRelease(() -> release(context), () -> label, failures, retryId)) {
                holders.add(label);
            }
        }
        return new ThreadRelease(null, holders, failures, closed);
    }

    /** Resumes the set and commits its release, or leaves it owned by the context on failure. */
    private void release(Context context) {
        if (context.state == State.RELEASED) {
            return;
        }
        // An invocation borrowing this context's suspension keeps its thread's share: resumed now, at
        // count 0 mid-invocation, the resume would be lost and the invocation's restore would leave
        // the thread suspended with no owner. The share moves to the lease, which resumes it at its end.
        final Lease borrower = borrowerOf(context);
        if (borrower == null || context.policy == EventRequest.SUSPEND_ALL) {
            resumeSet(context, borrower);
        }
        context.state = State.RELEASED;
        if (context == current) {
            current = null;
        }
        if (borrower != null) {
            borrower.debt++;
        }
        context.released.clear();
        context.exempt.clear();
        context.compensated.clear();
        context.automaticReleaseFailure = null;
        context.watchdogFailures.clear();
        contexts.remove(context);
    }

    /** Names a finished set whose automatic release failed and identifies its event thread when available. */
    private static ReleaseFailure automaticReleaseFailure(Context context) {
        final ThreadReference thread = context.thread;
        final String scope = context.policy == EventRequest.SUSPEND_ALL ? " (stop-all, VM-wide suspension)" : "";
        final String holder = "failed automatic release of event set #" + context.id + scope
            + (thread == null ? "" : " on thread " + safeName(thread));
        return new ReleaseFailure(holder, Objects.requireNonNull(context.automaticReleaseFailure), retryThreadId(thread));
    }

    /** Lists recovery failures holding the selected thread, including retained compensation, or any thread when null. */
    private List<ReleaseFailure> waitingRecoveryFailures(@Nullable ThreadReference thread) {
        final List<ReleaseFailure> failures = new ArrayList<>();
        for (Context context : contexts) {
            if (context.state == State.RELEASE_FAILED
                && (thread == null || covers(context, thread) || context.compensated.contains(thread))) {
                failures.add(automaticReleaseFailure(context));
            }
        }
        if (thread == null) {
            unpaidLeaseDebts.forEach((debtor, debt) -> failures.add(leaseDebtFailure(debtor, debt)));
        } else {
            final LeaseDebt debt = unpaidLeaseDebts.get(thread);
            if (debt != null) {
                failures.add(leaseDebtFailure(thread, debt));
            }
        }
        return failures;
    }

    /** Keeps release progress and later failures, sampling pending sets only for a global wait. */
    private NextStop endWait(Outcome outcome, @Nullable StopReport report, @Nullable ReleaseResult released,
                             @Nullable ThreadRelease threadRelease, @Nullable String refusal,
                             @Nullable Long threadId, List<ReleaseFailure> laterFailures) {
        if (threadId != null) {
            if (threadRelease != null) {
                // A failed first pass ends the wait immediately, so later failures can only follow a successful pass.
                final List<ReleaseFailure> failures = laterFailures.isEmpty() ? threadRelease.failures() : laterFailures;
                threadRelease = new ThreadRelease(threadRelease.refusal(), threadRelease.holders(), failures, closed);
            } else if (!laterFailures.isEmpty()) {
                threadRelease = new ThreadRelease(null, List.of(), laterFailures, closed);
            }
            return new NextStop(outcome, report, null, threadRelease, refusal, unseenCountExcept(threadId));
        }
        if (released != null || !laterFailures.isEmpty()) {
            final int pending = closed ? 0 : (int) contexts.stream()
                .filter(context -> context.state == State.QUEUED || context.state == State.PROCESSING).count();
            if (released == null) {
                released = new ReleaseResult(List.of(), List.of(), pending, List.of(), List.of(), laterFailures, closed);
            } else {
                final List<ReleaseFailure> failures = new ArrayList<>(released.failures());
                failures.addAll(laterFailures);
                released = new ReleaseResult(released.released(), released.unseen(), pending, released.manual(),
                    released.recovered(), failures, closed);
            }
        }
        return new NextStop(outcome, report, released, null, refusal, 0);
    }

    /** Retries a finished set and retains the latest failure and retry thread ID if its resume still throws. */
    private void retryAutomaticRelease(Context context, List<String> recovered, List<ReleaseFailure> failures) {
        final ReleaseFailure previous = automaticReleaseFailure(context);
        if (attemptRelease(() -> release(context), previous::holder, failures, previous.retryThreadId())) {
            recovered.add("retry of " + previous.describe());
        } else {
            context.automaticReleaseFailure = failures.get(failures.size() - 1).cause();
        }
    }

    /** Retries only the recorded watchdog releases whose original liveness checks still pass. */
    private void retryWatchdogReleases(Context context, @Nullable ThreadReference onlyThread, List<String> recovered,
                                        List<ReleaseFailure> failures) {
        if (context.state != State.QUEUED && context.state != State.PROCESSING) {
            return;
        }
        for (ThreadReference owner : List.copyOf(context.watchdogFailures.keySet())) {
            final WatchdogReleaseFailure previous = context.watchdogFailures.get(owner);
            if (previous == null || (onlyThread != null && !onlyThread.equals(owner))
                || !watchdogHolds(previous.lease(), owner, previous.monitor()).contains(context)) {
                continue;
            }
            final String label = "failed watchdog release of thread " + safeName(owner)
                + " from event set #" + context.id;
            final String released = attemptWatchdogRelease(context, owner, previous.lease(), previous.monitor());
            if (released == null) {
                failures.add(new ReleaseFailure(label, Objects.requireNonNull(context.watchdogFailures.get(owner)).cause()));
            } else {
                recovered.add("retry of " + new ReleaseFailure(label, previous.cause()).describe());
                recordWatchdogRelease(previous.lease(), owner, List.of(released));
            }
        }
    }

    /** Repays confirmed counts and keeps the rest if JDI rejects a resume. */
    private int repayLeaseDebt(ThreadReference thread, int count) {
        int remaining = count;
        try {
            while (remaining > 0) {
                thread.resume();
                remaining--;
            }
            unpaidLeaseDebts.remove(thread);
        } catch (RuntimeException e) {
            final LeaseDebt previous = unpaidLeaseDebts.get(thread);
            final long id = previous == null ? nextId++ : previous.id();
            unpaidLeaseDebts.put(thread, new LeaseDebt(id, remaining, e));
            if (e instanceof VMDisconnectedException) {
                close();
            } else {
                notifyAll();
            }
            log.debug("[Suspension] Repaying lease debt on thread {} failed: {}", safeName(thread), brief(e));
        }
        return count - remaining;
    }

    /** Retries one thread's unpaid lease debt and reports any progress before a failure. */
    private void retryLeaseDebt(ThreadReference thread, List<String> recovered, List<ReleaseFailure> failures) {
        final LeaseDebt debt = unpaidLeaseDebts.get(thread);
        if (closed || debt == null) {
            return;
        }
        final int repaid = repayLeaseDebt(thread, debt.count());
        final String label = "lease debt on thread " + safeName(thread);
        if (repaid > 0) {
            recovered.add(label + " (" + repaid + " suspension count(s), previously " + brief(debt.cause()) + ")");
        }
        final LeaseDebt unpaid = unpaidLeaseDebts.get(thread);
        if (unpaid != null) {
            failures.add(leaseDebtFailure(thread, unpaid));
        }
    }

    /** Names the thread, its ID, the count and the failure of an unpaid lease debt. */
    private static ReleaseFailure leaseDebtFailure(ThreadReference thread, LeaseDebt debt) {
        return new ReleaseFailure("lease debt on thread " + safeName(thread)
            + " (" + debt.count() + " suspension count(s) unpaid)", debt.cause(), retryThreadId(thread));
    }

    /** Reads a retry thread ID when available without masking the original release failure. */
    @Nullable
    private static Long retryThreadId(@Nullable ThreadReference thread) {
        if (thread == null) {
            return null;
        }
        try {
            return thread.uniqueID();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Retries each unpaid lease debt in the session. */
    private void retryLeaseDebts(List<String> recovered, List<ReleaseFailure> failures) {
        for (ThreadReference thread : List.copyOf(unpaidLeaseDebts.keySet())) {
            retryLeaseDebt(thread, recovered, failures);
        }
    }

    /**
     * Resumes a context's event set so that it nets zero on threads that already left it (user
     * releases, exempt leases, a borrower's share): each is suspended once more first and recorded as
     * compensated. If the resume throws, the compensations are rolled back; a rollback that fails too
     * stays recorded, so the next attempt reuses or undoes it instead of adding another count.
     *
     * @throws RuntimeException what JDI threw; the context stays unreleased
     */
    private static void resumeSet(Context context, @Nullable Lease borrower) {
        final Set<ThreadReference> needed = new LinkedHashSet<>(context.released);
        needed.addAll(context.exempt);
        if (borrower != null) {
            needed.add(borrower.thread);
        }
        for (ThreadReference thread : List.copyOf(context.compensated)) {
            if (!needed.contains(thread)) {
                // A lease may have ended since an earlier rollback failed.
                undoCompensation(context, thread);
            }
        }
        try {
            // A set-wide resume must net zero on threads that already left the set, even if they
            // stopped somewhere else meanwhile. The same net applies to a borrower's share.
            for (ThreadReference thread : needed) {
                if (!context.compensated.contains(thread)) {
                    try {
                        thread.suspend();
                        context.compensated.add(thread);
                    } catch (ObjectCollectedException ignored) {
                        // There is no longer a thread for EventSet.resume() to touch.
                    }
                }
            }
            context.eventSet.resume();
        } catch (VMDisconnectedException e) {
            throw e;
        } catch (RuntimeException e) {
            for (ThreadReference thread : List.copyOf(context.compensated)) {
                try {
                    undoCompensation(context, thread);
                } catch (VMDisconnectedException disconnected) {
                    throw disconnected;
                } catch (RuntimeException rollback) {
                    e.addSuppressed(new IllegalStateException("could not undo temporary suspension on "
                        + safeName(thread) + " (" + brief(rollback) + ")", rollback));
                }
            }
            throw e;
        }
    }

    /**
     * Resumes one temporary suspension that {@link #resumeSet} added and forgets it. A collected
     * thread has nothing left to undo.
     */
    private static void undoCompensation(Context context, ThreadReference thread) {
        try {
            thread.resume();
        } catch (ObjectCollectedException ignored) {
            // The thread and the temporary suspension are gone.
        }
        context.compensated.remove(thread);
    }

    @Nullable
    private Lease borrowerOf(Context context) {
        for (Lease lease : leases.values()) {
            if (lease.anchor == context) {
                return lease;
            }
        }
        return null;
    }

    /**
     * Resumes the manual hold of {@code thread} — unless a lease took the thread out of it already,
     * whose resume stands for this one.
     */
    private void releaseManual(ThreadReference thread) {
        final Lease lease = leases.get(thread);
        if (lease != null && lease.manualExempt) {
            lease.manualExempt = false;
        } else {
            thread.resume();
        }
        manual.remove(thread);
    }

    /**
     * Releases every {@code jdwp_suspend_thread} hold, stopping early when the session closes.
     *
     * @param failures collects the holds whose release threw; they stay held
     * @return the threads whose hold was released
     */
    private List<ThreadReference> releaseManualHolds(List<ReleaseFailure> failures) {
        final List<ThreadReference> released = new ArrayList<>();
        for (ThreadReference thread : List.copyOf(manual)) {
            if (closed) {
                break;
            }
            if (attemptRelease(() -> releaseManual(thread), () -> "the jdwp_suspend_thread hold of "
                + safeName(thread), failures)) {
                released.add(thread);
            }
        }
        return released;
    }

    /**
     * Runs one release and records its failure instead of propagating it. A disconnect closes the
     * session, so the callers stop at the next holder.
     *
     * @param action   the release
     * @param holder   the holder in words, computed only on failure
     * @param failures collects the failure
     * @return whether the release succeeded
     */
    private boolean attemptRelease(Runnable action, Supplier<String> holder, List<ReleaseFailure> failures) {
        return attemptRelease(action, holder, failures, null);
    }

    /** Records the retry thread ID along with a failed release when there is a targeted recovery. */
    private boolean attemptRelease(Runnable action, Supplier<String> holder, List<ReleaseFailure> failures,
                                   @Nullable Long retryThreadId) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            if (e instanceof VMDisconnectedException) {
                close();
            }
            failures.add(new ReleaseFailure(holder.get(), e, retryThreadId));
            return false;
        }
    }

    /**
     * Takes the leased thread out of {@code context}: one resume undoes the context's suspension of it.
     */
    private void exempt(Context context, Lease lease) {
        resumeQuietly(lease.thread);
        context.exempt.add(lease.thread);
        lease.exemptFrom.add(context);
    }

    /**
     * The context whose suspension an invocation on {@code thread} borrows (see {@link #lease}).
     *
     * @throws EvaluationRefusedException when there is none
     */
    private Context leaseAnchor(ThreadReference thread) {
        final Processing processing = PROCESSING.get();
        if (processing != null && processing.session() == this && thread.equals(processing.context().thread)
            && processing.context().policy != EventRequest.SUSPEND_NONE) {
            // The dispatcher invokes on the thread of the set it processes.
            return processing.context();
        }
        final String refusal = releaseRefusal(thread);
        if (refusal != null) {
            throw new EvaluationRefusedException(refusal);
        }
        Context anchor = null;
        boolean bystander = false;
        for (Context context : contexts) {
            if (context.state != State.HELD || !covers(context, thread)) {
                continue;
            }
            if (thread.equals(context.thread)) {
                anchor = context;
            } else {
                bystander = true;
            }
        }
        if (anchor != null) {
            if (!anchor.invocable()) {
                throw new EvaluationRefusedException("thread " + safeName(thread) + " is stopped at VM start, where "
                    + "the JDWP agent allows no invocation — step or run to a breakpoint first");
            }
            return anchor;
        }
        final String needs = "; an evaluation needs it stopped at an event of its own (a breakpoint, an exception "
            + "or a step)";
        if (bystander) {
            throw new EvaluationRefusedException("thread " + safeName(thread) + " is parked by another thread's "
                + "stop" + needs);
        }
        if (manual.contains(thread)) {
            throw new EvaluationRefusedException("thread " + safeName(thread) + " is held by jdwp_suspend_thread"
                + needs);
        }
        throw new EvaluationRefusedException("thread " + safeName(thread) + " is not stopped by this debugging "
            + "session" + needs);
    }

    private static int suspendCountOf(ThreadReference thread) {
        try {
            return thread.suspendCount();
        } catch (RuntimeException e) {
            // Thread gone or VM dying: nothing to take it out of.
            return 0;
        }
    }

    private static boolean covers(Context context, ThreadReference thread) {
        return !context.exempt.contains(thread) && claims(context, thread);
    }

    /**
     * Whether {@code context} holds {@code thread}, a lease's exemption from it aside.
     */
    private static boolean claims(Context context, ThreadReference thread) {
        if (context.state == State.RELEASED || context.released.contains(thread)) {
            return false;
        }
        return switch (context.policy) {
            case EventRequest.SUSPEND_ALL -> true;
            case EventRequest.SUSPEND_EVENT_THREAD -> thread.equals(context.thread);
            default -> false;
        };
    }

    /**
     * Whether {@code context} is a stop the user sees, or may still be: one that {@link #finish}
     * can publish.
     */
    private boolean mayBeShown(Context context) {
        return switch (context.state) {
            case HELD -> true;
            case QUEUED, PROCESSING -> context.mayStop && context.generation == generation && !context.staleAtRouting
                && !context.cancelled && context.policy != EventRequest.SUSPEND_NONE;
            case RELEASE_FAILED, RELEASED -> false;
        };
    }

    private static boolean isLive(Context context) {
        if (context.state != State.HELD) {
            return false;
        }
        final ThreadReference thread = context.thread;
        return thread == null || covers(context, thread);
    }

    @Nullable
    private Context oldestUnseen(@Nullable Long threadId) {
        for (Context context : contexts) {
            if (context.state == State.HELD && !context.reported
                && (threadId == null || Objects.requireNonNull(context.report).threadId() == threadId)) {
                return context;
            }
        }
        return null;
    }

    /**
     * Marks a held context as presented and current; from now on a release may release it.
     * Caller holds the session monitor.
     */
    private StopReport present(Context context) {
        context.reported = true;
        current = context;
        return Objects.requireNonNull(context.report);
    }

    private static String describe(Context context) {
        final BreakpointTracker.LastBreakpoint report = context.snapshot();
        if (report == null) {
            return context.toString();
        }
        return switch (report.kind()) {
            case BREAKPOINT -> "breakpoint " + report.id() + " stop";
            case STEP -> "step stop";
            case EXCEPTION -> "exception stop";
            case VM_START -> "VM start";
        };
    }

    /**
     * A context in words for a release failure: its stop when it has one, else the context itself.
     */
    private static String releaseLabel(Context context) {
        final BreakpointTracker.LastBreakpoint stop = context.snapshot();
        return stop == null ? context.toString() : describeStop(stop);
    }

    /**
     * One stop in words: "breakpoint 3 on thread main".
     */
    static String describeStop(BreakpointTracker.LastBreakpoint stop) {
        return switch (stop.kind()) {
            case BREAKPOINT -> (stop.id() != null ? "breakpoint " + stop.id() : "untracked breakpoint")
                + " on thread " + safeName(stop.thread());
            case STEP -> "step on thread " + safeName(stop.thread());
            case EXCEPTION -> "exception on thread " + safeName(stop.thread());
            case VM_START -> "the VM-start suspension";
        };
    }

    /**
     * The exception's simple class name and the first line of its message, for a one-line reply.
     */
    private static String brief(Throwable failure) {
        final String type = failure.getClass().getSimpleName();
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message.lines().findFirst().orElse("");
    }

    private static void resumeQuietly(ThreadReference thread) {
        try {
            thread.resume();
        } catch (RuntimeException e) {
            log.debug("[Suspension] Resuming {} failed: {}", safeName(thread), e.toString());
        }
    }

    private static void deleteQuietly(StepRequest request) {
        try {
            request.virtualMachine().eventRequestManager().deleteEventRequest(request);
        } catch (RuntimeException e) {
            log.debug("[Suspension] Deleting a step request failed: {}", e.toString());
        }
    }

    private static String safeName(ThreadReference thread) {
        try {
            return thread.name();
        } catch (RuntimeException e) {
            return "thread#" + thread.uniqueID();
        }
    }

    /**
     * The contexts, manual holds, unpaid lease debts and pending steps, for diagnostics.
     */
    public synchronized String describeState() {
        final List<String> leased = new ArrayList<>();
        for (ThreadReference thread : leases.keySet()) {
            leased.add(safeName(thread));
        }
        return "generation=" + generation + (closed ? ", closed" : "") + ", contexts=" + contexts + ", manual="
            + manual.size() + ", pendingSteps=" + pendingSteps.size() + ", leases=" + leased + ", reserved="
            + reserved.size() + ", unpaidLeaseDebts=" + unpaidLeaseDebts.size() + ", routeExemptions="
            + routeExemptions + ", current=" + current;
    }

    /**
     * Test hook: how many stop candidates the set the calling thread processes has, or {@code -1}.
     */
    static int processingCandidateCount() {
        final Processing processing = PROCESSING.get();
        return processing == null ? -1 : processing.session().candidateCount(processing.context());
    }

    /**
     * Test hook: a context's state.
     */
    synchronized State stateOf(Context context) {
        return context.state;
    }
}
