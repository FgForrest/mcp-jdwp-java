package one.edee.mcp.jdwp;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@code forceLoad=true} that cannot load the class — at VM start no thread can run
 * {@code Class.forName} — falls back to deferring the breakpoint. The reply must say so instead of
 * reading like a plain deferral, on every set-breakpoint path that defers: line breakpoint, logpoint,
 * exception breakpoint / logpoint and field breakpoint / logpoint. The mocked connection service
 * returns {@code null} from {@code findOrForceLoadClass}, which is what it does when no thread can run
 * the load.
 */
@DisplayName("forceLoad=true that cannot load the class says so in the deferred reply")
class JDWPToolsForceLoadFallbackNoteTest {

	private static final String NOTE = "forceLoad=true could not load it";

	private JDWPTools tools;

	@BeforeEach
	void setUp() throws Exception {
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, new BreakpointTracker(), new WatcherManager(),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			new JvmDiscoveryService());
		final VirtualMachine vm = mock(VirtualMachine.class);
		final EventRequestManager erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
		when(erm.createClassPrepareRequest()).thenReturn(mock(ClassPrepareRequest.class));
		when(jdiService.findOrForceLoadClass(anyString())).thenReturn(null);
		when(jdiService.findLoadedClass(anyString())).thenReturn(null);
	}

	@Test
	@DisplayName("line breakpoint")
	void lineBreakpoint() {
		assertThat(tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null, true))
			.startsWith("Breakpoint deferred for com.example.Foo:10")
			.contains("Class not yet loaded (" + NOTE)
			.contains("at VM start");
	}

	@Test
	@DisplayName("logpoint")
	void logpoint() {
		assertThat(tools.jdwp_set_logpoint("com.example.Foo", 10, "x", null, true))
			.startsWith("Logpoint deferred for com.example.Foo:10")
			.contains("Class not yet loaded (" + NOTE);
	}

	@Test
	@DisplayName("exception breakpoint")
	void exceptionBreakpoint() {
		assertThat(tools.jdwp_set_exception_breakpoint(
			"java.sql.SQLException", null, null, null, null, true, null, null, null, null))
			.startsWith("Exception breakpoint deferred")
			.contains("Class not yet loaded (" + NOTE);
	}

	@Test
	@DisplayName("exception logpoint")
	void exceptionLogpoint() {
		assertThat(tools.jdwp_set_exception_logpoint(
			"java.sql.SQLException", "$exception.getMessage()", null, null, null, null, null, true, null, null))
			.startsWith("Exception breakpoint deferred")
			.contains("Class not yet loaded (" + NOTE);
	}

	@Test
	@DisplayName("field breakpoint")
	void fieldBreakpoint() {
		assertThat(tools.jdwp_set_field_breakpoint(
			"com.example.Foo", "value", "modification", null, null, null, null, null, true, null))
			.startsWith("Field breakpoint deferred")
			.contains("Class not yet loaded (" + NOTE);
	}

	@Test
	@DisplayName("field logpoint")
	void fieldLogpoint() {
		assertThat(tools.jdwp_set_field_logpoint(
			"com.example.Foo", "value", "modification", "$newValue", null, null, null, null, null, true, null))
			.startsWith("Field breakpoint deferred")
			.contains("Class not yet loaded (" + NOTE);
	}

	@Test
	@DisplayName("without forceLoad the deferred reply carries no note")
	void noNoteWithoutForceLoad() {
		assertThat(tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null, null))
			.contains("Class not yet loaded — will activate")
			.doesNotContain(NOTE);
		assertThat(tools.jdwp_set_exception_breakpoint(
			"java.sql.SQLException", null, null, null, null, false, null, null, null, null))
			.doesNotContain(NOTE);
		assertThat(tools.jdwp_set_field_breakpoint(
			"com.example.Foo", "value", "modification", null, null, null, null, null, null, null))
			.doesNotContain(NOTE);
	}
}
