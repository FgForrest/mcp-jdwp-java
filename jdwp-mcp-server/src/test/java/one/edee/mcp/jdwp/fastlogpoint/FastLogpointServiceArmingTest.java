package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.*;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.BreakpointTracker;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.EventHistory;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.JdiEventListener;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
	private EventRequestManager requests;
	private Location location;
	private ThreadReference thread;
	private StackFrame frame;
	private BreakpointRequest armingRequest;
	/**
	 * Every breakpoint request the service created, in order.
	 */
	private final List<BreakpointRequest> created = new ArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		history = new EventHistory();
		evaluator = mock(JdiExpressionEvaluator.class);
		service = new FastLogpointService(new BreakpointTracker(), history, new EvaluationGuard(), evaluator,
			mock(JdiEventListener.class), mock(JDIConnectionService.class));

		final VirtualMachine vm = mock(VirtualMachine.class);
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

		service.registerBreakpoint(vm, location, "total > 100", true, EventRequest.SUSPEND_ALL,
			FastLogpointService.BREAKPOINT_ERROR_BUFFER_SIZE);
		armingRequest = created.get(0);
	}

	@Test
	@DisplayName("a condition evaluation cut short by a resumed thread fails the arm with that reason, never a false stop")
	void resumedArmingThreadFailsTheArm() throws Exception {
		when(evaluator.evaluate(frame, "total > 100")).thenThrow(new JdiEvaluationException("thread not suspended"));
		when(thread.isSuspended()).thenReturn(false);

		assertThat(service.handleArmingHit(armingHit())).isEqualTo(FastLogpointService.ArmingOutcome.RESUME);

		final FastLogpointService.Info info = service.list().get(0);
		assertThat(info.state()).isEqualTo(FastLogpointService.State.FAILED);
		assertThat(info.failureReason()).contains("resumed while its arming hit was evaluated");
		assertThat(history.getRecent(EventHistory.MAX_EVENTS)).extracting(EventHistory.DebugEvent::type)
			.doesNotContain("FAST_BREAKPOINT", "FAST_BREAKPOINT_ERROR");
	}

	@Test
	@DisplayName("a condition that throws on a still-suspended thread is the condition's failure: an error stop")
	void failingConditionOnSuspendedThreadStops() throws Exception {
		when(evaluator.evaluate(frame, "total > 100")).thenThrow(new JdiEvaluationException("boom"));
		when(thread.isSuspended()).thenReturn(true);

		assertThat(service.handleArmingHit(armingHit())).as("the error stop is published")
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
