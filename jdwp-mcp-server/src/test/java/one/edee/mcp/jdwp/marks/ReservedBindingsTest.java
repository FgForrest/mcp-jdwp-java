package one.edee.mcp.jdwp.marks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservedBindingsTest {

    @Test
    @DisplayName("valid Java identifier — accepted")
    void acceptsValidIdentifier() {
        assertThatCode(() -> ReservedBindings.requireValidLabel("cart_42")).doesNotThrowAnyException();
        assertThatCode(() -> ReservedBindings.requireValidLabel("_session")).doesNotThrowAnyException();
        assertThatCode(() -> ReservedBindings.requireValidLabel("a")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("blank or null label — rejected with clear message")
    void rejectsBlank() {
        assertThatThrownBy(() -> ReservedBindings.requireValidLabel(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
        assertThatThrownBy(() -> ReservedBindings.requireValidLabel("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
        assertThatThrownBy(() -> ReservedBindings.requireValidLabel(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("non-identifier label — rejected (digit start, hyphen, dot, special)")
    void rejectsNonIdentifier() {
        assertThatThrownBy(() -> ReservedBindings.requireValidLabel("42cart"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a valid Java identifier");
        assertThatThrownBy(() -> ReservedBindings.requireValidLabel("cart-42"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservedBindings.requireValidLabel("cart.x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReservedBindings.requireValidLabel("$cart"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Java keyword — rejected with a 'reserved word' message")
    void rejectsJavaKeywords() {
        for (String kw : new String[]{"class", "if", "return", "null", "void", "static", "this", "true"}) {
            assertThatThrownBy(() -> ReservedBindings.requireValidLabel(kw))
                .as("keyword %s should be rejected", kw)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved word");
        }
    }

    @Test
    @DisplayName("MCP-injected binding name — rejected with a 'reserved by the MCP server' message")
    void rejectsReservedBindings() {
        for (String bound : new String[]{"exception", "oldValue", "newValue", "object", "fieldName", "mode", "_this"}) {
            assertThatThrownBy(() -> ReservedBindings.requireValidLabel(bound))
                .as("reserved binding %s should be rejected", bound)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved by the MCP server");
        }
    }
}
