package one.edee.mcp.jdwp.fastlogpoint;

import com.sun.jdi.Method;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.*;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.InMemoryJavaCompiler;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider;
import one.edee.mcp.jdwp.fastlogpoint.fixture.FastLogpointTarget;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A forked {@link FastLogpointTarget} JVM with a JDWP agent, plus the server-side services wired
 * around one connection to it — the same graph Spring builds, minus the MCP transport. Shared by the
 * opt-in live tests of fast logpoints and fast breakpoints.
 */
final class LiveSession implements AutoCloseable {
	static final String TARGET = FastLogpointTarget.class.getName();
	private static final Path TARGET_SOURCE =
		Path.of("src/test/java/one/edee/mcp/jdwp/fastlogpoint/fixture/FastLogpointTarget.java");

	final Process process;
	final int port;
	final EventHistory history = new EventHistory();
	final BreakpointTracker tracker = new BreakpointTracker();
	final JDIConnectionService connection;
	final FastLogpointService fastLogpoints;
	final JDWPTools tools;

	private LiveSession(Process process, int port) {
		this.process = process;
		this.port = port;
		final EvaluationGuard guard = new EvaluationGuard();
		final MarkedInstanceRegistry marks = new MarkedInstanceRegistry();
		final WatcherManager watchers = new WatcherManager();
		final JdiHealthMonitor health = new JdiHealthMonitor();
		final LocalProjectClasspathProvider noLocalClasspath = new LocalProjectClasspathProvider(
			Path.of(System.getProperty("java.io.tmpdir"), "fast-logpoint-live-" + UUID.randomUUID()),
			name -> null, (command, workingDirectory, timeoutSeconds) -> List.of());
		// The listener and the evaluator depend on each other through the connection service;
		// Spring breaks the cycle with @Lazy, the test with a delegating stand-in.
		final AtomicReference<JdiExpressionEvaluator> evaluatorRef = new AtomicReference<>();
		final JdiExpressionEvaluator lazyEvaluator = mock(JdiExpressionEvaluator.class, invocation -> {
			try {
				return invocation.getMethod().invoke(evaluatorRef.get(), invocation.getArguments());
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
		final JdiEventListener listener = new JdiEventListener(tracker, history, lazyEvaluator, guard, null, marks);
		connection = new JDIConnectionService(listener, tracker, history, watchers, guard, marks, health);
		final JdiExpressionEvaluator evaluator =
			new JdiExpressionEvaluator(new InMemoryJavaCompiler(), connection, guard, noLocalClasspath);
		evaluatorRef.set(evaluator);
		fastLogpoints = new FastLogpointService(tracker, history, guard, evaluator, listener, connection);
		tools = new JDWPTools(connection, tracker, watchers, evaluator, history, guard,
			mock(JvmDiscoveryService.class), marks, health, noLocalClasspath, fastLogpoints);
	}

	static LiveSession start(List<String> extraJvmArgs) throws Exception {
		final String classpath = Path.of(FastLogpointTarget.class.getProtectionDomain().getCodeSource()
			.getLocation().toURI()).toString();
		// A fixed port, not :0 — after a detach the agent listens again on the same address, and the
		// disconnect test re-attaches.
		final int port;
		try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = probe.getLocalPort();
		}
		final List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + port);
		command.addAll(extraJvmArgs);
		command.addAll(List.of("-cp", classpath, TARGET, "2"));
		final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		awaitReady(process);
		final LiveSession session = new LiveSession(process, port);
		session.connection.connect("127.0.0.1", port);
		return session;
	}

	@Override
	public void close() throws Exception {
		try {
			connection.disconnect();
		} finally {
			process.getOutputStream().close();
			process.destroy();
			process.waitFor(10, TimeUnit.SECONDS);
		}
	}

	/**
	 * Reads the forked JVM's output until the program is ready, then keeps draining it in the
	 * background so the child never blocks on a full pipe.
	 */
	private static void awaitReady(Process process) throws Exception {
		final BufferedReader out = new BufferedReader(
			new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
		final CompletableFuture<Void> ready = CompletableFuture.runAsync(() -> {
			try {
				String line;
				while ((line = out.readLine()) != null) {
					if (line.contains("TARGET READY")) {
						return;
					}
				}
				throw new IllegalStateException("target exited before it was ready");
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		ready.get(30, TimeUnit.SECONDS);
		final Thread drain = new Thread(() -> {
			try {
				while (out.readLine() != null) {
					// discard
				}
			} catch (Exception ignored) {
				// process ended
			}
		}, "fast-logpoint-target-output");
		drain.setDaemon(true);
		drain.start();
	}

	/**
	 * Bytecode of every method of the target class as the VM runs it now.
	 */
	Map<String, byte[]> liveBytecode() throws Exception {
		final Map<String, byte[]> code = new HashMap<>();
		for (Method method : connection.getVM().classesByName(TARGET).get(0).methods()) {
			if (!method.isAbstract() && !method.isNative()) {
				code.put(method.name() + method.signature(), method.bytecodes());
			}
		}
		return code;
	}

	/**
	 * The ID a set-tool reply announces as {@code ID: <n>}.
	 */
	static int idOf(String reply) {
		final Matcher m = Pattern.compile("ID: (\\d+)").matcher(reply);
		assertThat(m.find()).as(reply).isTrue();
		return Integer.parseInt(m.group(1));
	}

	/**
	 * The line of the target's source that carries {@code marker}.
	 */
	static int lineOf(String marker) throws Exception {
		return lineOf(marker, TARGET_SOURCE);
	}

	/**
	 * The line of {@code source} that carries {@code marker}.
	 */
	static int lineOf(String marker, Path source) throws Exception {
		final List<String> lines = Files.readAllLines(source);
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).contains(marker)) {
				return i + 1;
			}
		}
		throw new IllegalStateException(marker + " not found in " + source);
	}

	/**
	 * Makes the target load {@code LateTarget} and start its worker.
	 */
	void loadLateTarget() throws Exception {
		process.getOutputStream().write('L');
		process.getOutputStream().flush();
	}

	/**
	 * Lets the target's {@code main} return, so the JVM exits.
	 */
	void letTargetExit() throws Exception {
		process.getOutputStream().close();
	}

	/**
	 * Enables a one-shot request while every thread is suspended. JDI drops an event the target
	 * raises before the enable reply is read, and on a hot line that spends the count filter: the
	 * request would never report a hit (the service's arming requests do the same).
	 */
	static void enableSuspended(EventRequest request) {
		final VirtualMachine vm = request.virtualMachine();
		vm.suspend();
		try {
			request.enable();
		} finally {
			vm.resume();
		}
	}
}
