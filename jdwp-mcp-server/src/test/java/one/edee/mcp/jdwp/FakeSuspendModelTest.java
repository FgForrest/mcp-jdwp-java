package one.edee.mcp.jdwp;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link FakeSuspendModel} to the jdk21u agent behaviour the suspension-ownership design relies
 * on (PROPOSAL-suspension-ownership.md § 0), so a test built on the model asserts real counts.
 */
@DisplayName("FakeSuspendModel — the JDWP agent's suspend bookkeeping")
class FakeSuspendModelTest {

	@Test
	@DisplayName("a resume at count 0 is a no-op; a stop-all event and its resume net zero on every thread")
	void resumeAtZeroIsANoOp() {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread t = model.thread("t");
		final FakeSuspendModel.FakeThread u = model.thread("u");

		t.ref.resume();
		assertThat(t.count).isZero();

		final EventSet set = model.breakpointEvent(u, EventRequest.SUSPEND_ALL);
		assertThat(model.counts()).containsEntry("t", 1).containsEntry("u", 1);
		assertThat(model.suspendAllCount).isEqualTo(1);
		set.resume();
		assertThat(model.counts()).containsEntry("t", 0).containsEntry("u", 0);
		assertThat(model.suspendAllCount).isZero();
	}

	@Test
	@DisplayName("a thread started while the VM is suspended for a stop-all inherits that suspension")
	void newbornInheritsSuspendAll() {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread u = model.thread("u");
		final EventSet set = model.breakpointEvent(u, EventRequest.SUSPEND_ALL);

		final FakeSuspendModel.FakeThread v = model.thread("v");
		assertThat(v.count).isEqualTo(1);
		set.resume();
		assertThat(v.count).isZero();
	}

	@Test
	@DisplayName("only the event thread is invokable; an invocation borrows one count and restores it")
	void invocationLoan() throws Exception {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread t = model.thread("t");
		final FakeSuspendModel.FakeThread u = model.thread("u");
		model.breakpointEvent(t, EventRequest.SUSPEND_ALL);

		assertThatThrownBy(() -> model.beginInvocation(u)).as("a bystander of the stop-all is not invokable")
			.isInstanceOf(IncompatibleThreadStateException.class);
		model.beginInvocation(t);
		assertThat(t.count).isZero();
		assertThat(model.invocationRuns(t)).isTrue();
		model.completeInvocation(t);
		assertThat(t.count).isEqualTo(1);
	}

	@Test
	@DisplayName("a thread that ran after its event is no longer invokable, even when suspended again")
	void runningClearsInvokability() {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread t = model.thread("t");
		final EventSet set = model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD);

		set.resume();
		t.ref.suspend();
		assertThat(t.count).isEqualTo(1);
		assertThatThrownBy(() -> model.beginInvocation(t)).isInstanceOf(IncompatibleThreadStateException.class);
	}

	@Test
	@DisplayName("another stop-all during an invocation parks the invoked code until it is resumed (#43)")
	void stopAllDuringInvocationParksIt() throws Exception {
		final FakeSuspendModel model = new FakeSuspendModel();
		final FakeSuspendModel.FakeThread t = model.thread("t");
		final FakeSuspendModel.FakeThread u = model.thread("u");
		model.breakpointEvent(t, EventRequest.SUSPEND_EVENT_THREAD);
		model.beginInvocation(t);

		final EventSet other = model.breakpointEvent(u, EventRequest.SUSPEND_ALL);
		assertThat(model.invocationRuns(t)).isFalse();
		t.ref.resume();
		assertThat(model.invocationRuns(t)).as("exempted").isTrue();
		model.completeInvocation(t);
		other.resume();
		assertThat(t.count).as("exemption not compensated: the stop-all release took the event's own count")
			.isZero();
	}
}
