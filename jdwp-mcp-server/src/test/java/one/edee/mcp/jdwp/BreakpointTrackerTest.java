package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.BreakpointTracker.EventKind;
import one.edee.mcp.jdwp.BreakpointTracker.LastBreakpoint;
import one.edee.mcp.jdwp.BreakpointTracker.PendingBreakpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BreakpointTrackerTest {

	private BreakpointTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
	}

	// ── Pending breakpoint tests (pure unit, no mocks) ──

	@Nested
	class PendingBreakpoints {

		@Test
		void shouldAssignIncrementingIds() {
			int id1 = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			int id2 = tracker.registerPendingBreakpoint("com.Bar", 20, 2, "ALL");
			int id3 = tracker.registerPendingBreakpoint("com.Baz", 30, 2, "ALL");

			assertThat(id1).isEqualTo(1);
			assertThat(id2).isEqualTo(2);
			assertThat(id3).isEqualTo(3);
		}

		@Test
		void shouldRegisterAndRetrievePendingBreakpoint() {
			int id = tracker.registerPendingBreakpoint("com.example.MyClass", 42, 1, "EVENT_THREAD");
			PendingBreakpoint pending = tracker.getPendingBreakpoint(id);

			assertThat(pending).isNotNull();
			assertThat(pending.getClassName()).isEqualTo("com.example.MyClass");
			assertThat(pending.getLineNumber()).isEqualTo(42);
			assertThat(pending.getSuspendPolicy()).isEqualTo(1);
			assertThat(pending.getSuspendPolicyLabel()).isEqualTo("EVENT_THREAD");
		}

		@Test
		void shouldRemovePendingBreakpoint() {
			int id = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			assertThat(tracker.removePendingBreakpoint(id)).isTrue();
			assertThat(tracker.getPendingBreakpoint(id)).isNull();
		}

		@Test
		void shouldReturnFalseWhenRemovingNonexistentPending() {
			assertThat(tracker.removePendingBreakpoint(9999)).isFalse();
		}

		@Test
		void shouldGetPendingBreakpointsForClass() {
			tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			tracker.registerPendingBreakpoint("com.Foo", 20, 2, "ALL");
			tracker.registerPendingBreakpoint("com.Bar", 30, 2, "ALL");

			List<Map.Entry<Integer, PendingBreakpoint>> result = tracker.getPendingBreakpointsForClass("com.Foo");
			assertThat(result).hasSize(2);
			assertThat(result).allSatisfy(entry ->
				assertThat(entry.getValue().getClassName()).isEqualTo("com.Foo")
			);
		}

		@Test
		void shouldReturnEmptyListForClassWithNoPendingBreakpoints() {
			assertThat(tracker.getPendingBreakpointsForClass("com.Unknown")).isEmpty();
		}

		@Test
		void shouldReturnAllPendingBreakpoints() {
			tracker.registerPendingBreakpoint("com.A", 1, 2, "ALL");
			tracker.registerPendingBreakpoint("com.B", 2, 2, "ALL");

			Map<Integer, PendingBreakpoint> all = tracker.getAllPendingBreakpoints();
			assertThat(all).hasSize(2);
			// Should be unmodifiable
			assertThatCode(() -> all.put(999, null))
				.isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		void shouldMarkPendingFailed() {
			int id = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			tracker.markPendingFailed(id, "No executable code at line 10");

			PendingBreakpoint pending = tracker.getPendingBreakpoint(id);
			assertThat(pending.getFailureReason()).isEqualTo("No executable code at line 10");
		}

		@Test
		void shouldNotFailWhenMarkingNonexistentPendingAsFailed() {
			assertThatCode(() -> tracker.markPendingFailed(9999, "some reason"))
				.doesNotThrowAnyException();
		}

		@Test
		void shouldPromotePendingToActiveBreakpoint() {
			int id = tracker.registerPendingBreakpoint("com.example.Deferred", 42, 2, "ALL");
			BreakpointRequest mockBp = mock(BreakpointRequest.class);

			tracker.promotePendingToActive(id, mockBp);

			assertThat(tracker.getAllPendingBreakpoints()).doesNotContainKey(id);
			assertThat(tracker.getPendingBreakpoint(id)).isNull();
			assertThat(tracker.getAllBreakpoints()).containsKey(id);
			assertThat(tracker.getBreakpoint(id)).isSameAs(mockBp);
		}

		@Test
		void shouldPreserveIdOnPromotion() {
			int id1 = tracker.registerPendingBreakpoint("com.example.First", 10, 2, "ALL");
			BreakpointRequest mockBp = mock(BreakpointRequest.class);
			tracker.promotePendingToActive(id1, mockBp);

			int id2 = tracker.registerPendingBreakpoint("com.example.Second", 20, 2, "ALL");

			assertThat(id1).isEqualTo(1);
			assertThat(id2).isEqualTo(2);
			assertThat(tracker.getBreakpoint(id1)).isSameAs(mockBp);
			assertThat(tracker.getPendingBreakpoint(id2)).isNotNull();
		}
	}

	// ── PendingBreakpoint inner class tests ──

	@Nested
	class PendingBreakpointInnerClass {

		@Test
		void shouldStoreAllConstructorParameters() {
			PendingBreakpoint pb = new PendingBreakpoint("com.Cls", 55, 1, "EVENT_THREAD");

			assertThat(pb.getClassName()).isEqualTo("com.Cls");
			assertThat(pb.getLineNumber()).isEqualTo(55);
			assertThat(pb.getSuspendPolicy()).isEqualTo(1);
			assertThat(pb.getSuspendPolicyLabel()).isEqualTo("EVENT_THREAD");
		}

		@Test
		void shouldHaveNullFailureReasonByDefault() {
			PendingBreakpoint pb = new PendingBreakpoint("com.Cls", 10, 2, "ALL");
			assertThat(pb.getFailureReason()).isNull();
		}

		@Test
		void shouldSetFailureReason() {
			PendingBreakpoint pb = new PendingBreakpoint("com.Cls", 10, 2, "ALL");
			pb.setFailureReason("class not loaded");
			assertThat(pb.getFailureReason()).isEqualTo("class not loaded");
		}
	}

	// ── Active breakpoint tests (require mock JDI interfaces) ──

	@Nested
	class ActiveBreakpoints {

		@Test
		void shouldRegisterAndRetrieveActiveBreakpoint() {
			BreakpointRequest mockBp = mock(BreakpointRequest.class);
			int id = tracker.registerBreakpoint(mockBp);

			assertThat(tracker.getBreakpoint(id)).isSameAs(mockBp);
		}

		@Test
		void shouldFindIdByRequest() {
			BreakpointRequest mockBp = mock(BreakpointRequest.class);
			int id = tracker.registerBreakpoint(mockBp);

			assertThat(tracker.findIdByRequest(mockBp)).isEqualTo(id);
		}

		@Test
		void shouldReturnNullForUnknownRequest() {
			BreakpointRequest unknownBp = mock(BreakpointRequest.class);
			assertThat(tracker.findIdByRequest(unknownBp)).isNull();
		}

		@Test
		void shouldReturnAllActiveBreakpoints() {
			BreakpointRequest bp1 = mock(BreakpointRequest.class);
			BreakpointRequest bp2 = mock(BreakpointRequest.class);
			tracker.registerBreakpoint(bp1);
			tracker.registerBreakpoint(bp2);

			Map<Integer, BreakpointRequest> all = tracker.getAllBreakpoints();
			assertThat(all).hasSize(2);
			// Should be unmodifiable
			assertThatCode(() -> all.put(999, null))
				.isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		void shouldUnregisterByRequest() {
			BreakpointRequest mockBp = mock(BreakpointRequest.class);
			tracker.registerBreakpoint(mockBp);

			tracker.unregisterByRequest(mockBp);

			assertThat(tracker.getAllBreakpoints()).isEmpty();
		}

		@Test
		void shouldNotUnregisterDifferentRequest() {
			BreakpointRequest bpA = mock(BreakpointRequest.class);
			BreakpointRequest bpB = mock(BreakpointRequest.class);
			int idA = tracker.registerBreakpoint(bpA);

			tracker.unregisterByRequest(bpB);

			assertThat(tracker.getAllBreakpoints()).hasSize(1);
			assertThat(tracker.getBreakpoint(idA)).isSameAs(bpA);
		}

		@Test
		void shouldRemoveActiveBreakpointById() {
			BreakpointRequest mockBp = mock(BreakpointRequest.class);
			VirtualMachine mockVm = mock(VirtualMachine.class);
			EventRequestManager mockErm = mock(EventRequestManager.class);
			when(mockBp.virtualMachine()).thenReturn(mockVm);
			when(mockVm.eventRequestManager()).thenReturn(mockErm);

			int id = tracker.registerBreakpoint(mockBp);

			boolean removed = tracker.removeBreakpoint(id);

			assertThat(removed).isTrue();
			assertThat(tracker.getBreakpoint(id)).isNull();
			assertThat(tracker.getAllBreakpoints()).isEmpty();
			verify(mockErm).deleteEventRequest(mockBp);
		}

		@Test
		void shouldReturnFalseWhenRemovingNonexistentBreakpoint() {
			assertThat(tracker.removeBreakpoint(9999)).isFalse();
		}

		@Test
		void shouldFallToPendingWhenActiveNotFound() {
			int id = tracker.registerPendingBreakpoint("com.example.Pending", 15, 2, "ALL");

			boolean removed = tracker.removeBreakpoint(id);

			assertThat(removed).isTrue();
			assertThat(tracker.getPendingBreakpoint(id)).isNull();
			assertThat(tracker.getAllPendingBreakpoints()).isEmpty();
		}
	}

	// ── Thread tracking ──

	@Nested
	class ThreadTracking {

		@Test
		void shouldSetAndGetLastBreakpointThread() {
			ThreadReference mockThread = mock(ThreadReference.class);
			tracker.setLastBreakpointThread(mockThread, 7);

			assertThat(tracker.getLastBreakpointThread()).isSameAs(mockThread);
			assertThat(tracker.getLastBreakpointId()).isEqualTo(7);
		}

		@Test
		void shouldReturnNullThreadByDefault() {
			assertThat(tracker.getLastBreakpointThread()).isNull();
			assertThat(tracker.getLastBreakpointId()).isNull();
		}

		@Test
		void shouldDefaultLastBreakpointKindToBreakpointForTwoArgConstructor() {
			ThreadReference mockThread = mock(ThreadReference.class);
			tracker.setLastBreakpointThread(mockThread, 5);

			LastBreakpoint snapshot = tracker.getLastBreakpoint();
			assertThat(snapshot).isNotNull();
			assertThat(snapshot.kind()).isEqualTo(EventKind.BREAKPOINT);
			assertThat(snapshot.id()).isEqualTo(5);
		}

		@Test
		void shouldAutoClassifyMinusOneSentinelAsExceptionKind() {
			ThreadReference mockThread = mock(ThreadReference.class);
			LastBreakpoint snapshot = new LastBreakpoint(mockThread, -1);

			// The 2-arg back-compat constructor maps id=-1 → kind=EXCEPTION; the canonical
			// constructor then normalises the id to null (EXCEPTION snapshots carry no BP id).
			assertThat(snapshot.kind()).isEqualTo(EventKind.EXCEPTION);
			assertThat(snapshot.id()).isNull();
		}

		@Test
		void shouldDefaultKindToBreakpointWhenIdIsNull() {
			ThreadReference mockThread = mock(ThreadReference.class);
			LastBreakpoint snapshot = new LastBreakpoint(mockThread, null);

			assertThat(snapshot.kind()).isEqualTo(EventKind.BREAKPOINT);
		}

		@Test
		void shouldRecordStepKindViaSetLastSuspendingEvent() {
			ThreadReference mockThread = mock(ThreadReference.class);
			tracker.setLastSuspendingEvent(mockThread, EventKind.STEP);

			LastBreakpoint snapshot = tracker.getLastBreakpoint();
			assertThat(snapshot).isNotNull();
			assertThat(snapshot.kind()).isEqualTo(EventKind.STEP);
			assertThat(snapshot.id()).isNull();
			assertThat(snapshot.thread()).isSameAs(mockThread);
		}

		@Test
		void shouldRecordExceptionKindViaSetLastSuspendingEvent() {
			ThreadReference mockThread = mock(ThreadReference.class);
			tracker.setLastSuspendingEvent(mockThread, EventKind.EXCEPTION);

			LastBreakpoint snapshot = tracker.getLastBreakpoint();
			assertThat(snapshot).isNotNull();
			assertThat(snapshot.kind()).isEqualTo(EventKind.EXCEPTION);
			assertThat(snapshot.id()).isNull();
			assertThat(snapshot.thread()).isSameAs(mockThread);
		}
	}

	// ── Reset ──

	@Nested
	class Reset {

		@Test
		void shouldResetAllState() {
			// Populate some state
			BreakpointRequest mockBp = mock(BreakpointRequest.class);
			tracker.registerBreakpoint(mockBp);
			tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			tracker.setLastBreakpointThread(mock(ThreadReference.class), 1);

			tracker.reset();

			assertThat(tracker.getAllBreakpoints()).isEmpty();
			assertThat(tracker.getAllPendingBreakpoints()).isEmpty();
			assertThat(tracker.getLastBreakpointThread()).isNull();
			assertThat(tracker.getLastBreakpointId()).isNull();

			// Counter should reset — next ID should be 1 again
			int nextId = tracker.registerPendingBreakpoint("com.New", 1, 2, "ALL");
			assertThat(nextId).isEqualTo(1);
		}
	}
}
