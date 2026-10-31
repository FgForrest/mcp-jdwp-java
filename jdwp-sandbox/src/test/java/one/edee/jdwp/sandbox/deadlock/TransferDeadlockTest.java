package one.edee.jdwp.sandbox.deadlock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferDeadlockTest {

	@Test
	void shouldCompleteBothTransfers() throws Exception {
		Account a = new Account("A", 1000);
		Account b = new Account("B", 1000);

		// Two transfers in opposite directions at once.
		Thread t1 = new Thread(() -> a.transfer(b, 100), "transfer-A-to-B");
		Thread t2 = new Thread(() -> b.transfer(a, 100), "transfer-B-to-A");
		t1.start();
		t2.start();

		// Bounded so an accidental run can't hang the suite forever; the window leaves plenty of
		// time to attach and inspect during a flight.
		t1.join(60_000);
		t2.join(60_000);

		// Fails: both threads are still alive after the join window.
		assertThat(t1.isAlive() || t2.isAlive())
			.describedAs("Both transfers should have finished")
			.isFalse();
	}
}
