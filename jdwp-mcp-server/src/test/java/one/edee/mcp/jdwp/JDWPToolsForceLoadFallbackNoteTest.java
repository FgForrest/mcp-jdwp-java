package one.edee.mcp.jdwp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

	/** Exercises real force-load logic over JDI mirrors, including the worker and the registration paths. */
	@Nested
	class TargetFailure {
		private static final String NAME = "demo.BadInit";
		private static final int LINE = 42;
		private static final String FAILURE = "Class.forName threw java.lang.IllegalStateException: Connection refused";
		private static final String WARNING = "\nWarning: forceLoad=true";
		private static final String FALLBACK = "(forceLoad=true could not load it: Class.forName needs a thread "
			+ "stopped at an event, which does not exist at VM start, and finds only classes the application can load)";

		private final JDIConnectionService connection = JDIConnectionServiceTestSupport.newServiceWithMocks();
		private final VirtualMachine vm = mock(VirtualMachine.class);
		private final ClassType javaClass = mock(ClassType.class);
		private final ReferenceType loaded = mock(ReferenceType.class);
		private final EventRequestManager requests = mock(EventRequestManager.class);
		private final ClassPrepareRequest prepare = mock(ClassPrepareRequest.class);
		private final BreakpointTracker tracker = new BreakpointTracker();
		private final EventHistory history = new EventHistory();
		private final FastLogpointService probes = JDWPToolsTestSupport.fastLogpointsMock();
		private final JDWPTools registration = JDWPToolsTestSupport.newTools(connection, tracker, history, probes);
		private final Field field = mock(Field.class);
		private final Location location = mock(Location.class);
		private final ThreadReference thread = mock(ThreadReference.class);

		/** Supplies a real service with a target ready to run Class.forName. */
		@BeforeEach
		void setUpTarget() throws Exception {
			JDIConnectionServiceTestSupport.setVm(connection, vm);
			JDIConnectionServiceTestSupport.setLastSuccessfulAttach(connection, "127.0.0.1", 5005);
			when(vm.eventRequestManager()).thenReturn(requests);
			when(vm.allThreads()).thenReturn(List.of(thread));
			when(thread.name()).thenReturn("main");
			when(thread.isSuspended()).thenReturn(true);
			when(thread.frameCount()).thenReturn(1);
			when(vm.classesByName("java.lang.Class")).thenReturn(List.of(javaClass));
			when(javaClass.concreteMethodByName("forName", "(Ljava/lang/String;)Ljava/lang/Class;"))
				.thenReturn(mock(Method.class));
			when(vm.mirrorOf(NAME)).thenReturn(mock(StringReference.class));
			when(vm.canRedefineClasses()).thenReturn(true);
			when(vm.canGetBytecodes()).thenReturn(true);
			when(vm.canGetConstantPool()).thenReturn(true);
			when(probes.findWaiting(any(), anyString(), anyInt())).thenReturn(null);
			when(requests.createClassPrepareRequest()).thenReturn(prepare);
			when(requests.createBreakpointRequest(location)).thenAnswer(call -> mock(BreakpointRequest.class));
			when(requests.createExceptionRequest(any(), anyBoolean(), anyBoolean()))
				.thenReturn(mock(ExceptionRequest.class));
			when(requests.createModificationWatchpointRequest(field))
				.thenReturn(mock(ModificationWatchpointRequest.class));
			when(loaded.name()).thenReturn(NAME);
			when(loaded.classLoader()).thenReturn(mock(ClassLoaderReference.class));
			when(loaded.failedToInitialize()).thenReturn(true);
			when(loaded.locationsOfLine(LINE)).thenReturn(List.of(location));
			when(loaded.allFields()).thenReturn(List.of(field));
			when(field.name()).thenReturn("value");
			when(location.method()).thenReturn(mock(Method.class));
			when(location.method().name()).thenReturn("work");
		}

		/** All eight registrations bind a now-visible class and append the same warning. */
		@ParameterizedTest
		@ValueSource(strings = {"line", "log", "exception", "exception log", "field", "field log", "fast log", "fast break"})
		void warnsAfterBinding(String tool) throws Exception {
			failAndExposeClass();

			final String reply = register(tool);

			assertThat(reply).contains("set")
				.endsWith(WARNING + " failed in the target: " + FAILURE + ". " + NAME + " is loaded, but its "
					+ "initialization failed: later uses that require initialization throw NoClassDefFoundError "
					+ "until the target restarts.")
				.doesNotContain("[VM_DEATH]", "deferred", "WAITING", "at VM start");
			assertWarningOnce(reply);
		}

		/** A failed Class.forName leaves normal pending registration and names the target exception. */
		@ParameterizedTest
		@ValueSource(strings = {"line", "log", "exception", "exception log", "field", "field log", "fast log", "fast break"})
		void defersWithTheTargetException(String tool) throws Exception {
			final InvocationException failure = targetFailure("java.lang.ClassNotFoundException", NAME);
			when(javaClass.invokeMethod(any(), any(), any(), anyInt())).thenThrow(failure);

			assertThat(register(tool))
				.contains("(forceLoad=true could not load it: Class.forName threw java.lang.ClassNotFoundException: " + NAME + ")")
				.doesNotContain(WARNING, "at VM start");
			switch (tool) {
				case "line", "log" -> assertThat(tracker.getPendingBreakpointsForClass(NAME)).hasSize(1);
				case "exception", "exception log" -> assertThat(tracker.getPendingExceptionBreakpointsForClass(NAME)).hasSize(1);
				case "field", "field log" -> assertThat(tracker.getPendingFieldBreakpointsForClass(NAME)).hasSize(1);
				default -> verify(probes).registerWaiting(any(), any(), anyString(), anyInt(), anyString(),
					any(), anyInt(), anyInt(), anyBoolean());
			}
		}

		/** Force-load failures in the target are logged at WARN with their formatted description. */
		@Test
		void logsTargetFailureAtWarn() throws Exception {
			failAndExposeClass();
			final Logger logger = (Logger) LoggerFactory.getLogger(JDIConnectionService.class);
			final ListAppender<ILoggingEvent> captured = new ListAppender<>();
			captured.start();
			logger.addAppender(captured);
			try {
				register("line");
			} finally {
				logger.detachAppender(captured);
				captured.stop();
			}

			assertThat(captured.list).anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(event.getFormattedMessage()).contains("Could not force-load class '" + NAME + "'",
					"java.lang.IllegalStateException: Connection refused");
			});
		}

		/** No thread and a JDI invocation rejection both retain the original note. */
		@Test
		void preservesFallbackWithoutATargetException() throws Exception {
			when(vm.allThreads()).thenReturn(List.of());
			assertThat(register("line")).contains(FALLBACK);

			when(vm.allThreads()).thenReturn(List.of(thread));
			when(javaClass.invokeMethod(any(), any(), any(), anyInt()))
				.thenThrow(new IncompatibleThreadStateException("no event stop"));
			assertThat(register("log")).contains(FALLBACK).doesNotContain("no event stop");
		}

		/** Phase 1 returns an already visible class without reporting its previous initialization failure. */
		@Test
		void leavesAlreadyVisibleClassesAlone() throws Exception {
			when(vm.classesByName(NAME)).thenReturn(List.of(loaded));

			assertThat(register("line")).startsWith("Breakpoint set").doesNotContain(WARNING);
		}

		/** The target exception can occur without making the loaded class unusable. */
		@Test
		void warnsWhenTheLoadedClassDidNotFailInitialization() throws Exception {
			failAndExposeClass();
			when(loaded.failedToInitialize()).thenReturn(false);

			final String reply = register("line");

			assertThat(reply).startsWith("Breakpoint set").endsWith(FAILURE + ". " + NAME + " is loaded.")
				.doesNotContain("NoClassDefFoundError");
			assertWarningOnce(reply);
		}

		/** Errors after lookup keep the original refusal and append the target failure. */
		@Test
		void warnsAfterLineAndFastProbeErrors() throws Exception {
			failAndExposeClass();
			when(loaded.locationsOfLine(LINE)).thenReturn(List.of());
			assertWarningOnce("Error: No executable code", register("line"));

			failAndExposeClass();
			when(loaded.locationsOfLine(LINE)).thenThrow(new IllegalStateException("later lookup failed"));
			assertWarningOnce("Error: later lookup failed", register("log"));

			failAndExposeClass();
			when(loaded.classLoader()).thenReturn(null);
			assertWarningOnce("Error: classes loaded by the bootstrap", register("fast log"));
		}

		/** Field resolution errors still carry the warning. */
		@Test
		void warnsAfterFieldErrors() throws Exception {
			failAndExposeClass();
			when(loaded.allFields()).thenReturn(List.of());
			assertWarningOnce("Error: Field 'value' not found", register("field"));

			failAndExposeClass();
			final Field shadowed = mock(Field.class);
			when(shadowed.name()).thenReturn("value");
			when(loaded.allFields()).thenReturn(List.of(shadowed, field));
			assertWarningOnce("Error: Field 'value' is ambiguous", register("field log"));

			failAndExposeClass();
			when(loaded.allFields()).thenReturn(List.of(field));
			when(field.isStatic()).thenReturn(true);
			final String reply = registration.jdwp_set_field_breakpoint(
				NAME, "value", "modification", null, null, 1L, null, null, true, null);
			assertThat(reply).startsWith("Error: Field 'value' on " + NAME + " is static");
			assertWarningOnce(reply);
		}

		/** Re-arming reports the target failure even when there is no new registration. */
		@Test
		void warnsAfterRearming() throws Exception {
			failAndExposeClass();
			when(probes.rearmBreakpoint(any(), anyString(), any(), anyBoolean(), anyInt()))
				.thenReturn(new FastLogpointService.RearmResult("Fast breakpoint re-armed", true));

			final String reply = register("fast break");
			assertThat(reply).startsWith("Fast breakpoint re-armed");
			assertWarningOnce(reply);
		}

		/** A fast probe's post-failure lookup and registration stay on the VM approved by the caller. */
		@Test
		void usesTheCapturedVmAfterFailure() throws Exception {
			final VirtualMachine replacement = mock(VirtualMachine.class);
			final InvocationException failure = targetFailure("java.lang.IllegalStateException", "Connection refused");
			when(vm.classesByName(NAME)).thenReturn(List.of(), List.of(loaded));
			when(javaClass.invokeMethod(any(), any(), any(), anyInt())).thenAnswer(call -> {
				JDIConnectionServiceTestSupport.setVm(connection, replacement);
				throw failure;
			});

			assertWarningOnce("Fast logpoint set", register("fast log"));
			verify(probes).register(eq(vm), eq(location), eq("x"), any(), anyInt());
			verify(replacement, never()).classesByName(NAME);
		}

		/** If activation wins the deferral race, the reply still names the target failure. */
		@Test
		void warnsAfterConcurrentActivation() throws Exception {
			final InvocationException failure = targetFailure("java.lang.IllegalStateException", "Connection refused");
			when(javaClass.invokeMethod(any(), any(), any(), anyInt())).thenThrow(failure);
			final BreakpointRequest winner = mock(BreakpointRequest.class);
			doAnswer(call -> {
				when(vm.classesByName(NAME)).thenReturn(List.of(loaded));
				final int id = tracker.getPendingBreakpointsForClass(NAME).get(0).getKey();
				assertThat(tracker.promotePendingToActive(id, winner)).isTrue();
				return null;
			}).when(prepare).enable();

			final String reply = register("line");
			assertThat(reply).contains("bound by a concurrent activation path");
			assertWarningOnce(reply);
		}

		/** Validation before lookup has no warning. */
		@Test
		void doesNotWarnBeforeLookup() throws Exception {
			failAndExposeClass();
			assertThat(registration.jdwp_set_breakpoint(NAME, LINE, "invalid", null, null, null, true))
				.startsWith("Error: Invalid suspend policy").doesNotContain(WARNING);
			assertThat(registration.jdwp_set_fast_logpoint(NAME, LINE, "x", null, null, null, true))
				.startsWith("[CONSENT REQUIRED]").doesNotContain(WARNING);
		}

		/** The worker reports the same target text if it finishes after the tool's deadline. */
		@Test
		void recordsLateForceLoadAsAFailure() throws Exception {
			final CountDownLatch release = new CountDownLatch(1);
			final CountDownLatch invoking = new CountDownLatch(1);
			final ExecutorService pool = InvocationRunner.newPool();
			final SuspensionSession session = tracker.openSuspensionSession(vm, history);
			ReflectionTestUtils.setField(registration, "invocations", new InvocationRunner(command -> {
				pool.execute(command);
				await().atMost(Duration.ofSeconds(5)).until(() -> invoking.getCount() == 0);
			}, 50, history, () -> session));
			final InvocationException failure = targetFailure("java.lang.IllegalStateException", "Connection refused");
			when(javaClass.invokeMethod(any(), any(), any(), anyInt())).thenAnswer(call -> {
				invoking.countDown();
				assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
				throw failure;
			});
			try {
				assertThat(register("line")).contains("[STILL_RUNNING]");
				release.countDown();
				await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
					assertThat(history.getRecent(10)).anySatisfy(event -> {
						assertThat(event.type()).isEqualTo("EVALUATION_COMPLETED_LATE");
						assertThat(event.summary()).endsWith("failed: " + FAILURE);
					}));
			} finally {
				release.countDown();
				pool.shutdownNow();
			}
		}

		/** Invokes a registration with the same class and line on each tool surface. */
		private String register(String tool) {
			return switch (tool) {
				case "line" -> registration.jdwp_set_breakpoint(NAME, LINE, null, null, null, null, true);
				case "log" -> registration.jdwp_set_logpoint(NAME, LINE, "x", null, true);
				case "exception" -> registration.jdwp_set_exception_breakpoint(
					NAME, null, null, null, null, true, null, null, null, null);
				case "exception log" -> registration.jdwp_set_exception_logpoint(
					NAME, "$exception", null, null, null, null, null, true, null, null);
				case "field" -> registration.jdwp_set_field_breakpoint(
					NAME, "value", "modification", null, null, null, null, null, true, null);
				case "field log" -> registration.jdwp_set_field_logpoint(
					NAME, "value", "modification", "$newValue", null, null, null, null, null, true, null);
				case "fast log" -> registration.jdwp_set_fast_logpoint(NAME, LINE, "x", null, true, null, true);
				case "fast break" -> registration.jdwp_set_fast_breakpoint(NAME, LINE, "true", true, null, null, true, null);
				default -> throw new AssertionError(tool);
			};
		}

		/** The first lookup misses the class, and the after-invocation lookup sees it. */
		private void failAndExposeClass() throws Exception {
			when(vm.classesByName(NAME)).thenReturn(List.of(), List.of(loaded));
			final InvocationException failure = targetFailure("java.lang.IllegalStateException", "Connection refused");
			doThrow(failure).when(javaClass).invokeMethod(any(), any(), any(), anyInt());
		}

		/** Supplies the target type and Throwable.detailMessage, without calling a target method. */
		private InvocationException targetFailure(String typeName, String message) {
			final ObjectReference exception = mock(ObjectReference.class);
			final ReferenceType type = mock(ReferenceType.class);
			final ReferenceType throwable = mock(ReferenceType.class);
			final Field detail = mock(Field.class);
			final StringReference text = mock(StringReference.class);
			when(exception.type()).thenReturn(type);
			when(exception.referenceType()).thenReturn(type);
			when(type.name()).thenReturn(typeName);
			when(type.allFields()).thenReturn(List.of(detail));
			when(throwable.name()).thenReturn("java.lang.Throwable");
			when(detail.name()).thenReturn("detailMessage");
			when(detail.declaringType()).thenReturn(throwable);
			when(exception.getValue(detail)).thenReturn(text);
			when(text.value()).thenReturn(message);
			return new InvocationException(exception);
		}

		/** Keeps the target text on a single warning line at the end of the original reply. */
		private void assertWarningOnce(String reply) {
			assertThat(reply).containsOnlyOnce(WARNING).containsOnlyOnce(FAILURE);
			assertThat(reply.lines().reduce((first, second) -> second).orElseThrow())
				.startsWith(WARNING.stripLeading()).contains(FAILURE);
		}

		/** Asserts the original reply prefix as well as its appended warning. */
		private void assertWarningOnce(String prefix, String reply) {
			assertThat(reply).startsWith(prefix);
			assertWarningOnce(reply);
		}
	}
}
