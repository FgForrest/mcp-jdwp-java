package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.JDWPTools.FieldDiagnostic;
import one.edee.mcp.jdwp.JDWPTools.FieldDiagnosticKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the name and kind of the first diagnostic matched by {@link JDWPTools#parseFieldDiagnostic(String)}.
 */
@DisplayName("JDWPTools parseFieldDiagnostic")
class JDWPToolsErrorParseTest {

	// ── pattern 1: "X cannot be resolved" ───────────────────────────────────────────────

	@Test
	@DisplayName("extracts the field name from a 'cannot be resolved' error")
	void shouldExtractFromCannotBeResolved() {
		String message = "Compilation failed:\nLine 7: sessions cannot be resolved";

		assertThat(JDWPTools.parseFieldDiagnostic(message)).isEqualTo(new FieldDiagnostic("sessions", FieldDiagnosticKind.BARE));
	}

	@Test
	@DisplayName("extracts identifier with underscores and digits")
	void shouldExtractIdentifierWithUnderscoresAndDigits() {
		String message = "_my_field2 cannot be resolved";

		assertThat(JDWPTools.parseFieldDiagnostic(message)).isEqualTo(new FieldDiagnostic("_my_field2", FieldDiagnosticKind.BARE));
	}

	/** The suffix distinguishes a missing variable, a missing field and a missing type. */
	@ParameterizedTest
	@CsvSource({"to a variable, BARE", "or is not a field, QUALIFIED", "to a type, TYPE"})
	void shouldClassifyCannotBeResolvedSuffix(String suffix, FieldDiagnosticKind kind) {
		assertThat(JDWPTools.parseFieldDiagnostic("sessions cannot be resolved " + suffix))
			.isEqualTo(new FieldDiagnostic("sessions", kind));
	}

	// ── pattern 2: "field x.y.z.X is not visible" ───────────────────────────────────────

	@Test
	@DisplayName("extracts the field name from a 'field a.b.c.X is not visible' error")
	void shouldExtractFromQualifiedNotVisible() {
		String message = "The field io.mcp.jdwp.sandbox.session.SessionStore.sessions is not visible";

		assertThat(JDWPTools.parseFieldDiagnostic(message)).isEqualTo(new FieldDiagnostic("sessions", FieldDiagnosticKind.NOT_VISIBLE));
	}

	@Test
	@DisplayName("extracts the rightmost component from a qualified visibility error")
	void shouldExtractRightmostFromQualifiedNotVisible() {
		String message = "field com.example.Outer.Inner.value is not visible";

		assertThat(JDWPTools.parseFieldDiagnostic(message)).isEqualTo(new FieldDiagnostic("value", FieldDiagnosticKind.NOT_VISIBLE));
	}

	// ── pattern 3: bare "X is not visible" ──────────────────────────────────────────────

	@Test
	@DisplayName("extracts the bare identifier from an 'X is not visible' error")
	void shouldExtractFromBareNotVisible() {
		String message = "sessions is not visible";

		assertThat(JDWPTools.parseFieldDiagnostic(message)).isEqualTo(new FieldDiagnostic("sessions", FieldDiagnosticKind.NOT_VISIBLE));
	}

	// ── negatives ───────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("returns null for unrelated error messages")
	void shouldReturnNullForUnrelatedError() {
		assertThat(JDWPTools.parseFieldDiagnostic("Type mismatch: cannot convert from int to String")).isNull();
		assertThat(JDWPTools.parseFieldDiagnostic("Syntax error on token \"foo\"")).isNull();
		assertThat(JDWPTools.parseFieldDiagnostic("")).isNull();
	}

	@Test
	@DisplayName("returns null for null input")
	void shouldReturnNullForNullInput() {
		assertThat(JDWPTools.parseFieldDiagnostic(null)).isNull();
	}

	@Test
	@DisplayName("handles a multi-line error message and extracts the first match")
	void shouldHandleMultilineMessage() {
		String message = "Compilation failed:\n"
			+ "  Line 7: sessions cannot be resolved\n"
			+ "  Line 8: sessions cannot be resolved to a type\n";

		assertThat(JDWPTools.parseFieldDiagnostic(message)).isEqualTo(new FieldDiagnostic("sessions", FieldDiagnosticKind.BARE));
	}
}
