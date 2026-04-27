package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import one.edee.mcp.jdwp.BreakpointTracker.ExceptionBreakpointInfo;
import one.edee.mcp.jdwp.BreakpointTracker.ExceptionBreakpointSpec;
import one.edee.mcp.jdwp.BreakpointTracker.PendingExceptionBreakpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link BreakpointTracker} surface area not covered by {@link BreakpointTrackerTest}:
 * exception breakpoints, breakpoint metadata (conditions/logpoint expressions), the
 * ClassPrepareRequest registry, the breakpoint location map used by watcher evaluation, and
 * {@link BreakpointTracker#clearAll(EventRequestManager)} doing a full sweep across every
 * registered structure.
 */
class BreakpointTrackerExceptionAndMetadataTest {

	private BreakpointTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
	}

	@Nested
	@DisplayName("Exception breakpoints")
	class ExceptionBreakpoints {

		@Test
		void shouldRegisterExceptionBreakpointAndAssignId() {
			ExceptionRequest req = mock(ExceptionRequest.class);

			int id = tracker.registerExceptionBreakpoint(req, ExceptionBreakpointSpec.suspending("java.lang.NullPointerException", true, true));

			assertThat(id).isEqualTo(1);
			assertThat(tracker.getAllExceptionBreakpoints()).containsKey(id);
		}

		@Test
		void shouldReturnAllExceptionBreakpointsAsUnmodifiableMap() {
			ExceptionRequest reqA = mock(ExceptionRequest.class);
			ExceptionRequest reqB = mock(ExceptionRequest.class);
			tracker.registerExceptionBreakpoint(reqA, ExceptionBreakpointSpec.suspending("java.lang.NullPointerException", true, false));
			tracker.registerExceptionBreakpoint(reqB, ExceptionBreakpointSpec.suspending("java.lang.IllegalStateException", false, true));

			Map<Integer, ExceptionBreakpointInfo> all = tracker.getAllExceptionBreakpoints();

			assertThat(all).hasSize(2);
			assertThatCode(() -> all.put(999, null))
				.isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		void shouldStoreExceptionClassCaughtAndUncaughtFlags() {
			ExceptionRequest req = mock(ExceptionRequest.class);
			int id = tracker.registerExceptionBreakpoint(req, ExceptionBreakpointSpec.suspending("com.example.MyException", true, false));

			ExceptionBreakpointInfo info = tracker.getAllExceptionBreakpoints().get(id);

			assertThat(info.getExceptionClass()).isEqualTo("com.example.MyException");
			assertThat(info.isCaught()).isTrue();
			assertThat(info.isUncaught()).isFalse();
			assertThat(info.getRequest()).isSameAs(req);
		}

		@Test
		void shouldRemoveActiveExceptionBreakpointAndDeleteEventRequest() {
			ExceptionRequest req = mock(ExceptionRequest.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(req.virtualMachine()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int id = tracker.registerExceptionBreakpoint(req, ExceptionBreakpointSpec.suspending("java.lang.NullPointerException", true, true));

			boolean removed = tracker.removeExceptionBreakpoint(id);

			assertThat(removed).isTrue();
			assertThat(tracker.getAllExceptionBreakpoints()).isEmpty();
			verify(erm).deleteEventRequest(req);
		}

		@Test
		void shouldReturnFalseWhenRemovingNonexistentExceptionBreakpoint() {
			assertThat(tracker.removeExceptionBreakpoint(9999)).isFalse();
		}

		@Test
		void shouldFallThroughToPendingWhenActiveExceptionNotFound() {
			int id = tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));

			boolean removed = tracker.removeExceptionBreakpoint(id);

			assertThat(removed).isTrue();
			assertThat(tracker.getAllPendingExceptionBreakpoints()).isEmpty();
		}

		@Test
		void shouldStoreLogOnlyAndExpressionFlags() {
			ExceptionRequest req = mock(ExceptionRequest.class);
			int id = tracker.registerExceptionBreakpoint(
				req, ExceptionBreakpointSpec.logOnly("java.lang.IllegalStateException", true, true, "$exception.getMessage()"));

			ExceptionBreakpointInfo info = tracker.getAllExceptionBreakpoints().get(id);

			assertThat(info.isLogOnly()).isTrue();
			assertThat(info.getExpression()).isEqualTo("$exception.getMessage()");
		}

		@Test
		void shouldDefaultLogOnlyAndExpressionToFalseAndNull() {
			ExceptionRequest req = mock(ExceptionRequest.class);
			int id = tracker.registerExceptionBreakpoint(
				req, ExceptionBreakpointSpec.suspending("java.lang.NullPointerException", true, true));

			ExceptionBreakpointInfo info = tracker.getAllExceptionBreakpoints().get(id);

			assertThat(info.isLogOnly()).isFalse();
			assertThat(info.getExpression()).isNull();
		}

		@Test
		void shouldFindExceptionInfoByRequest() {
			ExceptionRequest req = mock(ExceptionRequest.class);
			ExceptionRequest other = mock(ExceptionRequest.class);
			tracker.registerExceptionBreakpoint(
				req, ExceptionBreakpointSpec.logOnly("java.lang.IllegalStateException", true, true, "$exception.getMessage()"));

			ExceptionBreakpointInfo info = tracker.findExceptionInfoByRequest(req);

			assertThat(info).isNotNull();
			assertThat(info.getExceptionClass()).isEqualTo("java.lang.IllegalStateException");
			assertThat(info.isLogOnly()).isTrue();
			assertThat(tracker.findExceptionInfoByRequest(other)).isNull();
		}

		@Test
		void shouldTolerateDeadVmOnRemove() {
			ExceptionRequest req = mock(ExceptionRequest.class);
			when(req.virtualMachine()).thenThrow(new RuntimeException("VM dead"));
			int id = tracker.registerExceptionBreakpoint(req, ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));

			boolean removed = tracker.removeExceptionBreakpoint(id);

			assertThat(removed).isTrue();
			assertThat(tracker.getAllExceptionBreakpoints()).isEmpty();
		}
	}

	@Nested
	@DisplayName("Pending exception breakpoints")
	class PendingExceptionBreakpoints {

		@Test
		void shouldRegisterPendingExceptionBreakpointAndAssignId() {
			int id = tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));

			assertThat(id).isEqualTo(1);
			assertThat(tracker.getAllPendingExceptionBreakpoints()).containsKey(id);
		}

		@Test
		void shouldGetPendingExceptionBreakpointsForClass() {
			tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.A", true, true));
			tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.A", false, true));
			tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.B", true, false));

			var matches = tracker.getPendingExceptionBreakpointsForClass("com.example.A");

			assertThat(matches).hasSize(2);
			assertThat(matches).allSatisfy(entry ->
				assertThat(entry.getValue().getExceptionClass()).isEqualTo("com.example.A"));
		}

		@Test
		void shouldPromotePendingExceptionToActive() {
			int id = tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));
			ExceptionRequest req = mock(ExceptionRequest.class);

			tracker.promotePendingExceptionToActive(id, req);

			assertThat(tracker.getAllPendingExceptionBreakpoints()).doesNotContainKey(id);
			assertThat(tracker.getAllExceptionBreakpoints()).containsKey(id);
			ExceptionBreakpointInfo info = tracker.getAllExceptionBreakpoints().get(id);
			assertThat(info.getExceptionClass()).isEqualTo("com.example.MyException");
			assertThat(info.isCaught()).isTrue();
			assertThat(info.isUncaught()).isTrue();
			assertThat(info.getRequest()).isSameAs(req);
		}

		@Test
		void shouldCarryLogOnlyAndExpressionAcrossPromotion() {
			int id = tracker.registerPendingExceptionBreakpoint(
				ExceptionBreakpointSpec.logOnly("com.example.MyException", true, true, "$exception.getMessage()"));
			ExceptionRequest req = mock(ExceptionRequest.class);

			tracker.promotePendingExceptionToActive(id, req);

			ExceptionBreakpointInfo info = tracker.getAllExceptionBreakpoints().get(id);
			assertThat(info.isLogOnly()).isTrue();
			assertThat(info.getExpression()).isEqualTo("$exception.getMessage()");
		}

		@Test
		void shouldMarkPendingExceptionFailed() {
			int id = tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));

			tracker.markPendingExceptionFailed(id, "class not found");

			PendingExceptionBreakpoint pending = tracker.getAllPendingExceptionBreakpoints().get(id);
			assertThat(pending.getFailureReason()).isEqualTo("class not found");
		}

		@Test
		void shouldNoOpWhenMarkingNonexistentPendingExceptionFailed() {
			assertThatCode(() -> tracker.markPendingExceptionFailed(9999, "reason"))
				.doesNotThrowAnyException();
		}

		@Test
		void shouldReturnAllPendingExceptionBreakpointsAsUnmodifiableMap() {
			tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.A", true, true));

			Map<Integer, PendingExceptionBreakpoint> all = tracker.getAllPendingExceptionBreakpoints();

			assertThat(all).hasSize(1);
			assertThatCode(() -> all.put(999, null))
				.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	@DisplayName("Breakpoint metadata (conditions and logpoints)")
	class BreakpointMetadata {

		@Test
		void shouldSetAndGetCondition() {
			tracker.setCondition(1, "x > 100");
			assertThat(tracker.getCondition(1)).isEqualTo("x > 100");
		}

		@Test
		void shouldIgnoreBlankConditionString() {
			tracker.setCondition(1, "   ");
			assertThat(tracker.getCondition(1)).isNull();
		}

		@Test
		void shouldIgnoreNullCondition() {
			tracker.setCondition(1, null);
			assertThat(tracker.getCondition(1)).isNull();
		}

		@Test
		void shouldSetAndGetLogpointExpression() {
			tracker.setLogpointExpression(1, "\"x=\" + x");
			assertThat(tracker.getLogpointExpression(1)).isEqualTo("\"x=\" + x");
		}

		@Test
		void shouldIgnoreBlankLogpointExpression() {
			tracker.setLogpointExpression(1, "");
			assertThat(tracker.getLogpointExpression(1)).isNull();
		}

		@Test
		void shouldReportIsLogpointTrueWhenExpressionSet() {
			tracker.setLogpointExpression(1, "x");
			assertThat(tracker.isLogpoint(1)).isTrue();
		}

		@Test
		void shouldReportIsLogpointFalseWhenNoExpression() {
			assertThat(tracker.isLogpoint(99)).isFalse();
		}

		@Test
		void shouldReturnNullConditionForUnknownBreakpointId() {
			assertThat(tracker.getCondition(123456)).isNull();
		}

		@Test
		void shouldReturnNullLogpointForUnknownBreakpointId() {
			assertThat(tracker.getLogpointExpression(123456)).isNull();
		}
	}

	@Nested
	@DisplayName("ClassPrepareRequest registry")
	class ClassPrepareRegistry {

		@Test
		void shouldRegisterAndLookupClassPrepareRequest() {
			ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
			tracker.registerClassPrepareRequest("com.example.Foo", cpr);

			assertThat(tracker.hasClassPrepareRequest("com.example.Foo")).isTrue();
		}

		@Test
		void shouldReportHasClassPrepareRequestTrueAfterRegister() {
			ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
			tracker.registerClassPrepareRequest("com.example.Foo", cpr);

			assertThat(tracker.hasClassPrepareRequest("com.example.Foo")).isTrue();
		}

		@Test
		void shouldReportHasClassPrepareRequestFalseForUnknownClass() {
			assertThat(tracker.hasClassPrepareRequest("com.unknown.Bar")).isFalse();
		}

		@Test
		void shouldRemoveClassPrepareRequest() {
			ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
			tracker.registerClassPrepareRequest("com.example.Foo", cpr);

			ClassPrepareRequest removed = tracker.removeClassPrepareRequest("com.example.Foo");

			assertThat(removed).isSameAs(cpr);
			assertThat(tracker.hasClassPrepareRequest("com.example.Foo")).isFalse();
		}

		@Test
		void shouldReturnNullWhenRemovingUnknownClassPrepareRequest() {
			assertThat(tracker.removeClassPrepareRequest("com.unknown.Bar")).isNull();
		}
	}

	@Nested
	@DisplayName("getBreakpointLocationMap")
	class BreakpointLocationMap {

		@Test
		void shouldBuildEmptyMapWhenNoBreakpoints() {
			assertThat(tracker.getBreakpointLocationMap()).isEmpty();
		}

		@Test
		void shouldBuildLocationMapFromActiveBreakpoints() {
			BreakpointRequest bp = mockBreakpointAt("com.Foo", 42);
			int id = tracker.registerBreakpoint(bp);

			Map<String, Integer> map = tracker.getBreakpointLocationMap();

			assertThat(map).hasSize(1);
			assertThat(map).containsEntry("com.Foo:42", id);
		}

		@Test
		void shouldBuildLocationMapWithMultipleBreakpointsAndDistinctKeys() {
			BreakpointRequest bp1 = mockBreakpointAt("com.Foo", 42);
			BreakpointRequest bp2 = mockBreakpointAt("com.Bar", 17);
			int id1 = tracker.registerBreakpoint(bp1);
			int id2 = tracker.registerBreakpoint(bp2);

			Map<String, Integer> map = tracker.getBreakpointLocationMap();

			assertThat(map).hasSize(2);
			assertThat(map).containsEntry("com.Foo:42", id1);
			assertThat(map).containsEntry("com.Bar:17", id2);
		}
	}

	@Nested
	@DisplayName("clearAll full sweep")
	class ClearAllFullSweep {

		@Test
		void shouldClearActivePendingMetadataCprsAndExceptionsAndResetCounter() {
			BreakpointRequest activeBp = mock(BreakpointRequest.class);
			ExceptionRequest excReq = mock(ExceptionRequest.class);
			ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
			VirtualMachine cprVm = mock(VirtualMachine.class);
			EventRequestManager cprErm = mock(EventRequestManager.class);
			when(cpr.virtualMachine()).thenReturn(cprVm);
			when(cprVm.eventRequestManager()).thenReturn(cprErm);

			int activeId = tracker.registerBreakpoint(activeBp);
			tracker.setCondition(activeId, "x > 0");
			tracker.registerPendingBreakpoint("com.Foo", 10, 2, "ALL");
			tracker.registerExceptionBreakpoint(excReq, ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));
			tracker.registerPendingExceptionBreakpoint(ExceptionBreakpointSpec.suspending("com.example.OtherException", true, false));
			tracker.registerClassPrepareRequest("com.Foo", cpr);

			EventRequestManager erm = mock(EventRequestManager.class);
			tracker.clearAll(erm);

			assertThat(tracker.getAllBreakpoints()).isEmpty();
			assertThat(tracker.getAllPendingBreakpoints()).isEmpty();
			assertThat(tracker.getAllExceptionBreakpoints()).isEmpty();
			assertThat(tracker.getAllPendingExceptionBreakpoints()).isEmpty();
			assertThat(tracker.getCondition(activeId)).isNull();
			assertThat(tracker.hasClassPrepareRequest("com.Foo")).isFalse();
			verify(erm).deleteEventRequest(activeBp);
			verify(erm).deleteEventRequest(excReq);
			verify(erm).deleteEventRequest(cpr);

			// Counter restarts at 1
			int next = tracker.registerPendingBreakpoint("com.New", 1, 2, "ALL");
			assertThat(next).isEqualTo(1);
		}

		@Test
		void shouldTolerateErmExceptionDuringClearAll() {
			BreakpointRequest bp = mock(BreakpointRequest.class);
			tracker.registerBreakpoint(bp);

			EventRequestManager erm = mock(EventRequestManager.class);
			doThrowOn(erm, bp);

			assertThatCode(() -> tracker.clearAll(erm)).doesNotThrowAnyException();
			assertThat(tracker.getAllBreakpoints()).isEmpty();
		}

		private void doThrowOn(EventRequestManager erm, BreakpointRequest bp) {
			org.mockito.Mockito.doThrow(new RuntimeException("simulated"))
				.when(erm).deleteEventRequest(bp);
		}
	}

	@Nested
	@DisplayName("tryPromotePending no-op branches")
	class TryPromotePending {

		@Test
		void shouldReturnZeroWhenServiceNull() {
			assertThat(tracker.tryPromotePending(null, null)).isZero();
		}

		@Test
		void shouldReturnZeroWhenVmNull() {
			JDIConnectionService service = mock(JDIConnectionService.class);
			when(service.getRawVM()).thenReturn(null);

			assertThat(tracker.tryPromotePending(service, null)).isZero();
		}

		@Test
		void shouldReturnZeroWhenNoPending() {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			assertThat(tracker.tryPromotePending(service, null)).isZero();
		}

		@Test
		void shouldSkipPendingWithFailureReason() {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int id = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			tracker.markPendingFailed(id, "no executable code");

			int promoted = tracker.tryPromotePending(service, null);

			assertThat(promoted).isZero();
			assertThat(tracker.getPendingBreakpoint(id)).isNotNull();
		}
	}

	@Nested
	@DisplayName("Metadata cleanup and last-breakpoint pair atomicity")
	class KnownLimitations {

		/**
		 * FINDING-8: removing a breakpoint by ID must also clear its entry in
		 * {@code breakpointMetadata} so condition/logpoint expressions do not leak across the
		 * lifetime of the synthetic ID.
		 */
		@Test
		void shouldLeakConditionMetadataAfterRemoveBreakpoint_FINDING_8() {
			BreakpointRequest bp = mock(BreakpointRequest.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(bp.virtualMachine()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);
			int id = tracker.registerBreakpoint(bp);
			tracker.setCondition(id, "x > 5");

			tracker.removeBreakpoint(id);

			assertThat(tracker.getCondition(id)).isNull();
		}

		/**
		 * FINDING-8 (variant): {@code unregisterByRequest} must also clear the metadata associated
		 * with the removed request's synthetic ID.
		 */
		@Test
		void shouldLeakConditionMetadataAfterUnregisterByRequest_FINDING_8() {
			BreakpointRequest bp = mock(BreakpointRequest.class);
			int id = tracker.registerBreakpoint(bp);
			tracker.setCondition(id, "x > 5");

			tracker.unregisterByRequest(bp);

			assertThat(tracker.getCondition(id)).isNull();
		}

		/**
		 * FINDING-9: {@link BreakpointTracker#getLastBreakpoint()} must publish the
		 * {@code (thread, id)} pair as a single atomic snapshot so callers can read both halves
		 * without observing a torn pair from two different writes.
		 */
		@Test
		void shouldExposeLastBreakpointPairViaSeparateUnsynchronisedGetters_FINDING_9() {
			com.sun.jdi.ThreadReference t1 = mock(com.sun.jdi.ThreadReference.class);
			com.sun.jdi.ThreadReference t2 = mock(com.sun.jdi.ThreadReference.class);

			tracker.setLastBreakpointThread(t1, 1);
			BreakpointTracker.LastBreakpoint first = tracker.getLastBreakpoint();
			tracker.setLastBreakpointThread(t2, 2);
			BreakpointTracker.LastBreakpoint second = tracker.getLastBreakpoint();

			assertThat(first.thread()).isSameAs(t1);
			assertThat(first.id()).isEqualTo(1);
			assertThat(second.thread()).isSameAs(t2);
			assertThat(second.id()).isEqualTo(2);
		}

		/**
		 * Stress test that drives the actual race window. Two writers update the
		 * {@code (thread, id)} pair in lockstep with consistent pairs; a reader takes a single
		 * atomic snapshot via {@link BreakpointTracker#getLastBreakpoint()} and counts mismatches.
		 * With atomic publication via a single record field, no mismatch can occur.
		 */
		@Test
		void shouldNeverObserveTornLastBreakpointPair_FINDING_9() throws Exception {
			com.sun.jdi.ThreadReference t1 = mock(com.sun.jdi.ThreadReference.class);
			com.sun.jdi.ThreadReference t2 = mock(com.sun.jdi.ThreadReference.class);
			java.util.concurrent.atomic.AtomicInteger mismatches = new java.util.concurrent.atomic.AtomicInteger();
			java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);

			Thread writer = new Thread(() -> {
				while (running.get()) {
					tracker.setLastBreakpointThread(t1, 1);
					tracker.setLastBreakpointThread(t2, 2);
				}
			});
			Thread reader = new Thread(() -> {
				while (running.get()) {
					BreakpointTracker.LastBreakpoint snapshot = tracker.getLastBreakpoint();
					if (snapshot == null) continue;
					com.sun.jdi.ThreadReference th = snapshot.thread();
					Integer id = snapshot.id();
					if (th == t1 && id != null && id == 2) mismatches.incrementAndGet();
					if (th == t2 && id != null && id == 1) mismatches.incrementAndGet();
				}
			});
			writer.start();
			reader.start();
			Thread.sleep(200);
			running.set(false);
			writer.join();
			reader.join();

			assertThat(mismatches.get())
				.as("torn (thread, id) reads detected")
				.isZero();
		}
	}

	// ── helper methods ──

	/**
	 * Builds a mocked {@link BreakpointRequest} whose {@code location().declaringType().name()}
	 * returns {@code className} and {@code lineNumber()} returns {@code lineNumber}. Used by the
	 * {@link BreakpointLocationMap} tests to drive
	 * {@link BreakpointTracker#getBreakpointLocationMap()}.
	 */
	private static BreakpointRequest mockBreakpointAt(String className, int lineNumber) {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		Location location = mock(Location.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(bp.location()).thenReturn(location);
		when(location.declaringType()).thenReturn(refType);
		when(refType.name()).thenReturn(className);
		when(location.lineNumber()).thenReturn(lineNumber);
		return bp;
	}
}
