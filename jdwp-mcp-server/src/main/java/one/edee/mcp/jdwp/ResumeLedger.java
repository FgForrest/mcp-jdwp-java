package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts the resumes the MCP tools issue, so that {@link JdiEventListener} never resumes an event set
 * whose suspension a tool has already spent. {@link EventSet#resume()} only decrements suspend counts
 * and cannot tell which stop it releases. Without this ledger, a set processed late releases a newer
 * stop of its thread: logpoint set A of thread T waits for the dispatcher, the user calls
 * {@code jdwp_resume}, T stops at a breakpoint, and A's resume lets T run on although the breakpoint
 * is reported.
 * <p>
 * Every resume a tool issues ({@code jdwp_resume}, {@code jdwp_resume_until_event},
 * {@code jdwp_resume_thread}, the step tools) goes through {@link #resume(VirtualMachine)} or
 * {@link #resume(ThreadReference)}, which count it. The listener takes a {@link Claim} when it routes a
 * set to its dispatcher and settles it in two windows:
 * <ul>
 *   <li><b>While the set waits</b>, its thread runs no MCP-driven invocation, because nobody knows of
 *       the stop yet. A tool resume that reaches the thread therefore spent the set's suspension:
 *       {@link #spent} says so and the set is never resumed. A SUSPEND_ALL set is spent by a VM resume
 *       and by a resume of its own thread alike. It never releases a suspension it cannot prove is its
 *       own; the next {@code jdwp_resume} takes up what it leaves.</li>
 *   <li><b>While the set is processed</b>, its thread may be inside {@code invokeMethod}. The invocation
 *       resumes the thread and suspends it again when it completes, so a tool resume meanwhile does not
 *       reach the thread, and the suspension is still owed. {@link #resume(EventSet, Claim)} therefore
 *       resumes a thread-policy set anyway; on a thread the tool did release, that is a no-op. A
 *       SUSPEND_ALL set after a VM resume resumes only its own thread, because the tool already
 *       released the others.</li>
 * </ul>
 * Not covered:
 * <ul>
 *   <li>a tool resume that lands after the target raised the event but before the listener routed it.
 *       The listener's per-thread {@code waiting} rule catches the resulting re-stop once that is
 *       routed;</li>
 *   <li>a {@code jdwp_resume_thread} of another thread while a SUSPEND_ALL set waits;</li>
 *   <li>a breakpoint set spent while it waited is still reported as a stop, although its thread
 *       runs.</li>
 * </ul>
 * <p>
 * Thread-safety: one monitor guards the counters and spans every counted or claimed resume, so a tool
 * resume never lands between a check and the resume it decides. The monitor is held only for those
 * JDWP calls, never across event processing; {@code jdwp_resume} must not wait behind a stalled
 * evaluation.
 */
@Service
public class ResumeLedger {

    /**
     * Resumes of the whole VM that tools have issued.
     */
    private long vmResumes;
    /**
     * Resumes of single threads that tools have issued, by {@link ThreadReference#uniqueID()}.
     */
    private final Map<Long, Long> threadResumes = new HashMap<>();

    /**
     * Resumes every thread of {@code vm} for a tool and counts it.
     */
    public synchronized void resume(VirtualMachine vm) {
        vmResumes++;
        vm.resume();
    }

    /**
     * Resumes {@code thread} for a tool and counts it.
     */
    public synchronized void resume(ThreadReference thread) {
        threadResumes.merge(thread.uniqueID(), 1L, Long::sum);
        thread.resume();
    }

    /**
     * Claims the suspension of a set the listener routes now. {@code thread} is the thread the set
     * suspended, or {@code null} for a set that suspends no thread of its own.
     */
    synchronized Claim claim(@Nullable ThreadReference thread) {
        return new Claim(thread, vmResumes, resumesOf(thread));
    }

    /**
     * Whether a tool resume reached the set of {@code claim} since it was claimed. Asked when
     * processing of the set starts, so a resume it reports came while the set waited.
     */
    synchronized boolean spent(Claim claim) {
        return vmResumes != claim.vmResumes() || resumesOf(claim.thread()) != claim.threadResumes();
    }

    /**
     * Resumes {@code eventSet}, which was not {@link #spent} when its processing started. Resumes the
     * whole set, unless the set suspended all threads and a tool resumed the VM while it was processed:
     * then it resumes only the set's own thread.
     */
    synchronized void resume(EventSet eventSet, Claim claim) {
        final ThreadReference thread = claim.thread();
        if (thread != null && vmResumes != claim.vmResumes()
            && eventSet.suspendPolicy() == EventRequest.SUSPEND_ALL) {
            thread.resume();
        } else {
            eventSet.resume();
        }
    }

    private long resumesOf(@Nullable ThreadReference thread) {
        return thread == null ? 0L : threadResumes.getOrDefault(thread.uniqueID(), 0L);
    }

    /**
     * The tool resumes counted when the listener routed a set.
     *
     * @param thread        the thread the set suspended, or {@code null} for a set that suspends none
     * @param vmResumes     VM resumes counted at that moment
     * @param threadResumes resumes of {@code thread} counted at that moment
     */
    record Claim(@Nullable ThreadReference thread, long vmResumes, long threadResumes) {
    }
}
