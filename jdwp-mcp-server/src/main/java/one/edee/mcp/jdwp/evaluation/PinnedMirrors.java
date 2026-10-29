package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Objects the debugger creates in the target for a call — a method argument, a binding — pinned
 * against garbage collection until {@link #close}. An object created through JDI is referenced by
 * nothing in the target until it is pinned, so a GC in between collects it, and the call it was
 * made for fails with {@link ObjectCollectedException}. Each object is pinned right after its
 * creation, and created again when a GC collected it first.
 * <p>
 * Not thread-safe: one instance serves one call on one thread, in a try-with-resources block.
 */
public final class PinnedMirrors implements AutoCloseable {
    /**
     * How often an object a GC collected before it could be pinned is created.
     */
    private static final int PIN_ATTEMPTS = 3;

    private final List<ObjectReference> pinned = new ArrayList<>(2);

    /**
     * A pinned {@code String} in the target ({@link VirtualMachine#mirrorOf(String)}).
     */
    public StringReference string(VirtualMachine vm, String value) {
        return pin(() -> vm.mirrorOf(value));
    }

    /**
     * Creates an object in the target with {@code creation} and pins it until {@link #close}.
     *
     * @throws ObjectCollectedException when a GC collected the object on every attempt
     */
    public <T extends ObjectReference> T pin(Supplier<T> creation) {
        for (int attempt = 1; ; attempt++) {
            final T object = creation.get();
            try {
                object.disableCollection();
                this.pinned.add(object);
                return object;
            } catch (UnsupportedOperationException e) {
                // The VM cannot pin: the object is used unpinned.
                return object;
            } catch (ObjectCollectedException e) {
                if (attempt == PIN_ATTEMPTS) {
                    throw e;
                }
            }
        }
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
