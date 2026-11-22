package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.*;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.BreakpointTracker;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.JdiEventListener;
import one.edee.mcp.jdwp.evaluation.FastBreakpointProbeSource;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The arming hit of a fast breakpoint against a resume of its thread: {@code jdwp_resume} and
 * {@code jdwp_resume_until_event} resume the whole VM, which takes the arming thread away from the
 * invocations on it. A hit lost that way is not the condition's failure — the entry waits for the
 * next hit — and the resume tools can wait until no arming is in flight.
 */
@DisplayName("Fast-breakpoint arming against a resume of its thread")
class FastLogpointServiceArmingTest {

	private static final int LINE = 42;

	private EventHistory history;
	private JdiExpressionEvaluator evaluator;
	private FastLogpointService service;
	private VirtualMachine vm;
	private EventRequestManager requests;
	private Location location;
	private ThreadReference thread;
	private StackFrame frame;
	private BreakpointRequest armingRequest;
	/**
	 * Every breakpoint request the service created, in order.
	 */
	private final List<BreakpointRequest> created = new ArrayList<>();

	/** Builds a pending fast breakpoint and mocks its arming event and target VM. */
	@BeforeEach
	void setUp() throws Exception {
		history = new EventHistory();
		evaluator = mock(JdiExpressionEvaluator.class);
		service = new FastLogpointService(new BreakpointTracker(), history, new EvaluationGuard(), evaluator,
			mock(JdiEventListener.class), mock(JDIConnectionService.class));

		vm = mock(VirtualMachine.class);
		requests = mock(EventRequestManager.class);
		when(vm.eventRequestManager()).thenReturn(requests);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn("com.example.Cart");
		final Method method = mock(Method.class);
		when(method.name()).thenReturn("total");
		when(method.signature()).thenReturn("()I");
		location = mock(Location.class);
		when(location.method()).thenReturn(method);
		when(location.declaringType()).thenReturn(type);
		when(location.lineNumber()).thenReturn(LINE);
		when(type.locationsOfLine(LINE)).thenReturn(List.of(location));
		when(requests.createBreakpointRequest(location)).thenAnswer(invocation -> {
			final BreakpointRequest request = mock(BreakpointRequest.class);
			created.add(request);
			return request;
		});

		thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("worker-0");
		frame = mock(StackFrame.class);
		when(frame.location()).thenReturn(location);
		when(thread.frame(0)).thenReturn(frame);

		service.registerBreakpoint(vm, location, "total > 100", null, true, EventRequest.SUSPEND_ALL,
			FastLogpointService.BREAKPOINT_ERROR_BUFFER_SIZE);
		armingRequest = created.get(0);
	}

	@Test
	@DisplayName("a mismatched class-prepare event leaves a deferred probe waiting for its exact class")
	void waitingProbeRequiresExactClassName() throws Exception {
		final ClassPrepareRequest prepare = mock(ClassPrepareRequest.class);
		when(requests.createClassPrepareRequest()).thenReturn(prepare);
		final int id = service.registerWaiting(vm, FastLogpointService.Kind.LOGPOINT, "com.example.Cart", LINE,
			"total", null, 16, EventRequest.SUSPEND_EVENT_THREAD, false);
		final ReferenceType unrelated = mock(ReferenceType.class);
		when(unrelated.name()).thenReturn("com.example.Order");
		when(unrelated.classLoader()).thenReturn(mock(ClassLoaderReference.class));
		when(unrelated.locationsOfLine(LINE)).thenReturn(List.of(location));
		final ClassPrepareEvent event = mock(ClassPrepareEvent.class);
		when(event.request()).thenReturn(prepare);
		when(event.referenceType()).thenReturn(unrelated);

		service.handleClassPrepare(event);
		assertThat(service.list()).filteredOn(info -> info.id() == id)
			.singleElement().extracting(FastLogpointService.Info::state)
			.isEqualTo(FastLogpointService.State.WAITING_FOR_CLASS);

		final ReferenceType expected = location.declaringType();
		when(expected.classLoader()).thenReturn(mock(ClassLoaderReference.class));
		when(event.referenceType()).thenReturn(expected);
		service.handleClassPrepare(event);
		assertThat(service.list()).filteredOn(info -> info.id() == id)
			.singleElement().extracting(FastLogpointService.Info::state)
			.isEqualTo(FastLogpointService.State.PENDING);
	}

