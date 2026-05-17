package one.edee.mcp.jdwp;

import com.sun.jdi.Field;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.AccessWatchpointRequest;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ModificationWatchpointRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the field-breakpoint MCP tool surface: {@link JDWPTools#jdwp_set_field_breakpoint},
 * {@link JDWPTools#jdwp_set_field_logpoint}, the {@code jdwp_overview} field-breakpoint section, and the
 * field-routing branch of {@link JDWPTools#jdwp_clear_breakpoint}.
 *
 *
 * Covers the hard-error contract (ambiguous / missing field, static + objectFilter, invalid mode,
 * unknown trigger, missing expression), the eager + deferred paths, BOTH-mode request creation, and
 * the unified clear-by-id routing across line / exception / field kinds.
 */
@DisplayName("jdwp field breakpoint tools")
class JDWPToolsFieldBreakpointTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker tracker;
	private JDWPTools tools;
	private VirtualMachine vm;
	private EventRequestManager erm;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		final WatcherManager watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
	}

	@Test
	@DisplayName("eager suspending — creates one watchpoint request and reports the synthetic id")
	void shouldCreateEagerAccessWatchpoint() {
		final ReferenceType refType = mockClassWithField("com.Foo", "counter", false);
		final AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		when(erm.createAccessWatchpointRequest(refType.allFields().get(0))).thenReturn(req);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", "access", null, null, null, null, null);

		assertThat(result).startsWith("Field breakpoint set");
		assertThat(result).contains("com.Foo").contains("counter")
			.contains("Mode: access (suspend)");
		verify(req).setEnabled(true);
	}

	@Test
	@DisplayName("BOTH mode creates an access AND a modification request and enables both")
	void shouldCreateBothRequestsForBothMode() {
		final ReferenceType refType = mockClassWithField("com.Foo", "counter", false);
		final Field field = refType.allFields().get(0);
		final AccessWatchpointRequest accReq = mock(AccessWatchpointRequest.class);
		final ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
		when(erm.createAccessWatchpointRequest(field)).thenReturn(accReq);
		when(erm.createModificationWatchpointRequest(field)).thenReturn(modReq);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", "BOTH", null, null, null, null, null);

		assertThat(result).startsWith("Field breakpoint set").contains("Mode: both");
		verify(erm).createAccessWatchpointRequest(field);
		verify(erm).createModificationWatchpointRequest(field);
		verify(accReq).setEnabled(true);
		verify(modReq).setEnabled(true);
	}

	@Test
	@DisplayName("deferred path — registers a ClassPrepareRequest when the class is unloaded")
	void shouldDeferWhenClassIsUnloaded() {
		final ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		when(jdiService.findOrForceLoadClass("com.NotLoaded")).thenReturn(null);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.NotLoaded", "value", "modification", null, null, null, null, null);

		assertThat(result).startsWith("Field breakpoint deferred")
			.contains("com.NotLoaded").contains("value")
			.contains("Mode: modification (suspend)");
		verify(cpr).addClassFilter("com.NotLoaded");
		verify(cpr).enable();
	}

	@Test
	@DisplayName("ambiguous field — hard error, no JDI request created")
	void shouldRejectAmbiguousField() {
		final ReferenceType refType = mock(ReferenceType.class);
		Field f1 = mock(Field.class);
		Field f2 = mock(Field.class);
		when(f1.name()).thenReturn("counter");
		when(f2.name()).thenReturn("counter");
		when(refType.allFields()).thenReturn(List.of(f1, f2));
		when(jdiService.findOrForceLoadClass("com.Ambig")).thenReturn(refType);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Ambig", "counter", "access", null, null, null, null, null);

		assertThat(result).startsWith("Error:").contains("ambiguous");
	}

	@Test
	@DisplayName("missing field — hard error")
	void shouldRejectMissingField() {
		final ReferenceType refType = mock(ReferenceType.class);
		when(refType.allFields()).thenReturn(List.of());
		when(jdiService.findOrForceLoadClass("com.Foo")).thenReturn(refType);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "missing", "access", null, null, null, null, null);

		assertThat(result).startsWith("Error:").contains("not found");
	}

	@Test
	@DisplayName("objectFilterId on a static field — hard error")
	void shouldRejectObjectFilterOnStaticField() {
		mockClassWithField("com.Foo", "instances", /* isStatic */ true);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "instances", "modification", null, null, 42L, null, null);

		assertThat(result).startsWith("Error:").contains("static").contains("objectFilterId");
	}

	@Test
	@DisplayName("invalid mode — hard error")
	void shouldRejectInvalidMode() {
		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", "sideways", null, null, null, null, null);

		assertThat(result).startsWith("Error:").contains("invalid mode").contains("sideways");
	}

	@Test
	@DisplayName("unknown trigger BP id — hard error before touching the VM")
	void shouldRejectUnknownTrigger() {
		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", "access", null, null, null, 999, null);

		assertThat(result).startsWith("Error:").contains("Trigger breakpoint #999");
	}

	@Test
	@DisplayName("known trigger — leaves the watchpoint disabled and embeds the chain suffix")
	void shouldKeepWatchpointDisabledWhenChained() {
		final ReferenceType refType = mockClassWithField("com.Foo", "counter", false);
		final AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		when(erm.createAccessWatchpointRequest(refType.allFields().get(0))).thenReturn(req);
		final int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", "access", null, null, null, triggerId, true);

		assertThat(result).contains("Chain: trigger=#" + triggerId).contains("one-shot");
		// Chained BPs stay disarmed until the trigger fires — must NOT have been enabled.
		verify(req, org.mockito.Mockito.never()).setEnabled(true);
	}

	@Test
	@DisplayName("jdwp_set_field_logpoint without expression — hard error")
	void shouldRejectFieldLogpointWithoutExpression() {
		final String result = tools.jdwp_set_field_logpoint(
			"com.Foo", "counter", "access", "", null, null, null, null, null);

		assertThat(result).startsWith("Error:").contains("expression is required");
	}

	@Test
	@DisplayName("jdwp_set_field_logpoint embeds the expression and reports log-only mode")
	void shouldRecordExpressionForFieldLogpoint() {
		final ReferenceType refType = mockClassWithField("com.Foo", "ttl", false);
		final ModificationWatchpointRequest req = mock(ModificationWatchpointRequest.class);
		when(erm.createModificationWatchpointRequest(refType.allFields().get(0))).thenReturn(req);

		final String result = tools.jdwp_set_field_logpoint(
			"com.Foo", "ttl", "modification", "$oldValue + \" -> \" + $newValue",
			null, null, null, null, null);

		assertThat(result).startsWith("Field breakpoint set")
			.contains("log-only")
			.contains("Expression: $oldValue + \" -> \" + $newValue");
	}

	@Test
	@DisplayName("jdwp_overview(types=\"field_breakpoint\") emits an empty section when nothing is set")
	void shouldEmitEmptyFieldBreakpointSection() {
		final String result = tools.jdwp_overview("field_breakpoint", null);
		assertThat(result).contains("Field breakpoints (0):");
	}

	@Test
	@DisplayName("jdwp_overview(types=\"field_breakpoint\") lists active + pending entries")
	void shouldListActiveAndPendingFieldBreakpoints() {
		// Active BP via registerFieldBreakpoint
		final AccessWatchpointRequest activeReq = mock(AccessWatchpointRequest.class);
		when(activeReq.isEnabled()).thenReturn(true);
		final int activeId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.ACCESS,
				null, null, null),
			activeReq, null);
		// Pending BP via registerPendingFieldBreakpoint
		final int pendingId = tracker.registerPendingFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.logOnly(
				"com.Bar", "session", BreakpointTracker.FieldWatchMode.MODIFICATION,
				"$newValue", null, null, null));

		final String result = tools.jdwp_overview("field_breakpoint", null);

		assertThat(result)
			.contains("Field breakpoints (2):")
			.contains("com.Foo.counter")
			.contains("com.Bar.session")
			.contains("#" + activeId)
			.contains("#" + pendingId)
			.contains("[PENDING]");
	}

	@Test
	@DisplayName("jdwp_clear_breakpoint routes by ID to the field BP path")
	void shouldClearFieldBreakpointById() {
		final AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		// tearDownFieldRequestQuietly calls req.virtualMachine().eventRequestManager().deleteEventRequest(req);
		// wire the chain back to the test's erm so the verify below can observe the call.
		when(req.virtualMachine()).thenReturn(vm);
		final int id = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.ACCESS,
				null, null, null),
			req, null);

		final String result = tools.jdwp_clear_breakpoint(id);

		assertThat(result).startsWith("Field breakpoint " + id + " cleared");
		assertThat(tracker.getAllFieldBreakpoints()).doesNotContainKey(id);
		// Underlying JDI request torn down so a future event on it doesn't fire on a stale tracker.
		verify(erm).deleteEventRequest(req);
	}

	@Test
	@DisplayName("threadFilterId resolves a live thread and is applied to the watchpoint request")
	void shouldApplyThreadFilterWhenThreadIsAlive() {
		final ReferenceType refType = mockClassWithField("com.Foo", "counter", false);
		final AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		when(erm.createAccessWatchpointRequest(refType.allFields().get(0))).thenReturn(req);
		final com.sun.jdi.ThreadReference thread = mock(com.sun.jdi.ThreadReference.class);
		when(thread.uniqueID()).thenReturn(7L);
		when(vm.allThreads()).thenReturn(List.of(thread));

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", "access", null, 7L, null, null, null);

		assertThat(result).startsWith("Field breakpoint set").contains("Filters: thread=7");
		verify(req).addThreadFilter(thread);
	}

	@Test
	@DisplayName("threadFilterId pointing at a dead thread → hard error + rollback of the half-created request")
	void shouldRollBackWhenThreadFilterResolutionFails() {
		final ReferenceType refType = mockClassWithField("com.Foo", "counter", false);
		final AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		when(erm.createAccessWatchpointRequest(refType.allFields().get(0))).thenReturn(req);
		when(vm.allThreads()).thenReturn(List.of()); // no live threads → thread #999 cannot be found

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", "access", null, 999L, null, null, null);

		assertThat(result).startsWith("Error: failed to create field watchpoint")
			.contains("Thread #999 no longer alive");
		// Half-created request must be deleted so it doesn't leak armed on the target VM.
		verify(erm).deleteEventRequest(req);
	}

	@Test
	@DisplayName("objectFilterId resolves a cached non-static instance and is applied")
	void shouldApplyObjectFilterWhenInstanceIsCached() {
		final ReferenceType refType = mockClassWithField("com.Foo", "balance", /* isStatic */ false);
		final ModificationWatchpointRequest req = mock(ModificationWatchpointRequest.class);
		when(erm.createModificationWatchpointRequest(refType.allFields().get(0))).thenReturn(req);
		final com.sun.jdi.ObjectReference instance = mock(com.sun.jdi.ObjectReference.class);
		when(jdiService.getCachedObject(42L)).thenReturn(instance);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "balance", "modification", null, null, 42L, null, null);

		assertThat(result).startsWith("Field breakpoint set").contains("object=42");
		verify(req).addInstanceFilter(instance);
	}

	@Test
	@DisplayName("objectFilterId not in cache → hard error + rollback")
	void shouldRollBackWhenObjectFilterIsStale() {
		final ReferenceType refType = mockClassWithField("com.Foo", "balance", false);
		final ModificationWatchpointRequest req = mock(ModificationWatchpointRequest.class);
		when(erm.createModificationWatchpointRequest(refType.allFields().get(0))).thenReturn(req);
		when(jdiService.getCachedObject(999L)).thenReturn(null);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "balance", "modification", null, null, 999L, null, null);

		assertThat(result).startsWith("Error: failed to create field watchpoint")
			.contains("Object #999 no longer in cache");
		verify(erm).deleteEventRequest(req);
	}

	@Test
	@DisplayName("BOTH-mode rollback: second-half createModificationWatchpointRequest throws → both halves cleaned up")
	void shouldRollBackBothHalvesWhenSecondCreationThrows() {
		final ReferenceType refType = mockClassWithField("com.Foo", "counter", false);
		final AccessWatchpointRequest accReq = mock(AccessWatchpointRequest.class);
		when(erm.createAccessWatchpointRequest(refType.allFields().get(0))).thenReturn(accReq);
		when(erm.createModificationWatchpointRequest(refType.allFields().get(0)))
			.thenThrow(new RuntimeException("vm capacity exceeded"));

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", "both", null, null, null, null, null);

		assertThat(result).startsWith("Error: failed to create field watchpoint")
			.contains("vm capacity exceeded");
		// First half (access) was created; it must be torn down on the rollback path. The
		// modification request itself never existed (throw happened during createModification...).
		verify(erm).deleteEventRequest(accReq);
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({
		"access,access",
		"ACCESS,access",
		"modification,modification",
		"MODIFY,modification",
		"write,modification",
		"both,both",
		"BoTh,both"
	})
	@DisplayName("mode parsing accepts case-insensitive synonyms")
	void shouldAcceptModeSynonyms(String input, String canonical) {
		final ReferenceType refType = mockClassWithField("com.Foo", "counter", false);
		when(erm.createAccessWatchpointRequest(refType.allFields().get(0)))
			.thenReturn(mock(AccessWatchpointRequest.class));
		when(erm.createModificationWatchpointRequest(refType.allFields().get(0)))
			.thenReturn(mock(ModificationWatchpointRequest.class));

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Foo", "counter", input, null, null, null, null, null);

		assertThat(result).startsWith("Field breakpoint set").contains("Mode: " + canonical);
	}

	@Test
	@DisplayName("clearing a chained-trigger field BP cascades CHAIN_BROKEN to dependents")
	void shouldCascadeChainBreakWhenFieldTriggerCleared() {
		final AccessWatchpointRequest triggerReq = mock(AccessWatchpointRequest.class);
		when(triggerReq.virtualMachine()).thenReturn(vm);
		final int triggerId = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.ACCESS,
				null, null, null),
			triggerReq, null);
		final BreakpointRequest depReq = mock(BreakpointRequest.class);
		final int depId = tracker.registerBreakpoint(depReq);
		tracker.registerDependency(depId, triggerId, /* oneShot */ false);

		final String result = tools.jdwp_clear_breakpoint(triggerId);

		assertThat(result).contains("Field breakpoint " + triggerId + " cleared")
			.contains("1 dependent BP(s) armed (chain broken)");
		verify(depReq).setEnabled(true);
		assertThat(depId).isPositive();
	}

	@Test
	@DisplayName("clearing a field BP also removes attached watchers")
	void shouldRemoveWatchersAttachedToFieldBp() {
		final AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		when(req.virtualMachine()).thenReturn(vm);
		final int id = tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.ACCESS,
				null, null, null),
			req, null);
		// Attach a real watcher to the field BP id via the tool surface
		final WatcherManager watchers = (WatcherManager) extractField(tools, "watcherManager");
		watchers.createWatcher("trace-counter", id, "$oldValue");
		assertThat(watchers.getWatchersForBreakpoint(id)).hasSize(1);

		final String result = tools.jdwp_clear_breakpoint(id);

		assertThat(result).contains("Field breakpoint " + id + " cleared")
			.contains("1 associated watcher(s) also removed");
		assertThat(watchers.getWatchersForBreakpoint(id)).isEmpty();
	}

	@Test
	@DisplayName("jdwp_clear(types=\"field_breakpoint\") clears all field BPs and reports the total")
	void shouldClearAllFieldBreakpoints() {
		final AccessWatchpointRequest req = mock(AccessWatchpointRequest.class);
		when(req.virtualMachine()).thenReturn(vm);
		tracker.registerFieldBreakpoint(
			BreakpointTracker.FieldBreakpointSpec.suspending(
				"com.Foo", "counter", BreakpointTracker.FieldWatchMode.ACCESS,
				null, null, null),
			req, null);

		final String result = tools.jdwp_clear("field_breakpoint", null);

		assertThat(result).contains("Field breakpoints: 1 match")
			.contains("Total cleared: 1");
		assertThat(tracker.getAllFieldBreakpoints()).isEmpty();
	}

	@Test
	@DisplayName("jdwp_clear(types=\"field_breakpoint\") reports nothing matched when no field BPs exist")
	void shouldReportNothingMatchedWhenNoFieldBreakpoints() {
		final String result = tools.jdwp_clear("field_breakpoint", null);
		assertThat(result).contains("(nothing matched)");
	}

	@Test
	@DisplayName("deferred field BP with objectFilterId surfaces a Note about deferred static check")
	void shouldSurfaceDeferredObjectFilterWarning() {
		final ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		when(jdiService.findOrForceLoadClass("com.Later")).thenReturn(null);
		when(erm.createClassPrepareRequest()).thenReturn(cpr);

		final String result = tools.jdwp_set_field_breakpoint(
			"com.Later", "balance", "modification", null, null, 42L, null, null);

		assertThat(result).startsWith("Field breakpoint deferred")
			.contains("Note: objectFilterId is set")
			.contains("jdwp_overview(types=\"field_breakpoint\")");
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	/**
	 * Builds a {@link ReferenceType} mock for {@code className} carrying exactly one {@link Field}
	 * named {@code fieldName}. Wires {@code jdiService.findOrForceLoadClass(className)} to return
	 * the type so the eager path under test hits the field-resolution branch.
	 */
	private ReferenceType mockClassWithField(String className, String fieldName, boolean isStatic) {
		final ReferenceType refType = mock(ReferenceType.class);
		final Field field = mock(Field.class);
		when(field.name()).thenReturn(fieldName);
		when(field.isStatic()).thenReturn(isStatic);
		when(refType.allFields()).thenReturn(List.of(field));
		when(jdiService.findOrForceLoadClass(className)).thenReturn(refType);
		return refType;
	}

	/**
	 * Reflective field accessor for the watcher orphan-cleanup test. The tool surface composes a
	 * {@link WatcherManager} via the seven-argument constructor, but the field-BP watcher path is
	 * the only test in this file that needs the live manager; threading it through every other test
	 * via a parameter would clutter the unrelated scenarios.
	 */
	private static Object extractField(Object target, String fieldName) {
		try {
			final java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
			f.setAccessible(true);
			return f.get(target);
		} catch (Exception e) {
			throw new AssertionError("Cannot reflectively read " + fieldName + " on " + target, e);
		}
	}
}
