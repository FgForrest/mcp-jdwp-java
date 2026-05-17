package one.edee.mcp.jdwp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link JDWPTools#parseCharInput(String)}, a package-private static utility that strips
 * optional single-quote delimiters from user-supplied char literals and validates that the
 * remaining payload is exactly one character.
 */
@DisplayName("JDWPTools parseCharInput utility")
class JDWPToolsParseCharInputTest {

	@Nested
	@DisplayName("Valid inputs")
	class ValidInputs {

		@Test
		@DisplayName("'a' (quoted) yields 'a'")
		void shouldStripSurroundingQuotesAndReturnInnerChar() {
			assertThat(JDWPTools.parseCharInput("'a'")).isEqualTo('a');
		}

		@Test
		@DisplayName("bare 'a' yields 'a'")
		void shouldReturnBareCharDirectly() {
			assertThat(JDWPTools.parseCharInput("a")).isEqualTo('a');
		}

		@Test
		@DisplayName("quoted space '\\u0020' yields space")
		void shouldHandleQuotedSpace() {
			assertThat(JDWPTools.parseCharInput("' '")).isEqualTo(' ');
		}

		@Test
		@DisplayName("bare digit '7' yields '7'")
		void shouldHandleBareDigit() {
			assertThat(JDWPTools.parseCharInput("7")).isEqualTo('7');
		}

		@Test
		@DisplayName("quoted digit '7' yields '7'")
		void shouldHandleQuotedDigit() {
			assertThat(JDWPTools.parseCharInput("'7'")).isEqualTo('7');
		}
	}

	@Nested
	@DisplayName("Invalid inputs")
	class InvalidInputs {

		@Test
		@DisplayName("empty quotes '' throws IllegalArgumentException")
		void shouldRejectEmptyQuotedInput() {
			assertThatThrownBy(() -> JDWPTools.parseCharInput("''"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly one character");
		}

		@Test
		@DisplayName("bare multi-char 'abc' throws IllegalArgumentException")
		void shouldRejectBareMultiCharInput() {
			assertThatThrownBy(() -> JDWPTools.parseCharInput("abc"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly one character");
		}

		@Test
		@DisplayName("quoted multi-char 'abc' throws IllegalArgumentException")
		void shouldRejectQuotedMultiCharInput() {
			assertThatThrownBy(() -> JDWPTools.parseCharInput("'abc'"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly one character");
		}

		@Test
		@DisplayName("empty string throws IllegalArgumentException")
		void shouldRejectEmptyString() {
			assertThatThrownBy(() -> JDWPTools.parseCharInput(""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly one character");
		}
	}
}
