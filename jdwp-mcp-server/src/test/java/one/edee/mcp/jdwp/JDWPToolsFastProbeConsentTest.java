package one.edee.mcp.jdwp;

import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("standing consent for fast probes")
class JDWPToolsFastProbeConsentTest {

	private static final String CART = "com.example.Cart";
	private final JDIConnectionService connection = spy(JDIConnectionServiceTestSupport.newServiceWithMocks());
	private final FastLogpointService probes = JDWPToolsTestSupport.fastLogpointsMock();
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		tools = JDWPToolsTestSupport.newTools(connection, new BreakpointTracker(), new EventHistory(), probes);
		when(probes.findWaiting(any(), anyString(), anyInt())).thenReturn(null);
	}

	@Test
	@DisplayName("the briefing discloses scope and only explicit approval creates a reusable grant")
	void grantsAndReusesApprovalOnLaterVms() throws Exception {
		attach("localhost", 5005);
		for (Boolean approval : new Boolean[]{null, false}) {
			assertThat(logpoint(approval)).startsWith("[CONSENT REQUIRED]")
				.contains("later JVMs at the same host:port while this MCP server runs");
		}
		assertThat(tools.jdwp_overview("fast_logpoint", null, null)).doesNotContain("granted at");
		verify(connection, never()).getVM();

		assertThat(logpoint(true)).contains("WAITING");
		final String grant = tools.jdwp_overview("fast_logpoint", null, null).lines()
			.filter(line -> line.contains("granted at")).findFirst().orElseThrow();
		final String grantedAt = grant.substring(grant.indexOf("granted at ") + "granted at ".length());
		assertThat(grantedAt).matches("\\d{2}:\\d{2}:\\d{2} .+");
		DateTimeFormatter.ofPattern("HH:mm:ss z").withZone(ZoneId.systemDefault()).parse(grantedAt);
		assertThat(grant).contains("fast logpoint", CART + ":42", "total", "total < 0", "localhost:5005");
		assertThat(tools.jdwp_overview("fast_logpoint", "TOTAL < 0", null)).contains(grant);
		assertThat(tools.jdwp_overview("fast_breakpoint", null, null)).doesNotContain(grant);
		assertThat(tools.jdwp_overview("fast_logpoint", "unmatched", null)).doesNotContain(grant);

		for (String host : List.of("127.0.0.1", "::1", "localhost.")) {
			attach(host, 5005);
			assertThat(logpoint(null)).contains("WAITING", "Standing approval used", grantedAt);
			assertThat(logpoint(true)).contains("WAITING", "Standing approval used", grantedAt);
			assertThat(logpoint(false)).contains("WAITING", "Standing approval used", grantedAt);
		}
		final JDWPTools freshServer = JDWPToolsTestSupport.newTools(
			connection, new BreakpointTracker(), new EventHistory(), probes);
		assertThat(freshServer.jdwp_set_fast_logpoint(CART, 42, "total", "total < 0", null, null, null))
			.startsWith("[CONSENT REQUIRED]");
	}

	/** Checks that changing probe settings or the endpoint requires separate consent. */
	@Test
	@DisplayName("a changed probe or endpoint requires its own approval")
	void changedProbeOrEndpoint() throws Exception {
		attach("localhost", 5005);
		assertThat(logpoint(true)).contains("WAITING");

		assertThat(tools.jdwp_set_fast_logpoint(CART, 42, "total + 1", "total < 0", null, null, null))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_set_fast_logpoint(CART, 42, "total", "total <= 0", null, null, null))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_set_fast_logpoint(CART, 43, "total", "total < 0", null, null, null))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_set_fast_logpoint("com.example.Order", 42, "total", "total < 0", null, null, null))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, "thread", false, null, null))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_set_fast_logpoint(CART, 42, "total", "total < 0", null,
			FastLogpointService.DEFAULT_BUFFER_SIZE, null)).contains("Standing approval used");

		attach("localhost", 5006);
		assertThat(logpoint(null)).startsWith("[CONSENT REQUIRED]");
		attach("192.0.2.1", 5005);
		assertThat(logpoint(null)).startsWith("[CONSENT REQUIRED]");
	}

	/** A pre-attach briefing must be renewed before an explicit approval can target a JVM. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("an endpointless briefing must be renewed after attach")
	void endpointlessBriefingRequiresNamedApproval(boolean breakpoint) throws Exception {
		assertThat(setProbe(breakpoint, null, null)).startsWith("[CONSENT REQUIRED]")
			.contains("Attach to the intended endpoint before asking for approval");

		attach("localhost", 5005);
		assertThat(setProbe(breakpoint, true, null)).startsWith("[CONSENT REQUIRED AGAIN]")
			.contains("localhost:5005").doesNotContain("WAITING", "Standing approval used");
		verify(connection, never()).getVM();
		assertThat(tools.jdwp_overview(null, null, null)).doesNotContain("granted at");

		assertThat(setProbe(breakpoint, true, null)).contains("WAITING");
		assertThat(setProbe(breakpoint, null, null)).contains("Standing approval used");
	}

	/** The tools accept the exact name of a defined class whose name uses JVM-only syntax. */
	@Test
	@DisplayName("both fast tools accept an exact JVM binary name containing a hyphen")
	void validJvmBinaryNameIsAccepted() throws Exception {
		final String binaryName = "example.Order-item";
		final ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null);
		writer.visitEnd();
		final byte[] bytes = writer.toByteArray();
		final Class<?> target = new ClassLoader() {
			/** Defines the class from its bytecode, independent of Java source naming rules. */
			Class<?> loadTarget() {
				return defineClass(binaryName, bytes, 0, bytes.length);
			}
		}.loadTarget();
		assertThat(target.getName()).isEqualTo(binaryName);
		attach("localhost", 5005);

		assertThat(tools.jdwp_set_fast_logpoint(target.getName(), 42, "42", null, true, null, null))
			.contains("WAITING");
		assertThat(tools.jdwp_set_fast_breakpoint(target.getName(), 42, "false", true, null, null, null, null))
			.contains("WAITING");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("an explicit retry must use the endpoint of the latest briefing")
	void approvalStaysWithTheBriefedEndpoint(boolean breakpoint) throws Exception {
		attach("localhost", 5005);
		assertThat(setProbe(breakpoint, null, null)).startsWith("[CONSENT REQUIRED]").contains("localhost:5005");

		attach("localhost", 5006);
		assertThat(setProbe(breakpoint, true, null))
			.startsWith("[CONSENT REQUIRED AGAIN]").contains("localhost:5006").doesNotContain("WAITING");
		verify(connection, never()).getVM();
		assertThat(setProbe(breakpoint, null, null)).startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_overview(null, null, null)).doesNotContain("granted at");

		assertThat(setProbe(breakpoint, true, null)).contains("WAITING");
		attach("127.0.0.1", 5006);
		assertThat(setProbe(breakpoint, null, null)).contains("Standing approval used");

		attach("localhost", 5005);
		assertThat(setProbe(breakpoint, null, null)).startsWith("[CONSENT REQUIRED]").contains("localhost:5005");
		attach("localhost", 5006);
		assertThat(setProbe(breakpoint, true, null)).startsWith("[CONSENT REQUIRED AGAIN]");
		assertThat(setProbe(breakpoint, null, null)).contains("Standing approval used");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("passive approval cannot authorize forced class initialization")
	void passiveGrantCannotForceLoad(boolean breakpoint) throws Exception {
		attach("localhost", 5005);
		assertThat(setProbe(breakpoint, true, null)).contains("WAITING");
		final VirtualMachine nextVm = attach("localhost", 5005);
		doReturn(null).when(connection).findOrForceLoadClass(CART, null, nextVm);

		final String reply = setProbe(breakpoint, null, true);
		verify(connection, never()).findOrForceLoadClass(CART, null, nextVm);
		assertThat(reply).startsWith("[CONSENT REQUIRED]").contains("forceLoad=true", "<clinit>");
		assertThat(setProbe(breakpoint, true, true)).contains("WAITING");
		verify(connection).findOrForceLoadClass(CART, null, nextVm);
		assertThat(setProbe(breakpoint, null, false)).contains("Standing approval used");
		attach("localhost", 5005);
		assertThat(setProbe(breakpoint, null, true)).contains("Standing approval used");
	}

	@Test
	@DisplayName("a logpoint grant covers its effective buffer capacity")
	void bufferCapacityIsPartOfApproval() throws Exception {
		attach("localhost", 5005);
		assertThat(tools.jdwp_set_fast_logpoint(CART, 42, "total", null, true, 16, null)).contains("buffer: 16");
		attach("localhost", 5005);

		assertThat(tools.jdwp_set_fast_logpoint(CART, 42, "total", null, null, 65536, null))
			.startsWith("[CONSENT REQUIRED]").contains("buffer: 65536");
		assertThat(tools.jdwp_set_fast_logpoint(CART, 42, "total", null, null, 16, null))
			.contains("Standing approval used");
	}

	/** Invalid binary names fail before a consent exchange or VM lookup. */
	@ParameterizedTest
	@ValueSource(strings = {"example.*", "example/Cart", "example..Cart", "example.Cart[]", "example.Cart ",
		".example.Cart", "example.Cart.", "", "example.Cart;item", "example.Order\u00a0item"})
	@DisplayName("both fast tools reject non-binary class names before asking for consent")
	void invalidClassNamesAreRejected(String className) throws Exception {
		attach("localhost", 5005);
		for (Boolean approval : new Boolean[]{null, true}) {
			assertThat(tools.jdwp_set_fast_logpoint(className, 42, "total", null, approval, null, null))
				.startsWith("Error:").contains("binary class name");
			assertThat(tools.jdwp_set_fast_breakpoint(className, 42, "total < 0", approval, null, null, null, null))
				.startsWith("Error:").contains("binary class name");
		}
		verify(connection, never()).getVM();
		verifyNoInteractions(probes);
	}

	/** Checks that stop mode and suspend policy are part of fast-breakpoint approval. */
	@Test
	@DisplayName("fast breakpoint approvals match effective policy and stop mode")
	void breakpointPolicyAndMode() throws Exception {
		attach("localhost", 5005);
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, null, null, null, null))
			.startsWith("[CONSENT REQUIRED]").contains("later JVMs at the same host:port while this MCP server runs");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", true, null, null, null, null)).contains("WAITING");

		attach("127.0.0.1", 5005);
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, "ALL", true, null, null))
			.contains("WAITING", "Standing approval used");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, "thread", true, null, null))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, "all", false, null, null))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_overview("fast_breakpoint", null, null))
			.contains("fast breakpoint " + CART + ":42", "total < 0", "stops once", "suspendPolicy=all",
				"localhost:5005", "granted at");
	}

	/** Checks that fast-breakpoint approval binds the capture expression, force-load setting and endpoint. */
	@Test
	@DisplayName("a fast breakpoint approval covers its exact capture expression")
	void breakpointCaptureApproval() throws Exception {
		attach("localhost", 5005);
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", true, null, null, null, null))
			.contains("WAITING");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, null, null, null, "total + 1"))
			.startsWith("[CONSENT REQUIRED]").contains("condition: total < 0", "capture: total + 1");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", true, null, null, null, "total + 1"))
			.contains("WAITING").doesNotContain("Standing approval used");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", true, null, null, null, " "))
			.startsWith("Error: capture");

		attach("127.0.0.1", 5005);
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, null, null, null, "total + 1"))
			.contains("WAITING", "Standing approval used");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, null, null, null, "total + 2"))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_overview("fast_breakpoint", "total + 1", null))
			.contains("capture=\"total + 1\"", "forceLoad=false", "granted at");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, null, null, true, "total + 1"))
			.startsWith("[CONSENT REQUIRED]").contains("capture: total + 1", "forceLoad=true", "localhost:5005");
		attach("localhost", 5006);
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", true, null, null, true, "total + 1"))
			.startsWith("[CONSENT REQUIRED AGAIN]").contains("capture: total + 1", "forceLoad=true", "localhost:5006");
	}

	@Test
	@DisplayName("a refused explicit call does not create an approval")
	void rejectedCallDoesNotGrantApproval() throws Exception {
		final VirtualMachine vm = attach("localhost", 5005);
		when(vm.canRedefineClasses()).thenReturn(false);
		assertThat(logpoint(true)).contains("canRedefineClasses() is false");
		assertThat(logpoint(null)).startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_overview("fast_logpoint", null, null)).doesNotContain("granted at");
	}

	@Test
	@DisplayName("a concurrent endpoint switch cannot use or grant approval on the new endpoint")
	void endpointSwitchDuringTheCall() throws Exception {
		final VirtualMachine source = attach("localhost", 5005);
		assertThat(logpoint(true)).contains("WAITING");
		final VirtualMachine replacement = probeCapableVm();
		doAnswer(invocation -> {
			JDIConnectionServiceTestSupport.setVm(connection, replacement);
			JDIConnectionServiceTestSupport.setLastSuccessfulAttach(connection, "localhost", 5006);
			return replacement;
		}).when(connection).getVM();

		assertThat(logpoint(null)).startsWith("[CONSENT REQUIRED AGAIN]").contains("localhost:5006");
		JDIConnectionServiceTestSupport.setVm(connection, source);
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(connection, "localhost", 5005);
		assertThat(tools.jdwp_set_fast_logpoint(CART, 42, "id", null, true, null, null))
			.startsWith("[CONSENT REQUIRED AGAIN]").contains("localhost:5006");
		assertThat(logpoint(null)).startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_set_fast_logpoint(CART, 42, "id", null, null, null, null))
			.startsWith("[CONSENT REQUIRED]");
		verify(probes, never()).registerWaiting(eq(replacement), any(), anyString(),
			anyInt(), anyString(), any(), anyInt(), anyInt(), anyBoolean());
	}

	/** Checks that an explicit approval becomes a standing grant after a successful re-arm. */
	@Test
	@DisplayName("a breakpoint successfully re-armed with explicit approval grants consent, a refusal does not")
	void rearmGrantsOnlyOnSuccess() throws Exception {
		final VirtualMachine vm = attach("localhost", 5005);
		final ReferenceType type = mock(ReferenceType.class);
		final Location location = mock(Location.class);
		when(vm.classesByName(CART)).thenReturn(List.of(type));
		when(type.name()).thenReturn(CART);
		when(type.classLoader()).thenReturn(mock(ClassLoaderReference.class));
		when(type.locationsOfLine(42)).thenReturn(List.of(location));
		when(location.method()).thenReturn(mock(Method.class));
		when(probes.rearmBreakpoint(location, "total < 0", null, true, EventRequest.SUSPEND_ALL))
			.thenReturn(new FastLogpointService.RearmResult("Error: fast breakpoint with other parameters", false),
				new FastLogpointService.RearmResult("Fast breakpoint re-armed", true),
				new FastLogpointService.RearmResult("Fast breakpoint re-armed", true),
				new FastLogpointService.RearmResult("Error: stop gate did not reopen. Error gate reopened.", false));

		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", true, null, null, null, null))
			.startsWith("Error:");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, null, null, null, null))
			.startsWith("[CONSENT REQUIRED]");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", true, null, null, null, null))
			.contains("re-armed");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, null, null, null, null))
			.contains("re-armed", "Standing approval used");
		assertThat(tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", null, null, null, null, null))
			.contains("stop gate did not reopen").doesNotContain("Standing approval used");
	}

	private String logpoint(Boolean approval) {
		return tools.jdwp_set_fast_logpoint(CART, 42, "total", "total < 0", approval, null, null);
	}

	/** Calls the selected fast-probe tool with the requested consent and force-load settings and returns its reply. */
	private String setProbe(boolean breakpoint, Boolean approval, Boolean forceLoad) {
		return breakpoint
			? tools.jdwp_set_fast_breakpoint(CART, 42, "total < 0", approval, null, null, forceLoad, null)
			: tools.jdwp_set_fast_logpoint(CART, 42, "total", "total < 0", approval, null, forceLoad);
	}

	private VirtualMachine attach(String host, int port) throws Exception {
		final VirtualMachine vm = probeCapableVm();
		JDIConnectionServiceTestSupport.setVm(connection, vm);
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(connection, host, port);
		doReturn(vm).when(connection).getVM();
		return vm;
	}

	private static VirtualMachine probeCapableVm() {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(vm.canRedefineClasses()).thenReturn(true);
		when(vm.canGetBytecodes()).thenReturn(true);
		when(vm.canGetConstantPool()).thenReturn(true);
		return vm;
	}
}
