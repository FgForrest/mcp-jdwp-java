package one.edee.jdwp.sandbox.report;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

/** Sample order history for the autumn sales season. */
final class OrderHistory {

	private static final int SEPTEMBER_ORDERS = 1_200;
	private static final int OCTOBER_ORDERS = 7_600;
	private static final int NOVEMBER_ORDERS = 1_200;
	private static final int ORDER_COUNT = SEPTEMBER_ORDERS + OCTOBER_ORDERS + NOVEMBER_ORDERS;

	private OrderHistory() {
	}

	/** Opens a forward scan in order number sequence. */
	static Iterable<Order> rows() {
		return Cursor::new;
	}

	/** Generates each row as the scan advances. */
	private static final class Cursor implements Iterator<Order> {

		private final Random random = new Random(540);
		private int number;

		@Override
		public boolean hasNext() {
			return number < ORDER_COUNT;
		}

		@Override
		public Order next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			number++;
			final YearMonth period = periodFor(number);
			final int day = 1 + random.nextInt(period.lengthOfMonth());
			final int minute = random.nextInt(40) == 0
				? 22 * 60 + 30 * random.nextInt(4)
				: 8 * 60 + random.nextInt(14 * 60);
			final Instant createdAt = period.atDay(day).atStartOfDay().plusMinutes(minute).toInstant(ZoneOffset.UTC);
			return new Order(number, createdAt);
		}

		private YearMonth periodFor(final int number) {
			if (number <= SEPTEMBER_ORDERS) {
				return YearMonth.of(2026, 9);
			}
			if (number <= SEPTEMBER_ORDERS + OCTOBER_ORDERS) {
				return YearMonth.of(2026, 10);
			}
			return YearMonth.of(2026, 11);
		}
	}
}
