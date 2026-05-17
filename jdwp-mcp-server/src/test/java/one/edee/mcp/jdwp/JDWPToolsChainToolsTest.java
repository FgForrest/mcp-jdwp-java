package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import one.edee.mcp.jdwp.BreakpointTracker.ExceptionBreakpointSpec;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the chain-aware MCP tool surface on {@link JDWPTools}: the dedicated chain CRUD tools
 * ({@code jdwp_set_breakpoint_dependency}, {@code jdwp_clear_breakpoint_dependency},
 * {@code jdwp_disarm_until_trigger}), the optional {@code triggerBreakpointId} argument on
 * {@code jdwp_set_breakpoint} and {@code jdwp_set_exception_breakpoint}, the cascade-on-clear path
 * in {@code jdwp_clear_breakpoint_by_id} / {@code jdwp_clear_exception_breakpoint}, the
 * {@code [chain: ...]} suffix in {@code jdwp_list_breakpoints} / {@code jdwp_list_exception_breakpoints}
 * / {@code jdwp_diagnose}, and the chain-stuck interpretation hint emitted by the diagnostic.
 *
 * <p>Tests use a REAL {@link BreakpointTracker} and {@link EventHistory} so they exercise the
 * real chain bookkeeping and event recording, while mocking {@link JDIConnectionService} and the
 * underlying JDI request objects. This keeps the tests focused on the contract observable through
 * the {@code @McpTool} return values and the event stream.
 */
