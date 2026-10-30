package one.edee.mcp.jdwp;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import one.edee.mcp.jdwp.evaluation.FastBreakpointProbeSource;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A suspended thread's stack as the tools number it. The leading frames of in-target probe classes
 * (fast logpoints and fast breakpoints of this session or an earlier one) are hidden: where a fast
 * breakpoint stopped, the physical frames above the user's code are the probe's {@code stop()},
 * {@code hit()} and possibly {@code __mcpError()}, and logical frame 0 is the user's frame that
 * called {@code hit()}. Probe frames deeper in the stack — a breakpoint inside a method the
 * condition calls — stay visible.
 * <p>
 * Every {@code frameIndex} a tool accepts and every {@code #N} it prints is a logical index. The
 * view keeps only the number of hidden frames, never a {@link StackFrame}: JDI invalidates frame
 * mirrors when a method is invoked on the thread, while the hidden prefix stays the same until the
 * thread runs again.
 */
final class FrameView {
    /**
     * Frames classified per round trip. A fast breakpoint's stop has two or three probe frames
     * ({@code stop}, {@code hit}, possibly {@code __mcpError}), so one fetch covers the usual prefix.
     */
    private static final int CHUNK = 4;

    private final ThreadReference thread;
    private final int hidden;
    @Nullable
    private final ReferenceType probeType;
    private final boolean fastStop;

    private FrameView(ThreadReference thread, int hidden, @Nullable ReferenceType probeType, boolean fastStop) {
        this.thread = thread;
        this.hidden = hidden;
        this.probeType = probeType;
        this.fastStop = fastStop;
    }

    /**
     * Reads the hidden prefix of {@code thread}'s stack.
     *
     * @throws IncompatibleThreadStateException when the thread is not suspended
     */
    static FrameView of(ThreadReference thread) throws IncompatibleThreadStateException {
        final int count = thread.frameCount();
        int hidden = 0;
        ReferenceType probeType = null;
        String topMethod = null;
        classify:
        while (hidden < count) {
            final List<StackFrame> chunk = thread.frames(hidden, Math.min(CHUNK, count - hidden));
            if (chunk.isEmpty()) {
                break;
            }
            for (StackFrame frame : chunk) {
                final Location location = frame.location();
                if (!FastLogpointService.isProbeType(location.declaringType())) {
                    break classify;
                }
                if (hidden == 0) {
                    topMethod = location.method().name();
                }
                probeType = location.declaringType();
                hidden++;
            }
        }
        if (hidden == 0 || hidden == count) {
            // No probe on top, or nothing but probe frames — then there is nothing to show instead.
            return new FrameView(thread, 0, null, false);
        }
        return new FrameView(thread, hidden, probeType, FastBreakpointProbeSource.STOP_METHOD.equals(topMethod));
    }

    /**
     * How many leading probe frames are hidden.
     */
    int hidden() {
        return hidden;
    }

    /**
     * The class of the hidden probe frames, or {@code null} when none is hidden.
     */
    @Nullable
    ReferenceType probeType() {
        return probeType;
    }

    /**
     * Whether the thread stands in a fast breakpoint probe's {@code stop()} — a fast breakpoint's
     * stop — rather than somewhere else inside a probe (suspended by hand, say).
     */
    boolean isFastStop() {
        return fastStop;
    }

    /**
     * Number of logical frames.
     */
    int count() throws IncompatibleThreadStateException {
        return thread.frameCount() - hidden;
    }

    /**
     * Logical frame {@code index}, fetched fresh.
     *
     * @throws IndexOutOfBoundsException when {@code index} is not a logical frame
     */
    StackFrame frame(int index) throws IncompatibleThreadStateException {
        if (index < 0) {
            throw new IndexOutOfBoundsException("frame index " + index + " is negative");
        }
        return thread.frame(hidden + index);
    }

    /**
     * Every logical frame, fetched fresh; element {@code i} is logical frame {@code i}.
     */
    List<StackFrame> frames() throws IncompatibleThreadStateException {
        // A view hides frames only when at least one user frame follows them.
        return hidden == 0 ? thread.frames() : thread.frames(hidden, thread.frameCount() - hidden);
    }

    /**
     * A line for stack listings that says what is hidden, or {@code ""} when nothing is.
     */
    String hiddenNote() {
        if (hidden == 0) {
            return "";
        }
        final String probe = probeType != null ? probeType.name() : "an in-target probe";
        return fastStop
            ? String.format("(%d frame(s) of fast-breakpoint probe %s hidden — frame #0 is where the breakpoint "
                + "stopped; a step from here first lands on that line, before its code)%n", hidden, probe)
            : String.format("(%d frame(s) of in-target probe %s hidden — the thread is suspended inside the probe, "
                + "frame #0 is its caller; changing a local there does not change what the probe already copied)%n",
                hidden, probe);
    }
}
