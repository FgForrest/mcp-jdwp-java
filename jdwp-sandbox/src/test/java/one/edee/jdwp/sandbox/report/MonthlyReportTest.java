package one.edee.jdwp.sandbox.report;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyReportTest {

	@Test
	void shouldCountOrdersCreatedInOctober() {
		final MonthlyReport report = new MonthlyReport();
		final YearMonth month = YearMonth.of(2026, 10);
		final int count = report.forMonth(month);

		assertThat(count)
			.describedAs("Number of orders created in October 2026")
			.isEqualTo(7600);
	}
}
