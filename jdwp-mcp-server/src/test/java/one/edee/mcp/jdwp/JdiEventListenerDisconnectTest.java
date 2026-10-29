package one.edee.mcp.jdwp;

import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static one.edee.mcp.jdwp.JdiEventListenerTestSupport.runListenerWithDeathEventOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When the JDI event loop observes the target VM dying or disconnecting, the listener must:
 * <ul>
 *   <li>release the {@code resume_until_event} latch so any parked waiter wakes promptly instead
 *       of hanging to timeout;</li>
 *   <li>record a {@code VM_DEATH} entry in {@link EventHistory} so {@code jdwp_resume_until_event}
 *       can detect the dead-VM wakeup;</li>
 *   <li>invoke the registered {@link JdiEventListener#setVmDeathHook(Runnable) vm-death hook}
 *       exactly once so {@link JDIConnectionService#notifyVmDied()} can drop the stale VM
 *       reference without waiting for the next MCP tool call.</li>
 * </ul>
 *
 * <p>The above contract must hold on every observation path: the disconnect-exception catch
 * branch ({@code queue.remove()} throwing {@link VMDisconnectedException}), the graceful in-loop
 * {@link VMDeathEvent} branch, and the graceful in-loop {@link VMDisconnectEvent} branch — all
 * three are exercised below. Also covers the {@code stop()} path, the no-hook-registered case,
 * and a hook that itself throws.
 */
class JdiEventListenerDisconnectTest {

	private BreakpointTracker tracker;
	private EventHistory eventHistory;
	private JdiEventListener listener;

	@BeforeEach
	void setUp() {
		tracker = new BreakpointTracker();
		eventHistory = new EventHistory();
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		listener = new JdiEventListener(tracker, eventHistory, evaluator, new EvaluationGuard(), null, new MarkedInstanceRegistry());
	}

	@AfterEach
	void tearDown() {
		listener.stop();
	}

	@Test
	@DisplayName("VMDisconnectedException path: releases jdwp_resume_until_event waiter")
	void shouldReleaseWaiterOnVmDisconnect() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);
		when(queue.remove()).thenThrow(new VMDisconnectedException());

		CountDownLatch latch = tracker.armNextEventLatch();
		listener.start(vm);

		// Listener must wake the waiter so the caller can detect the disconnect promptly.
		boolean released = latch.await(2, TimeUnit.SECONDS);
		assertThat(released).isTrue();
	}

	@Test
	@DisplayName("VMDisconnectedException path: invokes vm-death hook exactly once and records VM_DEATH")
	void shouldInvokeVmDeathHookAndRecordVmDeathOnDisconnectException() throws Exception {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);
		when(queue.remove()).thenThrow(new VMDisconnectedException());

		CountDownLatch hookFired = new CountDownLatch(1);
		AtomicInteger hookCalls = new AtomicInteger();
		listener.setVmDeathHook(() -> {
			hookCalls.incrementAndGet();
			hookFired.countDown();
		});

		listener.start(vm);

		// Wait deterministically — no polling loops.
		assertThat(hookFired.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(hookCalls.get()).isEqualTo(1);
		assertThat(eventHistory.getRecent(1))
			.singleElement()
			.extracting(EventHistory.DebugEvent::type)
			.isEqualTo("VM_DEATH");
	}

	@Test
	@DisplayName("Graceful VMDeathEvent path: invokes vm-death hook exactly once and records VM_DEATH")
	void shouldInvokeVmDeathHookAndRecordVmDeathOnGracefulVmDeathEvent() throws Exception {
		CountDownLatch hookFired = new CountDownLatch(1);
		AtomicInteger hookCalls = new AtomicInteger();
		listener.setVmDeathHook(() -> {
			hookCalls.incrementAndGet();
			hookFired.countDown();
		});

		runListenerWithDeathEventOnly(listener, VMDeathEvent.class);

		assertThat(hookFired.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(hookCalls.get()).isEqualTo(1);
		assertThat(eventHistory.getRecent(1))
			.singleElement()
			.extracting(EventHistory.DebugEvent::type)
			.isEqualTo("VM_DEATH");
	}

	@Test
	@DisplayName("Graceful VMDisconnectEvent path: invokes vm-death hook exactly once and records VM_DEATH")
	void shouldInvokeVmDeathHookAndRecordVmDeathOnGracefulVmDisconnectEvent() throws Exception {
		CountDownLatch hookFired = new CountDownLatch(1);
		AtomicInteger hookCalls = new AtomicInteger();
		listener.setVmDeathHook(() -> {
			hookCalls.incrementAndGet();
			hookFired.countDown();
		});

		runListenerWithDeathEventOnly(listener, VMDisconnectEvent.class);

		assertThat(hookFired.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(hookCalls.get()).isEqualTo(1);
		assertThat(eventHistory.getRecent(1))
			.singleElement()
			.extracting(EventHistory.DebugEvent::type)
			.isEqualTo("VM_DEATH");
	}

	@Test
	@DisplayName("Graceful death path: also releases jdwp_resume_until_event waiter")
	void shouldReleaseWaiterOnGracefulVmDeathEvent() throws Exception {
		CountDownLatch latch = tracker.armNextEventLatch();

		runListenerWithDeathEventOnly(listener, VMDeathEvent.class);

		// fireNextEvent() runs on the in-loop death branch before the listener exits.
		assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	@DisplayName("Hook that throws does not break VM_DEATH recording or listener termination")
	void shouldNotThrowWhenVmDeathHookItselfThrows() throws Exception {
		// Covers the swallow-on-failure contract in invokeVmDeathHook: a buggy hook implementation
		// must not stop VM_DEATH from being recorded or break the listener's normal exit path.
		listener.setVmDeathHook(() -> {
			throw new RuntimeException("hook failure");
		});

		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);
		when(queue.remove()).thenThrow(new VMDisconnectedException());

		CountDownLatch latch = tracker.armNextEventLatch();
		listener.start(vm);

		// The latch must still be released and VM_DEATH must still be recorded — the hook failure
		// is swallowed.
		assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(eventHistory.getRecent(1))
			.singleElement()
			.extracting(EventHistory.DebugEvent::type)
			.isEqualTo("VM_DEATH");
	}

	@Test
	@DisplayName("No hook registered: VM_DEATH still recorded, listener exits cleanly")
	void shouldBeNoopWhenNoVmDeathHookRegistered() throws Exception {
		// No setVmDeathHook() call — the listener must still exit cleanly through the
		// invokeVmDeathHook() no-op branch and still record VM_DEATH.
		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);
		when(queue.remove()).thenThrow(new VMDisconnectedException());

		CountDownLatch latch = tracker.armNextEventLatch();
		listener.start(vm);

		assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(eventHistory.getRecent(1))
			.singleElement()
			.extracting(EventHistory.DebugEvent::type)
			.isEqualTo("VM_DEATH");
	}

	@Test
	@DisplayName("stop() on a parked listener fires the latch and invokes the death hook exactly once")
	void shouldFireLatchAndInvokeHookWhenStopInterruptsParkedListener() throws Exception {
		// stop() is the user-initiated VM-death path. It must wake any jdwp_resume_until_event
		// waiter and invoke the vm-death hook so JDIConnectionService.notifyVmDied()-style cleanup
		// can run promptly instead of being deferred to the next MCP tool call. The CAS gate
		// inside invokeVmDeathHook() collapses the listener thread's own InterruptedException-path
		// hook call with stop()'s safety-net call so the hook still fires exactly once.
		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);
		// queue.remove() parks indefinitely on a real listener thread until interrupted by stop().
		CountDownLatch enteredRemove = new CountDownLatch(1);
		when(queue.remove()).thenAnswer(invocation -> {
			enteredRemove.countDown();
			Thread.sleep(Long.MAX_VALUE);
			return null;
		});

		AtomicInteger hookCalls = new AtomicInteger();
		listener.setVmDeathHook(hookCalls::incrementAndGet);
		CountDownLatch waiter = tracker.armNextEventLatch();

		listener.start(vm);
		assertThat(enteredRemove.await(1, TimeUnit.SECONDS)).isTrue();

		listener.stop();

		assertThat(waiter.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(hookCalls.get()).isEqualTo(1);
	}
}
