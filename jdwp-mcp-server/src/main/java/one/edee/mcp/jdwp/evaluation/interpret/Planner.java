package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Location;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.impl.Constant;
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

    /** A plan the interpreter can execute by navigating values. */
    public record Navigate(VirtualMachine vm, Interpreter.Read read) implements Plan {
        /** Returns {@link KindOfPlan#NAVIGATE}. */
        @Override public KindOfPlan kind() { return KindOfPlan.NAVIGATE; }
    }

    /** A plan that needs a method invocation in the target. */
    public record Invoke(String reason) implements Plan {
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

    /**
     * A typed, executable expression node. {@code constant} is the folded value when known, {@code constantCandidate} marks a possible compile-time constant, {@code stringIdentity} marks a String constant whose identity matters, and {@code runtimeType} is the exact class of an erased local's value.
     */
    private record Node(StaticType type, Interpreter.Read read, @Nullable Object constant,
                        boolean constantCandidate, boolean stringIdentity, @Nullable ReferenceType runtimeType) {
        /** Creates a node that is not a constant. */
        Node(StaticType type, Interpreter.Read read) {
            this(type, read, null, false, false, null);
        }

        /** Whether Java could treat this node as a compile-time constant. */
        boolean potentiallyConstant() {
            return constantCandidate || constant != null;
        }
    }

    /** A visible local variable of the frame with its value at the time of planning. */
    private record Local(LocalVariable declaration, @Nullable Value value) {}

    /** Target VM. */
    private final VirtualMachine vm;
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
    /** Type lookup scoped to the declaring type. */
    private final JdiTypes types;

    /** Captures the frame's receiver and locals so planning and execution never touch the frame again. */
    private Planner(VirtualMachine vm, @Nullable StackFrame frame, @Nullable Location location,
                    Map<String, Value> bindings) {
        this.vm = vm;
        this.bindings = bindings;
        this.declaring = location == null ? null : location.declaringType();
        this.types = new JdiTypes(vm, declaring);
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
     * Plans a parsed snippet: syntax that cannot be navigated is routed first, the rest is navigated against the frame.
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

    /** Plans a navigable expression, turning every refusal or unloaded type into the plan that handles it instead. */
    static Plan navigation(VirtualMachine vm, @Nullable StackFrame frame, @Nullable Location location,
                           Map<String, Value> bindings, Expression expression) {
        try {
            final Planner planner = new Planner(vm, frame, location, bindings);
            final Node root = planner.node(expression);
            return new Navigate(vm, root.read());
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

    /** Creates a refusal that asks for a method invocation. */
    private static Refusal invoke(String reason) {
        return new Refusal(new Invoke(reason));
    }

    /** Creates a refusal that reports an error. */
    private static Refusal error(String message) {
        return new Refusal(new Error(message));
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
            return name(Arrays.stream(name.tokens).map(String::new).toList());
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
            return binary(operator(binary), node(binary.left), node(binary.right));
        }
        if (expression instanceof ConditionalExpression conditional) {
            return conditional(node(conditional.condition), node(conditional.valueIfTrue),
                node(conditional.valueIfFalse));
        }
        if (expression instanceof InstanceOfExpression test) {
            final Node value = node(test.expression);
            final ReferenceType target = reference(test.type);
            checkReferenceCast(value.type(), target);
            return new Node(JdiTypes.primitive(Kind.BOOLEAN), ctx -> ctx.mirror(
                value.read().run(ctx) instanceof ObjectReference object
                    && JdiTypes.assignable(object.referenceType(), target)));
        }
        if (expression instanceof CastExpression cast) {
            return cast(node(cast.expression), type(cast.type));
        }
        if (expression instanceof MessageSend call) {
            return intrinsic(call);
        }
        throw compile("unsupported expression: " + expression.getClass().getSimpleName());
    }

    /** Extracts the operator id stored in the node's bits. */
    private static int operator(ASTNode node) {
        return (node.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
    }

    /** Plans a literal as a constant node. */
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
                            text.append(component.constant.stringValue());
                        }
                    }
                    return stringConstant(text.toString());
                }
                return stringConstant(value.stringValue());
            }
            if (literal instanceof TrueLiteral || literal instanceof FalseLiteral) {
                return constant(Kind.BOOLEAN, value.booleanValue());
            }
            if (literal instanceof CharLiteral) {
                return constant(Kind.CHAR, value.charValue());
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

    /** Plans a String constant, which the JVM interns. */
    private Node stringConstant(String value) {
        return new Node(JdiTypes.of(types.bootstrap("java.lang.String"), null), ctx -> ctx.mirror(value),
            value, true, true, null);
    }

    /** Plans a primitive constant. */
    private static Node constant(Kind kind, Object value) {
        return new Node(JdiTypes.primitive(kind), ctx -> ctx.mirror(value), value, true, false, null);
    }

    /** Plans a simple or dotted name: a variable followed by fields, or a type followed by a static field. */
    private Node name(List<String> path) throws ClassNotLoadedException {
        final String first = path.get(0);
        final Node value = variable(first);
        if (value != null) {
            return selectPath(value, path, 1);
        }
        // A type prefix is followed by at least one field. Try the leftmost name first.
        for (int end = 1; end < path.size(); end++) {
            final ReferenceType type = types.resolve(String.join(".", path.subList(0, end)));
            if (type != null) {
                final String member = path.get(end);
                // A qualified type may contain further nested types. A field wins over a type.
                final Field field = JdiTypes.field(type, member, false);
                if (field != null) {
                    return selectPath(field(field, null), path, end + 1);
                }
            }
        }
        if (path.size() == 1 || first.startsWith("$")) {
            throw error("Unresolved variable '" + first + "'. Visible names: "
                + (names.isEmpty() ? "(none)" : String.join(", ", names)));
        }
        throw compile("type or variable is unavailable: " + String.join(".", path));
    }

    /** Applies the field selections of the path from {@code start} onwards. */
    private Node selectPath(Node value, List<String> path, int start) throws ClassNotLoadedException {
        Node result = value;
        for (int i = start; i < path.size(); i++) {
            result = select(result, path.get(i));
        }
        return result;
    }

    /** Resolves a simple name to a local, binding, field of this class or an enclosing class, or null. */
    private @Nullable Node variable(String name) throws ClassNotLoadedException {
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
            return new Node(JdiTypes.primitive(Kind.INT), ctx -> ctx.mirror(
                ((ArrayReference) Objects.requireNonNull(instance.read().run(ctx))).length()));
        }
        final ReferenceType lookup = type.imprecise() ? instance.runtimeType() : type.reference();
        if (lookup == null) {
            if (type.imprecise()) {
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
    private static Node field(Field field, @Nullable Node instance) throws ClassNotLoadedException {
        final ReferenceType declaring = field.declaringType();
        final boolean isStatic = field.isStatic();
        checkInitialized(field);
        if (!isStatic && instance == null) {
            throw compile("instance field requires a receiver: " + field.name());
        }
        final StaticType type = JdiTypes.of(field.type(), field.genericSignature());
        // JDI omits the ConstantValue attribute. A final primitive or String field can be
        // inlined by Java. Track the possibility where it affects identity or numeric narrowing.
        final boolean constantCandidate = field.isFinal() && (type.kind() != null || type.isString());
        return new Node(type, ctx -> {
            final @Nullable Value value = instance == null ? null : instance.read().run(ctx);
            return isStatic ? declaring.getValue(field)
                : ((ObjectReference) Objects.requireNonNull(value)).getValue(field);
        }, null, constantCandidate, type.isString() && constantCandidate, null);
    }

    /** Resolves a type written in the source, including primitives, arrays and intersection casts. */
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
        if (syntax.dimensions() == 0) {
            final Optional<Kind> primitive = primitiveNamed(name);
            if (primitive.isPresent()) {
                return JdiTypes.primitive(primitive.get());
            }
        }
        final ReferenceType ref;
        if (syntax.dimensions() > 0) {
            final ReferenceType component = primitiveNamed(name).isPresent() ? null : types.resolve(name);
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
        return new Node(JdiTypes.of(type.componentType(), null), ctx -> {
            final @Nullable Value receiver = array.read().run(ctx);
            final int i = (Integer) Kind.INT.convert(Interpreter.primitive(index.read().run(ctx), unbox));
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

    /** Plans a unary operator and folds it for a constant operand. */
    private static Node unary(int op, Node operand) {
        final Kind kind = primitiveKind(operand).promote();
        if ((op == NOT && kind != Kind.BOOLEAN) || (op == TWIDDLE && !kind.integral())
                || ((op == PLUS || op == MINUS) && !kind.numeric())) {
            throw compile("invalid unary operand type");
        }
        final Field unbox = unbox(operand);
        final Object folded = operand.constant() == null ? null : PrimitiveOps.unary(op, kind, operand.constant());
        return new Node(JdiTypes.primitive(kind),
            ctx -> ctx.mirror(PrimitiveOps.unary(op, kind, Interpreter.primitive(operand.read().run(ctx), unbox))),
            folded, operand.potentiallyConstant(), false, null);
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
                == Interpreter.identity(left.read().run(ctx), right.read().run(ctx))));
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
        return new Node(JdiTypes.primitive(result), ctx -> {
            final Object first = Interpreter.primitive(left.read().run(ctx), unboxLeft);
            if ((op == AND_AND && !((Boolean) first)) || (op == OR_OR && (Boolean) first)) {
                return ctx.mirror(first);
            }
            final Object second = Interpreter.primitive(right.read().run(ctx), unboxRight);
            return ctx.mirror(PrimitiveOps.binary(op, promoted, first, second));
        }, foldBinary(op, promoted, left, right), left.potentiallyConstant() && right.potentiallyConstant(),
            false, null);
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

    /** Plans String concatenation, refusing operands whose text the interpreter cannot reproduce. */
    private Node concatenate(Node left, Node right) {
        checkConcat(left);
        checkConcat(right);
        final Object a = left.constant();
        final Object b = right.constant();
        final boolean constant = left.potentiallyConstant() && right.potentiallyConstant();
        final StaticType type = left.type().isString() ? left.type() : right.type();
        return new Node(type, ctx -> {
            final String first = Interpreter.string(left.read().run(ctx));
            final String second = Interpreter.string(right.read().run(ctx));
            return ctx.mirror(first + second);
        }, a != null && b != null ? a.toString() + b : null,
            constant, constant, null);
    }

    /** Refuses concatenation operands that need floating-point or object formatting. */
    private static void checkConcat(Node node) {
        final @Nullable Kind kind = node.type().kind();
        if (kind == Kind.FLOAT || kind == Kind.DOUBLE) {
            throw invoke("floating-point String concatenation");
        }
        if (kind == null && !node.type().isNull() && !node.type().isString()) {
            throw invoke("object String concatenation");
        }
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
        final Object folded = condition.constant() instanceof Boolean test && yes.constant() != null && no.constant() != null
            ? foldConditional(test, yes, no, type) : null;
        return new Node(type, ctx -> {
            final boolean test = (Boolean) Interpreter.primitive(condition.read().run(ctx), unboxCondition);
            return (test ? takenYes : takenNo).read().run(ctx);
        }, folded, condition.potentiallyConstant() && yes.potentiallyConstant() && no.potentiallyConstant(),
            yes.stringIdentity() || no.stringIdentity(), null);
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
            throw invoke("boxing in conditional expression");
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

    /** Converts a branch to the conditional's primitive type when it differs. */
    private static Node convertConditional(Node node, StaticType type) {
        if (type.kind() == null || type.equals(node.type())) {
            return node;
        }
        final Kind to = type.kind();
        final Field unbox = unbox(node);
        return new Node(type, ctx -> ctx.mirror(to.convert(Interpreter.primitive(node.read().run(ctx), unbox))));
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
                return new Node(target,
                    ctx -> ctx.mirror(kind.convert(Interpreter.primitive(node.read().run(ctx), unbox))),
                    node.constant() == null ? null : kind.convert(node.constant()), node.potentiallyConstant(),
                    false, null);
            }
            final StaticType box = JdiTypes.of(types.box(kind), null);
            final Node checked = cast(node, box);
            final Field unbox = unbox(checked);
            return new Node(target, ctx -> ctx.mirror(Interpreter.primitive(checked.read().run(ctx), unbox)));
        }
        if (node.type().kind() != null) {
            throw invoke("boxing in cast");
        }
        final List<ReferenceType> bounds = JdiTypes.bounds(target);
        for (ReferenceType bound : bounds) {
            checkReferenceCast(node.type(), bound);
        }
        return new Node(target, ctx -> {
            final @Nullable Value value = node.read().run(ctx);
            if (value instanceof ObjectReference object) {
                final ReferenceType actual = object.referenceType();
                for (ReferenceType bound : bounds) {
                    if (!JdiTypes.assignable(actual, bound)) {
                        throw new ClassCastException(actual.name() + " cannot be cast to " + bound.name());
                    }
                }
            }
            return value;
        }, target.isString() ? node.constant() : null, target.isString() && node.potentiallyConstant(),
            node.stringIdentity(), null);
    }

    /** Whether a primitive widening conversion from one kind to the other exists. */
    private static boolean widening(Kind from, Kind to) {
        if (from == to) {
            return true;
        }
        return switch (from) {
            case BYTE -> to == Kind.SHORT || to.ordinal() >= Kind.INT.ordinal();
            case SHORT, CHAR -> to.ordinal() >= Kind.INT.ordinal();
            case INT, LONG, FLOAT -> to.ordinal() > from.ordinal();
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

    /**
     * Plans a call that is evaluated without invoking target code, for example {@code String.length()}, or refuses it.
     */
    private Node intrinsic(MessageSend call) throws ClassNotLoadedException {
        final String name = new String(call.selector);
        if (call.receiver instanceof SuperReference || call.receiver instanceof QualifiedSuperReference
            || call.typeArguments != null) {
            throw invoke("method call");
        }
        final Node receiver;
        try {
            receiver = node(call.receiver);
        } catch (Refusal refusal) {
            // A name receiver may be a type qualifier. Without a binding, the compiler can
            // still resolve a static method (including names shared with the intrinsics).
            if (call.receiver instanceof NameReference && refusal.plan instanceof Error
                && !call.receiver.toString().startsWith("$")) {
                throw invoke("method call");
            }
            throw refusal;
        }
        final ReferenceType reference = receiver.type().reference();
        final int arity = call.arguments == null ? 0 : call.arguments.length;
        if (reference == null || receiver.type().imprecise()) {
            throw invoke("method call");
        }
        if (receiver.type().isString()) {
            if (arity == 0 && name.equals("length")) {
                return intrinsic(Kind.INT, receiver, null, (self, arg) -> ((StringReference) self).value().length());
            }
            if (arity == 0 && name.equals("isEmpty")) {
                return intrinsic(Kind.BOOLEAN, receiver, null, (self, arg) -> ((StringReference) self).value().isEmpty());
            }
            if (arity == 1 && name.equals("charAt")) {
                final Node arg = node(call.arguments[0]);
                if (primitiveKind(arg).promote() != Kind.INT) {
                    throw compile("String.charAt requires an int argument");
                }
                final Field unbox = unbox(arg);
                // Unbox the argument before checking whether the receiver is null, as invocation does.
                final Node position = new Node(JdiTypes.primitive(Kind.INT),
                    ctx -> ctx.mirror(Kind.INT.convert(Interpreter.primitive(arg.read().run(ctx), unbox))));
                return intrinsic(Kind.CHAR, receiver, position,
                    (self, index) -> ((StringReference) self).value().charAt(((IntegerValue) Objects.requireNonNull(index)).value()));
            }
            if (arity == 1 && name.equals("equals")) {
                return intrinsic(Kind.BOOLEAN, receiver, objectArgument(call.arguments[0]),
                    (self, arg) -> arg instanceof StringReference text
                        && ((StringReference) self).value().equals(text.value()));
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
                return intrinsic(Kind.BOOLEAN, receiver, objectArgument(call.arguments[0]),
                    (self, arg) -> arg instanceof ObjectReference object && reference.equals(object.referenceType())
                        && Interpreter.primitive(self, value).equals(Interpreter.primitive(arg, value)));
            }
        }
        final ClassType enumType = enumType(reference);
        if (enumType != null) {
            if (arity == 1 && name.equals("equals")) {
                return intrinsic(Kind.BOOLEAN, receiver, objectArgument(call.arguments[0]), Interpreter::identity);
            }
            if (arity == 0 && (name.equals("name") || name.equals("ordinal"))) {
                final Field field = enumType.fieldByName(name);
                if (field == null) {
                    throw compile("Enum intrinsic field is unavailable");
                }
                return new Node(JdiTypes.of(field.type(), null), ctx ->
                    ((ObjectReference) Objects.requireNonNull(receiver.read().run(ctx))).getValue(field));
            }
        }
        throw invoke("method call");
    }

    /** Plans an intrinsic's argument, which must not need boxing. */
    private Node objectArgument(Expression syntax) throws ClassNotLoadedException {
        final Node arg = node(syntax);
        if (arg.type().kind() != null) {
            throw invoke("boxing in intrinsic argument");
        }
        return arg;
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
        return new Node(JdiTypes.primitive(kind), ctx -> {
            final @Nullable Value self = receiver.read().run(ctx);
            final @Nullable Value arg = argument == null ? null : argument.read().run(ctx);
            return ctx.mirror(intrinsic.run(Objects.requireNonNull(self), arg));
        });
    }
}
