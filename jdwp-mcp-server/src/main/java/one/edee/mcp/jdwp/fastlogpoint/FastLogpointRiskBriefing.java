package one.edee.mcp.jdwp.fastlogpoint;

import org.jspecify.annotations.Nullable;

/**
 * The consent gate's response: what {@code jdwp_set_fast_logpoint} returns when it is called
 * without {@code userApproved=true}. Nothing is armed. The briefing tells the agent to relay the
 * risks to the user word for word and to call again only after the user has agreed — the tool
 * rewrites the running application's bytecode, so the decision belongs to the human, not the agent.
 */
public final class FastLogpointRiskBriefing {

    private FastLogpointRiskBriefing() {
    }

    /**
     * Renders the briefing for one target.
     *
     * @param targetVm short description of the attached VM, or {@code null} when not connected
     */
    public static String render(String className, int lineNumber, String expression, @Nullable String condition,
                                @Nullable String targetVm) {
        return "[CONSENT REQUIRED] Nothing was armed.\n"
            + "jdwp_set_fast_logpoint rewrites the bytecode of the running application. Relay the briefing "
            + "below to the user word for word and ask for explicit approval. Only after the user has agreed "
            + "in this conversation, call jdwp_set_fast_logpoint again with the same arguments and "
            + "userApproved=true. Never infer consent, and never reuse consent given for a different class, "
            + "line or target VM. If the user declines, use jdwp_set_logpoint instead.\n"
            + "\n"
            + "── Fast logpoint: risk briefing ──\n"
            + "Target: " + className + ':' + lineNumber
            + " — expression: " + expression
            + (condition != null ? " — condition: " + condition : "")
            + " — VM: " + (targetVm != null ? targetVm : "the VM attached when the logpoint is set") + '\n'
            + "A fast logpoint evaluates the expression inside the target JVM instead of pausing the thread on "
            + "every hit. To do that it changes the running application:\n"
            + "1. It rewrites the class's bytecode. The change stays until it is reverted by clearing the "
            + "logpoint, jdwp_reset, jdwp_disconnect or a clean MCP server shutdown. If the MCP server crashes "
            + "or the connection drops without a clean disarm, the instrumentation stays in the target JVM — "
            + "detaching the debugger does not revert class redefinitions — and keeps evaluating. The next "
            + "debugging session lists it and can switch it off; restoring the class then needs the user's "
            + "approval again, and is impossible if the class was changed meanwhile (until the JVM restarts). "
            + "The probe keeps the class's original bytes and the expression text in the target's memory. A kill "
            + "switch stops the probe from evaluating anything, but the injected call itself remains.\n"
            + "2. The expression and condition run on the application's own threads. Slow, blocking or "
            + "side-effecting expressions directly change its latency and behaviour. Exceptions are caught and "
            + "logged; infinite loops and deadlocks are not.\n"
            + "3. Changes made by other bytecode agents (JaCoCo, the Mockito inline mock maker, Hibernate or "
            + "Spring enhancers, APM agents) could be lost. Arming is refused when the live bytecode differs "
            + "from the class file, which catches the usual cases.\n"
            + "4. Replacing the class deletes every JDWP breakpoint in it; the server re-creates the ones it "
            + "tracks after arming and after disarming.\n"
            + "5. Arming and disarming each cause a brief stop-the-world pause and throw away JIT-compiled code "
            + "that depends on the class. The first hit pauses one thread while the probe is installed; threads "
            + "already inside the method keep running the old code until they call it again.\n"
            + "6. Every logged hit allocates, and a shared sequence counter becomes a contention point under very "
            + "heavy concurrency (tens of nanoseconds per hit).\n"
            + "7. Same limits as expression evaluation: private members are not reachable, and the class needs "
            + "local-variable debug info (-g).\n"
            + "── end of briefing ──";
    }
}
