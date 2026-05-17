package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.StepRequest;
import org.jspecify.annotations.Nullable;

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
 * Centralises the listener-driving loop and the JDI mock factories (events / threads / exceptions)
 * so individual test classes only contain scenario logic, not mocking boilerplate.
 *
 * <p>Two listener-driving helpers are provided:
 * <ul>
 *   <li>{@link #runListenerWith} — feeds one event set followed by a {@link VMDisconnectedException}
 *       sentinel so the listener's catch-block exits cleanly. Use this for scenarios where the
 *       behaviour under test is recorded BEFORE the listener loop terminates.</li>
 *   <li>{@link #runListenerWithEventThenDeathEvent} — feeds one event set followed by a synthesised
 *       death event (a real {@link com.sun.jdi.event.VMDeathEvent} or
 *       {@link com.sun.jdi.event.VMDisconnectEvent}) inside a second event set, so the listener
 *       exits via the graceful in-loop branch rather than the exception catch. Required for tests
 *       that target the graceful death path.</li>
 * </ul>
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
	 * Drives the listener with one or more caller-supplied {@link EventSet}s. The first set the
	 * listener consumes is the caller's; the last set is always a synthesised death-event set
	 * (containing one event of {@code deathEventClass} — {@link com.sun.jdi.event.VMDeathEvent}
	 * or {@link com.sun.jdi.event.VMDisconnectEvent}). This exits the listener loop through the
	 * graceful in-loop branch at {@link JdiEventListener} where
	 * {@code event instanceof VMDisconnectEvent || event instanceof VMDeathEvent}, NOT through
	 * the {@link VMDisconnectedException} catch — necessary to exercise the in-loop death path
	 * independently of the disconnect-exception path.
	 *
	 * <p>If {@code preDeathEventSet} is null, only the death set is supplied — useful when the
	 * test targets the death path itself with no preceding "real" event.
	 */
	static void runListenerWithEventThenDeathEvent(JdiEventListener listener,
			@Nullable EventSet preDeathEventSet,
			Class<? extends Event> deathEventClass) throws InterruptedException {
		VirtualMachine vm = mock(VirtualMachine.class);
		EventQueue queue = mock(EventQueue.class);
		when(vm.eventQueue()).thenReturn(queue);

		Event deathEvent = mock(deathEventClass);
		EventSet deathSet = mockEventSet(deathEvent);

		BlockingQueue<EventSet> pending = new ArrayBlockingQueue<>(4);
		int expectedDrains = 1;
		if (preDeathEventSet != null) {
			pending.put(preDeathEventSet);
			expectedDrains = 2;
		}
		pending.put(deathSet);

		CountDownLatch drained = new CountDownLatch(expectedDrains);
		when(queue.remove()).thenAnswer(invocation -> {
			EventSet next = pending.take();
			drained.countDown();
			return next;
		});

		listener.start(vm);

		assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
		// Give the listener thread a moment to complete its post-event-set bookkeeping (hook
		// invocation, history record, latch firing) before the test reads observable state.
		Thread.sleep(30);
	}

	/**
	 * Drives the listener with a single {@link EventSet} containing one death event — no
	 * preceding "real" event. Convenience overload over
	 * {@link #runListenerWithEventThenDeathEvent} for tests that target the death path itself.
	 */
	static void runListenerWithDeathEventOnly(JdiEventListener listener,
			Class<? extends Event> deathEventClass) throws InterruptedException {
		runListenerWithEventThenDeathEvent(listener, null, deathEventClass);
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

	/**
	 * Builds a {@link BreakpointEvent} mock wired to the given request, thread, class name and
	 * line number. The {@link Location} chain is built so that
	 * {@code event.location().declaringType().name()} returns {@code className} and
	 * {@code event.location().lineNumber()} returns {@code line}.
	 */
	static BreakpointEvent mockBreakpointEvent(ThreadReference thread, BreakpointRequest request,
			String className, int line) {
		BreakpointEvent event = mock(BreakpointEvent.class);
		when(event.request()).thenReturn(request);
		when(event.thread()).thenReturn(thread);
		Location location = mock(Location.class);
		ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn(className);
		when(location.declaringType()).thenReturn(declaringType);
		when(location.lineNumber()).thenReturn(line);
		when(event.location()).thenReturn(location);
		return event;
	}

	/**
	 * Builds a {@link StepEvent} mock wired to the given request, thread, class name and line.
	 * Mirrors {@link #mockBreakpointEvent} but for the step path.
	 */
	static StepEvent mockStepEvent(ThreadReference thread, StepRequest request, String className, int line) {
		StepEvent event = mock(StepEvent.class);
		when(event.request()).thenReturn(request);
		when(event.thread()).thenReturn(thread);
		Location location = mock(Location.class);
		ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn(className);
		when(location.declaringType()).thenReturn(declaringType);
		when(location.lineNumber()).thenReturn(line);
		when(event.location()).thenReturn(location);
		return event;
	}

	/**
	 * Builds an {@link ExceptionEvent} mock wired to the given request, thread, exception and
	 * throw location. The catch location is set to {@code null} (uncaught) — for tests that need
	 * a non-null catch location, use {@link #mockExceptionEvent(ExceptionRequest, ThreadReference,
	 * ObjectReference, String, int, String, int)}.
	 */
	static ExceptionEvent mockExceptionEvent(@Nullable ExceptionRequest request, ThreadReference thread,
			ObjectReference exception, String declaringClassName, int throwLine) {
		return mockExceptionEvent(request, thread, exception, declaringClassName, throwLine, null, 0);
	}

	/**
	 * Full {@link ExceptionEvent} factory. Pass {@code catchClassName == null} to leave the catch
	 * location null (uncaught path); otherwise both arguments are used to build a non-null catch
	 * location so the formatter records {@code "catchClassName:catchLine"} in the event details.
	 */
	static ExceptionEvent mockExceptionEvent(@Nullable ExceptionRequest request, ThreadReference thread,
			ObjectReference exception, String declaringClassName, int throwLine,
			@Nullable String catchClassName, int catchLine) {
		ExceptionEvent event = mock(ExceptionEvent.class);
		when(event.request()).thenReturn(request);
		when(event.thread()).thenReturn(thread);
		when(event.exception()).thenReturn(exception);

		Location throwLocation = mock(Location.class);
		ReferenceType declaringType = mock(ReferenceType.class);
		when(declaringType.name()).thenReturn(declaringClassName);
		when(throwLocation.declaringType()).thenReturn(declaringType);
		when(throwLocation.lineNumber()).thenReturn(throwLine);
		when(event.location()).thenReturn(throwLocation);

		if (catchClassName != null) {
			Location catchLocation = mock(Location.class);
			ReferenceType catchType = mock(ReferenceType.class);
			when(catchType.name()).thenReturn(catchClassName);
			when(catchLocation.declaringType()).thenReturn(catchType);
			when(catchLocation.lineNumber()).thenReturn(catchLine);
			when(event.catchLocation()).thenReturn(catchLocation);
		} else {
			when(event.catchLocation()).thenReturn(null);
		}

		return event;
	}

	/**
	 * Builds an {@link ObjectReference} mock whose {@code referenceType().name()} returns the
	 * given fully-qualified exception class name. Used by exception-event tests to wire the
	 * thrown object that {@code ExceptionEvent.exception()} returns.
	 */
	static ObjectReference mockException(String exceptionType) {
		ObjectReference exception = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn(exceptionType);
		when(exception.referenceType()).thenReturn(refType);
		return exception;
	}

	/**
	 * Asserts that the most recent meaningful {@link EventHistory} entry has the expected type string.
	 * Trailing {@code VM_DEATH} entries are ignored because {@link #runListenerWith} terminates the
	 * listener loop by throwing {@code VMDisconnectedException}, which the production code records
	 * as {@code VM_DEATH} so {@code jdwp_resume_until_event} can detect the dead-VM wakeup. That
	 * recording is an artefact of the test harness, not of the event under test.
	 */
	static void assertLatestEventType(EventHistory eventHistory, String expectedType) {
		assertThat(latestMeaningfulEvent(eventHistory).type()).isEqualTo(expectedType);
	}

	/**
	 * Returns the most recent non-{@code VM_DEATH} entry. Use this when a test needs to inspect
	 * both the {@code type} and the {@code summary}/{@code details} of the event under test —
	 * raw access to {@code getRecent(N).get(last)} would otherwise pick up the harness's
	 * disconnect-sentinel {@code VM_DEATH} entry. Fails the test if the history holds nothing but
	 * VM_DEATH entries.
	 */
	static EventHistory.DebugEvent latestMeaningfulEvent(EventHistory eventHistory) {
		List<EventHistory.DebugEvent> recent = eventHistory.getRecent(10);
		assertThat(recent).isNotEmpty();
		EventHistory.DebugEvent latest = null;
		for (int i = recent.size() - 1; i >= 0; i--) {
			if (!"VM_DEATH".equals(recent.get(i).type())) {
				latest = recent.get(i);
				break;
			}
		}
		assertThat(latest)
			.as("expected a non-VM_DEATH event in the recent history")
			.isNotNull();
		return latest;
	}
}