	/** A rejected write does not advance its server generation and can be retried. */
	@ParameterizedTest
	@CsvSource({"true, true", "true, false", "false, true"})
	@DisplayName("a rejected gate write is reported and leaves that gate at its previous generation")
	void rejectedGateWritesAreReportedAndCanBeRetried(boolean rejectStop, boolean rejectError) throws Exception {
		final ClassType probe = mock(ClassType.class);
		final Object entry = installProbeForRearm(probe);
		final AtomicInteger targetStop = new AtomicInteger(1);
		final AtomicInteger targetError = new AtomicInteger(1);
		modelGate(probe, FastBreakpointProbeSource.EPOCH_FIELD, targetStop, rejectStop);
		modelGate(probe, FastBreakpointProbeSource.ERROR_EPOCH_FIELD, targetError, rejectError);
		when(vm.mirrorOf(anyInt())).thenAnswer(invocation -> integerValue(invocation.getArgument(0)));

		final FastLogpointService.RearmResult result =
			service.rearmBreakpoint(location, "total > 100", null, true, EventRequest.SUSPEND_ALL);

		assertThat(result.rearmed()).isFalse();
		assertThat(result.message()).doesNotContain("re-armed (ID:");
		assertThat(result.message()).contains(rejectStop ? "stop gate did not reopen" : "stop gate reopened",
			rejectError ? "error gate did not reopen" : "error gate reopened");
		assertThat(readEpoch(entry, "epoch")).isEqualTo(targetStop.get());
		assertThat(readEpoch(entry, "errorEpoch")).isEqualTo(targetError.get());
		assertThat(targetStop.get()).isEqualTo(rejectStop ? 1 : 2);
		assertThat(targetError.get()).isEqualTo(rejectError ? 1 : 2);

		assertThat(service.rearmBreakpoint(location, "total > 100", null, true, EventRequest.SUSPEND_ALL).rearmed())
			.isTrue();
		assertThat(targetStop.get()).isEqualTo(rejectStop ? 2 : 3);
		assertThat(targetError.get()).isEqualTo(rejectError ? 2 : 3);
	}

	/** A retry starts from the target generation even if the previous reply could not confirm it. */
	@Test
	@DisplayName("a retry reconciles a gate whose first write landed without a readable reply")
	void retryReadsTargetAfterAnUnconfirmedWrite() throws Exception {
		final ClassType probe = mock(ClassType.class);
		final Object entry = installProbeForRearm(probe);
		final AtomicInteger targetStop = new AtomicInteger(1);
		final AtomicBoolean loseReply = new AtomicBoolean(true);
		final AtomicBoolean loseRead = new AtomicBoolean();
		final Field stopField = mock(Field.class);
		when(probe.fieldByName(FastBreakpointProbeSource.EPOCH_FIELD)).thenReturn(stopField);
		when(probe.getValue(stopField)).thenAnswer(invocation -> {
			if (loseRead.getAndSet(false)) {
				throw new InternalException("could not read the gate");
			}
			return integerValue(targetStop.get());
		});
		doAnswer(invocation -> {
			targetStop.set(invocation.<IntegerValue>getArgument(1).value());
			if (loseReply.getAndSet(false)) {
				loseRead.set(true);
				throw new InternalException("could not confirm the write");
			}
			return null;
		}).when(probe).setValue(eq(stopField), any());
		modelGate(probe, FastBreakpointProbeSource.ERROR_EPOCH_FIELD, new AtomicInteger(1), false);
		when(vm.mirrorOf(anyInt())).thenAnswer(invocation -> integerValue(invocation.getArgument(0)));

		final FastLogpointService.RearmResult result =
			service.rearmBreakpoint(location, "total > 100", null, true, EventRequest.SUSPEND_ALL);
		assertThat(result.rearmed()).isFalse();
		assertThat(result.message()).contains("stop gate reopening could not be verified", "error gate reopened");
		assertThat(targetStop.get()).isEqualTo(2);

		assertThat(service.rearmBreakpoint(location, "total > 100", null, true, EventRequest.SUSPEND_ALL).rearmed())
			.isTrue();
		assertThat(targetStop.get()).isEqualTo(3);
		assertThat(readEpoch(entry, "epoch")).isEqualTo(3);
	}

