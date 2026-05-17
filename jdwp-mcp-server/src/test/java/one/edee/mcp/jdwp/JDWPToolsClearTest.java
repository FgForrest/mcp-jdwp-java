package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
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
 * Tests for {@link JDWPTools#jdwp_clear} — the unified by-type-and-filter delete tool covering
 * breakpoints, exception breakpoints, field breakpoints, logpoints, watchers, and marked instances.
 *
 * <p>Two properties pinned here:
 * <ul>
 *   <li><b>{@code types} is required</b> — an empty / null arg fails fast with a hint about
 *       passing {@code 'all'} on purpose, so a misuse cannot wipe everything by accident.</li>
 *   <li><b>VM-disconnected hint</b> — when no VM is connected and a BP-type clear was requested,
 *       the response carries a note that BP removal is server-local only.</li>
 * </ul>
 */
@DisplayName("jdwp_clear")
class JDWPToolsClearTest {

	private JDIConnectionService jdiService;
	private MarkedInstanceRegistry registry;
	private BreakpointTracker tracker;
	private WatcherManager watchers;
	private VirtualMachine vm;
	private JDWPTools tools;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		watchers = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory history = new EventHistory();
		registry = new MarkedInstanceRegistry();
		vm = mock(VirtualMachine.class);
		// Default: VM is live so BP-type clears do not trigger the disconnect hint.
		when(jdiService.getVM()).thenReturn(vm);
		final EventRequestManager erm = mock(EventRequestManager.class);
		when(vm.eventRequestManager()).thenReturn(erm);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watchers, evaluator, history,
			new EvaluationGuard(), new JvmDiscoveryService(), registry);
	}

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

	private int registerLineBreakpoint(String className, int line) {
		final BreakpointRequest req = mock(BreakpointRequest.class);
		final Location loc = mock(Location.class);
		final ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn(className);
		when(loc.declaringType()).thenReturn(declaringType);
		when(loc.lineNumber()).thenReturn(line);
		when(req.location()).thenReturn(loc);
		when(req.virtualMachine()).thenReturn(vm);
		return tracker.registerBreakpoint(req);
	}

	@Nested
	@DisplayName("Required 'types' argument")
	class RequiredTypes {

		@Test
		@DisplayName("null types → [ERROR] with 'pass all to clear everything' hint")
		void shouldRejectNullTypes() {
			final String out = tools.jdwp_clear(null, null);

			assertThat(out)
				.startsWith("[ERROR]")
				.contains("'types' is required")
				.contains("'all'");
		}

		@Test
		@DisplayName("blank types → [ERROR] same as null")
		void shouldRejectBlankTypes() {
			final String out = tools.jdwp_clear("   ", null);

			assertThat(out)
				.startsWith("[ERROR]")
				.contains("'types' is required");
		}

		@Test
		@DisplayName("unknown type token → [ERROR] from validator")
		void shouldRejectUnknownTypeToken() {
			final String out = tools.jdwp_clear("nonsense", null);

			assertThat(out)
				.startsWith("[ERROR]")
				.contains("nonsense");
		}
	}

	@Nested
	@DisplayName("Happy path clears")
	class HappyPathClears {

		@Test
		@DisplayName("types=\"mark\" → unmarks every mark, returns 'Total cleared: N'")
		void shouldClearAllMarks() {
			registry.mark("a", ref(1L, "T"), true);
			registry.mark("b", ref(2L, "T"), false);

			final String out = tools.jdwp_clear("mark", null);

			assertThat(out)
				.contains("Marks: 2 matches")
				.contains("removed $a")
				.contains("removed $b")
				.contains("Total cleared: 2");
			assertThat(registry.list()).isEmpty();
		}

		@Test
		@DisplayName("types=\"mark\" with filter → clears only matching labels, leaves others")
		void shouldClearMarksByFilter() {
			registry.mark("apple", ref(1L, "T"), false);
			registry.mark("banana", ref(2L, "T"), false);
			registry.mark("apricot", ref(3L, "T"), false);

			final String out = tools.jdwp_clear("mark", "ap");

			assertThat(out)
				.contains("Marks: 2 matches")
				.contains("removed $apple")
				.contains("removed $apricot")
				.doesNotContain("removed $banana");
			assertThat(registry.get("banana")).isNotNull();
			assertThat(registry.get("apple")).isNull();
			assertThat(registry.get("apricot")).isNull();
		}

		@Test
		@DisplayName("types=\"watcher\" → deletes every watcher")
		void shouldClearAllWatchers() {
			watchers.createWatcher("user.email", 1, "user.getEmail()");
			watchers.createWatcher("cart.total", 2, "cart.getTotal()");

			final String out = tools.jdwp_clear("watcher", null);

			assertThat(out)
				.contains("Watchers: 2 matches")
				.contains("Total cleared: 2");
			assertThat(watchers.getAllWatchers()).isEmpty();
		}

		@Test
		@DisplayName("types=\"breakpoint\" → removes line BPs but leaves logpoints intact")
		void shouldClearOnlyPlainBreakpoints() {
			final int plainId = registerLineBreakpoint("com.example.A", 1);
			final int logId = registerLineBreakpoint("com.example.B", 2);
			tracker.setLogpointExpression(logId, "user.getEmail()");

			final String out = tools.jdwp_clear("breakpoint", null);

			assertThat(out).contains("Breakpoints: 1 match");
			assertThat(tracker.getBreakpoint(plainId)).isNull();
			assertThat(tracker.getBreakpoint(logId)).isNotNull();
		}

		@Test
		@DisplayName("types=\"logpoint\" → removes only logpoints, leaves plain BPs intact")
		void shouldClearOnlyLogpoints() {
			final int plainId = registerLineBreakpoint("com.example.A", 1);
			final int logId = registerLineBreakpoint("com.example.B", 2);
			tracker.setLogpointExpression(logId, "user.getEmail()");

			final String out = tools.jdwp_clear("logpoint", null);

			assertThat(out).contains("Logpoints: 1 match");
			assertThat(tracker.getBreakpoint(plainId)).isNotNull();
			assertThat(tracker.getBreakpoint(logId)).isNull();
		}

		@Test
		@DisplayName("types=\"breakpoint,logpoint\" → combined header, both kinds cleared")
		void shouldClearBothBreakpointsAndLogpointsTogether() {
			final int plainId = registerLineBreakpoint("com.example.A", 1);
			final int logId = registerLineBreakpoint("com.example.B", 2);
			tracker.setLogpointExpression(logId, "user.getEmail()");

			final String out = tools.jdwp_clear("breakpoint,logpoint", null);

			assertThat(out).contains("Breakpoints + logpoints: 2 matches");
			assertThat(tracker.getBreakpoint(plainId)).isNull();
			assertThat(tracker.getBreakpoint(logId)).isNull();
		}

		@Test
		@DisplayName("types=\"exception_breakpoint\" → removes exception BP")
		void shouldClearExceptionBreakpoints() {
			final ExceptionRequest req = mock(ExceptionRequest.class);
			when(req.virtualMachine()).thenReturn(vm);
			final BreakpointTracker.ExceptionBreakpointSpec spec =
				BreakpointTracker.ExceptionBreakpointSpec.suspending(
					"java.lang.NullPointerException", true, true);
			final int id = tracker.registerExceptionBreakpoint(req, spec);

			final String out = tools.jdwp_clear("exception_breakpoint", null);

			assertThat(out).contains("Exception breakpoints: 1 match");
			assertThat(tracker.getAllExceptionBreakpoints()).doesNotContainKey(id);
		}

		@Test
		@DisplayName("types=\"field_breakpoint\" → removes field BP")
		void shouldClearFieldBreakpoints() {
			final ModificationWatchpointRequest modReq = mock(ModificationWatchpointRequest.class);
			when(modReq.virtualMachine()).thenReturn(vm);
			final BreakpointTracker.FieldBreakpointSpec spec =
				BreakpointTracker.FieldBreakpointSpec.suspending(
					"com.example.Order", "total",
					BreakpointTracker.FieldWatchMode.MODIFICATION,
					null, null, null);
			final int id = tracker.registerFieldBreakpoint(spec, null, modReq);

			final String out = tools.jdwp_clear("field_breakpoint", null);

			assertThat(out).contains("Field breakpoints: 1 match");
			assertThat(tracker.getAllFieldBreakpoints()).doesNotContainKey(id);
		}
	}

	@Nested
	@DisplayName("Empty results")
	class EmptyResults {

		@Test
		@DisplayName("matches nothing → '(nothing matched)' footer")
		void shouldReportNothingMatched() {
			final String out = tools.jdwp_clear("mark", "no-such-label");

			assertThat(out).contains("(nothing matched)");
		}
	}

	@Nested
	@DisplayName("VM-connectivity hint")
	class VmConnectivityHint {

		@Test
		@DisplayName("VM disconnected + BP-type requested → 'VM not connected' note appended")
		void shouldAppendVmDisconnectedNoteForBpTypes() throws Exception {
			when(jdiService.getVM()).thenThrow(new RuntimeException("not connected"));

			final String out = tools.jdwp_clear("breakpoint", null);

			assertThat(out)
				.contains("(note: VM not connected")
				.contains("server-local only");
		}

		@Test
		@DisplayName("VM disconnected + only mark/watcher types → NO VM hint (server-local clears work without VM)")
		void shouldNotAppendVmHintForServerLocalTypesOnly() throws Exception {
			when(jdiService.getVM()).thenThrow(new RuntimeException("not connected"));

			final String out = tools.jdwp_clear("mark,watcher", null);

			assertThat(out).doesNotContain("VM not connected");
		}
	}
}
