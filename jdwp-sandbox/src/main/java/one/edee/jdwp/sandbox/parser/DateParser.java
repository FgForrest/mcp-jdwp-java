package one.edee.jdwp.sandbox.parser;

import java.time.LocalDate;

/**
 * Parses an ISO-like {@code YYYY-MM-DD} date string into a {@link LocalDate}.
 */
public class DateParser {

	/**
	 * Parses {@code input} as {@code YYYY-MM-DD}.
	 */
	public LocalDate parse(String input) {
		String[] parts = input.split("-");
		int year = Integer.parseInt(parts[0]);
		int month = Integer.parseInt(parts[1]);
		int day = Integer.parseInt(parts[2]);
		return LocalDate.of(year, month, day);
	}
}
