package one.edee.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

			Map<String, List<Integer>> map = tracker.getBreakpointLocationMap();

			assertThat(map).hasSize(1);
			assertThat(map).containsEntry("com.Foo:42", List.of(id));
		}

		@Test
		void shouldBuildLocationMapWithMultipleBreakpointsAndDistinctKeys() {
			BreakpointRequest bp1 = mockBreakpointAt("com.Foo", 42);
			BreakpointRequest bp2 = mockBreakpointAt("com.Bar", 17);
			int id1 = tracker.registerBreakpoint(bp1);
			int id2 = tracker.registerBreakpoint(bp2);

			Map<String, List<Integer>> map = tracker.getBreakpointLocationMap();

			assertThat(map).hasSize(2);
			assertThat(map).containsEntry("com.Foo:42", List.of(id1));
			assertThat(map).containsEntry("com.Bar:17", List.of(id2));
		}

		/**
		 * A breakpoint and a logpoint, or two breakpoints with different filters, can share a line;
		 * the full-stack watcher scan needs every one of them, not whichever the map kept last.
		 */
		@Test
		void shouldKeepEveryBreakpointAtOneLineInAscendingOrder() {
			int id1 = tracker.registerBreakpoint(mockBreakpointAt("com.Foo", 42));
			int id2 = tracker.registerBreakpoint(mockBreakpointAt("com.Foo", 42));

			assertThat(tracker.getBreakpointLocationMap()).containsOnly(Map.entry("com.Foo:42", List.of(id1, id2)));
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

		/**
		 * Verifies {@link BreakpointTracker#clearAll(EventRequestManager)} also wipes chain
		 * state — without this, a fresh session could observe stale dependency edges across a
		 * {@code jdwp_reset} / {@code jdwp_clear(types="all")} call.
		 */
		@Test
		void shouldClearChainStateOnClearAll() {
			BreakpointRequest bp1 = mock(BreakpointRequest.class);
			BreakpointRequest bp2 = mock(BreakpointRequest.class);
			int triggerId = tracker.registerBreakpoint(bp1);
			int dependentId = tracker.registerBreakpoint(bp2);
			tracker.registerDependency(dependentId, triggerId, false);

			EventRequestManager erm = mock(EventRequestManager.class);
			tracker.clearAll(erm);

			assertThat(tracker.getDependencyOfDependent(dependentId)).isNull();
			assertThat(tracker.getDependentsOfTrigger(triggerId)).isEmpty();
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
			assertThat(tracker.tryPromotePending(null)).isZero();
		}

		@Test
		void shouldReturnZeroWhenVmNull() {
			JDIConnectionService service = mock(JDIConnectionService.class);
			when(service.getRawVM()).thenReturn(null);

			assertThat(tracker.tryPromotePending(service)).isZero();
		}

		@Test
		void shouldReturnZeroWhenNoPending() {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			assertThat(tracker.tryPromotePending(service)).isZero();
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

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isZero();
			assertThat(tracker.getPendingBreakpoint(id)).isNotNull();
		}

		/**
		 * Regression guard for GH issue #3: the safety-net promoter is strictly passive — it must
		 * NEVER invoke {@code findOrForceLoadClass}, because that path triggers {@code Class.forName}
		 * in the target VM and changes application behaviour the debugger should only observe. The
		 * promotion path uses {@link JDIConnectionService#findLoadedClass} exclusively; this test
		 * fails if a regression re-routes any of the three call sites (line, exception, field) back
		 * to the force-load overloads.
		 */
		@Test
		void shouldNeverCallFindOrForceLoadClassFromSafetyNet() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			tracker.registerPendingExceptionBreakpoint(
				ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));
			tracker.registerPendingFieldBreakpoint(BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.example.Owner", "field", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null));

			tracker.tryPromotePending(service);

			verify(service, org.mockito.Mockito.never()).findOrForceLoadClass(
				org.mockito.ArgumentMatchers.anyString());
			verify(service, org.mockito.Mockito.never()).findOrForceLoadClass(
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
		}

		/**
		 * When a pending line BP is promoted and it has a chain edge already registered, the
		 * promoted {@link BreakpointRequest} must come up DISABLED — otherwise the very first
		 * event could fire before the trigger has ever fired, defeating the whole chain.
		 */
		@Test
		void shouldDisarmPromotedLineBpWhenChained() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			Location loc = mock(Location.class);
			BreakpointRequest bp = mock(BreakpointRequest.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);
			when(service.findLoadedClass(eq("com.example.Foo"))).thenReturn(refType);
			when(refType.locationsOfLine(42)).thenReturn(java.util.List.of(loc));
			when(erm.createBreakpointRequest(loc)).thenReturn(bp);

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			tracker.registerDependency(pendingId, triggerId, false);

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isEqualTo(1);
			verify(bp).setEnabled(false);
		}

		/**
		 * When a pending exception BP is promoted and it has a chain edge already registered,
		 * the promoted {@link ExceptionRequest} must come up DISABLED so the trigger gates the
		 * very first delivery.
		 */
		@Test
		void shouldDisarmPromotedExceptionBpWhenChained() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			ExceptionRequest exReq = mock(ExceptionRequest.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);
			when(service.findLoadedClass(eq("com.example.MyException"))).thenReturn(refType);
			when(erm.createExceptionRequest(refType, true, true)).thenReturn(exReq);

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingId = tracker.registerPendingExceptionBreakpoint(
				ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));
			tracker.registerDependency(pendingId, triggerId, false);

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isEqualTo(1);
			verify(exReq).setEnabled(false);
		}

		/**
		 * Conversely, a pending BP without a chain edge must come up ENABLED. Asserts that the
		 * disarm branch in {@link BreakpointTracker#tryPromotePending} only fires when a chain
		 * edge is present — otherwise we would silently break every non-chained pending BP.
		 */
		@Test
		void shouldPromoteWithoutDisarmingWhenNoChain() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			Location loc = mock(Location.class);
			BreakpointRequest bp = mock(BreakpointRequest.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);
			when(service.findLoadedClass(eq("com.example.Foo"))).thenReturn(refType);
			when(refType.locationsOfLine(42)).thenReturn(java.util.List.of(loc));
			when(erm.createBreakpointRequest(loc)).thenReturn(bp);

			tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isEqualTo(1);
			// enable() is called (from the production code itself), but setEnabled(false) must NOT.
			verify(bp, org.mockito.Mockito.never()).setEnabled(false);
		}
	}

	/**
	 * Recheck-race coverage for the {@code tryPromotePending} per-entry recheck branches. The
	 * worker thread snapshots pending entries under the tracker monitor, releases the monitor for
	 * the per-entry {@code findLoadedClass} lookup, then re-acquires the monitor to recheck-then-
	 * promote each entry. Three things can have changed during the lookup window:
	 * <ul>
	 *   <li>another thread removed the pending entry (e.g. user issued {@code jdwp_clear})</li>
	 *   <li>another thread marked the pending entry failed (e.g. listener path classifier)</li>
	 *   <li>the listener path already promoted the entry from the {@code ClassPrepareEvent} side</li>
	 * </ul>
	 * In all three cases the worker's per-entry recheck must short-circuit so it neither double-
	 * registers a JDI request nor counts the entry as freshly promoted.
	 */
	@Nested
	@DisplayName("tryPromotePending per-entry recheck races")
	class PromotePendingRecheckRaces {

		@Test
		void shouldReturnZeroAndSkipEntryWhenPendingLineBpIsRemovedWhileWorkerIsResolvingClass() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

			// While the worker is mid-lookup inside findLoadedClass, another caller removes the
			// pending entry. The recheck must observe pendingBreakpointsById.get(id) != pending and
			// continue without ever calling locationsOfLine / createBreakpointRequest.
			when(service.findLoadedClass(eq("com.example.Foo")))
				.thenAnswer(inv -> {
					tracker.removePendingBreakpoint(pendingId);
					return refType;
				});

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isZero();
			assertThat(tracker.getPendingBreakpoint(pendingId)).isNull();
			assertThat(tracker.getBreakpoint(pendingId)).isNull();
			org.mockito.Mockito.verify(refType, org.mockito.Mockito.never()).locationsOfLine(org.mockito.ArgumentMatchers.anyInt());
		}

		@Test
		void shouldReturnZeroAndSkipEntryWhenPendingLineBpIsMarkedFailedWhileWorkerIsResolvingClass() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

			when(service.findLoadedClass(eq("com.example.Foo")))
				.thenAnswer(inv -> {
					tracker.markPendingFailed(pendingId, "concurrent classifier marked failed");
					return refType;
				});

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isZero();
			// Entry stays in the pending map so the failure reason is visible to overview tools.
			assertThat(tracker.getPendingBreakpoint(pendingId)).isNotNull();
			assertThat(tracker.getPendingBreakpoint(pendingId).getFailureReason())
				.isEqualTo("concurrent classifier marked failed");
			org.mockito.Mockito.verify(refType, org.mockito.Mockito.never()).locationsOfLine(org.mockito.ArgumentMatchers.anyInt());
		}

		@Test
		void shouldReturnZeroForLineBpWhenAnotherCallerHasPromotedItToActiveWhileWorkerIsResolvingClass() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			BreakpointRequest listenerBp = mock(BreakpointRequest.class, "listenerBp");
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

			// While the worker is parked, the listener path promotes the same id to active using its
			// own BreakpointRequest. The worker's recheck must observe breakpointsById.containsKey(id)
			// and skip the entry — otherwise it would overwrite breakpointsById with its own BP and
			// leave listenerBp armed on the target VM as a ghost without tracker entry.
			when(service.findLoadedClass(eq("com.example.Foo")))
				.thenAnswer(inv -> {
					tracker.promotePendingToActive(pendingId, listenerBp);
					return refType;
				});

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isZero();
			assertThat(tracker.getBreakpoint(pendingId))
				.as("the listener-side BP must remain bound to the synthetic id")
				.isSameAs(listenerBp);
			org.mockito.Mockito.verify(refType, org.mockito.Mockito.never()).locationsOfLine(org.mockito.ArgumentMatchers.anyInt());
		}

		@Test
		void shouldReturnZeroAndSkipEntryWhenPendingExceptionBpIsRemovedWhileWorkerIsResolvingClass() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int pendingId = tracker.registerPendingExceptionBreakpoint(
				ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));

			when(service.findLoadedClass(eq("com.example.MyException")))
				.thenAnswer(inv -> {
					tracker.removeExceptionBreakpoint(pendingId);
					return refType;
				});

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isZero();
			assertThat(tracker.getAllPendingExceptionBreakpoints()).doesNotContainKey(pendingId);
			assertThat(tracker.getAllExceptionBreakpoints()).doesNotContainKey(pendingId);
			org.mockito.Mockito.verify(erm, org.mockito.Mockito.never())
				.createExceptionRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
		}

		@Test
		void shouldReturnZeroAndSkipEntryWhenPendingExceptionBpIsMarkedFailedWhileWorkerIsResolvingClass() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int pendingId = tracker.registerPendingExceptionBreakpoint(
				ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));

			when(service.findLoadedClass(eq("com.example.MyException")))
				.thenAnswer(inv -> {
					tracker.markPendingExceptionFailed(pendingId, "concurrent classifier marked failed");
					return refType;
				});

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isZero();
			assertThat(tracker.getAllPendingExceptionBreakpoints().get(pendingId).getFailureReason())
				.isEqualTo("concurrent classifier marked failed");
			org.mockito.Mockito.verify(erm, org.mockito.Mockito.never())
				.createExceptionRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
		}

		@Test
		void shouldReturnZeroForExceptionBpWhenAnotherCallerHasPromotedItToActiveWhileWorkerIsResolvingClass() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			ExceptionRequest listenerExReq = mock(ExceptionRequest.class, "listenerExReq");
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int pendingId = tracker.registerPendingExceptionBreakpoint(
				ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));

			when(service.findLoadedClass(eq("com.example.MyException")))
				.thenAnswer(inv -> {
					tracker.promotePendingExceptionToActive(pendingId, listenerExReq);
					return refType;
				});

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isZero();
			assertThat(tracker.getAllExceptionBreakpoints().get(pendingId).getRequest())
				.as("the listener-side ExceptionRequest must remain bound to the synthetic id")
				.isSameAs(listenerExReq);
			org.mockito.Mockito.verify(erm, org.mockito.Mockito.never())
				.createExceptionRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
		}

		/**
		 * Coverage for the {@link AbsentInformationException} branch in the line-BP arm of
		 * {@link BreakpointTracker#tryPromotePending}: when the class is loaded but carries no
		 * debug info (or carries it for a different version of the source), {@code locationsOfLine}
		 * throws {@code AbsentInformationException}. The arm must swallow the exception so a single
		 * info-less class does not abort promotion of other pending entries, and must leave the
		 * pending entry in place so a later retry — perhaps after a different version of the class
		 * loads — gets another chance.
		 */
		@Test
		void shouldSkipLineBpWhenLocationsOfLineThrowsAbsentInformationException() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refType = mock(ReferenceType.class);
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);
			when(service.findLoadedClass(eq("com.example.Foo"))).thenReturn(refType);
			when(refType.locationsOfLine(42)).thenThrow(new AbsentInformationException("no debug info"));

			int pendingId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted).isZero();
			assertThat(tracker.getPendingBreakpoint(pendingId))
				.as("pending entry must stay in place so a later retry can succeed")
				.isNotNull();
			// The pending entry's failure reason MUST stay null — AbsentInformationException is the
			// "try again later" branch; only terminal failures should set a failure reason.
			assertThat(tracker.getPendingBreakpoint(pendingId).getFailureReason()).isNull();
		}

		/**
		 * The {@code promoted} counter returned by {@link BreakpointTracker#tryPromotePending}
		 * counts only entries that were ACTUALLY promoted by this caller — entries that lost their
		 * recheck race (because another caller already promoted them, removed them, or marked them
		 * failed) must not contribute to the count. Otherwise the caller's "promoted N entries"
		 * log line would attribute work that was performed by a different thread.
		 *
		 * <p>This test arranges three pending line BPs against three classes; the JDI-invoke
		 * answer-stub performs concurrent state mutations against entry A (remove) and entry B
		 * (already-promoted) before returning. Only entry C survives the race and is promoted by
		 * the caller, so the returned counter must be 1.
		 */
		@Test
		void shouldCountOnlyActuallyPromotedEntriesWhenSomeEntriesLoseRecheckRace() throws Exception {
			JDIConnectionService service = mock(JDIConnectionService.class);
			VirtualMachine vm = mock(VirtualMachine.class);
			EventRequestManager erm = mock(EventRequestManager.class);
			ReferenceType refTypeA = mock(ReferenceType.class, "refTypeA");
			ReferenceType refTypeB = mock(ReferenceType.class, "refTypeB");
			ReferenceType refTypeC = mock(ReferenceType.class, "refTypeC");
			Location locC = mock(Location.class);
			BreakpointRequest bpC = mock(BreakpointRequest.class, "bpC");
			BreakpointRequest listenerBpB = mock(BreakpointRequest.class, "listenerBpB");
			when(service.getRawVM()).thenReturn(vm);
			when(vm.eventRequestManager()).thenReturn(erm);

			int idA = tracker.registerPendingBreakpoint("com.example.A", 10, 2, "ALL");
			int idB = tracker.registerPendingBreakpoint("com.example.B", 20, 2, "ALL");
			int idC = tracker.registerPendingBreakpoint("com.example.C", 30, 2, "ALL");

			// A: removed under the worker's feet during its JDI invoke.
			when(service.findLoadedClass(eq("com.example.A")))
				.thenAnswer(inv -> {
					tracker.removePendingBreakpoint(idA);
					return refTypeA;
				});
			// B: promoted by another caller while the worker is parked.
			when(service.findLoadedClass(eq("com.example.B")))
				.thenAnswer(inv -> {
					tracker.promotePendingToActive(idB, listenerBpB);
					return refTypeB;
				});
			// C: a clean promotion path — survives the race.
			when(service.findLoadedClass(eq("com.example.C"))).thenReturn(refTypeC);
			when(refTypeC.locationsOfLine(30)).thenReturn(List.of(locC));
			when(erm.createBreakpointRequest(locC)).thenReturn(bpC);

			int promoted = tracker.tryPromotePending(service);

			assertThat(promoted)
				.as("only entry C was actually promoted by this caller — A and B lost their recheck race")
				.isEqualTo(1);
			assertThat(tracker.getBreakpoint(idB))
				.as("entry B remains bound to the listener-side BP")
				.isSameAs(listenerBpB);
			assertThat(tracker.getBreakpoint(idC)).isSameAs(bpC);
		}
	}

	/**
	 * Guards the already-promoted contract on the {@link BreakpointTracker} promotion methods.
	 * {@code promotePendingToActive} and {@code promotePendingExceptionToActive} are called from
	 * two paths that race against each other for the same pending entry — the JDI listener thread
	 * (on a {@code ClassPrepareEvent}) and the safety-net {@link BreakpointTracker#tryPromotePending}.
	 * The first promotion must win; the second must observe the existing active entry, return
	 * {@code false}, and leave callers to tear down the orphan JDI request they just created.
	 */
	@Nested
	@DisplayName("Double-promotion guard")
	class DoublePromotionGuard {

		@Test
		void promotePendingToActive_rejectsSecondPromotionForSameId() {
			int id = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			BreakpointRequest bp1 = mock(BreakpointRequest.class, "bp1");
			BreakpointRequest bp2 = mock(BreakpointRequest.class, "bp2");

			boolean firstResult = tracker.promotePendingToActive(id, bp1);
			boolean secondResult = tracker.promotePendingToActive(id, bp2);

			assertThat(firstResult)
				.as("first promotion succeeds")
				.isTrue();
			assertThat(secondResult)
				.as("second promotion for the same synthetic id is rejected — caller must delete the orphan request")
				.isFalse();
			assertThat(tracker.getBreakpoint(id))
				.as("first-arrived request wins — bp2 must NOT overwrite bp1 in the active map")
				.isSameAs(bp1);
			assertThat(tracker.findIdByRequest(bp1))
				.as("reverse index for the winning request is preserved")
				.isEqualTo(id);
			assertThat(tracker.findIdByRequest(bp2))
				.as("losing request must NOT appear in the reverse index — would be a ghost entry")
				.isNull();
		}

		/**
		 * The pending entry can disappear between the listener's class-prepare snapshot and the call
		 * to {@code promotePendingToActive} — most commonly because the user cleared the breakpoint
		 * via {@code clearBreakpoint}. Without this guard, late class-prepare promotion would resurrect
		 * a breakpoint the user has already removed: no active entry exists, the {@code containsKey}
		 * check passes, and the orphan {@link BreakpointRequest} gets installed under the recycled id.
		 */
		@Test
		void promotePendingToActive_returnsFalseWhenPendingEntryWasClearedConcurrently() {
			int id = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			// Simulate the user clearing the pending entry between the listener's snapshot of
			// pendingList and the call to promotePendingToActive.
			assertThat(tracker.removePendingBreakpoint(id))
				.as("precondition: the pending entry exists before the simulated clear")
				.isTrue();

			BreakpointRequest bp = mock(BreakpointRequest.class);
			boolean result = tracker.promotePendingToActive(id, bp);

			assertThat(result)
				.as("late class-prepare promotion must observe the cleared pending entry and refuse "
					+ "to install — caller will delete the orphan request")
				.isFalse();
			assertThat(tracker.getBreakpoint(id))
				.as("no active breakpoint may be created from a cleared pending entry")
				.isNull();
			assertThat(tracker.findIdByRequest(bp))
				.as("the orphan request must NOT appear in the reverse index — would be a ghost entry")
				.isNull();
		}

		@Test
		void promotePendingExceptionToActive_rejectsSecondPromotionForSameId() {
			int id = tracker.registerPendingExceptionBreakpoint(
				ExceptionBreakpointSpec.suspending("com.example.MyException", true, true));
			ExceptionRequest exReq1 = mock(ExceptionRequest.class, "exReq1");
			ExceptionRequest exReq2 = mock(ExceptionRequest.class, "exReq2");

			boolean firstResult = tracker.promotePendingExceptionToActive(id, exReq1);
			boolean secondResult = tracker.promotePendingExceptionToActive(id, exReq2);

			assertThat(firstResult)
				.as("first promotion succeeds")
				.isTrue();
			assertThat(secondResult)
				.as("second promotion for the same synthetic id is rejected — caller must delete the orphan request")
				.isFalse();
			assertThat(tracker.getAllExceptionBreakpoints().get(id).getRequest())
				.as("first-arrived request wins — exReq2 must NOT overwrite exReq1 in the active map")
				.isSameAs(exReq1);
			assertThat(tracker.findExceptionIdByRequest(exReq1))
				.as("reverse index for the winning request is preserved")
				.isEqualTo(id);
			assertThat(tracker.findExceptionIdByRequest(exReq2))
				.as("losing request must NOT appear in the reverse index — would be a ghost entry")
				.isNull();
		}
	}

	@Nested
	@DisplayName("Metadata cleanup and last-breakpoint pair atomicity")
	class MetadataCleanupAndAtomicPair {

		/**
		 * Removing a breakpoint by ID must also clear its entry in {@code breakpointMetadata} so
		 * condition/logpoint expressions cannot leak across the lifetime of the synthetic ID — a
		 * freshly minted BP that reuses the deleted ID would otherwise inherit the old condition.
		 */
		@Test
		void shouldClearConditionMetadataAfterRemoveBreakpoint() {
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
		 * {@code unregisterByRequest} must also clear the metadata associated with the removed
		 * request's synthetic ID — same lifetime invariant as {@link #shouldClearConditionMetadataAfterRemoveBreakpoint}
		 * applied to the request-identity removal path.
		 */
		@Test
		void shouldClearConditionMetadataAfterUnregisterByRequest() {
			BreakpointRequest bp = mock(BreakpointRequest.class);
			int id = tracker.registerBreakpoint(bp);
			tracker.setCondition(id, "x > 5");

			tracker.unregisterByRequest(bp);

			assertThat(tracker.getCondition(id)).isNull();
		}

		/**
		 * {@link BreakpointTracker#getLastBreakpoint()} must publish the {@code (thread, id)}
		 * pair as a single atomic snapshot so callers can read both halves without observing a torn
		 * pair from two different writes.
		 */
		@Test
		void shouldExposeLastBreakpointPairAtomically() {
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
		void shouldNeverObserveTornLastBreakpointPair() throws Exception {
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
