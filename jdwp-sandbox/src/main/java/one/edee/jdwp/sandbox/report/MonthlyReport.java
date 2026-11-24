package one.edee.jdwp.sandbox.report;

import java.time.YearMonth;
import java.time.ZoneId;

/** Counts orders for monthly sales reports. */
public final class MonthlyReport {

	private static final ZoneId REPORT_ZONE = ZoneId.of("Europe/Prague");

	/** Returns the number of orders created in the requested month. */
	public int forMonth(final YearMonth month) {
		int count = 0;
		for (final Order order : OrderHistory.rows()) {
			final YearMonth creationMonth = YearMonth.from(order.createdAt().atZone(REPORT_ZONE));
			if (!creationMonth.equals(month)) {
				continue;
			}
			count++;
		}
		return count;
	}
}
