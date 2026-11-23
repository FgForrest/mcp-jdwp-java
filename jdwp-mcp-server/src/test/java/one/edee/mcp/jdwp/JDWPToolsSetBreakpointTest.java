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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
	private EventHistory eventHistory;

	@BeforeEach
	void setUp() throws Exception {
		jdiService = mock(JDIConnectionService.class);
		// Spy (delegates to the real impl) so the race-guard tests can force promotePendingToActive
		// to win/lose the promotion race deterministically; all other tests see real behaviour.
		tracker = spy(new BreakpointTracker());
		final WatcherManager watcherManager = new WatcherManager();
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
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
		when(jdiService.findLoadedClass(className)).thenReturn(refType);
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

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null, null);

			assertThat(result).contains("suspend: all");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_ALL);
		}

		@Test
		@DisplayName("null → defaults to SUSPEND_ALL")
		void shouldDefaultToSuspendAllWhenNull() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, null, null, null, null, null);

			assertThat(result).contains("suspend: all");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_ALL);
		}

		@Test
		@DisplayName("'thread' → SUSPEND_EVENT_THREAD")
		void shouldUseSuspendEventThreadForThread() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "thread", null, null, null, null);

			assertThat(result).contains("suspend: thread");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
		}

		@Test
		@DisplayName("'none' → SUSPEND_NONE")
		void shouldUseSuspendNoneForNone() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "none", null, null, null, null);

			assertThat(result).contains("suspend: none");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_NONE);
		}

		@Test
		@DisplayName("policy is case-insensitive — 'ALL' matches 'all'")
		void shouldAcceptUppercasePolicy() throws Exception {
			final BreakpointRequest req = wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "ALL", null, null, null, null);

			assertThat(result).contains("suspend: all");
			verify(req).setSuspendPolicy(EventRequest.SUSPEND_ALL);
		}

		@Test
		@DisplayName("unknown policy returns an actionable error")
		void shouldRejectInvalidSuspendPolicy() throws Exception {
			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "bogus", null, null, null, null);

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

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null, null);

			assertThat(result).startsWith("Breakpoint set at com.example.Foo:10");
			// Unchained BP must be enabled as the last step.
			verify(req).setEnabled(true);
			assertThat(tracker.findIdByRequest(req)).isNotNull();
		}

		@Test
		@DisplayName("deferred path — registers a ClassPrepareRequest when class not yet loaded")
		void shouldDeferWhenClassNotLoaded() throws Exception {
			final ClassPrepareRequest cpr = mock(ClassPrepareRequest.class);
			when(jdiService.findLoadedClass("com.example.Foo")).thenReturn(null);
			when(vm.classesByName("com.example.Foo")).thenReturn(List.of());
			when(erm.createClassPrepareRequest()).thenReturn(cpr);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null, null);

			assertThat(result).startsWith("Breakpoint deferred for com.example.Foo:10");
			verify(cpr).addClassFilter("com.example.Foo");
			verify(cpr).enable();
		}

		/** Keeps loaded-name corrections for simple line targets and dotted nested names. */
		@ParameterizedTest
		@CsvSource({"String, com.acme.String", "java.util.Map.Entry, java.util.Map$Entry"})
		void shouldSuggestTheLoadedBinaryName(String className, String loadedName) {
			final ReferenceType loaded = mock(ReferenceType.class);
			when(loaded.name()).thenReturn(loadedName);
			when(vm.allClasses()).thenReturn(List.of(loaded));
			when(vm.version()).thenReturn(Runtime.version().toString());
			when(erm.createClassPrepareRequest()).thenReturn(mock(ClassPrepareRequest.class));

			assertThat(tools.jdwp_set_breakpoint(className, 42, null, null, null, null, null))
				.contains("Did you mean: " + loadedName);
		}

		/**
		 * Default registration must not trigger {@code Class.forName} in the target VM — the whole
		 * point of GH issue #3 is that the debugger observes class loads rather than causing them.
		 * Asserts via Mockito {@code never()} so a regression that re-introduces an eager force-load
		 * call on the default path fails this test deterministically.
		 */
		@Test
		@DisplayName("default registration never calls findOrForceLoadClass")
		void shouldNeverForceLoadOnDefaultRegistration() throws Exception {
			when(jdiService.findLoadedClass("com.example.Foo")).thenReturn(null);
			when(vm.classesByName("com.example.Foo")).thenReturn(List.of());
			when(erm.createClassPrepareRequest()).thenReturn(mock(ClassPrepareRequest.class));

			tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null, null);

			verify(jdiService, org.mockito.Mockito.never()).findOrForceLoadClass(org.mockito.ArgumentMatchers.anyString());
			verify(jdiService, org.mockito.Mockito.never()).findOrForceLoadClass(
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
		}

		/**
		 * Opt-in path: with {@code forceLoad=true} the tool routes to
		 * {@link JDIConnectionService#findOrForceLoadClass} so the caller can accept the side
		 * effects of running {@code <clinit>} in the target VM in exchange for an immediate bind.
		 */
		@Test
		@DisplayName("forceLoad=true routes to findOrForceLoadClass")
		void shouldRouteToForceLoadWhenForceLoadTrue() throws Exception {
			final ReferenceType refType = mock(ReferenceType.class);
			final Location loc = mock(Location.class);
			final BreakpointRequest req = mock(BreakpointRequest.class);
			when(jdiService.findOrForceLoadClass("com.example.Foo")).thenReturn(refType);
			when(refType.locationsOfLine(10)).thenReturn(List.of(loc));
			when(erm.createBreakpointRequest(loc)).thenReturn(req);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null, true);

			assertThat(result).startsWith("Breakpoint set at com.example.Foo:10");
			verify(jdiService).findOrForceLoadClass("com.example.Foo");
			verify(jdiService, org.mockito.Mockito.never()).findLoadedClass(org.mockito.ArgumentMatchers.anyString());
		}
	}

	@Nested
	@DisplayName("condition handling")
	class ConditionHandling {

		@Test
		@DisplayName("blank condition is treated as no condition (no 'condition:' suffix)")
		void shouldTreatBlankConditionAsNoCondition() throws Exception {
			wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", "   ", null, null, null);

			assertThat(result).doesNotContain("condition:");
		}

		@Test
		@DisplayName("non-blank condition is reflected in the response")
		void shouldIncludeConditionInResponseWhenProvided() throws Exception {
			wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", "i > 0", null, null, null);

			assertThat(result).contains("condition: i > 0");
		}
	}

	@Nested
	@DisplayName("multi-location diagnostic")
	class MultiLocationDiagnostic {

		@Test
		@DisplayName("eager bind to a line with >1 Location warns in the response AND records BP_MULTI_LOCATION")
		void shouldWarnAndRecordEventForMultiLocationEagerBind() throws Exception {
			final ReferenceType refType = mock(ReferenceType.class);
			final Location bound = mock(Location.class);
			final Location lambda = mock(Location.class);
			// describeLocation reads method().name() + codeIndex() off the bound Location.
			final com.sun.jdi.Method method = mock(com.sun.jdi.Method.class);
			when(method.name()).thenReturn("doWork");
			when(bound.method()).thenReturn(method);
			when(bound.codeIndex()).thenReturn(7L);
			when(jdiService.findLoadedClass("com.example.WithLambda")).thenReturn(refType);
			when(refType.locationsOfLine(99)).thenReturn(List.of(bound, lambda));
			when(erm.createBreakpointRequest(bound)).thenReturn(mock(BreakpointRequest.class));

			final String result = tools.jdwp_set_breakpoint("com.example.WithLambda", 99, "all", null, null, null, null);

			assertThat(result)
				.startsWith("Breakpoint set at com.example.WithLambda:99")
				.contains("WARNING: line has 2 Locations")
				.contains("other paths will MISS this BP");

			assertThat(eventHistory.getRecent(10))
				.anySatisfy(e -> {
					assertThat(e.type()).isEqualTo("BP_MULTI_LOCATION");
					assertThat(e.summary()).startsWith("BP ");
					assertThat(e.summary()).contains("com.example.WithLambda:99");
					assertThat(e.summary()).contains("2 bytecode locations");
					assertThat(e.details()).containsEntry("kind", "breakpoint");
					assertThat(e.details()).containsEntry("locationCount", "2");
				});
		}

		@Test
		@DisplayName("single-Location bind records NO BP_MULTI_LOCATION event and adds no warning")
		void shouldNotWarnOrRecordForSingleLocationBind() throws Exception {
			wireEagerSet("com.example.Foo", 10);

			final String result = tools.jdwp_set_breakpoint("com.example.Foo", 10, "all", null, null, null, null);

			assertThat(result).doesNotContain("WARNING");
			assertThat(eventHistory.getRecent(10))
				.noneSatisfy(e -> assertThat(e.type()).isEqualTo("BP_MULTI_LOCATION"));
		}
	}

	@Nested
	@DisplayName("race-guard multi-location diagnostic")
	class RaceGuardMultiLocationDiagnostic {

		/**
		 * Drives the race-guard recheck path: the class is NOT loaded eagerly, so a pending entry +
		 * ClassPrepareRequest are registered, but {@code vm.classesByName} then finds it (it loaded
		 * between the two checks), and the tool binds inline.
		 */
		private ReferenceType wireRaceGuard(String className, int line, Location... locations) throws Exception {
			final ReferenceType refType = mock(ReferenceType.class);
			when(jdiService.findLoadedClass(className)).thenReturn(null);
			when(erm.createClassPrepareRequest()).thenReturn(mock(ClassPrepareRequest.class));
			when(vm.classesByName(className)).thenReturn(List.of(refType));
			when(refType.locationsOfLine(line)).thenReturn(List.of(locations));
			when(erm.createBreakpointRequest(locations[0])).thenReturn(mock(BreakpointRequest.class));
			return refType;
		}

		@Test
		@DisplayName("losing the promotion race emits NO BP_MULTI_LOCATION and notes the concurrent path")
		void shouldNotRecordMultiLocationWhenPromotionLost() throws Exception {
			final Location bound = mock(Location.class);
			final Location lambda = mock(Location.class);
			wireRaceGuard("com.example.Lam", 50, bound, lambda);
			// Another path already promoted this id — our inline bind loses the race.
			doReturn(false).when(tracker).promotePendingToActive(anyInt(), any());

			final String result = tools.jdwp_set_breakpoint("com.example.Lam", 50, "all", null, null, null, null);

			assertThat(result)
				.startsWith("Breakpoint set at com.example.Lam:50")
				.contains("bound by a concurrent activation path")
				.doesNotContain("WARNING");
			// The winning path owns the diagnostic; we must not double-record it.
			assertThat(eventHistory.getRecent(10))
				.noneSatisfy(e -> assertThat(e.type()).isEqualTo("BP_MULTI_LOCATION"));
		}

		@Test
		@DisplayName("winning the promotion race records BP_MULTI_LOCATION and warns")
		void shouldRecordMultiLocationWhenPromotionWon() throws Exception {
			final Location bound = mock(Location.class);
			final Location lambda = mock(Location.class);
			final com.sun.jdi.Method method = mock(com.sun.jdi.Method.class);
			when(method.name()).thenReturn("doWork");
			when(bound.method()).thenReturn(method);
			when(bound.codeIndex()).thenReturn(3L);
			wireRaceGuard("com.example.Lam", 50, bound, lambda);
			doReturn(true).when(tracker).promotePendingToActive(anyInt(), any());

			final String result = tools.jdwp_set_breakpoint("com.example.Lam", 50, "all", null, null, null, null);

			assertThat(result)
				.startsWith("Breakpoint set at com.example.Lam:50")
				.contains("WARNING: line has 2 Locations")
				.doesNotContain("concurrent activation path");
			assertThat(eventHistory.getRecent(10))
				.anySatisfy(e -> {
					assertThat(e.type()).isEqualTo("BP_MULTI_LOCATION");
					assertThat(e.details()).containsEntry("locationCount", "2");
				});
		}
	}
}
