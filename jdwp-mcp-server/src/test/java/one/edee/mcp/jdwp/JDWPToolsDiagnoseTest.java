package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.discovery.JdwpEndpoint;
import one.edee.mcp.jdwp.discovery.JvmDescriptor;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the three-block {@code jdwp_diagnose} output. Each test pins one
 * observable feature of the rendered report: the MCP-server / connection / local-JVM block
 * structure, the connected-path branches (column alignment, attach-hint precedence), the
 * disconnected branches (last-attempt rendering with and without a prior error), and the
 * graceful degradation when discovery throws or {@code getConnectionStatus} fails outright.
 */
@DisplayName("jdwp_diagnose three-block report")
class JDWPToolsDiagnoseTest {

	private JDIConnectionService jdiService;
	private JvmDiscoveryService discovery;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker tracker = new BreakpointTracker();
		final WatcherManager watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		discovery = mock(JvmDiscoveryService.class);
		when(discovery.discover()).thenReturn(List.of());
		when(discovery.confirmAll(Mockito.anyList(), Mockito.any(), Mockito.anyInt())).thenAnswer(inv -> inv.getArgument(0));
		when(discovery.inspectAll(Mockito.anyList())).thenAnswer(inv -> inv.getArgument(0));
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), discovery);
	}

	@Test
	@DisplayName("disconnected: renders three blocks with last-attempt error")
	void shouldRenderThreeBlocksWhenDisconnected() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, "localhost", 5005, Instant.now(), "Connection refused"
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("MCP JDWP Inspector — Diagnostic Report");
		assertThat(result).contains("▸ MCP server");
		assertThat(result).contains("▸ JDWP connection");
		assertThat(result).contains("Not connected");
		assertThat(result).contains("Connection refused");
		assertThat(result).contains("▸ Local JVMs visible to user");
	}

	@Test
	@DisplayName("connected: connection block shows host:port + appended breakpoint report")
	void shouldAppendBreakpointReportWhenConnected() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			true, "localhost", 5005, Instant.now(), null
		));

		final String result = tools.jdwp_diagnose(false);

		assertThat(result).contains("✓ Connected to localhost:5005");
		// The legacy breakpoint diagnostic header appears verbatim.
		assertThat(result).contains("[DIAGNOSTIC] Current debugger state:");
		assertThat(result).contains("Active line breakpoints: 0");
		assertThat(result).contains("INTERPRETATION:");
	}

	/**
	 * Regression for P1-5: the MCP server's own JVM row must be filtered out of the rendered
	 * inventory — the agent can never attach to it, so the row is pure noise. Other JVMs on the
	 * list continue to render normally.
	 */
	@Test
	@DisplayName("THIS PROCESS row is filtered from the JVM block (P1-5)")
	void shouldFlagThisProcessRow() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final long selfPid = ProcessHandle.current().pid();
		when(discovery.discover()).thenReturn(List.of(
			new JvmDescriptor(selfPid, "JdwpMcpServerApplication", null, null,
				null, true, JvmDescriptor.Source.ATTACH_API),
			new JvmDescriptor(98765L, "com.example.Other", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.BOTH)
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).doesNotContain("(THIS PROCESS)");
		assertThat(result).doesNotContain("JdwpMcpServerApplication");
		// Sibling row must still render normally.
		assertThat(result).contains("98765");
		assertThat(result).contains(":5005");
		assertThat(result).contains("LISTENING");
	}

	@Test
	@DisplayName("inspectAll=true delegates to JvmDiscoveryService.inspectAll, default does not")
	void shouldRouteInspectAllFlag() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));

		tools.jdwp_diagnose(null);
		tools.jdwp_diagnose(false);
		verify(discovery, never()).inspectAll(Mockito.anyList());

		tools.jdwp_diagnose(true);
		verify(discovery, times(1)).inspectAll(Mockito.anyList());
	}

	@Test
	@DisplayName("when discovery throws, the rest of the report still renders")
	void shouldDegradeGracefullyOnDiscoveryFailure() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		when(discovery.discover()).thenThrow(new RuntimeException("attach api blew up"));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("▸ MCP server");
		assertThat(result).contains("▸ JDWP connection");
		assertThat(result).contains("Local JVMs");
		assertThat(result).contains("discovery failed: attach api blew up");
	}

	/**
	 * Regression for P1-5: a long-named THIS-PROCESS JVM is now filtered entirely — neither the
	 * truncated form nor the marker appear. The block reports "0 found" instead.
	 */
	@Test
	@DisplayName("long main class on THIS PROCESS row is filtered out (P1-5)")
	void shouldTruncateLongMainClassPreservingThisProcessMarker() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final long selfPid = ProcessHandle.current().pid();
		final String longMain = "com.example.really.long.package.with.many.segments.AndSubpackages.MainClass";
		when(discovery.discover()).thenReturn(List.of(
			new JvmDescriptor(selfPid, longMain, null, null, null, true, JvmDescriptor.Source.ATTACH_API)
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).doesNotContain(longMain);
		assertThat(result).doesNotContain("(THIS PROCESS)");
		assertThat(result).contains("(0 found)");
	}

	@Test
	@DisplayName("attach hint prefers the SUSPENDED port over a coexisting LISTENING port")
	void shouldPreferSuspendedPortForAttachHint() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		when(discovery.discover()).thenReturn(List.of(
			new JvmDescriptor(11111L, "com.first.Listening", null, null,
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.LISTENING),
				false, JvmDescriptor.Source.PROC_FS),
			new JvmDescriptor(22222L, "com.second.Suspended", null, null,
				new JdwpEndpoint("*", 5006, "dt_socket", true, true, JdwpEndpoint.State.SUSPENDED),
				false, JvmDescriptor.Source.PROC_FS)
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("💡 To attach");
		// SUSPENDED port wins regardless of input order. Use containsPattern to nail the hint
		// line specifically — the MCP-server block also prints "port=5005" in its Configured
		// line, so a plain doesNotContain("port=5005") would be too coarse.
		assertThat(result).containsPattern("💡 To attach: jdwp_wait_for_attach\\(port=5006\\)");
	}

	@Test
	@DisplayName("disconnected with no prior attempt prints 'Last attempt:  never'")
	void shouldRenderNeverAttemptedWhenStatusHasNoTimestamp() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("Last attempt:  never");
	}

	@Test
	@DisplayName("connected path does NOT emit the 'Suggestion:' line (suggestions are disconnected-only)")
	void shouldNotEmitSuggestionLineWhenConnected() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			true, "localhost", 5005, Instant.now(), null
		));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("✓ Connected to localhost:5005");
		assertThat(result).doesNotContain("Suggestion:");
	}

	@Test
	@DisplayName("rendered 'Tools:' line shows a positive registered-tool count")
	void shouldReportPositiveToolCount() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));

		final String result = tools.jdwp_diagnose(null);

		// The reflection-based counter must produce at least one tool — JDWPTools is full of
		// @McpTool methods. Match shape: "Tools:<whitespace><N> registered".
		assertThat(result).containsPattern("Tools:\\s+\\d+\\s+registered");
		assertThat(result).doesNotContain("Tools:         0 registered");
	}

	@Test
	@DisplayName("when getConnectionStatus() throws, all three top-level blocks still render")
	void shouldRenderAllBlocksWhenConnectionStatusThrows() {
		when(jdiService.getConnectionStatus()).thenThrow(new RuntimeException("vm reference is dead"));

		final String result = tools.jdwp_diagnose(null);

		assertThat(result).contains("▸ MCP server");
		// The connection block always renders, even when getConnectionStatus blows up — the code
		// falls back to a synthetic disconnected ConnectionStatus.
		assertThat(result).contains("▸ JDWP connection");
		assertThat(result).contains("Local JVMs");
	}

	@Test
	@DisplayName("confirmAll is called with (lastHost, lastPort) from getConnectionStatus")
	void shouldPassConnectedHostAndPortToConfirmAll() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			true, "10.0.0.5", 5050, Instant.now(), null
		));

		tools.jdwp_diagnose(null);

		final ArgumentCaptor<String> hostCaptor = ArgumentCaptor.forClass(String.class);
		final ArgumentCaptor<Integer> portCaptor = ArgumentCaptor.forClass(Integer.class);
		verify(discovery).confirmAll(Mockito.anyList(), hostCaptor.capture(), portCaptor.capture());
		assertThat(hostCaptor.getValue()).isEqualTo("10.0.0.5");
		assertThat(portCaptor.getValue()).isEqualTo(5050);
	}

	/**
	 * The local-classpath section answers two questions an agent asks when expression evaluation
	 * fails with "X cannot be resolved": (1) did the MCP server pick up the local project's
	 * classes/jars? and (2) which source(s) contributed? The test pins a stub provider with a known
	 * breakdown so we can assert the exact formatted output without shelling out to Maven.
	 */
	@Test
	@DisplayName("local-classpath section renders header + per-source breakdown + env state (unset)")
	void shouldRenderLocalClasspathSectionWithUnsetEnv() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final Path cwd = Path.of(System.getProperty("user.dir"));
		final LocalProjectClasspathProvider stub = new LocalProjectClasspathProvider(
			cwd,
			name -> null,
			(command, workingDirectory, timeoutSeconds) -> List.of(
				"/tmp/dep-a.jar", "/tmp/dep-b.jar", "/tmp/dep-c.jar"
			)
		);
		// Warm the breakdown cache so the diagnose path's non-blocking peek has something to render.
		// Production warming happens on the first expression-evaluation flow, not on the diagnose
		// path itself — see renderLocalClasspathBlock for why the diagnose call never blocks.
		stub.discoverWithBreakdown();
		final JDWPTools toolsWithStub = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			discovery, stub);

		final String result = toolsWithStub.jdwp_diagnose(null);

		assertThat(result).contains("Local project classpath");
		assertThat(result).contains("(read from cache; populated on first expression evaluation)");
		assertThat(result).contains("CWD: " + cwd + "\n");
		// The stub forces Maven to contribute 3 jars; filesystem and env may legitimately add
		// entries from the running build's tree but the maven count must be exactly 3.
		assertThat(result).containsPattern("Sources: env-override=0, filesystem=\\d+, maven=3");
		assertThat(result).containsPattern("Total local entries: \\d+");
		assertThat(result).contains("Override env: JDWP_EXTRA_CLASSPATH (unset)");
	}

	/**
	 * Codex starts the server in the plugin's install directory and reports the session directory
	 * with each tool call. The local-classpath block must show that directory, say where it came
	 * from, and not show the launch directory.
	 */
	@Test
	@DisplayName("local-classpath section shows the session directory the MCP client reported")
	void shouldShowClientReportedDirectory(@TempDir Path tmp) {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final Path launch = tmp.resolve("plugin-root");
		final ClientWorkspace workspace = new ClientWorkspace();
		workspace.observe(Map.of("codex/sandbox-state", Map.of("sandboxCwd", tmp.toUri().toString())));
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			launch, workspace, name -> null, (command, workingDirectory, timeoutSeconds) -> List.of());
		final JDWPTools toolsWithProvider = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			discovery, provider);

		final String result = toolsWithProvider.jdwp_diagnose(null);

		assertThat(result).contains("CWD: " + tmp.normalize() + " (session directory reported by the MCP client)\n");
		assertThat(result).doesNotContain("CWD: " + launch);
	}

	/**
	 * Complementary case: when {@code JDWP_EXTRA_CLASSPATH} is set in the process environment, the
	 * diagnose output must say so. We don't poke {@link System#getenv} — the stub provider takes a
	 * lookup function so we can flip the bit cleanly. The env contribution count must reflect the
	 * stub's payload.
	 */
	@Test
	@DisplayName("local-classpath section reflects JDWP_EXTRA_CLASSPATH when set")
	void shouldRenderLocalClasspathSectionWithSetEnv() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final Path cwd = Path.of(System.getProperty("user.dir"));
		final LocalProjectClasspathProvider stub = new LocalProjectClasspathProvider(
			cwd,
			name -> "JDWP_EXTRA_CLASSPATH".equals(name)
				? "/opt/extra1.jar" + java.io.File.pathSeparator + "/opt/extra2.jar"
				: null,
			(command, workingDirectory, timeoutSeconds) -> List.of()
		);
		// Warm the cache so the non-blocking peek in the diagnose path has data to render.
		stub.discoverWithBreakdown();
		final JDWPTools toolsWithStub = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			discovery, stub);

		final String result = toolsWithStub.jdwp_diagnose(null);

		assertThat(result).contains("Local project classpath");
		assertThat(result).containsPattern("Sources: env-override=2, filesystem=\\d+, maven=0");
		// Env state is read by the diagnose path from the same provider (or System.getenv) — when
		// the provider says the env contributed >0 entries, the marker must read "(set)".
		assertThat(result).contains("Override env: JDWP_EXTRA_CLASSPATH (set)");
	}

	/**
	 * The diagnose path must never block on a cold local-classpath cache. When the provider has
	 * never run discovery the renderer must call {@code peekCachedBreakdown()} (non-blocking),
	 * print a "not yet computed; will populate after first expression evaluation" hint, and return.
	 * Discovery happens organically when expression evaluation needs it.
	 */
	@Test
	@DisplayName("does not block on a cold local-classpath cache — renders a 'not yet computed' hint instead")
	void shouldRenderLocalClasspathSectionWithoutBlockingOnColdCache() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final Path cwd = Path.of(System.getProperty("user.dir"));
		// Slow provider — sleeps 2s inside the Maven runner. If diagnose blocks, the call exceeds
		// the 500ms budget; the fix path skips discovery entirely.
		final LocalProjectClasspathProvider slowStub = new LocalProjectClasspathProvider(
			cwd,
			name -> null,
			(command, workingDirectory, timeoutSeconds) -> {
				try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				return List.of("/m2/slow.jar");
			}
		);
		// The CWD is the project root (which has a real pom.xml), so the slow Maven runner WOULD be
		// triggered if diagnose ran a full discovery. The point of this test is that it doesn't:
		// the diagnose path peeks the cold cache and renders "not yet computed", never invoking
		// the runner. If the fast path regresses, the slow stub's 2s sleep blows the 500ms budget
		// below.
		final JDWPTools toolsWithSlowStub = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			discovery, slowStub);

		final long start = System.nanoTime();
		final String result = toolsWithSlowStub.jdwp_diagnose(null);
		final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		assertThat(result).contains("Local project classpath");
		assertThat(result).contains("not yet computed");
		assertThat(elapsedMs)
			.as("diagnose must not block on cold-cache Maven discovery")
			.isLessThan(500L);
	}

	/**
	 * When {@code JDWP_EXTRA_CLASSPATH} is set in the environment but parses to zero entries (all
	 * blank tokens), the diagnose line must read {@code (set, no entries)} — not {@code (unset)},
	 * which is indistinguishable from a truly absent env var. Three-way state:
	 * {@code (unset)} / {@code (set, no entries)} / {@code (set)}.
	 */
	@Test
	@DisplayName("renders '(set, no entries)' when JDWP_EXTRA_CLASSPATH is set but parses to zero entries")
	void shouldRenderSetNoEntriesWhenEnvIsSetButBlank() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final Path cwd = Path.of(System.getProperty("user.dir"));
		final LocalProjectClasspathProvider stub = new LocalProjectClasspathProvider(
			cwd,
			name -> "JDWP_EXTRA_CLASSPATH".equals(name) ? "" : null,
			(command, workingDirectory, timeoutSeconds) -> List.of()
		);
		// Warm the cache so the diagnose path can see the 0-entry breakdown and distinguish
		// "set but empty" from "unset" in its render.
		stub.discoverWithBreakdown();
		final JDWPTools toolsWithStub = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			discovery, stub);

		final String result = toolsWithStub.jdwp_diagnose(null);

		assertThat(result).contains("Override env: JDWP_EXTRA_CLASSPATH (set, no entries)");
		assertThat(result).doesNotContain("Override env: JDWP_EXTRA_CLASSPATH (unset)");
	}

	/**
	 * Cold-cache three-way env state. The warm-cache tests above exercise {@code renderEnvState}'s
	 * {@code breakdown != null} branch (entry count from the breakdown). This pins the COLD branch —
	 * where the renderer must answer from {@link LocalProjectClasspathProvider#envOverrideHasEntries()}
	 * by parsing the env var directly, WITHOUT triggering discovery. The cache is deliberately left
	 * cold (no {@code discoverWithBreakdown()} call), so the "not yet computed" hint must appear
	 * alongside the correct marker. Without this, the entire reason {@code envOverrideHasEntries()}
	 * exists in the diagnose path would be untested.
	 */
	@Test
	@DisplayName("cold cache: renders '(set)' from envOverrideHasEntries() without triggering discovery")
	void shouldRenderSetOnColdCacheWhenEnvHasEntries() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final Path cwd = Path.of(System.getProperty("user.dir"));
		final LocalProjectClasspathProvider stub = new LocalProjectClasspathProvider(
			cwd,
			name -> "JDWP_EXTRA_CLASSPATH".equals(name)
				? "/opt/extra1.jar" + java.io.File.pathSeparator + "/opt/extra2.jar"
				: null,
			(command, workingDirectory, timeoutSeconds) -> List.of()
		);
		// Cache intentionally NOT warmed — peekCachedBreakdown() returns null in the renderer.
		final JDWPTools toolsWithStub = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			discovery, stub);

		final String result = toolsWithStub.jdwp_diagnose(null);

		assertThat(result).contains("not yet computed");
		assertThat(result).contains("Override env: JDWP_EXTRA_CLASSPATH (set)");
	}

	@Test
	@DisplayName("cold cache: renders '(set, no entries)' from envOverrideHasEntries() for a blank env")
	void shouldRenderSetNoEntriesOnColdCacheWhenEnvIsBlank() {
		when(jdiService.getConnectionStatus()).thenReturn(new JDIConnectionService.ConnectionStatus(
			false, null, 0, null, null
		));
		final Path cwd = Path.of(System.getProperty("user.dir"));
		final LocalProjectClasspathProvider stub = new LocalProjectClasspathProvider(
			cwd,
			name -> "JDWP_EXTRA_CLASSPATH".equals(name) ? "   " : null,
			(command, workingDirectory, timeoutSeconds) -> List.of()
		);
		// Cache intentionally NOT warmed — exercises the cold envOverrideHasEntries() fallback.
		final JDWPTools toolsWithStub = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			discovery, stub);

		final String result = toolsWithStub.jdwp_diagnose(null);

		assertThat(result).contains("not yet computed");
		assertThat(result).contains("Override env: JDWP_EXTRA_CLASSPATH (set, no entries)");
		assertThat(result).doesNotContain("Override env: JDWP_EXTRA_CLASSPATH (unset)");
	}
}
