package one.edee.mcp.jdwp.evaluation;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompoundAssignment;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.ForStatement;
import org.eclipse.jdt.internal.compiler.ast.Invocation;
import org.eclipse.jdt.internal.compiler.ast.LambdaExpression;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.PostfixExpression;
import org.eclipse.jdt.internal.compiler.ast.PrefixExpression;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.batch.Main;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.InvocationSite;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemFieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemMethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemReasons;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.eclipse.jdt.internal.compiler.lookup.WildcardBinding;
import org.eclipse.jdt.internal.compiler.util.Util;
import org.jspecify.annotations.Nullable;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** Replaces one round of inaccessible member expressions identified by ECJ. */
final class MemberAccessRewrite extends ASTVisitor {
    /** Separates the generated wrapper name from the closed module name in the target marker. */
    static final String MODULE_FAILURE = ".memberAccessModuleClosed:";

    /** Java source for this binding pass. */
    private final String source;
    /** Errors that identify candidate member sites by range. */
    private final List<IProblem> accessProblems;
    /** Source replacements collected during traversal. */
    private final List<Edit> edits = new ArrayList<>();
    /** Statement expressions whose values the surrounding Java code discards. */
    private final Set<Expression> discarded = new HashSet<>();
    /** Original receiver wording keyed by generated calls that later passes may use as receivers. */
    private final Map<String, String> receiverDescriptions;
    /** Generated signatures for discarded results and reflected calls that declare checked exceptions. */
    private final StringBuilder bridges = new StringBuilder();
    /** Next candidate suffix for a generated call bridge. */
    private int nextBridge;
    /** A rewrite would change a target or require a different static type. */
    private boolean refused;

    /** Replaces a half-open source range with one complete Java expression. */
    private record Edit(int start, int end, String replacement) {}

    /** Collects edits against the original source for this round. */
    private MemberAccessRewrite(String source, List<IProblem> accessProblems, Map<String, String> receiverDescriptions) {
        this.source = source;
        this.accessProblems = accessProblems;
        this.receiverDescriptions = receiverDescriptions;
    }

    /** Identifies ECJ's member-visibility diagnostics, including invisible receiver types. */
    static boolean isAccessProblem(int id) {
        return switch (id) {
            case IProblem.NotVisibleField, IProblem.NotVisibleMethod, IProblem.NotVisibleConstructor,
                 IProblem.NotVisibleType, IProblem.InheritedFieldHidesEnclosingName,
                 IProblem.InheritedMethodHidesEnclosingName -> true;
            default -> false;
        };
    }

    /** Allows resolution or type errors that an inaccessible expression can cause in its enclosing expression. */
    static boolean canFollowAccessProblem(int id) {
        return switch (id) {
            case IProblem.UndefinedField, IProblem.UndefinedMethod, IProblem.UndefinedConstructor,
                 IProblem.ParameterMismatch, IProblem.TypeMismatch, IProblem.ReturnTypeMismatch,
                 IProblem.InvalidOperator, IProblem.InvalidTypeForCollection,
                 IProblem.UnhandledException, IProblem.UnreachableCatch -> true;
            default -> false;
        };
    }

    /**
     * Resolves the same source with ECJ's batch name environment, which exposes binding objects
     * and the original problem arguments. JSR-199 hides both behind diagnostic strings.
     * Keeps receiver descriptions across passes so diagnostics can still name an edited receiver.
     * Returns null when there is no supported safe edit.
     */
    static @Nullable String rewrite(String className, Path file, String source,
                                    InMemoryJavaCompiler.Configuration config, boolean appendHelpers,
                                    Map<String, String> receiverDescriptions) {
        final List<String> options = InMemoryJavaCompiler.buildCompilerOptions(config);
        options.addAll(List.of("-proc:none", file.toString()));
        final PrintWriter output = new PrintWriter(Writer.nullWriter());
        final Main batch = new Main(output, output, false, null, null);
        batch.configure(options.toArray(String[]::new));
        final FileSystem environment = batch.getLibraryAccess();
        try {
            final Compiler compiler = new Compiler(environment, DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                new CompilerOptions(batch.options), result -> {}, batch.getProblemFactory());
            final CompilationUnitDeclaration unit = compiler.resolve(batch.getCompilationUnits()[0], true, false, false);
            final IProblem @Nullable [] problems = unit.compilationResult.getErrors();
            if (problems == null || unit.scope == null) {
                return null;
            }
            final List<IProblem> access = new ArrayList<>();
            for (IProblem problem : problems) {
                if (isAccessProblem(problem.getID())) {
                    access.add(problem);
                } else if (!canFollowAccessProblem(problem.getID())) {
                    return null;
                }
            }
            final MemberAccessRewrite rewrite = new MemberAccessRewrite(source, access, receiverDescriptions);
            unit.traverse(rewrite, unit.scope, false);
            return rewrite.apply(className, unit.types[0], appendHelpers);
        } finally {
            environment.cleanup();
        }
    }

    /** Checks the entire assignment target before its receiver and indices are visited. */
    @Override
    public boolean visit(Assignment node, BlockScope scope) {
        return checkTarget(node.lhs);
    }

