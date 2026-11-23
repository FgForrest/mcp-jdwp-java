package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Location;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.jspecify.annotations.Nullable;

import java.io.Serial;
import java.util.*;

import static one.edee.mcp.jdwp.evaluation.interpret.JdiTypes.StaticType;
import static one.edee.mcp.jdwp.evaluation.interpret.PrimitiveOps.Kind;
import static org.eclipse.jdt.internal.compiler.ast.OperatorIds.*;

/** Resolves and types both sides of each expression before the interpreter evaluates either side. */
public final class Planner {
    /** Discriminator of the plan variants. */
    public enum KindOfPlan { NAVIGATE, INVOKE, COMPILE, ERROR }

    /**
     * Outcome of planning: an executable navigation, or the reason it must be invoked, compiled or reported as an error.
     */
    public sealed interface Plan permits Navigate, Invoke, Compile, Error {
        /** Returns the variant of this plan. */
        KindOfPlan kind();
    }

    /** A navigation plan and the frame to validate after executing its snapshot, if it used one. */
    public record Navigate(VirtualMachine vm, Interpreter.Read read, @Nullable StackFrame frame) implements Plan {
        /** Returns {@link KindOfPlan#NAVIGATE}. */
        @Override public KindOfPlan kind() { return KindOfPlan.NAVIGATE; }
    }

    /**
     * An interpreted plan that may invoke target code.
     *
     * @param vm virtual machine that owns the plan's mirrors
     * @param read executable expression plan
     */
    public record Invoke(VirtualMachine vm, Interpreter.Read read) implements Plan {
        /** Returns {@link KindOfPlan#INVOKE}. */
        @Override public KindOfPlan kind() { return KindOfPlan.INVOKE; }
    }

    /** A plan that must go to the compiler. */
    public record Compile(String reason) implements Plan {
        /** Returns {@link KindOfPlan#COMPILE}. */
        @Override public KindOfPlan kind() { return KindOfPlan.COMPILE; }
    }

    /** A plan that reports a definite error in the expression. */
    public record Error(String message) implements Plan {
        /** Returns {@link KindOfPlan#ERROR}. */
        @Override public KindOfPlan kind() { return KindOfPlan.ERROR; }
    }

    /** Prepares a value for use by target code, preserving string identity and code units. */
    @FunctionalInterface
    private interface TargetUse {
        /** Binds the boundary operation while compiler fallback is still possible. */
        Interpreter.Read prepare();
    }

    /**
     * A typed value reader with the source information needed to preserve Java semantics.
     *
     * @param type static expression type
     * @param read executable reader for the value
     * @param constant proven Java constant value, or null when unavailable
     * @param constantCandidate whether the expression may be a Java constant
     * @param stringIdentity whether a String constant may affect identity comparison
     * @param runtimeType captured runtime type for erased lookup, or null if unavailable
     * @param targetUse reader preparation for a value passed into target code
     * @param losslessConcat whether the concatenation is safe across a JDWP string transfer
     * @param floatingOperand whether floating-point provenance affects target string formatting
     * @param erasedResult JVM result type when it differs from the generic source type
     */
    private record Node(StaticType type, Interpreter.Read read, @Nullable Object constant,
                        boolean constantCandidate, boolean stringIdentity, @Nullable ReferenceType runtimeType,
                        @Nullable TargetUse targetUse, boolean losslessConcat, boolean floatingOperand,
                        @Nullable ReferenceType erasedResult) {
        /** Creates a node with string boundary information and no erased result. */
        Node(StaticType type, Interpreter.Read read, @Nullable Object constant, boolean constantCandidate,
             boolean stringIdentity, @Nullable ReferenceType runtimeType, @Nullable TargetUse targetUse,
             boolean losslessConcat) {
            this(type, read, constant, constantCandidate, stringIdentity, runtimeType, targetUse, losslessConcat,
                isFloating(type), null);
        }

        /** Creates an ordinary node whose value needs no string boundary conversion. */
        Node(StaticType type, Interpreter.Read read, @Nullable Object constant, boolean constantCandidate,
             boolean stringIdentity, @Nullable ReferenceType runtimeType) {
            this(type, read, constant, constantCandidate, stringIdentity, runtimeType, null, false);
        }

        /** Creates a node that is not a constant. */
        Node(StaticType type, Interpreter.Read read) {
            this(type, read, null, false, false, null);
        }

        /** Whether Java could treat this node as a compile-time constant. */
        boolean potentiallyConstant() {
            return constantCandidate || constant != null;
        }
    }

    /**
     * A pattern variable visible on a definitely matched branch.
     *
     * @param name local name used in the expression
     * @param value reader for the matched value
     * @param enclosing previously visible pattern binding, or null at the end of the scope chain
     */
    private record PatternBinding(String name, Node value, @Nullable PatternBinding enclosing) {}

    /** A visible local variable of the frame with its value at the time of planning. */
    private record Local(LocalVariable declaration, @Nullable Value value) {}

    /** Type declaring the frame's method, or null without a frame. */
    private final @Nullable ReferenceType declaring;
    /** Node reading {@code this}, or null in a static context or without a frame. */
    private final @Nullable Node receiver;
    /** Names of the variables visible to the expression, for the unresolved-name message. */
    private final Set<String> names = new TreeSet<>();
    /** Locals of the frame by name. */
    private final Map<String, Local> locals = new HashMap<>();
    /** Debugger-held values, by name, visible to the expression. */
    private final Map<String, Value> bindings;
    /** True when execution may invoke, including on a branch that may be skipped. */
    private boolean invokes;
    /** Set of every pattern name, including declarations in unsupported truth paths. */
    private final Set<String> patternNames = new HashSet<>();
    /** Refusal reason for steps that can run only in the navigation engine. */
    private @Nullable String navigationOnly;
    /** Whether the first pass relied on host String intrinsics. */
    private boolean usedStringIntrinsic;
    /** Whether String intrinsics must bind their target methods in this plan. */
    private boolean targetStringIntrinsics;
    /** Flow-scoped bindings visible on the current planning branch. */
    private @Nullable PatternBinding patterns;
    /** Type lookup scoped to the declaring type. */
    private final JdiTypes types;

    /** Captures the frame's method, receiver and locals before planning the expression. */
    private Planner(VirtualMachine vm, @Nullable StackFrame frame, @Nullable Location location,
                    Map<String, Value> bindings) {
        this.bindings = bindings;
        this.declaring = location == null ? null : location.declaringType();
        this.types = new JdiTypes(vm, declaring, location == null ? null : location.method());
        final ObjectReference self = frame == null ? null : frame.thisObject();
        this.receiver = self == null || declaring == null ? null
            : new Node(JdiTypes.of(declaring, null), context -> self);
        names.addAll(bindings.keySet());
        if (frame != null) {
            try {
                final List<LocalVariable> variables = frame.visibleVariables();
                if (!variables.isEmpty()) {
                    final Map<LocalVariable, Value> values = frame.getValues(variables);
                    for (LocalVariable variable : variables) {
                        locals.put(variable.name(), new Local(variable, values.get(variable)));
                    }
                    names.addAll(locals.keySet());
                }
            } catch (AbsentInformationException ignored) {
                // This method has no local-variable table. Fields and debugger bindings still work.
            }
        }
    }

    /**
     * Routes unsupported syntax first, then binds supported expressions against the frame.
     */
    public static Plan plan(VirtualMachine vm, @Nullable StackFrame frame, Map<String, Value> bindings,
                            ExpressionParser.Parsed parsed) {
        final Plan syntax = SyntaxShape.routing(parsed);
        if (syntax != null) {
            return syntax;
        }
        return navigation(vm, frame, frame == null ? null : frame.location(), bindings,
            Objects.requireNonNull(parsed.expression()));
    }

    /** Plans a depth-checked expression from {@link SyntaxShape#routing}, returning any fallback the expression needs. */
    static Plan navigation(VirtualMachine vm, @Nullable StackFrame frame, @Nullable Location location,
                           Map<String, Value> bindings, Expression expression) {
        try {
            final Planner planner = new Planner(vm, frame, location, bindings);
            planner.collectPatternNames(expression);
            Node root = planner.node(expression);
            if (planner.invokes && planner.usedStringIntrinsic) {
                // Host String intrinsics can discover a transfer refusal. Bind the real calls
                // before execution when an expression can invoke anywhere.
                planner.targetStringIntrinsics = true;
                root = planner.node(expression);
            }
            if (planner.invokes) {
                if (planner.navigationOnly != null) {
                    return new Compile(planner.navigationOnly);
                }
                if (frame != null) {
                    // Confirm the snapshot before admission can invalidate the frame.
                    frame.location();
                }
                return new Invoke(vm, root.read());
            }
            return new Navigate(vm, root.read(), frame);
        } catch (Refusal e) {
            return e.plan;
        } catch (ClassNotLoadedException e) {
            return new Compile("type is not loaded: " + e.className());
        } catch (ClassNotPreparedException e) {
            return new Compile("type is not prepared");
        } catch (ObjectCollectedException e) {
            return new Compile("object was collected");
        }
    }

