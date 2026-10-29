package one.edee.mcp.jdwp;

import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Surface tests for {@link JDWPTools#jdwp_reconnect} and the soft-wait envelope
 * emitted by {@link JDWPTools#jdwp_resume_until_event} when its 30s ceiling elapses without an
 * event. The envelope is the load-bearing contract — agents pivot to {@code wait_more} /
 * {@code reconnect} / {@code abort} based on what it says, so the format is asserted on directly.
 */
@DisplayName("reconnect tool + soft-wait envelope")
class JDWPToolsReconnectAndSoftWaitTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private EventHistory eventHistory;
	private JdiHealthMonitor healthMonitor;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		// The wait tools need the connection's suspension-ownership session (issue #45).
		when(breakpointTracker.suspensionSession())
			.thenReturn(new BreakpointTracker().openSuspensionSession(new EventHistory()));
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
		healthMonitor = new JdiHealthMonitor();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService(),
			new MarkedInstanceRegistry(), healthMonitor);
	}

	@Nested
	@DisplayName("jdwp_reconnect precondition")
	class ReconnectPrecondition {

		/**
		 * Reconnect with no prior successful attach must surface a clear [ERROR] envelope — the
		 * agent should be steered to {@code jdwp_connect} / {@code jdwp_wait_for_attach} rather
		 * than retrying reconnect against a null target.
		 */
		@Test
		@DisplayName("returns [ERROR] when no prior attach exists")
		void shouldReturnErrorWhenNoPriorAttach() throws Exception {
			when(jdiService.reconnectPreservingSpecs()).thenThrow(
				new IllegalStateException("No prior successful attach — call jdwp_connect or jdwp_wait_for_attach first."));

			final String result = tools.jdwp_reconnect();

			assertThat(result).startsWith("[ERROR]");
			assertThat(result).contains("jdwp_connect");
		}

		/**
		 * A generic attach failure during reconnect must surface a recoverable envelope that tells
		 * the agent the previous JDI connection is gone but spec state survives MCP-side, so
		 * retrying once the target is back is meaningful.
		 */
		@Test
		@DisplayName("returns recoverable [ERROR] when the fresh attach fails")
		void shouldReturnRecoverableErrorOnAttachFailure() throws Exception {
			when(jdiService.reconnectPreservingSpecs())
				.thenThrow(new java.net.ConnectException("Connection refused"));

			final String result = tools.jdwp_reconnect();

			assertThat(result).startsWith("[ERROR]");
			assertThat(result).contains("Connection refused");
			assertThat(result).contains("jdwp_diagnose");
		}
	}

	@Nested
	@DisplayName("jdwp_reconnect happy-path rendering")
	class ReconnectRendering {

		/**
		 * The reconnect report must echo every field in the contract — what survived (line / exc /
		 * field BPs, watchers) and what was lost (marks, object cache, last-suspended-thread,
		 * classpath). Agents key on this structure to know what they need to re-establish.
		 */
		@Test
		@DisplayName("renders the full survived/lost contract")
		void shouldRenderFullContract() throws Exception {
			when(jdiService.reconnectPreservingSpecs()).thenReturn(
				new JDIConnectionService.ReconnectResult(
					"localhost", 5005,
					4, 8, 12,   // line: 4 active + 8 deferred = 12 total
					3,          // exception
					2,          // field
					5,          // watchers preserved
					4,          // marked instances lost
					17          // object cache lost
				));

			final String result = tools.jdwp_reconnect();

			assertThat(result).contains("Reconnected to localhost:5005");
			assertThat(result).contains("Line breakpoints: 12 (4 active, 8 deferred");
			assertThat(result).contains("Exception breakpoints: 3");
			assertThat(result).contains("Field breakpoints: 2");
			assertThat(result).contains("Watchers: 5");
			assertThat(result).contains("Marked instances: 4");
			assertThat(result).contains("Object cache: 17 entries cleared");
			assertThat(result).contains("Target VM state after reconnect: RUNNING");
		}
	}

	@Nested
	@DisplayName("soft-wait envelope")
	class SoftWaitEnvelope {

		/**
		 * On a {@code jdwp_resume_until_event} timeout the response must lead with the structured
		 * envelope so an agent that parses the first lines can take the next action without reading
		 * the trailing diagnostic block.
		 */
		@Test
		@DisplayName("resume_until_event timeout produces the still_waiting envelope")
		void shouldRenderSoftWaitEnvelopeOnTimeout() throws Exception {
			final VirtualMachine vm = mock(VirtualMachine.class);
			when(jdiService.getVM()).thenReturn(vm);
			// Latch that never fires — await() returns false after the timeout.
			final CountDownLatch latch = new CountDownLatch(1);
			when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
			when(breakpointTracker.getAllBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllPendingBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllExceptionBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllPendingExceptionBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllFieldBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllPendingFieldBreakpoints()).thenReturn(Map.of());

			final String result = tools.jdwp_resume_until_event(50);

			assertThat(result).startsWith("Status: still_waiting");
			assertThat(result).contains("Tool: jdwp_resume_until_event");
			assertThat(result).contains("Elapsed: 50ms");
			assertThat(result).contains("JDI Health: disconnected");  // healthMonitor not started in this test
			assertThat(result).contains("wait_more");
			assertThat(result).contains("reconnect   — call jdwp_reconnect");
			assertThat(result).contains("abort       — call jdwp_disconnect");
		}

		/**
		 * After the monitor has observed traffic, the envelope must reflect the RESPONSIVE
		 * classification — proves the renderer reads live state, not a hardcoded label.
		 */
		@Test
		@DisplayName("envelope reflects RESPONSIVE health when traffic has been observed")
		void shouldRenderResponsiveHealthWhenMonitorActive() throws Exception {
			final VirtualMachine vm = mock(VirtualMachine.class);
			// Start the monitor against the mock vm so notifyTraffic publishes (the synchronized
			// + null-vmRef guard short-circuits when no session is bound).
			healthMonitor.start(vm);
			healthMonitor.notifyTraffic();
			when(jdiService.getVM()).thenReturn(vm);
			final CountDownLatch latch = new CountDownLatch(1);
			when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
			when(breakpointTracker.getAllBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllPendingBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllExceptionBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllPendingExceptionBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllFieldBreakpoints()).thenReturn(Map.of());
			when(breakpointTracker.getAllPendingFieldBreakpoints()).thenReturn(Map.of());

			final String result = tools.jdwp_resume_until_event(50);

			assertThat(result).contains("JDI Health: responsive");
		}
	}
}
