package one.edee.mcp.jdwp.fastlogpoint.fixture;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Target program for {@code FastLogpointLiveTest}, run in a forked JVM with a JDWP agent. Worker
 * threads call {@link #compute}, {@link #other} and {@link #visitDefault} until standard input closes; a
 * {@code lock-holder} thread holds {@link #LOCK} nearly all the time. The test locates lines by the
 * {@code // MARK:} comments, so the code can move without breaking it.
 */
public class FastLogpointTarget implements DefaultMethodTarget {
    /**
     * Total {@link #compute} calls — read by the test over JDWP to measure throughput.
     */
    static final AtomicLong CALLS = new AtomicLong();
    /** Virtual worker started on demand by the harness's V command. */
    static volatile Thread virtualWorker;
    /** JDI-controlled condition that lets tests trigger a stop after the probe has armed. */
    public static volatile boolean captureTestStop;
    /** Number of explicit collections completed in response to the test harness's GC command. */
    static volatile int captureTestCollections;
    /**
     * Held by the {@code lock-holder} thread, which sleeps while holding it: a monitor an evaluation
     * can wait for.
     */
    public static final Object LOCK = new Object();

    /**
     * Package-private so the probe — defined in this package — can read it by its bare name.
     */
    int base = 7;

    /**
     * Starts the workers, including calls into the default-method fixture, and handles test commands on
     * the main thread, including starting a virtual worker and explicit GC. Propagates failure to read a
     * command or load the deferred target.
     */
    public static void main(String[] args) throws Exception {
        final int threads = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        final FastLogpointTarget target = new FastLogpointTarget();
        for (int t = 0; t < threads; t++) {
            final Thread worker = new Thread(() -> {
                int i = 0;
                while (true) {
                    target.compute(i);
                    target.other(i);
                    target.visitDefault(i);
                    i++;
                }
            }, "worker-" + t);
            worker.setDaemon(true);
            worker.start();
        }
        final Thread holder = new Thread(() -> {
            while (true) {
                synchronized (LOCK) {
                    LockSupport.parkNanos(5_000_000L); // MARK: holding
                }
                LockSupport.parkNanos(1_000_000L);
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();
        System.out.println("TARGET READY");
        System.out.flush();
        // Runs until the test closes our standard input. "L" loads LateTarget — by name, so nothing
        // loads it earlier — and starts its worker. "V" starts a virtual worker, "X" exits through
        // System.exit, and "G" collects before acknowledging a capture lifetime test.
        int command;
        while ((command = System.in.read()) >= 0) {
            if (command == 'L') {
                Class.forName("one.edee.mcp.jdwp.fastlogpoint.fixture.LateTarget").getMethod("start").invoke(null);
            } else if (command == 'V') {
                virtualWorker = (Thread) Thread.class.getMethod("startVirtualThread", Runnable.class)
                    .invoke(null, (Runnable) () -> {
                        Thread.currentThread().setName("unlisted-virtual-worker");
                        for (;;) {
                            LockSupport.park();
                        }
                    });
            } else if (command == 'X') {
                System.exit(0);
            } else if (command == 'G') {
                System.gc();
                captureTestCollections++;
            }
        }
    }

    /** Returns the worker's computed result and exposes a local named {@code _this} at the probe line. */
    public int compute(int i) {
        final int doubled = i * 2;
        final int _this = i & 1;
        final String label = "v" + doubled; // MARK: label
        final int result = doubled + base + label.length() + _this - _this; // MARK: probe
        CALLS.incrementAndGet();
        return result;
    }

    public int other(int i) {
        return i + base; // MARK: other
    }
}
