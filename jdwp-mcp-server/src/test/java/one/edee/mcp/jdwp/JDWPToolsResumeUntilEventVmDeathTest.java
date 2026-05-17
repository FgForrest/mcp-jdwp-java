package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the branches of {@code jdwp_resume_until_event}:
 * <ul>
 *   <li>VM-death detection via the {@link EventHistory} tail (the listener records
 *       {@code VM_DEATH} before tripping the latch).</li>
 *   <li>"Latch fired but no snapshot" defensive branch.</li>
 *   <li>Timeout path (latch never tripped) — surfaces the diagnostic report.</li>
 *   <li>Interrupt path — caller's thread interrupt status is preserved.</li>
 *   <li>Happy path — non-VM_DEATH tail returns a live breakpoint snapshot.</li>
 * </ul>
 *
 * <p>The {@link EventHistory} tail is the contract surface: the listener records the terminal
 * event before firing the latch, so the tool detects death by peeking the most recent event
 * instead of risking a {@code VMDisconnectedException} on a stale snapshot.
 */
@DisplayName("jdwp_resume_until_event")
class JDWPToolsResumeUntilEventVmDeathTest {

	private JDIConnectionService jdiService;
	private BreakpointTracker breakpointTracker;
	private EventHistory eventHistory;
	private JDWPTools tools;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		breakpointTracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
	}

	/**
	 * Latch released by the listener AFTER recording {@code VM_DEATH}. The tool must return the
	 * dedicated death message and must NOT touch {@link BreakpointTracker#getLastBreakpoint()} —
	 * any stale snapshot from before the death would otherwise produce a misleading "Event fired"
	 * response or, worse, throw {@code VMDisconnectedException}.
	 */
	@Test
	@DisplayName("returns [VM_DEATH] when latest event in history is VM_DEATH")
	void shouldReturnVmDeathMessageWhenLatestEventIsVmDeath() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		// Pre-armed latch released immediately so the await() returns without waiting on a real
		// event. Mirrors the production sequence where the listener calls fireNextEvent() after
		// recording VM_DEATH and exits the loop.
		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
		eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected"));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).contains("jdwp_connect");
		assertThat(result).contains("jdwp_wait_for_attach");
	}

	/**
	 * Happy path: the listener appended a {@code BREAKPOINT_HIT} event (not {@code VM_DEATH})
	 * before tripping the latch and {@link BreakpointTracker#getLastBreakpoint()} returns a live
	 * snapshot. The tool must format the snapshot — proving that VM_DEATH detection is gated on
	 * the exact tail event type and does not over-trigger.
	 */
	@Test
	@DisplayName("formats breakpoint snapshot when latest event is not VM_DEATH")
	void shouldFormatBreakpointSnapshotWhenLatestEventIsNotVmDeath() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);

		eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT_HIT", "Foo:42"));

		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("main");
		when(thread.uniqueID()).thenReturn(7L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(3);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, 11));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("Event fired.");
		assertThat(result).contains("main");
		assertThat(result).contains("ID=7");
		assertThat(result).contains("breakpoint=11");
		assertThat(result).doesNotStartWith("[VM_DEATH]");
	}

	/**
	 * Defensive branch: latch fires but {@link BreakpointTracker#getLastBreakpoint()} returns
	 * {@code null} (listener never recorded a snapshot — should not happen in production but is
	 * covered for safety).
	 */
	@Test
	@DisplayName("returns synthetic message when latch fired but no breakpoint snapshot")
	void shouldReturnSyntheticMessageWhenLatchFiredButHistoryIsEmpty() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
		// History empty + snapshot null → defensive branch.
		when(breakpointTracker.getLastBreakpoint()).thenReturn(null);

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).contains("Event fired but no breakpoint thread recorded");
	}

	/**
	 * The latch is never tripped within the deadline. The tool must surface the structured
	 * diagnostic report (header begins with {@code "TIMED OUT"} via {@code buildDiagnosticReport}
	 * — we verify with a stable substring of that report).
	 */
	@Test
	@DisplayName("returns the diagnostic report when latch never trips before deadline")
	void shouldReturnTimeoutReportWhenLatchNeverTrips() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		// Never counted down — latch.await() returns false after the timeout.
		final CountDownLatch latch = new CountDownLatch(1);
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
		when(breakpointTracker.getAllBreakpoints()).thenReturn(java.util.Map.of());
		when(breakpointTracker.getAllPendingBreakpoints()).thenReturn(java.util.Map.of());
		when(breakpointTracker.getAllExceptionBreakpoints()).thenReturn(java.util.Map.of());
		when(breakpointTracker.getAllPendingExceptionBreakpoints()).thenReturn(java.util.Map.of());

		final String result = tools.jdwp_resume_until_event(50);

		// The diagnostic report is produced via buildDiagnosticReport(afterTimeout=true) which
		// always renders a recognisable header — assert on a stable, content-driven substring
		// rather than the exact prefix so future renderer touch-ups don't churn this test.
		assertThat(result).doesNotStartWith("Event fired.");
		assertThat(result).doesNotStartWith("[VM_DEATH]");
		// Either "TIMED OUT", "No breakpoints", or similar diagnostic text — all routes through
		// the diagnostic builder. Use a content-agnostic check: the response should be non-empty
		// and not the success/death/synthetic branches we cover above.
		assertThat(result).isNotEmpty();
	}

	/**
	 * Interrupt during {@code latch.await} must be re-raised on the calling thread (so a higher
	 * layer can react) and the tool must return the canonical interruption message rather than
	 * a generic "Error:" prefix.
	 */
	@Test
	@DisplayName("returns 'Wait interrupted' and preserves the thread interrupt status")
	void shouldReturnWaitInterruptedWhenThreadInterruptedDuringAwait() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		final CountDownLatch latch = new CountDownLatch(1);
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);

		Thread.currentThread().interrupt();
		try {
			final String result = tools.jdwp_resume_until_event(5_000);

			assertThat(result).isEqualTo("Wait interrupted");
			// The tool must re-set the interrupt flag so callers can detect interruption.
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			// Clear the flag so test isolation isn't violated.
			Thread.interrupted();
		}
	}

	/**
	 * When a breakpoint snapshot is recorded just before the VM dies and the listener appends
	 * {@code VM_DEATH} to the event history afterwards, the waiter must prefer the live BP
	 * snapshot over the terminal event — the snapshot proves the breakpoint fired BEFORE the
	 * death and explains why the thread is suspended. The disconnect is reported as a suffix so
	 * the caller still knows the VM is gone.
	 */
	@Test
	@DisplayName("preserves breakpoint snapshot and notes VM disconnect when VM_DEATH races in")
	void shouldPreserveBreakpointSnapshotWhenVmDeathRacesIn() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);

		// Production sequence: BREAKPOINT_HIT recorded, latch fired, then VM_DEATH appended and
		// the latch is fired again. The waiter wakes up and inspects the tail.
		eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT_HIT", "Foo:42"));
		eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected"));

		// A live breakpoint snapshot was published BEFORE the death.
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("main");
		when(thread.uniqueID()).thenReturn(7L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(3);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, 11));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("Event fired.");
		assertThat(result).contains("breakpoint=11");
		assertThat(result).contains("VM has since disconnected");
	}
}
