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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
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

	/** Launches a target with a JDWP port explicitly configured in its JVM options. */
	private void launchTarget(String agentOptions, String... args) throws Exception {
		final List<String> command = new ArrayList<>(List.of(
			Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port + agentOptions,
			"-cp", Path.of(Target.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
			Target.class.getName()));
		command.addAll(List.of(args));
		final ProcessBuilder launcher = new ProcessBuilder(command).redirectErrorStream(true)
			.redirectOutput(targetFiles.resolve("target.log").toFile());
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

	/** Checks that a verified direct peer retains its process identity across the attach lifecycle. */
	@Test
	void identifiesFreshAndStaleJvmsAcrossDetachAndReconnect() throws Exception {
		launchTarget("");
		final String pid = "PID " + target.pid();
		assertThat(tools.jdwp_wait_for_attach("localhost", port, 5_000, true))
			.contains("suspended at VM_START", "verified socket owner", pid + ", started ", Target.class.getName());
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

	/** A live Class.forName failure leaves a prepared class that still accepts a line breakpoint. */
	@Test
	void forceLoadReportsFailedInitializationAndBindsBreakpoint() throws Exception {
		launchTarget("");
		final String badInit = Target.BadInit.class.getName();
		assertThat(tools.jdwp_wait_for_attach("127.0.0.1", port, 5_000, true)).contains("suspended at VM_START");
		final int waitLine = lineOf("// MARK: wait");
		assertThat(tools.jdwp_set_breakpoint(Target.class.getName(), waitLine, null, null, null, null, null))
			.startsWith("Breakpoint deferred");
		assertThat(tools.jdwp_resume_until_event(5_000))
			.startsWith("Event fired at " + Target.class.getName() + ":" + waitLine);
		assertThat(connection.getVM().classesByName(badInit)).isEmpty();

		final String reply = tools.jdwp_set_breakpoint(
			badInit, lineOf("// MARK: failing initializer"), null, null, null, null, true);

		assertThat(reply).startsWith("Breakpoint set at " + badInit).containsOnlyOnce("Warning: forceLoad=true")
			.contains("java.lang.ExceptionInInitializerError (cause: java.lang.IllegalStateException: config missing)",
				"NoClassDefFoundError");
	}

	/** The line of this source file that carries {@code marker} after its code. */
	private static int lineOf(String marker) throws IOException {
		final List<String> lines = Files.readAllLines(
			Path.of("src/test/java/one/edee/mcp/jdwp/JDWPToolsFreshAttachLiveTest.java"));
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).endsWith("; " + marker)) {
				return i + 1;
			}
		}
		throw new IllegalStateException(marker + " not found");
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

	/** Requires the tool to attribute a real forwarded JDWP connection to its TCP peer only. */
	@Test
	void identifiesAJavaForwarderAsTheTcpPeer() throws Exception {
		launchTarget("");
		final Path relayPort = targetFiles.resolve("relay-port");
		final Process relay = new ProcessBuilder(
			Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-cp", Path.of(Relay.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
			Relay.class.getName(), Integer.toString(port), relayPort.toString())
			.redirectErrorStream(true).redirectOutput(targetFiles.resolve("relay.log").toFile()).start();
		try {
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!Files.exists(relayPort) && relay.isAlive() && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
			assertThat(relayPort).exists();
			final int forwardedPort = Integer.parseInt(Files.readString(relayPort));
			final String peer = "PID " + relay.pid() + " owns the other end of the connection";

			final String attached = tools.jdwp_wait_for_attach("127.0.0.1", forwardedPort, 5_000, true);
			final String detached = tools.jdwp_disconnect();

			assertThat(attached).contains("suspended at VM_START", peer);
			assertThat(detached).contains(peer, "probably a port forwarder", "ss -ltnp", "lsof")
				.doesNotContain("kill ", "cannot bind", "Target process");
		} finally {
			try {
				tools.jdwp_disconnect();
			} finally {
				assertThat(relay.destroyForcibly().waitFor(5, TimeUnit.SECONDS)).isTrue();
			}
		}
	}

	/** A fixture accepting one loopback connection and relaying it to the target. */
	public static class Relay {
		/** Publishes the listening port, relays JDWP, then stays alive until test cleanup. */
		public static void main(String[] args) throws Exception {
			final InetAddress loopback = InetAddress.getByName("127.0.0.1");
			final Path portFile = Path.of(args[1]);
			final Socket client;
			try (ServerSocket listener = new ServerSocket(0, 1, loopback)) {
				final Path pending = Files.writeString(portFile.resolveSibling("pending-port"),
					Integer.toString(listener.getLocalPort()));
				Files.move(pending, portFile);
				client = listener.accept();
			}
			try (client; Socket upstream = connectToTarget(loopback, Integer.parseInt(args[0]))) {
				final Thread requests = new Thread(() -> relay(client, upstream));
				requests.setDaemon(true);
				requests.start();
				relay(upstream, client);
				requests.join();
			}
			System.in.read();
		}

		/** Allows the target JVM a bounded window to open its JDWP listener. */
		private static Socket connectToTarget(InetAddress host, int port) throws Exception {
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline) {
				try {
					return new Socket(host, port);
				} catch (IOException ignored) {
					Thread.sleep(20);
				}
			}
			throw new IOException("The target never opened its JDWP port");
		}

		/** Relays one direction until either side closes. */
		private static void relay(Socket from, Socket to) {
			try {
				from.getInputStream().transferTo(to.getOutputStream());
				to.shutdownOutput();
			} catch (IOException ignored) {
				// A normal detach can close either direction first.
			}
		}
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
				System.in.read(); // MARK: wait
			}
		}

		/** A class that will stay loaded after its initializer fails. */
		public static class BadInit {
			static {
				failInitialization();
			}

			/** Provides an executable line for the force-loaded breakpoint. */
			private static void failInitialization() {
				throw new IllegalStateException("config missing"); // MARK: failing initializer
			}
		}
	}
}
