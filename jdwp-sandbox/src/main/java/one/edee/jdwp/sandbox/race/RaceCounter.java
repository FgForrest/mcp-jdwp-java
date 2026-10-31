package one.edee.jdwp.sandbox.race;

import java.util.concurrent.CyclicBarrier;

/**
 * A counter incremented by threads that keep in step at a barrier.
 */
public class RaceCounter {

	private int count = 0;

	public int getCount() {
		return count;
	}

	/**
	 * Adds one to the count, keeping in step with the other thread at {@code barrier}.
	 */
	public void increment(CyclicBarrier barrier) throws Exception {
		int observed = count;
		barrier.await();
		count = observed + 1;
	}
}
