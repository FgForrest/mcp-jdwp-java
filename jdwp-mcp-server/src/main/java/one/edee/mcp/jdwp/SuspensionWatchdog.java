package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.ThreadReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Frees what a stuck dispatcher invocation waits for when nobody else can (issue #45, proposal § 19
 * A1). A condition, logpoint or arming evaluation runs on the dispatcher; when the invoked code
 * blocks on a monitor that a thread parked by a set still queued (or being processed) holds, the
 * dispatcher never gets to that set, and no tool releases a set before it is processed.
 * <p>
 * Every {@link #PERIOD_MS} it looks at the dispatcher invocations of its session. One watched for
 * more than {@link #MIN_AGE_NANOS} whose thread is blocked on a monitor in two consecutive samples
 * has the monitor's owner asked for — a balanced suspend of the invoking thread around the query —
 * and {@link SuspensionSession#unblock} releases that owner if, under the session's monitor, it is
 * held by such sets alone. Worker invocations are not watched: their tool's wait is bounded
 * ({@link InvocationRunner}). Spins, native waits and dependencies on a published stop are not
 * detected: {@code jdwp_resume} or {@code jdwp_reconnect} recover those.
 */
public final class SuspensionWatchdog {
    private static final Logger log = LoggerFactory.getLogger(SuspensionWatchdog.class);

    /**
     * How often the invocations are sampled.
     */
    static final long PERIOD_MS = 500;
    /**
     * How long an invocation runs before it is sampled.
     */
    static final long MIN_AGE_NANOS = TimeUnit.SECONDS.toNanos(3);
    /**
     * Consecutive samples blocked on a monitor before its owner is released.
     */
    static final int MONITOR_SAMPLES = 2;

    private final SuspensionSession session;
    private final LongSupplier clock;
    /**
     * When each invocation (by token) was first seen; samples of an ended one are dropped, so a
     * later invocation on the same thread starts afresh.
     */
    private final Map<Object, Long> firstSeen = new HashMap<>();
    private final Map<Object, Integer> monitorSamples = new HashMap<>();
    @Nullable
    private ScheduledExecutorService scheduler;

    SuspensionWatchdog(SuspensionSession session, LongSupplier clock) {
        this.session = session;
        this.clock = clock;
    }

    /**
     * Starts a watchdog for {@code session} on a daemon thread; it stops by itself once the session
     * is closed.
     */
    public static SuspensionWatchdog start(SuspensionSession session) {
        final SuspensionWatchdog watchdog = new SuspensionWatchdog(session, System::nanoTime);
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "jdi-suspension-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduler = scheduler;
        scheduler.scheduleWithFixedDelay(watchdog::tick, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        return watchdog;
    }

    /**
     * Stops the sampling.
     */
    public void stop() {
        final ScheduledExecutorService running = scheduler;
        scheduler = null;
        if (running != null) {
            running.shutdownNow();
        }
    }

    /**
     * One round of samples. Never throws — an exception would end the schedule.
     */
    void tick() {
        try {
            if (session.isClosed()) {
                stop();
                return;
            }
            final List<SuspensionSession.Invocation> invocations = session.dispatcherInvocations();
            final Set<Object> live = new HashSet<>();
            for (SuspensionSession.Invocation invocation : invocations) {
                live.add(invocation.token());
            }
            firstSeen.keySet().retainAll(live);
            monitorSamples.keySet().retainAll(live);
            final long now = clock.getAsLong();
            for (SuspensionSession.Invocation invocation : invocations) {
                sample(invocation, now);
            }
        } catch (RuntimeException e) {
            log.debug("[Watchdog] Sampling failed: {}", e.toString());
        }
    }

    private void sample(SuspensionSession.Invocation invocation, long now) {
        final Object token = invocation.token();
        final long since = firstSeen.computeIfAbsent(token, key -> now);
        if (now - since < MIN_AGE_NANOS) {
            return;
        }
        if (statusOf(invocation.thread()) != ThreadReference.THREAD_STATUS_MONITOR) {
            monitorSamples.remove(token);
            return;
        }
        if (monitorSamples.merge(token, 1, Integer::sum) < MONITOR_SAMPLES) {
            return;
        }
        final ThreadReference owner = monitorOwner(invocation.thread());
        if (owner == null) {
            return;
        }
        final String released = session.unblock(token, owner);
        if (released != null) {
            monitorSamples.remove(token);
            log.info("[Watchdog] Released {}", released);
        }
    }

    private static int statusOf(ThreadReference thread) {
        try {
            return thread.status();
        } catch (RuntimeException e) {
            return ThreadReference.THREAD_STATUS_UNKNOWN;
        }
    }

    /**
     * The owner of the monitor {@code thread} is blocked on, asked for during a brief, balanced
     * suspension of the thread (JDI answers only for a suspended thread); {@code null} when unknown.
     */
    @Nullable
    private static ThreadReference monitorOwner(ThreadReference thread) {
        try {
            thread.suspend();
            try {
                final ObjectReference monitor = thread.currentContendedMonitor();
                return monitor != null ? monitor.owningThread() : null;
            } finally {
                thread.resume();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
