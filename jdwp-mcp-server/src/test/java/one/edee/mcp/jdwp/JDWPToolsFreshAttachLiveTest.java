package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledOnOs(OS.LINUX)
class JDWPToolsFreshAttachLiveTest {

	@Test
	void identifiesFreshAndStaleJvmsAcrossDetachAndReconnect() throws Exception {
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final JdiEventListener listener = new JdiEventListener(
			tracker, history, evaluator, guard, null, new MarkedInstanceRegistry());
		final JDIConnectionService connection = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			listener, tracker, history, watchers, guard);
		final JDWPTools tools = JDWPToolsTestSupport.newTools(
			connection, tracker, watchers, evaluator, history, guard, new JvmDiscoveryService());

		final int port;
		try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			port = probe.getLocalPort();
		}
		final ProcessBuilder launcher = new ProcessBuilder(
			Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-cp", Path.of(Target.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
			Target.class.getName())
			.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
		// Keep the port out of argv so recognition must come from the connected TCP socket.
		launcher.environment().put("JDK_JAVA_OPTIONS",
			"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port);
		final Process target = launcher.start();
		try {
			final String pid = "PID " + target.pid();
			assertThat(tools.jdwp_wait_for_attach("localhost", port, 5_000, true))
				.contains("suspended at VM_START", pid + ", started ", Target.class.getName());
			assertThat(tools.jdwp_wait_for_attach("127.0.0.1", port, 5_000, true))
				.startsWith("[NOT_FRESH]").contains(pid, "jdwp_disconnect");
			assertThat(tools.jdwp_resume()).contains("VM-start suspension");
			assertThat(tools.jdwp_reconnect()).contains("Reconnected", pid);
			assertThat(tools.jdwp_disconnect()).contains(pid, "still alive", "cannot bind");

			assertThat(tools.jdwp_wait_for_attach("localhost", port, 5_000, true))
				.startsWith("[NOT_FRESH]").contains(pid, "jdwp_disconnect");
			assertThat(connection.getConnectionStatus().connected()).isFalse();
			assertThat(tools.jdwp_wait_for_attach("localhost", port, 5_000, null))
				.contains("already running", "earlier debugger session", "requireVmStart=true", pid);
			assertThat(connection.getVM().allThreads()).noneMatch(thread -> thread.isSuspended());
		} finally {
			try {
				tools.jdwp_disconnect();
			} finally {
				target.destroyForcibly();
				assertThat(target.waitFor(5, TimeUnit.SECONDS)).isTrue();
			}
		}
	}

	public static class Target {
		public static void main(String[] args) throws Exception {
			System.in.read();
		}
	}
}