    /** Aborts planning with the plan that takes over. */
    static final class Refusal extends RuntimeException {
        /** Serialization version of this exception. */
        @Serial
        private static final long serialVersionUID = 1L;
        /** Plan that replaces the navigation. */
        final Plan plan;

        /** Creates the refusal without a stack trace; it is control flow. */
        Refusal(Plan plan) {
            super(null, null, false, false);
            this.plan = plan;
        }
    }

    /** Creates a refusal that sends the expression to the compiler. */
    static Refusal compile(String reason) {
        return new Refusal(new Compile(reason));
    }

    /** Creates a refusal that reports an error. */
    private static Refusal error(String message) {
        return new Refusal(new Error(message));
    }

    /** Collects names from all branches before any of those names can bind to a local, field or type. */
    private void collectPatternNames(Expression expression) {
        expression.traverse(new ASTVisitor() {
            /** Records every supported type pattern regardless of its flow scope. */
            @Override
            public boolean visit(InstanceOfExpression test, BlockScope scope) {
                if (test.pattern instanceof TypePattern pattern) {
                    patternNames.add(new String(pattern.local.name));
                }
                return true;
            }
        }, (BlockScope) null);
    }

    /** Marks a step whose lookup or transfer may refuse at runtime on the read-only path. */
    private void requireNavigation(String reason) {
        if (navigationOnly == null) {
            navigationOnly = reason;
        }
    }

    /** Plans one syntax node, dispatching on its kind. */
    private Node node(Expression expression) throws ClassNotLoadedException {
        if (expression instanceof Literal literal) {
            return literal(literal);
        }
        if (expression instanceof SingleNameReference name) {
            return name(List.of(new String(name.token)));
        }
        if (expression instanceof QualifiedNameReference name) {
            return name(path(name));
        }
        if (expression instanceof FieldReference access) {
            return select(node(access.receiver), new String(access.token));
        }
        if (expression instanceof QualifiedSuperReference parent) {
            return superclass(qualifiedThis(reference(parent.qualification)));
        }
        if (expression instanceof SuperReference) {
            if (receiver == null) {
                throw error("'super' is unavailable in this frame");
            }
            return superclass(receiver);
        }
        if (expression instanceof QualifiedThisReference self) {
            return qualifiedThis(reference(self.qualification));
        }
        if (expression instanceof ThisReference) {
            if (receiver == null) {
                throw error("'this' is unavailable in this frame");
            }
            return receiver;
        }
        if (expression instanceof ClassLiteralAccess literal) {
            return classLiteral(literal.type);
        }
        if (expression instanceof org.eclipse.jdt.internal.compiler.ast.ArrayReference access) {
            return index(node(access.receiver), node(access.position));
        }
        if (expression instanceof UnaryExpression unary) {
            return unary(operator(unary), node(unary.expression));
        }
        if (expression instanceof BinaryExpression binary) {
            final int op = operator(binary);
            if (op == AND_AND) {
                return and(binary);
            }
            return binary(op, node(binary.left), node(binary.right));
        }
        if (expression instanceof ConditionalExpression conditional) {
            final PatternBinding enclosing = patterns;
            final Node condition;
            final Node yes;
            try {
                condition = trueCondition(conditional.condition);
                yes = node(conditional.valueIfTrue);
            } finally {
                patterns = enclosing;
            }
            return conditional(condition, yes, node(conditional.valueIfFalse));
        }
        if (expression instanceof InstanceOfExpression test) {
            return instanceOf(test, false);
        }
        if (expression instanceof CastExpression cast) {
            return cast(node(cast.expression), type(cast.type));
        }
        if (expression instanceof MessageSend call) {
            return methodCall(call);
        }
        if (expression instanceof AllocationExpression allocation) {
            return constructor(allocation);
        }
        throw compile("unsupported expression: " + expression.getClass().getSimpleName());
    }

    /** Plans a standalone conjunction and closes the scope of its pattern bindings. */
    private Node and(BinaryExpression expression) throws ClassNotLoadedException {
        final PatternBinding enclosing = patterns;
        try {
            return trueCondition(expression);
        } finally {
            patterns = enclosing;
        }
    }

    /** Plans a condition and leaves only bindings that are definitely matched when it is true. */
    private Node trueCondition(Expression expression) throws ClassNotLoadedException {
        if (expression instanceof BinaryExpression binary && operator(binary) == AND_AND) {
            return binary(AND_AND, trueCondition(binary.left), trueCondition(binary.right));
        }
        if (expression instanceof InstanceOfExpression test) {
            return instanceOf(test, true);
        }
        return node(expression);
    }

    /** Proves pattern applicability, then reads the operand once and shares the successful match. */
    private Node instanceOf(InstanceOfExpression test, boolean exports) throws ClassNotLoadedException {
        final Node value = node(test.expression);
        final Pattern pattern = test.pattern;
        if (pattern != null && !(pattern instanceof TypePattern)) {
            throw compile("unsupported instanceof pattern");
        }
        final TypeReference syntax = pattern instanceof TypePattern typePattern ? typePattern.local.type : test.type;
        final ReferenceType target = reference(syntax);
        if (pattern == null) {
            checkReferenceCast(value.type(), target);
        } else {
            checkPatternApplicability(value.type(), target);
        }
        if (target instanceof ArrayType) {
            requireNavigation("array type test needs runtime component metadata");
        }
        final Object slot;
        if (pattern instanceof TypePattern typePattern) {
            final String name = new String(typePattern.local.name);
            if (locals.containsKey(name) || pattern(name) != null) {
                throw compile("pattern variable shadows a local: " + name);
            }
            if (exports) {
                final Object key = new Object();
                // Preserve the single scrutinee read. Compile if a debugger-created String escapes.
                final TargetUse boundary = value.targetUse() == null ? null : () -> {
                    throw compile("pattern binding carries a debugger-created String into a call");
                };
                final Node binding = new Node(JdiTypes.of(target, null), ctx -> ctx.pattern(key),
                    null, false, false, null, boundary, false);
                patterns = new PatternBinding(name, binding, patterns);
                slot = key;
            } else {
                slot = null;
            }
        } else {
            slot = null;
        }
        return new Node(JdiTypes.primitive(Kind.BOOLEAN), ctx -> {
            final Value tested = value.read().run(ctx);
            final boolean matches = tested instanceof ObjectReference object
                && JdiTypes.assignable(object.referenceType(), target);
            if (matches && slot != null) {
                ctx.bind(slot, Objects.requireNonNull(ctx.keep(tested)));
            }
            return ctx.mirror(matches);
        });
    }

    /** Proves a checked casting conversion and excludes unconditional patterns on older targets. */
    private void checkPatternApplicability(StaticType from, ReferenceType to) throws ClassNotLoadedException {
        if (!types.plainPatternType(from) || !types.plainPatternReference(to)) {
            throw compile("binding pattern requires provable source types");
        }
        final ReferenceType source = from.reference();
        final boolean unconditional = source == null || JdiTypes.assignable(source, to);
        // Ordered, non-generic reference types prove a checked conversion. For unrelated types
        // JDI cannot fully prove the narrowing rules in JLS 5.5, including sealed hierarchies.
        if (!unconditional && source != null && !JdiTypes.assignable(to, source)) {
            throw compile("binding pattern requires a provable casting conversion");
        }
        if (unconditional && !types.supportsUnconditionalPatterns()) {
            throw compile("unconditional binding pattern requires Java 21 or later");
        }
    }

