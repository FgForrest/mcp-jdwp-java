package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
class JDWPToolsFreshAttachLiveTest {

	@TempDir
	Path targetFiles;

	private JDIConnectionService connection;
	private JDWPTools tools;
	private int port;
	private Process target;

	@BeforeEach
	void setUp() throws Exception {
		final BreakpointTracker tracker = new BreakpointTracker();
		final EventHistory history = new EventHistory();
		final WatcherManager watchers = new WatcherManager();
		final EvaluationGuard guard = new EvaluationGuard();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final JdiEventListener listener = new JdiEventListener(
			tracker, history, evaluator, guard, null, new MarkedInstanceRegistry());
		connection = JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			listener, tracker, history, watchers, guard);
		tools = JDWPToolsTestSupport.newTools(
			connection, tracker, watchers, evaluator, history, guard, new JvmDiscoveryService());

		try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
			port = probe.getLocalPort();
		}
	}

	private void launchTarget(String agentOptions, String... args) throws Exception {
		final List<String> command = new ArrayList<>(List.of(
			Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-cp", Path.of(Target.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
			Target.class.getName()));
		command.addAll(List.of(args));
		final ProcessBuilder launcher = new ProcessBuilder(command).redirectErrorStream(true)
			.redirectOutput(targetFiles.resolve("target.log").toFile());
		// Keep the port out of argv so recognition must come from the connected TCP socket.
		launcher.environment().put("JDK_JAVA_OPTIONS",
			"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port + agentOptions);
		target = launcher.start();
	}

	@AfterEach
	void stopTarget() throws Exception {
		try {
			tools.jdwp_disconnect();
		} finally {
			if (target != null) {
				target.destroyForcibly();
				assertThat(target.waitFor(5, TimeUnit.SECONDS)).isTrue();
			}
		}
	}

	@Test
	void identifiesFreshAndStaleJvmsAcrossDetachAndReconnect() throws Exception {
		launchTarget("");
		final String pid = "PID " + target.pid();
		assertThat(tools.jdwp_wait_for_attach("localhost", port, 5_000, true))
			.contains("suspended at VM_START", pid + ", started ", Target.class.getName());
		assertThat(tools.jdwp_wait_for_attach("127.0.0.1", port, 5_000, true))
			.startsWith("[NOT_FRESH]").contains(pid, "jdwp_disconnect");
		assertThat(tools.jdwp_resume()).contains("VM-start suspension");
		assertThat(tools.jdwp_reconnect()).contains("Reconnected", pid);
		assertThat(tools.jdwp_disconnect()).contains(pid, "still alive", "cannot bind", "kill " + target.pid());

		assertThat(tools.jdwp_wait_for_attach("localhost", port, 5_000, true))
			.startsWith("[NOT_FRESH]").contains(pid, "jdwp_disconnect");
		assertThat(connection.getConnectionStatus().connected()).isFalse();
		assertThat(tools.jdwp_wait_for_attach("localhost", port, 5_000, null))
			.contains("already running", "earlier debugger session", "requireVmStart=true", pid);
		assertThat(connection.getVM().allThreads()).noneMatch(thread -> thread.isSuspended());
	}

	@Test
	void rejectsAttachWhenJdwpStartsAtAnApplicationException() throws Exception {
		final Path marker = targetFiles.resolve("main-entered");
		launchTarget(",onthrow=java.lang.IllegalStateException,launch=/bin/true", marker.toString());

		final String reply = tools.jdwp_wait_for_attach("127.0.0.1", port, 5_000, true);

		assertThat(marker).exists();
		assertThat(reply).as("target output: " + Files.readString(targetFiles.resolve("target.log")))
			.startsWith("[NOT_FRESH]");
		assertThat(connection.getConnectionStatus().connected()).isFalse();
	}

	public static class Target {
		public static void main(String[] args) throws Exception {
			if (args.length != 0) {
				Files.createFile(Path.of(args[0]));
				try {
					throw new IllegalStateException("starts JDWP after application code has run");
				} catch (IllegalStateException expected) {
					System.in.read();
				}
			} else {
				System.in.read();
			}
		}
	}
}