    /** Checks the target of a compound assignment. */
    @Override
    public boolean visit(CompoundAssignment node, BlockScope scope) {
        return checkTarget(node.lhs);
    }

    /** Checks the target of a prefix increment or decrement. */
    @Override
    public boolean visit(PrefixExpression node, BlockScope scope) {
        return checkTarget(node.lhs);
    }

    /** Checks the target of a postfix increment or decrement. */
    @Override
    public boolean visit(PostfixExpression node, BlockScope scope) {
        return checkTarget(node.lhs);
    }

    /** Refuses the whole retry if an access diagnostic intersects any part of the target. */
    private boolean checkTarget(Expression target) {
        for (final IProblem problem : accessProblems) {
            if (problem.getSourceStart() <= target.sourceEnd && target.sourceStart <= problem.getSourceEnd()) {
                refused = true;
                return false;
            }
        }
        return true;
    }

    /** A non-void call in a void-compatible lambda still has to be a statement expression. */
    @Override
    public boolean visit(LambdaExpression node, BlockScope scope) {
        if (node.body() instanceof Expression body && node.descriptor != null
            && node.descriptor.returnType == TypeBinding.VOID) {
            discarded.add(body);
        }
        return true;
    }

    /** For-loop initializers and updates can discard the value of a call without a semicolon. */
    @Override
    public boolean visit(ForStatement node, BlockScope scope) {
        discardValues(node.initializations);
        discardValues(node.increments);
        return true;
    }

    /** Records the statements for which a call's helper result should stay uncast. */
    private void discardValues(Statement @Nullable [] statements) {
        if (statements != null) {
            for (Statement statement : statements) {
                if (statement instanceof Expression expression) {
                    discarded.add(expression);
                }
            }
        }
    }

    /** Returns whether Java discards the value of the invocation as a statement. */
    private boolean discardsValue(Expression node) {
        return node.statementEnd > node.sourceEnd || discarded.contains(node);
    }

    /** Rewrites a failed field selection whose receiver is an arbitrary Java expression. */
    @Override
    public void endVisit(FieldReference node, BlockScope scope) {
        if (refused || node.receiver.isSuper()) {
            return;
        }
        final @Nullable FieldBinding field = field(node.binding, node.actualReceiverType, node.token);
        if (field == null) {
            return;
        }
        final boolean invisibleReceiver = hasProblem(IProblem.NotVisibleType, node.receiver.sourceStart,
            node.receiver.sourceEnd, null, 0);
        final long position = node.nameSourcePosition;
        if (!invisibleReceiver
            && !hasProblem(IProblem.NotVisibleField, (int) (position >>> 32), (int) position, field.name, 0)
            && !hasProblem(IProblem.InheritedFieldHidesEnclosingName, node.sourceStart, node.sourceEnd, null, 0)) {
            return;
        }
        // The next pass can bind this read against the same type after editing its receiver.
        if (hasEditIn(node)) {
            return;
        }
        rewriteField(node.sourceStart, node.sourceEnd, field, node.receiver.isTypeReference(),
            text(node.receiver), describeReceiver(node.receiver), node.receiver.resolvedType, scope);
    }

    /** ECJ represents dotted names as one node. Rewrite the first inaccessible selection in that chain. */
    @Override
    public void endVisit(QualifiedNameReference node, BlockScope scope) {
        final int firstField = node.indexOfFirstFieldBinding - 1;
        if (refused || firstField < 0) {
            return;
        }
        // ECJ includes enclosing parentheses in sourceStart, so token positions bound this edit.
        final int start = (int) (node.sourcePositions[0] >>> 32);
        @Nullable TypeBinding receiverType = node.actualReceiverType;
        for (int i = firstField; i < node.tokens.length; i++) {
            if (i == firstField && node.binding instanceof LocalVariableBinding local) {
                receiverType = local.type;
                continue;
            }
            final @Nullable FieldBinding field = field(fieldAt(node, i), receiverType, node.tokens[i]);
            if (field == null) {
                return;
            }
            final long position = node.sourcePositions[i];
            final int end = (int) position;
            final int receiverEnd = i == 0 ? -1 : (int) node.sourcePositions[i - 1];
            if (hasProblem(IProblem.NotVisibleField, (int) (position >>> 32), end, field.name, 0)
                || hasProblem(IProblem.InheritedFieldHidesEnclosingName, start, end, null, 0)
                || hasProblem(IProblem.NotVisibleType, start, receiverEnd,
                    receiverType == null ? null : receiverType.readableName(), 0)) {
                if (receiverEnd >= start) {
                    final boolean typeQualifier = i == firstField && node.binding instanceof FieldBinding;
                    rewriteField(start, end, field, typeQualifier, source.substring(start, receiverEnd + 1),
                        describeQualifiedName(node, i - 1), receiverType, scope);
                }
                return;
            }
            receiverType = field.type;
        }
    }

