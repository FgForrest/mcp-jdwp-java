package one.edee.mcp.jdwp.evaluation.interpret;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

import static one.edee.mcp.jdwp.evaluation.interpret.PrimitiveOps.Kind;
import static org.eclipse.jdt.internal.compiler.ast.OperatorIds.*;

/** Syntax-only classification shared by the planner and corpus reporting. */
public final class SyntaxShape extends ASTVisitor {
    /**
     * Evaluation phase a snippet needs: navigation only, method calls, a statement block, the compiler, or none (syntax error).
     */
    public enum Phase { NAVIGATION, CALLS, BLOCKS, COMPILED, ERROR }

    /** A snippet's phase together with the syntax feature that decided it. */
    public record Classification(Phase phase, String reason) {}

    /** Reason of the first construct that needs the compiler, or null. */
    private @Nullable String compiled;
    /** Reason of the first construct that needs a method invocation, or null. */
    private @Nullable String calls;
    /** Whether the snippet is a statement block. */
    private boolean block;
    /** Whether the visit is for classification only, which also inspects concatenation operands. */
    private boolean sourceOnly;

    /** Created only by {@link #inspect}. */
    private SyntaxShape() {}

    /** Classifies a parsed snippet by the most demanding construct it contains. */
    public static Classification classify(ExpressionParser.Parsed parsed) {
        if (parsed.error() != null) {
            return new Classification(Phase.ERROR, "syntax error");
        }
        final SyntaxShape shape = inspect(parsed, true);
        if (shape.compiled != null) {
            return new Classification(Phase.COMPILED, shape.compiled);
        }
        if (shape.block) {
            return new Classification(Phase.BLOCKS, "statement block");
        }
        if (shape.calls != null) {
            return new Classification(Phase.CALLS, shape.calls);
        }
        return new Classification(Phase.NAVIGATION, "navigation syntax");
    }

    /** Returns the fallback plan, or null when the depth-checked expression may be navigated. */
    static Planner.@Nullable Plan routing(ExpressionParser.Parsed parsed) {
        if (parsed.error() != null) {
            return new Planner.Error(parsed.error());
        }
        if (parsed.statements() != null) {
            return new Planner.Compile(AstDepth.exceedsLimit(parsed) ? AstDepth.REASON : "statement block");
        }
        final SyntaxShape shape = inspect(parsed, false);
        if (shape.compiled != null) {
            return new Planner.Compile(shape.compiled);
        }
        return shape.calls == null ? null : new Planner.Invoke(shape.calls);
    }

    /** Checks depth before walking the parsed snippet to record the constructs it contains. */
    private static SyntaxShape inspect(ExpressionParser.Parsed parsed, boolean sourceOnly) {
        final SyntaxShape shape = new SyntaxShape();
        if (AstDepth.exceedsLimit(parsed)) {
            shape.compiled = AstDepth.REASON;
            return shape;
        }
        shape.sourceOnly = sourceOnly;
        if (parsed.statements() != null) {
            shape.block = true;
            for (Statement statement : parsed.statements()) {
                statement.traverse(shape, (BlockScope) null);
            }
        } else if (parsed.expression() != null) {
            parsed.expression().traverse(shape, (BlockScope) null);
        }
        return shape;
    }

    /** Records that the compiler is needed and stops descending into the node. */
    private boolean compiled(String reason) {
        if (compiled == null) {
            compiled = reason;
        }
        return false;
    }

    /** Records that a method invocation is needed and continues the traversal. */
    private boolean calls(String reason) {
        if (calls == null) {
            calls = reason;
        }
        return true;
    }

    /**
     * Allows only a few side-effect-free calls on an explicit receiver to stay in navigation; any other call needs invocation.
     */
    @Override
    public boolean visit(MessageSend call, BlockScope scope) {
        final String name = new String(call.selector);
        final int count = call.arguments == null ? 0 : call.arguments.length;
        if (call.typeArguments == null && !call.receiver.isImplicitThis() && !call.receiver.isSuper()
            && (count == 0 && (name.equals("length") || name.equals("isEmpty") || name.equals("name")
                || name.equals("ordinal") || boxConversion(name) != null)
                || count == 1 && (name.equals("equals") || name.equals("charAt")))) {
            return true;
        }
        return calls("method call");
    }

    /** Returns the primitive kind produced by a box's {@code xxxValue()} method, or null if the name is not one. */
    static @Nullable Kind boxConversion(String name) {
        return switch (name) {
            case "booleanValue" -> Kind.BOOLEAN;
            case "byteValue" -> Kind.BYTE;
            case "shortValue" -> Kind.SHORT;
            case "charValue" -> Kind.CHAR;
            case "intValue" -> Kind.INT;
            case "longValue" -> Kind.LONG;
            case "floatValue" -> Kind.FLOAT;
            case "doubleValue" -> Kind.DOUBLE;
            default -> null;
        };
    }

    /** Object construction needs invocation. */
    @Override
    public boolean visit(AllocationExpression expression, BlockScope scope) {
        return calls("object construction");
    }

    /** Anonymous classes need the compiler; other qualified construction needs invocation. */
    @Override
    public boolean visit(QualifiedAllocationExpression expression, BlockScope scope) {
        return expression.anonymousType != null ? compiled("anonymous class") : calls("object construction");
    }

