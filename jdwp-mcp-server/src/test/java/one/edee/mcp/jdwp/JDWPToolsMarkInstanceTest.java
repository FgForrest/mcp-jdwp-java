package one.edee.mcp.jdwp;

import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkInfo;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the mark-instance MCP surface: {@link JDWPTools#jdwp_mark_instance},
 * {@link JDWPTools#jdwp_unmark_instance}, and {@link JDWPTools#jdwp_rename_mark}.
 *
 * <p>The tools layer is a thin orchestration around {@link MarkedInstanceRegistry}; the registry
 * itself is exercised in {@code MarkedInstanceRegistryTest}. The coverage here focuses on what the
 * tools layer ADDS on top of the registry: cache miss handling, stale-VM detection, formatted
 * happy/error responses, and propagation of registry-thrown exceptions to the user-facing
 * {@code [ERROR] ...} format.
 */
@DisplayName("jdwp_mark_instance / jdwp_unmark_instance / jdwp_rename_mark")
class JDWPToolsMarkInstanceTest {

	private JDIConnectionService jdiService;
	private MarkedInstanceRegistry registry;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker tracker = mock(BreakpointTracker.class);
		final WatcherManager watchers = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory history = new EventHistory();
		registry = new MarkedInstanceRegistry();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watchers, evaluator, history,
			new EvaluationGuard(), new JvmDiscoveryService(), registry);
		vm = mock(VirtualMachine.class);
	}

	/**
	 * Builds a live (not-collected) {@link ObjectReference} whose {@code virtualMachine()} matches
	 * the test's {@link #vm} mock — required so {@code staleVmHintIfMismatched} does not flag the
	 * reference as belonging to a previous VM session.
	 */
	private ObjectReference liveRef(long id, String typeName) {
		final ObjectReference ref = mock(ObjectReference.class);
		when(ref.uniqueID()).thenReturn(id);
		when(ref.isCollected()).thenReturn(false);
		when(ref.virtualMachine()).thenReturn(vm);
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(typeName);
		when(ref.referenceType()).thenReturn(type);
		return ref;
	}

	@Nested
	@DisplayName("jdwp_mark_instance")
	class MarkInstance {

		@Test
		@DisplayName("happy path with default pin (null param) → registers, pins, and reports [pinned]")
		void shouldRegisterAndPinByDefault() throws Exception {
			final ObjectReference ref = liveRef(42L, "com.example.Cart");
			when(jdiService.getCachedObject(42L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);

			final String response = tools.jdwp_mark_instance("cart", 42L, null);

			assertThat(response)
				.contains("Marked Object#42")
				.contains("com.example.Cart")
				.contains("$cart")
				.contains("[pinned]");
			verify(ref).disableCollection();
			final MarkInfo info = registry.get("cart");
			assertThat(info).isNotNull();
			assertThat(info.pinned()).isTrue();
			assertThat(info.objectId()).isEqualTo(42L);
		}

		@Test
		@DisplayName("explicit pin=true → registers and pins")
		void shouldRegisterAndPinWhenPinTrue() throws Exception {
			final ObjectReference ref = liveRef(7L, "com.example.S");
			when(jdiService.getCachedObject(7L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);

			final String response = tools.jdwp_mark_instance("session", 7L, true);

			assertThat(response).contains("[pinned]");
			verify(ref).disableCollection();
			assertThat(registry.get("session")).isNotNull();
		}

		@Test
		@DisplayName("explicit pin=false → registers without pinning, response carries the GC warning")
		void shouldRegisterWithoutPinningWhenPinFalse() throws Exception {
			final ObjectReference ref = liveRef(8L, "com.example.U");
			when(jdiService.getCachedObject(8L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);

			final String response = tools.jdwp_mark_instance("user", 8L, false);

			assertThat(response)
				.contains("Marked Object#8")
				.contains("[unpinned")
				.contains("may be GC'd");
			verify(ref, never()).disableCollection();
			final MarkInfo info = registry.get("user");
			assertThat(info).isNotNull();
			assertThat(info.pinned()).isFalse();
		}

		@Test
		@DisplayName("cache miss → [ERROR] Object #N not found, suggests jdwp_get_locals")
		void shouldReportCacheMiss() {
			when(jdiService.getCachedObject(999L)).thenReturn(null);

			final String response = tools.jdwp_mark_instance("ghost", 999L, true);

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("Object #999")
				.contains("not found in cache")
				.contains("jdwp_get_locals");
			assertThat(registry.get("ghost")).isNull();
		}

		@Test
		@DisplayName("stale-VM reference → [ERROR] hint pointing at jdwp_get_locals, mark NOT created")
		void shouldRejectStaleVmReference() throws Exception {
			final ObjectReference ref = mock(ObjectReference.class);
			when(ref.uniqueID()).thenReturn(50L);
			when(ref.isCollected()).thenReturn(false);
			final VirtualMachine otherVm = mock(VirtualMachine.class);
			when(ref.virtualMachine()).thenReturn(otherVm);
			when(jdiService.getCachedObject(50L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);

			final String response = tools.jdwp_mark_instance("stale", 50L, true);

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("Object #50")
				.contains("previous VM session");
			verify(ref, never()).disableCollection();
			assertThat(registry.get("stale")).isNull();
		}

		@Test
		@DisplayName("invalid label (digit-leading) → [ERROR] from registry validator, no JDI side-effect")
		void shouldRejectInvalidLabel() throws Exception {
			final ObjectReference ref = liveRef(1L, "com.example.X");
			when(jdiService.getCachedObject(1L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);

			final String response = tools.jdwp_mark_instance("42bad", 1L, true);

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("not a valid Java identifier");
			verify(ref, never()).disableCollection();
		}

		@Test
		@DisplayName("reserved binding name (e.g. 'exception') → [ERROR] from validator")
		void shouldRejectReservedBindingLabel() throws Exception {
			final ObjectReference ref = liveRef(1L, "com.example.X");
			when(jdiService.getCachedObject(1L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);

			final String response = tools.jdwp_mark_instance("exception", 1L, true);

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("reserved");
		}

		@Test
		@DisplayName("label collision with an existing mark → [ERROR] 'already exists', original mark preserved")
		void shouldRejectLabelCollision() throws Exception {
			final ObjectReference first = liveRef(1L, "com.example.X");
			final ObjectReference second = liveRef(2L, "com.example.X");
			when(jdiService.getCachedObject(1L)).thenReturn(first);
			when(jdiService.getCachedObject(2L)).thenReturn(second);
			when(jdiService.getVM()).thenReturn(vm);

			tools.jdwp_mark_instance("same", 1L, false);
			final String response = tools.jdwp_mark_instance("same", 2L, false);

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("already exists");
			// The original mark must still point at the first object.
			final MarkInfo info = registry.get("same");
			assertThat(info).isNotNull();
			assertThat(info.objectId()).isEqualTo(1L);
		}

		@Test
		@DisplayName("already-collected reference → [ERROR] surfacing ObjectCollectedException message")
		void shouldRejectCollectedReference() throws Exception {
			final ObjectReference ref = mock(ObjectReference.class);
			when(ref.uniqueID()).thenReturn(99L);
			when(ref.isCollected()).thenReturn(true);
			when(ref.virtualMachine()).thenReturn(vm);
			final ReferenceType type = mock(ReferenceType.class);
			when(type.name()).thenReturn("com.example.D");
			when(ref.referenceType()).thenReturn(type);
			when(jdiService.getCachedObject(99L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);

			final String response = tools.jdwp_mark_instance("dead", 99L, true);

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("already been collected");
			assertThat(registry.get("dead")).isNull();
		}
	}

	@Nested
	@DisplayName("jdwp_unmark_instance")
	class UnmarkInstance {

		@Test
		@DisplayName("happy path → removes the mark and releases the pin, returns ✓ Removed")
		void shouldRemoveExistingMark() throws Exception {
			final ObjectReference ref = liveRef(10L, "com.example.X");
			when(jdiService.getCachedObject(10L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);
			tools.jdwp_mark_instance("x", 10L, true);

			final String response = tools.jdwp_unmark_instance("x");

			assertThat(response).contains("Removed mark $x");
			verify(ref).enableCollection();
			assertThat(registry.get("x")).isNull();
		}

		@Test
		@DisplayName("unknown label → [ERROR] suggests jdwp_overview(types=\"mark\")")
		void shouldReportUnknownLabel() {
			final String response = tools.jdwp_unmark_instance("ghost");

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("No mark named 'ghost'")
				.contains("jdwp_overview");
		}
	}

	@Nested
	@DisplayName("jdwp_rename_mark")
	class RenameMark {

		@Test
		@DisplayName("happy path → ✓ Renamed $old -> $new, registry slot moves")
		void shouldRenameExistingMark() throws Exception {
			final ObjectReference ref = liveRef(3L, "com.example.X");
			when(jdiService.getCachedObject(3L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);
			tools.jdwp_mark_instance("old", 3L, true);

			final String response = tools.jdwp_rename_mark("old", "fresh");

			assertThat(response).contains("Renamed $old -> $fresh");
			assertThat(registry.get("old")).isNull();
			assertThat(registry.get("fresh")).isNotNull();
		}

		@Test
		@DisplayName("unknown source label → [ERROR] No mark named '...'")
		void shouldRejectUnknownSource() {
			final String response = tools.jdwp_rename_mark("ghost", "anything");

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("No mark");
		}

		@Test
		@DisplayName("destination collides with another mark → [ERROR] 'already exists'")
		void shouldRejectCollision() throws Exception {
			final ObjectReference a = liveRef(1L, "T");
			final ObjectReference b = liveRef(2L, "T");
			when(jdiService.getCachedObject(1L)).thenReturn(a);
			when(jdiService.getCachedObject(2L)).thenReturn(b);
			when(jdiService.getVM()).thenReturn(vm);
			tools.jdwp_mark_instance("a", 1L, false);
			tools.jdwp_mark_instance("b", 2L, false);

			final String response = tools.jdwp_rename_mark("a", "b");

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("already exists");
			// Both original marks must still exist.
			assertThat(registry.get("a")).isNotNull();
			assertThat(registry.get("b")).isNotNull();
		}

		@Test
		@DisplayName("invalid destination label → [ERROR] from validator")
		void shouldRejectInvalidDestination() throws Exception {
			final ObjectReference ref = liveRef(1L, "T");
			when(jdiService.getCachedObject(1L)).thenReturn(ref);
			when(jdiService.getVM()).thenReturn(vm);
			tools.jdwp_mark_instance("good", 1L, false);

			final String response = tools.jdwp_rename_mark("good", "1bad");

			assertThat(response)
				.startsWith("[ERROR]")
				.contains("not a valid Java identifier");
			// Original mark must still be intact.
			assertThat(registry.get("good")).isNotNull();
		}
	}

}
