package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
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
import static org.mockito.Mockito.when;

/**
 * Guards the not-suspended error path of the read-only inspection tools
 * ({@link JDWPTools#jdwp_get_stack}, {@link JDWPTools#jdwp_get_locals}).
 *
 * <p>Regression context (issue #22): a thread blocked on a Java monitor
 * ({@link ThreadReference#THREAD_STATUS_MONITOR}) or inside {@code Object.wait()}
 * ({@link ThreadReference#THREAD_STATUS_WAIT}) but not JDI-suspended — the deadlock case — will
 * never stop at a breakpoint on its own. The old {@code get_stack} message ("must be stopped at a
 * breakpoint") was a dead end, and {@code get_locals} had no suspend check at all and leaked the
 * raw {@code IncompatibleThreadStateException}. Both must now point the caller at
 * {@code jdwp_suspend_thread(threadId)} — the actual deadlock-inspection path.
 */
@DisplayName("get_stack / get_locals — not-suspended inspection guard")
class JDWPToolsInspectSuspensionGuardTest {

	private JDIConnectionService jdiService;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker breakpointTracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
	}

	private ThreadReference notSuspendedThread(long id, String name, int status) {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(id);
		when(thread.name()).thenReturn(name);
		when(thread.isSuspended()).thenReturn(false);
		when(thread.status()).thenReturn(status);
		return thread;
	}

	@Nested
	@DisplayName("jdwp_get_stack")
	class GetStack {

		@Test
		@DisplayName("a MONITOR-blocked, not-suspended thread is pointed at jdwp_suspend_thread")
		void shouldPointMonitorBlockedThreadAtSuspendThread() throws Exception {
			final ThreadReference thread = notSuspendedThread(
				2644L, "transfer-A-to-B", ThreadReference.THREAD_STATUS_MONITOR);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));

			final String result = tools.jdwp_get_stack(2644L, null, null);

			assertThat(result)
				.contains("jdwp_suspend_thread(2644)")
				.contains("deadlock-inspection path")
				.doesNotContain("must be stopped at a breakpoint");
		}

		@Test
		@DisplayName("a WAIT (Object.wait) not-suspended thread is pointed at jdwp_suspend_thread")
		void shouldPointWaitingThreadAtSuspendThread() throws Exception {
			final ThreadReference thread = notSuspendedThread(
				7L, "consumer", ThreadReference.THREAD_STATUS_WAIT);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));

			final String result = tools.jdwp_get_stack(7L, null, null);

			assertThat(result)
				.contains("jdwp_suspend_thread(7)")
				.contains("Object.wait()");
		}

		@Test
		@DisplayName("a RUNNING not-suspended thread still gets a suspend_thread hint as the fallback")
		void shouldHintSuspendThreadForRunningThread() throws Exception {
			final ThreadReference thread = notSuspendedThread(
				9L, "worker", ThreadReference.THREAD_STATUS_RUNNING);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));

			final String result = tools.jdwp_get_stack(9L, null, null);

			assertThat(result)
				.startsWith("Error:")
				.contains("jdwp_suspend_thread(9)")
				.contains("RUNNING");
		}
	}

	@Nested
	@DisplayName("jdwp_get_locals")
	class GetLocals {

		@Test
		@DisplayName("a MONITOR-blocked, not-suspended thread is pointed at jdwp_suspend_thread (no raw JDI error)")
		void shouldPointMonitorBlockedThreadAtSuspendThread() throws Exception {
			final ThreadReference thread = notSuspendedThread(
				2644L, "transfer-A-to-B", ThreadReference.THREAD_STATUS_MONITOR);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));

			final String result = tools.jdwp_get_locals(2644L, 0);

			assertThat(result)
				.contains("jdwp_suspend_thread(2644)")
				.contains("deadlock-inspection path")
				.doesNotContain("IncompatibleThreadStateException");
		}
	}
}