    /** Array creation needs the compiler. */
    @Override
    public boolean visit(ArrayAllocationExpression expression, BlockScope scope) {
        return compiled("array creation");
    }

    /** Assignment to a simple name is accepted inside blocks; every other assignment needs the compiler. */
    @Override
    public boolean visit(Assignment expression, BlockScope scope) {
        return block && expression.lhs instanceof SingleNameReference || compiled("assignment");
    }

    /** Compound assignment needs the compiler. */
    @Override
    public boolean visit(CompoundAssignment expression, BlockScope scope) {
        return compiled("compound assignment");
    }

    /** Prefix increment or decrement needs the compiler. */
    @Override
    public boolean visit(PrefixExpression expression, BlockScope scope) {
        return compiled("increment or decrement");
    }

    /** Postfix increment or decrement needs the compiler. */
    @Override
    public boolean visit(PostfixExpression expression, BlockScope scope) {
        return compiled("increment or decrement");
    }

    /** A plain instanceof stays in navigation; a pattern needs invocation. */
    @Override
    public boolean visit(InstanceOfExpression expression, BlockScope scope) {
        return expression.pattern == null || calls("instanceof pattern");
    }

    /** Comparing a String constant by identity needs the compiler, because constants are interned. */
    @Override
    public boolean visit(EqualExpression expression, BlockScope scope) {
        if (stringConstant(expression.left) || stringConstant(expression.right)) {
            return compiled("String constant identity");
        }
        return true;
    }

    /** Whether the expression is a compile-time String constant made of literals, concatenation and casts. */
    private static boolean stringConstant(Expression expression) {
        return expression instanceof StringLiteral
            || expression instanceof BinaryExpression binary && operator(binary) == PLUS
                && (stringConstant(binary.left) && binary.right instanceof Literal
                    || stringConstant(binary.right) && binary.left instanceof Literal)
            || expression instanceof CastExpression cast && stringConstant(cast.expression);
    }

    /** Extracts the operator id stored in the node's bits. */
    private static int operator(ASTNode expression) {
        return (expression.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
    }

    /**
     * In source-only mode, checks the operands of a constant String concatenation for forms that cannot be navigated.
     */
    @Override
    public boolean visit(BinaryExpression expression, BlockScope scope) {
        if (sourceOnly && operator(expression) == PLUS) {
            if (stringConstant(expression.left)) {
                inspectConcatOperand(expression.right);
            }
            if (stringConstant(expression.right)) {
                inspectConcatOperand(expression.left);
            }
        }
        return true;
    }

    /**
     * Marks a concatenation operand whose text depends on floating-point or object formatting as needing invocation.
     */
    private void inspectConcatOperand(Expression operand) {
        if (operand instanceof FloatLiteral || operand instanceof DoubleLiteral) {
            calls("floating-point String concatenation");
        } else if (operand instanceof ThisReference) {
            calls("object String concatenation");
        } else if (operand instanceof CastExpression cast) {
            final char[][] name = cast.type.getTypeName();
            final String last = new String(name[name.length - 1]);
            if (cast.type.dimensions() != 0 || !Arrays.asList("boolean", "byte", "short", "char", "int", "long",
                    "float", "double", "String").contains(last)) {
                calls("object String concatenation");
            } else if (last.equals("float") || last.equals("double")) {
                calls("floating-point String concatenation");
            }
        }
    }

    /** Assert statements need the compiler. */
    /** Do-while loops need the compiler. */
    /** Enhanced for loops need the compiler. */
    /** For loops need the compiler. */
    /** Lambdas need the compiler. */
    /** Local type declarations need the compiler. */
    /** Method references need the compiler. */
    /** Switch expressions need the compiler. */
    /** Switch statements need the compiler. */
    /** Synchronized blocks need the compiler. */
    /** Throw statements need the compiler. */
    /** Try statements need the compiler. */
    /** While loops need the compiler. */
    @Override public boolean visit(LambdaExpression node, BlockScope scope) { return compiled("lambda"); }
    @Override public boolean visit(ReferenceExpression node, BlockScope scope) { return compiled("method reference"); }
    @Override public boolean visit(SwitchExpression node, BlockScope scope) { return compiled("switch expression"); }
    @Override public boolean visit(SwitchStatement node, BlockScope scope) { return compiled("switch statement"); }
    @Override public boolean visit(TryStatement node, BlockScope scope) { return compiled("try statement"); }
    @Override public boolean visit(ThrowStatement node, BlockScope scope) { return compiled("throw statement"); }
    @Override public boolean visit(SynchronizedStatement node, BlockScope scope) { return compiled("synchronized"); }
    @Override public boolean visit(TypeDeclaration node, BlockScope scope) { return compiled("local type"); }
    @Override public boolean visit(ForeachStatement node, BlockScope scope) { return compiled("loop"); }
    @Override public boolean visit(ForStatement node, BlockScope scope) { return compiled("loop"); }
    @Override public boolean visit(WhileStatement node, BlockScope scope) { return compiled("loop"); }
    @Override public boolean visit(DoStatement node, BlockScope scope) { return compiled("loop"); }
    @Override public boolean visit(AssertStatement node, BlockScope scope) { return compiled("assert statement"); }
}
