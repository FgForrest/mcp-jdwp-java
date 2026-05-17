/**
 * Marked instances: agent-assigned labels that pin a target-heap object and expose it to
 * expression evaluation as a synthetic {@code $label} binding. Mirrors the watchers package
 * layout but stores no breakpoint association — marks are independent of where they were
 * captured.
 */
@NullMarked
package one.edee.mcp.jdwp.marks;

import org.jspecify.annotations.NullMarked;