    /** Retrieves the field binding for one token in an ECJ qualified name. */
    private static @Nullable FieldBinding fieldAt(QualifiedNameReference node, int token) {
        final int firstField = node.indexOfFirstFieldBinding - 1;
        if (token == firstField && node.binding instanceof FieldBinding field) {
            return field;
        }
        final int index = token - firstField - 1;
        if (node.otherBindings != null && index >= 0 && index < node.otherBindings.length) {
            return node.otherBindings[index];
        }
        return null;
    }

    /** Rewrites an inaccessible method using ECJ's selected declaring class and erased signature. */
    @Override
    public void endVisit(MessageSend node, BlockScope scope) {
        final int start = (int) (node.nameSourcePosition >>> 32);
        final int end = (int) node.nameSourcePosition;
        final boolean inaccessible = hasProblem(IProblem.NotVisibleMethod, start, end, node.selector, 1)
            || hasProblem(IProblem.InheritedMethodHidesEnclosingName, start, end, node.selector, 1);
        final boolean invisibleReceiver = hasProblem(IProblem.NotVisibleType, node.receiver.sourceStart,
            node.receiver.sourceEnd, null, 0);
        if (refused || (!inaccessible && !invisibleReceiver) || node.receiver.isSuper() || node.receiver.isImplicitThis()) {
            return;
        }
        if (!valid(node.argumentTypes)) {
            return;
        }
        final boolean typeReceiver = node.receiver.isTypeReference();
        @Nullable MethodBinding binding = method(node.binding);
        if (invisibleReceiver && node.actualReceiverType instanceof ReferenceBinding type) {
            final @Nullable MethodBinding resolved = scope.findMethod(type, node.selector, node.argumentTypes, node, false);
            if (resolved != null && resolved.isValidBinding()) {
                binding = resolved;
            } else {
                binding = selectOverload(method(resolved), node, node.argumentTypes, scope, typeReceiver);
            }
        } else {
            binding = selectOverload(binding, node, node.argumentTypes, scope, typeReceiver);
        }
        if (binding == null || (!binding.isStatic() && typeReceiver)) {
            return;
        }
        if (!canName(binding.returnType, scope)) {
            refused = true;
            return;
        }
        final @Nullable String arguments = arguments(binding, node, node.arguments, node.argumentTypes, scope);
        if (arguments == null) {
            return;
        }
        final boolean returnsVoid = binding.returnType == TypeBinding.VOID;
        final boolean discardedResult = discardsValue(node) && !returnsVoid;
        final @Nullable String helper = bridge(binding, returnsVoid ? "__mcpInvokeVoid" : "__mcpInvoke",
            false, discardedResult ? binding.returnType : null, scope);
        if (helper == null) {
            return;
        }
        final String member = displayQuoted(describeMethod(node.receiver.resolvedType, binding));
        final String nullMessage = binding.isStatic() ? "null" : quote(
            "Cannot invoke " + member + " because " + describeReceiver(node.receiver) + " is null");
        final String qualifier = receiver(text(node.receiver), typeReceiver, binding.isStatic());
        final String invoke = helper + "(" + qualifier + ", " + quote(binaryName(binding.declaringClass))
            + ", " + quote(new String(node.selector)) + ", " + arguments + ", " + nullMessage + ")";
        if (returnsVoid || discardedResult) {
            add(node.sourceStart, node.sourceEnd, invoke);
        } else {
            receiverDescriptions.put(invoke, "the return value of " + member);
            add(node.sourceStart, node.sourceEnd, cast(binding.returnType, invoke));
        }
    }

    /** Rewrites ordinary class creation when the constructor alone is inaccessible. */
    @Override
    public void endVisit(AllocationExpression node, BlockScope scope) {
        if (refused || !hasProblem(IProblem.NotVisibleConstructor, node.sourceStart, node.sourceEnd, null, 0)
            || !valid(node.argumentTypes)) {
            return;
        }
        final @Nullable MethodBinding binding = selectOverload(method(node.binding), node, node.argumentTypes, scope, false);
        if (binding == null || binding.declaringClass.isLocalType()
            || (binding.declaringClass.isMemberType() && !binding.declaringClass.isStatic())) {
            return;
        }
        final @Nullable String arguments = arguments(binding, node, node.arguments, node.argumentTypes, scope);
        if (arguments == null) {
            return;
        }
        if (!canName(node.resolvedType, scope)) {
            refused = true;
            return;
        }
        final boolean discardedResult = discardsValue(node);
        final @Nullable String helper = bridge(binding, "__mcpConstruct", true,
            discardedResult ? node.resolvedType : null, scope);
        if (helper != null) {
            // Java initializes the class at new, before evaluating constructor arguments.
            final String owner = "__mcpInitialize(" + quote(binaryName(binding.declaringClass)) + ")";
            final String invoke = helper + "(" + owner + ", " + arguments + ")";
            add(node.sourceStart, node.sourceEnd, discardedResult ? invoke : cast(node.resolvedType, invoke));
        }
    }

