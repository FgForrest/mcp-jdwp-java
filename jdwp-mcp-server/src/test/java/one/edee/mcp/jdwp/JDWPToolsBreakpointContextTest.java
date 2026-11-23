package one.edee.mcp.jdwp;

import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises thread selection through the published MCP tool schema and handler. */
class JDWPToolsBreakpointContextTest {

	/** The target thread and suspend-count model. */
	private FakeSuspendModel model;
	/** The connection used by the tools to fetch the VM and format JDI values. */
	private JDIConnectionService jdi;
	/** The tracker that supplies the connection's suspension session. */
	private BreakpointTracker tracker;
	/** The current session where the fixture publishes stops. */
	private SuspensionSession session;
	/** The same tool instance used to compare stack and context errors. */
	private JDWPTools tools;
	/** The annotated context tool as registered with the MCP server. */
	private SyncToolSpecification contextTool;

	/** Connects a fake VM and builds the annotated tool specification. */
	@BeforeEach
	void setUp() throws Exception {
		model = new FakeSuspendModel();
		jdi = mock(JDIConnectionService.class);
		when(jdi.getVM()).thenReturn(model.vm);
		tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		session = tracker.openSuspensionSession(model.vm, history);
		tools = JDWPToolsTestSupport.newTools(jdi, tracker, mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class), history, new EvaluationGuard(), new JvmDiscoveryService());
		contextTool = new SyncMcpToolProvider(List.of(tools)).getToolSpecifications().stream()
			.filter(spec -> spec.tool().name().equals("jdwp_get_breakpoint_context")).findFirst().orElseThrow();
	}

	/** Presents only the requested thread and keeps its event tag and capture together. */
	@Test
	void selectsEachThreadsPublishedContext() {
		final FakeSuspendModel.FakeThread first = model.thread("first");
		final FakeSuspendModel.FakeThread second = model.thread("second");
		publish(first, 11, "first capture");
		publish(second, 22, "second capture");

		assertThat(context(second.ref.uniqueID()))
			.contains("Thread: second", "breakpoint=22", "second capture")
			.doesNotContain("first capture");
		assertThat(session.unseenCount()).isEqualTo(1);
		assertThat(context(first.ref.uniqueID()))
			.contains("Thread: first", "breakpoint=11", "first capture")
			.doesNotContain("second capture");
		assertThat(context(second.ref.uniqueID())).contains("Thread: second", "second capture");
		assertThat(session.currentStop()).isNotNull().satisfies(stop -> assertThat(stop.thread()).isSameAs(first.ref));
	}

	/** Renders independent frame data for a manual hold while another thread has an unseen stop. */
	@Test
	void inspectsManualHoldWithoutPresentingOtherStop() throws Exception {
		final FakeSuspendModel.FakeThread stopped = model.thread("stopped");
		final FakeSuspendModel.FakeThread manual = model.thread("manual");
		publish(stopped, 11, "unrelated capture");
		assertThat(session.suspendThread(manual.ref)).isNull();

		final StackFrame frame = mock(StackFrame.class);
		final Location location = mock(Location.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method method = mock(Method.class);
		final LocalVariable local = mock(LocalVariable.class);
		final Value value = mock(Value.class);
		final ObjectReference thisObject = mock(ObjectReference.class);
		when(manual.ref.frameCount()).thenReturn(1);
		when(manual.ref.frames(0, 1)).thenReturn(List.of(frame));
		when(manual.ref.frames()).thenReturn(List.of(frame));
		when(frame.location()).thenReturn(location);
		when(location.declaringType()).thenReturn(type);
		when(location.method()).thenReturn(method);
		when(location.lineNumber()).thenReturn(12);
		when(type.name()).thenReturn("example.Work");
		when(method.name()).thenReturn("inspect");
		when(frame.visibleVariables()).thenReturn(List.of(local));
		when(frame.getValues(List.of(local))).thenReturn(Map.of(local, value));
		when(local.name()).thenReturn("seats");
		when(local.typeName()).thenReturn("int");
		when(jdi.formatFieldValue(value)).thenReturn("3");
		when(frame.thisObject()).thenReturn(thisObject);
		when(thisObject.referenceType()).thenReturn(type);
		when(jdi.formatFieldValue(thisObject)).thenReturn("Object#42");

		assertThat(context(manual.ref.uniqueID()))
			.contains("Thread: manual", "No stop of its own is recorded", "example.Work.inspect",
				"seats (int) = 3", "this (example.Work) = Object#42", "--- this fields")
			.doesNotContain("unrelated capture", "breakpoint=11");
		assertThat(session.unseenCount()).isEqualTo(1);
	}

	/** Uses the same validation replies as stack inspection for running and unknown threads. */
	@Test
	void reportsRunningAndUnknownThreads() {
		final FakeSuspendModel.FakeThread stopped = model.thread("stopped");
		final FakeSuspendModel.FakeThread running = model.thread("running");
		publish(stopped, 11, "unrelated capture");

		final String runningReply = context(running.ref.uniqueID());
		final String unknownReply = context(999L);
		assertThat(runningReply).contains("not suspended").isEqualTo(tools.jdwp_get_stack(running.ref.uniqueID(), null, null));
		assertThat(unknownReply).contains("Thread not found with ID 999")
			.isEqualTo(tools.jdwp_get_stack(999L, null, null));
		assertThat(session.unseenCount()).isEqualTo(1);
	}

	/** Rejects a VM fetched just before the tracker switched to a different connection. */
	@Test
	void reportsConnectionChangeBeforeSelectingAReusedThreadId() throws Exception {
		final VirtualMachine earlierVm = model.vm;
		model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread newThread = model.thread("replacement");
		session = tracker.openSuspensionSession(model.vm, new EventHistory());
		when(jdi.getVM()).thenReturn(earlierVm);
		publish(newThread, 22, "replacement capture");

		assertThat(context(newThread.ref.uniqueID())).startsWith("[CONNECTION_CHANGED]");
		assertThat(session.unseenCount()).isEqualTo(1);
	}

	/** Publishes a thread-policy breakpoint with a distinct capture. */
	private void publish(FakeSuspendModel.FakeThread thread, int id, String capture) {
		final SuspensionSession.Context stop = session.route(
			model.breakpointEvent(thread, EventRequest.SUSPEND_EVENT_THREAD), thread.ref, false);
		session.begin(stop);
		session.candidate(stop, new BreakpointTracker.LastBreakpoint(
			thread.ref, id, BreakpointTracker.EventKind.BREAKPOINT, "example.Work:12", Map.of(id, capture)));
		assertThat(session.finish(stop)).isTrue();
	}

	/** Sends a targeted context request through the same annotation handler the server registers. */
	private String context(long threadId) {
		final McpSchema.CallToolResult result = contextTool.callHandler().apply(mock(McpSyncServerExchange.class),
			new McpSchema.CallToolRequest(contextTool.tool().name(), Map.of("threadId", threadId)));
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}
}
