package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.StringReference;
import com.sun.jdi.VMCannotBeModifiedException;
import com.sun.jdi.VirtualMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An object created through JDI is referenced by nothing in the target until it is pinned.
 * {@link PinnedMirrors} pins it right after creation, creates it again when a GC collected it first,
 * and releases every pin on {@link PinnedMirrors#close}.
 */
@DisplayName("PinnedMirrors")
class PinnedMirrorsTest {

	@Test
	@DisplayName("a string collected before it was pinned is created again; close releases the pin")
	void collectedStringIsCreatedAgain() {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final StringReference collected = mock(StringReference.class);
		doThrow(new ObjectCollectedException()).when(collected).disableCollection();
		final StringReference pinned = mock(StringReference.class);
		when(vm.mirrorOf("name")).thenReturn(collected, pinned);

		try (PinnedMirrors pins = new PinnedMirrors()) {
			assertThat(pins.string(vm, "name")).isSameAs(pinned);
			verify(pinned).disableCollection();
			verify(pinned, never()).enableCollection();
		}

		verify(pinned).enableCollection();
		verify(collected, never()).enableCollection();
	}

	@Test
	@DisplayName("an object collected on every attempt fails after three attempts")
	void objectCollectedOnEveryAttemptFails() {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final StringReference collected = mock(StringReference.class);
		doThrow(new ObjectCollectedException()).when(collected).disableCollection();
		when(vm.mirrorOf("name")).thenReturn(collected);

		try (PinnedMirrors pins = new PinnedMirrors()) {
			assertThatThrownBy(() -> pins.string(vm, "name")).isInstanceOf(ObjectCollectedException.class);
		}

		verify(vm, times(3)).mirrorOf("name");
		verify(collected, never()).enableCollection();
	}

	@Test
	@DisplayName("a VM that cannot pin hands the object out unpinned")
	void vmThatCannotPinHandsTheObjectOut() {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final StringReference unpinnable = mock(StringReference.class);
		doThrow(new VMCannotBeModifiedException()).when(unpinnable).disableCollection();
		when(vm.mirrorOf("name")).thenReturn(unpinnable);

		try (PinnedMirrors pins = new PinnedMirrors()) {
			assertThat(pins.string(vm, "name")).isSameAs(unpinnable);
		}

		verify(vm).mirrorOf("name");
		verify(unpinnable, never()).enableCollection();
	}

	@Test
	@DisplayName("close is quiet when releasing a pin fails, and releases the remaining pins")
	void closeIsQuiet() {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final StringReference gone = mock(StringReference.class);
		doThrow(new IllegalStateException("VM gone")).when(gone).enableCollection();
		final StringReference other = mock(StringReference.class);
		when(vm.mirrorOf("a")).thenReturn(gone);
		when(vm.mirrorOf("b")).thenReturn(other);

		final PinnedMirrors pins = new PinnedMirrors();
		pins.string(vm, "a");
		pins.string(vm, "b");
		pins.close();

		verify(other).enableCollection();
	}
}