    /**
     * ECJ's inaccessible candidate can be the first applicable overload rather than the most specific.
     * Resolve the selected owner's overloads through ECJ's compatibility and specificity checks.
     */
    private static @Nullable MethodBinding selectOverload(@Nullable MethodBinding candidate, Invocation site,
                                                           TypeBinding[] types, BlockScope scope, boolean staticOnly) {
        if (candidate == null) {
            return null;
        }
        final MethodBinding[] overloads = candidate.declaringClass.getMethods(candidate.selector);
        if (overloads.length <= 1) {
            return candidate;
        }
        return new OverloadScope(scope).select(candidate.declaringClass, overloads, types, site, staticOnly);
    }

    /** Gives the retry access to the same ECJ overload checks used by ordinary method lookup. */
    private static final class OverloadScope extends BlockScope {
        /** Shares the current scope's compiler environment without modifying the wrapper's scope tree. */
        OverloadScope(BlockScope scope) {
            super(scope, false);
        }

        /** Resolves a unique compatible overload, keeping ambiguity a compile error. */
        @Nullable MethodBinding select(ReferenceBinding owner, MethodBinding[] overloads, TypeBinding[] types,
                                       InvocationSite site, boolean staticOnly) {
            final MethodBinding[] applicable = new MethodBinding[overloads.length];
            int count = 0;
            for (MethodBinding overload : overloads) {
                if (overload.isBridge() || (staticOnly && !overload.isStatic())) {
                    continue;
                }
                final @Nullable MethodBinding method = computeCompatibleMethod(overload, types, site);
                if (method != null && method.isValidBinding()) {
                    applicable[count++] = method;
                }
            }
            if (count == 0) {
                return null;
            }
            final MethodBinding selected = count == 1 ? applicable[0]
                : mostSpecificMethodBinding(applicable, count, types, site, owner);
            return selected.isValidBinding() ? selected : null;
        }
    }

    /** Keeps checked-exception reachability and types discarded results without making them cast statements. */
    private @Nullable String bridge(MethodBinding method, String helper, boolean constructor,
                                    @Nullable TypeBinding discardedType, BlockScope scope) {
        final StringJoiner exceptions = new StringJoiner(", ");
        for (ReferenceBinding exception : method.thrownExceptions) {
            if (!exception.isUncheckedException(false)) {
                if (!canName(exception, scope)) {
                    return null;
                }
                exceptions.add(new String(exception.readableName()));
            }
        }
        if (exceptions.length() == 0 && discardedType == null) {
            return helper;
        }
        String name;
        do {
            name = "__mcpBridge" + nextBridge++;
        } while (source.contains(" " + name + "("));
        final boolean returnsVoid = method.returnType == TypeBinding.VOID && !constructor;
        final String returnType;
        if (discardedType != null) {
            returnType = new String(discardedType.readableName());
        } else {
            returnType = returnsVoid ? "void" : "java.lang.Object";
        }
        bridges.append("\nprivate static ").append(returnType).append(' ').append(name).append('(');
        if (!constructor) {
            bridges.append("java.lang.Object receiver, ");
        }
        bridges.append("java.lang.String owner, ");
        if (!constructor) {
            bridges.append("java.lang.String name, ");
        }
        bridges.append("java.lang.String[] signature, java.lang.Object[] args");
        if (!constructor) {
            bridges.append(", java.lang.String nullMessage");
        }
        bridges.append(')');
        if (exceptions.length() != 0) {
            bridges.append(" throws ").append(exceptions);
        }
        bridges.append(" {\n    ");
        if (!returnsVoid) {
            bridges.append("return ");
        }
        final StringBuilder call = new StringBuilder(helper).append('(');
        if (constructor) {
            call.append("owner, ");
        } else {
            call.append("receiver, owner, name, ");
        }
        call.append("signature, args");
        if (!constructor) {
            call.append(", nullMessage");
        }
        call.append(')');
        final String invocation = call.toString();
        bridges.append(discardedType == null ? invocation : cast(discardedType, invocation)).append(";\n}\n");
        return name;
    }

