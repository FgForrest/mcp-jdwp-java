package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ThreadReference;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the {@link JdiExpressionEvaluator#configureCompilerClasspath(ThreadReference)} union
 * semantics introduced when the local-project classpath fallback was wired in:
 * the compiler classpath received by {@link InMemoryJavaCompiler#configure} is the merger of
 * the remote (target VM) classpath and the local-project entries — remote-first, deduped,
 * joined with {@link File#pathSeparator}.
 *
 * <p>The local entries must NOT replace the remote ones; they must augment them, so live
 * target VM JAR locations continue to win on JDT type resolution and local entries only fill
 * gaps the remote classloader walk could not see (Tomcat / Spring Boot dev-tools / custom
 * URLClassLoaders that hide their JARs from {@code getURLs()}).
 */
@DisplayName("JdiExpressionEvaluator — remote+local classpath union")
class JdiExpressionEvaluatorLocalClasspathUnionTest {

	private InMemoryJavaCompiler compiler;
	private JDIConnectionService jdiService;
	private ThreadReference thread;

	@BeforeEach
	void setUp() {
		compiler = mock(InMemoryJavaCompiler.class);
		jdiService = mock(JDIConnectionService.class);
		thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(42L);
	}

	@Test
	@DisplayName("compiler receives the union of remote and local entries — remote first, deduped")
	void shouldFeedCompilerTheUnionOfRemoteAndLocalEntries() throws Exception {
		// First call (guard) returns null → proceed; second call (post-discovery) returns the path
		// so the configure-success branch is taken.
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn("/r1:/r2");
		when(jdiService.getTargetMajorVersion()).thenReturn(21);

		// Local provider contributes /r1 (overlapping with remote — must be deduped) plus two
		// new entries. The remote/local separator handling is host-agnostic (the prod code
		// detects `;` vs `:` from the remote content), and the JOINED output uses the host
		// File.pathSeparator.
		final Set<String> localEntries = new LinkedHashSet<>(List.of("/r1", "/local1", "/local2"));
		final LocalProjectClasspathProvider local = stubLocalProvider(localEntries);

		final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), local);

		evaluator.configureCompilerClasspath(thread);

		final ArgumentCaptor<String> cpCaptor = ArgumentCaptor.forClass(String.class);
		verify(compiler).configure(eq("/opt/jdk"), cpCaptor.capture(), eq(21));

		final List<String> entries = List.of(cpCaptor.getValue().split(java.util.regex.Pattern.quote(File.pathSeparator), -1));
		// Remote-first, /r1 deduped (appears once), local-only entries appended in the order
		// the provider returned them.
		assertThat(entries).containsExactly("/r1", "/r2", "/local1", "/local2");
	}

	@Test
	@DisplayName("local classpath is still used when remote discovery returns empty")
	void shouldUseLocalClasspathWhenRemoteDiscoveryReturnsEmpty() throws Exception {
		// Remote discovery yields null classpath but JDK was discovered → previously this would
		// throw "classpath could not be determined"; the new behaviour is that local entries
		// alone are enough to keep evaluation alive.
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn(null);
		when(jdiService.getTargetMajorVersion()).thenReturn(21);

		final Set<String> localEntries = new LinkedHashSet<>(List.of("/local/a", "/local/b"));
		final LocalProjectClasspathProvider local = stubLocalProvider(localEntries);

		final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), local);

		assertThatCode(() -> evaluator.configureCompilerClasspath(thread)).doesNotThrowAnyException();

		final ArgumentCaptor<String> cpCaptor = ArgumentCaptor.forClass(String.class);
		verify(compiler).configure(eq("/opt/jdk"), cpCaptor.capture(), anyInt());
		final List<String> entries = List.of(cpCaptor.getValue().split(java.util.regex.Pattern.quote(File.pathSeparator), -1));
		assertThat(entries).containsExactly("/local/a", "/local/b");
	}

	/**
	 * Separator detection must not shred a single-entry Windows-style remote path. A bare
	 * content-based heuristic ({@code remote.contains(";")}) treats {@code "C:\foo"} as colon-separated
	 * and breaks the drive letter into {@code "C"} + {@code "\foo"}. The provider must use the host
	 * {@link File#pathSeparator} for both detection and splitting.
	 */
	@Test
	@DisplayName("does not shred a single-entry Windows-style remote path on `:` separator")
	void shouldNotShredSingleEntryWindowsPath() throws Exception {
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn("C:\\foo");
		when(jdiService.getTargetMajorVersion()).thenReturn(21);

		final LocalProjectClasspathProvider local = stubLocalProvider(Set.of());

		final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), local);

		evaluator.configureCompilerClasspath(thread);

		final ArgumentCaptor<String> cpCaptor = ArgumentCaptor.forClass(String.class);
		verify(compiler).configure(eq("/opt/jdk"), cpCaptor.capture(), anyInt());

		final String merged = cpCaptor.getValue();
		// The merged classpath must contain the original Windows entry verbatim — `C:\foo` survives
		// the host-separator join because the provider's separator-detection treats a single
		// drive-letter path as one entry. Asserting on the merged string (not on entries split by
		// the host pathSeparator) keeps the check meaningful on non-Windows hosts where the host
		// separator IS `:` and would shred the value at the assertion side.
		assertThat(merged)
			.as("single-entry Windows path must not be shredded on the `:` heuristic")
			.isEqualTo("C:\\foo");
	}

	/**
	 * When both the remote classpath is empty/null and the local provider contributes zero entries,
	 * {@code configureCompilerClasspath} throws an actionable {@link JdiEvaluationException} whose
	 * message embeds the working directory. If {@code user.dir} has been cleared (some sandboxed /
	 * restricted JVM launch options do this), the message must fall back to a placeholder instead
	 * of containing the literal string {@code "null"}.
	 */
	@Test
	@DisplayName("does not embed the literal string 'null' when user.dir is unset")
	void shouldNotEmitLiteralNullInExceptionMessage() throws Exception {
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn(null);
		when(jdiService.getTargetMajorVersion()).thenReturn(21);

		final LocalProjectClasspathProvider local = stubLocalProvider(Set.of());

		final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), local);

		final String savedUserDir = System.getProperty("user.dir");
		System.clearProperty("user.dir");
		try {
			assertThatCode(() -> evaluator.configureCompilerClasspath(thread))
				.isInstanceOf(JdiEvaluationException.class)
				.satisfies(e -> assertThat(e.getMessage())
					.as("exception message must not embed the literal string \"null\"")
					.doesNotContain("directory 'null'"));
		} finally {
			if (savedUserDir != null) {
				System.setProperty("user.dir", savedUserDir);
			}
		}
	}

	/**
	 * Under Codex the server runs in the plugin's install directory; the "nothing found" error must
	 * name the session directory the client reported, which is the one that was scanned, rather
	 * than the server's launch directory.
	 */
	@Test
	@DisplayName("the empty-classpath error names the session directory the MCP client reported")
	void shouldNameClientReportedDirectoryInExceptionMessage(@TempDir Path session) throws Exception {
		when(jdiService.getDiscoveredJdkPath()).thenReturn(null, "/opt/jdk");
		when(jdiService.discoverClasspath(thread)).thenReturn(null);
		when(jdiService.getTargetMajorVersion()).thenReturn(21);
		final ClientWorkspace workspace = new ClientWorkspace();
		workspace.observe(Map.of("codex/sandbox-state", Map.of("sandboxCwd", session.toUri().toString())));
		final LocalProjectClasspathProvider local = new LocalProjectClasspathProvider(
			Path.of(System.getProperty("java.io.tmpdir"), "jdwp-mcp-plugin-root-" + java.util.UUID.randomUUID()),
			workspace, name -> null, (c, d, t) -> List.of());

		final JdiExpressionEvaluator evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(), local);

		assertThatCode(() -> evaluator.configureCompilerClasspath(thread))
			.isInstanceOf(JdiEvaluationException.class)
			.satisfies(e -> assertThat(e.getMessage())
				.contains("'" + session.normalize() + "' (the session directory the MCP client reported)")
				.doesNotContain("jdwp-mcp-plugin-root-"));
	}

	/**
	 * Builds a seam-taking {@link LocalProjectClasspathProvider} whose {@code discover()} returns
	 * the fixed entry set without touching the real environment, filesystem, or Maven. Uses the
	 * env-lookup seam to inject the entries via the {@code JDWP_EXTRA_CLASSPATH} pathway — this
	 * keeps the test self-contained (no need to mock the public synchronized method).
	 */
	private static LocalProjectClasspathProvider stubLocalProvider(Set<String> entries) {
		// Build a JDWP_EXTRA_CLASSPATH-style value joined with the host File.pathSeparator —
		// this is the exact format the provider's env-override path expects.
		final String joined = String.join(File.pathSeparator, entries);
		return new LocalProjectClasspathProvider(
			// Portable non-existent root: tmpdir is platform-correct on every host; appending a
			// UUID-named child we never create gives a guaranteed-absent root, so the depth-5
			// scan short-circuits at the first isDirectory probe and never walks into whatever
			// tmpdir itself contains.
			Path.of(System.getProperty("java.io.tmpdir"), "jdwp-mcp-union-test-" + java.util.UUID.randomUUID()),
			name -> "JDWP_EXTRA_CLASSPATH".equals(name) ? joined : null,
			(c, d, t) -> List.of()
		);
	}
}
