package one.edee.jdwp.sandbox.boxoffice;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Sells the seats of one performance from several box-office windows at once. A seat is checked
 * before it is sold, so every seat is sold at most once.
 */
public class BoxOffice {

	/**
	 * Who bought each seat: {@code 0} while the seat is free, otherwise the buying customer.
	 */
	private final int[] owners;
	private final AtomicInteger sold = new AtomicInteger();
	private final LongAdder revenue = new LongAdder();
	/**
	 * Set by the pricing desk while the sale is running.
	 */
	private volatile int surcharge = 3;

	public BoxOffice(int seats) {
		this.owners = new int[seats];
	}

	public int seats() {
		return owners.length;
	}

	public int sold() {
		return sold.get();
	}

	public long revenue() {
		return revenue.sum();
	}

	public void setSurcharge(int surcharge) {
		this.surcharge = surcharge;
	}

	/**
	 * Sells {@code seat} to {@code customer} unless somebody has bought it already.
	 *
	 * @return whether the sale went through
	 */
	public boolean trySell(int seat, int customer) {
		final int[] seatOwners = this.owners;
		if (seatOwners[seat] != 0) {
			return false;
		}
		final int price = priceOf(seat);
		seatOwners[seat] = customer;
		sold.incrementAndGet();
		revenue.add(price);
		return true;
	}

	/**
	 * Base price plus the current surcharge; rows nearer the stage cost more.
	 */
	private int priceOf(int seat) {
		int price = 40 + surcharge;
		for (int row = seat % 100; row < 100; row++) {
			price += row & 1;
		}
		return price;
	}
}
