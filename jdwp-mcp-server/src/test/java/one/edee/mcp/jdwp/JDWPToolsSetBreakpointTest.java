package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link JDWPTools#jdwp_set_breakpoint}: the {@code suspendPolicy}
 * switch ('all' / 'thread' / 'none' / null default / case-insensitivity / invalid value), the
 * eager-class-loaded happy path, the deferred-class path, and the "blank condition treated as
 * no condition" semantics.
 */
@DisplayName("jdwp_set_breakpoint")
class JDWPToolsSetBreakpointTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker tracker;
	private JDWPTools tools;
	private VirtualMachine vm;
	private EventRequestManager erm;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		tracker = new BreakpointTracker();
		final WatcherManager watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		erm = mock(EventRequestManager.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.eventRequestManager()).thenReturn(erm);
	}

	private BreakpointRequest wireEagerSet(String className, int line) throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		final Location loc = mock(Location.class);
		final BreakpointRequest req = mock(BreakpointRequest.class);
		when(jdiService.findOrForceLoadClass(className)).thenReturn(refType);
		when(refType.locationsOfLine(line)).thenReturn(List.of(loc));
		when(erm.createBreakpointRequest(loc)).thenReturn(req);
		return req;
	}

	@Nested
	@DisplayName("suspend policy")
	class SuspendPolicy {

		@Test
		@DisplayName("'all' (explicit) → SUSPEND_ALL")
		void shouldUseSuspendAllForExplicitAll() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null);

			assertThat(result).contains("suspend: all");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_ALL);
		}

		@Test
		@DisplayName("null → defaults to SUSPEND_ALL")
		void shouldDefaultToSuspendAllWhenNull() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, null, null, null, null);

			assertThat(result).contains("suspend: all");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_ALL);
		}

		@Test
		@DisplayName("'thread' → SUSPEND_EVENT_THREAD")
		void shouldUseSuspendEventThreadForThread() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "thread", null, null, null);

			assertThat(result).contains("suspend: thread");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
		}

		@Test
		@DisplayName("'none' → SUSPEND_NONE")
		void shouldUseSuspendNoneForNone() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "none", null, null, null);

			assertThat(result).contains("suspend: none");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_NONE);
		}

		@Test
		@DisplayName("policy is case-insensitive — 'ALL' matches 'all'")
		void shouldAcceptUppercasePolicy() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "ALL", null, null, null);

			assertThat(result).contains("suspend: all");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_ALL);
		}

		@Test
		@DisplayName("unknown policy returns an actionable error")
		void shouldRejectInvalidSuspendPolicy() throws Exception {
			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "bogus", null, null, null);

			assertThat(result).startsWith("Error:").contains("Invalid suspend policy 'bogus'");
		}
	}

	@Nested
	@DisplayName("class resolution")
	class ClassResolution {

		@Test
		@DisplayName("eager-loaded class — registers, enables, and returns the new BP id")
		void shouldSetBreakpointEagerlyWhenClassIsLoaded() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null);

			assertThat(result).startsWith("Breakpoint set at com.example.Foo:10");
			// Unchained BP must be enabled as the last step.
			verify(req).setEnabled(true);
			assertThat(tracker.findIdByRequest(req)).isNotNull();
		}

		@Test
		@DisplayName("deferred path — registers a ClassPrepareRequest when class not yet loaded")
		void shouldDeferWhenClassNotLoaded() throws Exception {
			final ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
			when(jdiService.findOrForceLoadClass("com.example.Foo")).thenReturn(null);
			when(vm.classesByName("com.example.Foo")).thenReturn(List.of());
			when(erm.createClassPrepareRequest()).thenReturn(cpr);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null);

			assertThat(result).startsWith("Breakpoint deferred for com.example.Foo:10");
			verify(cpr).addClassFilter("com.example.Foo");
			verify(cpr).enable();
		}
	}

	@Nested
	@DisplayName("condition handling")
	class ConditionHandling {

		@Test
		@DisplayName("blank condition is treated as no condition (no 'condition:' suffix)")
		void shouldTreatBlankConditionAsNoCondition() throws Exception {
			wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", "   ", null, null);

			assertThat(result).doesNotContain("condition:");
		}

		@Test
		@DisplayName("non-blank condition is reflected in the response")
		void shouldIncludeConditionInResponseWhenProvided() throws Exception {
			wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", "i > 0", null, null);

			assertThat(result).contains("condition: i > 0");
		}
	}
}
