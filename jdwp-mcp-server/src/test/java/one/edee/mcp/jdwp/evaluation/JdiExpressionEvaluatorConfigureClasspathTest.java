package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.evaluation.exceptions.LocalClasspathPendingException;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests on-demand compiler configuration, including local classpath publication and reset after
 * discovery failure.
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

	/** Handles replacement discovery even when a completed scan added no compiler entries. */
	@ParameterizedTest
	@CsvSource({"false, pending", "true, pending", "false, empty", "true, empty", "true, duplicate"})
	void probeUsesReplacementScanDuringFirstCompile(boolean ready, String initialScan, @TempDir Path root)
		throws Exception {
		final boolean initialScanComplete = !initialScan.equals("pending");
		final List<String> initialEntries = initialScan.equals("duplicate")
			? List.of(root.resolve("remote").toString()) : List.of();
		final Path initial = Files.createDirectory(root.resolve("initial"));
		Files.writeString(initial.resolve("pom.xml"), "<project/>");
		final Path replacement = Files.createDirectory(root.resolve("replacement"));
		final Path classes = compileProbeDependency(replacement);
		final ClientWorkspace workspace = new ClientWorkspace();
		final CountDownLatch release = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(initial, workspace, name -> null,
			(command, directory, timeout) -> {
				if (initialScanComplete && directory.equals(initial)) {
					return initialEntries;
				}
				assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
				return directory.equals(replacement) ? List.of(classes.toString()) : List.of();
			});
		final VirtualMachine vm = mock(VirtualMachine.class);
		final StackFrame frame = configureProbeFrame(vm, root);
		final AtomicInteger attempts = new AtomicInteger();
		final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler() {
			/** Switches workspaces once, while ECJ still holds the original remote-only options. */
			@Override
			Map<String, byte[]> compile(String name, String source, @Nullable Configuration options)
				throws JdiEvaluationException {
				if (attempts.getAndIncrement() == 0) {
					reportWorkspace(workspace, replacement);
					if (ready) {
						release.countDown();
						assertThat(provider.discover()).containsExactly(classes.toString());
					}
				}
				return super.compile(name, source, options);
			}
		};
		final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), provider);
		evaluator.startLocalClasspathDiscovery(vm);
		try {
			if (initialScanComplete) {
				assertThat(provider.discover()).containsExactlyElementsOf(initialEntries);
			}
			evaluator.configureCompilerClasspathForEvent(thread);
			if (ready) {
				assertThat(compileDependencyProbe(evaluator, frame)).isNotNull();
				assertThat(attempts).hasValue(2);
			} else {
				assertThatThrownBy(() -> compileDependencyProbe(evaluator, frame))
					.isInstanceOf(LocalClasspathPendingException.class);
				assertThat(provider.discoveryStatus().running()).isTrue();
				assertThat(attempts).hasValue(1);
				release.countDown();
				assertThat(provider.discover()).containsExactly(classes.toString());
				evaluator.configureCompilerClasspathForEvent(thread);
				assertThat(compileDependencyProbe(evaluator, frame)).isNotNull();
			}
		} finally {
			release.countDown();
			evaluator.stopLocalClasspathDiscovery(vm);
		}
	}

	/** Rejects both probe bytecode and a compiler diagnostic from a superseded merged retry. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void probeDiscardsRetryResultWhenWorkspaceChanges(boolean oldDependencyPresent, @TempDir Path root)
		throws Exception {
		final Path original = Files.createDirectory(root.resolve("original"));
		Files.writeString(original.resolve("pom.xml"), "<project/>");
		final Path oldClasses = oldDependencyPresent ? compileProbeDependency(original) : original;
		final Path replacement = Files.createDirectory(root.resolve("replacement"));
		final Path newClasses = compileProbeDependency(replacement);
		final ClientWorkspace workspace = new ClientWorkspace();
		final CountDownLatch release = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(original, workspace, name -> null,
			(command, directory, timeout) -> {
				assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
				return List.of((directory.equals(original) ? oldClasses : newClasses).toString());
			});
		final VirtualMachine vm = mock(VirtualMachine.class);
		final StackFrame frame = configureProbeFrame(vm, root);
		final AtomicInteger attempts = new AtomicInteger();
		final InMemoryJavaCompiler compiler = new InMemoryJavaCompiler() {
			/** Makes the original scan ready, then replaces it while ECJ runs with its result. */
			@Override
			Map<String, byte[]> compile(String name, String source, @Nullable Configuration options)
				throws JdiEvaluationException {
				final int attempt = attempts.incrementAndGet();
				if (attempt == 1) {
					release.countDown();
					assertThat(provider.discover()).containsExactly(oldClasses.toString());
				} else if (attempt == 2) {
					assertThat(Objects.requireNonNull(options).classpath()).contains(oldClasses.toString());
					reportWorkspace(workspace, replacement);
					assertThat(provider.discover()).containsExactly(newClasses.toString());
				}
				return super.compile(name, source, options);
			}
		};
		final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), provider);
		evaluator.startLocalClasspathDiscovery(vm);
		try {
			evaluator.configureCompilerClasspathForEvent(thread);
			assertThatThrownBy(() -> compileDependencyProbe(evaluator, frame))
				.isInstanceOf(LocalClasspathPendingException.class)
				.hasMessageContaining("changed");
			assertThat(attempts).hasValue(2);
			verify(jdiService).discoverClasspath(thread);
			evaluator.configureCompilerClasspathForEvent(thread);
			assertThat(compileDependencyProbe(evaluator, frame)).isNotNull();
		} finally {
			release.countDown();
			evaluator.stopLocalClasspathDiscovery(vm);
		}
	}

	/** Prepares a probe frame whose remote classpath does not contain the Maven dependency. */
	private StackFrame configureProbeFrame(VirtualMachine vm, Path root) throws Exception {
		final StackFrame frame = mock(StackFrame.class);
		when(thread.virtualMachine()).thenReturn(vm);
		when(frame.virtualMachine()).thenReturn(vm);
		when(frame.visibleVariables()).thenReturn(List.of());
		when(jdiService.getDiscoveredJdkPath()).thenReturn(System.getProperty("java.home"));
		when(jdiService.getTargetMajorVersion()).thenReturn(17);
		when(jdiService.discoverClasspath(thread)).thenReturn(Files.createDirectory(root.resolve("remote")).toString());
		return frame;
	}

	/** Produces a compile-time constant available only through the specified workspace's scan. */
	private static Path compileProbeDependency(Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final Path classes = Files.createDirectory(project.resolve("classes"));
		final Path source = Files.writeString(project.resolve("Dependency.java"),
			"package fixture; public class Dependency { public static final int VALUE = 42; }");
		assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
			"-d", classes.toString(), source.toString())).isZero();
		return classes;
	}

	/** Reports the new workspace through the same metadata path used by the client. */
	private static void reportWorkspace(ClientWorkspace workspace, Path directory) {
		workspace.observe(Map.of("codex/sandbox-state", Map.of("sandboxCwd", directory.toString())));
	}

	/** Arms the compile step of a fast logpoint that references the local dependency. */
	private static FastLogpointProbe compileDependencyProbe(JdiExpressionEvaluator evaluator, StackFrame frame)
		throws JdiEvaluationException {
		final String expression = "fixture.Dependency.VALUE";
		final FastLogpointProbeSource.Metadata metadata = new FastLogpointProbeSource.Metadata(
			"fixture.Target", "work", "()V", 10, expression, null, 0, "test");
		return evaluator.compileFastLogpointProbe(frame, false, "fixture", expression, null, 16, metadata);
	}

	/**
	 * Completes discovery during ECJ's first attempt, then checks an unknown qualified name or a newly available type.
	 * The unknown name is qualified because a bare misspelled local is a permanent error without any retry.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void probeRetriesOnceIfDiscoveryFinishesDuringCompilation(boolean typo, @TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final Path local = Files.createDirectory(project.resolve("local"));
		final Path source = Files.writeString(project.resolve("Late.java"),
			"package fixture; public class Late { public static final int VALUE = 42; }");
		assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
			"-d", local.toString(), source.toString())).isZero();
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, directory, timeout) -> {
				assertThat(releaseMaven.await(30, TimeUnit.SECONDS)).isTrue();
				return List.of(local.toString());
			});
		final AtomicInteger attempts = new AtomicInteger();
		final InMemoryJavaCompiler finishingCompiler = new InMemoryJavaCompiler() {
			/** Publishes the local result while the first compilation still holds its remote options. */
			@Override
			Map<String, byte[]> compile(String name, String text, @Nullable Configuration config)
				throws JdiEvaluationException {
				if (attempts.getAndIncrement() == 0) {
					releaseMaven.countDown();
					assertThat(provider.discover()).containsExactly(local.toString());
				}
				return super.compile(name, text, config);
			}
		};
		final VirtualMachine vm = mock(VirtualMachine.class);
		final StackFrame frame = mock(StackFrame.class);
		when(thread.virtualMachine()).thenReturn(vm);
		when(frame.virtualMachine()).thenReturn(vm);
		when(frame.visibleVariables()).thenReturn(List.of());
		when(jdiService.getDiscoveredJdkPath()).thenReturn(System.getProperty("java.home"));
		when(jdiService.getTargetMajorVersion()).thenReturn(17);
		when(jdiService.discoverClasspath(thread)).thenReturn(project.toString());
		final JdiExpressionEvaluator withLocal = new JdiExpressionEvaluator(
			finishingCompiler, jdiService, new EvaluationGuard(), provider);
		final String expression = typo ? "Missing.VALUE" : "fixture.Late.VALUE";
		final FastLogpointProbeSource.Metadata metadata = new FastLogpointProbeSource.Metadata(
			"fixture.Target", "work", "()V", 10, expression, null, 0, "test");
		withLocal.startLocalClasspathDiscovery(vm);
		try {
			withLocal.configureCompilerClasspathForEvent(thread);
			assertThat(provider.discoveryStatus().running()).isTrue();
			if (typo) {
				assertThatThrownBy(() -> withLocal.compileFastLogpointProbe(
					frame, false, "fixture", expression, null, 16, metadata))
					.isInstanceOf(JdiEvaluationException.class).isNotInstanceOf(LocalClasspathPendingException.class)
					.hasMessageContaining("Missing cannot be resolved");
			} else {
				assertThat(withLocal.compileFastLogpointProbe(frame, false, "fixture", expression, null, 16, metadata))
					.isNotNull();
			}
			assertThat(attempts.get()).isEqualTo(2);
			verify(jdiService).discoverClasspath(thread);
		} finally {
			releaseMaven.countDown();
			withLocal.stopLocalClasspathDiscovery(vm);
		}
	}

	/** Finishes local discovery during the remote walk and checks one publication with both results. */
	@Test
	void eventMergesLocalClasspathThatBecomesReadyDuringRemoteWalk(@TempDir Path project) throws Exception {
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		final Path local = project.resolve("local.jar");
		final CountDownLatch releaseMaven = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, name -> null,
			(command, directory, timeout) -> {
				if (!releaseMaven.await(30, TimeUnit.SECONDS)) {
					throw new AssertionError("Remote walk did not release Maven");
				}
				return List.of(local.toString());
			});
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(thread.virtualMachine()).thenReturn(vm);
		when(jdiService.getDiscoveredJdkPath()).thenReturn("/opt/jdk");
		when(jdiService.getTargetMajorVersion()).thenReturn(21);
		when(jdiService.discoverClasspath(thread)).thenAnswer(call -> {
			releaseMaven.countDown();
			assertThat(provider.discover()).containsExactly(local.toString());
			return "/remote";
		});
		final JdiExpressionEvaluator withLocal = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), provider);
		withLocal.startLocalClasspathDiscovery(vm);
		try {
			withLocal.configureCompilerClasspathForEvent(thread);
			verify(compiler).configure("/opt/jdk", "/remote" + File.pathSeparator + local, 21);
			verify(compiler, never()).configure("/opt/jdk", "/remote", 21);
			verify(jdiService).discoverClasspath(thread);
		} finally {
			releaseMaven.countDown();
			withLocal.stopLocalClasspathDiscovery(vm);
		}
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

	/** Reuses options when the connection and the completed local discovery still match. */
	@Test
	@DisplayName("A completed full configure short-circuits a second call without re-discovering")
	void shouldShortCircuitWhenAlreadyConfigured() throws Exception {
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

	/** Carries a remote gap into a missing-type error while using the available entries. */
	@Test
	void compileErrorNamesTheIncompleteRemoteWalk(@TempDir Path project) throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final StackFrame frame = configureProbeFrame(vm, project);
		final String problem = "classpath discovery was incomplete: example.Loader: could not read parent";
		when(jdiService.getClasspathDiscoveryProblem()).thenReturn(problem);
		final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(new InMemoryJavaCompiler(), jdiService,
			new EvaluationGuard(), LocalProjectClasspathProviderTestSupport.noOpProvider());

		evaluator.configureCompilerClasspathForEvent(thread);

		assertThatThrownBy(() -> compileDependencyProbe(evaluator, frame))
			.isInstanceOf(JdiEvaluationException.class).hasMessageContaining("cannot be resolved")
			.hasMessageContaining(problem);
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

}
