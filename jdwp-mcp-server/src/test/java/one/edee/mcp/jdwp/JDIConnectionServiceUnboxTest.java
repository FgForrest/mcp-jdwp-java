package one.edee.mcp.jdwp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JDIConnectionService#isBoxedPrimitiveType(String)}, the pure
 * type-name check that drives auto-unboxing in {@link JDIConnectionService#formatFieldValue}.
 * Verifies all eight Java primitive wrapper types are recognised and that nothing else slips
 * through.
 */
class JDIConnectionServiceUnboxTest {

	@Test
	@DisplayName("recognises all eight Java primitive wrapper types")
	void shouldRecogniseAllEightWrappers() {
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Integer")).isTrue();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Long")).isTrue();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Double")).isTrue();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Float")).isTrue();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Boolean")).isTrue();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Character")).isTrue();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Byte")).isTrue();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Short")).isTrue();
	}

	@Test
	@DisplayName("does NOT recognise java.lang.String")
	void shouldNotRecogniseString() {
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.String")).isFalse();
	}

	@Test
	@DisplayName("does NOT recognise java.lang.Object or other JDK types")
	void shouldNotRecogniseOtherJdkTypes() {
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Object")).isFalse();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.Number")).isFalse();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.math.BigDecimal")).isFalse();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.util.HashMap")).isFalse();
	}

	@Test
	@DisplayName("does NOT recognise application classes")
	void shouldNotRecogniseUserClasses() {
		assertThat(JDIConnectionService.isBoxedPrimitiveType("one.edee.mcp.jdwp.sandbox.order.Order")).isFalse();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("com.example.MyEntity")).isFalse();
	}

	@Test
	@DisplayName("does NOT recognise primitive type names (only wrapper FQNs)")
	void shouldNotRecognisePrimitiveNames() {
		assertThat(JDIConnectionService.isBoxedPrimitiveType("int")).isFalse();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("long")).isFalse();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("boolean")).isFalse();
	}

	@Test
	@DisplayName("returns false for null and empty input")
	void shouldHandleNullAndEmpty() {
		assertThat(JDIConnectionService.isBoxedPrimitiveType(null)).isFalse();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("")).isFalse();
	}

	@Test
	@DisplayName("is case-sensitive")
	void shouldBeCaseSensitive() {
		assertThat(JDIConnectionService.isBoxedPrimitiveType("java.lang.integer")).isFalse();
		assertThat(JDIConnectionService.isBoxedPrimitiveType("JAVA.LANG.INTEGER")).isFalse();
	}
}