    /** Extracts the operator id stored in the node's bits. */
    private static int operator(ASTNode node) {
        return (node.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
    }

    /** Plans a literal as a constant node, declining literals with isolated surrogate code units. */
    private Node literal(Literal literal) {
        if (literal instanceof NullLiteral) {
            return new Node(JdiTypes.NULL, ctx -> null);
        }
        synchronized (literal) {
            // ECJ populates the literal in place. A parsed watcher can be planned again.
            literal.computeConstant();
            final Constant value = literal.constant;
            if (value == null || value == Constant.NotAConstant) {
                throw error("Invalid or out-of-range literal: " + literal);
            }
            if (literal instanceof StringLiteral) {
                if (literal instanceof StringLiteralConcatenation concat) {
                    // ECJ can retain component literals without flattening their source.
                    final StringBuilder text = new StringBuilder();
                    for (StringLiteral component : concat.getLiterals()) {
                        if (component != null) {
                            component.computeConstant();
                            text.append(checkedStringLiteral(component.constant.stringValue()));
                        }
                    }
                    return stringConstant(text.toString());
                }
                return stringConstant(checkedStringLiteral(value.stringValue()));
            }
            if (literal instanceof TrueLiteral || literal instanceof FalseLiteral) {
                return constant(Kind.BOOLEAN, value.booleanValue());
            }
            if (literal instanceof CharLiteral) {
                final char codeUnit = value.charValue();
                if (Character.isSurrogate(codeUnit)) {
                    throw compile("literal contains an unpaired surrogate");
                }
                return constant(Kind.CHAR, codeUnit);
            }
            if (literal instanceof IntLiteral) {
                return constant(Kind.INT, value.intValue());
            }
            if (literal instanceof LongLiteral) {
                return constant(Kind.LONG, value.longValue());
            }
            if (literal instanceof FloatLiteral) {
                return constant(Kind.FLOAT, value.floatValue());
            }
            if (literal instanceof DoubleLiteral) {
                return constant(Kind.DOUBLE, value.doubleValue());
            }
        }
        throw compile("unsupported literal");
    }

    /** Checks one String literal before any concatenation can mask an isolated surrogate. */
    private static String checkedStringLiteral(String value) {
        if (value.codePoints().anyMatch(codePoint ->
                Character.MIN_SURROGATE <= codePoint && codePoint <= Character.MAX_SURROGATE)) {
            throw compile("literal contains an unpaired surrogate");
        }
        return value;
    }

    /** Plans a String constant with its interned identity preserved when it crosses into target code. */
    private Node stringConstant(String value) {
        final ReferenceType string = types.bootstrap("java.lang.String");
        final Interpreter.Read read = ctx -> ctx.mirror(value);
        return new Node(JdiTypes.of(string, null), read, value, true, true, null,
            () -> intern(string, read), false);
    }

    /** Plans a primitive constant. */
    private static Node constant(Kind kind, Object value) {
        return new Node(JdiTypes.primitive(kind), ctx -> ctx.mirror(value), value, true, false, null);
    }

    /** Plans a variable or static field path and reports a name that cannot be found. */
    private Node name(List<String> path) throws ClassNotLoadedException {
        final Node value = valuePath(path);
        if (value != null) {
            return value;
        }
        final String first = path.get(0);
        if (path.size() == 1 || first.startsWith("$")) {
            throw error("Unresolved variable '" + first + "'. Visible names: "
                + (names.isEmpty() ? "(none)" : String.join(", ", names)));
        }
        throw compile("type or variable is unavailable: " + String.join(".", path));
    }

    /** Looks up a dotted value path, giving a variable or field precedence over a type name. */
    private @Nullable Node valuePath(List<String> path) throws ClassNotLoadedException {
        final Node value = variable(path.get(0));
        if (value != null) {
            return selectPath(value, path, 1);
        }
        // A type prefix is followed by at least one field. Try the leftmost name first.
        for (int end = 1; end < path.size(); end++) {
            final ReferenceType type = types.resolve(String.join(".", path.subList(0, end)), true);
            if (type != null) {
                final Field member = JdiTypes.field(type, path.get(end), false);
                if (member != null) {
                    return selectPath(field(member, null), path, end + 1);
                }
            }
        }
        return null;
    }

    /** Converts ECJ's token array into the components of a qualified name. */
    private static List<String> path(QualifiedNameReference syntax) {
        final List<String> path = new ArrayList<>(syntax.tokens.length);
        for (char[] token : syntax.tokens) {
            path.add(new String(token));
        }
        return path;
    }

    /** Applies the field selections of the path from {@code start} onwards. */
    private Node selectPath(Node value, List<String> path, int start) throws ClassNotLoadedException {
        Node result = value;
        for (int i = start; i < path.size(); i++) {
            result = select(result, path.get(i));
        }
        return result;
    }

    /** Looks for a definitely matched pattern variable in the current flow scope. */
    private @Nullable Node pattern(String name) {
        for (PatternBinding binding = patterns; binding != null; binding = binding.enclosing()) {
            if (binding.name().equals(name)) {
                return binding.value();
            }
        }
        return null;
    }

    /** Resolves a pattern, local, binding or field of this class or an enclosing class. */
    private @Nullable Node variable(String name) throws ClassNotLoadedException {
        final Node pattern = pattern(name);
        if (pattern != null) {
            return pattern;
        }
        if (patternNames.contains(name)) {
            throw compile("pattern variable is outside the supported scope: " + name);
        }
        final Local local = locals.get(name);
        if (local != null) {
            final StaticType type = JdiTypes.of(local.declaration().type(), local.declaration().genericSignature());
            final @Nullable Value value = local.value();
            return new Node(type, ctx -> value, null, false, false,
                type.imprecise() && value instanceof ObjectReference object ? object.referenceType() : null);
        }
        if (bindings.containsKey(name)) {
            final @Nullable Value value = bindings.get(name);
            return new Node(value == null ? JdiTypes.NULL : JdiTypes.of(value.type(), null), ctx -> value);
        }
        if ("_this".equals(name) && receiver != null) {
            return receiver;
        }
        final ReferenceType owner = declaring;
        if (owner == null) {
            return null;
        }
        final Field own = JdiTypes.field(owner, name, false);
        if (own != null && (receiver != null || own.isStatic())) {
            return field(own, receiver);
        }
        String binary = owner.name();
        for (int cut = binary.lastIndexOf('$'); cut > binary.lastIndexOf('.'); cut = binary.lastIndexOf('$')) {
            binary = binary.substring(0, cut);
            final ReferenceType enclosing = types.findExact(binary);
            if (enclosing != null) {
                final Field member = JdiTypes.field(enclosing, name, false);
                if (member != null && member.isStatic()) {
                    return field(member, null);
                }
            }
        }
        Node outer = receiver == null ? null : outer(receiver);
        while (outer != null) {
            final Field member = JdiTypes.field(Objects.requireNonNull(outer.type().reference()), name, false);
            if (member != null) {
                return field(member, outer);
            }
            outer = outer(outer);
        }
        return null;
    }

    /** Returns the enclosing instance of an inner class instance, or null when it has none. */
    private @Nullable Node outer(Node instance) throws ClassNotLoadedException {
        final ReferenceType type = Objects.requireNonNull(instance.type().reference());
        for (Field field : type.fields()) {
            if (field.isSynthetic() && field.name().startsWith("this$") && !field.isStatic()) {
                return field(field, instance);
            }
        }
        return null;
    }

    /** Finds the enclosing instance of the target type, walking outwards from {@code this}. */
    private Node qualifiedThis(ReferenceType target) throws ClassNotLoadedException {
        for (Node current = receiver; current != null; current = outer(current)) {
            if (target.equals(current.type().reference())) {
                return current;
            }
        }
        throw compile("no enclosing instance of " + target.name());
    }

    /** Views the instance as its superclass. */
    private static Node superclass(Node instance) {
        if (instance.type().reference() instanceof ClassType type && type.superclass() != null) {
            return new Node(JdiTypes.of(type.superclass(), null), instance.read());
        }
        throw compile("superclass is unavailable");
    }

    /** Plans a field selection, including the length of an array. */
    private Node select(Node instance, String name) throws ClassNotLoadedException {
        final StaticType type = instance.type();
        if (type.reference() instanceof ArrayType && name.equals("length")) {
            final Interpreter.Read read = memberRead(instance, instance.read());
            return new Node(JdiTypes.primitive(Kind.INT), ctx -> ctx.mirror(
                ((ArrayReference) Objects.requireNonNull(read.run(ctx))).length()));
        }
        final ReferenceType lookup = type.imprecise() ? instance.runtimeType() : type.reference();
        if (lookup == null) {
            if (type.imprecise()) {
                requireNavigation("field lookup depends on an erased receiver: " + name);
                return deferredField(instance, name);
            }
            throw compile("field receiver has no reference type: " + name);
        }
        final Field member = type.bounds().isEmpty()
            ? JdiTypes.field(lookup, name, type.imprecise()) : JdiTypes.field(type.bounds(), name);
        if (member == null) {
            throw compile("field is unavailable: " + lookup.name() + "." + name);
        }
        return field(member, instance);
    }

    /** Plans a field selection on an erased type, resolving the field on the runtime class when the node runs. */
    private static Node deferredField(Node instance, String name) throws ClassNotLoadedException {
        final ReferenceType type = instance.type().reference();
        final Field known = type == null ? null : JdiTypes.field(type, name, false);
        final StaticType result = known == null ? JdiTypes.DEFERRED : JdiTypes.of(known.type(), known.genericSignature());
        if (known != null) {
            checkInitialized(known);
        }
        final boolean constantCandidate = known != null && known.isFinal()
            && (result.kind() != null || result.isString());
        return new Node(result, ctx -> {
            final @Nullable Value value = instance.read().run(ctx);
            if (value == null && known != null && known.isStatic()) {
                return known.declaringType().getValue(known);
            }
            if (!(Objects.requireNonNull(value) instanceof ObjectReference object)) {
                throw compile("field receiver has no reference type: " + name);
            }
            if (object instanceof ArrayReference array && name.equals("length")) {
                return ctx.mirror(array.length());
            }
            final ReferenceType runtime = object.referenceType();
            final Field member = JdiTypes.field(runtime, name, true);
            if (member == null) {
                throw compile("field is unavailable: " + runtime.name() + "." + name);
            }
            checkInitialized(member);
            try {
                member.type();
            } catch (ClassNotLoadedException e) {
                throw compile("type is not loaded: " + e.className());
            }
            return member.isStatic() ? member.declaringType().getValue(member) : object.getValue(member);
        }, null, constantCandidate, constantCandidate && result.isString(), null);
    }

    /** Refuses reading a static field whose class is not yet initialized. */
    private static void checkInitialized(Field field) {
        final ReferenceType owner = field.declaringType();
        if (field.isStatic() && !owner.isInitialized()) {
            throw compile("static field's class is not initialized: " + owner.name());
        }
    }

    /** Plans a read of a static or instance field. */
    private Node field(Field field, @Nullable Node instance) throws ClassNotLoadedException {
        final ReferenceType declaring = field.declaringType();
        final boolean isStatic = field.isStatic();
        checkInitialized(field);
        if (!isStatic && instance == null) {
            throw compile("instance field requires a receiver: " + field.name());
        }
        final Type erased = field.type();
        final StaticType type = GenericSignatures.resultType(types, erased, field.genericSignature(),
            instance == null ? null : instance.type(), declaring);
        final Interpreter.Read read = instance == null ? ctx -> null : memberRead(instance, instance.read());
        // JDI omits the ConstantValue attribute. A final primitive or String field can be
        // inlined by Java. Track the possibility where it affects identity or numeric narrowing.
        final boolean constantCandidate = field.isFinal() && (type.kind() != null || type.isString());
        return new Node(type, ctx -> {
            final @Nullable Value value = read.run(ctx);
            return isStatic ? declaring.getValue(field)
                : ((ObjectReference) Objects.requireNonNull(value)).getValue(field);
        }, null, constantCandidate, type.isString() && constantCandidate, null, null, false,
            isFloating(type), erasureForCast(type, erased));
    }

    /** Resolves a source type, requiring a binding for an array's simple reference component name. */
    private StaticType type(TypeReference syntax) {
        if (syntax instanceof IntersectionCastTypeReference intersection) {
            final List<ReferenceType> bounds = Arrays.stream(intersection.typeReferences)
                .map(this::reference).toList();
            if (new HashSet<>(bounds).size() != bounds.size()
                || bounds.stream().skip(1).anyMatch(bound -> !(bound instanceof InterfaceType))) {
                throw compile("invalid intersection cast");
            }
            return new StaticType(null, bounds.get(0), false, bounds);
        }
        if (syntax.getTypeArguments() != null) {
            throw compile("parameterized type");
        }
        final String name = Arrays.stream(syntax.getTypeName()).map(String::new)
            .reduce((a, b) -> a + "." + b).orElseThrow();
        final int firstDot = name.indexOf('.');
        final String first = firstDot < 0 ? name : name.substring(0, firstDot);
        if (patternNames.contains(first) && pattern(first) == null) {
            throw compile("pattern variable is outside the supported scope: " + first);
        }
        final Optional<Kind> primitive = primitiveNamed(name);
        if (syntax.dimensions() == 0 && primitive.isPresent()) {
            return JdiTypes.primitive(primitive.get());
        }
        final ReferenceType ref;
        if (syntax.dimensions() > 0) {
            final ReferenceType component = primitive.isPresent() ? null : types.resolve(name);
            if (component == null && primitive.isEmpty() && !name.contains(".")) {
                throw compile("type is not loaded or visible: " + name);
            }
            final String arrayName = (component == null ? name : component.name()) + "[]".repeat(syntax.dimensions());
            ref = types.findExact(arrayName);
        } else {
            ref = types.resolve(name);
        }
        if (ref == null) {
            throw compile("type is not loaded or visible: " + name);
        }
        return JdiTypes.of(ref, null);
    }

    /** Returns the primitive kind with the given Java name, if any. */
    private static Optional<Kind> primitiveNamed(String name) {
        return Arrays.stream(Kind.values()).filter(kind -> kind.name().toLowerCase(Locale.ROOT).equals(name))
            .findFirst();
    }

    /** Resolves a type written in the source that must be a reference type. */
    private ReferenceType reference(TypeReference syntax) {
        final ReferenceType type = type(syntax).reference();
        if (type == null) {
            throw compile("expected a reference type");
        }
        return type;
    }

    /** Plans {@code Type.class}, reading the box's {@code TYPE} field for primitives. */
    private Node classLiteral(TypeReference syntax) throws ClassNotLoadedException {
        final StaticType valueType = type(syntax);
        if (valueType.reference() != null) {
            final ReferenceType value = valueType.reference();
            return new Node(JdiTypes.of(types.bootstrap("java.lang.Class"), null), ctx -> value.classObject());
        }
        final ReferenceType box = types.box(Objects.requireNonNull(valueType.kind()));
        final Field value = box.fieldByName("TYPE");
        if (value == null) {
            throw compile("primitive class mirror is unavailable");
        }
        return field(value, null);
    }

    /** Plans an array element read with a bounds check. */
    private static Node index(Node array, Node index) throws ClassNotLoadedException {
        if (!(array.type().reference() instanceof ArrayType type)) {
            throw compile("array receiver has no array type");
        }
        if (primitiveKind(index).promote() != Kind.INT) {
            throw compile("array index requires int promotion");
        }
        final Field unbox = unbox(index);
        final String signature = array.type().genericSignature();
        final String component = signature != null && signature.startsWith("[") ? signature.substring(1) : null;
        final Interpreter.Read read = memberRead(array, array.read());
        final Interpreter.Read readIndex = unboxRead(index, unbox);
        return new Node(JdiTypes.of(type.componentType(), component), ctx -> {
            final @Nullable Value receiver = ctx.keep(read.run(ctx));
            final int i = (Integer) Kind.INT.convert(Interpreter.primitive(readIndex.run(ctx), unbox));
            final ArrayReference ref = (ArrayReference) Objects.requireNonNull(receiver);
            if (i < 0 || i >= ref.length()) {
                throw new ArrayIndexOutOfBoundsException(i);
            }
            return ref.getValue(i);
        });
    }

    /** Returns the primitive kind of a primitive or box operand, or refuses the plan. */
    private static Kind primitiveKind(Node node) {
        final Kind kind = JdiTypes.unboxed(node.type());
        if (kind == null || kind == Kind.VOID) {
            throw compile("operand requires a primitive or a primitive box");
        }
        return kind;
    }

    /** Returns the value field to read when the operand is a box, or null for other operands. */
    private static @Nullable Field unbox(Node node) {
        final ReferenceType type = node.type().reference();
        if (type == null) {
            return null;
        }
        final Field value = type.fieldByName("value");
        if (value == null) {
            throw compile("primitive box has no value field");
        }
        return value;
    }

    /** Checks the receiver of a wrapper value-field read before using it for unboxing. */
    private static Interpreter.Read unboxRead(Node node, @Nullable Field unbox) {
        return unbox == null ? node.read() : memberRead(node, node.read());
    }

    /** Whether this static type is a floating-point primitive. */
    private static boolean isFloating(StaticType type) {
        return type.kind() == Kind.FLOAT || type.kind() == Kind.DOUBLE;
    }

    /** Plans a unary operator and folds it for a constant operand. */
    private static Node unary(int op, Node operand) {
        final Kind kind = primitiveKind(operand).promote();
        if ((op == NOT && kind != Kind.BOOLEAN) || (op == TWIDDLE && !kind.integral())
                || ((op == PLUS || op == MINUS) && !kind.numeric())) {
            throw compile("invalid unary operand type");
        }
        final Field unbox = unbox(operand);
        final Interpreter.Read read = unboxRead(operand, unbox);
        final Object folded = operand.constant() == null ? null : PrimitiveOps.unary(op, kind, operand.constant());
        return new Node(JdiTypes.primitive(kind),
            ctx -> ctx.mirror(PrimitiveOps.unary(op, kind, Interpreter.primitive(read.run(ctx), unbox))),
            folded, operand.potentiallyConstant(), false, null, null, false, operand.floatingOperand(), null);
    }

    /** Plans a binary operator: concatenation, reference equality, or primitive arithmetic, comparison and logic. */
    private Node binary(int op, Node left, Node right) {
        if (left.type().deferred() || right.type().deferred()) {
            throw compile("operator depends on an erased field type");
        }
        if (op == PLUS && (left.type().isString() || right.type().isString())) {
            return concatenate(left, right);
        }
        if (PrimitiveOps.equality(op) && left.type().kind() == null && right.type().kind() == null) {
            if (left.stringIdentity() || right.stringIdentity()) {
                throw compile("String constant identity");
            }
            checkReferenceEquality(left.type(), right.type());
            return new Node(JdiTypes.primitive(Kind.BOOLEAN), ctx -> ctx.mirror((op == EQUAL_EQUAL)
                == Interpreter.identity(ctx.keep(left.read().run(ctx)), right.read().run(ctx))));
        }
        final Kind a = primitiveKind(left);
        final Kind b = primitiveKind(right);
        final boolean logical = op == AND_AND || op == OR_OR;
        final boolean booleans = a == Kind.BOOLEAN && b == Kind.BOOLEAN;
        if ((a != b && (a == Kind.BOOLEAN || b == Kind.BOOLEAN))
            || (booleans && !(logical || PrimitiveOps.equality(op) || PrimitiveOps.bitwise(op)))
            || (!booleans && logical)
            || ((PrimitiveOps.shift(op) || (!booleans && PrimitiveOps.bitwise(op)))
                && !(a.integral() && b.integral()))) {
            throw compile("invalid binary operand types");
        }
        final Kind promoted = PrimitiveOps.shift(op) ? a.promote() : a.promote(b);
        final Kind result = PrimitiveOps.comparison(op) ? Kind.BOOLEAN : promoted;
        final Field unboxLeft = unbox(left);
        final Field unboxRight = unbox(right);
        final Interpreter.Read readLeft = unboxRead(left, unboxLeft);
        final Interpreter.Read readRight = unboxRead(right, unboxRight);
        return new Node(JdiTypes.primitive(result), ctx -> {
            final Object first = Interpreter.primitive(readLeft.run(ctx), unboxLeft);
            if ((op == AND_AND && !((Boolean) first)) || (op == OR_OR && (Boolean) first)) {
                return ctx.mirror(first);
            }
            final Object second = Interpreter.primitive(readRight.run(ctx), unboxRight);
            return ctx.mirror(PrimitiveOps.binary(op, promoted, first, second));
        }, foldBinary(op, promoted, left, right), left.potentiallyConstant() && right.potentiallyConstant(),
            false, null, null, false, left.floatingOperand() || right.floatingOperand(), null);
    }

    /** Folds a binary operation on constant operands, or returns null when it cannot be folded. */
    private static @Nullable Object foldBinary(int op, Kind kind, Node left, Node right) {
        if (left.constant() != null && right.constant() != null) {
            try {
                return PrimitiveOps.binary(op, kind, left.constant(), right.constant());
            } catch (ArithmeticException ignored) {
                // Division by zero is an execution error when this node is reached.
            }
        }
        return null;
    }

    /** Plans String concatenation with target formatting where Java requires it. */
    private Node concatenate(Node left, Node right) {
        final Method leftFormat = concatFormatter(left);
        final Method rightFormat = concatFormatter(right);
        final Interpreter.Read readLeft = formatInput(left, leftFormat);
        final Interpreter.Read readRight = formatInput(right, rightFormat);
        final Object a = left.constant();
        final Object b = right.constant();
        final boolean constant = left.potentiallyConstant() && right.potentiallyConstant();
        final StaticType type = left.type().isString() ? left.type() : right.type();
        final boolean lossless = losslessOperand(left) && losslessOperand(right);
        final boolean floating = left.floatingOperand() || right.floatingOperand();
        final Interpreter.Read read = ctx -> {
            final String first = concatOperand(readLeft, leftFormat, ctx);
            final String second = concatOperand(readRight, rightFormat, ctx);
            return ctx.mirror(first + second);
        };
        // The folded value identifies a Java constant. The read still formats floating-point
        // operands in the target, since the host may use a different JDK.
        final String folded = a == null || b == null ? null : checkedStringLiteral(a.toString() + b);
        return new Node(type, read, folded, constant, constant, null, () -> {
            // A nonconstant concatenation can still contain a folded floating-point fragment.
            if (floating) {
                throw compile("String concatenation contains a floating-point operand");
            }
            if (!lossless) {
                throw compile("String concatenation may lose code units before a call");
            }
            if (folded != null) {
                return intern(Objects.requireNonNull(type.reference()), read);
            }
            if (constant) {
                throw compile("String concatenation may depend on a constant field");
            }
            return read;
        }, lossless, floating, null);
    }

    /** Whether a concatenation operand can round-trip through the host without losing code units. */
    private static boolean losslessOperand(Node node) {
        final Kind kind = node.type().kind();
        if (node.constant() instanceof Character codeUnit) {
            return !Character.isSurrogate(codeUnit);
        }
        return node.losslessConcat() || node.constant() != null || node.type().isNull()
            || kind == Kind.BOOLEAN || (kind != null && kind.integral() && kind != Kind.CHAR);
    }

    /** Reads a concatenation operand as a String or prepares it for target formatting. */
    private static Interpreter.Read formatInput(Node node, @Nullable Method format) {
        if (format != null) {
            return targetRead(node);
        }
        return node.type().isString() ? memberRead(node, node.read()) : node.read();
    }

    /** Selects target formatting for object and floating-point operands. */
    private @Nullable Method concatFormatter(Node node) {
        final Kind kind = node.type().kind();
        if (kind == Kind.FLOAT || kind == Kind.DOUBLE
            || (kind == null && !node.type().isNull() && !node.type().isString())) {
            invokes = true;
            final String parameter = kind == null ? "Ljava/lang/Object;" : descriptor(kind);
            return helper(types.bootstrap("java.lang.String"), "valueOf", "(" + parameter + ")Ljava/lang/String;");
        }
        if (kind == Kind.VOID) {
            throw compile("void is not a concatenation operand");
        }
        return null;
    }

    /** Formats one operand before evaluating the next, including null returned by toString. */
    private static String concatOperand(Interpreter.Read read, @Nullable Method formatter, Interpreter.Context ctx) {
        final Value value = read.run(ctx);
        if (formatter == null) {
            return Interpreter.string(value);
        }
        final List<@Nullable Value> argument = Collections.singletonList(ctx.keep(value));
        final Value text;
        if (formatter.signature().startsWith("(Ljava/lang/Object;")) {
            // String.valueOf(Object) can invoke arbitrary toString code.
            text = ctx.call(formatter, null, argument, false, false);
        } else {
            text = ctx.repeatableCall(formatter, null, argument);
        }
        return Interpreter.string(text);
    }

    /** Plans a conditional expression, converting both branches to the common type. */
    private Node conditional(Node condition, Node yes, Node no) {
        if (primitiveKind(condition) != Kind.BOOLEAN) {
            throw compile("conditional test requires boolean");
        }
        final Field unboxCondition = unbox(condition);
        final StaticType type = conditionalType(yes, no);
        final Node takenYes = convertConditional(yes, type);
        final Node takenNo = convertConditional(no, type);
        // A reference conditional of any type other than String is not a JLS constant expression.
        final boolean constantType = type.kind() != null || type.isString();
        final Object folded = constantType && condition.constant() instanceof Boolean test
            && yes.constant() != null && no.constant() != null
            ? foldConditional(test, yes, no, type) : null;
        // A generic branch may need a different cast depending on how the conditional is used.
        if (takenYes.erasedResult() != null || takenNo.erasedResult() != null) {
            throw compile("conditional branches need a context-dependent generic cast");
        }
        final boolean constant = constantType && condition.potentiallyConstant()
            && yes.potentiallyConstant() && no.potentiallyConstant();
        final boolean floating = condition.floatingOperand() || yes.floatingOperand() || no.floatingOperand();
        final Interpreter.Read readCondition = unboxRead(condition, unboxCondition);
        final Interpreter.Read read = conditionalRead(readCondition, unboxCondition, takenYes.read(), takenNo.read());
        final TargetUse boundary;
        if (type.isString() && constant && floating) {
            boundary = () -> {
                throw compile("String constant has a floating-point operand");
            };
        } else {
            boundary = takenYes.targetUse() == null && takenNo.targetUse() == null
                ? null : () -> conditionalRead(readCondition, unboxCondition, targetRead(takenYes), targetRead(takenNo));
        }
        return new Node(type, read, folded, constant, yes.stringIdentity() || no.stringIdentity(), null,
            boundary, false, floating, null);
    }

    /** Reads the condition and only the selected branch in either value mode. */
    private static Interpreter.Read conditionalRead(Interpreter.Read condition, @Nullable Field unbox,
                                                     Interpreter.Read yes, Interpreter.Read no) {
        return ctx -> {
            final boolean test = (Boolean) Interpreter.primitive(condition.run(ctx), unbox);
            return (test ? yes : no).run(ctx);
        };
    }

    /** Folds a conditional whose test and branches are constants. */
    private static Object foldConditional(boolean test, Node yes, Node no, StaticType type) {
        final Object value = Objects.requireNonNull((test ? yes : no).constant());
        return type.kind() == null ? value : type.kind().convert(value);
    }

    /** Determines the type of a conditional from its branch types. */
    private StaticType conditionalType(Node yes, Node no) {
        final StaticType a = yes.type();
        final StaticType b = no.type();
        if (a.deferred() || b.deferred()) {
            throw compile("conditional depends on an erased field type");
        }
        if (a.equals(b)) {
            return a;
        }
        final Kind x = JdiTypes.unboxed(a);
        final Kind y = JdiTypes.unboxed(b);
        if (x == Kind.BOOLEAN && y == Kind.BOOLEAN) {
            return JdiTypes.primitive(Kind.BOOLEAN);
        }
        if (x != null && y != null && x.numeric() && y.numeric()) {
            if (x == y) {
                return JdiTypes.primitive(x);
            }
            if ((x == Kind.BYTE && y == Kind.SHORT) || (x == Kind.SHORT && y == Kind.BYTE)) {
                return JdiTypes.primitive(Kind.SHORT);
            }
            if (x == Kind.INT && a.kind() == Kind.INT && yes.constant() instanceof Integer n && y.represents(n)) {
                return JdiTypes.primitive(y);
            }
            if (y == Kind.INT && b.kind() == Kind.INT && no.constant() instanceof Integer n && x.represents(n)) {
                return JdiTypes.primitive(x);
            }
            if (possibleNarrowing(yes, y) || possibleNarrowing(no, x)) {
                throw compile("conditional narrowing depends on a constant field");
            }
            return JdiTypes.primitive(x.promote(y));
        }
        if (a.kind() != null || b.kind() != null) {
            final StaticType boxedA = a.kind() == null ? a : JdiTypes.of(types.box(a.kind()), null);
            final StaticType boxedB = b.kind() == null ? b : JdiTypes.of(types.box(b.kind()), null);
            if (boxedA.isNull()) {
                return boxedB;
            }
            if (boxedB.isNull()) {
                return boxedA;
            }
            return types.commonType(boxedA, boxedB);
        }
        if (a.isNull()) {
            return b;
        }
        if (b.isNull()) {
            return a;
        }
        return types.commonType(a, b);
    }

    /** Whether an int branch that may be a constant field could narrow to the other branch's type. */
    private static boolean possibleNarrowing(Node node, Kind other) {
        return node.type().kind() == Kind.INT && node.potentiallyConstant() && node.constant() == null
            && (other == Kind.BYTE || other == Kind.SHORT || other == Kind.CHAR);
    }

    /** Converts each selected branch to the conditional's type. */
    private Node convertConditional(Node node, StaticType type) {
        if (type.kind() == null) {
            return node.type().kind() == null ? node : box(node);
        }
        if (type.equals(node.type())) {
            return node;
        }
        final Kind to = type.kind();
        final Field unbox = unbox(node);
        final Interpreter.Read read = unboxRead(node, unbox);
        return new Node(type, ctx -> ctx.mirror(to.convert(Interpreter.primitive(read.run(ctx), unbox))));
    }

    /** Plans a primitive or reference cast, checking reference casts when the node runs. */
    private Node cast(Node node, StaticType target) {
        final @Nullable Kind kind = target.kind();
        if (kind != null) {
            if (kind == Kind.VOID) {
                throw compile("cannot cast to void");
            }
            if (node.type().isNull()) {
                throw compile("null cannot be cast directly to a primitive");
            }
            final Kind actual = JdiTypes.unboxed(node.type());
            if (actual != null) {
                if (actual == Kind.BOOLEAN ^ kind == Kind.BOOLEAN) {
                    throw compile("invalid primitive cast");
                }
                if (node.type().kind() == null && !widening(actual, kind)) {
                    throw compile("invalid unboxing cast");
                }
                final Field unbox = unbox(node);
                final Interpreter.Read read = unboxRead(node, unbox);
                return new Node(target, ctx -> ctx.mirror(kind.convert(Interpreter.primitive(read.run(ctx), unbox))),
                    node.constant() == null ? null : kind.convert(node.constant()), node.potentiallyConstant(),
                    false, null, null, false, node.floatingOperand() || isFloating(target), null);
            }
            final StaticType box = JdiTypes.of(types.box(kind), null);
            final Node checked = cast(node, box);
            final Field unbox = unbox(checked);
            return new Node(target, ctx -> ctx.mirror(Interpreter.primitive(checked.read().run(ctx), unbox)));
        }
        if (node.type().kind() != null) {
            final Kind primitive = node.type().kind();
            final ReferenceType boxed = types.box(primitive);
            for (ReferenceType bound : JdiTypes.bounds(target)) {
                if (!JdiTypes.assignable(boxed, bound)) {
                    throw compile("invalid boxing cast");
                }
            }
            final Node converted = box(node);
            return new Node(target, converted.read());
        }
        final List<ReferenceType> bounds = JdiTypes.bounds(target);
        for (ReferenceType bound : bounds) {
            checkReferenceCast(node.type(), bound);
            if (bound instanceof ArrayType) {
                requireNavigation("array cast needs runtime component metadata");
            }
        }
        final TargetUse boundary = node.targetUse() == null ? null : () -> checkedCast(targetRead(node), bounds);
        return new Node(target, checkedCast(node.read(), bounds), target.isString() ? node.constant() : null,
            target.isString() && node.potentiallyConstant(), node.stringIdentity(), null, boundary,
            node.losslessConcat(), node.floatingOperand(), null);
    }

    /** Records when a member read has a static type narrower than its JVM descriptor. */
    private static @Nullable ReferenceType erasureForCast(StaticType type, Type erased) {
        final ReferenceType narrowed = type.reference();
        if (type.imprecise() || narrowed == null || !(erased instanceof ReferenceType reference)
            || narrowed.equals(reference)) {
            return null;
        }
        return reference;
    }

    /** Inserts the generic cast at a use that needs the narrowed reference. */
    private static Interpreter.Read memberRead(Node node, Interpreter.Read read) {
        if (node.erasedResult() == null) {
            return read;
        }
        return checkedGenericCast(read, Objects.requireNonNull(node.type().reference()));
    }

    /** Checks a generic conversion with a bound whose subtype test requires no array component lookup. */
    private static Interpreter.Read checkedGenericCast(Interpreter.Read read, ReferenceType bound) {
        if (bound instanceof ArrayType) {
            throw compile("generic array cast needs runtime component metadata");
        }
        return checkedCast(read, List.of(bound));
    }

    /** Reads a value and enforces a Java reference cast before further evaluation. */
    private static Interpreter.Read checkedCast(Interpreter.Read read, List<ReferenceType> bounds) {
        return ctx -> {
            final Value value = read.run(ctx);
            if (value instanceof ObjectReference object) {
                final ReferenceType actual = object.referenceType();
                for (ReferenceType bound : bounds) {
                    if (!JdiTypes.assignable(actual, bound)) {
                        throw new ClassCastException(actual.name() + " cannot be cast to " + bound.name());
                    }
                }
            }
            return value;
        };
    }

    /** Whether a primitive widening conversion from one kind to the other exists. */
    private static boolean widening(Kind from, Kind to) {
        if (from == to) {
            return true;
        }
        return switch (from) {
            case BYTE -> to == Kind.SHORT || to == Kind.INT || to == Kind.LONG || to == Kind.FLOAT || to == Kind.DOUBLE;
            case SHORT, CHAR -> to == Kind.INT || to == Kind.LONG || to == Kind.FLOAT || to == Kind.DOUBLE;
            case INT -> to == Kind.LONG || to == Kind.FLOAT || to == Kind.DOUBLE;
            case LONG -> to == Kind.FLOAT || to == Kind.DOUBLE;
            case FLOAT -> to == Kind.DOUBLE;
            default -> false;
        };
    }

    /** Refuses reference comparison of types that can never be equal. */
    private static void checkReferenceEquality(StaticType a, StaticType b) {
        for (ReferenceType bound : JdiTypes.bounds(b)) {
            checkReferenceCast(a, bound);
        }
    }

    /** Refuses a cast from a type that is primitive, deferred, or provably incompatible with the target. */
    private static void checkReferenceCast(StaticType from, ReferenceType to) {
        if (from.deferred()) {
            throw compile("type check depends on an erased field type");
        }
        if (from.kind() != null) {
            throw compile("expected a reference operand");
        }
        for (ReferenceType ref : JdiTypes.bounds(from)) {
            checkReferenceCast(ref, to);
        }
    }

    /** Refuses a cast between two reference types that can never succeed. */
    private static void checkReferenceCast(ReferenceType ref, ReferenceType to) {
        if (JdiTypes.assignable(ref, to) || JdiTypes.assignable(to, ref)) {
            return;
        }
        if (ref instanceof InterfaceType && to instanceof ClassType tc && !tc.isFinal()
            || to instanceof InterfaceType && ref instanceof ClassType fc && !fc.isFinal()
            || ref instanceof InterfaceType && to instanceof InterfaceType) {
            return;
        }
        if (ref instanceof ArrayType a && to instanceof ArrayType b) {
            try {
                if (a.componentType() instanceof ReferenceType ac && b.componentType() instanceof ReferenceType bc) {
                    checkReferenceCast(ac, bc);
                    return;
                }
            } catch (ClassNotLoadedException e) {
                throw compile("array component type is not loaded");
            }
        }
        throw compile("incompatible reference types: " + ref.name() + " and " + to.name());
    }

    /** Plans a method call using the qualifier and argument types available to Java source lookup. */
    private Node methodCall(MessageSend call) throws ClassNotLoadedException {
        if (call.typeArguments != null) {
            throw compile("explicit method type arguments");
        }
        final String name = new String(call.selector);
        final boolean implicit = call.receiver.isImplicitThis();
        final boolean nonvirtual = call.receiver.isSuper();
        final Node qualifier = implicit ? receiver : callQualifier(call.receiver);
        final int arity = call.arguments == null ? 0 : call.arguments.length;
        final ReferenceType lookup;
        // Resolve the source receiver before selecting a method, using a captured class only for erased lookup.
        if (implicit) {
            lookup = declaring;
        } else if (qualifier == null) {
            lookup = types.resolve(call.receiver.toString());
        } else if (qualifier.type().imprecise()) {
            lookup = qualifier.runtimeType();
        } else {
            if (!qualifier.type().bounds().isEmpty()) {
                throw compile("method receiver has intersection type: " + name);
            }
            lookup = qualifier.type().reference();
            if (!nonvirtual && lookup != null) {
                final Node shortcut = intrinsic(call, qualifier, lookup, name, arity);
                if (shortcut != null) {
                    return shortcut;
                }
            }
        }
        if (lookup == null) {
            if (qualifier != null && qualifier.type().imprecise()) {
                throw compile("method lookup depends on an erased receiver: " + name);
            }
            if (implicit) {
                throw compile("unqualified method name is absent from the frame class: " + name);
            }
            throw compile("method receiver type is unavailable: " + call.receiver);
        }
        final StaticType staticReceiver = qualifier == null || qualifier.type().imprecise()
            ? JdiTypes.of(lookup, null) : qualifier.type();
        final Collection<Method> candidates = CallCandidates.named(types, lookup, staticReceiver, name);
        if (implicit && candidates.isEmpty()) {
            throw compile("unqualified method name is absent from the frame class: " + name);
        }
        if (qualifier != null && qualifier.type().imprecise() && candidates.size() > 1) {
            throw compile("multiple method signatures on an erased receiver: " + name);
        }
        final Method selected = CallCandidates.single(candidates, arity, name);
        if (selected.isStatic() && qualifier != null && qualifier.type().imprecise()) {
            throw compile("static method on an erased receiver: " + name);
        }
        if (!selected.isStatic() && qualifier == null && !implicit) {
            throw compile("instance method requires a receiver: " + name);
        }
        if (nonvirtual && selected.isAbstract()) {
            throw compile("super method is abstract: " + name);
        }
        return boundCall(selected, qualifier, arguments(call.arguments), nonvirtual, false);
    }

    /** Resolves a value receiver, leaving an unresolved name available as a type qualifier. */
    private @Nullable Node callQualifier(Expression syntax) throws ClassNotLoadedException {
        final List<String> path;
        if (syntax instanceof SingleNameReference single) {
            path = List.of(new String(single.token));
        } else if (syntax instanceof QualifiedNameReference qualified) {
            path = path(qualified);
        } else {
            return node(syntax);
        }
        return path.get(0).startsWith("$") ? name(path) : valuePath(path);
    }

    /** Plans construction of an initialized, concrete class with an ordinary source-level constructor. */
    private Node constructor(AllocationExpression allocation) throws ClassNotLoadedException {
        if (allocation.typeArguments != null) {
            throw compile("explicit constructor type arguments");
        }
        final ReferenceType type = reference(allocation.type);
        if (!(type instanceof ClassType cls) || cls.isAbstract() || cls.isEnum()) {
            throw compile("constructor requires a concrete class: " + type.name());
        }
        final String binary = cls.name();
        final int nested = binary.lastIndexOf('$');
        if (nested > binary.lastIndexOf('.') && (!cls.isStatic() || hasLocalClassSuffix(binary))) {
            throw compile("inner, local or anonymous class constructor: " + binary);
        }
        if (!cls.isInitialized()) {
            throw compile("constructor's class is not initialized: " + binary);
        }
        final List<Node> arguments = arguments(allocation.arguments);
        final Method selected = CallCandidates.single(CallCandidates.named(types, cls, null, "<init>"),
            arguments.size(), binary);
        return boundCall(selected, null, arguments, false, true);
    }

    /** Detects local and anonymous class components in a binary name. */
    private static boolean hasLocalClassSuffix(String binary) {
        for (int index = binary.indexOf('$'); index >= 0 && index + 1 < binary.length();
             index = binary.indexOf('$', index + 1)) {
            final char next = binary.charAt(index + 1);
            if (next >= '0' && next <= '9') {
                return true;
            }
        }
        return false;
    }

    /** Plans arguments in source order. */
    private List<Node> arguments(Expression @Nullable [] expressions) throws ClassNotLoadedException {
        if (expressions == null) {
            return List.of();
        }
        final List<Node> result = new ArrayList<>(expressions.length);
        for (Expression expression : expressions) {
            result.add(node(expression));
        }
        return result;
    }

    /** Binds conversions and return type while the plan is still free to choose the compiler. */
    private Node boundCall(Method method, @Nullable Node qualifier, List<Node> arguments, boolean nonvirtual,
                           boolean constructor) throws ClassNotLoadedException {
        final ReferenceType owner = method.declaringType();
        if (method.isStatic() && !owner.isInitialized()) {
            throw compile("static method's class is not initialized: " + owner.name());
        }
        final StaticType receiverType = qualifier == null ? null : qualifier.type();
        final String signature = method.genericSignature();
        final GenericSignatures.MethodTypes generic = signature == null ? null : GenericSignatures.methodTypes(signature);
        final List<Node> converted = convertArguments(method, receiverType, arguments, generic);
        if (!constructor && !method.isStatic() && qualifier == null) {
            // The unqualified call has an applicable method, but the frame is static.
            throw error("Cannot make a static reference to the non-static method " + method.name());
        }
        final Type erased = constructor ? owner : method.returnType();
        final String resultSignature = generic == null ? null : generic.result();
        final StaticType result = constructor ? JdiTypes.of(owner, null)
            : GenericSignatures.resultType(types, erased, resultSignature, receiverType, owner);
        invokes = true;
        final Interpreter.Read receiverRead;
        if (qualifier == null) {
            receiverRead = ctx -> null;
        } else if (method.isStatic()) {
            // Java still evaluates a value used to qualify a static call.
            receiverRead = memberRead(qualifier, qualifier.read());
        } else {
            receiverRead = memberRead(qualifier, targetRead(qualifier));
        }
        final List<Interpreter.Read> argumentReads = new ArrayList<>(converted.size());
        for (Node argument : converted) {
            argumentReads.add(targetRead(argument));
        }
        return new Node(result, ctx -> {
            // Retain the receiver through argument evaluations, which can also invoke target code.
            final Value object = ctx.keep(receiverRead.run(ctx));
            return ctx.call(method, object, readArguments(argumentReads, ctx), nonvirtual, constructor);
        }, null, false, false, null, null, false, isFloating(result), erasureForCast(result, erased));
    }

    /** Checks argument applicability using declared types, preserving conversion order during execution. */
    private List<Node> convertArguments(Method method, @Nullable StaticType receiverType, List<Node> arguments,
                                        GenericSignatures.@Nullable MethodTypes generic) throws ClassNotLoadedException {
        final List<Type> parameters = method.argumentTypes();
        final List<Node> converted = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            final String signature = generic == null ? null : generic.parameters().get(i);
            final Type erased = parameters.get(i);
            final StaticType formal = GenericSignatures.memberType(types, erased, signature,
                receiverType, method.declaringType());
            converted.add(convertArgument(arguments.get(i), formal, erased, method.name()));
        }
        return converted;
    }

