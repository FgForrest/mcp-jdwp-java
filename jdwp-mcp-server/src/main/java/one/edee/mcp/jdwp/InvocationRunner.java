package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs a tool's work that invokes methods in the target — an evaluation, {@code toString()}, loading
 * a class — on a pool thread, and waits for it at most {@link #TIMEOUT_MS} (issue #45, proposal
 * § 19 A1). Invoked code can block on a lock that a parked thread holds; MCP tool calls are
 * serialized, so a tool blocked on it would leave the user no tool to release that thread with.
 * <p>
 * A job that outlives the wait keeps running — an invocation in the target cannot be cancelled —
 * and the tool answers {@link StillRunning}, naming what blocks the thread when JDI can tell. The
 * job's lease keeps the thread until it ends; its result is then recorded as
 * {@code EVALUATION_COMPLETED_LATE} in the session it started in. Exactly one of the two happens:
 * the caller's timeout and the job's completion are settled by one compare-and-set. A thread with a
 * job still running refuses another one before anything is read from it. A hung job pins its pool
 * thread until the connection ends ({@code jdwp_reconnect} or {@code jdwp_disconnect}).
 */
public final class InvocationRunner {
    private static final Logger log = LoggerFactory.getLogger(InvocationRunner.class);

    /**
     * How long a tool waits for its invocation — well under the MCP client's tool timeout.
     */
    public static final long TIMEOUT_MS = 20_000;

    private static final int PENDING = 0;
    private static final int RUNNING = 1;
    private static final int DONE = 2;
    private static final int ABANDONED = 3;

    /**
     * The answer of a tool whose invocation did not finish in time, or that found one still running.
     */
    public static final class StillRunning extends RuntimeException {
        private final String report;

        private StillRunning(String report) {
            super(report, null, false, false);
            this.report = report;
        }

        /**
         * The tool's answer.
         */
        public String report() {
            return report;
        }
    }

    private final Executor executor;
    private final long timeoutMs;
    private final EventHistory history;
    private final Supplier<@Nullable SuspensionSession> currentSession;
    /**
     * The job running on each target thread (by uniqueID).
     */
    private final ConcurrentMap<Long, Job<?>> running = new ConcurrentHashMap<>();

    public InvocationRunner(Executor executor, long timeoutMs, EventHistory history,
                            Supplier<@Nullable SuspensionSession> currentSession) {
        this.executor = executor;
        this.timeoutMs = timeoutMs;
        this.history = history;
        this.currentSession = currentSession;
    }

