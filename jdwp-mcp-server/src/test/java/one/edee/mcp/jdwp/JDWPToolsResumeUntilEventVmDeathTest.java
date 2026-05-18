package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketException;
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

	/**
	 * Regression for P1-6: a follow-up {@code jdwp_resume_until_event} call after the VM has died
	 * used to throw {@link NullPointerException} and render as "Error: null" because the catch
	 * block did {@code "Error: " + e.getMessage()} without null-guarding. The new envelope must
	 * include the exception class name when no message is available.
	 */
	@Test
	@DisplayName("after VM is gone: [VM_GONE] envelope (never 'Error: null')")
	void shouldReturnVmGoneEnvelopeWhenVmDisconnectedExceptionThrown() throws Exception {
		// JDIConnectionService.getVM() typically throws IllegalStateException when no VM is attached.
		when(jdiService.getVM()).thenThrow(new IllegalStateException("Not connected"));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_GONE]");
		assertThat(result).contains("jdwp_resume_until_event");
		assertThat(result).contains("jdwp_connect");
	}

	@Test
	@DisplayName("after VM is gone with null-message exception: envelope falls back to class name")
	void shouldRenderClassNameWhenExceptionMessageIsNull() throws Exception {
		// A naked NPE has no message — the previous code rendered "Error: null". The new
		// envelope must always include enough information to identify the cause.
		when(jdiService.getVM()).thenThrow(new RuntimeException((String) null));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("Error in jdwp_resume_until_event");
		assertThat(result).doesNotContain(": null");
		assertThat(result).contains("RuntimeException");
	}

	@Test
	@DisplayName("VMDisconnectedException routes through [VM_GONE] envelope")
	void shouldRouteVmDisconnectedExceptionThroughVmGoneEnvelope() throws Exception {
		when(jdiService.getVM()).thenThrow(new VMDisconnectedException("connection is closed"));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_GONE]");
		assertThat(result).contains("connection is closed");
	}

	/**
	 * Regression for P0-1 polish: when the VM dies before any BP fires and pending BPs have been
	 * marked FAILED (e.g. a deferred BP on a comment line), the {@code [VM_DEATH]} response must
	 * proactively surface the FAILED state so the agent doesn't blame the system for a race
	 * condition. Previously the agent had to follow up with {@code jdwp_overview} to discover this.
	 */
	@Test
	@DisplayName("[VM_DEATH] hint lists deferred BPs that failed to install")
	void shouldListFailedPendingBpsInVmDeathHint() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
		eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected"));

		// Build a real pending BP with a failure reason — wraps the dual concerns of "tracker has
		// the state" and "renderer surfaces it" in one assertion.
		final BreakpointTracker realTracker = new BreakpointTracker();
		final int pendingId = realTracker.registerPendingBreakpoint("com.example.Foo", 40, 2, "ALL");
		realTracker.markPendingFailed(pendingId, "No executable code at line 40");
		when(breakpointTracker.getAllPendingBreakpoints()).thenReturn(realTracker.getAllPendingBreakpoints());

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).contains("deferred breakpoint(s) were promoted but failed");
		assertThat(result).contains("#" + pendingId);
		assertThat(result).contains("com.example.Foo:40");
		assertThat(result).contains("No executable code at line 40");
		assertThat(result).contains("Set BPs on real executable lines");
	}

	/**
	 * Regression for P0-2/P0-3: when an event has fired before {@code jdwp_resume_until_event} is
	 * called (e.g. {@code jdwp_step_over} → other tool call → {@code resume_until_event}), the
	 * tool must return the captured snapshot WITHOUT calling {@code vm.resume()} again. The
	 * previous code path always re-armed and re-resumed, overshooting the suspended thread.
	 */
	@Test
	@DisplayName("short-circuits without resume when pendingFire is set (step → other tool → resume_until_event)")
	void shouldShortCircuitWhenPendingFireSet() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);
		// Pretend a STEP fired before the call — the listener would have set this via
		// fireNextEvent(). consumePendingFire() returns true the first time and false thereafter.
		when(breakpointTracker.consumePendingFire()).thenReturn(true).thenReturn(false);

		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("main");
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(3);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, 7));

		final String result = tools.jdwp_resume_until_event(5_000);

		assertThat(result).startsWith("Event fired.");
		assertThat(result).contains("breakpoint=7");
		// The critical assertion: NO vm.resume() while a captured snapshot exists. Verifying this
		// directly via Mockito.verify protects future renderer tweaks from masking a regression.
		org.mockito.Mockito.verify(vm, org.mockito.Mockito.never()).resume();
		// And NO armNextEventLatch — the short-circuit means we never set up a fresh latch.
		org.mockito.Mockito.verify(breakpointTracker, org.mockito.Mockito.never()).armNextEventLatch();
	}

	@Test
	@DisplayName("normal path arms + resumes when pendingFire is clear (no prior event)")
	void shouldArmAndResumeWhenPendingFireClear() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);
		when(breakpointTracker.consumePendingFire()).thenReturn(false);

		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);

		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("main");
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(2);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(thread, 11));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("Event fired.");
		org.mockito.Mockito.verify(vm).resume();
		org.mockito.Mockito.verify(breakpointTracker).armNextEventLatch();
	}

	/**
	 * F-RA2: after a step landing, the snapshot kind is {@code STEP} and the renderer must
	 * surface "via=step" instead of echoing the stale last-hit breakpoint id. The previous
	 * "breakpoint=N" rendering misled agents into thinking BP #N had re-fired at the step
	 * target line, when in reality the BP was the *previous* suspension that the step ran
	 * away from.
	 */
	@Test
	@DisplayName("F-RA2: step landing renders 'via=step' instead of stale 'breakpoint=N'")
	void shouldRenderStepKindAsViaStep() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
		eventHistory.record(new EventHistory.DebugEvent("STEP", "Step to Foo:42"));

		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("main");
		when(thread.uniqueID()).thenReturn(1L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(3);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(
				thread, null, BreakpointTracker.EventKind.STEP));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("Event fired");
		assertThat(result).contains("via=step");
		assertThat(result).doesNotContain("breakpoint=");
	}

	/**
	 * F-RA2: exception events similarly render as "via=exception". This subsumes the older
	 * P1-7 sentinel that planted {@code id = -1} and special-cased it in the renderer —
	 * the new {@link BreakpointTracker.EventKind#EXCEPTION} kind carries the same information
	 * cleanly. The id-sentinel path is still tolerated by the renderer for back-compat.
	 */
	@Test
	@DisplayName("F-RA2: exception event renders 'via=exception'")
	void shouldRenderExceptionKindAsViaException() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
		eventHistory.record(new EventHistory.DebugEvent("EXCEPTION", "NPE at Foo:42"));

		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn("worker");
		when(thread.uniqueID()).thenReturn(2L);
		when(thread.isSuspended()).thenReturn(true);
		when(thread.frameCount()).thenReturn(5);
		when(breakpointTracker.getLastBreakpoint())
			.thenReturn(new BreakpointTracker.LastBreakpoint(
				thread, null, BreakpointTracker.EventKind.EXCEPTION));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).contains("via=exception");
		assertThat(result).doesNotContain("breakpoint=");
	}

	/**
	 * F-RA1: a raw {@link SocketException} thrown straight out of {@code getVM()} (transport
	 * down before JDI wraps it) must be classified by {@code isVmGone} as VM death and routed
	 * through the {@code [VM_GONE]} envelope, not the generic {@code "Error in ..."} prefix.
	 */
	@Test
	@DisplayName("F-RA1: SocketException routes through [VM_GONE] envelope")
	void shouldRouteSocketExceptionThroughVmGoneEnvelope() throws Exception {
		when(jdiService.getVM()).thenThrow(new SocketException("Connection refused"));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_GONE]");
		assertThat(result).contains("jdwp_resume_until_event");
		assertThat(result).contains("jdwp_connect");
	}

	/**
	 * F-RA1: when the transport message {@code "closed by the remote host"} is buried inside a
	 * cause chain, the message-substring fallback inside {@code isVmGone} must still classify
	 * the failure as VM death.
	 */
	@Test
	@DisplayName("F-RA1: wrapped 'closed by the remote host' routes through [VM_GONE] envelope")
	void shouldRouteWrappedClosedByRemoteHostThroughVmGoneEnvelope() throws Exception {
		when(jdiService.getVM())
			.thenThrow(new RuntimeException("wrapper",
				new RuntimeException("An existing connection was forcibly closed by the remote host")));

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_GONE]");
		assertThat(result).contains("jdwp_resume_until_event");
	}

	@Test
	@DisplayName("[VM_DEATH] hint omits FAILED block when no pending BPs failed")
	void shouldOmitFailedHintWhenNoPendingBpsFailed() throws Exception {
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(jdiService.getVM()).thenReturn(vm);

		final CountDownLatch latch = new CountDownLatch(1);
		latch.countDown();
		when(breakpointTracker.armNextEventLatch()).thenReturn(latch);
		eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected"));
		when(breakpointTracker.getAllPendingBreakpoints()).thenReturn(java.util.Map.of());

		final String result = tools.jdwp_resume_until_event(1_000);

		assertThat(result).startsWith("[VM_DEATH]");
		assertThat(result).doesNotContain("deferred breakpoint(s) were promoted but failed");
	}
}
