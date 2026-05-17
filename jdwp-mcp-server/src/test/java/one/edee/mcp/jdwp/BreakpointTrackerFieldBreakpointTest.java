package one.edee.mcp.jdwp;

import com.sun.jdi.Field;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.AccessWatchpointRequest;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ModificationWatchpointRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the field-watchpoint state in {@link BreakpointTracker}: registration of
 * access-only, modification-only, and both-mode field BPs (the latter binds one synthetic ID to
 * two underlying JDI requests), pending → active promotion mirroring the exception-BP scaffolding,
 * removal cascading through both reverse indices, and {@code reset} wiping every field-BP map.
 */
@DisplayName("BreakpointTracker field watchpoints")
class BreakpointTrackerFieldBreakpointTest {

	private BreakpointTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
	}

	@Test
	@DisplayName("registers an access-only field BP and indexes by request")
	void registersAccessOnlyFieldBreakpoint() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null);
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);

		int id = tracker.registerFieldBreakpoint(spec, accessReq, null);

		assertThat(tracker.findFieldIdByRequest(accessReq)).isEqualTo(id);
		assertThat(tracker.findFieldInfoByRequest(accessReq).getSpec()).isSameAs(spec);
		assertThat(tracker.getAllFieldBreakpoints()).containsKey(id);
		assertThat(tracker.isKnownBreakpointId(id)).isTrue();
	}

	@Test
	@DisplayName("registers a modification-only field BP")
	void registersModificationOnlyFieldBreakpoint() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.MODIFICATION, null, null, null);
		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);

		int id = tracker.registerFieldBreakpoint(spec, null, modReq);

		assertThat(tracker.findFieldIdByRequest(modReq)).isEqualTo(id);
		assertThat(tracker.getAllFieldBreakpoints().get(id).getModificationRequest()).isSameAs(modReq);
		assertThat(tracker.getAllFieldBreakpoints().get(id).getAccessRequest()).isNull();
	}

	@Test
	@DisplayName("both-mode binds one synthetic ID to two JDI requests")
	void registersBothModeWithTwoRequestsSharingOneId() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.BOTH, null, null, null);
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);
		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);

		int id = tracker.registerFieldBreakpoint(spec, accessReq, modReq);

		assertThat(tracker.findFieldIdByRequest(accessReq)).isEqualTo(id);
		assertThat(tracker.findFieldIdByRequest(modReq)).isEqualTo(id);
		assertThat(tracker.findFieldInfoByRequest(accessReq))
			.isSameAs(tracker.findFieldInfoByRequest(modReq));
	}

	@Test
	@DisplayName("registering with both requests null throws IllegalArgumentException")
	void rejectsRegistrationWithoutAnyRequest() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null);
		try {
			tracker.registerFieldBreakpoint(spec, null, null);
			throw new AssertionError("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertThat(expected).hasMessageContaining("At least one");
		}
	}

	@Test
	@DisplayName("pending field BP is listed by class and survives until promoted")
	void pendingFieldBreakpointSurvivesUntilPromoted() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.MODIFICATION, null, null, null);

		int id = tracker.registerPendingFieldBreakpoint(spec);

		assertThat(tracker.getAllPendingFieldBreakpoints()).containsKey(id);
		assertThat(tracker.getPendingFieldBreakpointsForClass("com.x.Foo"))
			.singleElement()
			.satisfies(e -> {
				assertThat(e.getKey()).isEqualTo(id);
				assertThat(e.getValue().getSpec()).isSameAs(spec);
			});
		assertThat(tracker.isKnownBreakpointId(id)).isTrue();

		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
		tracker.promotePendingFieldToActive(id, null, modReq);

		assertThat(tracker.getAllPendingFieldBreakpoints()).doesNotContainKey(id);
		assertThat(tracker.getAllFieldBreakpoints()).containsKey(id);
		assertThat(tracker.findFieldIdByRequest(modReq)).isEqualTo(id);
	}

	@Test
	@DisplayName("markPendingFieldFailed records the reason without removing the entry")
	void markPendingFieldFailedRecordsReason() {
		int id = tracker.registerPendingFieldBreakpoint(BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null));

		tracker.markPendingFieldFailed(id, "Field 'bar' not found on com.x.Foo");

		assertThat(tracker.getAllPendingFieldBreakpoints().get(id).getFailureReason())
			.isEqualTo("Field 'bar' not found on com.x.Foo");
	}

	@Test
	@DisplayName("removeFieldBreakpoint drops both maps for a BOTH-mode entry")
	void removeFieldBreakpointDropsBothRequestsForBothMode() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.BOTH, null, null, null);
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);
		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
		int id = tracker.registerFieldBreakpoint(spec, accessReq, modReq);

		assertThat(tracker.removeFieldBreakpoint(id)).isTrue();

		assertThat(tracker.findFieldIdByRequest(accessReq)).isNull();
		assertThat(tracker.findFieldIdByRequest(modReq)).isNull();
		assertThat(tracker.findFieldInfoByRequest(accessReq)).isNull();
		assertThat(tracker.findFieldInfoByRequest(modReq)).isNull();
		assertThat(tracker.getAllFieldBreakpoints()).doesNotContainKey(id);
		assertThat(tracker.isKnownBreakpointId(id)).isFalse();
	}

	@Test
	@DisplayName("removeFieldBreakpoint cleans pending entries too")
	void removeFieldBreakpointHandlesPending() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null);
		int id = tracker.registerPendingFieldBreakpoint(spec);

		assertThat(tracker.removeFieldBreakpoint(id)).isTrue();
		assertThat(tracker.getAllPendingFieldBreakpoints()).doesNotContainKey(id);
		assertThat(tracker.isKnownBreakpointId(id)).isFalse();
	}

	@Test
	@DisplayName("removeFieldBreakpoint on unknown ID returns false without throwing")
	void removeFieldBreakpointReturnsFalseForUnknownId() {
		assertThat(tracker.removeFieldBreakpoint(987_654)).isFalse();
	}

	@Test
	@DisplayName("reset wipes every field BP map")
	void resetWipesFieldBreakpointState() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.BOTH, null, null, null);
		tracker.registerFieldBreakpoint(spec,
			mock(AccessWatchpointRequest.class), mock(ModificationWatchpointRequest.class));
		tracker.registerPendingFieldBreakpoint(spec);

		tracker.reset();

		assertThat(tracker.getAllFieldBreakpoints()).isEmpty();
		assertThat(tracker.getAllPendingFieldBreakpoints()).isEmpty();
	}

	@Test
	@DisplayName("logOnly factory captures expression and flips the flag")
	void logOnlySpecFactory() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.logOnly(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.MODIFICATION,
			"\"value=\" + $newValue", 5L, 42L, "$newValue != null");

		assertThat(spec.logOnly()).isTrue();
		assertThat(spec.expression()).isEqualTo("\"value=\" + $newValue");
		assertThat(spec.threadFilterId()).isEqualTo(5L);
		assertThat(spec.objectFilterId()).isEqualTo(42L);
		assertThat(spec.condition()).isEqualTo("$newValue != null");
	}

	@Test
	@DisplayName("getEventRequestById returns the access request for a BOTH-mode entry")
	void shouldReturnAccessRequestFromGetEventRequestByIdForBothMode() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.BOTH, null, null, null);
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);
		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
		int id = tracker.registerFieldBreakpoint(spec, accessReq, modReq);

		EventRequest resolved = tracker.getEventRequestById(id);

		assertThat(resolved).isSameAs(accessReq);
	}

	@Test
	@DisplayName("getEventRequestById falls back to the modification request when access is absent")
	void shouldReturnModificationRequestFromGetEventRequestByIdForModificationOnlyMode() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.MODIFICATION, null, null, null);
		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
		int id = tracker.registerFieldBreakpoint(spec, null, modReq);

		EventRequest resolved = tracker.getEventRequestById(id);

		assertThat(resolved).isSameAs(modReq);
	}

	@Test
	@DisplayName("getEventRequestById returns null for a pending field BP but the ID is still known")
	void shouldReturnNullFromGetEventRequestByIdForPendingFieldBp() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null);
		int id = tracker.registerPendingFieldBreakpoint(spec);

		assertThat(tracker.getEventRequestById(id)).isNull();
		assertThat(tracker.isKnownBreakpointId(id)).isTrue();
	}

	@Test
	@DisplayName("removing one pending field BP keeps the ClassPrepareRequest alive while another pending entry references the same class")
	void shouldKeepClassPrepareRequestAliveWhenAnotherPendingFieldBpReferencesSameClass() {
		final String className = "com.x.Foo";
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			className, "bar", BreakpointTracker.FieldWatchMode.ACCESS, null, null, null);
		int firstId = tracker.registerPendingFieldBreakpoint(spec);
		int secondId = tracker.registerPendingFieldBreakpoint(spec);
		ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		when(cpr.virtualMachine()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		tracker.registerClassPrepareRequest(className, cpr);

		assertThat(tracker.removeFieldBreakpoint(firstId)).isTrue();
		assertThat(tracker.hasClassPrepareRequest(className))
			.as("CPR must stay alive while another pending field BP still targets the class")
			.isTrue();

		assertThat(tracker.removeFieldBreakpoint(secondId)).isTrue();
		assertThat(tracker.hasClassPrepareRequest(className))
			.as("CPR must be released once the last pending field BP referencing the class is gone")
			.isFalse();
	}

	@Test
	@DisplayName("markPendingFieldFailed is a no-op for an unknown ID")
	void shouldBeNoOpWhenMarkPendingFieldFailedReceivesUnknownId() {
		tracker.markPendingFieldFailed(987_654, "x");

		assertThat(tracker.getAllPendingFieldBreakpoints()).isEmpty();
	}

	@Test
	@DisplayName("clearAll deletes both underlying requests of a BOTH-mode field entry")
	void clearAllInvokesDeleteOnBothRequestsForBothModeEntry() {
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.BOTH, null, null, null);
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);
		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
		tracker.registerFieldBreakpoint(spec, accessReq, modReq);
		EventRequestManager erm = mock(EventRequestManager.class);

		tracker.clearAll(erm);

		verify(erm, atLeastOnce()).deleteEventRequest(accessReq);
		verify(erm, atLeastOnce()).deleteEventRequest(modReq);
		assertThat(tracker.getAllFieldBreakpoints()).isEmpty();
	}

	@Test
	@DisplayName("arming a BOTH-mode chain dependent enables both of its underlying requests")
	void bothModeChainDependentArmsBothRequestsWhenTriggerFires() {
		final int triggerId = tracker.registerBreakpoint(mock(BreakpointRequest.class));
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.BOTH, null, null, null);
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);
		ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
		final int dependentId = tracker.registerFieldBreakpoint(spec, accessReq, modReq);
		tracker.registerDependency(dependentId, triggerId, false);
		// Chain disarm: both underlying requests start disabled, awaiting the trigger fire.
		accessReq.setEnabled(false);
		modReq.setEnabled(false);

		tracker.markTriggerFired(triggerId);
		// Mirror the JdiEventListener arm-step: iterate dependents and enable the logical BP for
		// each dependent ID. setBreakpointEnabledById toggles every underlying JDI request, so a
		// BOTH-mode field BP has both halves re-armed atomically — neither event kind can slip
		// through disarmed after the trigger fires.
		for (Integer depId : tracker.getDependentsOfTrigger(triggerId)) {
			tracker.setBreakpointEnabledById(depId, true);
		}

		verify(accessReq).setEnabled(true);
		verify(modReq).setEnabled(true);
	}

	@Test
	@DisplayName("tryPromotePending deletes the orphan access request when modification creation fails for BOTH mode")
	void tryPromotePendingDeletesOrphanRequestsWhenSecondHalfCreationFails() throws Exception {
		JDIConnectionService service = mock(JDIConnectionService.class);
		VirtualMachine vm = mock(VirtualMachine.class);
		EventRequestManager erm = mock(EventRequestManager.class);
		ReferenceType refType = mock(ReferenceType.class);
		Field field = mock(Field.class);
		AccessWatchpointRequest accessReq = mock(AccessWatchpointRequest.class);
		when(service.getRawVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(service.findOrForceLoadClass(eq("com.x.Foo"), any())).thenReturn(refType);
		when(refType.allFields()).thenReturn(List.of(field));
		when(field.name()).thenReturn("bar");
		when(field.isStatic()).thenReturn(false);
		when(erm.createAccessWatchpointRequest(field)).thenReturn(accessReq);
		when(erm.createModificationWatchpointRequest(field))
			.thenThrow(new RuntimeException("simulated VM transition"));
		BreakpointTracker.FieldBreakpointSpec spec = BreakpointTracker.FieldBreakpointSpec.suspending(
			"com.x.Foo", "bar", BreakpointTracker.FieldWatchMode.BOTH, null, null, null);
		int id = tracker.registerPendingFieldBreakpoint(spec);

		int promoted = tracker.tryPromotePending(service, null);

		assertThat(promoted).isZero();
		// Pending entry stays in the map so it surfaces in jdwp_overview(types="field_breakpoint"), but its
		// failure reason is set so subsequent tryPromotePending cycles skip it instead of retrying
		// the same orphan-prone code path on every tool call.
		assertThat(tracker.getAllPendingFieldBreakpoints()).containsKey(id);
		assertThat(tracker.getAllPendingFieldBreakpoints().get(id).getFailureReason()).isNotNull();
		// Rollback contract: the half-created access request must be deleted from the
		// EventRequestManager, otherwise an armed watchpoint stays live on the target VM with no
		// tracker entry pointing at it (findFieldInfoByRequest would return null on every hit).
		verify(erm).deleteEventRequest(accessReq);
		assertThat(tracker.getAllFieldBreakpoints()).doesNotContainKey(id);
	}
}