@DisplayName("JDWPTools chain-aware tool surface")
class JDWPToolsChainToolsTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker tracker;
	private WatcherManager watcherManager;
	private EventHistory eventHistory;
	private JDWPTools tools;
	@Nullable
	private VirtualMachine vm;
	@Nullable
	private EventRequestManager erm;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		watcherManager = new WatcherManager();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(),
			new one.edee.mcp.jdwp.discovery.JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
	}

	// ── Helpers ────────────────────────────────────────────────────────────

	/**
	 * Wires a {@link BreakpointRequest} mock with a location report of
	 * {@code className}/{@code lineNumber} so {@link JDWPTools#jdwp_list_breakpoints} can render
	 * it without throwing. {@code suspendPolicy} mirrors the JDI {@link EventRequest} constants
	 * (2 = SUSPEND_ALL on the JDI ABI).
	 */
	private BreakpointRequest mockBreakpointAt(String className, int lineNumber, int suspendPolicy) {
		BreakpointRequest bp = mock(BreakpointRequest.class);
		Location loc = mock(Location.class);
		ReferenceType refType = mock(ReferenceType.class);
		Method method = mock(Method.class);
		when(bp.location()).thenReturn(loc);
		when(bp.suspendPolicy()).thenReturn(suspendPolicy);
		when(loc.declaringType()).thenReturn(refType);
		when(loc.method()).thenReturn(method);
		when(method.name()).thenReturn("m");
		when(refType.name()).thenReturn(className);
		when(loc.lineNumber()).thenReturn(lineNumber);
		return bp;
	}

	private boolean hasEventOfType(String type) {
		return eventHistory.getRecent(20).stream().anyMatch(e -> type.equals(e.type()));
	}

	private EventHistory.@Nullable DebugEvent findEventOfType(String type) {
		return eventHistory.getRecent(20).stream()
			.filter(e -> type.equals(e.type()))
			.findFirst()
			.orElse(null);
	}

	// ── Tests ──────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("jdwp_set_breakpoint_dependency")
	class SetBreakpointDependency {

		@Test
		@DisplayName("rejects self-chain")
		void shouldRejectSelfChain() {
			String result = tools.jdwp_set_breakpoint_dependency(5, 5, false);
			assertThat(result).startsWith("Error:").contains("own trigger");
		}

		@Test
		@DisplayName("rejects when dependent does not exist")
		void shouldRejectMissingDependent() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			String result = tools.jdwp_set_breakpoint_dependency(999, triggerId, false);

			assertThat(result).startsWith("Error:").contains("Dependent breakpoint #999");
		}

		@Test
		@DisplayName("rejects when trigger does not exist")
		void shouldRejectMissingTrigger() {
			int depId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			String result = tools.jdwp_set_breakpoint_dependency(depId, 999, false);

			assertThat(result).startsWith("Error:").contains("Trigger breakpoint #999");
		}

		@Test
		@DisplayName("disables active dependent and registers sticky chain")
		void shouldDisableActiveDependentAndRegisterStickyChain() {
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int depId = tracker.registerBreakpoint(depBp);

			String result = tools.jdwp_set_breakpoint_dependency(depId, triggerId, null);

			assertThat(result).contains("sticky").contains("Disarmed");
			verify(depBp).setEnabled(false);
			BreakpointTracker.TriggerLink link = tracker.getDependencyOfDependent(depId);
			assertThat(link).isNotNull();
			assertThat(link.triggerId()).isEqualTo(triggerId);
			assertThat(link.oneShot()).isFalse();
		}

		@Test
		@DisplayName("registers a one-shot chain when oneShot=true")
		void shouldRegisterOneShotChainWhenOneShotTrue() {
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int depId = tracker.registerBreakpoint(depBp);

			String result = tools.jdwp_set_breakpoint_dependency(depId, triggerId, true);

			assertThat(result).contains("one-shot");
			assertThat(tracker.getDependencyOfDependent(depId).oneShot()).isTrue();
		}

		@Test
		@DisplayName("handles a pending dependent: chain registered, no setEnabled call")
		void shouldHandlePendingDependent() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingDepId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");

			String result = tools.jdwp_set_breakpoint_dependency(pendingDepId, triggerId, false);

			assertThat(result).contains("still pending");
			assertThat(tracker.getDependencyOfDependent(pendingDepId)).isNotNull();
		}

		@Test
		@DisplayName("handles a pending trigger: chain still registers")
		void shouldHandlePendingTrigger() {
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			int pendingTriggerId = tracker.registerPendingBreakpoint("com.example.Trigger", 10, 2, "ALL");
			int depId = tracker.registerBreakpoint(depBp);

			String result = tools.jdwp_set_breakpoint_dependency(depId, pendingTriggerId, false);

			assertThat(result).doesNotStartWith("Error");
			verify(depBp).setEnabled(false);
			assertThat(tracker.getDependencyOfDependent(depId).triggerId()).isEqualTo(pendingTriggerId);
		}
	}

	@Nested
	@DisplayName("jdwp_clear_breakpoint_dependency")
	class ClearBreakpointDependency {

		@Test
		@DisplayName("returns 'no chain' message when dependent has no chain")
		void shouldReturnNoChainMessageWhenDependentHasNone() {
			int depId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			String result = tools.jdwp_clear_breakpoint_dependency(depId);

			assertThat(result).contains("no chain dependency");
		}

		@Test
		@DisplayName("clears chain and re-enables the active dependent")
		void shouldClearChainAndReEnableActiveDependent() {
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			String result = tools.jdwp_clear_breakpoint_dependency(depId);

			assertThat(result).contains("armed independently");
			verify(depBp).setEnabled(true);
			assertThat(tracker.getDependencyOfDependent(depId)).isNull();
		}

		@Test
		@DisplayName("clears chain silently when the dependent is pending")
		void shouldClearChainSilentlyWhenDependentIsPending() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingDepId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			tracker.registerDependency(pendingDepId, triggerId, false);

			String result = tools.jdwp_clear_breakpoint_dependency(pendingDepId);

			assertThat(result).doesNotStartWith("Error");
			assertThat(tracker.getDependencyOfDependent(pendingDepId)).isNull();
		}

		@Test
		@DisplayName("tolerates setEnabled failure during clear (best-effort)")
		void shouldTolerateSetEnabledFailure() {
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);
			doThrow(new RuntimeException("vm dead")).when(depBp).setEnabled(true);

			String result = tools.jdwp_clear_breakpoint_dependency(depId);

			assertThat(result).doesNotStartWith("Error");
			assertThat(tracker.getDependencyOfDependent(depId)).isNull();
		}
	}

	@Nested
	@DisplayName("jdwp_disarm_until_trigger")
	class DisarmUntilTrigger {

		@Test
		@DisplayName("rejects when no chain is configured")
		void shouldRejectWhenNoChainConfigured() {
			int depId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			String result = tools.jdwp_disarm_until_trigger(depId);

			assertThat(result).contains("no chain");
		}

		@Test
		@DisplayName("disarms the active chained dependent")
		void shouldDisarmActiveChainedDependent() {
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			String result = tools.jdwp_disarm_until_trigger(depId);

			assertThat(result).contains("re-disarmed");
			verify(depBp).setEnabled(false);
		}

		@Test
		@DisplayName("reports pending dependent when no active request exists")
		void shouldReportPendingDependentWhenNoActiveRequest() {
			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
			int pendingDepId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			tracker.registerDependency(pendingDepId, triggerId, false);

			String result = tools.jdwp_disarm_until_trigger(pendingDepId);

			assertThat(result).contains("still pending");
		}
	}

	@Nested
	@DisplayName("jdwp_set_breakpoint with chain")
	class SetBreakpointWithChain {

		@Test
		@DisplayName("rejects an unknown trigger")
		void shouldRejectSetBreakpointWithUnknownTrigger() throws Exception {
			String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, 999, false);

			assertThat(result).startsWith("Error:").contains("Trigger breakpoint #999");
		}

		@Test
		@DisplayName("accepts a pending trigger when setting a new breakpoint")
		void shouldAcceptPendingTriggerWhenSettingBreakpoint() throws Exception {
			int pendingTriggerId = tracker.registerPendingBreakpoint("com.example.Trigger", 10, 2, "ALL");
			when(jdiService.findOrForceLoadClass("com.example.Foo")).thenReturn(null);
			when(vm.classesByName("com.example.Foo")).thenReturn(List.of());
			when(erm.createClassPrepareRequest()).thenReturn(mock(com.sun.jdi.request.ClassPrepareRequest.class));

			String result = tools.jdwp_set_breakpoint("com.example.Foo", 99, "all", null, pendingTriggerId, false);

			assertThat(result).doesNotStartWith("Error");
			assertThat(result).contains("chain: trigger=#" + pendingTriggerId);
		}

		@Test
		@DisplayName("embeds chain info in the active breakpoint response")
		void shouldEmbedChainInfoInActiveBreakpointResponse() throws Exception {
			BreakpointRequest createdBp = mock(BreakpointRequest.class);
			ReferenceType refType = mock(ReferenceType.class);
			Location loc = mock(Location.class);
			when(jdiService.findOrForceLoadClass("com.example.Foo")).thenReturn(refType);
			when(refType.locationsOfLine(10)).thenReturn(List.of(loc));
			when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, triggerId, true);

			assertThat(result).contains("chain: trigger=#" + triggerId)
				.contains("one-shot");
		}

		@Test
		@DisplayName("embeds chain info in the pending breakpoint response")
		void shouldEmbedChainInfoInPendingBreakpointResponse() throws Exception {
			when(jdiService.findOrForceLoadClass("com.example.Foo")).thenReturn(null);
			when(vm.classesByName("com.example.Foo")).thenReturn(List.of());
			when(erm.createClassPrepareRequest()).thenReturn(mock(com.sun.jdi.request.ClassPrepareRequest.class));

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, triggerId, false);

			assertThat(result).contains("deferred")
				.contains("chain: trigger=#" + triggerId)
				.contains("sticky");
		}

		@Test
		@DisplayName("registers chain and leaves the active BP disabled when trigger provided")
		void shouldRegisterChainAndDisableActiveBpWhenTriggerProvided() throws Exception {
			BreakpointRequest createdBp = mock(BreakpointRequest.class);
			ReferenceType refType = mock(ReferenceType.class);
			Location loc = mock(Location.class);
			when(jdiService.findOrForceLoadClass("com.example.Foo")).thenReturn(refType);
			when(refType.locationsOfLine(10)).thenReturn(List.of(loc));
			when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, triggerId, false);

			// The request must NEVER be enabled while chained — neither setEnabled(true) nor the
			// convenience enable() may be called. JDI delivers events the instant a request is
			// enabled, so any window where the chained BP is enabled lets it fire once unchained.
			// (JDI requests are disabled on creation, so no explicit setEnabled(false) is needed.)
			verify(createdBp, org.mockito.Mockito.never()).setEnabled(true);
			verify(createdBp, org.mockito.Mockito.never()).enable();
			// The chain edge is recorded.
			Integer newBpId = tracker.findIdByRequest(createdBp);
			assertThat(newBpId).isNotNull();
			BreakpointTracker.TriggerLink link = tracker.getDependencyOfDependent(newBpId);
			assertThat(link).isNotNull();
			assertThat(link.triggerId()).isEqualTo(triggerId);
		}

		/**
		 * The unchained path must NOT introduce a window where the request is enabled before its
		 * policy is set — an event delivered between {@code create()} and {@code setSuspendPolicy()}
		 * would be routed with the wrong policy. Captures the ordering for the NO-trigger case via
		 * {@link org.mockito.InOrder} so a regression of the "enable last" invariant fails this
		 * test deterministically (the race-window companion test stays {@code @Disabled}).
		 */
		@Test
		@DisplayName("unchained BP — setSuspendPolicy fires before setEnabled(true)")
		void shouldSetSuspendPolicyBeforeEnableOnUnchainedBp() throws Exception {
			BreakpointRequest createdBp = mock(BreakpointRequest.class);
			ReferenceType refType = mock(ReferenceType.class);
			Location loc = mock(Location.class);
			when(jdiService.findOrForceLoadClass("com.example.Foo")).thenReturn(refType);
			when(refType.locationsOfLine(10)).thenReturn(List.of(loc));
			when(erm.createBreakpointRequest(loc)).thenReturn(createdBp);

			tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, false);

			org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(createdBp);
			inOrder.verify(createdBp).setSuspendPolicy(EventRequest.SUSPEND_ALL);
			inOrder.verify(createdBp).setEnabled(true);
		}
	}

	@Nested
	@DisplayName("jdwp_set_exception_breakpoint with chain")
	class SetExceptionBreakpointWithChain {

		@Test
		@DisplayName("rejects an unknown trigger")
		void shouldRejectExceptionBpWithUnknownTrigger() throws Exception {
			String result = tools.jdwp_set_exception_breakpoint(
				"java.lang.RuntimeException", null, null, null, null, 999, false);

			assertThat(result).startsWith("Error:").contains("Trigger breakpoint #999");
		}

		@Test
		@DisplayName("embeds chain info in the exception BP response")
		void shouldEmbedChainInfoInExceptionBreakpointResponse() throws Exception {
			ReferenceType refType = mock(ReferenceType.class);
			ExceptionRequest exReq = mock(ExceptionRequest.class);
			when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
			when(erm.createExceptionRequest(refType, true, true)).thenReturn(exReq);

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			String result = tools.jdwp_set_exception_breakpoint(
				"java.lang.RuntimeException", null, null, null, null, triggerId, false);

			assertThat(result).contains("Chain: trigger=#" + triggerId)
				.contains("sticky");
		}

		@Test
		@DisplayName("leaves the active exception request disabled when chained")
		void shouldDisableActiveExceptionRequestWhenChained() throws Exception {
			ReferenceType refType = mock(ReferenceType.class);
			ExceptionRequest exReq = mock(ExceptionRequest.class);
			when(jdiService.findOrForceLoadClass("java.lang.RuntimeException")).thenReturn(refType);
			when(erm.createExceptionRequest(refType, true, true)).thenReturn(exReq);

			int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

			tools.jdwp_set_exception_breakpoint(
				"java.lang.RuntimeException", null, null, null, null, triggerId, false);

			// The exception request must never be enabled while a chain is wired up — same race
			// window as the line-BP variant above.
			verify(exReq, org.mockito.Mockito.never()).setEnabled(true);
			verify(exReq, org.mockito.Mockito.never()).enable();
		}
	}

	@Nested
	@DisplayName("Cascade chain break on clear")
	class ClearBreakpointCascade {

		@Test
		@DisplayName("emits CHAIN_BROKEN events when clearing a trigger with dependents")
		void shouldEmitChainBrokenEventsWhenClearingTriggerWithDependents() {
			BreakpointRequest triggerBp = mock(BreakpointRequest.class);
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			when(triggerBp.virtualMachine()).thenReturn(vm);
			int triggerId = tracker.registerBreakpoint(triggerBp);
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			String result = tools.jdwp_clear_breakpoint_by_id(triggerId);

			assertThat(result).contains("cleared successfully");
			assertThat(result).contains("chain broken");
			verify(depBp).setEnabled(true);
			assertThat(hasEventOfType("CHAIN_BROKEN")).isTrue();
			assertThat(tracker.getDependentsOfTrigger(triggerId)).isEmpty();
		}

		@Test
		@DisplayName("does not mention chain breaks when trigger had no dependents")
		void shouldNotMentionChainBreaksWhenTriggerHadNoDependents() {
			BreakpointRequest triggerBp = mock(BreakpointRequest.class);
			when(triggerBp.virtualMachine()).thenReturn(vm);
			int triggerId = tracker.registerBreakpoint(triggerBp);

			String result = tools.jdwp_clear_breakpoint_by_id(triggerId);

			assertThat(result).contains("cleared successfully");
			assertThat(result).doesNotContain("chain broken");
			assertThat(hasEventOfType("CHAIN_BROKEN")).isFalse();
		}

		@Test
		@DisplayName("exception BP clear also cascades chain break")
		void shouldEmitChainBrokenEventsWhenClearingExceptionTriggerWithDependents() {
			ExceptionRequest exTrigger = mock(ExceptionRequest.class);
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			when(exTrigger.virtualMachine()).thenReturn(vm);
			int triggerId = tracker.registerExceptionBreakpoint(exTrigger,
				ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			String result = tools.jdwp_clear_exception_breakpoint(triggerId);

			assertThat(result).contains("chain broken");
			verify(depBp).setEnabled(true);
			assertThat(hasEventOfType("CHAIN_BROKEN")).isTrue();
			assertThat(tracker.getDependentsOfTrigger(triggerId)).isEmpty();
		}

		/**
		 * {@link JDWPTools#jdwp_clear_breakpoint_by_id} validates the ID kind BEFORE running
		 * {@code cascadeChainBreak}. An exception-BP ID is rejected with a pointer to
		 * {@code jdwp_clear_exception_breakpoint}, and the chain that was pointing at the exception
		 * BP as a trigger stays intact — no spurious {@code CHAIN_BROKEN} event, no surprise
		 * re-arming of dependents.
		 */
		@Test
		@DisplayName("clear-by-id does not cascade chain when ID is an exception BP")
		void shouldNotCascadeChainBreakWhenIdIsExceptionBp() {
			ExceptionRequest exTrigger = mock(ExceptionRequest.class);
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			int triggerId = tracker.registerExceptionBreakpoint(exTrigger,
				ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			String result = tools.jdwp_clear_breakpoint_by_id(triggerId);

			// New: helpful message that points the user at the correct tool.
			assertThat(result).contains("exception breakpoint")
				.contains("jdwp_clear_exception_breakpoint");
			// Chain is intact — no CHAIN_BROKEN event, dependent still wired.
			assertThat(hasEventOfType("CHAIN_BROKEN")).isFalse();
			assertThat(tracker.getDependentsOfTrigger(triggerId)).containsExactly(depId);
			assertThat(tracker.getDependencyOfDependent(depId)).isNotNull();
		}

		/**
		 * Companion to {@link #shouldNotCascadeChainBreakWhenIdIsExceptionBp}: passing a wholly
		 * unknown ID returns the historic "not found" message without any chain side effects.
		 */
		@Test
		@DisplayName("clear-by-id with unknown ID returns 'not found' and does not cascade")
		void shouldReturnNotFoundForUnknownIdAndNotCascade() {
			ExceptionRequest exTrigger = mock(ExceptionRequest.class);
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			int triggerId = tracker.registerExceptionBreakpoint(exTrigger,
				ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			String result = tools.jdwp_clear_breakpoint_by_id(987_654);

			assertThat(result).startsWith("Breakpoint 987654 not found");
			assertThat(hasEventOfType("CHAIN_BROKEN")).isFalse();
			assertThat(tracker.getDependentsOfTrigger(triggerId)).containsExactly(depId);
		}

		/**
		 * TOCTOU race in chain registration. Disabled placeholder — not unit-testable
		 * deterministically without a deeper interleaving harness. The race: between the validation
		 * that the trigger exists and the {@code registerDependency} call, another thread can
		 * remove the trigger; the chain ends up pointing at a non-existent trigger ID. The
		 * deterministic half of this concern is asserted via the atomic-validation test in
		 * {@code BreakpointTrackerChainTest}.
		 */
		@Test
		@Disabled("TOCTOU race in chain registration — not deterministically unit-testable")
		@DisplayName("register chain atomically even if trigger is removed concurrently")
		void shouldRegisterChainAtomicallyEvenIfTriggerRemovedConcurrently() {
			// Intentionally left empty — see method JavaDoc for rationale.
		}

		/**
		 * Eager-enable race. Disabled placeholder — not unit-testable deterministically. The race:
		 * a chained dependent is registered enabled and an event fires before {@code setEnabled(false)}
		 * can run. The deterministic half (call-order invariants) is asserted via
		 * {@link #shouldSetSuspendPolicyBeforeEnableOnUnchainedBp} and the {@code never().enable()}
		 * verifies on the chained-BP setter tests.
		 */
		@Test
		@Disabled("Eager-enable race window — not deterministically unit-testable")
		@DisplayName("should not fire unchained during registration window")
		void shouldNotFireUnchainedDuringRegistrationWindow() {
			// Intentionally left empty — see method JavaDoc for rationale.
		}

		/**
		 * The {@code CHAIN_BROKEN} summary branches on pending vs active. A pending dependent is
		 * described as "still pending; will come up armed when its class loads" instead of the
		 * misleading "armed unconditionally" — the dependent has no JDI request yet, so nothing
		 * was actually armed at detach time.
		 */
		@Test
		@DisplayName("CHAIN_BROKEN summary distinguishes pending from active dependents")
		void shouldDistinguishPendingFromArmedInChainBrokenMessage() {
			BreakpointRequest triggerBp = mock(BreakpointRequest.class);
			when(triggerBp.virtualMachine()).thenReturn(vm);
			int triggerId = tracker.registerBreakpoint(triggerBp);
			int pendingDepId = tracker.registerPendingBreakpoint("com.example.Foo", 42, 2, "ALL");
			tracker.registerDependency(pendingDepId, triggerId, false);

			tools.jdwp_clear_breakpoint_by_id(triggerId);

			EventHistory.DebugEvent ev = findEventOfType("CHAIN_BROKEN");
			assertThat(ev).isNotNull();
			assertThat(ev.summary()).contains("still pending");
			assertThat(ev.summary()).doesNotContain("armed unconditionally");
		}

		/**
		 * Counterpart to {@link #shouldDistinguishPendingFromArmedInChainBrokenMessage}: an active
		 * dependent still gets the historic "armed unconditionally" wording, so the pending-specific
		 * branch has not regressed the active case.
		 */
		@Test
		@DisplayName("CHAIN_BROKEN summary keeps 'armed unconditionally' for active dependents")
		void shouldKeepArmedUnconditionallyWordingForActiveDependent() {
			BreakpointRequest triggerBp = mock(BreakpointRequest.class);
			BreakpointRequest depBp = mock(BreakpointRequest.class);
			when(triggerBp.virtualMachine()).thenReturn(vm);
			int triggerId = tracker.registerBreakpoint(triggerBp);
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			tools.jdwp_clear_breakpoint_by_id(triggerId);

			EventHistory.DebugEvent ev = findEventOfType("CHAIN_BROKEN");
			assertThat(ev).isNotNull();
			assertThat(ev.summary()).contains("armed unconditionally");
			assertThat(ev.summary()).doesNotContain("still pending");
		}
	}

	@Nested
	@DisplayName("jdwp_list_breakpoints chain rendering")
	class ListBreakpointsChainRendering {

		@Test
		@DisplayName("renders chain suffix for an ARMED active dependent")
		void shouldRenderChainSuffixInListBreakpointsForActiveDependent() {
			BreakpointRequest depBp = mockBreakpointAt("com.example.Foo", 10, EventRequest.SUSPEND_ALL);
			when(depBp.isEnabled()).thenReturn(true);
			int triggerId = tracker.registerBreakpoint(mockBreakpointAt("com.example.Trigger", 1, EventRequest.SUSPEND_ALL));
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			String result = tools.jdwp_list_breakpoints();

			assertThat(result).contains("Chain: trigger=#" + triggerId)
				.contains("sticky")
				.contains("ARMED");
		}

		@Test
		@DisplayName("renders chain suffix for a pending dependent")
		void shouldRenderChainSuffixForPendingDependent() {
			int triggerId = tracker.registerBreakpoint(mockBreakpointAt("com.example.Trigger", 1, EventRequest.SUSPEND_ALL));
			int pendingDepId = tracker.registerPendingBreakpoint("com.example.Foo", 99, 2, "all");
			tracker.registerDependency(pendingDepId, triggerId, true);

			String result = tools.jdwp_list_breakpoints();

			assertThat(result).contains("Chain: trigger=#" + triggerId)
				.contains("one-shot");
		}

		@Test
		@DisplayName("renders chain suffix in jdwp_list_exception_breakpoints")
		void shouldRenderChainOnExceptionListing() {
			BreakpointRequest triggerBp = mock(BreakpointRequest.class);
			ExceptionRequest exReq = mock(ExceptionRequest.class);
			when(exReq.isEnabled()).thenReturn(false);
			int triggerId = tracker.registerBreakpoint(triggerBp);
			int exId = tracker.registerExceptionBreakpoint(exReq,
				ExceptionBreakpointSpec.suspending("java.lang.RuntimeException", true, true));
			tracker.registerDependency(exId, triggerId, false);

			String result = tools.jdwp_list_exception_breakpoints();

			assertThat(result).contains("chain=#" + triggerId)
				.contains("sticky")
				.contains("WAITING");
		}
	}

	@Nested
	@DisplayName("jdwp_diagnose chain rendering and interpretation")
	class Diagnose {

		@Test
		@DisplayName("renders chain suffix in diagnostic report for ARMED active BP")
		void shouldRenderChainSuffixInDiagnosticReportForActiveBp() {
			BreakpointRequest depBp = mockBreakpointAt("com.example.Foo", 10, EventRequest.SUSPEND_ALL);
			when(depBp.isEnabled()).thenReturn(true);
			int triggerId = tracker.registerBreakpoint(mockBreakpointAt("com.example.Trigger", 1, EventRequest.SUSPEND_ALL));
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, triggerId, false);

			String result = tools.jdwp_diagnose(null);

			assertThat(result).contains("[chain: trigger=#" + triggerId)
				.contains("ARMED");
		}

		@Test
		@DisplayName("returns chain-stuck interpretation when all BPs are WAITING")
		void shouldReturnChainStuckInterpretationWhenAllBpsAreWaiting() {
			// The chain-stuck interpretation fires when every ACTIVE BP is a chained-WAITING
			// dependent. Use a pending trigger so it is known (satisfying registerDependency's
			// atomic validation) without contributing an "armed or unchained" active BP to the
			// tally.
			int pendingTriggerId = tracker.registerPendingBreakpoint("com.example.Trigger", 1, 2, "ALL");
			BreakpointRequest depBp = mockBreakpointAt("com.example.Foo", 10, EventRequest.SUSPEND_ALL);
			when(depBp.isEnabled()).thenReturn(false);
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, pendingTriggerId, false);

			String result = tools.jdwp_diagnose(null);

			assertThat(result).contains("Every active BP is WAITING on a trigger");
		}

		@Test
		@DisplayName("does not return chain-stuck when any BP is armed or unchained")
		void shouldNotReturnChainStuckWhenAnyBpIsArmedOrUnchained() {
			// One unchained active BP — must short-circuit the chain-stuck interpretation.
			BreakpointRequest unchainedBp = mockBreakpointAt("com.example.Plain", 5, EventRequest.SUSPEND_ALL);
			when(unchainedBp.isEnabled()).thenReturn(true);
			tracker.registerBreakpoint(unchainedBp);

			// And a chained dependent that IS waiting, just to make the test realistic. Use a
			// pending trigger BP so the dependent has a known trigger ID without inflating the
			// "unchained-or-armed" active count.
			int pendingTriggerId = tracker.registerPendingBreakpoint("com.example.Trigger", 1, 2, "ALL");
			BreakpointRequest depBp = mockBreakpointAt("com.example.Foo", 10, EventRequest.SUSPEND_ALL);
			when(depBp.isEnabled()).thenReturn(false);
			int depId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(depId, pendingTriggerId, false);

			String result = tools.jdwp_diagnose(null);

			assertThat(result).doesNotContain("Every active BP is WAITING on a trigger");
		}

		/**
		 * {@code describeChainStuckState} folds pending BPs into the tally and surfaces them in
		 * the interpretation line. A user with both an active chained-WAITING BP and a pending BP
		 * sees the pending count called out explicitly so the guidance reflects every BP in the
		 * registry rather than only the active half.
		 */
		@Test
		@DisplayName("chain-stuck message mentions pending BPs alongside active WAITING ones")
		void shouldMentionPendingBpsInChainStuckDiagnostic() {
			// One active chained-WAITING BP plus one pending BP. The active BP needs a known
			// trigger ID (so registerDependency's atomic validation accepts the edge): a pending
			// trigger fits the bill without inflating the unchained-or-armed tally.
			int pendingTriggerId = tracker.registerPendingBreakpoint("com.example.Trigger", 1, 2, "ALL");
			BreakpointRequest depBp = mockBreakpointAt("com.example.Foo", 10, EventRequest.SUSPEND_ALL);
			when(depBp.isEnabled()).thenReturn(false);
			int activeDepId = tracker.registerBreakpoint(depBp);
			tracker.registerDependency(activeDepId, pendingTriggerId, false);

			tracker.registerPendingBreakpoint("com.example.Pending", 42, 2, "ALL");

			String result = tools.jdwp_diagnose(null);

			final int interpStart = result.indexOf("INTERPRETATION:");
			assertThat(interpStart).isGreaterThanOrEqualTo(0);
			final String interpretation = result.substring(interpStart);
			// Active chained-WAITING reference is still there.
			assertThat(interpretation).contains("Every active BP is WAITING on a trigger");
			// The pending count is surfaced explicitly with the total and a sample pending ID —
			// e.g. "2 pending BP(s) (e.g. #1) …".
			assertThat(interpretation).contains("2 pending BP(s)");
			assertThat(interpretation).contains("waiting for their class to load");
			assertThat(interpretation).contains("[PENDING]");
		}

		// A "single pending" variant is intentionally omitted — the 2-pending assertion above
		// already exercises both the count digit and the example-ID rendering.
	}
}
