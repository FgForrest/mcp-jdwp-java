package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-thread reentrancy guard that tracks which target-VM threads are currently executing an
 * MCP-initiated {@code invokeMethod} chain. Used by {@link JdiEventListener} to suppress
 * breakpoint / exception / step events that fire on a thread that is already mid-evaluation —
 * without this suppression, the listener would try to suspend the very thread that the outer
 * {@code invokeMethod} is waiting on, producing a cross-thread deadlock (the MCP server freezes
 * until the evaluation times out).
 *
 * <p><b>Usage contract:</b> every MCP-driven {@code invokeMethod} path runs inside
 * {@code try (EvaluationGuard.Lease lease = guard.lease(thread))}. The lease captures the thread's
 * {@link ThreadReference#uniqueID()} once, up front: if the target thread dies during the
 * evaluation, a later {@code uniqueID()} call throws {@code ObjectCollectedException}, which would
 * propagate out of the cleanup and leave a dangling entry in the depth map.
 *
 * <p><b>Leases (issue #45):</b> the outermost lease of a thread is also taken in the bound
 * {@link SuspensionSession}, which admits the invocation or refuses it with a reason
 * ({@link EvaluationRefusedException}) and takes the thread out of every other stop suspending it,
 * so the invocation can run. Without a bound session (unit tests) only the depth is counted.
 * Only the chain holding a thread re-enters; another caller is refused. {@link #enter(long)} /
 * {@link #exit(long)} mark a thread as evaluating by depth alone (no session, no ownership).
 *
 * <p>The guard is counted (not a boolean) so layered call sites — e.g. {@code configureCompilerClasspath}
 * calling {@code discoverClasspath} which itself issues {@code invokeMethod} calls nested inside
 * the outer {@code evaluate()} — all increment a shared depth and release the guard in reverse
 * order. An {@code exit} with no matching {@code enter} is a no-op (defensive) rather than an
 * error, because half-finished cleanup should never throw.
 *
 * <p><b>Why {@code long} for mutations but {@link ThreadReference} for reads:</b>
 * {@link #isEvaluating(ThreadReference)} is the listener's hot-path check, called on every
 * suspending JDI event. JDI guarantees that the {@code ThreadReference} carried by a live
 * {@code BreakpointEvent} / {@code StepEvent} / {@code ExceptionEvent} is itself live at the
 * moment of delivery, so {@code uniqueID()} is safe there. The mutation path lives across an
 * {@code invokeMethod} round-trip where the thread can die, so it takes a pre-captured id.
 *
 * <p>Thread-safety: backed by a {@link ConcurrentHashMap}; all mutations go through
 * {@code merge}/{@code compute} so enter and exit from different threads on the same key are
 * atomic. No locks are held across {@code invokeMethod} calls.
 */
@Service
public class EvaluationGuard {

    /**
     * The session of the current connection, bound by {@link JdiEventListener} when it starts.
     */
    @Nullable
    private volatile SuspensionSession session;

    /**
     * Depth counter per target-VM thread for {@link #enter(long)} / {@link #exit(long)}. An entry is
     * present iff the thread is marked as inside an MCP-driven {@code invokeMethod} chain; the value
     * is the nesting depth.
     */
    private final ConcurrentMap<Long, Integer> depthByThreadId = new ConcurrentHashMap<>();

    /**
     * The invocation chain holding each target thread (by uniqueID) through a {@link #lease}: the
     * Java thread running it and the session that admitted it. Only that chain re-enters.
     */
    private final ConcurrentMap<Long, Hold> holds = new ConcurrentHashMap<>();

    private record Hold(Thread chain, @Nullable SuspensionSession session) {
    }

    /**
     * Binds the session whose leases the outermost {@link #lease} of a thread takes. Holds of an
     * earlier connection are dropped: they neither suppress nor admit anything on this one, and
     * their leases end only in their own session.
     */
    public void bind(@Nullable SuspensionSession session) {
        this.session = session;
        holds.clear();
    }

    /**
     * Enters an MCP-driven invocation chain on {@code thread}; close the lease when the chain ends.
     * The chain that holds the thread may lease it again (nested calls); any other caller is
     * refused — the JDWP agent runs one invocation per thread, and a second chain would spend the
     * suspension the first one borrows. The outermost lease is admitted by the bound session (see
     * the class Javadoc).
     *
     * @throws EvaluationRefusedException when another chain holds the thread or the session refuses
     */
    public Lease lease(ThreadReference thread) {
        final long threadUniqueId = thread.uniqueID();
        final Thread chain = Thread.currentThread();
        final Hold held = holds.get(threadUniqueId);
        if (held != null) {
            if (held.chain() == chain) {
                return new Lease(threadUniqueId, thread, null);
            }
            throw alreadyRunning(thread);
        }
        final SuspensionSession bound = this.session;
        final Hold hold = new Hold(chain, bound);
        if (holds.putIfAbsent(threadUniqueId, hold) != null) {
            throw alreadyRunning(thread);
        }
        if (bound != null) {
            try {
                bound.lease(thread);
            } catch (RuntimeException e) {
                holds.remove(threadUniqueId, hold);
                throw e;
            }
        }
        return new Lease(threadUniqueId, thread, hold);
    }

    private static EvaluationRefusedException alreadyRunning(ThreadReference thread) {
        String name;
        try {
            name = thread.name();
        } catch (RuntimeException e) {
            name = "#" + thread.uniqueID();
        }
        return new EvaluationRefusedException("an evaluation on thread " + name + " is already running — try again "
            + "when it has finished");
    }

    /**
     * One level of an invocation chain; {@link #close} leaves it.
     */
    public final class Lease implements AutoCloseable {
        private final long threadUniqueId;
        private final ThreadReference thread;
        /**
         * The hold this lease took — only the chain's outermost lease has one, and only it ends the chain.
         */
        @Nullable
        private final Hold hold;

        private Lease(long threadUniqueId, ThreadReference thread, @Nullable Hold hold) {
            this.threadUniqueId = threadUniqueId;
            this.thread = thread;
            this.hold = hold;
        }

        @Override
        public void close() {
            final Hold taken = this.hold;
            if (taken == null) {
                return;
            }
            // Unlease before the hold is dropped, so the thread's own events stay suppressed meanwhile.
            final SuspensionSession admittedBy = taken.session();
            if (admittedBy != null) {
                admittedBy.unlease(this.thread);
            }
            // By identity: a hold of an earlier connection never removes one of the current connection.
            holds.remove(this.threadUniqueId, taken);
        }
    }

    /**
     * Marks the thread with the given {@code threadUniqueId} as entering an MCP-driven
     * evaluation. Safe to call repeatedly for nested invocations — the depth counter increases
     * each time and the matching {@link #exit(long)} calls must balance the entries for the
     * guard to clear.
     *
     * <p>Callers MUST capture {@link ThreadReference#uniqueID()} at the call site (before
     * starting any {@code invokeMethod} work) and pass the resulting {@code long} to both
     * {@code enter} and the paired {@link #exit(long)}. See the class JavaDoc for the rationale.
     */
    public void enter(long threadUniqueId) {
        depthByThreadId.merge(threadUniqueId, 1, Integer::sum);
    }

    /**
     * Marks the thread with the given {@code threadUniqueId} as leaving one level of an
     * MCP-driven evaluation. When the depth reaches zero the entry is removed. An {@code exit}
     * with no matching {@code enter} is a no-op so that defensive try/finally cleanup can never
     * throw — this matters because the id may outlive the underlying thread if the target
     * thread died during the invocation chain.
     */
    public void exit(long threadUniqueId) {
        depthByThreadId.compute(threadUniqueId, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    }

    /**
     * Returns true iff {@code thread} is currently inside at least one MCP-driven evaluation.
     * Queried by {@link JdiEventListener} on every suspending JDI event; must be O(1). JDI
     * guarantees the {@code ThreadReference} on a live event is itself live, so calling
     * {@code uniqueID()} here cannot throw {@code ObjectCollectedException}.
     */
    public boolean isEvaluating(ThreadReference thread) {
        final long threadUniqueId = thread.uniqueID();
        return holds.containsKey(threadUniqueId) || depthByThreadId.containsKey(threadUniqueId);
    }

    /**
     * Current nesting depth for the thread with the given id; zero if not evaluating. Exposed
     * for unit tests and defensive diagnostics — callers should prefer {@link #isEvaluating} for
     * the hot-path check.
     */
    int depth(long threadUniqueId) {
        final Integer d = depthByThreadId.get(threadUniqueId);
        return d == null ? 0 : d;
    }
}
