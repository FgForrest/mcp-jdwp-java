package one.edee.mcp.jdwp;

import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tool-surface contract of {@code jdwp_set_fast_logpoint}: the consent gate arms nothing and
 * touches no VM, argument validation happens before any registration, and the shared tools route
 * fast logpoints correctly — {@code jdwp_get_events} drains the rings first,
 * {@code jdwp_clear_breakpoint} restores bytecode, {@code jdwp_reset} disarms everything, and
 * {@code jdwp_overview} lists them.
 */
@DisplayName("jdwp_set_fast_logpoint tool surface")
class JDWPToolsFastLogpointTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker tracker;
	private EventHistory history;
	private FastLogpointService fastLogpoints;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		history = new EventHistory();
		fastLogpoints = mock(FastLogpointService.class);
		tools = JDWPToolsTestSupport.newTools(jdiService, tracker, history, fastLogpoints);
	}

	@Test
	@DisplayName("missing or false userApproved returns the risk briefing and arms nothing")
	void consentGate() throws Exception {
		when(jdiService.getConnectionStatus()).thenReturn(
			new JDIConnectionService.ConnectionStatus(true, "localhost", 5005, Instant.now(), null));

		for (Boolean approval : new Boolean[]{null, false}) {
			final String reply = tools.jdwp_set_fast_logpoint("com.example.Cart", 42, "total", "total < 0",
				approval, null, null);
			assertThat(reply)
				.startsWith("[CONSENT REQUIRED] Nothing was armed.")
				.contains("word for word")
				.contains("userApproved=true")
				.contains("Never infer consent")
				.contains("com.example.Cart:42")
				.contains("condition: total < 0")
				.contains("localhost:5005")
				.contains("rewrites the class's bytecode")
				.contains("JaCoCo");
		}
		verify(jdiService, never()).getVM();
		verifyNoInteractions(fastLogpoints);
	}

	@Test
	@DisplayName("the briefing still renders when nothing is attached")
	void briefingWithoutConnection() {
		when(jdiService.getConnectionStatus()).thenReturn(
			new JDIConnectionService.ConnectionStatus(false, null, 0, null, null));
		assertThat(tools.jdwp_set_fast_logpoint("com.example.Cart", 42, "total", null, null, null, null))
			.contains("the VM attached when the logpoint is set");
	}

	@Test
	@DisplayName("an invalid bufferSize is rejected before the VM is touched")
	void rejectsBadBufferSize() throws Exception {
		for (int size : new int[]{100, 8, 131072}) {
			assertThat(tools.jdwp_set_fast_logpoint("com.example.Cart", 42, "total", null, true, size, null))
				.startsWith("Error: bufferSize must be a power of two between 16 and 65536");
		}
		verify(jdiService, never()).getVM();
		verifyNoInteractions(fastLogpoints);
	}

	@Test
	@DisplayName("approval does not carry over to a JVM the call re-attached to")
	void approvalDoesNotFollowAReconnect() throws Exception {
		final VirtualMachine replacement = mock(VirtualMachine.class);
		when(jdiService.getRawVM()).thenReturn(mock(VirtualMachine.class));
		when(jdiService.getVM()).thenReturn(replacement);
		when(jdiService.getConnectionStatus()).thenReturn(
			new JDIConnectionService.ConnectionStatus(true, "localhost", 5005, Instant.now(), null));

		assertThat(tools.jdwp_set_fast_logpoint("com.example.Cart", 42, "total", null, true, null, null))
			.startsWith("[CONSENT REQUIRED AGAIN] Nothing was armed")
			.contains("[CONSENT REQUIRED] Nothing was armed.");
		verify(fastLogpoints, never()).register(any(), any(), anyString(), any(), anyInt());
		verifyNoInteractions(replacement);
	}

	@Test
	@DisplayName("a VM without class redefinition is refused")
	void requiresRedefinition() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getRawVM()).thenReturn(vm);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.canRedefineClasses()).thenReturn(false);
		assertThat(tools.jdwp_set_fast_logpoint("com.example.Cart", 42, "total", null, true, null, null))
			.contains("canRedefineClasses() is false");
		verify(fastLogpoints, never()).register(any(), any(), anyString(), any(), anyInt());
	}

	@Test
	@DisplayName("an unloaded class is refused with a hint")
	void requiresLoadedClass() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getRawVM()).thenReturn(vm);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.canRedefineClasses()).thenReturn(true);
		when(vm.canGetBytecodes()).thenReturn(true);
		when(vm.canGetConstantPool()).thenReturn(true);
		assertThat(tools.jdwp_set_fast_logpoint("com.example.Cart", 42, "total", null, true, null, null))
			.contains("is not loaded").contains("forceLoad=true");
		verify(fastLogpoints, never()).register(any(), any(), anyString(), any(), anyInt());
	}

	@Test
	@DisplayName("a VM without constant pools is refused: the probe wiring could not be verified")
	void requiresConstantPool() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getRawVM()).thenReturn(vm);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.canRedefineClasses()).thenReturn(true);
		when(vm.canGetBytecodes()).thenReturn(true);
		assertThat(tools.jdwp_set_fast_logpoint("com.example.Cart", 42, "total", null, true, null, null))
			.contains("canGetConstantPool()");
		verify(fastLogpoints, never()).register(any(), any(), anyString(), any(), anyInt());
	}

	@Test
	@DisplayName("a class still carrying an earlier session's fast logpoints is refused with the next step")
	void refusesClassWithLeftovers() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		final ReferenceType type = mock(ReferenceType.class);
		when(jdiService.getRawVM()).thenReturn(vm);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.canRedefineClasses()).thenReturn(true);
		when(vm.canGetBytecodes()).thenReturn(true);
		when(vm.canGetConstantPool()).thenReturn(true);
		when(jdiService.findLoadedClass("com.example.Cart")).thenReturn(type);
		when(type.name()).thenReturn("com.example.Cart");
		when(type.classLoader()).thenReturn(mock(ClassLoaderReference.class));
		when(fastLogpoints.leftoversIn(type)).thenReturn("refusing to arm: com.example.Cart still carries ...");
		assertThat(tools.jdwp_set_fast_logpoint("com.example.Cart", 42, "total", null, true, null, null))
			.isEqualTo("Error: refusing to arm: com.example.Cart still carries ...");
		verify(fastLogpoints, never()).register(any(), any(), anyString(), any(), anyInt());
	}

	@Test
	@DisplayName("jdwp_restore_fast_logpoints passes the approval through — missing means not approved")
	void restoreRoutesApproval() {
		when(fastLogpoints.restore(7, false)).thenReturn("RESTORE NOT PERFORMED — briefing");
		when(fastLogpoints.restore(7, true)).thenReturn("Restored the class file of X");
		assertThat(tools.jdwp_restore_fast_logpoints(7, null)).isEqualTo("RESTORE NOT PERFORMED — briefing");
		assertThat(tools.jdwp_restore_fast_logpoints(7, false)).isEqualTo("RESTORE NOT PERFORMED — briefing");
		assertThat(tools.jdwp_restore_fast_logpoints(7, true)).isEqualTo("Restored the class file of X");
	}

	@Test
	@DisplayName("jdwp_get_events drains the rings before reading the history")
	void getEventsDrainsFirst() {
		tools.jdwp_get_events(10);
		verify(fastLogpoints).drainAll();
	}

	@Test
	@DisplayName("jdwp_clear_breakpoint routes a fast-logpoint ID to the service")
	void clearRoutesToService() {
		when(fastLogpoints.clear(7)).thenReturn("Fast logpoint 7 cleared — original bytecode of X restored");
		assertThat(tools.jdwp_clear_breakpoint(7)).isEqualTo("Fast logpoint 7 cleared — original bytecode of X restored");
		// Other IDs still fall through to the tracker.
		assertThat(tools.jdwp_clear_breakpoint(8)).isEqualTo("Breakpoint 8 not found");
	}

	@Test
	@DisplayName("jdwp_reset disarms every fast logpoint and reports it")
	void resetDisarms() {
		when(fastLogpoints.disarmAll("jdwp_reset")).thenReturn(2);
		assertThat(tools.jdwp_reset()).contains("Fast logpoints cleared:        2 (original bytecode restored)");
		verify(fastLogpoints).disarmAll("jdwp_reset");
	}

	@Test
	@DisplayName("jdwp_reset connects first, disables leftovers before the ID reset and lists them again after it")
	void resetHandlesLeftoversAroundTheIdReset() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(vm.eventRequestManager()).thenReturn(mock(EventRequestManager.class));
		when(jdiService.getRawVM()).thenReturn(vm);
		when(jdiService.getVM()).thenReturn(vm);
		when(fastLogpoints.disableLeftovers()).thenReturn(1);
		when(fastLogpoints.discover(vm)).thenReturn("\n\nFAST LOGPOINTS LEFT BY AN EARLIER SESSION — 1 found");

		assertThat(tools.jdwp_reset())
			.contains("Earlier sessions' fast logpoints: 1 disabled (kill switch; bytecode unchanged)")
			.endsWith("FAST LOGPOINTS LEFT BY AN EARLIER SESSION — 1 found");
		final InOrder order = inOrder(jdiService, fastLogpoints);
		order.verify(jdiService).getVM();
		order.verify(fastLogpoints).disableLeftovers();
		order.verify(fastLogpoints).disarmAll("jdwp_reset");
		order.verify(fastLogpoints).discover(vm);
	}

	@Test
	@DisplayName("jdwp_overview renders the fast-logpoint section and filters it")
	void overviewListsFastLogpoints() {
		when(fastLogpoints.list()).thenReturn(List.of(
			new FastLogpointService.Info(3, "com.example.Cart", 42, "total", "total < 0",
				FastLogpointService.State.ARMED, null, 4096, 10, 2, 15, true, null, FastLogpointService.Kind.LOGPOINT, EventRequest.SUSPEND_EVENT_THREAD, false, null),
			new FastLogpointService.Info(4, "com.example.Order", 7, "id", null,
				FastLogpointService.State.FAILED, "refusing to arm: ...", 4096, 0, 0, -1, false, null, FastLogpointService.Kind.LOGPOINT, EventRequest.SUSPEND_EVENT_THREAD, false, null)));

		final String all = tools.jdwp_overview("fast_logpoint", null, null);
		assertThat(all)
			.contains("Fast logpoints (2)")
			.contains("#3  com.example.Cart:42  [ARMED — written=15 read=10 dropped=2 buffer=4096]  expr=\"total\"  cond=\"total < 0\"")
			.contains("#4  com.example.Order:7  [FAILED: refusing to arm: ...]  expr=\"id\"");

		assertThat(tools.jdwp_overview("fast_logpoint", "order", null))
			.contains("Fast logpoints (1)").doesNotContain("Cart");
	}

	@Test
	@DisplayName("jdwp_overview shows leftovers of an earlier session with state, kill switch and provenance")
	void overviewListsLeftovers() {
		final FastLogpointService.Provenance provenance = new FastLogpointService.Provenance(
			Instant.parse("2026-10-28T15:02:11Z"), "mcp-jdwp-java, session 3fa1c2d9", "AppClassLoader#12", 4096);
		when(fastLogpoints.list()).thenReturn(List.of(
			new FastLogpointService.Info(7, "com.example.Cart", 42, "total", null,
				FastLogpointService.State.RECOVERED, null, 4096, 3, 0, 18342, false, provenance, FastLogpointService.Kind.LOGPOINT, EventRequest.SUSPEND_EVENT_THREAD, false, null),
			new FastLogpointService.Info(8, "com.example.Cart", 50, "qty", null,
				FastLogpointService.State.STRANDED, "the class's bytecode differs", 4096, 0, 0, 9, true, provenance, FastLogpointService.Kind.LOGPOINT, EventRequest.SUSPEND_EVENT_THREAD, false, null)));

		assertThat(tools.jdwp_overview("fast_logpoint", null, null))
			.contains("#7  com.example.Cart:42  [RECOVERED, kill switch set — written=18342 read=3 dropped=0 buffer=4096; "
				+ "jdwp_clear_breakpoint disables, jdwp_restore_fast_logpoints restores (user approval)]")
			.contains("[left by an earlier session: armed 2026-10-28T15:02:11Z by mcp-jdwp-java, session 3fa1c2d9, "
				+ "loader AppClassLoader#12, up to 4096 entries written before this attach (not replayed)]")
			.contains("#8  com.example.Cart:50  [STRANDED, evaluating — the class's bytecode differs]");
	}
}
