package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
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
        private BreakpointTracker.@Nullable LastBreakpoint report;
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
         * Threads the watchdog released from this stop-all so an evaluation could finish (A1); they
         * are in {@link #released} too. A stop published with any is partial.
         */
        private final Set<ThreadReference> unblocked = new LinkedHashSet<>();
        /**
         * The watchdog released the event thread before the set was processed: no handler may use
         * the thread or publish a stop; {@link SuspensionSession#finish} settles it.
         */
        private boolean cancelled;

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
     * What a release did.
     *
     * @param released the stops released, oldest first
     * @param unseen   stops still held because no tool has presented them yet
     * @param pending  sets still waiting for or being processed; their threads stay parked
     * @param manual   threads whose manual hold was released
     */
    public record ReleaseResult(List<BreakpointTracker.LastBreakpoint> released,
                                List<BreakpointTracker.LastBreakpoint> unseen, int pending,
                                List<ThreadReference> manual) {
        /**
         * Whether anything was released.
         */
        public boolean releasedAnything() {
            return !released.isEmpty() || !manual.isEmpty();
        }
    }

    /**
     * What {@link #releaseThread} did.
     *
     * @param refusal  why nothing was released, or {@code null} when it went ahead
     * @param holders  what it released, in words
     */
    public record ThreadRelease(@Nullable String refusal, List<String> holders) {
        public boolean released() {
            return refusal == null && !holders.isEmpty();
        }
    }

    /**
     * What {@link #reset} released.
     *
     * @param stops held stops, seen or not
     * @param holds {@code jdwp_suspend_thread} holds
     */
    public record ResetResult(int stops, int holds) {
        public static final ResetResult NONE = new ResetResult(0, 0);
    }

    /**
     * Outcome of {@link #nextStop}.
     */
    public enum Outcome {
        /**
         * A stop is presented.
         */
        STOPPED,
        /**
         * The deadline passed without a stop.
         */
        TIMEOUT,
        /**
         * The session closed (disconnect, death, reconnect).
         */
        CLOSED,
        /**
         * {@code jdwp_reset} ran while waiting.
         */
        RESET
    }

    /**
     * Result of {@link #nextStop}.
     *
     * @param stop     the stop presented, for {@link Outcome#STOPPED}
     * @param released what the call released before it waited ({@code null} when it released nothing
     *                 because a stop was already waiting to be seen or a step was in flight)
     */
    public record NextStop(Outcome outcome, BreakpointTracker.@Nullable LastBreakpoint stop,
                           @Nullable ReleaseResult released) {
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

    private final BreakpointTracker tracker;
    private final EventHistory history;
    private final List<Context> contexts = new ArrayList<>();
    private final Set<ThreadReference> manual = new LinkedHashSet<>();
    private final Map<ThreadReference, Lease> leases = new LinkedHashMap<>();
    /**
     * Threads a frame tool works on, with how many tools: the watchdog never releases them (A2).
     */
    private final Map<ThreadReference, Integer> reserved = new LinkedHashMap<>();
    /**
     * How often a routed stop-all took a leased thread out of it, for diagnostics.
     */
    private long routeExemptions;
    private final Map<ThreadReference, StepRequest> pendingSteps = new LinkedHashMap<>();
    private long nextId = 1;
    private long generation = 1;
    private boolean closed;
    /**
     * Whether a VMStart set has finished (published or, with policy NONE, released).
     */
    private boolean vmStartSeen;
    @Nullable
    private Context current;

    SuspensionSession(BreakpointTracker tracker, EventHistory history) {
        this.tracker = tracker;
        this.history = history;
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
        release(context);
        if (context.vmStart) {
            notifyAll();
        }
        return false;
    }

    private void publish(Context context) {
        final BreakpointTracker.LastBreakpoint report = context.candidates.get(0);
        context.report = report;
        context.state = State.HELD;
        tracker.published(report);
        final ThreadReference thread = context.thread;
        if (thread != null) {
            final StepRequest pending = pendingSteps.remove(thread);
            if (pending != null && report.kind() != BreakpointTracker.EventKind.STEP) {
                // Another stop on the stepping thread ends the step, as in an IDE.
                deleteQuietly(pending);
                history.record(new EventHistory.DebugEvent("STEP_INTERRUPTED",
                    String.format("The step on thread %s was interrupted by a stop", safeName(thread)),
                    Map.of("thread", safeName(thread))));
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
        if (closed) {
            return null;
        }
        final Context live = current;
        if (live != null && isLive(live)) {
            return live.report;
        }
        current = null;
        final Context unseen = oldestUnseen();
        return unseen != null ? present(unseen) : null;
    }

    /**
     * The current stop if it is still held on its event thread, without presenting anything: what
     * tools that default their thread act on. {@code null} when there is none.
     */
    public synchronized BreakpointTracker.@Nullable LastBreakpoint currentStop() {
        final Context live = current;
        return !closed && live != null && isLive(live) ? live.report : null;
    }

    /**
     * How many published stops no tool has presented yet.
     */
    public synchronized int unseenCount() {
        int count = 0;
        for (Context context : contexts) {
            if (context.state == State.HELD && !context.reported) {
                count++;
            }
        }
        return count;
    }

    /**
     * {@code jdwp_resume_until_event}: presents the oldest unseen stop at once if there is one;
     * otherwise releases the presented stops (unless a step is in flight, whose landing it waits
     * for without releasing anything) and waits for the next stop. Every wake re-selects; a
     * notification never implies that a particular stop exists.
     */
    public synchronized NextStop nextStop(long timeoutMs) throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        final long startGeneration = generation;
        ReleaseResult released = null;
        boolean first = true;
        while (true) {
            // A stop published just before the VM died is still shown: it explains the death.
            final Context unseen = oldestUnseen();
            if (unseen != null) {
                return new NextStop(Outcome.STOPPED, present(unseen), released);
            }
            if (closed) {
                return new NextStop(Outcome.CLOSED, null, released);
            }
            if (generation != startGeneration) {
                return new NextStop(Outcome.RESET, null, released);
            }
            if (first) {
                first = false;
                if (pendingSteps.isEmpty()) {
                    released = releaseAllLocked();
                }
            }
            final long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return new NextStop(Outcome.TIMEOUT, null, released);
            }
            wait(remainingMs);
        }
    }

    // ── Releases ──

    /**
     * {@code jdwp_resume}: releases every presented stop and every manual hold. Stops not yet
     * presented stay held; sets still queued or processed are not touched.
     */
    public synchronized ReleaseResult releaseAll() {
        return closed ? new ReleaseResult(List.of(), List.of(), 0, List.of()) : releaseAllLocked();
    }

    private ReleaseResult releaseAllLocked() {
        final List<BreakpointTracker.LastBreakpoint> releasedStops = new ArrayList<>();
        final List<BreakpointTracker.LastBreakpoint> unseen = new ArrayList<>();
        int pending = 0;
        for (Context context : new ArrayList<>(contexts)) {
            switch (context.state) {
                case HELD -> {
                    if (context.reported) {
                        releasedStops.add(Objects.requireNonNull(context.report));
                        release(context);
                    } else {
                        unseen.add(Objects.requireNonNull(context.report));
                    }
                }
                case QUEUED, PROCESSING -> pending++;
                case RELEASED -> {
                }
            }
        }
        final List<ThreadReference> manualReleased = new ArrayList<>(manual);
        for (ThreadReference thread : manualReleased) {
            releaseManual(thread);
        }
        manual.clear();
        return new ReleaseResult(releasedStops, unseen, pending, manualReleased);
    }

    /**
     * {@code jdwp_resume_thread}: releases what the user holds on {@code thread} — its manual hold,
     * its own presented stop, and its share of presented stop-all stops. Refuses while a set
     * covering the thread is still queued or processed, or while it holds a stop nobody has seen.
     */
    public synchronized ThreadRelease releaseThread(ThreadReference thread) {
        if (closed) {
            return new ThreadRelease("the debugging session is closed", List.of());
        }
        final String refusal = releaseRefusal(thread);
        if (refusal != null) {
            return new ThreadRelease(refusal, List.of());
        }
        final List<String> holders = releaseHolders(thread);
        return holders.isEmpty()
            ? new ThreadRelease("nothing holds thread " + safeName(thread) + " — it was not suspended by this "
            + "debugging session's stops or jdwp_suspend_thread", List.of())
            : new ThreadRelease(null, holders);
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
     * A step on {@code thread}, atomically: admission, installing the request, then releasing the
     * thread. A refused or failed step leaves no request behind.
     *
     * @return {@code null} when the step was started, otherwise why not
     */
    @Nullable
    public synchronized String step(ThreadReference thread, StepInstaller installer) throws Exception {
        if (closed) {
            return "the debugging session is closed";
        }
        final String refusal = releaseRefusal(thread);
        if (refusal != null) {
            return refusal;
        }
        if (!holds(thread)) {
            return "nothing holds thread " + safeName(thread) + " — step from a stop or a jdwp_suspend_thread hold";
        }
        final StepRequest request = installer.install();
        stamp(request);
        try {
            pendingSteps.put(thread, request);
            releaseHolders(thread);
        } catch (RuntimeException e) {
            pendingSteps.remove(thread);
            deleteQuietly(request);
            throw e;
        }
        return null;
    }

    /**
     * Whether a step is in flight on {@code thread}.
     */
    public synchronized boolean stepPending(ThreadReference thread) {
        return pendingSteps.containsKey(thread);
    }

    /**
     * The holds this session knows on {@code thread}: the ids of the contexts holding it, and
     * {@link #MANUAL_HOLD} for a manual hold. A lease's exemption still counts — its invocation
     * returns to the same frames. While one hold lasts, the thread cannot run past its stop, so a
     * hold present in two snapshots proves its frames belong to the same activations in between
     * (an {@link InvocationRunner} job's admission). Context ids are never reused.
     */
    public synchronized Set<Long> holdsOn(ThreadReference thread) {
        return holdsOn(thread, false);
    }

    /**
     * {@link #holdsOn}, limited to the holds only a tool ends: the published stops holding
     * {@code thread} and a manual hold. Nothing else releases those — the dispatcher releases only
     * the sets it processes, the watchdog never touches a published stop or a manual hold, and tools
     * run one at a time — so one of them found by a tool still holds the thread when the tool next
     * reads its frames. A set still queued or processed does not vouch for that: the dispatcher may
     * release it at any moment, and the thread run on (a frame tool's retry, #47 § 4).
     */
    public synchronized Set<Long> userHoldsOn(ThreadReference thread) {
        return holdsOn(thread, true);
    }

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
        return holds;
    }

    /**
     * The threads the user may see stopped, with what holds each — for the redefinition rule (#42
     * proposal § 6): a method a held thread has a frame in is never redefined, or the frame would
     * turn obsolete and lose its locals. A hold is a context that is shown or may still be (HELD, or
     * QUEUED or PROCESSING in the current generation, not cancelled, and able to stop), a manual hold
     * ({@link #MANUAL_HOLD}), a step in flight ({@link #STEP_HOLD}), or a suspension nothing here
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
         * The VM started without suspending (VMStart with policy NONE).
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
     * @throws EvaluationRefusedException when the invocation is not admitted, or a frame tool still
     *                                    reads the thread after {@link #RESERVATION_WAIT_MS}
     */
    public synchronized void lease(ThreadReference thread) {
        awaitUnreserved(thread);
        if (closed) {
            return;
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
     * of that still holds. A context released meanwhile has re-suspended it already.
     */
    public synchronized void unlease(ThreadReference thread) {
        final Lease lease = leases.remove(thread);
        if (lease == null || closed) {
            return;
        }
        for (Context context : lease.exemptFrom) {
            if (context.state != State.RELEASED && context.exempt.remove(thread)) {
                suspendQuietly(thread);
            }
        }
        if (lease.manualExempt && manual.contains(thread)) {
            suspendQuietly(thread);
        }
        // Last: the shares released contexts handed over (the invocation's restore put them back).
        for (int i = 0; i < lease.debt; i++) {
            resumeQuietly(thread);
        }
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
     * of a queued set cancels it ({@link #cancel}).
     *
     * @return what was released, or {@code null} when nothing was
     */
    @Nullable
    public synchronized String unblock(Object token, ThreadReference owner, ObjectReference monitor) {
        if (closed || !(token instanceof Lease lease) || leases.get(lease.thread) != lease || !lease.dispatcher
            || leases.containsKey(owner) || reserved.containsKey(owner) || manual.contains(owner)) {
            return null;
        }
        final List<Context> holding = new ArrayList<>();
        for (Context context : contexts) {
            if (!covers(context, owner)) {
                continue;
            }
            if (context.state == State.HELD || (owner.equals(context.thread) && !cancellable(context))) {
                return null;
            }
            holding.add(context);
        }
        if (holding.isEmpty() || suspendCountOf(owner) != holding.size() || !owner.equals(owningThreadOf(monitor))) {
            return null;
        }
        final List<String> from = new ArrayList<>();
        for (Context context : holding) {
            if (owner.equals(context.thread)) {
                from.add(cancel(context, lease.thread));
            } else {
                resumeQuietly(owner);
                context.released.add(owner);
                context.unblocked.add(owner);
                from.add(describeSet(context) + " (its other threads stay parked)");
            }
        }
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
     *
     * @return what was cancelled
     */
    private String cancel(Context context, ThreadReference evaluating) {
        final ThreadReference thread = Objects.requireNonNull(context.thread);
        context.cancelled = true;
        final String what = describeSet(context);
        final StepRequest step = pendingSteps.get(thread);
        if (step != null && reports(context, step)) {
            // The step's landing: it never lands now.
            pendingSteps.remove(thread);
            deleteQuietly(step);
        }
        if (context.policy == EventRequest.SUSPEND_ALL) {
            resumeQuietly(thread);
            context.released.add(thread);
        } else {
            context.state = State.RELEASED;
            try {
                context.eventSet.resume();
            } catch (RuntimeException e) {
                log.debug("[Suspension] Releasing cancelled {} failed: {}", context, e.toString());
            }
            contexts.remove(context);
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
     * " — partial stop: …" when the watchdog released threads from the presented stop, else "".
     */
    public synchronized String unblockedNote() {
        final Context presented = current;
        if (presented == null || presented.unblocked.isEmpty()) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (ThreadReference thread : presented.unblocked) {
            names.add(safeName(thread));
        }
        return " — partial stop: the watchdog released thread(s) " + String.join(", ", names) + " from it so an "
            + "evaluation could finish (EVALUATION_UNBLOCKED in jdwp_get_events)";
    }

    // ── Reset and close ──

    /**
     * {@code jdwp_reset}: a new generation — sets of the old one can no longer publish — then every
     * held stop (seen or not) and every manual hold is released and pending steps are dropped.
     *
     * @return how many stops and manual holds were released
     */
    public synchronized ResetResult reset() {
        if (closed) {
            return ResetResult.NONE;
        }
        generation++;
        int released = 0;
        for (Context context : new ArrayList<>(contexts)) {
            if (context.state == State.HELD) {
                release(context);
                released++;
            }
        }
        final int holds = manual.size();
        for (ThreadReference thread : manual) {
            releaseManual(thread);
        }
        manual.clear();
        for (StepRequest request : pendingSteps.values()) {
            deleteQuietly(request);
        }
        pendingSteps.clear();
        current = null;
        notifyAll();
        return new ResetResult(released, holds);
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

    @Nullable
    private String releaseRefusal(ThreadReference thread) {
        if (leases.containsKey(thread)) {
            return "an evaluation is running on thread " + safeName(thread) + " — try again when it has finished";
        }
        for (Context context : contexts) {
            if (!covers(context, thread)) {
                continue;
            }
            switch (context.state) {
                case QUEUED -> {
                    return "an event of thread " + safeName(thread) + " is waiting to be processed — try again";
                }
                case PROCESSING -> {
                    return "an event of thread " + safeName(thread) + " is being processed — try again";
                }
                case HELD -> {
                    if (!context.reported) {
                        return "thread " + safeName(thread) + " is held by a stop you have not seen yet — call "
                            + "jdwp_resume_until_event";
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

    private List<String> releaseHolders(ThreadReference thread) {
        final List<String> holders = new ArrayList<>();
        if (manual.remove(thread)) {
            releaseManual(thread);
            holders.add("its jdwp_suspend_thread hold");
        }
        for (Context context : new ArrayList<>(contexts)) {
            if (context.state != State.HELD || !covers(context, thread)) {
                continue;
            }
            final String label = describe(context);
            if (context.policy == EventRequest.SUSPEND_ALL) {
                resumeQuietly(thread);
                context.released.add(thread);
                if (context == current && thread.equals(context.thread)) {
                    current = null;
                }
                holders.add(label + " (the other threads stay parked)");
            } else {
                release(context);
                holders.add(label);
            }
        }
        return holders;
    }

    private void release(Context context) {
        if (context.state == State.RELEASED) {
            return;
        }
        context.state = State.RELEASED;
        if (context == current) {
            current = null;
        }
        // An invocation borrowing this context's suspension keeps its thread's share: resumed now, at
        // count 0 mid-invocation, the resume would be lost and the invocation's restore would leave
        // the thread suspended with no owner. The share moves to the lease, which resumes it at its end.
        final Lease borrower = borrowerOf(context);
        try {
            if (borrower != null && context.policy != EventRequest.SUSPEND_ALL) {
                // The borrower's own stop of its thread alone: nothing else to release, and no thread
                // is exempt from it (a lease never takes its thread out of its own anchor).
                log.debug("[Suspension] {} released; its suspension of {} now belongs to the running "
                    + "evaluation", context, safeName(borrower.thread));
            } else {
                if (context.policy == EventRequest.SUSPEND_ALL) {
                    // Re-suspend the threads a user release took out, so the set's vm.resume() nets
                    // zero on them. Relative: a thread that has stopped again keeps its new stop.
                    for (ThreadReference thread : context.released) {
                        suspendQuietly(thread);
                    }
                    if (borrower != null) {
                        suspendQuietly(borrower.thread);
                    }
                }
                // Likewise the leased threads taken out of it; their leases find it released.
                for (ThreadReference thread : context.exempt) {
                    suspendQuietly(thread);
                }
                context.eventSet.resume();
            }
        } catch (RuntimeException e) {
            // VM gone or dying: the session is about to close.
            log.debug("[Suspension] Releasing {} failed: {}", context, e.toString());
        }
        if (borrower != null) {
            borrower.debt++;
        }
        context.released.clear();
        context.exempt.clear();
        contexts.remove(context);
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
            return;
        }
        resumeQuietly(thread);
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
            case RELEASED -> false;
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
    private Context oldestUnseen() {
        for (Context context : contexts) {
            if (context.state == State.HELD && !context.reported) {
                return context;
            }
        }
        return null;
    }

    private BreakpointTracker.LastBreakpoint present(Context context) {
        context.reported = true;
        current = context;
        return Objects.requireNonNull(context.report);
    }

    private static String describe(Context context) {
        final BreakpointTracker.LastBreakpoint report = context.report;
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

    private static void resumeQuietly(ThreadReference thread) {
        try {
            thread.resume();
        } catch (RuntimeException e) {
            log.debug("[Suspension] Resuming {} failed: {}", safeName(thread), e.toString());
        }
    }

    private static void suspendQuietly(ThreadReference thread) {
        try {
            thread.suspend();
        } catch (RuntimeException e) {
            log.debug("[Suspension] Suspending {} failed: {}", safeName(thread), e.toString());
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
     * The contexts, manual holds and pending steps, for diagnostics.
     */
    public synchronized String describeState() {
        final List<String> leased = new ArrayList<>();
        for (ThreadReference thread : leases.keySet()) {
            leased.add(safeName(thread));
        }
        return "generation=" + generation + (closed ? ", closed" : "") + ", contexts=" + contexts + ", manual="
            + manual.size() + ", pendingSteps=" + pendingSteps.size() + ", leases=" + leased + ", reserved="
            + reserved.size() + ", routeExemptions=" + routeExemptions + ", current=" + current;
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