	/** Checks visible and suppressed hits after an installed gate accepted an unconfirmed update. */
	@ParameterizedTest
	@CsvSource({
		"false, 2, false, CURRENT",
		"true, 2, false, CURRENT",
		"false, 2, true, CURRENT",
		"true, 1, false, STALE",
		"false, 1, true, UNCERTAIN",
		"true, 2, false, SUPPRESSED"
	})
	@DisplayName("stops are classified against the relevant gate after an unconfirmed generation write")
	void stopsAfterUnconfirmedGeneration(boolean errorGate, int hitEpoch, boolean loseStopRead, String expected)
		throws Exception {
		final ClassType probe = mock(ClassType.class);
		final Object entry = installProbeForRearm(probe);
		final BreakpointRequest stopRequest = mock(BreakpointRequest.class);
		indexStopRequest(entry, stopRequest);

		final String updatedGate = errorGate ? FastBreakpointProbeSource.ERROR_EPOCH_FIELD : FastBreakpointProbeSource.EPOCH_FIELD;
		final String otherGate = errorGate ? FastBreakpointProbeSource.EPOCH_FIELD : FastBreakpointProbeSource.ERROR_EPOCH_FIELD;
		modelGate(probe, otherGate, new AtomicInteger(1), false);
		final Field gateField = mock(Field.class);
		final AtomicInteger targetEpoch = new AtomicInteger(1);
		final AtomicBoolean loseReply = new AtomicBoolean(true);
		final AtomicBoolean loseRead = new AtomicBoolean();
		when(probe.fieldByName(updatedGate)).thenReturn(gateField);
		when(probe.getValue(gateField)).thenAnswer(invocation -> {
			if (loseRead.getAndSet(false)) {
				throw new InternalException("generation read-back failed");
			}
			return integerValue(targetEpoch.get());
		});
		doAnswer(invocation -> {
			targetEpoch.set(invocation.<IntegerValue>getArgument(1).value());
			if (loseReply.getAndSet(false)) {
				loseRead.set(true);
				throw new InternalException("could not confirm the write");
			}
			return null;
		}).when(probe).setValue(eq(gateField), any());
		when(vm.mirrorOf(anyInt())).thenAnswer(invocation -> integerValue(invocation.getArgument(0)));

		final FastLogpointService.RearmResult result =
			service.rearmBreakpoint(location, "total > 100", null, true, EventRequest.SUSPEND_ALL);
		assertThat(result.rearmed()).isFalse();
		assertThat(result.message()).contains("gate reopening could not be verified");
		assertThat(targetEpoch.get()).isEqualTo(2);
		loseRead.set(loseStopRead);

		final BreakpointEvent event = mock(BreakpointEvent.class);
		when(event.request()).thenReturn(stopRequest);
		when(event.thread()).thenReturn(thread);
		when(event.location()).thenReturn(location);
		when(thread.frames()).thenReturn(List.of(frame));
		final int kind = errorGate ? FastBreakpointProbeSource.STOP_CONDITION_ERROR : FastBreakpointProbeSource.STOP_CONDITION_TRUE;
		final List<Value> arguments = Arrays.asList(integerValue(kind), integerValue(hitEpoch), null);
		when(frame.getArgumentValues()).thenReturn(arguments);

		if ("SUPPRESSED".equals(expected)) {
			service.skipStopHit(event);
			assertThat(targetEpoch.get()).isEqualTo(3);
			return;
		}
		assertThat(service.handleStopHit(event)).isEqualTo(!"STALE".equals(expected));
		if ("STALE".equals(expected)) {
			assertThat(history.getRecent(10)).extracting(EventHistory.DebugEvent::type).contains("FAST_BREAKPOINT_STALE");
		} else {
			final String reason = service.stopReason(1);
			assertThat(reason).startsWith(errorGate ? "condition failed" : "condition true");
			if ("UNCERTAIN".equals(expected)) {
				assertThat(reason).contains("gate generation uncertain");
			} else {
				assertThat(reason).doesNotContain("uncertain");
				assertThat(readEpoch(entry, errorGate ? "errorEpoch" : "epoch")).isEqualTo(hitEpoch);
			}
		}
	}

	/** Locates the registered breakpoint and models the state after its probe was installed. */
	private Object installProbeForRearm(ClassType probe) throws ReflectiveOperationException {
		final java.lang.reflect.Field entries = FastLogpointService.class.getDeclaredField("logpoints");
		entries.setAccessible(true);
		final Object entry = ((Map<?, ?>) entries.get(service)).values().iterator().next();
		final java.lang.reflect.Field state = entry.getClass().getDeclaredField("state");
		state.setAccessible(true);
		state.set(entry, FastLogpointService.State.ARMED);
		final java.lang.reflect.Field probeType = entry.getClass().getDeclaredField("probeType");
		probeType.setAccessible(true);
		probeType.set(entry, probe);
		return entry;
	}

