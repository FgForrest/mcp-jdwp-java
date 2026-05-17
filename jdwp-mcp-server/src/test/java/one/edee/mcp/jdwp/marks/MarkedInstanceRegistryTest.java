package one.edee.mcp.jdwp.marks;

import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarkedInstanceRegistryTest {

    private MarkedInstanceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MarkedInstanceRegistry();
    }

    private ObjectReference mockRef(long id, String typeName) {
        return mockRef(id, typeName, false);
    }

    private ObjectReference mockRef(long id, String typeName, boolean collected) {
        final ObjectReference ref = mock(ObjectReference.class);
        when(ref.uniqueID()).thenReturn(id);
        when(ref.isCollected()).thenReturn(collected);
        final ReferenceType refType = mock(ReferenceType.class);
        when(refType.name()).thenReturn(typeName);
        when(ref.referenceType()).thenReturn(refType);
        return ref;
    }

    @Test
    @DisplayName("mark + get round-trips a labelled object and pins it by default")
    void markRoundTrip() {
        final ObjectReference ref = mockRef(42L, "com.example.Cart");
        registry.mark("cart_42", ref, true);

        verify(ref, times(1)).disableCollection();
        final MarkInfo info = registry.get("cart_42");
        assertThat(info).isNotNull();
        assertThat(info.label()).isEqualTo("cart_42");
        assertThat(info.objectId()).isEqualTo(42L);
        assertThat(info.typeName()).isEqualTo("com.example.Cart");
        assertThat(info.pinned()).isTrue();
        assertThat(info.collected()).isFalse();
    }

    @Test
    @DisplayName("mark with pin=false does NOT call disableCollection")
    void unpinnedMarkSkipsDisableCollection() {
        final ObjectReference ref = mockRef(7L, "com.example.X");
        registry.mark("x", ref, false);

        verify(ref, never()).disableCollection();
        final MarkInfo info = registry.get("x");
        assertThat(info).isNotNull();
        assertThat(info.pinned()).isFalse();
    }

    @Test
    @DisplayName("collision on label — rejected with descriptive IllegalStateException")
    void rejectsCollision() {
        registry.mark("session", mockRef(1L, "com.example.S"), false);
        assertThatThrownBy(() -> registry.mark("session", mockRef(2L, "com.example.S"), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("already-collected reference — rejected, never pinned")
    void rejectsCollectedReference() {
        final ObjectReference dead = mockRef(99L, "com.example.D", true);
        assertThatThrownBy(() -> registry.mark("d", dead, true))
            .isInstanceOf(ObjectCollectedException.class)
            .hasMessageContaining("already been collected");
        verify(dead, never()).disableCollection();
        assertThat(registry.get("d")).isNull();
    }

    @Test
    @DisplayName("disableCollection failure surfaces as IllegalStateException — registry slot stays empty")
    void surfacesPinFailure() {
        final ObjectReference ref = mockRef(5L, "com.example.X");
        doThrow(new UnsupportedOperationException("not supported")).when(ref).disableCollection();
        assertThatThrownBy(() -> registry.mark("x", ref, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot pin object 5");
        assertThat(registry.get("x")).isNull();
    }

    @Test
    @DisplayName("invalid label — rejected via the validator before any side-effect")
    void rejectsInvalidLabel() {
        final ObjectReference ref = mockRef(1L, "com.example.X");
        assertThatThrownBy(() -> registry.mark("42bad", ref, true))
            .isInstanceOf(IllegalArgumentException.class);
        verify(ref, never()).disableCollection();
    }

    @Test
    @DisplayName("unmark releases the pin via enableCollection and frees the slot")
    void unmarkUnpins() {
        final ObjectReference ref = mockRef(11L, "com.example.X");
        registry.mark("x", ref, true);

        assertThat(registry.unmark("x")).isTrue();
        verify(ref, times(1)).enableCollection();
        assertThat(registry.get("x")).isNull();
    }

    @Test
    @DisplayName("unmark on unknown label — returns false, no exception")
    void unmarkUnknown() {
        assertThat(registry.unmark("nope")).isFalse();
    }

    @Test
    @DisplayName("unmark on an unpinned mark — does NOT call enableCollection")
    void unmarkUnpinnedSkipsEnable() {
        final ObjectReference ref = mockRef(11L, "com.example.X");
        registry.mark("x", ref, false);

        assertThat(registry.unmark("x")).isTrue();
        verify(ref, never()).enableCollection();
    }

    @Test
    @DisplayName("rename preserves the underlying object and pin without re-disabling collection")
    void renamePreservesPin() {
        final ObjectReference ref = mockRef(3L, "com.example.X");
        registry.mark("old", ref, true);
        verify(ref, times(1)).disableCollection();

        registry.rename("old", "fresh");

        verify(ref, times(1)).disableCollection();
        verify(ref, never()).enableCollection();
        assertThat(registry.get("old")).isNull();
        final MarkInfo info = registry.get("fresh");
        assertThat(info).isNotNull();
        assertThat(info.pinned()).isTrue();
        assertThat(info.objectId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("rename to a colliding label — rejected")
    void renameCollisionRejected() {
        registry.mark("a", mockRef(1L, "T"), false);
        registry.mark("b", mockRef(2L, "T"), false);

        assertThatThrownBy(() -> registry.rename("a", "b"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already exists");
        assertThat(registry.get("a")).isNotNull();
        assertThat(registry.get("b")).isNotNull();
    }

    @Test
    @DisplayName("rename unknown source — rejected with IllegalArgumentException")
    void renameUnknownSource() {
        assertThatThrownBy(() -> registry.rename("ghost", "anything"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No mark");
    }

    @Test
    @DisplayName("rename to same label — no-op when the source exists")
    void renameSameLabelIsNoOp() {
        final ObjectReference ref = mockRef(1L, "T");
        registry.mark("x", ref, false);
        assertThatCode(() -> registry.rename("x", "x")).doesNotThrowAnyException();
        assertThat(registry.get("x")).isNotNull();
    }

    @Test
    @DisplayName("rename(label, label) on an unknown source still rejects with 'No mark'")
    void shouldErrorOnSameLabelRenameWhenSourceUnknown() {
        // The same-label shortcut must not bypass existence validation: an agent that asks to
        // rename a label that is not registered should always get the "No mark" error, regardless
        // of whether the requested new label happens to equal the (non-existent) old one. Otherwise
        // the call silently returns "renamed" and the agent assumes a mark now exists.
        assertThatThrownBy(() -> registry.rename("ghost", "ghost"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No mark");
    }

    @Test
    @DisplayName("buildBindings emits live entries keyed with the $ sigil and skips collected refs")
    void buildBindingsSkipsCollected() {
        final ObjectReference live = mockRef(1L, "T");
        final ObjectReference dead = mockRef(2L, "T");
        registry.mark("live", live, false);
        registry.mark("dead", dead, false);
        // Simulate the underlying object dying after mark time.
        when(dead.isCollected()).thenReturn(true);

        final Map<String, Value> bindings = registry.buildBindings();
        assertThat(bindings).containsOnlyKeys("$live");
        assertThat(bindings.get("$live")).isEqualTo(live);
    }

    @Test
    @DisplayName("buildBindings on empty registry returns the empty map without allocating")
    void buildBindingsEmpty() {
        assertThat(registry.buildBindings()).isEmpty();
    }

    @Test
    @DisplayName("list returns all entries including ones whose mirror has died")
    void listIncludesCollected() {
        final ObjectReference live = mockRef(1L, "T");
        final ObjectReference dead = mockRef(2L, "T");
        registry.mark("a", live, false);
        registry.mark("b", dead, false);
        when(dead.isCollected()).thenReturn(true);

        final List<MarkInfo> all = registry.list();
        assertThat(all).hasSize(2);
        assertThat(all).anySatisfy(m -> {
            assertThat(m.label()).isEqualTo("b");
            assertThat(m.collected()).isTrue();
        });
    }

    @Test
    @DisplayName("clearAll unpins every pinned mark and empties the registry")
    void clearAll() {
        final ObjectReference pinned = mockRef(1L, "T");
        final ObjectReference unpinned = mockRef(2L, "T");
        registry.mark("p", pinned, true);
        registry.mark("u", unpinned, false);

        registry.clearAll();

        verify(pinned, times(1)).enableCollection();
        verify(unpinned, never()).enableCollection();
        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.list()).isEmpty();
    }

    @Test
    @DisplayName("clearAll on an empty registry is a no-op")
    void clearAllEmpty() {
        assertThatCode(() -> registry.clearAll()).doesNotThrowAnyException();
        assertThat(registry.isEmpty()).isTrue();
    }
}
