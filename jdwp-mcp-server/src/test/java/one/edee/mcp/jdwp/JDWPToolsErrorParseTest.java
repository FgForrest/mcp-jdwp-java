package one.edee.mcp.jdwp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JDWPTools#parseUnresolvedFieldName(String)}, the pure regex extraction
 * that drives error enrichment in {@code jdwp_evaluate_expression}. Covers the three JDT compiler
 * error patterns we want to enrich, plus negative cases.
 */
@DisplayName("JDWPTools parseUnresolvedFieldName")
class JDWPToolsErrorParseTest {

	// ── pattern 1: "X cannot be resolved" ───────────────────────────────────────────────

	@Test
	@DisplayName("extracts the field name from a 'cannot be resolved' error")
	void shouldExtractFromCannotBeResolved() {
		String message = "Compilation failed:\nLine 7: sessions cannot be resolved";

		assertThat(JDWPTools.parseUnresolvedFieldName(message)).isEqualTo("sessions");
	}

	@Test
	@DisplayName("extracts identifier with underscores and digits")
	void shouldExtractIdentifierWithUnderscoresAndDigits() {
		String message = "_my_field2 cannot be resolved";

		assertThat(JDWPTools.parseUnresolvedFieldName(message)).isEqualTo("_my_field2");
	}

	// ── pattern 2: "field x.y.z.X is not visible" ───────────────────────────────────────

	@Test
	@DisplayName("extracts the field name from a 'field a.b.c.X is not visible' error")
	void shouldExtractFromQualifiedNotVisible() {
		String message = "The field io.mcp.jdwp.sandbox.session.SessionStore.sessions is not visible";

		assertThat(JDWPTools.parseUnresolvedFieldName(message)).isEqualTo("sessions");
	}

	@Test
	@DisplayName("extracts the rightmost component from a qualified visibility error")
	void shouldExtractRightmostFromQualifiedNotVisible() {
		String message = "field com.example.Outer.Inner.value is not visible";

		assertThat(JDWPTools.parseUnresolvedFieldName(message)).isEqualTo("value");
	}

	// ── pattern 3: bare "X is not visible" ──────────────────────────────────────────────

	@Test
	@DisplayName("extracts the bare identifier from an 'X is not visible' error")
	void shouldExtractFromBareNotVisible() {
		String message = "sessions is not visible";

		assertThat(JDWPTools.parseUnresolvedFieldName(message)).isEqualTo("sessions");
	}

	// ── negatives ───────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("returns null for unrelated error messages")
	void shouldReturnNullForUnrelatedError() {
		assertThat(JDWPTools.parseUnresolvedFieldName("Type mismatch: cannot convert from int to String")).isNull();
		assertThat(JDWPTools.parseUnresolvedFieldName("Syntax error on token \"foo\"")).isNull();
		assertThat(JDWPTools.parseUnresolvedFieldName("")).isNull();
	}

	@Test
	@DisplayName("returns null for null input")
	void shouldReturnNullForNullInput() {
		assertThat(JDWPTools.parseUnresolvedFieldName(null)).isNull();
	}

	@Test
	@DisplayName("handles a multi-line error message and extracts the first match")
	void shouldHandleMultilineMessage() {
		String message = "Compilation failed:\n"
			+ "  Line 7: sessions cannot be resolved\n"
			+ "  Line 8: another problem here\n";

		assertThat(JDWPTools.parseUnresolvedFieldName(message)).isEqualTo("sessions");
	}
}
