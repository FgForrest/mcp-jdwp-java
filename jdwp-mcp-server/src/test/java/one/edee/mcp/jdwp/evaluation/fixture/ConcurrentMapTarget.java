package one.edee.mcp.jdwp.evaluation.fixture;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Supplies real map layouts and holds a transfer partway through a resize. */
public final class ConcurrentMapTarget {
    static final ConcurrentHashMap<String, Integer> SMALL = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Collision, Integer> TREE = new ConcurrentHashMap<>(64);
    static final ConcurrentHashMap<String, Integer> EMPTY = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Integer, Integer> LARGE = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Integer, Integer> RESIZING = new ConcurrentHashMap<>(32);

    /** Fills the maps and pauses at a debugger stop while the resize is blocked. */
    public static void main(String[] args) throws InterruptedException {
        SMALL.put("a", 1);
        SMALL.put("b", 2);
        for (int i = 0; i < 12; i++) {
            TREE.put(new Collision(i), i);
        }
        for (int i = 0; i < 57; i++) {
            LARGE.put(i, i);
        }
        for (int i = 0; i < 46; i++) {
            RESIZING.put(i, i);
        }
        RESIZING.put(63, 63);

        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> RESIZING.computeIfPresent(0, (key, value) -> {
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return value;
        }), "bin-holder");
        final Thread resizer = new Thread(() -> RESIZING.put(46, 46), "resizer");
        holder.start();
        try {
            if (!locked.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("bin holder did not start");
            }
            // The 48th insert triggers transfer from bin 63 down to bin 0, which the holder owns.
            resizer.start();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (resizer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            if (resizer.getState() != Thread.State.BLOCKED) {
                throw new IllegalStateException("resizer did not block on bin zero");
            }
            inspect();
        } finally {
            release.countDown();
            holder.join();
            resizer.join();
        }
    }

    /** Breakpoint site where all maps are ready to inspect. */
    private static void inspect() {}

    /** Gives distinct keys the same hash so the bin grows into a tree. */
    private record Collision(int id) {
        /** Returns the same hash for all fixture keys. */
        @Override
        public int hashCode() {
            return 0;
        }
    }
}
