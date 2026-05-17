package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JDWPTools#jdwp_overview} — the unified read-only debug-state inspector
 * covering breakpoints, exception breakpoints, field breakpoints, logpoints, watchers, and
 * marked instances. The coverage focuses on:
 *
 * <ul>
 *   <li><b>Type filtering</b> — null/blank/"all" wildcard, explicit subsets, unknown tokens.</li>
 *   <li><b>Substring filtering</b> — case-insensitive match against label / class / expression.</li>
 *   <li><b>Rendering shape</b> — section headers, counts, pin / collected / log-only adornments.</li>
 * </ul>
 *
 * <p>This is the user-facing contract that an agent sees; renders are pinned literally so
 * downstream documentation / prompts that depend on the format don't drift silently.
 */
@DisplayName("jdwp_overview")
class JDWPToolsOverviewTest {

	private MarkedInstanceRegistry registry;
	private BreakpointTracker tracker;
	private WatcherManager watchers;
	private VirtualMachine vm;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		watchers = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory history = new EventHistory();
		registry = new MarkedInstanceRegistry();
		vm = mock(VirtualMachine.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watchers, evaluator, history,
			new EvaluationGuard(), new JvmDiscoveryService(), registry);
	}

	/**
	 * Builds an {@link ObjectReference} mock suitable for registering as a mark. The {@code vm}
	 * identity matches the test fixture so it would pass the stale-VM guard if it were exercised
	 * through the tool surface (this helper is used by tests that register directly on the registry
	 * to keep setup minimal).
	 */
	private ObjectReference ref(long id, String typeName) {
		final ObjectReference r = mock(ObjectReference.class);
		when(r.uniqueID()).thenReturn(id);
		when(r.isCollected()).thenReturn(false);
		when(r.virtualMachine()).thenReturn(vm);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(typeName);
		when(r.referenceType()).thenReturn(type);
		return r;
	}

	/**
	 * Registers a fully-mocked active line {@link BreakpointRequest} returning the synthetic ID
	 * assigned by the tracker. The class name and line number are wired so the overview renderer
	 * can format the location string.
	 */
	private int registerLineBreakpoint(String className, int line) {
		final BreakpointRequest req = mock(BreakpointRequest.class);
		final Location loc = mock(Location.class);
		final ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn(className);
		when(loc.declaringType()).thenReturn(declaringType);
		when(loc.lineNumber()).thenReturn(line);
		when(req.location()).thenReturn(loc);
		return tracker.registerBreakpoint(req);
	}

	@Nested
	@DisplayName("Default 'all types' selection")
	class AllTypes {

		@Test
		@DisplayName("null types → every section emitted in canonical order (marks first, then BPs, logpoints, exceptions, fields, watchers)")
		void shouldEmitEverySectionWhenTypesIsNull() {
			registry.mark("cart", ref(1L, "com.example.Cart"), false);
			registerLineBreakpoint("com.example.Service", 12);
			watchers.createWatcher("user", 1, "user.getEmail()");

			final String out = tools.jdwp_overview(null, null);

			assertThat(out)
				.startsWith("=== Overview ===")
				.contains("Marks (")
				.contains("Breakpoints (")
				.contains("Logpoints (")
				.contains("Exception breakpoints (")
				.contains("Field breakpoints (")
				.contains("Watchers (");
		}

		@Test
		@DisplayName("blank types → same as null, every section emitted")
		void shouldTreatBlankTypesAsAll() {
			final String out = tools.jdwp_overview("   ", null);

			assertThat(out)
				.contains("Marks (0):")
				.contains("Breakpoints (0):")
				.contains("Watchers (0):");
		}

		@Test
		@DisplayName("with no entries registered → '(no entries matched)' footer")
		void shouldReportEmptyOverviewWhenNothingRegistered() {
			final String out = tools.jdwp_overview(null, null);

			assertThat(out).contains("(no entries matched)");
		}
	}

	@Nested
	@DisplayName("Selective type subsets")
	class SelectiveTypes {

		@Test
		@DisplayName("types=\"mark\" → only the mark section is rendered")
		void shouldRenderOnlyMarkSectionWhenRequested() {
			registry.mark("cart", ref(1L, "com.example.Cart"), true);
			registerLineBreakpoint("com.example.Service", 12);
			watchers.createWatcher("user", 1, "user.getEmail()");

			final String out = tools.jdwp_overview("mark", null);

			assertThat(out)
				.contains("Marks (1)")
				.contains("$cart")
				.doesNotContain("Breakpoints (")
				.doesNotContain("Watchers (");
		}

		@Test
		@DisplayName("types=\"mark,watcher\" → both sections rendered, none of the others")
		void shouldRenderTwoExplicitSubsets() {
			registry.mark("cart", ref(1L, "com.example.Cart"), false);
			watchers.createWatcher("user", 1, "user.getEmail()");
			registerLineBreakpoint("com.example.Service", 12);

			final String out = tools.jdwp_overview("mark,watcher", null);

			assertThat(out)
				.contains("Marks (1)")
				.contains("Watchers (1)")
				.doesNotContain("Breakpoints (")
				.doesNotContain("Logpoints (")
				.doesNotContain("Exception breakpoints (")
				.doesNotContain("Field breakpoints (");
		}

		@Test
		@DisplayName("types=\"all\" → wildcard, every section rendered")
		void shouldHonorAllWildcard() {
			final String out = tools.jdwp_overview("all", null);

			assertThat(out)
				.contains("Marks (0):")
				.contains("Breakpoints (0):")
				.contains("Logpoints (0):")
				.contains("Watchers (0):");
		}

		@Test
		@DisplayName("unknown type token → [ERROR] surfacing the supported list")
		void shouldRejectUnknownTypeToken() {
			final String out = tools.jdwp_overview("bogus", null);

			assertThat(out)
				.startsWith("[ERROR]")
				.contains("bogus")
				.contains("Supported");
		}

		@Test
		@DisplayName("unknown token before 'all' → still rejected, no wildcard short-circuit")
		void shouldRejectUnknownTokenWhenAllIsLast() {
			// The 'all' wildcard must not short-circuit token validation: every token in the
			// comma-separated list has to be a known type, otherwise the agent could pass
			// "bogus,all" and silently get the full overview while their typo goes unnoticed.
			final String out = tools.jdwp_overview("bogus,all", null);

			assertThat(out)
				.startsWith("[ERROR]")
				.contains("bogus")
				.contains("Supported");
		}

		@Test
		@DisplayName("unknown token after 'all' → still rejected, validation is symmetric")
		void shouldRejectUnknownTokenWhenAllIsFirst() {
			// Mirror of the previous test for the reverse ordering: "all,bogus" must produce the
			// same error as "bogus,all". The validator must inspect every token before honouring
			// the wildcard.
			final String out = tools.jdwp_overview("all,bogus", null);

			assertThat(out)
				.startsWith("[ERROR]")
				.contains("bogus")
				.contains("Supported");
		}
	}

	@Nested
	@DisplayName("Substring filter")
	class Filter {

		@Test
		@DisplayName("filter echoed in header line")
		void shouldEchoFilterInHeader() {
			final String out = tools.jdwp_overview(null, "Cart");

			assertThat(out).contains("(filter: 'Cart')");
		}

		@Test
		@DisplayName("filter matches mark label case-insensitively")
		void shouldMatchMarkLabelCaseInsensitively() {
			registry.mark("shoppingCart", ref(1L, "com.example.X"), false);
			registry.mark("user", ref(2L, "com.example.Y"), false);

			final String out = tools.jdwp_overview("mark", "CART");

			assertThat(out)
				.contains("$shoppingCart")
				.doesNotContain("$user");
		}

		@Test
		@DisplayName("filter matches mark type name case-insensitively")
		void shouldMatchMarkTypeName() {
			registry.mark("first", ref(1L, "com.example.Order"), false);
			registry.mark("second", ref(2L, "com.example.User"), false);

			final String out = tools.jdwp_overview("mark", "order");

			assertThat(out)
				.contains("$first")
				.doesNotContain("$second");
		}

		@Test
		@DisplayName("filter applies independently per section — count reflects only matches")
		void shouldShowMatchedCountOverTotal() {
			registry.mark("apple", ref(1L, "com.example.X"), false);
			registry.mark("banana", ref(2L, "com.example.X"), false);
			registry.mark("apricot", ref(3L, "com.example.X"), false);

			final String out = tools.jdwp_overview("mark", "ap");

			// Two of three labels start with 'ap' (apple, apricot) — banana is filtered out.
			assertThat(out)
				.contains("Marks (2 of 3)")
				.contains("$apple")
				.contains("$apricot")
				.doesNotContain("$banana");
		}

		@Test
		@DisplayName("filter matches nothing → '(no entries matched)' footer")
		void shouldReportNoMatchesWhenFilterExcludesAll() {
			registry.mark("alpha", ref(1L, "com.example.X"), false);

			final String out = tools.jdwp_overview("mark", "zzz-nope");

			assertThat(out).contains("(no entries matched)");
		}
	}

	@Nested
	@DisplayName("Rendering shapes")
	class RenderingShapes {

		@Test
		@DisplayName("pinned mark → [pinned] adornment")
		void shouldRenderPinnedAdornment() {
			registry.mark("cart", ref(1L, "com.example.Cart"), true);

			final String out = tools.jdwp_overview("mark", null);

			assertThat(out).contains("$cart -> Object#1 (com.example.Cart) [pinned]");
		}

		@Test
		@DisplayName("unpinned mark → [unpinned] adornment")
		void shouldRenderUnpinnedAdornment() {
			registry.mark("cart", ref(1L, "com.example.Cart"), false);

			final String out = tools.jdwp_overview("mark", null);

			assertThat(out).contains("$cart -> Object#1 (com.example.Cart) [unpinned]");
		}

		@Test
		@DisplayName("collected mark → ', collected' suffix on the adornment")
		void shouldRenderCollectedSuffix() {
			final ObjectReference r = ref(7L, "com.example.D");
			registry.mark("dead", r, false);
			// Simulate the underlying object dying after mark time.
			when(r.isCollected()).thenReturn(true);

			final String out = tools.jdwp_overview("mark", null);

			assertThat(out).contains("$dead -> Object#7 (com.example.D) [unpinned, collected]");
		}

		@Test
		@DisplayName("marks sorted alphabetically by label")
		void shouldRenderMarksAlphabetically() {
			registry.mark("zulu", ref(1L, "T"), false);
			registry.mark("alpha", ref(2L, "T"), false);
			registry.mark("mike", ref(3L, "T"), false);

			final String out = tools.jdwp_overview("mark", null);

			final int alpha = out.indexOf("$alpha");
			final int mike = out.indexOf("$mike");
			final int zulu = out.indexOf("$zulu");
			assertThat(alpha).isLessThan(mike);
			assertThat(mike).isLessThan(zulu);
		}

		@Test
		@DisplayName("breakpoint section renders #id, class:line, and condition tag")
		void shouldRenderBreakpointWithCondition() {
			final int id = registerLineBreakpoint("com.example.Service", 42);
			tracker.setCondition(id, "x > 0");

			final String out = tools.jdwp_overview("breakpoint", null);

			assertThat(out)
				.contains("#" + id)
				.contains("com.example.Service:42")
				.contains("cond=\"x > 0\"");
		}

		@Test
		@DisplayName("logpoint section separates entries that carry an expression from plain BPs")
		void shouldSeparateLogpointsFromBreakpoints() {
			final int plainId = registerLineBreakpoint("com.example.A", 1);
			final int logId = registerLineBreakpoint("com.example.B", 2);
			tracker.setLogpointExpression(logId, "user.getEmail()");

			final String overview = tools.jdwp_overview(null, null);

			assertThat(overview)
				.contains("Breakpoints (1):")
				.contains("Logpoints (1):")
				.contains("#" + plainId + "  com.example.A:1")
				.contains("#" + logId + "  com.example.B:2")
				.contains("expr=\"user.getEmail()\"");
		}

		@Test
		@DisplayName("watcher row carries id-prefix, label, BP id, and expression")
		void shouldRenderWatcherRow() {
			final String watcherId = watchers.createWatcher("user.email", 11, "user.getEmail()");

			final String out = tools.jdwp_overview("watcher", null);

			assertThat(out)
				.contains("[" + watcherId.substring(0, 8) + "]")
				.contains("user.email")
				.contains("on BP #11")
				.contains("\"user.getEmail()\"");
		}

		@Test
		@DisplayName("exception breakpoint shows log-only adornment when registered as logpoint")
		void shouldShowLogOnlyAdornmentForExceptionLogpoint() {
			final ExceptionRequest req = mock(ExceptionRequest.class);
			final BreakpointTracker.ExceptionBreakpointSpec spec =
				BreakpointTracker.ExceptionBreakpointSpec.logOnly(
					"java.lang.IllegalStateException", true, true, "$exception.getMessage()");
			final int id = tracker.registerExceptionBreakpoint(req, spec);

			final String out = tools.jdwp_overview("exception_breakpoint", null);

			assertThat(out)
				.contains("#" + id)
				.contains("java.lang.IllegalStateException")
				.contains("[log-only]")
				.contains("expr=\"$exception.getMessage()\"");
		}

		@Test
		@DisplayName("field breakpoint shows className.fieldName, mode, and condition")
		void shouldRenderFieldBreakpointRow() {
			final BreakpointTracker.FieldBreakpointSpec spec =
				BreakpointTracker.FieldBreakpointSpec.suspending(
					"com.example.Order", "total",
					BreakpointTracker.FieldWatchMode.MODIFICATION,
					null, null, null);
			final ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
			final int id = tracker.registerFieldBreakpoint(spec, null, modReq);
			// The overview renders condition text via tracker.getCondition(id), not via the spec's
			// own condition field — those are kept separate by design so the per-BP metadata map
			// stays the single source of truth that the listener consults at fire time.
			tracker.setCondition(id, "$newValue > 100");

			final String out = tools.jdwp_overview("field_breakpoint", null);

			assertThat(out)
				.contains("#" + id)
				.contains("com.example.Order.total")
				.contains("mode=MODIFICATION")
				.contains("cond=\"$newValue > 100\"");
		}
	}
}
