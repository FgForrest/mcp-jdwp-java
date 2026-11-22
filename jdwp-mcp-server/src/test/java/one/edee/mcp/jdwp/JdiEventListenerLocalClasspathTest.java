package one.edee.mcp.jdwp;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import one.edee.mcp.jdwp.evaluation.InMemoryJavaCompiler;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockBreakpointEvent;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockEventSet;
import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.mockThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdiEventListenerLocalClasspathTest {
	private final BreakpointTracker tracker = new BreakpointTracker();
	private final InMemoryJavaCompiler compiler = mock(InMemoryJavaCompiler.class);
	private final JDIConnectionService connection = mock(JDIConnectionService.class);
	private final JdiEventListener listener;
	private final JdiExpressionEvaluator evaluator;
	private final LinkedBlockingQueue<EventSet> events = new LinkedBlockingQueue<>();
	private final VirtualMachine vm = mock(VirtualMachine.class);
	private final ThreadReference thread = mockThread("test-target", 42L);
	private final BreakpointRequest request = mock(BreakpointRequest.class);
	private final ClientWorkspace workspace = new ClientWorkspace();
	private final LocalProjectClasspathProvider provider;
	private final LinkedBlockingQueue<Path> mavenDirectories = new LinkedBlockingQueue<>();
	private final CountDownLatch releaseMaven = new CountDownLatch(1);
	private final String remote = "/app/classes";
	private final String local = "/m2/dependency.jar";

	JdiEventListenerLocalClasspathTest(@TempDir Path root) throws Exception {
		Files.writeString(root.resolve("pom.xml"), "<project/>");
		provider = new LocalProjectClasspathProvider(root, workspace, name -> null, (command, directory, timeout) -> {
			mavenDirectories.add(directory);
			try {
				releaseMaven.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return List.of(local);
		});
		final EvaluationGuard evaluatorGuard = mock(EvaluationGuard.class);
		when(evaluatorGuard.lease(any())).thenReturn(mock(EvaluationGuard.Lease.class));
		final AtomicReference<String> jdkPath = new AtomicReference<>();
		when(connection.getDiscoveredJdkPath()).thenAnswer(call -> jdkPath.get());
		when(connection.discoverClasspath(thread)).thenAnswer(call -> {
			jdkPath.set("/opt/jdk");
			return remote;
		});
		when(connection.getTargetMajorVersion()).thenReturn(21);
		evaluator = spy(new JdiExpressionEvaluator(compiler, connection, evaluatorGuard, provider));
		listener = new JdiEventListener(tracker, new EventHistory(), evaluator,
			new EvaluationGuard(), null, new MarkedInstanceRegistry());
		final EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);
		when(queue.remove()).thenAnswer(call -> events.take());
		when(thread.frame(0)).thenReturn(mock(StackFrame.class));
		final BooleanValue falseValue = mock(BooleanValue.class);
		doReturn(falseValue).when(evaluator).evaluate(any(), any(), any());
		tracker.setCondition(tracker.registerBreakpoint(request), "false");
	}

	@AfterEach
	void stopListener() {
		releaseMaven.countDown();
		listener.stop();
	}

	@Test
	void dispatcherUsesRemoteClasspathWhileWorkerWaits() throws Exception {
		listener.start(vm);
		assertThat(mavenDirectories.poll(2, TimeUnit.SECONDS)).isEqualTo(provider.getWorkingDirectory());

		final CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
			try {
				evaluator.configureCompilerClasspath(thread);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		assertThatThrownBy(() -> worker.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
		fireCondition();
		verify(compiler).configure("/opt/jdk", remote, 21);
		assertThat(worker).isNotDone();

		releaseMaven.countDown();
		worker.get(2, TimeUnit.SECONDS);
		fireCondition();
		verify(compiler, times(1)).configure("/opt/jdk", remote + File.pathSeparator + local, 21);
		assertThat(mavenDirectories).isEmpty();
	}

	@Test
	void laterConditionMergesCompletedDiscovery() throws Exception {
		listener.start(vm);
		assertThat(mavenDirectories.poll(2, TimeUnit.SECONDS)).isEqualTo(provider.getWorkingDirectory());

		fireCondition();
		fireCondition();
		verify(compiler, times(1)).configure("/opt/jdk", remote, 21);

		releaseMaven.countDown();
		assertThat(provider.discover()).containsExactly(local);
		fireCondition();
		fireCondition();
		verify(compiler, times(1)).configure("/opt/jdk", remote + File.pathSeparator + local, 21);
		assertThat(mavenDirectories).isEmpty();
	}

	@Test
	void attachCapturesWorkspaceAndFreshAttachReplacesTheDiscovery(@TempDir Path nextDirectory) throws Exception {
		Files.writeString(nextDirectory.resolve("pom.xml"), "<project/>");
		final Path attachDirectory = provider.getWorkingDirectory();
		listener.start(vm);
		workspace.observe(Map.of("codex/sandbox-state", Map.of("sandboxCwd", nextDirectory.toString())));

		assertThat(mavenDirectories.poll(2, TimeUnit.SECONDS)).isEqualTo(attachDirectory);
		assertThat(provider.peekCachedBreakdown()).isNull();
		releaseMaven.countDown();
		fireCondition();
		assertThat(mavenDirectories.poll(2, TimeUnit.SECONDS)).isEqualTo(nextDirectory);
		assertThat(provider.discover()).containsExactly(local);

		listener.start(vm);
		assertThat(mavenDirectories.poll(2, TimeUnit.SECONDS)).isEqualTo(nextDirectory);
		assertThat(provider.discover()).containsExactly(local);
	}

	private void fireCondition() throws Exception {
		final CompletableFuture<Void> resumed = new CompletableFuture<>();
		final EventSet hit = mockEventSet(mockBreakpointEvent(thread, request, "example.Target", 12));
		doAnswer(call -> {
			resumed.complete(null);
			return null;
		}).when(hit).resume();
		events.add(hit);
		resumed.get(2, TimeUnit.SECONDS);
	}
}
