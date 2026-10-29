package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

        private Context(long id, long generation, EventSet eventSet, int policy, @Nullable ThreadReference thread,
                        boolean vmStart, boolean staleAtRouting) {
            this.id = id;
            this.generation = generation;
            this.eventSet = eventSet;
            this.policy = policy;
            this.thread = thread;
            this.vmStart = vmStart;
            this.staleAtRouting = staleAtRouting;
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

    private final BreakpointTracker tracker;
    private final EventHistory history;
    private final List<Context> contexts = new ArrayList<>();
    private final Set<ThreadReference> manual = new LinkedHashSet<>();
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
        boolean stale = false;
        for (com.sun.jdi.event.Event event : eventSet) {
            stale |= isStale(event.request());
        }
        final Context context = new Context(nextId++, generation, eventSet, eventSet.suspendPolicy(), thread, vmStart,
            stale);
        if (!closed) {
            contexts.add(context);
        }
        return context;
    }

    /**
     * The dispatcher starts processing {@code context}.
     */
    public synchronized void begin(Context context) {
        if (!closed && context.state == State.QUEUED) {
            context.state = State.PROCESSING;
        }
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
     * earlier generation (reset meanwhile, or a request created before the reset) and a set that
     * suspends nothing never publish.
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
        if (current && !context.candidates.isEmpty() && context.policy != EventRequest.SUSPEND_NONE) {
            publish(context);
            return true;
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
            resumeQuietly(thread);
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

    // ── Reset and close ──

    /**
     * {@code jdwp_reset}: a new generation — sets of the old one can no longer publish — then every
     * held stop (seen or not) and every manual hold is released and pending steps are dropped.
     *
     * @return how many stops were released
     */
    public synchronized int reset() {
        if (closed) {
            return 0;
        }
        generation++;
        int released = 0;
        for (Context context : new ArrayList<>(contexts)) {
            if (context.state == State.HELD) {
                release(context);
                released++;
            }
        }
        for (ThreadReference thread : manual) {
            resumeQuietly(thread);
        }
        manual.clear();
        for (StepRequest request : pendingSteps.values()) {
            deleteQuietly(request);
        }
        pendingSteps.clear();
        current = null;
        notifyAll();
        return released;
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
            resumeQuietly(thread);
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
        try {
            if (context.policy == EventRequest.SUSPEND_ALL) {
                // Re-suspend the threads a user release took out, so the set's vm.resume() nets
                // zero on them. Relative: a thread that has stopped again keeps its new stop.
                for (ThreadReference thread : context.released) {
                    suspendQuietly(thread);
                }
            }
            context.eventSet.resume();
        } catch (RuntimeException e) {
            // VM gone or dying: the session is about to close.
            log.debug("[Suspension] Releasing {} failed: {}", context, e.toString());
        }
        context.released.clear();
        contexts.remove(context);
    }

    private static boolean covers(Context context, ThreadReference thread) {
        if (context.state == State.RELEASED || context.released.contains(thread)) {
            return false;
        }
        return switch (context.policy) {
            case EventRequest.SUSPEND_ALL -> true;
            case EventRequest.SUSPEND_EVENT_THREAD -> thread.equals(context.thread);
            default -> false;
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
        return "generation=" + generation + (closed ? ", closed" : "") + ", contexts=" + contexts + ", manual="
            + manual.size() + ", pendingSteps=" + pendingSteps.size() + ", current=" + current;
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
