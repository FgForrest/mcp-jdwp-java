package one.edee.jdwp.sandbox.parser;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DateParserTest {

	@Test
	void shouldParseDateFromFeed() {
		DateParser parser = new DateParser();

		// The date as the upstream feed delivers it.
		String fromFeed = "2026-05-15 ";

		// Fails: NumberFormatException.
		LocalDate result = parser.parse(fromFeed);

		assertThat(result).isEqualTo(LocalDate.of(2026, 5, 15));
	}
}
