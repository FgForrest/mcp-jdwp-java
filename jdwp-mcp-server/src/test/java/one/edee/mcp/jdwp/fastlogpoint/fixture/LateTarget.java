package one.edee.mcp.jdwp.fastlogpoint.fixture;

/**
 * Loaded only when {@link FastLogpointTarget} reads {@code L} from standard input — a class the live
 * tests set fast probes on before the target has loaded it. {@link #start} runs {@link #step} in a
 * tight loop on a daemon thread. The test locates lines by the {@code // MARK:} comments.
 */
public final class LateTarget {

    private LateTarget() {
    }

    public static void start() {
        final Thread worker = new Thread(() -> {
            int n = 0;
            while (true) {
                step(n++);
            }
        }, "late-worker");
        worker.setDaemon(true);
        worker.start();
    }

    static int step(int n) {
        final int squared = n * n;
        return squared + 1; // MARK: late
    }
}
