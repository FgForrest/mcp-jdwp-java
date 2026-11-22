package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.interpret.ExpressionParser;
import one.edee.mcp.jdwp.evaluation.interpret.Planner.KindOfPlan;
import one.edee.mcp.jdwp.evaluation.interpret.SyntaxShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Reports aggregate phase-2 syntax coverage without storing any source from the optional corpus. */
@EnabledIfSystemProperty(named = "jdwp.live.corpus", matches = ".+")
class InterpreterSessionCorpusTest {

    /** Classifies each row and separates observed sessions from documentation and synthetic examples. */
    @Test
    void reportsObservedAndTopUpSyntaxWithoutReadingAFrame() throws Exception {
        final JsonMapper json = JsonMapper.builder().build();
        final Counts observed = new Counts();
        final Counts topUp = new Counts();
        int taggedErrors = 0;
        try (BufferedReader lines = Files.newBufferedReader(Path.of(System.getProperty("jdwp.live.corpus")))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonNode row = json.readTree(line);
                final ExpressionParser.Parsed parsed = ExpressionParser.parse(row.path("expression").asString());
                final SyntaxShape.Classification category = SyntaxShape.classify(parsed);
                boolean callBearing = category.phase() == SyntaxShape.Phase.CALLS;
                for (JsonNode tag : row.path("constructs")) {
                    switch (tag.asString()) {
                        case "method_call", "new_object" -> callBearing = true;
                        case "html_entity_in_source" -> {
                            assertThat(category.phase()).isEqualTo(SyntaxShape.Phase.ERROR);
                            assertThat(parsed.error()).contains("HTML entity");
                            taggedErrors++;
                        }
                        default -> { }
                    }
                }
                switch (row.path("agent").asString()) {
                    case "claude" -> observed.add(category, callBearing);
                    case "docs", "synthetic" -> topUp.add(category, callBearing);
                    default -> throw new AssertionError("Unexpected corpus source");
                }
            }
        }
        assertThat(taggedErrors).isEqualTo(2);
        final String report = """
            NAVIGATE and INVOKE are syntax candidates. Actual coverage requires each row's original JDI frame,
            including receiver signatures, class initialization and argument types.
            """ + observed.render("Observed") + topUp.render("Top-up");
        System.out.print(report);
        final Path reports = Path.of("..", "target");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("interp-session-distribution.txt"), report);
    }

    /** Counts rows by their anticipated plan and records syntax reasons for compiler routing. */
    private static final class Counts {
        /** Syntax plans for all rows in this source group. */
        private final Map<KindOfPlan, Integer> plans = new EnumMap<>(KindOfPlan.class);
        /** Syntax plans for rows containing calls, constructors or invoking concatenation. */
        private final Map<KindOfPlan, Integer> callPlans = new EnumMap<>(KindOfPlan.class);
        /** Compile reasons across all rows. */
        private final Map<String, Integer> reasons = new TreeMap<>();
        /** Compile reasons across call-bearing rows. */
        private final Map<String, Integer> callReasons = new TreeMap<>();

        /** Counts one row and records its generic frontend reason when it needs the compiler. */
        void add(SyntaxShape.Classification category, boolean callBearing) {
            final KindOfPlan kind = switch (category.phase()) {
                case NAVIGATION -> KindOfPlan.NAVIGATE;
                case CALLS -> KindOfPlan.INVOKE;
                case BLOCKS, COMPILED -> KindOfPlan.COMPILE;
                case ERROR -> KindOfPlan.ERROR;
            };
            plans.merge(kind, 1, Integer::sum);
            if (kind == KindOfPlan.COMPILE) {
                reasons.merge(category.reason(), 1, Integer::sum);
            }
            if (callBearing) {
                callPlans.merge(kind, 1, Integer::sum);
                if (kind == KindOfPlan.COMPILE) {
                    callReasons.merge(category.reason(), 1, Integer::sum);
                }
            }
        }

        /** Formats this group's counts and highest-frequency compilation reasons. */
        String render(String label) {
            final StringBuilder out = new StringBuilder(label).append('\n');
            for (KindOfPlan kind : KindOfPlan.values()) {
                out.append("  ").append(kind).append(' ').append(plans.getOrDefault(kind, 0)).append('\n');
            }
            out.append("  Call-bearing rows ").append(callPlans).append('\n');
            out.append("  Top COMPILE reasons ").append(top(reasons)).append('\n');
            out.append("  Top call-bearing COMPILE reasons ").append(top(callReasons)).append('\n');
            return out.toString();
        }

        /** Formats the five most frequent reasons with deterministic tie ordering. */
        private static String top(Map<String, Integer> source) {
            return source.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(
                Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(5).toList().toString();
        }
    }
}
