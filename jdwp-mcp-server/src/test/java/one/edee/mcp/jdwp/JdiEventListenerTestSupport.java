package one.edee.mcp.jdwp;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared scaffolding for tests that drive {@link JdiEventListener} with synthetic JDI events.
 * Two pieces of mocking are awkward to write inline and were originally copy-pasted between
 * {@link JdiEventListenerEvaluationSuppressionTest} and {@link JdiEventListenerExceptionLogTest}:
 * <ul>
 *   <li>{@link #runListenerWith}, which feeds one event set followed by a
 *       {@link VMDisconnectedException} sentinel so the listener loop exits cleanly after
 *       handling the test event.</li>
 *   <li>The {@link EventSet} / {@link ThreadReference} factory helpers used by every test event.</li>
 * </ul>
 *
 * <p>Test-event constructors that differ per scenario (different argument shapes for
 * {@code BreakpointEvent} / {@code ExceptionEvent}) intentionally stay in their respective test
 * files — sharing them would force every caller to pass a long chain of nullable arguments.
 */
final class JdiEventListenerTestSupport {

	private JdiEventListenerTestSupport() {
	}

	/**
	 * Drives the listener with exactly one caller-supplied {@link EventSet} followed by a
	 * {@link VMDisconnectedException} sentinel so the listener's main loop exits cleanly. A
	 * {@link CountDownLatch} fires once the second {@code queue.remove()} call has returned,
	 * meaning the event set has been fully processed (including the trailing
	 * {@code eventSet.resume()} call on the auto-resume path). A short post-await sleep lets
	 * the disconnect catch block settle before assertions.
	 */
	static void runListenerWith(JdiEventListener listener, EventSet eventSet) throws InterruptedException {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);

		BlockingQueue<Object> pending = new ArrayBlockingQueue<>(4);
		pending.put(eventSet);
		pending.put(new VMDisconnectedException());

		CountDownLatch drained = new CountDownLatch(2);
		when(queue.remove()).thenAnswer(invocation -> {
			Object next = pending.take();
			drained.countDown();
			if (next instanceof EventSet es) {
				return es;
			}
			throw (VMDisconnectedException) next;
		});

		listener.start(vm);

		assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
		// Give the listener's catch block a moment to run after the disconnect throw so the
		// loop exits cleanly before the test asserts mock-interaction state.
		Thread.sleep(30);
	}

	/**
	 * Creates an {@link EventSet} mock that iterates the given events exactly once and
	 * records calls to {@link EventSet#resume()} so the test can verify auto-resume.
	 */
	static EventSet mockEventSet(Event... events) {
		EventSet set = mock(EventSet.class);
		when(set.iterator()).thenAnswer(inv -> List.of(events).iterator());
		return set;
	}

	static ThreadReference mockThread(String name, long uniqueId) {
		ThreadReference thread = mock(ThreadReference.class);
		when(thread.name()).thenReturn(name);
		when(thread.uniqueID()).thenReturn(uniqueId);
		return thread;
	}

	/** Asserts that the most recent {@link EventHistory} entry has the expected type string. */
	static void assertLatestEventType(EventHistory eventHistory, String expectedType) {
		List<EventHistory.DebugEvent> recent = eventHistory.getRecent(5);
		assertThat(recent).isNotEmpty();
		assertThat(recent.get(recent.size() - 1).type()).isEqualTo(expectedType);
	}
}
