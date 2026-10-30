package one.edee.mcp.jdwp;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A model of the JDWP agent's suspend bookkeeping (jdk21u {@code threadControl.c},
 * {@code invoker.c}, {@code eventHelper.c}) behind JDI mocks, so tests of suspension ownership
 * assert the counts the target would really have instead of which calls were made.
 * <p>
 * What it models:
 * <ul>
 *   <li>per-thread suspend counts; a resume at count 0 is a no-op ({@code resumeThreadByNode});</li>
 *   <li>{@code vm.suspend()/resume()} with the global {@code suspendAllCount}, which a thread started
 *       meanwhile inherits ({@code insertThread});</li>
 *   <li>an event: its thread becomes invokable ({@code suspendWithInvokeEnabled}); policy ALL
 *       suspends every thread, EVENT_THREAD only its thread, NONE nothing. {@link EventSet#resume()}
 *       undoes exactly that by policy;</li>
 *   <li>a single-threaded invocation: refused unless the thread is invokable and suspended; it
 *       borrows one count when it starts and restores it when it completes ({@code invoker.c:332,
 *       740}); a thread that runs outside an invocation loses its invokability ({@code invoker.c:650});</li>
 *   <li>thread status, for the invocation watchdog.</li>
 * </ul>
 */
final class FakeSuspendModel {

	/**
	 * One target thread.
	 */
	final class FakeThread {
		final String name;
		final ThreadReference ref;
		int count;
		boolean invokable;
		int loans;
		int status = ThreadReference.THREAD_STATUS_RUNNING;

		private FakeThread(String name, long id) {
			this.name = name;
			this.ref = mock(ThreadReference.class);
			when(ref.name()).thenReturn(name);
			when(ref.uniqueID()).thenReturn(id);
			when(ref.isSuspended()).thenAnswer(invocation -> count > 0);
			when(ref.suspendCount()).thenAnswer(invocation -> count);
			when(ref.status()).thenAnswer(invocation -> status);
			when(ref.virtualMachine()).thenAnswer(invocation -> vm);
			doAnswer(invocation -> {
				count++;
				return null;
			}).when(ref).suspend();
			doAnswer(invocation -> {
				resumeOnce(this);
				return null;
			}).when(ref).resume();
		}

		/**
		 * Whether the thread runs application code now.
		 */
		boolean running() {
			return count == 0;
		}

		@Override
		public String toString() {
			return name + "(count=" + count + (invokable ? ", invokable" : "") + (loans > 0 ? ", invoking" : "") + ')';
		}
	}

	final VirtualMachine vm = mock(VirtualMachine.class);
	private final Map<ThreadReference, FakeThread> threads = new LinkedHashMap<>();
	private long nextId = 1;
	int suspendAllCount;

	FakeSuspendModel() {
		when(vm.allThreads()).thenAnswer(invocation -> {
			final List<ThreadReference> refs = new ArrayList<>();
			for (FakeThread thread : threads.values()) {
				refs.add(thread.ref);
			}
			return refs;
		});
		doAnswer(invocation -> {
			suspendAll();
			return null;
		}).when(vm).suspend();
		doAnswer(invocation -> {
			resumeAll();
			return null;
		}).when(vm).resume();
	}

	/**
	 * Starts a thread. It inherits the global suspend-all count, as a thread started while the VM is
	 * suspended by the debugger does.
	 */
	FakeThread thread(String name) {
		final FakeThread thread = new FakeThread(name, nextId++);
		thread.count = suspendAllCount;
		threads.put(thread.ref, thread);
		return thread;
	}

	FakeThread of(ThreadReference ref) {
		final FakeThread thread = threads.get(ref);
		if (thread == null) {
			throw new IllegalArgumentException("unknown thread " + ref);
		}
		return thread;
	}

	/**
	 * The target raises a breakpoint event on {@code thread} with {@code policy}: the thread becomes
	 * invokable and the policy's suspension is applied. The returned set's {@code resume()} undoes
	 * that suspension the way JDI's {@code EventSetImpl.resume()} does.
	 */
	EventSet breakpointEvent(FakeThread thread, int policy) {
		return breakpointEvent(thread, policy, null);
	}

	/**
	 * {@link #breakpointEvent(FakeThread, int)} whose event reports {@code request}.
	 */
	EventSet breakpointEvent(FakeThread thread, int policy, @Nullable EventRequest request) {
		thread.invokable = true;
		switch (policy) {
			case EventRequest.SUSPEND_ALL -> suspendAll();
			case EventRequest.SUSPEND_EVENT_THREAD -> thread.count++;
			default -> {
				// SUSPEND_NONE suspends nothing.
			}
		}
		final BreakpointEvent event = mock(BreakpointEvent.class);
		when(event.thread()).thenReturn(thread.ref);
		if (request instanceof BreakpointRequest breakpointRequest) {
			when(event.request()).thenReturn(breakpointRequest);
		}
		final EventSet set = mock(EventSet.class);
		when(set.suspendPolicy()).thenReturn(policy);
		when(set.iterator()).thenAnswer(invocation -> List.<Event>of(event).iterator());
		doAnswer(invocation -> {
			switch (policy) {
				case EventRequest.SUSPEND_ALL -> resumeAll();
				case EventRequest.SUSPEND_EVENT_THREAD -> resumeOnce(thread);
				default -> {
					// Nothing to undo.
				}
			}
			return null;
		}).when(set).resume();
		return set;
	}

	/**
	 * Starts a single-threaded invocation on {@code thread}: it must be invokable, suspended and not
	 * invoking already (the agent runs one invocation per thread); it borrows one count. The thread
	 * runs the invoked code only while its count is 0.
	 *
	 * @throws IncompatibleThreadStateException as JDI reports {@code INVALID_THREAD}
	 */
	void beginInvocation(FakeThread thread) throws IncompatibleThreadStateException {
		if (!thread.invokable || thread.count == 0 || thread.loans > 0) {
			throw new IncompatibleThreadStateException();
		}
		thread.loans++;
		thread.count--;
	}

	/**
	 * Completes an invocation on {@code thread}: the agent suspends it once more before the reply.
	 * The invoked code must be able to run to get there — a test that completes an invocation whose
	 * thread is still suspended would manufacture the very progress under test.
	 */
	void completeInvocation(FakeThread thread) {
		if (thread.loans == 0) {
			throw new IllegalStateException(thread.name + " has no invocation in flight");
		}
		if (thread.count > 0) {
			throw new IllegalStateException(thread.name + " is suspended (count " + thread.count + ") — its invocation "
				+ "cannot complete");
		}
		thread.loans--;
		thread.count++;
	}

	/**
	 * Whether {@code thread}'s invoked code can make progress: an invocation is in flight and the
	 * thread is not suspended by anything else.
	 */
	boolean invocationRuns(FakeThread thread) {
		return thread.loans > 0 && thread.count == 0;
	}

	/**
	 * Native suspend counts of every thread, by name, for assertions.
	 */
	Map<String, Integer> counts() {
		final Map<String, Integer> counts = new LinkedHashMap<>();
		for (FakeThread thread : threads.values()) {
			counts.put(thread.name, thread.count);
		}
		return counts;
	}

	private void suspendAll() {
		suspendAllCount++;
		for (FakeThread thread : threads.values()) {
			thread.count++;
		}
	}

	private void resumeAll() {
		for (FakeThread thread : threads.values()) {
			resumeOnce(thread);
		}
		if (suspendAllCount > 0) {
			suspendAllCount--;
		}
	}

	private void resumeOnce(FakeThread thread) {
		if (thread.count == 0) {
			return;
		}
		thread.count--;
		if (thread.count == 0 && thread.loans == 0) {
			// It runs application code again; only a new event makes it invokable.
			thread.invokable = false;
		}
	}
}
