package one.edee.mcp.jdwp.evaluation;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterpreterCorpusTest {

    @Test
    void loadsSyntheticAndSessionRowsWithoutGuessingAFrame() throws Exception {
        final var entries = InterpreterCorpus.read(new StringReader("""
            {"id":"synthetic","tool":"jdwp_evaluate_expression","param":"expression","expression":"n + 1","stop":"main","expected_class":"int","notes":"arithmetic"}

            {"id":"session","tool":"jdwp_set_logpoint","param":"expression","expression":"item","agent":"a","project":"p","outcome":"ok","error_class":null,"constructs":["name"],"count":4}
            """), "sample");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).isEqualTo(new InterpreterCorpus.Entry(
            "synthetic", "jdwp_evaluate_expression", "expression", "n + 1", "main", "int", "arithmetic"));
        assertThat(entries.get(1)).isEqualTo(new InterpreterCorpus.Entry(
            "session", "jdwp_set_logpoint", "expression", "item", null, null, null));
    }

    @Test
    void reportsInvalidRowsWithTheirSourceAndLine() {
        assertThatThrownBy(() -> InterpreterCorpus.read(new StringReader("""
            {"id":"first","tool":"jdwp_evaluate_expression","param":"expression","expression":"1"}
            {"id":"second","tool":"jdwp_evaluate_expression","param":"expression","expression":3}
            """), "sample"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sample:2", "expression");
    }

    @Test
    void rejectsDuplicateIdsUsedToMatchOracleRows() {
        final String row = """
            {"id":"first","tool":"jdwp_evaluate_expression","param":"expression","expression":"1"}
            """;
        assertThatThrownBy(() -> InterpreterCorpus.read(new StringReader(row + row), "sample"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sample:2", "duplicate", "first");
    }

    @Test
    void rejectsMultipleJsonValuesOnOneLine() {
        assertThatThrownBy(() -> InterpreterCorpus.read(new StringReader("""
            {"id":"first","tool":"jdwp_evaluate_expression","param":"expression","expression":"1"} {}
            """), "sample"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sample:1");
    }
}
