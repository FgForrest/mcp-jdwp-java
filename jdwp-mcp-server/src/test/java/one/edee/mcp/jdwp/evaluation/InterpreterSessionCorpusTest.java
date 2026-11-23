package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.interpret.ExpressionParser;
import one.edee.mcp.jdwp.evaluation.interpret.Planner.KindOfPlan;
import one.edee.mcp.jdwp.evaluation.interpret.SyntaxShape;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.ConditionalExpression;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.jspecify.annotations.Nullable;
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
                final ResultUses uses = ResultUses.inspect(category, parsed.expression());
                final String tool = row.path("tool").asString();
                switch (row.path("agent").asString()) {
                    case "claude" -> observed.add(category, callBearing, uses, tool);
                    case "docs", "synthetic" -> topUp.add(category, callBearing, uses, tool);
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

    /** Finds intermediate invocation results consumed as a receiver, a field target or an argument. */
    private static final class ResultUses extends ASTVisitor {
        /** Receiver, field or constructor syntax proves a reference result is consumed. */
        private boolean reference;
        /** An argument consumes a call result with an unknown return type. */
        private boolean unknownArgument;

        /** Visits supported call syntax independent of private names and target metadata. */
        static ResultUses inspect(SyntaxShape.Classification category, @Nullable Expression expression) {
            final ResultUses uses = new ResultUses();
            if (category.phase() == SyntaxShape.Phase.CALLS && expression != null) {
                expression.traverse(uses, (BlockScope) null);
            }
            return uses;
        }

        /** Counts the receiver and arguments of an ordinary call. */
        @Override
        public boolean visit(MessageSend call, BlockScope scope) {
            reference |= callResult(call.receiver);
            arguments(call.arguments);
            return true;
        }

        /** Counts call results passed to a constructor. */
        @Override
        public boolean visit(AllocationExpression call, BlockScope scope) {
            arguments(call.arguments);
            return true;
        }

        /** Counts a field read on a call result. */
        @Override
        public boolean visit(FieldReference field, BlockScope scope) {
            reference |= callResult(field.receiver);
            return true;
        }

        /** Records argument uses, distinguishing an allocation from a method's unknown return type. */
        private void arguments(Expression @Nullable [] arguments) {
            if (arguments != null) {
                for (Expression argument : arguments) {
                    if (argument instanceof AllocationExpression) {
                        reference = true;
                    } else {
                        unknownArgument |= callResult(argument);
                    }
                }
            }
        }

        /** Follows explicit casts and branches that forward the call's result. */
        private static boolean callResult(Expression expression) {
            if (expression instanceof CastExpression cast) {
                return callResult(cast.expression);
            }
            if (expression instanceof ConditionalExpression conditional) {
                return callResult(conditional.valueIfTrue) || callResult(conditional.valueIfFalse);
            }
            return expression instanceof MessageSend || expression instanceof AllocationExpression;
        }
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

        /** Rows with typed-by-syntax reference reuse, grouped by the tool that owns the source. */
        private final Map<String, Integer> referenceUses = new TreeMap<>();
        /** Rows with any potential object-result reuse, including arguments with unknown type. */
        private final Map<String, Integer> possibleUses = new TreeMap<>();

        /** Counts one row and records its generic frontend reason when it needs the compiler. */
        void add(SyntaxShape.Classification category, boolean callBearing, ResultUses uses, String tool) {
            final KindOfPlan kind = switch (category.phase()) {
                case NAVIGATION -> KindOfPlan.NAVIGATE;
                case CALLS -> KindOfPlan.INVOKE;
                case BLOCKS, COMPILED -> KindOfPlan.COMPILE;
                case ERROR -> KindOfPlan.ERROR;
            };
            plans.merge(kind, 1, Integer::sum);
            if (uses.reference) {
                referenceUses.merge(tool, 1, Integer::sum);
            }
            if (uses.reference || uses.unknownArgument) {
                possibleUses.merge(tool, 1, Integer::sum);
            }
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
            out.append("  Reference-result reuse by tool ").append(referenceUses).append('\n');
            out.append("  Possible result reuse, including untyped arguments ").append(possibleUses).append('\n');
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
