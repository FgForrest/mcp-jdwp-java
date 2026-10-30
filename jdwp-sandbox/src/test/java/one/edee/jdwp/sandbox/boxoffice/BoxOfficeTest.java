package one.edee.jdwp.sandbox.boxoffice;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BoxOfficeTest {

	/**
	 * Seats in the hall; {@code -Dboxoffice.seats=<n>} changes it.
	 */
	private static final int SEATS = Integer.getInteger("boxoffice.seats", 200_000);
	/**
	 * Performances in the season, each sold out from scratch; {@code -Dboxoffice.performances=<n>}
	 * changes it.
	 */
	private static final int PERFORMANCES = Integer.getInteger("boxoffice.performances", 300);
	private static final int WINDOWS = 8;
	/**
	 * The box office closes this long after the season's sale opened, sold out or not.
	 */
	private static final long OPENING_HOURS_MS = 120_000L;

	@Test
	void shouldSellEverySeatExactlyOnce() throws Exception {
		// Each window serves its own queue of customers asking for seats in random order; customer
		// numbers are the window numbers.
		final int[][] requests = new int[WINDOWS][];
		for (int w = 0; w < WINDOWS; w++) {
			requests[w] = shuffledSeats(new Random(w + 1));
		}

		final long opened = System.nanoTime();
		final long closing = opened + TimeUnit.MILLISECONDS.toNanos(OPENING_HOURS_MS);
		long ticketsSold = 0;
		int performancesSoldOut = 0;
		for (int performance = 1; performance <= PERFORMANCES && System.nanoTime() < closing; performance++) {
			final BoxOffice office = new BoxOffice(SEATS);
			if (!sell(office, requests, closing)) {
				break;
			}
			ticketsSold += office.sold();
			performancesSoldOut++;
		}
		final long elapsedNanos = System.nanoTime() - opened;
		final long attempts = (long) performancesSoldOut * WINDOWS * SEATS;
		System.out.printf("Box office: %d performances x %d seats, %d windows: %d attempts in %d ms (%.2f ns per attempt)%n",
			performancesSoldOut, SEATS, WINDOWS, attempts, TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
			attempts == 0 ? 0.0 : (double) elapsedNanos / attempts);
		System.out.printf("Box office: sold %d tickets for %d seats%n", ticketsSold,
			(long) performancesSoldOut * SEATS);

		assertThat(performancesSoldOut)
			.describedAs("The box office closed before the season's sale finished")
			.isEqualTo(PERFORMANCES);
		// Fails: more tickets were sold than the hall has seats.
		assertThat(ticketsSold)
			.describedAs("Tickets sold for %d performances of %d seats", PERFORMANCES, SEATS)
			.isEqualTo((long) PERFORMANCES * SEATS);
	}

	/**
	 * Opens every window for one performance and waits until they are through their queues.
	 *
	 * @return whether all windows finished before closing time
	 */
	private static boolean sell(BoxOffice office, int[][] requests, long closing) throws InterruptedException {
		final Thread[] windows = new Thread[WINDOWS];
		for (int w = 0; w < WINDOWS; w++) {
			final int customer = w + 1;
			final int[] queue = requests[w];
			windows[w] = new Thread(() -> {
				for (int seat : queue) {
					office.trySell(seat, customer);
				}
			}, "box-office-" + customer);
			windows[w].setDaemon(true);
		}
		for (Thread window : windows) {
			window.start();
		}
		boolean finished = true;
		for (Thread window : windows) {
			window.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(closing - System.nanoTime())));
			finished &= !window.isAlive();
		}
		return finished;
	}

	private static int[] shuffledSeats(Random random) {
		final int[] seats = new int[SEATS];
		for (int i = 0; i < SEATS; i++) {
			seats[i] = i;
		}
		for (int i = SEATS - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final int swap = seats[i];
			seats[i] = seats[j];
			seats[j] = swap;
		}
		return seats;
	}
}
