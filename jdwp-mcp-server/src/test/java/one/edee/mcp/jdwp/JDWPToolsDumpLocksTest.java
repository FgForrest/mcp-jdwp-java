package one.edee.mcp.jdwp;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link JDWPTools#jdwp_dump_locks} — the monitor wait-for graph and deadlock reporting.
 * The JDI surface (contended monitors and their owning threads) is mocked, so these exercise the
 * graph assembly and rendering, including that the snapshot suspend/resume is balanced.
 */
@DisplayName("jdwp_dump_locks — monitor graph + deadlock detection")
class JDWPToolsDumpLocksTest {

	private JDIConnectionService jdiService;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, mock(BreakpointTracker.class), mock(WatcherManager.class),
			mock(JdiExpressionEvaluator.class), new EventHistory(), new EvaluationGuard(),
			new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
		when(vm.canGetCurrentContendedMonitor()).thenReturn(true);
		when(vm.canGetMonitorInfo()).thenReturn(true);
	}

	private ThreadReference thread(long id, String name, int status) throws Exception {
		final ThreadReference t = mock(ThreadReference.class);
		when(t.uniqueID()).thenReturn(id);
		when(t.name()).thenReturn(name);
		when(t.status()).thenReturn(status);
		when(t.currentContendedMonitor()).thenReturn(null); // overridden per test when blocked
		return t;
	}

	/** A monitor object of declared type {@code typeName} that {@code owner} currently holds. */
	private ObjectReference monitor(long id, String typeName, ThreadReference owner) throws Exception {
		final ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn(typeName);
		final ObjectReference mon = mock(ObjectReference.class);
		when(mon.uniqueID()).thenReturn(id);
		when(mon.referenceType()).thenReturn(refType);
		when(mon.owningThread()).thenReturn(owner);
		return mon;
	}

	@Test
	@DisplayName("AB-BA monitor cycle is reported as a deadlock naming both threads")
	void shouldDetectTwoThreadDeadlock() throws Exception {
		final ThreadReference a = thread(1L, "transfer-A-to-B", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference b = thread(2L, "transfer-B-to-A", ThreadReference.THREAD_STATUS_MONITOR);
		// A is blocked on a monitor held by B; B is blocked on a monitor held by A. Build the
		// monitor mocks into locals first — stubbing them inside the outer thenReturn(...) would
		// trip Mockito's unfinished-stubbing guard.
		final ObjectReference monHeldByB = monitor(101L, "one.edee.Account", b);
		final ObjectReference monHeldByA = monitor(102L, "one.edee.Account", a);
		when(a.currentContendedMonitor()).thenReturn(monHeldByB);
		when(b.currentContendedMonitor()).thenReturn(monHeldByA);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(a, b));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.contains("DEADLOCK")
			.contains("transfer-A-to-B")
			.contains("transfer-B-to-A")
			.contains("one.edee.Account@101")
			.contains("jdwp_suspend_thread(id)");
		// snapshot suspend/resume must be balanced
		verify(vm).suspend();
		verify(vm).resume();
	}

	@Test
	@DisplayName("a thread blocked on a monitor held by a runnable thread is shown but is not a deadlock")
	void shouldReportBlockedWithoutCycle() throws Exception {
		final ThreadReference waiter = thread(1L, "worker-1", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference holder = thread(3L, "worker-3", ThreadReference.THREAD_STATUS_RUNNING);
		// waiter blocks on a monitor held by holder; holder is running and waits on nothing.
		final ObjectReference held = monitor(201L, "one.edee.Cache", holder);
		when(waiter.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(waiter, holder));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.contains("worker-1")
			.contains("held by:")
			.contains("#3 worker-3")
			.contains("No deadlock cycle detected")
			.doesNotContain("DEADLOCK");
	}

	@Test
	@DisplayName("a thread in Object.wait() (e.g. Thread.join) is not counted as blocked — it is listed apart (#50)")
	void shouldListWaitersApartFromTheGraph() throws Exception {
		final ThreadReference main = thread(1L, "main", ThreadReference.THREAD_STATUS_WAIT);
		final ThreadReference worker = thread(2L, "worker-1", ThreadReference.THREAD_STATUS_RUNNING);
		final ObjectReference joined = monitor(301L, "java.lang.Thread", null);
		when(main.currentContendedMonitor()).thenReturn(joined);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(main, worker));

		assertThat(tools.jdwp_dump_locks(null))
			.startsWith("No threads are currently blocked on a Java monitor.")
			.contains("In Object.wait() — the monitor is released, so not part of the lock graph: #1 main on "
				+ "java.lang.Thread@301");
	}

	@Test
	@DisplayName("no monitor-blocked threads → explicit none message, not an empty table")
	void shouldReportNoneWhenNothingBlocked() throws Exception {
		final ThreadReference t = thread(1L, "main", ThreadReference.THREAD_STATUS_RUNNING);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(t));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result).contains("No threads are currently blocked on a Java monitor");
	}

	@Test
	@DisplayName("a VM without contended-monitor capability returns an actionable error")
	void shouldErrorWhenCapabilityMissing() throws Exception {
		when(vm.canGetCurrentContendedMonitor()).thenReturn(false);
		when(jdiService.getVM()).thenReturn(vm);

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.startsWith("Error:")
			.contains("canGetCurrentContendedMonitor");
	}

	@Test
	@DisplayName("without canGetMonitorInfo a blocked thread lists its holder as unknown and reports no deadlock")
	void shouldListBlockedThreadWithoutOwnerWhenMonitorInfoUnavailable() throws Exception {
		when(vm.canGetMonitorInfo()).thenReturn(false);
		final ThreadReference waiter = thread(1L, "worker-1", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference holder = thread(2L, "worker-2", ThreadReference.THREAD_STATUS_RUNNING);
		// Even though the monitor mock knows its owner, the VM lacks the capability to read it, so
		// owningThread() must never be consulted and no wait-for edge can be built.
		final ObjectReference held = monitor(201L, "one.edee.Cache", holder);
		when(waiter.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(waiter, holder));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.contains("worker-1")
			.contains("(unknown — VM lacks canGetMonitorInfo)")
			.contains("No deadlock cycle detected")
			.doesNotContain("DEADLOCK");
		verify(held, never()).owningThread();
		// snapshot suspend/resume must stay balanced even when no owner is read
		verify(vm).suspend();
		verify(vm).resume();
	}

	@Test
	@DisplayName("a monitor with no current owner reports the wait/notify holder note and is not a deadlock")
	void shouldReportNoCurrentOwnerWhenOwningThreadIsNull() throws Exception {
		final ThreadReference waiter = thread(1L, "worker-1", ThreadReference.THREAD_STATUS_MONITOR);
		// canGetMonitorInfo is true (default) but the monitor currently has no owner — held via
		// wait/notify or just released — so owningThread() returns null and no edge is created.
		final ObjectReference held = monitor(201L, "one.edee.Cache", null);
		when(waiter.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(waiter));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.contains("worker-1")
			.contains("(no current owner — monitor held via wait/notify or just released)")
			.contains("No deadlock cycle detected")
			.doesNotContain("DEADLOCK");
	}

	@Test
	@DisplayName("an owningThread() that throws leaves the owner unknown but still renders the dump")
	void shouldLeaveOwnerUnknownWhenOwningThreadThrows() throws Exception {
		final ThreadReference waiter = thread(1L, "worker-1", ThreadReference.THREAD_STATUS_MONITOR);
		final ReferenceType refType = mock(ReferenceType.class);
		when(refType.name()).thenReturn("one.edee.Cache");
		final ObjectReference held = mock(ObjectReference.class);
		when(held.uniqueID()).thenReturn(201L);
		when(held.referenceType()).thenReturn(refType);
		when(held.owningThread()).thenThrow(new IncompatibleThreadStateException());
		when(waiter.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(waiter));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.contains("worker-1")
			.contains("one.edee.Cache@201")
			.contains("(no current owner — monitor held via wait/notify or just released)")
			.contains("No deadlock cycle detected");
		verify(vm).resume();
	}

	@Test
	@DisplayName("a thread whose currentContendedMonitor() throws is skipped while others still render")
	void shouldSkipThreadWhoseContendedMonitorThrows() throws Exception {
		final ThreadReference flaky = thread(1L, "flaky", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference waiter = thread(2L, "worker-2", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference holder = thread(3L, "worker-3", ThreadReference.THREAD_STATUS_RUNNING);
		// The flaky thread resumed in the snapshot gap → IncompatibleThreadStateException; it is
		// dropped from the table while the other genuinely-blocked thread is still rendered.
		when(flaky.currentContendedMonitor()).thenThrow(new IncompatibleThreadStateException());
		final ObjectReference held = monitor(201L, "one.edee.Cache", holder);
		when(waiter.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(flaky, waiter, holder));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.contains("1 thread(s) blocked on a monitor")
			.contains("worker-2")
			.doesNotContain("flaky");
	}

	@Test
	@DisplayName("a thread whose monitor was collected mid-snapshot is skipped, not fatal")
	void shouldSkipThreadWhoseMonitorWasCollected() throws Exception {
		final ThreadReference flaky = thread(1L, "flaky", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference waiter = thread(2L, "worker-2", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference holder = thread(3L, "worker-3", ThreadReference.THREAD_STATUS_RUNNING);
		when(flaky.currentContendedMonitor()).thenThrow(new ObjectCollectedException());
		final ObjectReference held = monitor(201L, "one.edee.Cache", holder);
		when(waiter.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(flaky, waiter, holder));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.contains("1 thread(s) blocked on a monitor")
			.contains("worker-2")
			.doesNotContain("flaky");
	}

	@Test
	@DisplayName("a blocked JVM-internal thread (Finalizer) is hidden by default")
	void shouldHideSystemThreadByDefault() throws Exception {
		final ThreadReference finalizer = thread(1L, "Finalizer", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference holder = thread(2L, "worker-2", ThreadReference.THREAD_STATUS_RUNNING);
		final ObjectReference held = monitor(201L, "one.edee.Cache", holder);
		when(finalizer.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(finalizer, holder));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result).contains("No threads are currently blocked on a Java monitor");
	}

	@Test
	@DisplayName("a blocked JVM-internal thread is shown when includeSystemThreads=true")
	void shouldIncludeSystemThreadWhenRequested() throws Exception {
		final ThreadReference refHandler = thread(1L, "Reference Handler", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference holder = thread(2L, "worker-2", ThreadReference.THREAD_STATUS_RUNNING);
		final ObjectReference held = monitor(201L, "one.edee.Cache", holder);
		when(refHandler.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(refHandler, holder));

		final String result = tools.jdwp_dump_locks(true);

		assertThat(result)
			.contains("1 thread(s) blocked on a monitor")
			.contains("Reference Handler");
	}

	@Test
	@DisplayName("an unreadable monitor renders a placeholder rather than failing the dump")
	void shouldRenderPlaceholderWhenMonitorIsUnreadable() throws Exception {
		final ThreadReference waiter = thread(1L, "worker-1", ThreadReference.THREAD_STATUS_MONITOR);
		final ThreadReference holder = thread(2L, "worker-2", ThreadReference.THREAD_STATUS_RUNNING);
		final ObjectReference held = mock(ObjectReference.class);
		when(held.owningThread()).thenReturn(holder);
		// referenceType() throws → describeMonitor degrades to its "monitor@?" placeholder.
		when(held.referenceType()).thenThrow(new ObjectCollectedException());
		when(waiter.currentContendedMonitor()).thenReturn(held);
		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(waiter, holder));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.contains("worker-1")
			.contains("monitor@?")
			.contains("#2 worker-2");
		verify(vm).resume();
	}

	@Test
	@DisplayName("a failure obtaining the VM is reported as an error")
	void shouldReturnErrorWhenGetVmFails() throws Exception {
		when(jdiService.getVM()).thenThrow(new IllegalStateException("not connected"));

		final String result = tools.jdwp_dump_locks(null);

		assertThat(result)
			.startsWith("Error:")
			.contains("not connected");
	}
}
