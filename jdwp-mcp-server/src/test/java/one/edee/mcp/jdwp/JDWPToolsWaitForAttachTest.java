package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.discovery.JdwpEndpoint;
import one.edee.mcp.jdwp.discovery.JvmDescriptor;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link JDWPTools#jdwp_wait_for_attach} surfaces the local-JVM list on timeout
 * (so the user can spot a target on a different port). Successful replies omit the inventory.
 */
@DisplayName("jdwp_wait_for_attach")
class JDWPToolsWaitForAttachTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private JvmDiscoveryService discovery;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		final WatcherManager watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
		discovery = mock(JvmDiscoveryService.class);
		when(discovery.discover()).thenReturn(List.of(
			new JvmDescriptor(54321L, "com.example.Other", null, null,
				new JdwpEndpoint("*", 5006, "dt_socket", true, true, JdwpEndpoint.State.SUSPENDED),
				false, JvmDescriptor.Source.PROC_FS)
		));
		when(discovery.confirmAll(Mockito.anyList(), Mockito.any(), Mockito.anyInt()))
			.thenAnswer(inv -> inv.getArgument(0));
		when(jdiService.getConnectionStatus()).thenReturn(
			new JDIConnectionService.ConnectionStatus(false, "localhost", 5005, null, "refused"));
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), discovery);
	}

	/** Publishes the delayed VM start on the connection's session. */
	@Test
	@DisplayName("Strict attach waits for a suspending VM_START to be published after connect")
	void shouldWaitForDelayedVmStart() throws Exception {
		final SuspensionSession session = tracker.openSuspensionSession(mock(VirtualMachine.class), eventHistory);
		final ThreadReference thread = JdiEventListenerTestSupport.mockThread("main", 1L);
		final EventSet eventSet = JdiEventListenerTestSupport.mockEventSet();
		when(eventSet.suspendPolicy()).thenReturn(EventRequest.SUSPEND_ALL);

		when(jdiService.connect("localhost", 5005, true)).thenAnswer(invocation -> {
			CompletableFuture.runAsync(() -> {
				final SuspensionSession.Context start = session.route(eventSet, thread, true);
				session.begin(start);
				session.candidate(start, new BreakpointTracker.LastBreakpoint(thread, -1));
				session.finish(start);
			}, CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS));
			return "Connected to test VM";
		});

		assertThat(tools.jdwp_wait_for_attach("localhost", 5005, 2_000, true))
			.contains("currently suspended at VM_START");
		verify(jdiService, never()).attachedVM();
		verify(jdiService, never()).disconnect();
	}

	@Test
	@DisplayName("timeout appends the local-JVM list")
	void shouldAppendDiscoveryOnTimeout() throws Exception {
		// Force every attempt to fail with a retriable IOException — the loop will exhaust
		// the 100ms budget and time out.
		when(jdiService.connect(Mockito.anyString(), Mockito.anyInt()))
			.thenThrow(new IOException("Connection refused"));

		final String result = tools.jdwp_wait_for_attach("localhost", 5005, 100, null);

		// Timeout returns the structured soft-wait envelope instead of [TIMEOUT].
		// The actionable Local-JVM block is still appended so the agent can see candidate targets.
		assertThat(result).contains("Status: still_waiting");
		assertThat(result).contains("Tool: jdwp_wait_for_attach");
		assertThat(result).contains("JDI Health:");
		assertThat(result).contains("Local JVMs visible to user");
		assertThat(result).contains("54321");
		assertThat(result).contains("com.example.Other");
		verify(discovery, times(1)).discover();
	}

	@Test
	@DisplayName("fast-fail (IllegalConnectorArgumentsException) does NOT trigger discovery")
	void shouldSkipDiscoveryOnFastFail() throws Exception {
		when(jdiService.connect(Mockito.anyString(), Mockito.anyInt()))
			.thenThrow(new IllegalConnectorArgumentsException("bad", "port"));

		final String result = tools.jdwp_wait_for_attach("localhost", 5005, 5000, null);

		assertThat(result).startsWith("[ERROR]");
		verify(discovery, never()).discover();
	}

	@Test
	@DisplayName("successful attach omits the local-JVM inventory")
	void shouldOmitInventoryOnSuccessfulAttach() throws Exception {
		when(jdiService.connect(Mockito.anyString(), Mockito.anyInt()))
			.thenReturn("Connected to OpenJDK 64-Bit Server VM (version 21)");

		final String result = tools.jdwp_wait_for_attach("localhost", 5005, 5000, null);

		assertThat(result).contains("Connected");
		assertThat(result).doesNotContain("Local JVMs");
		verify(discovery, never()).discover();
	}

	@Test
	@DisplayName("timeout passes the connection-status host/port to confirmAll exactly once")
	void shouldForwardConnectedHostAndPortToConfirmAllOnTimeout() throws Exception {
		when(jdiService.connect(Mockito.anyString(), Mockito.anyInt()))
			.thenThrow(new IOException("Connection refused"));
		// Override the default status with something distinctive so the captor proves the
		// host/port came from getConnectionStatus, not from a baked-in default.
		when(jdiService.getConnectionStatus()).thenReturn(
			new JDIConnectionService.ConnectionStatus(false, "10.0.0.5", 5050, null, "refused")
		);

		tools.jdwp_wait_for_attach("ignored.host", 9999, 100, null);

		final ArgumentCaptor<String> hostCaptor = ArgumentCaptor.forClass(String.class);
		final ArgumentCaptor<Integer> portCaptor = ArgumentCaptor.forClass(Integer.class);
		verify(discovery, times(1)).confirmAll(Mockito.anyList(), hostCaptor.capture(), portCaptor.capture());
		assertThat(hostCaptor.getValue()).isEqualTo("10.0.0.5");
		assertThat(portCaptor.getValue()).isEqualTo(5050);
	}

	@Test
	@DisplayName("timeout never invokes the inspectAll attach pass (only confirmAll is used)")
	void shouldNotInvokeInspectAllOnTimeout() throws Exception {
		when(jdiService.connect(Mockito.anyString(), Mockito.anyInt()))
			.thenThrow(new IOException("Connection refused"));

		tools.jdwp_wait_for_attach("localhost", 5005, 100, null);

		// confirmAll is cheap (one TCP handshake); inspectAll attaches to every JVM and is opt-in
		// in jdwp_diagnose. The timeout path must NOT trigger it.
		verify(discovery, never()).inspectAll(Mockito.anyList());
	}

	@Test
	@DisplayName("interrupted wait skips discovery — the user explicitly cancelled, don't add overhead")
	void shouldSkipDiscoveryOnInterruptedWait() throws Exception {
		when(jdiService.connect(Mockito.anyString(), Mockito.anyInt()))
			.thenThrow(new IOException("Connection refused"));
		// Pre-set the interrupt flag so the Thread.sleep(200) inside the retry loop throws
		// InterruptedException on its very first invocation.
		Thread.currentThread().interrupt();
		try {
			final String result = tools.jdwp_wait_for_attach("localhost", 5005, 5_000, null);

			// Interrupt is a user-requested cancellation: surface it promptly and skip the
			// discovery overhead. The timeout path runs discovery because the user is still
			// waiting for an answer; the interrupted path is the opposite signal.
			assertThat(result).startsWith("[INTERRUPTED]");
			verify(discovery, never()).discover();
		} finally {
			// Clear the interrupt flag we set ourselves, in case the implementation didn't
			// re-set it before returning, so we don't leak state to other tests in this class.
			Thread.interrupted();
		}
	}
}
