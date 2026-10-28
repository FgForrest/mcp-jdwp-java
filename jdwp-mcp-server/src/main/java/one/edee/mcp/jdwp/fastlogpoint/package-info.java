/**
 * Fast logpoints ({@code jdwp_set_fast_logpoint}): logpoints evaluated inside the target VM by an
 * injected probe class and read back from an in-target ring buffer, instead of suspending the
 * hitting thread on every hit. Rewrites the running application's bytecode, so the tool requires
 * explicit user consent.
 */
@NullMarked
package one.edee.mcp.jdwp.fastlogpoint;

import org.jspecify.annotations.NullMarked;
