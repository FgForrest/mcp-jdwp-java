package one.edee.mcp.jdwp.fastlogpoint;

import one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource;
import org.jspecify.annotations.Nullable;

/**
 * Pure decoding logic for a fast-logpoint probe's ring buffer (layout: see
 * {@link FastLogpointProbeSource}). Kept free of JDI so the overrun and torn-slot rules can be
 * unit-tested directly.
 * <p>
 * The writer increments {@code SEQ} and then stores the entry, so a reader that sees
 * {@code SEQ = n} may find slot {@code n - 1} still holding an older entry (write in progress) or —
 * if writers lapped the buffer — a newer one (the entry it wanted was overwritten). The sequence
 * number embedded in every entry tells the three cases apart; see {@link #classify}.
 */
final class ProbeRing {

    private ProbeRing() {
    }

    /**
     * Parses one raw ring entry. Returns {@code null} for {@code null} (slot never written) or a
     * malformed entry.
     */
    @Nullable
    static Entry parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        final String[] parts = raw.split(String.valueOf(FastLogpointProbeSource.SEPARATOR), 6);
        if (parts.length != 6 || parts[3].length() != 1) {
            return null;
        }
        try {
            return new Entry(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                parts[3].charAt(0), parts[4], parts[5]);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * Classifies the slot that should hold entry {@code expectedSeq}.
     *
     * @return {@link SlotState#READY} when it holds that entry, {@link SlotState#OVERWRITTEN} when
     * a later lap already replaced it, {@link SlotState#PENDING} when it is empty, malformed, or
     * still holds an older entry (the writer has claimed the sequence number but not stored yet)
     */
    static SlotState classify(long expectedSeq, @Nullable Entry entry) {
        if (entry == null || entry.seq() < expectedSeq) {
            return SlotState.PENDING;
        }
        return entry.seq() == expectedSeq ? SlotState.READY : SlotState.OVERWRITTEN;
    }

    /**
     * Plans which sequence numbers a drain reads.
     *
     * @param nextSeq     first sequence number not yet consumed
     * @param writtenSeq  current value of the probe's {@code SEQ} (entries {@code < writtenSeq} exist)
     * @param bufferSize  ring capacity
     * @param maxIngest   most entries one drain may ingest (the event history's capacity); older
     *                    unread entries beyond it are skipped rather than read only to be evicted
     */
    static Window plan(long nextSeq, long writtenSeq, int bufferSize, int maxIngest) {
        if (writtenSeq <= nextSeq) {
            return new Window(nextSeq, nextSeq, 0, 0);
        }
        final long overrun = Math.max(0, writtenSeq - nextSeq - bufferSize);
        final long afterOverrun = nextSeq + overrun;
        final long skipped = Math.max(0, writtenSeq - afterOverrun - maxIngest);
        return new Window(afterOverrun + skipped, writtenSeq, overrun, skipped);
    }

    /**
     * One decoded ring entry.
     *
     * @param seq         sequence number (0-based, per probe)
     * @param epochMillis {@code System.currentTimeMillis()} in the target at the hit
     * @param nanoTime    {@code System.nanoTime()} in the target at the hit
     * @param kind        {@code V} value, {@code E} expression threw, {@code C} condition failed
     * @param thread      name of the hitting thread
     * @param value       rendered result or failure
     */
    record Entry(long seq, long epochMillis, long nanoTime, char kind, String thread, String value) {
    }

    /**
     * State of the slot expected to hold a given sequence number.
     */
    enum SlotState { READY, OVERWRITTEN, PENDING }

    /**
     * The sequence numbers one drain reads: {@code [from, to)}.
     *
     * @param overrun  entries lost because writers lapped the ring before they were read
     * @param skipped  entries still in the ring but skipped because they exceed one drain's ingest
     *                 limit
     */
    record Window(long from, long to, long overrun, long skipped) {
    }
}
