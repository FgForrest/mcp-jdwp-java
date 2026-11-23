package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.VirtualMachine;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Keeps target object mirrors alive for the lifetime of one debugger operation.
 * Each scope belongs to one debugger thread and releases its pins on {@link #close}.
 */
public final class PinnedMirrors implements AutoCloseable {
    /**
     * How often an object a GC collected before it could be pinned is created.
     */
    private static final int PIN_ATTEMPTS = 3;

    /** Object mirrors pinned by this scope, in acquisition order. */
    private final Set<ObjectReference> pinned = new LinkedHashSet<>(2);

    /**
     * A pinned {@code String} in the target ({@link VirtualMachine#mirrorOf(String)}).
     */
    public StringReference string(VirtualMachine vm, String value) {
        return pin(() -> vm.mirrorOf(value));
    }

    /**
     * Obtains a target mirror from a repeatable supplier and pins it until {@link #close}.
     *
     * @throws ObjectCollectedException when a GC collected the object on every attempt
     */
    public <T extends ObjectReference> T pin(Supplier<T> creation) {
        for (int attempt = 1; ; attempt++) {
            final T object = creation.get();
            try {
                return keep(object);
            } catch (ObjectCollectedException e) {
                // Retry only when collection wins the handoff between obtaining the mirror and pinning it.
                if (attempt == PIN_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /** Pins an existing mirror once, without retrying the operation that produced it. */
    public <T extends ObjectReference> T keep(T object) {
        if (!pinned.contains(object)) {
            try {
                object.disableCollection();
                pinned.add(object);
            } catch (UnsupportedOperationException ignored) {
                // The target VM does not support disabling collection.
            }
        }
        return object;
    }

    /**
     * Releases every pin. Quiet: an object of a disconnected VM needs no release.
     */
    @Override
    public void close() {
        for (ObjectReference object : this.pinned) {
            try {
                object.enableCollection();
            } catch (RuntimeException ignored) {
                // Collected or VM gone — nothing left to release.
            }
        }
        this.pinned.clear();
    }
}