    /** Plans an invocation conversion, declining conversions that depend on an unknown argument type. */
    private Node convertArgument(Node argument, StaticType formal, Type erased, String name) {
        final StaticType actual = argument.type();
        if (!formal.imprecise()) {
            final Kind from = JdiTypes.unboxed(actual);
            final Kind to = formal.kind();
            if (!actual.imprecise() && to != null && from != null && widening(from, to)) {
                if (actual.kind() == to) {
                    return argument;
                }
                final Field unbox = unbox(argument);
                final Interpreter.Read read = unboxRead(argument, unbox);
                return new Node(formal, ctx -> ctx.mirror(to.convert(Interpreter.primitive(read.run(ctx), unbox))));
            }
            final ReferenceType reference = formal.reference();
            if (reference != null && from != Kind.VOID) {
                if (actual.isNull()) {
                    return argument;
                }
                if (actual.kind() != null && JdiTypes.assignable(types.box(actual.kind()), reference)) {
                    return box(argument);
                }
                if (actual.reference() != null && referenceWidening(actual, formal, reference)) {
                    final ReferenceType erasedResult = argument.erasedResult();
                    // javac checks the erased parameter's type, which can be wider than the source argument.
                    if (erasedResult != null && erased instanceof ReferenceType parameter
                        && !JdiTypes.assignable(erasedResult, parameter)) {
                        return new Node(actual, checkedGenericCast(argument.read(), parameter));
                    }
                    return argument;
                }
            }
        }
        throw compile("inapplicable argument: " + name);
    }

