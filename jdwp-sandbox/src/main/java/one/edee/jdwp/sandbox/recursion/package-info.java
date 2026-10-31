/**
 * The Echo Chamber — recursive breakpoint hit during expression evaluation. A server check, not
 * one of the ten scored test flights.
 *
 * <p>Unlike the other sandbox scenarios, the production code in this package is correct.
 * The "break" is in the debugger interaction: without the reentrancy guard, evaluating an
 * expression from inside a breakpoint hit can re-enter the very line the breakpoint is on,
 * deadlocking the MCP server.
 *
 * <h2>Test flight script</h2>
 * <pre>
 *   # Launch the sandbox JVM in another shell, paused so the agent can attach.
 *   mvn -pl jdwp-sandbox test -Dtest=RecursiveCalculatorTest -DskipTests=false \
 *       -Dmaven.surefire.debug
 *
 *   # From the MCP client:
 *   jdwp_wait_for_attach()
 *   jdwp_set_breakpoint("one.edee.jdwp.sandbox.recursion.RecursiveCalculator", 22)
 *       # line 22 is "int left = compute(n - 1);" — confirm with jdwp_get_breakpoint_context
 *   jdwp_resume_until_event()
 *       # BP fires once inside compute(5)
 *   jdwp_evaluate_expression(threadId, "this.compute(3)")
 * </pre>
 *
 * <h2>Expected behaviour</h2>
 * <ul>
 *   <li><b>Pre-fix</b>: the {@code jdwp_evaluate_expression} call hangs until the client times
 *       out because the recursive breakpoint hit re-suspends the thread the outer
 *       {@code invokeMethod} is waiting on.</li>
 *   <li><b>Post-fix</b>: the call returns {@code 2}; {@code jdwp_get_events} shows
 *       {@code BREAKPOINT_SUPPRESSED} entries — one per recursive hit that happened during
 *       the evaluation. The original breakpoint is still armed and will fire again on the
 *       next natural hit.</li>
 * </ul>
 */
package one.edee.jdwp.sandbox.recursion;