	/** Associates the installed probe's mock stop request with its registered entry. */
	@SuppressWarnings("unchecked")
	private void indexStopRequest(Object entry, BreakpointRequest request) throws ReflectiveOperationException {
		final java.lang.reflect.Field stopRequest = entry.getClass().getDeclaredField("stopRequest");
		stopRequest.setAccessible(true);
		stopRequest.set(entry, request);
		final java.lang.reflect.Field index = FastLogpointService.class.getDeclaredField("byStopRequest");
		index.setAccessible(true);
		((Map<BreakpointRequest, Object>) index.get(service)).put(request, entry);
	}

	private static void modelGate(ClassType probe, String name, AtomicInteger targetEpoch, boolean rejectFirst)
		throws Exception {
		final Field field = mock(Field.class);
		when(probe.fieldByName(name)).thenReturn(field);
		when(probe.getValue(field)).thenAnswer(invocation -> integerValue(targetEpoch.get()));
		final AtomicInteger writes = new AtomicInteger();
		doAnswer(invocation -> {
			if (writes.getAndIncrement() == 0 && rejectFirst) {
				throw new InternalException("gate write refused");
			}
			targetEpoch.set(invocation.<IntegerValue>getArgument(1).value());
			return null;
		}).when(probe).setValue(eq(field), any());
	}

	private static IntegerValue integerValue(int value) {
		final IntegerValue mirror = mock(IntegerValue.class);
		when(mirror.value()).thenReturn(value);
		return mirror;
	}

	/** Reads the service's last confirmed generation for the requested gate. */
	private static int readEpoch(Object entry, String name) throws ReflectiveOperationException {
		final java.lang.reflect.Field epoch = entry.getClass().getDeclaredField(name);
		epoch.setAccessible(true);
		return epoch.getInt(entry);
	}

	/** Checks that losing suspension during condition evaluation fails arming. */
	@Test
	@DisplayName("a condition evaluation cut short by a resumed thread fails the arm with that reason, never a false stop")
	void resumedArmingThreadFailsTheArm() throws Exception {
		when(evaluator.readArmingResult(eq(frame), eq("total > 100"), any())).thenThrow(new JdiEvaluationException("thread not suspended"));
		when(thread.isSuspended()).thenReturn(false);

		assertThat(service.handleArmingHit(armingHit(), false)).isEqualTo(FastLogpointService.ArmingOutcome.RESUME);

		final FastLogpointService.Info info = service.list().get(0);
		assertThat(info.state()).isEqualTo(FastLogpointService.State.FAILED);
		assertThat(info.failureReason()).contains("resumed while its arming hit was evaluated");
		assertThat(history.getRecent(EventHistory.MAX_EVENTS)).extracting(EventHistory.DebugEvent::type)
			.doesNotContain("FAST_BREAKPOINT", "FAST_BREAKPOINT_ERROR");
	}

	/** Checks that a condition failure on a still-suspended arming thread is proposed as a stop. */
	@Test
	@DisplayName("a condition that throws on a still-suspended thread is the condition's failure: an error stop")
	void failingConditionOnSuspendedThreadStops() throws Exception {
		when(evaluator.readArmingResult(eq(frame), eq("total > 100"), any())).thenThrow(new JdiEvaluationException("boom"));
		when(thread.isSuspended()).thenReturn(true);

		assertThat(service.handleArmingHit(armingHit(), false)).as("the error stop is published")
			.isEqualTo(FastLogpointService.ArmingOutcome.STOPPED);
		assertThat(history.getRecent(EventHistory.MAX_EVENTS)).anyMatch(e -> "FAST_BREAKPOINT".equals(e.type())
			&& e.details().get("reason").startsWith("condition failed: "));
		assertThat(service.stopReason(service.list().get(0).id())).startsWith("condition failed: ");
	}

	private BreakpointEvent armingHit() {
		final BreakpointEvent event = mock(BreakpointEvent.class);
		when(event.request()).thenReturn(armingRequest);
		when(event.thread()).thenReturn(thread);
		when(event.location()).thenReturn(location);
		return event;
	}
}
