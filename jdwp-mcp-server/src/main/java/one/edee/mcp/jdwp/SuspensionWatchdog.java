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
 * Every {@link #PERIOD_MS} it looks at the dispatcher invocations of its session. A lease chains
 * host-side work (compiling, classpath discovery) and several method invocations in the target; the
 * thread is suspended between them and runs only inside one. So each invocation is aged from the
 * sample that first sees the thread running, and a sample that sees it suspended starts the next one
 * afresh (#47 § 2). One running for more than {@link #MIN_AGE_NANOS} whose thread is blocked on the
 * same monitor in two consecutive samples has the monitor's owner asked for — a balanced suspend of
 * the invoking thread around each query — and {@link SuspensionSession#unblock} releases that owner
 * if, under the session's monitor, it is held by such sets alone. Worker invocations are not watched: their tool's wait is bounded
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
     * When the current invocation of each lease (by token) was first seen running; dropped when the
     * thread is seen suspended between two invocations or the lease ends, so a later invocation
     * starts afresh.
     */
    private final Map<Object, Long> firstSeen = new HashMap<>();
    private final Map<Object, Integer> monitorSamples = new HashMap<>();
    /**
     * The monitor the latest blocked sample of each lease waited for: consecutive samples count only
     * while it is the same one.
     */
    private final Map<Object, ObjectReference> sampledMonitor = new HashMap<>();
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
            sampledMonitor.keySet().retainAll(live);
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
        if (isSuspended(invocation.thread())) {
            // Between two invocations of the chain: host-side work, or the next call not made yet.
            firstSeen.remove(token);
            forgetSamples(token);
            return;
        }
        final long since = firstSeen.computeIfAbsent(token, key -> now);
        if (now - since < MIN_AGE_NANOS) {
            return;
        }
        if (statusOf(invocation.thread()) != ThreadReference.THREAD_STATUS_MONITOR) {
            forgetSamples(token);
            return;
        }
        final ObjectReference monitor = contendedMonitor(invocation.thread());
        if (monitor == null) {
            forgetSamples(token);
            return;
        }
        // Samples straddling two invocations, or one invocation blocked twice, wait for other monitors.
        if (!monitor.equals(sampledMonitor.put(token, monitor))) {
            monitorSamples.put(token, 1);
            return;
        }
        if (monitorSamples.merge(token, 1, Integer::sum) < MONITOR_SAMPLES) {
            return;
        }
        final String released = unblock(token, invocation.thread());
        if (released != null) {
            forgetSamples(token);
            log.info("[Watchdog] Released {}", released);
        }
    }

    private void forgetSamples(Object token) {
        monitorSamples.remove(token);
        sampledMonitor.remove(token);
    }

    private static boolean isSuspended(ThreadReference thread) {
        try {
            return thread.isSuspended();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The monitor {@code thread} waits for, asked during a brief, balanced suspension (JDI answers only
     * for a suspended thread); {@code null} when none or unreadable.
     */
    @Nullable
    private static ObjectReference contendedMonitor(ThreadReference thread) {
        try {
            thread.suspend();
            try {
                return thread.currentContendedMonitor();
            } finally {
                thread.resume();
            }
        } catch (Exception e) {
            return null;
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
     * Asks which monitor the invoking {@code thread} is blocked on and who owns it, and lets the
     * session release that owner — all during one brief, balanced suspension of the thread (JDI
     * answers only for a suspended thread). Suspended, the thread cannot take the monitor and move
     * on before the decision, and the session confirms the owner still owns it once the owner is
     * proven parked. {@code null} when nothing was released.
     */
    @Nullable
    private String unblock(Object token, ThreadReference thread) {
        try {
            thread.suspend();
            try {
                final ObjectReference monitor = thread.currentContendedMonitor();
                final ThreadReference owner = monitor != null ? monitor.owningThread() : null;
                return owner != null ? session.unblock(token, owner, monitor) : null;
            } finally {
                thread.resume();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
