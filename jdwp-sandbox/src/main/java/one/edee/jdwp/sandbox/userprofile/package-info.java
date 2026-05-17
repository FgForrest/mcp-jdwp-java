/**
 * Scenario 7: The Field That Lies — a "read-only" formatter that secretly mutates a field on the
 * object it was handed. The mutation is buried in a private helper of an unrelated class, so a
 * line breakpoint requires knowing where to look. A field-modification watchpoint on
 * {@code UserProfile.displayName} catches the write at its actual source in one step.
 */
package one.edee.jdwp.sandbox.userprofile;
