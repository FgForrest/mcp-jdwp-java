package one.edee.mcp.jdwp.fastlogpoint.fixture;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Target program for {@code FastLogpointLiveTest}, run in a forked JVM with a JDWP agent. Worker
 * threads call {@link #compute} and {@link #other} in a tight loop until standard input closes; a
 * {@code lock-holder} thread holds {@link #LOCK} nearly all the time. The test locates lines by the
 * {@code // MARK:} comments, so the code can move without breaking it.
 */
public class FastLogpointTarget {
    /**
     * Total {@link #compute} calls — read by the test over JDWP to measure throughput.
     */
    static final AtomicLong CALLS = new AtomicLong();
    /**
     * Held by the {@code lock-holder} thread, which sleeps while holding it: a monitor an evaluation
     * can wait for.
     */
    public static final Object LOCK = new Object();

    /**
     * Package-private so the probe — defined in this package — can read it by its bare name.
     */
    int base = 7;

    public static void main(String[] args) throws Exception {
        final int threads = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        final FastLogpointTarget target = new FastLogpointTarget();
        for (int t = 0; t < threads; t++) {
            final Thread worker = new Thread(() -> {
                int i = 0;
                while (true) {
                    target.compute(i);
                    target.other(i);
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
        // Runs until the test closes our standard input.
        while (System.in.read() >= 0) {
            // keep reading
        }
    }

    public int compute(int i) {
        final int doubled = i * 2;
        final String label = "v" + doubled;
        final int result = doubled + base + label.length(); // MARK: probe
        CALLS.incrementAndGet();
        return result;
    }

    public int other(int i) {
        return i + base; // MARK: other
    }
}
