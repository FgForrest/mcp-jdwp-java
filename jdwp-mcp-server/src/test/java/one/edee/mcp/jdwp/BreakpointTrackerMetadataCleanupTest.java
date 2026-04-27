package one.edee.mcp.jdwp;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that breakpoint metadata (conditions, logpoint expressions) and ClassPrepareRequest
 * registrations are correctly cleaned up when breakpoints are removed. Complements
 * {@link BreakpointTrackerExceptionAndMetadataTest} with focused cleanup-lifecycle scenarios.
 */
class BreakpointTrackerMetadataCleanupTest {

	private BreakpointTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
	}

	@Nested
	@DisplayName("Condition cleanup on removal")
	class ConditionCleanup {

		@Test
		@DisplayName("Removing a pending BP clears its condition")
		void shouldClearConditionAfterRemovingPendingBreakpoint() {
			int id = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			tracker.setCondition(id, "x > 5");

			tracker.removeBreakpoint(id);

			assertThat(tracker.getCondition(id)).isNull();
		}

		@Test
		@DisplayName("Removing an active BP clears its condition")
		void shouldClearConditionAfterRemovingActiveBreakpoint() {
			BreakpointRequest bp = mockBreakpointWithVm();
			int id = tracker.registerBreakpoint(bp);
			tracker.setCondition(id, "x > 5");

			tracker.removeBreakpoint(id);

			assertThat(tracker.getCondition(id)).isNull();
		}
	}

	@Nested
	@DisplayName("Logpoint cleanup on removal")
	class LogpointCleanup {

		@Test
		@DisplayName("Removing a pending BP clears its logpoint expression")
		void shouldClearLogpointAfterRemovingPendingBreakpoint() {
			int id = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			tracker.setLogpointExpression(id, "\"x=\" + x");

			tracker.removeBreakpoint(id);

			assertThat(tracker.getLogpointExpression(id)).isNull();
		}

		@Test
		@DisplayName("Removing an active BP clears its logpoint expression")
		void shouldClearLogpointAfterRemovingActiveBreakpoint() {
			BreakpointRequest bp = mockBreakpointWithVm();
			int id = tracker.registerBreakpoint(bp);
			tracker.setLogpointExpression(id, "\"y=\" + y");

			tracker.removeBreakpoint(id);

			assertThat(tracker.getLogpointExpression(id)).isNull();
		}
	}

	@Nested
	@DisplayName("setLogpointExpression with null")
	class NullLogpoint {

		@Test
		@DisplayName("Setting logpoint to null creates no metadata row")
		void shouldNotCreateMetadataRowForNullLogpoint() {
			tracker.setLogpointExpression(42, null);

			assertThat(tracker.getLogpointExpression(42)).isNull();
			assertThat(tracker.isLogpoint(42)).isFalse();
		}

		@Test
		@DisplayName("Setting logpoint to empty string creates no metadata row")
		void shouldNotCreateMetadataRowForEmptyLogpoint() {
			tracker.setLogpointExpression(42, "");

			assertThat(tracker.getLogpointExpression(42)).isNull();
			assertThat(tracker.isLogpoint(42)).isFalse();
		}
	}

	@Nested
	@DisplayName("ClassPrepareRequest cleanup")
	class ClassPrepareRequestCleanup {

		@Test
		@DisplayName("Removing last pending BP for a class cleans up the CPR")
		void shouldCleanupCprWhenLastPendingBreakpointRemoved() {
			ClassPrepareRequest cpr = mockCpr();
			tracker.registerClassPrepareRequest("com.Foo", cpr);
			int id = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");

			tracker.removePendingBreakpoint(id);

			assertThat(tracker.hasClassPrepareRequest("com.Foo")).isFalse();
		}

		@Test
		@DisplayName("Removing one of two pending BPs for the same class leaves CPR intact")
		void shouldKeepCprWhenOtherPendingBreakpointsExist() {
			ClassPrepareRequest cpr = mockCpr();
			tracker.registerClassPrepareRequest("com.Foo", cpr);
			int id1 = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			int id2 = tracker.registerPendingBreakpoint("com.Foo", 20, 2, "ALL");

			tracker.removePendingBreakpoint(id1);

			assertThat(tracker.hasClassPrepareRequest("com.Foo")).isTrue();
			// The second pending BP is still there
			assertThat(tracker.getPendingBreakpoint(id2)).isNotNull();
		}

		@Test
		@DisplayName("Removing last pending BP via removeBreakpoint also cleans up CPR")
		void shouldCleanupCprWhenLastPendingRemovedViaRemoveBreakpoint() {
			ClassPrepareRequest cpr = mockCpr();
			tracker.registerClassPrepareRequest("com.Foo", cpr);
			int id = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");

			tracker.removeBreakpoint(id);

			assertThat(tracker.hasClassPrepareRequest("com.Foo")).isFalse();
		}

		@Test
		@DisplayName("CPR cleanup also considers pending exception breakpoints")
		void shouldNotCleanupCprWhenPendingExceptionBreakpointExists() {
			ClassPrepareRequest cpr = mockCpr();
			tracker.registerClassPrepareRequest("com.Foo", cpr);
			int lineId = tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			tracker.registerPendingExceptionBreakpoint(BreakpointTracker.ExceptionBreakpointSpec.suspending("com.Foo", true, true));

			tracker.removePendingBreakpoint(lineId);

			// CPR should still exist because the pending exception BP references the same class
			assertThat(tracker.hasClassPrepareRequest("com.Foo")).isTrue();
		}
	}

	// ── helpers ──

	private static BreakpointRequest mockBreakpointWithVm() {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(bp.virtualMachine()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		return bp;
	}

	private static ClassPrepareRequest mockCpr() {
		ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(cpr.virtualMachine()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		return cpr;
	}
}
