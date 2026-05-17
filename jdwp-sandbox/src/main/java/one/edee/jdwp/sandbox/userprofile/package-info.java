/**
 * Scenario 7: The Field That Lies — a "read-only" formatter mutates a private field on the object
 * it was handed, but routes the write through {@link java.lang.reflect.Field#set} so the public
 * setter is never called and source-level call-site analysis comes up empty. A line breakpoint on
 * {@code UserProfile.setDisplayName} never fires. A field-modification watchpoint on
 * {@code UserProfile.displayName} catches the write at its real source — reflective stores look
 * just like regular stores to the JDI watchpoint machinery.
 */
package one.edee.jdwp.sandbox.userprofile;