    /** Checks a reference conversion including the parameterized target's arguments. */
    private static boolean referenceWidening(StaticType actual, StaticType formal, ReferenceType reference) {
        for (ReferenceType bound : JdiTypes.bounds(actual)) {
            if (JdiTypes.assignable(bound, reference) && GenericSignatures.acceptsArguments(actual, formal)) {
                return true;
            }
        }
        return false;
    }

    /** Reads each argument and retains its mirror across later argument evaluations and the call. */
    private static List<@Nullable Value> readArguments(List<Interpreter.Read> arguments, Interpreter.Context ctx) {
        final List<@Nullable Value> values = new ArrayList<>(arguments.size());
        for (Interpreter.Read argument : arguments) {
            values.add(ctx.keep(argument.run(ctx)));
        }
        return values;
    }

    /** Binds a value crossing into target code, including a String assembled in the debugger. */
    private static Interpreter.Read targetRead(Node node) {
        final TargetUse boundary = node.targetUse();
        return boundary == null ? node.read() : boundary.prepare();
    }

    /** Preserves the target identity of a String constant materialized by the debugger. */
    private static Interpreter.Read intern(ReferenceType string, Interpreter.Read read) {
        final Method method = helper(string, "intern", "()Ljava/lang/String;");
        return ctx -> ctx.repeatableCall(method, read.run(ctx), List.of());
    }

