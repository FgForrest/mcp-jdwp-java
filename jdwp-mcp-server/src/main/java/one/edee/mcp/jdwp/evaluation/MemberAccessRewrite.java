package one.edee.mcp.jdwp.evaluation;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.ForStatement;
import org.eclipse.jdt.internal.compiler.ast.Invocation;
import org.eclipse.jdt.internal.compiler.ast.LambdaExpression;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.batch.Main;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.InvocationSite;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemFieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemMethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemReasons;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.WildcardBinding;
import org.jspecify.annotations.Nullable;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
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
    /** Generated signatures for reflected calls that declare checked exceptions. */
    private final StringBuilder bridges = new StringBuilder();
    /** Next candidate suffix for a checked-exception bridge. */
    private int nextBridge;

    /** Replaces a half-open source range with one complete Java expression. */
    private record Edit(int start, int end, String replacement) {}

    /** Collects edits against the original source for this round. */
    private MemberAccessRewrite(String source, List<IProblem> accessProblems) {
        this.source = source;
        this.accessProblems = accessProblems;
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
     * Returns null when there is no supported safe edit.
     */
    static @Nullable String rewrite(String className, Path file, String source,
                                    InMemoryJavaCompiler.Configuration config, boolean appendHelpers) {
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
            final MemberAccessRewrite rewrite = new MemberAccessRewrite(source, access);
            unit.traverse(rewrite, unit.scope, false);
            return rewrite.apply(className, unit.types[0], appendHelpers);
        } finally {
            environment.cleanup();
        }
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
        if (isWrite(node) || node.receiver.isSuper()) {
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
        // ECJ can bind again after an ordinary receiver edit. An invisible receiver loses its
        // type when widened, so keep its outer field binding in the same edit.
        if (!invisibleReceiver && hasEditIn(node)) {
            return;
        }
        add(node.sourceStart, node.sourceEnd, read(field, receiver(node.receiver, field.isStatic()), scope));
    }

    /** ECJ represents a dotted sequence of bare names as one node, with a separate range for each token. */
    @Override
    public void endVisit(QualifiedNameReference node, BlockScope scope) {
        final int firstField = node.indexOfFirstFieldBinding - 1;
        if (firstField < 0) {
            return;
        }
        // ECJ includes enclosing parentheses in sourceStart, so token positions bound this edit.
        final int start = (int) (node.sourcePositions[0] >>> 32);
        @Nullable TypeBinding receiverType = node.actualReceiverType;
        @Nullable String rewrittenPrefix = null;
        int rewrittenEnd = start;
        for (int i = firstField; i < node.tokens.length; i++) {
            final @Nullable FieldBinding bound;
            if (i == firstField) {
                if (node.binding instanceof VariableBinding variable && !(variable instanceof FieldBinding)) {
                    receiverType = variable.type;
                    continue;
                }
                bound = node.binding instanceof FieldBinding f ? f : null;
            } else {
                bound = node.otherBindings == null ? null : node.otherBindings[i - firstField - 1];
            }
            final @Nullable FieldBinding field = field(bound, receiverType, node.tokens[i]);
            if (field == null) {
                break;
            }
            final long position = node.sourcePositions[i];
            final int end = (int) position;
            final int receiverEnd = i == 0 ? -1 : (int) node.sourcePositions[i - 1];
            final boolean receiverWillBeWidened = rewrittenPrefix != null && receiverType != null
                && !canName(receiverType, scope);
            if (receiverWillBeWidened
                || hasProblem(IProblem.NotVisibleField, (int) (position >>> 32), end, field.name, 0)
                || hasProblem(IProblem.InheritedFieldHidesEnclosingName, start, end, null, 0)
                || hasProblem(IProblem.NotVisibleType, start, receiverEnd,
                    receiverType == null ? null : receiverType.readableName(), 0)) {
                if (isWrite(node) && i == node.tokens.length - 1) {
                    return;
                }
                final String qualifier;
                if (i == firstField && field.isStatic()) {
                    qualifier = "null";
                } else if (receiverEnd >= start) {
                    final String prefix = rewrittenPrefix != null ? rewrittenPrefix : source.substring(start, receiverEnd + 1);
                    qualifier = field.isStatic() ? "__mcpDiscard(" + prefix + ")" : prefix;
                } else {
                    break;
                }
                rewrittenPrefix = read(field, qualifier, scope);
                rewrittenEnd = end;
                // Leave further accesses to ECJ except where widening would lose their receiver.
                if (canName(field.type, scope)) {
                    break;
                }
            }
            receiverType = field.type;
        }
        if (rewrittenPrefix != null) {
            add(start, rewrittenEnd, rewrittenPrefix);
        }
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
        if ((!inaccessible && !invisibleReceiver) || node.receiver.isSuper() || node.receiver.isImplicitThis()) {
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
        final @Nullable String arguments = arguments(binding, node, node.arguments, node.argumentTypes, scope);
        if (arguments == null) {
            return;
        }
        final boolean returnsVoid = binding.returnType == TypeBinding.VOID;
        final @Nullable String helper = bridge(binding, returnsVoid ? "__mcpInvokeVoid" : "__mcpInvoke", false, scope);
        if (helper == null) {
            return;
        }
        final String invoke = helper + "(" + receiver(node.receiver, binding.isStatic()) + ", "
            + quote(binaryName(binding.declaringClass)) + ", " + quote(new String(node.selector)) + ", " + arguments + ")";
        add(node.sourceStart, node.sourceEnd, returnsVoid || discardsValue(node) ? invoke : cast(binding.returnType, invoke, scope));
    }

    /** Rewrites ordinary class creation when the constructor alone is inaccessible. */
    @Override
    public void endVisit(AllocationExpression node, BlockScope scope) {
        if (!hasProblem(IProblem.NotVisibleConstructor, node.sourceStart, node.sourceEnd, null, 0)
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
        final @Nullable String helper = bridge(binding, "__mcpConstruct", true, scope);
        if (helper != null) {
            final String invoke = helper + "(" + quote(binaryName(binding.declaringClass)) + ", " + arguments + ")";
            add(node.sourceStart, node.sourceEnd, discardsValue(node) ? invoke : cast(node.resolvedType, invoke, scope));
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

    /** Preserves checked-exception reachability in surrounding catches, lambdas and methods. */
    private @Nullable String bridge(MethodBinding method, String helper, boolean constructor, BlockScope scope) {
        final StringJoiner exceptions = new StringJoiner(", ");
        for (ReferenceBinding exception : method.thrownExceptions) {
            if (!exception.isUncheckedException(false)) {
                if (!canName(exception, scope)) {
                    return null;
                }
                exceptions.add(new String(exception.readableName()));
            }
        }
        if (exceptions.length() == 0) {
            return helper;
        }
        String name;
        do {
            name = "__mcpChecked" + nextBridge++;
        } while (source.contains(" " + name + "("));
        final boolean returnsVoid = method.returnType == TypeBinding.VOID && !constructor;
        bridges.append("\nprivate static ").append(returnsVoid ? "void " : "java.lang.Object ").append(name).append('(');
        if (!constructor) {
            bridges.append("java.lang.Object receiver, ");
        }
        bridges.append("java.lang.String owner, ");
        if (!constructor) {
            bridges.append("java.lang.String name, ");
        }
        bridges.append("java.lang.String[] signature, java.lang.Object[] args) throws ")
            .append(exceptions).append(" {\n    ");
        if (!returnsVoid) {
            bridges.append("return ");
        }
        bridges.append(helper).append('(');
        if (constructor) {
            bridges.append("owner, ");
        } else {
            bridges.append("receiver, owner, name, ");
        }
        bridges.append("signature, args);\n}\n");
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

    /** Keeps writes, compound updates and increments subject to the original access checks. */
    private static boolean isWrite(ASTNode node) {
        return (node.bits & (ASTNode.IsStrictlyAssigned | ASTNode.IsCompoundAssigned)) != 0;
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
    private String receiver(Expression expression, boolean isStatic) {
        if (expression.isTypeReference()) {
            return "null";
        }
        final String receiver = text(expression);
        return isStatic ? "__mcpDiscard(" + receiver + ")" : receiver;
    }

    /** Renders a field read retaining the declared type when the wrapper can name it. */
    private static String read(FieldBinding field, String receiver, BlockScope scope) {
        final String invoke = "__mcpGet(" + receiver + ", " + quote(binaryName(field.declaringClass)) + ", "
            + quote(new String(field.name)) + ")";
        return cast(field.type, invoke, scope);
    }

    /** Casts a reflection result to the closest accessible Java type. */
    private static String cast(TypeBinding type, String invoke, BlockScope scope) {
        final TypeBinding castType = accessibleType(type, scope);
        return "((" + new String(castType.readableName()) + ") " + invoke + ")";
    }

    /** Searches supertypes by distance, using Object as a last resort. */
    private static TypeBinding accessibleType(TypeBinding type, BlockScope scope) {
        if (canName(type, scope)) {
            return type;
        }
        if (type.isArrayType()) {
            return scope.createArrayType(accessibleType(type.leafComponentType(), scope), type.dimensions());
        }
        final Deque<TypeBinding> queue = new ArrayDeque<>();
        final Set<TypeBinding> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            final TypeBinding next = queue.removeFirst();
            if (!seen.add(next)) {
                continue;
            }
            if (next.id != TypeIds.T_JavaLangObject) {
                if (canName(next, scope)) {
                    return next;
                }
                final TypeBinding erased = next.erasure();
                if (canName(erased, scope)) {
                    return erased;
                }
            }
            final @Nullable ReferenceBinding parent = next.superclass();
            if (parent != null) {
                queue.add(parent);
            }
            for (ReferenceBinding face : next.superInterfaces()) {
                queue.add(face);
            }
        }
        return scope.getJavaLangObject();
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
        return "\"" + value + "\"";
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
        if (edits.isEmpty()) {
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

            private static java.lang.Object __mcpGet(java.lang.Object receiver, java.lang.String owner, java.lang.String name) {
                try {
                    return ((java.lang.reflect.Field) __mcpMember(owner, name, null)).get(receiver);
                } catch (java.lang.ReflectiveOperationException failure) {
                    return __mcpThrow(failure);
                }
            }

            private static java.lang.Object __mcpInvoke(java.lang.Object receiver, java.lang.String owner, java.lang.String name,
                                              java.lang.String[] signature, java.lang.Object[] args) {
                try {
                    return ((java.lang.reflect.Method) __mcpMember(owner, name, signature)).invoke(receiver, args);
                } catch (java.lang.reflect.InvocationTargetException target) {
                    return __mcpThrow(target.getCause());
                } catch (java.lang.ReflectiveOperationException failure) {
                    return __mcpThrow(failure);
                }
            }

            private static void __mcpInvokeVoid(java.lang.Object receiver, java.lang.String owner, java.lang.String name,
                                               java.lang.String[] signature, java.lang.Object[] args) {
                __mcpInvoke(receiver, owner, name, signature, args);
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
            """.formatted(simpleName, simpleName, MODULE_FAILURE);
    }
}
