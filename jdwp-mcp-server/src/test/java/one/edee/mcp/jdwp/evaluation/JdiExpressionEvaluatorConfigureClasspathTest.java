package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the discovery-failure and pre-warm semantics of
 * {@link JdiExpressionEvaluator#configureCompilerClasspath(ThreadReference)} and
 * {@link JdiExpressionEvaluator#prewarmClasspath(ThreadReference)}.
 *
 * <p>Covers the classpath-warming lifecycle contract: a failed discovery must reset
 * the compiler (so no stale config survives), surface an actionable exception from the strict path,
 * and stay silent on the best-effort pre-warm path.
 */
class JdiExpressionEvaluatorConfigureClasspathTest {

	private InMemoryJavaCompiler compiler;
	private JDIConnectionService jdiService;
	private JdiExpressionEvaluator evaluator;
	private ThreadReference thread;

	@BeforeEach
	void setUp() {
		compiler = mock(InMemoryJavaCompiler.class);
		jdiService = mock(JDIConnectionService.class);
		// No-op local-classpath provider — these tests only care about the remote-side reset/configure
		// semantics; LocalProjectClasspathProviderTestSupport supplies a provider that never touches
		// the real environment, filesystem, or Maven.
		evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(),
			LocalProjectClasspathProviderTestSupport.noOpProvider());
		thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(42L);
	}

	/** Changes the reported workspace between reading discovery and publishing compiler options. */
	@Test
	void workspaceChangeBeforePublicationDiscardsOldClasspath(@TempDir Path project) throws Exception {
		final Path next = Files.createDirectory(project.resolve("next"));
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		Files.writeString(next.resolve("pom.xml"), "<project/>");
		final ClientWorkspace workspace = new ClientWorkspace();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, workspace,
			name -> null, (command, directory, timeout) -> List.of(directory.resolve("local.jar").toString()));
		final JdiExpressionEvaluator withWorkspace = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), provider);
		when(jdiService.getDiscoveredJdkPath()).thenReturn("/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn("/app");
		when(jdiService.getTargetMajorVersion()).thenAnswer(call -> {
			workspace.observe(Map.of("codex/sandbox-state", Map.of("sandboxCwd", next.toString())));
			provider.snapshotFor(null, true);
			return 21;
		});
		assertThatCode(() -> {
			provider.discover();
			withWorkspace.configureCompilerClasspathForEvent(thread);
		}).doesNotThrowAnyException();

		verify(compiler, never()).configure("/opt/jdk", "/app" + File.pathSeparator + project.resolve("local.jar"), 21);
		provider.discover();
		withWorkspace.configureCompilerClasspathForEvent(thread);
		verify(compiler).configure("/opt/jdk", "/app" + File.pathSeparator + next.resolve("local.jar"), 21);
	}

	/** Replaces the VM during configuration and checks that late work cannot publish for it. */
	@Test
	void reconnectBeforePublicationRefusesOldConnection() throws Exception {
		final VirtualMachine previous = mock(VirtualMachine.class);
		final VirtualMachine next = mock(VirtualMachine.class);
		when(thread.virtualMachine()).thenReturn(previous);
		when(jdiService.getDiscoveredJdkPath()).thenReturn("/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn("/old");
		when(jdiService.getTargetMajorVersion()).thenAnswer(call -> {
			evaluator.stopLocalClasspathDiscovery(previous);
			evaluator.startLocalClasspathDiscovery(next);
			// A delayed teardown from the previous listener must leave the replacement alive.
			evaluator.stopLocalClasspathDiscovery(previous);
			return 21;
		});

		evaluator.startLocalClasspathDiscovery(previous);
		assertThatThrownBy(() -> evaluator.configureCompilerClasspathForEvent(thread))
			.isInstanceOf(JdiEvaluationException.class).hasMessageContaining("target connection ended");
		verify(compiler, never()).configure(any(), any(), org.mockito.ArgumentMatchers.anyInt());

		when(thread.virtualMachine()).thenReturn(next);
		when(jdiService.discoverClasspath(thread)).thenReturn("/new");
		org.mockito.Mockito.doReturn(21).when(jdiService).getTargetMajorVersion();
		evaluator.configureCompilerClasspath(thread);
		verify(compiler).configure("/opt/jdk", "/new", 21);
	}

	@Test
	@DisplayName("Discovery failure (no JDK) throws an actionable JdiEvaluationException")
	void shouldThrowWhenJdkNotDiscovered() {
		// Not cached → proceed into discovery; discovery leaves the JDK path null → failure.
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null);
		when(jdiService.discoverClasspath(thread)).thenReturn(null);

		assertThatThrownBy(() -> evaluator.configureCompilerClasspath(thread))
			.isInstanceOf(JdiEvaluationException.class)
			.hasMessageContaining("Classpath discovery failed")
			.hasMessageContaining("application types cannot be resolved");
	}

	@Test
	@DisplayName("Discovery failure resets the compiler and never configures it with stale state")
	void shouldResetCompilerOnDiscoveryFailure() {
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null);
		when(jdiService.discoverClasspath(thread)).thenReturn(null);

		assertThatThrownBy(() -> evaluator.configureCompilerClasspath(thread))
			.isInstanceOf(JdiEvaluationException.class);

		verify(compiler).reset();
		verify(compiler, never()).configure(any(), any(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	@DisplayName("A completed full configure short-circuits a second call without re-discovering")
	void shouldShortCircuitWhenAlreadyConfigured() throws Exception {
		// First call: discovery succeeds and the local fallback is merged → the connection is fully
		// configured. A non-null JDK path alone is NOT enough to short-circuit a real evaluation now
		// (prewarm leaves it set with the local fallback still pending); only a completed full
		// configure is. Drive one full configure, then assert the second call does nothing.
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn("/app/classes");
		when(jdiService.getTargetMajorVersion()).thenReturn(21);

		evaluator.configureCompilerClasspath(thread);
		org.mockito.Mockito.clearInvocations(compiler, jdiService);

		evaluator.configureCompilerClasspath(thread);

		verify(compiler, never()).reset();
		verify(compiler, never()).configure(any(), any(), org.mockito.ArgumentMatchers.anyInt());
		verify(jdiService, never()).discoverClasspath(any());
	}

	@Test
	@DisplayName("prewarm warms remote+JDK but defers local merge; first real eval completes it")
	void prewarmDefersLocalMergeUntilFirstEvaluation() throws Exception {
		final LocalProjectClasspathProvider local = new LocalProjectClasspathProvider(
			java.nio.file.Path.of("missing-classpath-fixture"), name -> "/local/a", (c, d, t) -> java.util.List.of());
		final JdiExpressionEvaluator deferEvaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), local);
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(thread.virtualMachine()).thenReturn(vm);
		deferEvaluator.startLocalClasspathDiscovery(vm);
		local.discover();

		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn("/app/classes");
		when(jdiService.getTargetMajorVersion()).thenReturn(21);

		deferEvaluator.prewarmClasspath(thread);
		verify(compiler).configure("/opt/jdk", "/app/classes", 21);
		deferEvaluator.configureCompilerClasspath(thread);
		verify(compiler).configure("/opt/jdk", "/app/classes" + java.io.File.pathSeparator + "/local/a", 21);
	}

	@Test
	@DisplayName("Successful discovery resets then configures the compiler with discovered values")
	void shouldConfigureOnSuccessfulDiscovery() throws Exception {
		// First call (guard) returns null so we proceed; second call (post-discovery) returns the path.
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn("/app/classes:/app/lib.jar");
		when(jdiService.getTargetMajorVersion()).thenReturn(21);

		evaluator.configureCompilerClasspath(thread);

		verify(compiler).reset();
		verify(compiler).configure("/opt/jdk", "/app/classes:/app/lib.jar", 21);
	}

	@Test
	@DisplayName("prewarmClasspath swallows a discovery failure instead of throwing")
	void prewarmShouldNotThrowOnFailure() {
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null);
		when(jdiService.discoverClasspath(thread)).thenReturn(null);

		assertThatCode(() -> evaluator.prewarmClasspath(thread)).doesNotThrowAnyException();
		verify(compiler).reset();
	}

	@Test
	@DisplayName("prewarmClasspath short-circuits when the connection is already configured")
	void prewarmShouldShortCircuitWhenConfigured() {
		when(jdiService.getDiscoveredJdkPath()).thenReturn("/opt/jdk");

		evaluator.prewarmClasspath(thread);

		verify(jdiService, never()).discoverClasspath(any());
		verify(compiler, never()).reset();
	}
}