    /** Selects a bootstrap helper by its exact JVM signature before execution. */
    private static Method helper(ReferenceType owner, String name, String signature) {
        final List<Method> methods = owner.methodsByName(name, signature);
        if (methods.size() != 1 || !owner.isInitialized()) {
            throw compile("invocation helper is unavailable: " + owner.name() + "." + name);
        }
        final Method method = methods.get(0);
        try {
            method.argumentTypes();
            method.returnType();
        } catch (ClassNotLoadedException e) {
            throw compile("invocation helper type is not loaded: " + e.className());
        }
        return method;
    }

    /** Boxes using the target's valueOf so cached identities are preserved. */
    private Node box(Node node) {
        final Kind kind = Objects.requireNonNull(node.type().kind());
        final ReferenceType owner = types.box(kind);
        return box(node, owner, helper(owner, "valueOf", "(" + descriptor(kind) + ")" + owner.signature()));
    }

    /** Uses an already resolved boxing helper. */
    private Node box(Node node, ReferenceType owner, Method helper) {
        invokes = true;
        return new Node(JdiTypes.of(owner, null), ctx ->
            ctx.repeatableCall(helper, null, Collections.singletonList(node.read().run(ctx))));
    }

    /** JVM descriptor of a primitive accepted by a boxing or concatenation helper. */
    private static String descriptor(Kind kind) {
        return switch (kind) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case CHAR -> "C";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> throw compile("void is not a value");
        };
    }

    /** Tries the exact read-only shortcuts maintained by phase 1. */
    private @Nullable Node intrinsic(MessageSend call, Node receiver, ReferenceType reference, String name, int arity)
            throws ClassNotLoadedException {
        if (receiver.type().isString() && !targetStringIntrinsics) {
            if ((arity == 0 && (name.equals("length") || name.equals("isEmpty")))
                || (arity == 1 && (name.equals("charAt") || name.equals("equals")))) {
                usedStringIntrinsic = true;
            }
            if (arity == 0 && name.equals("length")) {
                return intrinsic(Kind.INT, receiver, null, (self, arg) -> intrinsicString(self).length());
            }
            if (arity == 0 && name.equals("isEmpty")) {
                return intrinsic(Kind.BOOLEAN, receiver, null, (self, arg) -> intrinsicString(self).isEmpty());
            }
            if (arity == 1 && name.equals("charAt")) {
                final Node arg = node(call.arguments[0]);
                if (primitiveKind(arg).promote() != Kind.INT) {
                    throw compile("String.charAt requires an int argument");
                }
                final Field unbox = unbox(arg);
                // Unbox the argument before checking whether the receiver is null, as invocation does.
                final Interpreter.Read read = unboxRead(arg, unbox);
                final Node position = new Node(JdiTypes.primitive(Kind.INT),
                    ctx -> ctx.mirror(Kind.INT.convert(Interpreter.primitive(read.run(ctx), unbox))));
                return intrinsic(Kind.CHAR, receiver, position,
                    (self, index) -> intrinsicString(self).charAt(((IntegerValue) Objects.requireNonNull(index)).value()));
            }
            if (arity == 1 && name.equals("equals")) {
                final Node argument = node(call.arguments[0]);
                if (argument.type().kind() != null) {
                    return null;
                }
                return intrinsic(Kind.BOOLEAN, receiver, argument,
                    (self, arg) -> {
                        final String receiverText = intrinsicString(self);
                        return arg instanceof StringReference text && receiverText.equals(intrinsicString(text));
                    });
            }
        }
        final Kind box = JdiTypes.unboxed(receiver.type());
        if (box != null) {
            final Field value = Objects.requireNonNull(unbox(receiver));
            final Kind conversion = SyntaxShape.boxConversion(name);
            if (arity == 0 && conversion != null && (box == conversion || (box != Kind.CHAR && box.numeric()
                    && conversion.numeric() && conversion != Kind.CHAR))) {
                return intrinsic(conversion, receiver, null,
                    (self, arg) -> conversion.convert(Interpreter.primitive(self, value)));
            }
            if (arity == 1 && name.equals("equals")) {
                final Node argument = node(call.arguments[0]);
                if (argument.type().kind() != null) {
                    return null;
                }
                return intrinsic(Kind.BOOLEAN, receiver, argument,
                    (self, arg) -> arg instanceof ObjectReference object && reference.equals(object.referenceType())
                        && Interpreter.primitive(self, value).equals(Interpreter.primitive(arg, value)));
            }
        }
        final boolean enumShortcut = (arity == 0 && (name.equals("name") || name.equals("ordinal")))
            || (arity == 1 && name.equals("equals"));
        final ClassType enumType = enumShortcut ? enumType(reference) : null;
        if (enumType != null) {
            // Application enums can overload the final methods inherited from java.lang.Enum.
            final Method selected = CallCandidates.single(
                CallCandidates.named(types, reference, receiver.type(), name), arity, name);
            if (!enumType.equals(selected.declaringType())) {
                return null;
            }
            if (arity == 1) {
                final Node argument = node(call.arguments[0]);
                if (argument.type().kind() != null) {
                    return null;
                }
                return intrinsic(Kind.BOOLEAN, receiver, argument, Interpreter::identity);
            }
            if (arity == 0 && (name.equals("name") || name.equals("ordinal"))) {
                final Field field = enumType.fieldByName(name);
                if (field == null) {
                    throw compile("Enum intrinsic field is unavailable");
                }
                final Interpreter.Read read = memberRead(receiver, receiver.read());
                return new Node(JdiTypes.of(field.type(), null), ctx ->
                    ((ObjectReference) Objects.requireNonNull(read.run(ctx))).getValue(field));
            }
        }
        return null;
    }

    /** Reads a String for an intrinsic and declines if JDWP may have substituted a code unit. */
    private static String intrinsicString(Value value) {
        final String text = ((StringReference) value).value();
        if (text.indexOf('�') >= 0) {
            throw compile("String transfer may have replaced a code unit");
        }
        return text;
    }

    /** Returns the {@code java.lang.Enum} class in the type's superclass chain, or null. */
    private static @Nullable ClassType enumType(ReferenceType type) {
        for (ClassType cls = type instanceof ClassType c ? c : null; cls != null; cls = cls.superclass()) {
            if (JdiTypes.isBootstrap(cls, "java.lang.Enum")) {
                return cls;
            }
        }
        return null;
    }

    /** Host-side implementation of an intrinsic call. */
    @FunctionalInterface
    private interface Intrinsic {
        /** Computes the result of the call from the receiver and the optional argument. */
        Object run(Value receiver, @Nullable Value arg);
    }

    /** Wraps an intrinsic as a node that evaluates the receiver and argument first. */
    private static Node intrinsic(Kind kind, Node receiver, @Nullable Node argument, Intrinsic intrinsic) {
        final Interpreter.Read read = memberRead(receiver, receiver.read());
        return new Node(JdiTypes.primitive(kind), ctx -> {
            final @Nullable Value self = ctx.keep(read.run(ctx));
            final @Nullable Value arg = argument == null ? null : argument.read().run(ctx);
            return ctx.mirror(intrinsic.run(Objects.requireNonNull(self), arg));
        });
    }
}
