package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fast logpoints and fast breakpoints as the watcher and dependency tools look them up by ID, and the
 * order in which clearing a pending one gives up its arming request.
 */
@DisplayName("Fast logpoints and fast breakpoints looked up by ID")
class FastLogpointServiceLookupTest {

	private static final int BREAKPOINT_LINE = 42;
	private static final int LOGPOINT_LINE = 50;

	private JdiExpressionEvaluator evaluator;
	private FastLogpointService service;
	private EventRequestManager requests;
	private final Map<Integer, Location> locations = new HashMap<>();
	/**
	 * Every breakpoint request the service created, by line.
	 */
	private final Map<Integer, List<BreakpointRequest>> created = new HashMap<>();
	private VirtualMachine vm;
	private int breakpointId;
	private int logpointId;

	@BeforeEach
	void setUp() throws Exception {
		evaluator = mock(JdiExpressionEvaluator.class);
		service = new FastLogpointService(new BreakpointTracker(), new EventHistory(), new EvaluationGuard(), evaluator,
			mock(JdiEventListener.class), mock(JDIConnectionService.class));
		vm = mock(VirtualMachine.class);
		requests = mock(EventRequestManager.class);
		when(vm.eventRequestManager()).thenReturn(requests);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn("com.example.Cart");
		final Method method = mock(Method.class);
		when(method.name()).thenReturn("total");
		when(method.signature()).thenReturn("()I");
		for (int line : new int[]{BREAKPOINT_LINE, LOGPOINT_LINE}) {
			final Location location = mock(Location.class);
			when(location.method()).thenReturn(method);
			when(location.declaringType()).thenReturn(type);
			when(location.lineNumber()).thenReturn(line);
			when(type.locationsOfLine(line)).thenReturn(List.of(location));
			when(requests.createBreakpointRequest(location)).thenAnswer(invocation -> {
				final BreakpointRequest request = mock(BreakpointRequest.class);
				created.computeIfAbsent(line, k -> new ArrayList<>()).add(request);
				return request;
			});
			locations.put(line, location);
		}
		breakpointId = service.registerBreakpoint(vm, locations.get(BREAKPOINT_LINE), "total > 100", true,
			EventRequest.SUSPEND_ALL, FastLogpointService.BREAKPOINT_ERROR_BUFFER_SIZE);
		logpointId = service.register(vm, locations.get(LOGPOINT_LINE), "total", null, 256);
	}

	@Test
	@DisplayName("the kind of a listed ID; nothing for an unknown or a cleared one")
	void listedKind() {
		assertThat(service.listedKind(breakpointId)).isEqualTo(FastLogpointService.Kind.BREAKPOINT);
		assertThat(service.listedKind(logpointId)).isEqualTo(FastLogpointService.Kind.LOGPOINT);
		assertThat(service.listedKind(999)).isNull();

		service.clear(breakpointId);

		assertThat(service.listedKind(breakpointId)).isNull();
	}

	@Test
	@DisplayName("a pending fast breakpoint takes watchers; a fast logpoint and an unknown ID are not refused here")
	void watcherRefusal() {
		assertThat(service.watcherRefusal(breakpointId)).isNull();
		assertThat(service.watcherRefusal(logpointId)).startsWith("[ERROR] #" + logpointId + " is a fast logpoint");
		assertThat(service.watcherRefusal(999)).as("left to the tracker").isNull();
	}

	@Test
	@DisplayName("a fast breakpoint whose arming failed never stops, so it takes no watcher")
	void failedBreakpointTakesNoWatcher() throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		final StackFrame frame = mock(StackFrame.class);
		when(frame.location()).thenReturn(locations.get(BREAKPOINT_LINE));
		when(thread.frame(0)).thenReturn(frame);
		when(evaluator.evaluate(frame, "total > 100")).thenThrow(new JdiEvaluationException("thread not suspended"));
		when(thread.isSuspended()).thenReturn(false);
		service.handleArmingHit(armingHit(BREAKPOINT_LINE, thread), false);

		assertThat(service.watcherRefusal(breakpointId))
			.isEqualTo("[ERROR] Fast breakpoint " + breakpointId + " is FAILED and never stops — clear it with "
				+ "jdwp_clear_breakpoint(" + breakpointId + ") and set it again.");
	}

	@Test
	@DisplayName("the full-stack scan sees this session's fast breakpoints by line, not fast logpoints")
	void breakpointIdsByLocation() {
		assertThat(service.breakpointIdsByLocation())
			.containsOnly(Map.entry("com.example.Cart:" + BREAKPOINT_LINE, List.of(breakpointId)));
	}

	/**
	 * The race the tracker's {@code removeBreakpoint} closes: an arming request forgotten while still
	 * enabled would leave a hit already queued to the listener as an untracked breakpoint, keeping its
	 * thread stopped.
	 */
	@Test
	@DisplayName("clearing a pending entry deletes its arming request before forgetting it")
	void armingRequestIsDeletedBeforeItIsForgotten() {
		final BreakpointRequest armingRequest = created.get(BREAKPOINT_LINE).get(0);
		final BreakpointEvent queuedHit = armingHit(BREAKPOINT_LINE, mock(ThreadReference.class));
		final List<Boolean> claimedWhileDeleting = new ArrayList<>();
		doAnswer(invocation -> {
			claimedWhileDeleting.add(service.claimsArmingEvent(queuedHit));
			return null;
		}).when(requests).deleteEventRequest(armingRequest);

		service.clear(breakpointId);

		assertThat(claimedWhileDeleting).containsExactly(true);
		assertThat(service.claimsArmingEvent(queuedHit)).isFalse();
	}

	private BreakpointEvent armingHit(int line, ThreadReference thread) {
		final BreakpointEvent event = mock(BreakpointEvent.class);
		when(event.request()).thenReturn(created.get(line).get(0));
		when(event.thread()).thenReturn(thread);
		when(event.location()).thenReturn(locations.get(line));
		return event;
	}
}