    /**
     * The pool the jobs run on: daemon threads, created on demand, so a hung job never delays another.
     */
    public static ExecutorService newPool() {
        final AtomicInteger counter = new AtomicInteger();
        return Executors.newCachedThreadPool(r -> {
            final Thread thread = new Thread(r, "jdi-invoker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Runs {@code work} — which invokes on {@code thread} — and returns its result, or rethrows its
     * failure.
     *
     * @param thread the target thread the work invokes on; {@code null} when the work picks one itself
     * @param what   what the work does, for the replies and the late record (e.g. "evaluating x + 1")
     * @throws StillRunning when the work did not finish in time, or another job runs on {@code thread}
     */
    public <T> T run(@Nullable ThreadReference thread, String what, Callable<T> work) throws Exception {
        final long key = thread != null ? thread.uniqueID() : -1L;
        final Job<T> job = new Job<>(what, thread, work, currentSession.get());
        if (thread != null) {
            final Job<?> other = running.putIfAbsent(key, job);
            if (other != null) {
                throw new StillRunning(String.format("[STILL_RUNNING] %s on thread %s has been running in the target for "
                    + "%d s — wait for EVALUATION_COMPLETED_LATE in jdwp_get_events before starting another "
                    + "invocation on that thread.", other.what, nameOf(thread), other.elapsedMs() / 1000));
            }
        }
        try {
            executor.execute(() -> job.execute(key));
        } catch (RuntimeException e) {
            running.remove(key, job);
            throw e;
        }
        try {
            return job.outcome.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (job.state.compareAndSet(PENDING, ABANDONED)) {
                running.remove(key, job);
                throw new StillRunning(String.format("[STILL_RUNNING] %s did not start within %d s and was dropped "
                    + "— try again.", what, timeoutMs / 1000));
            }
            if (job.state.compareAndSet(RUNNING, ABANDONED)) {
                throw new StillRunning(stillRunningReport(job));
            }
            return unwrap(job);
        } catch (ExecutionException e) {
            throw rethrow(e);
        }
    }

    private static <T> T unwrap(Job<T> job) throws Exception {
        try {
            return job.outcome.get();
        } catch (ExecutionException e) {
            throw rethrow(e);
        }
    }

    private static Exception rethrow(ExecutionException e) {
        final Throwable cause = e.getCause();
        if (cause instanceof Exception exception) {
            return exception;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return e;
    }

    private String stillRunningReport(Job<?> job) {
        final ThreadReference thread = job.thread;
        final String on = thread != null ? " on thread " + nameOf(thread) : "";
        final String blocker = thread != null ? "; " + blockerOf(thread) : "";
        return String.format("[STILL_RUNNING] %s%s did not finish within %d s and is still running in the target%s. "
                + "When it ends, its result is recorded as EVALUATION_COMPLETED_LATE (jdwp_get_events)%s. Meanwhile: "
                + "jdwp_resume_thread releases a parked thread it waits for; jdwp_resume releases your stops (the "
                + "evaluating thread stays suspended until the evaluation ends); jdwp_reconnect ends everything.",
            job.what, on, timeoutMs / 1000, blocker,
            thread != null ? " and the thread can be evaluated on again" : "");
    }

    /**
     * What keeps {@code thread} from finishing its invocation, as far as JDI can tell — bounded: a
     * status read, and for a thread blocked on a monitor a brief suspension to ask which monitor and
     * who owns it (a balanced suspend and resume; the invocation pauses for its duration).
     */
    private static String blockerOf(ThreadReference thread) {
        try {
            final int status = thread.status();
            if (status != ThreadReference.THREAD_STATUS_MONITOR) {
                return "its state is " + statusName(status);
            }
            thread.suspend();
            try {
                final ObjectReference monitor = thread.currentContendedMonitor();
                final ThreadReference owner = monitor != null ? monitor.owningThread() : null;
                if (owner == null) {
                    return "it waits for a monitor";
                }
                return "it waits for a monitor held by thread " + nameOf(owner) + " (ID=" + owner.uniqueID() + ")"
                    + (owner.isSuspended() ? ", which is suspended" : "");
            } finally {
                thread.resume();
            }
        } catch (Exception e) {
            return "its state is unknown (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static String statusName(int status) {
        return switch (status) {
            case ThreadReference.THREAD_STATUS_RUNNING -> "running";
            case ThreadReference.THREAD_STATUS_SLEEPING -> "sleeping";
            case ThreadReference.THREAD_STATUS_WAIT -> "waiting (Object.wait or a park)";
            case ThreadReference.THREAD_STATUS_ZOMBIE -> "finished";
            case ThreadReference.THREAD_STATUS_NOT_STARTED -> "not started";
            default -> "unknown";
        };
    }

    private static String nameOf(ThreadReference thread) {
        try {
            return thread.name();
        } catch (RuntimeException e) {
            return "#" + thread.uniqueID();
        }
    }

    /**
     * One run of a tool's work.
     */
    private final class Job<T> {
        private final String what;
        @Nullable
        private final ThreadReference thread;
        private final Callable<T> work;
        @Nullable
        private final SuspensionSession session;
        private final long startedNanos = System.nanoTime();
        private final AtomicInteger state = new AtomicInteger(PENDING);
        private final CompletableFuture<T> outcome = new CompletableFuture<>();

        private Job(String what, @Nullable ThreadReference thread, Callable<T> work,
                    @Nullable SuspensionSession session) {
            this.what = what;
            this.thread = thread;
            this.work = work;
            this.session = session;
        }

        private long elapsedMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        }

        private void execute(long key) {
            if (!state.compareAndSet(PENDING, RUNNING)) {
                // The caller gave up before the job started: it must not run after the reply.
                return;
            }
            T value = null;
            Throwable failure = null;
            try {
                value = work.call();
            } catch (Throwable t) {
                failure = t;
            } finally {
                running.remove(key, this);
            }
            if (state.compareAndSet(RUNNING, DONE)) {
                if (failure != null) {
                    outcome.completeExceptionally(failure);
                } else {
                    outcome.complete(value);
                }
                return;
            }
            recordLate(value, failure);
        }

        private void recordLate(@Nullable T value, @Nullable Throwable failure) {
            final SuspensionSession current = currentSession.get();
            if (session == null || session != current || session.isClosed()) {
                // Its connection ended: nothing of it may reach the next one.
                log.info("[Invoker] {} finished after the connection it started on had ended", what);
                return;
            }
            final String result = failure != null
                ? "failed: " + (failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName())
                : "returned " + truncate(String.valueOf(value));
            history.record(new EventHistory.DebugEvent("EVALUATION_COMPLETED_LATE",
                String.format("%s%s finished after %d s and %s", what,
                    thread != null ? " on thread " + nameOf(thread) : "", elapsedMs() / 1000, result),
                Map.of("what", what, "elapsedMs", String.valueOf(elapsedMs()))));
        }

        private static String truncate(String text) {
            return text.length() <= 200 ? text : text.substring(0, 200) + '…';
        }
    }
}
