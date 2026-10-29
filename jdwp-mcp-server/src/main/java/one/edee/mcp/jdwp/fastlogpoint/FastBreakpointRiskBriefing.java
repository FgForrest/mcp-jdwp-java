package one.edee.mcp.jdwp.fastlogpoint;

import org.jspecify.annotations.Nullable;

/**
 * The consent gate's response: what {@code jdwp_set_fast_breakpoint} returns when it is called
 * without {@code userApproved=true}. Nothing is armed. Same protocol as
 * {@link FastLogpointRiskBriefing}: the agent relays the risks to the user word for word and calls
 * again only after the user has agreed, because the tool rewrites the running application's bytecode.
 */
public final class FastBreakpointRiskBriefing {

    private FastBreakpointRiskBriefing() {
    }

    /**
     * Renders the briefing for one target.
     *
     * @param suspendPolicy the requested policy as the user wrote it ("all" or "thread")
     * @param targetVm      short description of the attached VM, or {@code null} when not connected
     */
    public static String render(String className, int lineNumber, String condition, String suspendPolicy,
                                boolean stopOnce, @Nullable String targetVm) {
        final boolean all = "all".equals(suspendPolicy);
        return "[CONSENT REQUIRED] Nothing was armed.\n"
            + "jdwp_set_fast_breakpoint rewrites the bytecode of the running application. Relay the briefing "
            + "below to the user word for word and ask for explicit approval. Only after the user has agreed "
            + "in this conversation, call jdwp_set_fast_breakpoint again with the same arguments and "
            + "userApproved=true. Never infer consent, and never reuse consent given for a different class, "
            + "line or target VM. If the user declines, use jdwp_set_breakpoint with a condition instead.\n"
            + "\n"
            + "── Fast breakpoint: risk briefing ──\n"
            + "Target: " + className + ':' + lineNumber
            + " — condition: " + condition
            + " — " + FastLogpointService.modeLabel(stopOnce)
            + " — suspendPolicy: " + suspendPolicy
            + " — VM: " + (targetVm != null ? targetVm : "the VM attached when the breakpoint is set") + '\n'
            + "A fast breakpoint evaluates its condition inside the target JVM, so hits that do not meet it never "
            + "involve the debugger. To do that it changes the running application:\n"
            + "1. It rewrites the class's bytecode. The change stays until it is reverted by clearing the "
            + "breakpoint, jdwp_reset, jdwp_disconnect or a clean MCP server shutdown. If the MCP server crashes "
            + "or the connection drops without a clean disarm, the instrumentation stays in the target JVM — "
            + "detaching the debugger does not revert class redefinitions — and keeps evaluating the condition, "
            + "although nothing stops any more. The next debugging session lists it and can switch it off; "
            + "restoring the class then needs the user's approval again, and is impossible if the class was "
            + "changed meanwhile (until the JVM restarts). The probe keeps the class's original bytes and the "
            + "condition text in the target's memory.\n"
            + "2. The first hit of the line is an ordinary debugger stop that evaluates the condition. If it "
            + "holds, that hit is the stop. Otherwise the probe is compiled and installed while "
            + (all ? "every thread is paused" : "that thread is paused")
            + " — a one-time pause of a second or more. "
            + (all ? "With a class loader that is not parallel-capable, installing can stall if a paused thread "
            + "holds that loader's lock; the application then stays paused until the debugger disconnects. " : "")
            + "Threads already inside the method keep running the old code, without the condition, until they "
            + "call it again.\n"
            + "3. The condition runs unsynchronized on every application thread that passes the line: it must be "
            + "thread-safe and free of side effects, and it may read stale values. A slow or blocking condition "
            + "directly changes the application's latency; infinite loops and deadlocks are not caught. Private "
            + "members are not reachable (locals, this and non-private members are), and the class needs "
            + "local-variable debug info (-g).\n"
            + "4. The thread that meets the condition stops at once"
            + (all ? ", but the other threads are paused a moment later (microseconds to milliseconds) and may "
            + "have moved past the state you want to see" : "; the other threads keep running")
            + ". "
            + (stopOnce
            ? "Only the first hit that meets the condition stops; calling the tool again with the same arguments "
            + "re-arms it."
            : "Every hit that meets the condition stops.")
            + "\n"
            + "5. A condition that throws or does not yield a boolean stops once, at its first failure; later "
            + "failures are only counted and recorded (jdwp_get_events), and each one allocates its error text.\n"
            + "6. While stopped, paused threads keep the locks they hold"
            + (all ? "" : "; under suspendPolicy=thread, other threads that need them block")
            + ". Clearing the breakpoint, or jdwp_reset, while a thread is stopped in the method restores the class "
            + "at once, and that thread's frame can then no longer show its local variables — resume first. On a "
            + "virtual thread, local variables of the stopped method can only be changed after one step (a JDK "
            + "limitation).\n"
            + "7. Changes made by other bytecode agents (JaCoCo, the Mockito inline mock maker, Hibernate or "
            + "Spring enhancers, APM agents) could be lost. Arming is refused when the live bytecode differs "
            + "from the class file, which catches the usual cases. Replacing the class deletes every JDWP "
            + "breakpoint in it; the server re-creates the ones it tracks. Arming and disarming each cause a brief "
            + "stop-the-world pause and throw away JIT-compiled code that depends on the class.\n"
            + "── end of briefing ──";
    }
}
