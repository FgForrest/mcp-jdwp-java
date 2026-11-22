package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.interpret.ExpressionParser;
import one.edee.mcp.jdwp.evaluation.interpret.SyntaxShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "jdwp.live.corpus", matches = ".+")
class InterpreterSessionCorpusTest {

    @Test
    void reportsObservedAndTopUpSyntaxWithoutReadingAFrame() throws Exception {
        final var json = JsonMapper.builder().build();
        final var observed = new Counts();
        final var topUp = new Counts();
        int taggedErrors = 0;
        try (var lines = Files.newBufferedReader(Path.of(System.getProperty("jdwp.live.corpus")))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final var row = json.readTree(line);
                final var parsed = ExpressionParser.parse(row.path("expression").asString());
                final var category = SyntaxShape.classify(parsed);
                switch (row.path("agent").asString()) {
                    case "claude" -> observed.add(category);
                    case "docs", "synthetic" -> topUp.add(category);
                    default -> throw new AssertionError("Unexpected corpus source");
                }
                for (var tag : row.path("constructs")) {
                    if ("html_entity_in_source".equals(tag.asString())) {
                        assertThat(category.phase()).isEqualTo(SyntaxShape.Phase.ERROR);
                        assertThat(parsed.error()).contains("HTML entity");
                        taggedErrors++;
                    }
                }
            }
        }
        assertThat(taggedErrors).isEqualTo(2);
        final String report = observed.render("Observed") + topUp.render("Top-up");
        System.out.print(report);
        final Path reports = Path.of("..", "target");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("interp-session-distribution.txt"), report);
    }

    private static final class Counts {
        private final Map<SyntaxShape.Phase, Map<String, Integer>> phases = new EnumMap<>(SyntaxShape.Phase.class);

        void add(SyntaxShape.Classification category) {
            phases.computeIfAbsent(category.phase(), phase -> new TreeMap<>()).merge(category.reason(), 1, Integer::sum);
        }

        String render(String label) {
            final var out = new StringBuilder(label).append(" (syntax only)\n");
            for (SyntaxShape.Phase phase : SyntaxShape.Phase.values()) {
                final Map<String, Integer> reasons = phases.getOrDefault(phase, Map.of());
                final int count = reasons.values().stream().mapToInt(Integer::intValue).sum();
                out.append("  ").append(phase).append(" ").append(count).append(" ").append(reasons).append('\n');
            }
            return out.toString();
        }
    }
}