    /** Matches the problem's inclusive source range and, for named members, the problem argument. */
    private boolean hasProblem(int id, int start, int end, char @Nullable [] name, int argumentIndex) {
        for (IProblem problem : accessProblems) {
            if (problem.getID() == id && problem.getSourceStart() == start && problem.getSourceEnd() == end) {
                final String[] arguments = problem.getArguments();
                if (name == null || (arguments.length > argumentIndex
                    && arguments[argumentIndex].equals(new String(name)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Gets the compiler's field binding, including a declared member on a non-public receiver. */
    private static @Nullable FieldBinding field(@Nullable FieldBinding binding, @Nullable TypeBinding receiver, char[] name) {
        if (binding instanceof ProblemFieldBinding problem) {
            if (problem.problemId() == ProblemReasons.ReceiverTypeNotVisible && receiver instanceof ReferenceBinding type) {
                // ECJ stops before member lookup on this receiver. A directly declared field is unambiguous.
                return type.getField(name, true);
            }
            return problem.closestMatch;
        }
        if (binding == null && receiver instanceof ReferenceBinding type) {
            return type.getField(name, true);
        }
        return binding;
    }

    /** Extracts the selected declaration from ECJ's visibility problem binding. */
    private static @Nullable MethodBinding method(@Nullable MethodBinding binding) {
        if (binding instanceof ProblemMethodBinding problem) {
            return problem.closestMatch;
        }
        return binding;
    }

    /** Checks for unresolved arguments that need an inner edit before overload resolution. */
    private static boolean valid(TypeBinding[] types) {
        for (TypeBinding type : types) {
            if (type == null || !type.isValidBinding()) {
                return false;
            }
        }
        return true;
    }

    /** Supplies the exact erased parameter names and applies Java invocation conversions before boxing. */
    private @Nullable String arguments(MethodBinding binding, Invocation invocation, Expression @Nullable [] expressions,
                                        TypeBinding[] types, BlockScope scope) {
        if (!valid(types)) {
            return null;
        }
        final int compatibility = scope.parameterCompatibilityLevel(binding, types, invocation);
        if (compatibility == Scope.NOT_COMPATIBLE) {
            return null;
        }
        final StringJoiner signature = new StringJoiner(", ", "new java.lang.String[]{", "}");
        for (TypeBinding parameter : binding.original().parameters) {
            signature.add(quote(binaryName(parameter)));
        }
        final StringJoiner values = new StringJoiner(", ", "new java.lang.Object[]{", "}");
        final boolean pack = compatibility == Scope.VARARGS_COMPATIBLE;
        final int fixedCount = pack ? binding.parameters.length - 1 : binding.parameters.length;
        for (int i = 0; i < fixedCount; i++) {
            if (expressions == null || i >= expressions.length) {
                return null;
            }
            values.add(argument(expressions[i], binding.parameters[i], scope));
        }
        if (pack) {
            final TypeBinding array = binding.parameters[fixedCount].erasure();
            if (!canName(array, scope)) {
                return null;
            }
            final StringJoiner elements = new StringJoiner(", ", "new " + new String(array.readableName()) + "{", "}");
            if (expressions != null) {
                for (int i = fixedCount; i < expressions.length; i++) {
                    elements.add(text(expressions[i]));
                }
            }
            values.add(elements.toString());
        }
        return signature + ", " + values;
    }

    /** Retains target typing for lambdas and converts primitives before they enter the reflection array. */
    private String argument(Expression expression, TypeBinding parameter, BlockScope scope) {
        if (canName(parameter, scope)) {
            return "(" + new String(parameter.readableName()) + ") (" + text(expression) + ")";
        }
        return text(expression);
    }

    /** Evaluates an expression qualifier to a static member once, then passes a null receiver. */
    private static String receiver(String expression, boolean typeQualifier, boolean isStatic) {
        if (typeQualifier) {
            return "null";
        }
        return isStatic ? "__mcpDiscard(" + expression + ")" : expression;
    }

    /** Emits a constant or a read of the exact field type. Other reads retain their original diagnostic. */
    private void rewriteField(int start, int end, FieldBinding field, boolean typeQualifier, String qualifier,
                              String receiverDescription, @Nullable TypeBinding qualifierType, BlockScope scope) {
        if (!canName(field.type, scope)) {
            refused = true;
            return;
        }
        final Constant constant = field.constant(scope);
        if (constant != Constant.NotAConstant) {
            if (!typeQualifier) {
                refused = true;
                return;
            }
            add(start, end, cast(field.type, constantLiteral(constant)));
            return;
        }
        final String name = new String(field.name);
        final String nullMessage = field.isStatic() ? "null"
            : quote("Cannot read field " + displayQuoted(name) + " because " + receiverDescription + " is null");
        final String invoke = "__mcpGet(" + receiver(qualifier, typeQualifier, field.isStatic()) + ", "
            + quote(binaryName(field.declaringClass)) + ", " + quote(name) + ", " + nullMessage + ")";
        receiverDescriptions.put(invoke, describeFieldValue(field, qualifierType, receiverDescription));
        add(start, end, cast(field.type, invoke));
    }

    /** Describes a field result if a later member access uses it as a receiver. */
    private static String describeFieldValue(FieldBinding field, @Nullable TypeBinding qualifier, String receiver) {
        final String member = new String(field.name);
        if (field.isStatic()) {
            return displayQuoted(displayType(qualifier == null ? field.declaringClass : qualifier) + "." + member);
        }
        if (receiver.startsWith("\"")) {
            return receiver.substring(0, receiver.length() - 1) + "." + member + "\"";
        }
        return "the receiver";
    }

    /** Writes an ECJ constant as a Java constant expression without reading its declaring class. */
    private static String constantLiteral(Constant constant) {
        return switch (constant.typeID()) {
            case TypeIds.T_boolean -> Boolean.toString(constant.booleanValue());
            case TypeIds.T_byte, TypeIds.T_short, TypeIds.T_char, TypeIds.T_int -> Integer.toString(constant.intValue());
            case TypeIds.T_long -> constant.longValue() + "L";
            case TypeIds.T_float -> floatingLiteral(constant.floatValue(), "f");
            case TypeIds.T_double -> floatingLiteral(constant.doubleValue(), "d");
            case TypeIds.T_JavaLangString -> quote(constant.stringValue());
            default -> throw new IllegalArgumentException("Unsupported constant type: " + constant.typeID());
        };
    }

    /** Preserves signed zero and non-finite constants with expressions of the original precision. */
    private static String floatingLiteral(double value, String suffix) {
        if (Double.isFinite(value)) {
            return Double.toHexString(value) + suffix;
        }
        final String numerator;
        if (Double.isNaN(value)) {
            numerator = "0.0";
        } else {
            numerator = value < 0 ? "-1.0" : "1.0";
        }
        return "(" + numerator + suffix + " / 0.0" + suffix + ")";
    }

    /** Renders a fixed cast to a type the source can name without erasure or widening. */
    private static String cast(TypeBinding type, String expression) {
        return "((" + new String(type.readableName()) + ") " + expression + ")";
    }

    /** Describes locals, fields and method results using the JVM's helpful-null wording. */
    private String describeReceiver(Expression expression) {
        if (expression instanceof CastExpression cast && cast.expression instanceof MessageSend call) {
            // A previous edit preserved the receiver's static type in a cast around this helper call.
            return receiverDescriptions.getOrDefault(source.substring(call.sourceStart, call.sourceEnd + 1),
                "the receiver");
        }
        if (expression instanceof MessageSend call) {
            final @Nullable MethodBinding binding = method(call.binding);
            if (binding != null) {
                return "the return value of " + displayQuoted(describeMethod(call.receiver.resolvedType, binding));
            }
        } else if (expression instanceof SingleNameReference name) {
            if (name.binding instanceof LocalVariableBinding) {
                return displayQuoted(displayLocal(name.token));
            }
            if (name.binding instanceof FieldBinding field) {
                return displayQuoted(fieldQualifier(field, null, "this") + "." + new String(field.name));
            }
        } else if (expression instanceof QualifiedNameReference name) {
            return describeQualifiedName(name, name.tokens.length - 1);
        } else if (expression instanceof FieldReference reference) {
            final @Nullable FieldBinding binding = field(reference.binding, reference.actualReceiverType, reference.token);
            if (binding != null) {
                if (binding.isStatic()) {
                    return displayQuoted(fieldQualifier(binding, reference.receiver.resolvedType, "this")
                        + "." + new String(reference.token));
                }
                if (reference.receiver instanceof ThisReference) {
                    return displayQuoted("this." + new String(reference.token));
                }
                if (reference.receiver instanceof SingleNameReference name && name.binding instanceof LocalVariableBinding) {
                    return displayQuoted(displayLocal(name.token) + "." + new String(reference.token));
                }
            }
        }
        return "the receiver";
    }

    /** Formats a dotted local or field path, restarting at the owning type for static fields. */
    private static String describeQualifiedName(QualifiedNameReference node, int end) {
        final int first = node.indexOfFirstFieldBinding - 1;
        final StringBuilder path = new StringBuilder();
        if (node.binding instanceof LocalVariableBinding) {
            path.append(displayLocal(node.tokens[0]));
        } else if (first == 0) {
            path.append("this");
        }
        for (int i = Math.max(first, 0); i <= end; i++) {
            if (i == 0 && node.binding instanceof LocalVariableBinding) {
                continue;
            }
            final @Nullable FieldBinding bound = fieldAt(node, i);
            if (bound != null && bound.isStatic()) {
                path.setLength(0);
                path.append(fieldQualifier(bound, i == first ? node.actualReceiverType : null, "this"));
            }
            if (path.length() != 0) {
                path.append('.');
            }
            path.append(node.tokens[i]);
        }
        return path.length() == 0 ? "the receiver" : displayQuoted(path.toString());
    }

    /** Names a static field by its owner and an instance field by its qualifier. */
    private static String fieldQualifier(FieldBinding binding, @Nullable TypeBinding qualifier, String instance) {
        if (!binding.isStatic()) {
            return instance;
        }
        return displayType(qualifier == null ? binding.declaringClass : qualifier);
    }

    /** Renders the wrapper's synthetic frame receiver using the source name the user wrote. */
    private static String displayLocal(char[] name) {
        final String local = new String(name);
        return local.matches("_this\\$*") ? "this" : local;
    }

    /** Prints the invocation owner and parameter types, as read from a JVM method descriptor. */
    private static String describeMethod(@Nullable TypeBinding receiver, MethodBinding binding) {
        final MethodBinding original = binding.original();
        final TypeBinding owner = receiver == null ? original.declaringClass : receiver;
        final StringJoiner result = new StringJoiner(", ", displayType(owner) + "." + new String(binding.selector) + "(", ")");
        for (final TypeBinding parameter : original.parameters) {
            result.add(displayType(parameter));
        }
        return result.toString();
    }

    /** Prints descriptor types using the abbreviated names used by the JVM for common JDK classes. */
    private static String displayType(TypeBinding type) {
        final TypeBinding erased = type.erasure();
        if (erased.isArrayType()) {
            return displayType(erased.leafComponentType()) + "[]".repeat(erased.dimensions());
        }
        final String binary = binaryName(erased);
        return switch (binary) {
            case "java.lang.Object" -> "Object";
            case "java.lang.String" -> "String";
            default -> binary;
        };
    }

    /** Quotes a name for display inside an exception message. */
    private static String displayQuoted(String text) {
        return '"' + text + '"';
    }

    /** A generated cast can name a type only if its qualifiers and type arguments are nameable too. */
    private static boolean canName(TypeBinding type, BlockScope scope) {
        if (type.isArrayType()) {
            return canName(type.leafComponentType(), scope);
        }
        if (type instanceof WildcardBinding wildcard) {
            return wildcard.bound == null || canName(wildcard.bound, scope);
        }
        if (type.isTypeVariable() || type.isIntersectionType18() || !type.canBeSeenBy(scope)) {
            return false;
        }
        if (type instanceof ParameterizedTypeBinding parameterized && parameterized.arguments != null) {
            for (TypeBinding argument : parameterized.arguments) {
                if (!canName(argument, scope)) {
                    return false;
                }
            }
        }
        final @Nullable ReferenceBinding enclosing = type.enclosingType();
        return enclosing == null || canName(enclosing, scope);
    }

    /** Returns the name accepted by Class.forName, or the keyword for a primitive. */
    private static String binaryName(TypeBinding type) {
        final TypeBinding erased = type.erasure();
        return erased.isBaseType() ? new String(erased.sourceName())
            : new String(erased.constantPoolName()).replace('/', '.');
    }

    /** Copies a Java subexpression and includes any inner accesses already rewritten in this pass. */
    private String text(Expression node) {
        final int start = node.sourceStart;
        final int end = node.sourceEnd + 1;
        final StringBuilder result = new StringBuilder(source.substring(start, end));
        for (int i = edits.size() - 1; i >= 0; i--) {
            final Edit edit = edits.get(i);
            if (start <= edit.start() && edit.end() <= end) {
                result.replace(edit.start() - start, edit.end() - start, edit.replacement());
            }
        }
        return result.toString();
    }

    /** Quotes a compiler-supplied Java name for the generated helper invocation. */
    private static String quote(String value) {
        final StringBuilder literal = new StringBuilder().append('"');
        for (int i = 0; i < value.length(); i++) {
            final char character = value.charAt(i);
            if (Character.isSurrogate(character)) {
                // The temporary source is UTF-8. Preserve isolated UTF-16 code units through it.
                literal.append("\\u").append(Integer.toHexString(character));
            } else {
                Util.appendEscapedChar(literal, character, true);
            }
        }
        return literal.append('"').toString();
    }

    /** Detects a constituent access that the next compiler pass can resolve before this one. */
    private boolean hasEditIn(Expression node) {
        for (Edit edit : edits) {
            if (node.sourceStart <= edit.start() && edit.end() <= node.sourceEnd + 1) {
                return true;
            }
        }
        return false;
    }

    /** An outer edit incorporates its rewritten receivers and arguments, then replaces their edits. */
    private void add(int start, int end, String replacement) {
        for (int i = edits.size() - 1; i >= 0; i--) {
            final Edit edit = edits.get(i);
            if (start <= edit.start() && edit.end() <= end + 1) {
                edits.remove(i);
            }
        }
        edits.add(new Edit(start, end + 1, replacement));
    }

    /** Applies non-overlapping edits in reverse order and appends the support code once. */
    private @Nullable String apply(String className, TypeDeclaration wrapper, boolean appendHelpers) {
        if (refused || edits.isEmpty()) {
            return null;
        }
        final StringBuilder rewritten = new StringBuilder(source);
        if (appendHelpers) {
            final String simpleName = className.substring(className.lastIndexOf('.') + 1);
            rewritten.insert(wrapper.bodyEnd, helpers(simpleName));
            guardModuleFailure(wrapper);
        }
        rewritten.insert(wrapper.bodyEnd, bridges);
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit edit : edits) {
            rewritten.replace(edit.start(), edit.end(), edit.replacement());
        }
        return rewritten.toString();
    }

    /** Reports blocked reflection even if the user's code handled the generated exception. */
    private void guardModuleFailure(TypeDeclaration wrapper) {
        for (AbstractMethodDeclaration method : wrapper.methods) {
            if (method.isMethod() && method.isStatic()
                && new String(method.selector).equals(JdiExpressionEvaluator.EVALUATION_METHOD_NAME)) {
                // ECJ's bodyEnd points just before the closing brace.
                final int end = method.bodyEnd + 1;
                edits.add(new Edit(end, end, "\n        } finally {\n            __mcpCheckAccess();\n        }\n"));
                edits.add(new Edit(method.bodyStart, method.bodyStart, "\n        try {"));
                return;
            }
        }
    }

    /** Emits target-side lookup, caching, and invocation using the wrapper's own class loader. */
    private static String helpers(String simpleName) {
        return """
            private static final java.util.Map<java.lang.String, java.lang.reflect.AccessibleObject> __mcpMembers =
                new java.util.concurrent.ConcurrentHashMap<>();
            private static final java.lang.ThreadLocal<java.lang.reflect.InaccessibleObjectException> __mcpClosedModule =
                new java.lang.ThreadLocal<>();

            private static void __mcpCheckAccess() {
                final java.lang.reflect.InaccessibleObjectException failure = __mcpClosedModule.get();
                __mcpClosedModule.remove();
                if (failure != null) {
                    throw failure;
                }
            }

            private static java.lang.Class<?> __mcpClass(java.lang.String name) throws java.lang.ClassNotFoundException {
                return switch (name) {
                    case "boolean" -> boolean.class;
                    case "byte" -> byte.class;
                    case "char" -> char.class;
                    case "short" -> short.class;
                    case "int" -> int.class;
                    case "long" -> long.class;
                    case "float" -> float.class;
                    case "double" -> double.class;
                    default -> java.lang.Class.forName(name, false, %s.class.getClassLoader());
                };
            }

            /** Reproduces the initialization triggered by new before the argument array is evaluated. */
            private static java.lang.String __mcpInitialize(java.lang.String owner) {
                try {
                    java.lang.Class.forName(owner, true, %s.class.getClassLoader());
                    return owner;
                } catch (java.lang.ClassNotFoundException failure) {
                    return (java.lang.String) __mcpThrow(failure);
                }
            }

            private static java.lang.Object __mcpDiscard(java.lang.Object qualifier) {
                return null;
            }

            private static java.lang.reflect.AccessibleObject __mcpMember(
                java.lang.String owner, java.lang.String name, java.lang.String[] signature
            ) throws java.lang.ReflectiveOperationException {
                final java.lang.String key = owner + "#" + name + java.util.Arrays.toString(signature);
                java.lang.reflect.AccessibleObject member = __mcpMembers.get(key);
                if (member != null) {
                    return member;
                }
                final java.lang.Class<?> declaring = __mcpClass(owner);
                if (signature == null) {
                    member = declaring.getDeclaredField(name);
                } else {
                    final java.lang.Class<?>[] parameters = new java.lang.Class<?>[signature.length];
                    for (int i = 0; i < parameters.length; i++) {
                        parameters[i] = __mcpClass(signature[i]);
                    }
                    member = name.isEmpty() ? declaring.getDeclaredConstructor(parameters)
                        : declaring.getDeclaredMethod(name, parameters);
                }
                try {
                    member.setAccessible(true);
                } catch (java.lang.reflect.InaccessibleObjectException closed) {
                    final java.lang.reflect.InaccessibleObjectException failure =
                        new java.lang.reflect.InaccessibleObjectException(
                            %s.class.getName() + "%s" + declaring.getModule().getName());
                    __mcpClosedModule.set(failure);
                    throw failure;
                }
                __mcpMembers.putIfAbsent(key, member);
                return member;
            }

            /** Checks the receiver before looking up or reading the field. */
            private static java.lang.Object __mcpGet(java.lang.Object receiver, java.lang.String owner,
                                                     java.lang.String name, java.lang.String nullMessage) {
                __mcpCheckReceiver(receiver, nullMessage);
                try {
                    return ((java.lang.reflect.Field) __mcpMember(owner, name, null)).get(receiver);
                } catch (java.lang.ReflectiveOperationException failure) {
                    return __mcpThrow(failure);
                }
            }

            /** Checks the receiver after the call site has evaluated the arguments. */
            private static java.lang.Object __mcpInvoke(java.lang.Object receiver, java.lang.String owner, java.lang.String name,
                                                        java.lang.String[] signature, java.lang.Object[] args,
                                                        java.lang.String nullMessage) {
                __mcpCheckReceiver(receiver, nullMessage);
                try {
                    return ((java.lang.reflect.Method) __mcpMember(owner, name, signature)).invoke(receiver, args);
                } catch (java.lang.reflect.InvocationTargetException target) {
                    return __mcpThrow(target.getCause());
                } catch (java.lang.ReflectiveOperationException failure) {
                    return __mcpThrow(failure);
                }
            }

            /** Preserves the void type of calls that cannot appear in a value context. */
            private static void __mcpInvokeVoid(java.lang.Object receiver, java.lang.String owner, java.lang.String name,
                                                java.lang.String[] signature, java.lang.Object[] args,
                                                java.lang.String nullMessage) {
                __mcpInvoke(receiver, owner, name, signature, args, nullMessage);
            }

            /** A null message marks static access, where a null qualifier is valid. */
            private static void __mcpCheckReceiver(java.lang.Object receiver, java.lang.String nullMessage) {
                if (receiver == null && nullMessage != null) {
                    throw new java.lang.NullPointerException(nullMessage);
                }
            }

            private static java.lang.Object __mcpConstruct(java.lang.String owner, java.lang.String[] signature, java.lang.Object[] args) {
                try {
                    return ((java.lang.reflect.Constructor<?>) __mcpMember(owner, "", signature)).newInstance(args);
                } catch (java.lang.reflect.InvocationTargetException target) {
                    return __mcpThrow(target.getCause());
                } catch (java.lang.ReflectiveOperationException failure) {
                    return __mcpThrow(failure);
                }
            }

            @java.lang.SuppressWarnings("unchecked")
            private static <E extends java.lang.Throwable> java.lang.Object __mcpThrow(java.lang.Throwable cause) throws E {
                throw (E) cause;
            }
            """.formatted(simpleName, simpleName, simpleName, MODULE_FAILURE);
    }
}
