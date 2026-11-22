package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiClassDefinitionException;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import one.edee.mcp.jdwp.evaluation.exceptions.LocalClasspathPendingException;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider.DiscoveryStatus;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider.Snapshot;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates evaluation of a user-supplied Java expression against a live JDI {@link StackFrame}.
 * <p>
 * Pipeline (per call to {@link #evaluate}):
 * 1. Build evaluation context from the frame (locals + `this`).
 * 2. Auto-rewrite bare field identifiers and method calls (`field` → `_this.field`, `size()` →
 * `_this.size()`) via {@link #rewriteThisMemberReferences} when safe; explicit `this`/`this.field` is handled
 * separately by {@link #rewriteThisKeyword}.
 * 3. Cache lookup keyed on wrapper package, evaluation mode, signature and expression (the package
 * is part of the key because a define-time fallback may recompile the same expression into a
 * different package); on miss, generate a wrapper class with a UUID-suffixed name, compile via
 * {@link InMemoryJavaCompiler}, and cache the bytecode.
 * 4. Inject the bytecode into the target VM and execute via {@link RemoteCodeExecutor}.
 * <p>
 * Cache eviction: when {@link CompilerState#cache()} reaches {@link #MAX_CACHE_SIZE} entries it is
 * fully flushed rather than LRU-evicted (see the inline comment on the eviction call for the
 * design rationale).
 * <p>
 * Class naming: every generated wrapper uses a fresh `UUID`-suffixed name. This avoids
 * `LinkageError` when the MCP server reconnects to a freshly-restarted target VM and tries to
 * load bytecode that the previous session had already pinned to a class name.
 * <p>
 * Callers must configure the compiler before calling {@link #evaluate} so classpath discovery
 * runs separately from expression evaluation. MCP tool evaluations use
 * {@link #configureCompilerClasspath(ThreadReference)}. The event dispatcher uses
 * {@link #configureCompilerClasspathForEvent(ThreadReference)} so it can continue processing events
 * while local discovery runs. The JDI event listener must never call either entry point. The remote
 * classloader walk can invoke methods in the target that raise breakpoint events, and the listener
 * must keep draining and releasing those events for the invocation to finish.
 */
@Service
public class JdiExpressionEvaluator {
    private static final Logger log = LoggerFactory.getLogger(JdiExpressionEvaluator.class);

    /**
     * How often {@link #evaluate} reads its frame when a resume elsewhere invalidated it meanwhile.
     */
    private static final int FRAME_READ_ATTEMPTS = 3;

    /**
     * Default package used for the generated wrapper class when {@code this} is public (or absent).
     * Kept short and isolated from app code. Non-public {@code this} types may instead emit the
     * wrapper into {@code this}'s own package so package-private fields and the type itself become
     * reachable — see {@link #resolveWrapperPackage}.
     */
    public static final String DEFAULT_EVALUATION_PACKAGE = "mcp.jdi.evaluation";
    /**
     * Class name prefix; the actual name is `<prefix><UUID>` for collision-free reloads.
     */
    private static final String EVALUATION_CLASS_PREFIX = "ExpressionEvaluator_";
    /**
     * Static method name on every wrapper class; signature is `(<context vars>) -> Object`.
     */
    private static final String EVALUATION_METHOD_NAME = "evaluate";
    /**
     * Threshold for the full-flush eviction — see {@link CompilerState#cache()}.
     */
    private static final int MAX_CACHE_SIZE = 100;

    private final InMemoryJavaCompiler compiler;
    private final JDIConnectionService jdiConnectionService;
    private final EvaluationGuard evaluationGuard;
    /**
     * Additive local-project classpath. Unioned with the remote classloader-discovered classpath in
     * {@link #configureCompilerClasspath} to fill gaps the remote walk cannot see (Tomcat / Spring
     * Boot dev-tools / custom URLClassLoaders that hide their JARs from {@code getURLs()}). Reset
     * on every reconnect so a new connection sees the current project layout.
     */
    private final LocalProjectClasspathProvider localClasspathProvider;

    /**
     * Compilation cache keyed by wrapper package, context signature, and expression. Frames with
     * the same local types and names can reuse the compiled wrapper in the same package.
     * Flushes the whole map at {@link #MAX_CACHE_SIZE}. Compiler reconfiguration replaces the map
     * so a concurrent compilation can only populate the cache it started with.
     */
    private volatile CompilerState compilerState =
        new CompilerState(null, null, null, false, null, new ConcurrentHashMap<>());

    /**
     * Binds the VM and discovery snapshot to the options and wrapper cache. A tool worker or
     * dispatcher captures one state so each compilation uses the options that own its cache.
     * {@code localEntriesAdded} records whether the merge changed the remote-only classpath.
     */
    private record CompilerState(@Nullable VirtualMachine vm, @Nullable Snapshot snapshot,
                                 LocalProjectClasspathProvider.@Nullable Breakdown mergedLocal,
                                 boolean localEntriesAdded,
                                 InMemoryJavaCompiler.@Nullable Configuration options,
                                 Map<String, CachedCompilation> cache) {}

    /** Compiled classes and whether they can enter the wrapper cache for the captured options. */
    private record Compilation(Map<String, byte[]> classes, boolean cacheable) {}

    public JdiExpressionEvaluator(
        InMemoryJavaCompiler compiler,
        JDIConnectionService jdiConnectionService,
        EvaluationGuard evaluationGuard,
        LocalProjectClasspathProvider localClasspathProvider
    ) {
        this.compiler = compiler;
        this.jdiConnectionService = jdiConnectionService;
        this.evaluationGuard = evaluationGuard;
        this.localClasspathProvider = localClasspathProvider;
    }

    /**
     * Rewrites bare references to fields of {@code this} as {@code _this.field} so the wrapper class
     * can resolve them. Only safe to call when {@code this}'s declared type is public AND each
     * candidate field is itself public — for non-public types or fields the wrapper class still
     * couldn't access the field even after the rewrite. The caller decides what's safe to pass.
     *
     * <p>Implemented as a hand-rolled lightweight tokenizer rather than a regex so that bare field
     * names appearing INSIDE string literals, char literals, or text blocks are NOT rewritten.
     * Without this, an expression like {@code "name=" + name} (with field {@code name}) would
     * incorrectly become {@code "_this.name=" + _this.name}, corrupting the string content.
     *
     * <p>The tokenizer handles:
     * <ul>
     *   <li>Regular string literals {@code "..."} with backslash escapes</li>
     *   <li>Java text blocks {@code """..."""} with multi-line content and escapes</li>
     *   <li>Character literals {@code '.'} including {@code 'A'} escapes</li>
     *   <li>Qualified references — an identifier preceded by {@code .} (with optional whitespace)
     *       is treated as a field/method access on something else and is NOT rewritten</li>
     *   <li>Identifier characters per {@link Character#isJavaIdentifierStart(int)} /
     *       {@link Character#isJavaIdentifierPart(int)}</li>
     * </ul>
     *
     * <p>Static + package-private so it can be unit-tested without a real {@link StackFrame}.
     *
     * @param expression      the user-supplied Java expression
     * @param thisFieldNames  field names declared on {@code this}'s type (already filtered for publicness)
     * @param shadowingLocals local variable names that shadow fields and must NOT be rewritten
     * @return the expression with bare field references (outside string/char literals) prefixed by {@code _this.}
     */
    static String rewriteThisFieldReferences(String expression, Set<String> thisFieldNames,
                                             Set<String> shadowingLocals) {
        return rewriteThisMemberReferences(expression, thisFieldNames, Set.of(), shadowingLocals);
    }

    /**
     * {@link #rewriteThisFieldReferences} for methods too: an unqualified call {@code sold()} of a
     * method of {@code this} becomes {@code _this.sold()}. An identifier followed by {@code (} is a
     * call — rewritten only when {@code thisMethodNames} holds it, never as a field — and a
     * constructor call ({@code new Foo(...)}) is left alone. Locals cannot be called, so they shadow
     * fields only.
     *
     * @param thisMethodNames method names declared on {@code this}'s type (already filtered for
     *                        accessibility)
     */
    static String rewriteThisMemberReferences(String expression, Set<String> thisFieldNames,
                                              Set<String> thisMethodNames, Set<String> shadowingLocals) {
        return rewriteThisMemberReferences(expression, thisFieldNames, thisMethodNames, shadowingLocals, "_this");
    }

    /**
     * Returns the expression with eligible bare fields and method calls qualified by the chosen receiver name.
     * Leaves literals, qualified members, and fields shadowed by locals as written.
     */
    private static String rewriteThisMemberReferences(String expression, Set<String> thisFieldNames,
                                                      Set<String> thisMethodNames, Set<String> shadowingLocals,
                                                      String receiver) {
        final Set<String> rewritable = thisFieldNames.stream()
            .filter(name -> !shadowingLocals.contains(name))
            .collect(Collectors.toSet());
        if (rewritable.isEmpty() && thisMethodNames.isEmpty()) {
            return expression;
        }

        final StringBuilder out = new StringBuilder(expression.length() + 16);
        int i = 0;
        final int n = expression.length();
        while (i < n) {
            final char c = expression.charAt(i);
            if (c == '"') {
                // String literal or text block — copy verbatim, do not rewrite contents
                final int end = skipStringLiteral(expression, i);
                out.append(expression, i, end);
                i = end;
            } else if (c == '\'') {
                // Char literal — copy verbatim
                final int end = skipCharLiteral(expression, i);
                out.append(expression, i, end);
                i = end;
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(expression.charAt(end))) {
                    end++;
                }
                final String identifier = expression.substring(i, end);
                final boolean call = isFollowedByParenthesis(expression, end);
                final boolean member = call
                    ? thisMethodNames.contains(identifier) && !isPrecededByNew(expression, i)
                    : rewritable.contains(identifier);
                if (!member || isPrecededByDot(expression, i)) {
                    out.append(identifier);
                } else {
                    out.append(receiver).append('.').append(identifier);
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Rewrites bare `this` keyword references to `_this` so the wrapper class — which compiles to
     * a static method and therefore has no real `this` — can refer to the original `this` via its
     * synthetic parameter. Uses the same hand-rolled tokenizer as {@link #rewriteThisFieldReferences}
     * so that the keyword is NOT rewritten when it appears inside a string literal, char literal,
     * or text block, and identifiers that merely contain `this` as a substring (e.g. `myThis`,
     * `thisFoo`) are left untouched.
     *
     * <p>Replaces an earlier naive `replaceAll("(?&lt;!\\w)this(?!\\w)", "_this")` that corrupted
     * string-literal contents.
     *
     * <p>Static + package-private so it can be unit-tested without a real {@link StackFrame}.
     */
    static String rewriteThisKeyword(String expression) {
        return rewriteThisKeyword(expression, "_this");
    }

    /** Returns the expression with {@code this} tokens outside literals replaced by the receiver name. */
    private static String rewriteThisKeyword(String expression, String receiver) {
        final StringBuilder out = new StringBuilder(expression.length() + 8);
        int i = 0;
        final int n = expression.length();
        while (i < n) {
            final char c = expression.charAt(i);
            if (c == '"') {
                final int end = skipStringLiteral(expression, i);
                out.append(expression, i, end);
                i = end;
            } else if (c == '\'') {
                final int end = skipCharLiteral(expression, i);
                out.append(expression, i, end);
                i = end;
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(expression.charAt(end))) {
                    end++;
                }
                final String identifier = expression.substring(i, end);
                if ("this".equals(identifier)) {
                    out.append(receiver);
                } else {
                    out.append(identifier);
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Returns true if the character at position {@code pos} is preceded by a {@code .}
     * (skipping whitespace) — indicating a qualified reference like {@code obj.field}
     * or {@code obj . field} that should NOT be rewritten.
     */
    /**
     * Whether the next non-whitespace character at or after {@code pos} is {@code (}.
     */
    private static boolean isFollowedByParenthesis(String s, int pos) {
        for (int j = pos; j < s.length(); j++) {
            final char c = s.charAt(j);
            if (!Character.isWhitespace(c)) {
                return c == '(';
            }
        }
        return false;
    }

    /**
     * Whether the token before {@code pos} is the keyword {@code new}.
     */
    private static boolean isPrecededByNew(String s, int pos) {
        int j = pos - 1;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) {
            j--;
        }
        final int end = j + 1;
        while (j >= 0 && Character.isJavaIdentifierPart(s.charAt(j))) {
            j--;
        }
        return "new".equals(s.substring(j + 1, end));
    }

    private static boolean isPrecededByDot(String s, int pos) {
        for (int j = pos - 1; j >= 0; j--) {
            final char p = s.charAt(j);
            if (Character.isWhitespace(p)) {
                continue;
            }
            return p == '.';
        }
        return false;
    }

    /**
     * Returns the index just past the end of a string literal that starts at position {@code start}.
     * Handles both regular strings ({@code "..."}) and Java text blocks ({@code """..."""}),
     * with backslash escape sequences. If the literal is unterminated, returns the end of the string
     * (best-effort tolerance — we never throw on malformed input).
     */
    private static int skipStringLiteral(String s, int start) {
        final int n = s.length();
        // Text block: """..."""
        if (start + 3 <= n && s.charAt(start + 1) == '"' && s.charAt(start + 2) == '"') {
            int i = start + 3;
            while (i < n) {
                if (s.charAt(i) == '\\') {
                    i = Math.min(i + 2, n);
                    continue;
                }
                if (i + 3 <= n && s.charAt(i) == '"' && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                    return i + 3;
                }
                i++;
            }
            return n;
        }
        // Regular string literal
        int i = start + 1;
        while (i < n) {
            final char c = s.charAt(i);
            if (c == '\\') {
                i = Math.min(i + 2, n);
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /**
     * Returns the index just past the end of a char literal starting at position {@code start}.
     * Handles backslash escapes (including {@code 'A'} unicode escapes). Tolerant of
     * unterminated literals — returns the end of the string in that case.
     */
    private static int skipCharLiteral(String s, int start) {
        final int n = s.length();
        int i = start + 1;
        while (i < n) {
            final char c = s.charAt(i);
            if (c == '\\') {
                i = Math.min(i + 2, n);
                continue;
            }
            if (c == '\'') {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /**
     * Extracts locals and the optional receiver from a stack frame, then appends any caller-supplied
     * synthetic bindings. A frame without local-variable debug info can still use the receiver and
     * synthetic bindings.
     *
     * @param frame          stack frame providing locals and `this`
     * @param extraBindings  additional name → value pairs to expose as synthetic locals (used for
     *                       `$exception` when evaluating at an exception breakpoint). Iteration
     *                       order is preserved as wrapper-method parameter order — pass a
     *                       {@link java.util.LinkedHashMap} or {@code Map.of(...)} to keep the
     *                       order deterministic.
     * @param wrapperPackage the package the wrapper class will live in. When this matches the
     *                       package of a referenced type, that type's package-private members
     *                       become accessible to the wrapper, so the type can be exposed by its
     *                       actual (non-public) name instead of walking up to a public supertype.
     * @param receiverType   the receiver's binding type, or {@code null} to omit the receiver
     */
    private static EvaluationContext buildContext(StackFrame frame, Map<String, Value> extraBindings,
                                                  String wrapperPackage, @Nullable ReferenceType receiverType)
        throws AbsentInformationException {
        final List<ContextVariable> variables = new ArrayList<>();
        final List<Value> values = new ArrayList<>();

        // Enumerate locals defensively. A target compiled without a local-variable table
        // (`javac` default, or an explicit `-g:none` / debug-info-stripped release build) makes
        // frame.visibleVariables() throw AbsentInformationException — with a null message. Letting
        // that abort the whole evaluation is wrong: an expression that names only `_this` and the
        // synthetic bindings ($oldValue/$newValue/$object/$exception/$mark…) needs no locals at all,
        // yet would fail with an opaque "Expression evaluation failed: null". Skip locals instead;
        // the expression still compiles against this + the synthetics, and an expression that *does*
        // reference a now-absent local gets a clear "X cannot be resolved" compile error rather than
        // a null.
        try {
            for (LocalVariable var : frame.visibleVariables()) {
                // Filter out synthetic `this$N` outer-class references emitted by `javac` for inner classes:
                // they live in a different package than the wrapper class and so cannot be addressed from
                // inside it. The `isArgument()` allowance is defensive — a real argument named `this$N`
                // would be unusual but is technically valid bytecode.
                if (var.isArgument() || !var.name().startsWith("this$")) {
                    final String typeName = resolveLocalVarType(var, wrapperPackage);
                    variables.add(new ContextVariable(var.name(), typeName));
                    values.add(frame.getValue(var));
                }
            }
        } catch (AbsentInformationException noLocals) {
            log.debug("[Evaluator] No local-variable table on the firing frame (target compiled "
                + "without -g:vars?) — evaluating with `this` + synthetic bindings only.");
        }

        // Synthetic bindings are appended last so they appear after the real locals in the wrapper
        // signature. Cache key (signature + expression) naturally varies on the bound type, so two
        // different exception subclasses thrown at the same site get distinct cached compilations.
        for (Map.Entry<String, Value> entry : extraBindings.entrySet()) {
            variables.add(new ContextVariable(entry.getKey(), inferDeclaredType(entry.getValue(), wrapperPackage)));
            values.add(entry.getValue());
        }
        if (receiverType != null) {
            final String receiver = availableReceiverName(variables.stream().map(v -> v.name).toList());
            variables.add(0, new ContextVariable(receiver, getDeclaredType(receiverType, wrapperPackage)));
            values.add(0, frame.thisObject());
        }

        return new EvaluationContext(variables, values);
    }

    /** Returns {@code _this} with enough trailing dollar signs to avoid existing bindings. */
    private static String availableReceiverName(List<String> names) {
        String receiver = "_this";
        while (names.contains(receiver)) {
            receiver += '$';
        }
        return receiver;
    }

    /**
     * Resolves a wrapper-visible declared type for a JDI {@link Value}. Mirrors the public-supertype
     * walk performed by {@link #getDeclaredType(ReferenceType, String)} so a non-public exception
     * subclass still binds as a public ancestor (worst case {@code java.lang.Object}). Falls back
     * to {@code java.lang.Object} for {@code null} and unrecognised value kinds.
     */
    private static String inferDeclaredType(@Nullable Value value, String wrapperPackage) {
        if (value instanceof ObjectReference objRef) {
            return getDeclaredType(objRef.referenceType(), wrapperPackage);
        }
        if (value instanceof PrimitiveValue primValue) {
            return primValue.type().name();
        }
        return "java.lang.Object";
    }

    /**
     * Resolves a local variable's declared type to one the wrapper class can reference.
     * Falls back to a public supertype (or Object) for non-public types that live outside the
     * wrapper's package.
     */
    private static String resolveLocalVarType(LocalVariable var, String wrapperPackage) {
        try {
            final Type t = var.type();
            if (t instanceof ReferenceType refType) {
                return getDeclaredType(refType, wrapperPackage);
            }
            return t.name();
        } catch (ClassNotLoadedException e) {
            // Type not loaded yet — fall back to declared name (may still be valid if a public type)
            return var.typeName();
        }
    }

    /**
     * Generates a wrapper class with a static `evaluate()` method that accepts the context
     * variables as parameters and returns the result of the user input.
     *
     * <p>Two input modes, picked by {@link #isBlockMode}:
     * <ul>
     *   <li><b>Expression mode (default).</b> The input is treated as a single Java expression and
     *       wrapped as {@code return (Object) (<expr>);} so any value type — including primitives
     *       via autoboxing — flows back as the method's return value.</li>
     *   <li><b>Block mode.</b> The trimmed input starts with '{' and ends with the matching '}';
     *       the inside is spliced into the method body verbatim, guarded by a runtime-only branch
     *       so that the trailing fallthrough {@code return null;} stays reachable even when the
     *       user body ends with an explicit {@code return X;}. The user is responsible for writing
     *       {@code return X;} statements to yield a value — block mode supports {@code try/catch},
     *       intermediate locals, early {@code return}, and other statement-level constructs that
     *       the single-expression mode could not express.</li>
     * </ul>
     *
     * <p>In both modes, the bare {@code this} keyword is tokenizer-rewritten to {@code _this}
     * because the wrapper is a static method.
     *
     * <p>The package portion of {@code className} drives the wrapper's {@code package} declaration.
     * When the caller routes a non-public {@code this} type into its own package via
     * {@link #resolveWrapperPackage}, the wrapper compiles next to that type and can dereference
     * its package-private members directly.
     */
    private static String generateSourceCode(String className, EvaluationContext context, String expression,
                                             boolean renderCapture, boolean retainResult) {
        final int lastDot = className.lastIndexOf('.');
        final String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);
        final String simpleClassName = lastDot < 0 ? className : className.substring(lastDot + 1);

        final List<FastLogpointProbeSource.Parameter> parameters = context.getVariables().stream()
            .map(v -> new FastLogpointProbeSource.Parameter(v.name, v.type)).toList();

        final String packageDecl = packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
        final StringBuilder source = new StringBuilder(packageDecl +
            "// Automatically generated class for JDI expression evaluation\n" +
            "public class " + simpleClassName + " {\n");
        final String entry = retainResult ? "__mcpArmingValue" : EVALUATION_METHOD_NAME;
        final String visibility = retainResult ? "private" : "public";
        if (retainResult) {
            final List<FastLogpointProbeSource.Parameter> forwarded = FastLogpointProbeSource.forwardedParameters(parameters);
            source.append("    private static java.lang.Object ").append(RemoteCodeExecutor.ARMING_RESULT_FIELD).append(";\n")
                .append("    public static java.lang.Object ").append(EVALUATION_METHOD_NAME).append('(')
                .append(FastLogpointProbeSource.declaredParameters(forwarded)).append(") throws java.lang.Throwable {\n")
                .append("        return ").append(RemoteCodeExecutor.ARMING_RESULT_FIELD).append(" = ")
                .append(entry).append('(').append(FastLogpointProbeSource.arguments(forwarded)).append(");\n")
                .append("    }\n");
        }
        if (renderCapture) {
            FastBreakpointProbeSource.appendCapture(source, visibility + " static java.lang.String " + entry,
                parameters, expression);
            FastLogpointProbeSource.appendFailureRenderer(source);
            FastLogpointProbeSource.appendValueRenderer(source);
        } else {
            source.append("    ").append(visibility).append(" static java.lang.Object ").append(entry).append('(')
                .append(FastLogpointProbeSource.declaredParameters(parameters)).append(") throws java.lang.Throwable {\n")
                .append(generateMethodBody(expression)).append("    }\n");
        }
        return source.append("}\n").toString();
    }

    /**
     * Generates the body of a static {@code Object}-returning method that yields the value of the
     * user input — in expression mode a single {@code return (Object) (...)}, in block mode the
     * user's statements behind a fallthrough guard (see {@link #generateSourceCode} for both
     * modes). The bare {@code this} keyword is rewritten to {@code _this} first. Shared by the
     * expression-evaluation wrapper and the fast-logpoint probe ({@link FastLogpointProbeSource}).
     */
    static String generateMethodBody(String expression) {
        // Replace bare `this` keyword with `_this` to match the wrapper's static-method parameter name.
        // Tokenizer-aware so identifiers like `myThis`/`thisFoo` and `this` tokens inside string/char
        // literals are NOT rewritten — see {@link #rewriteThisKeyword(String)}.
        final String safeExpression = rewriteThisKeyword(expression);

        final String trimmed = safeExpression.trim();
        final int bodyEnd = blockEnd(trimmed);
        final String methodBody;
        if (bodyEnd >= 0) {
            // Extract the body inside the outermost braces — already balanced because
            // isBlockMode verified the structure (start with `{`, balanced end with `}`,
            // string/char/text-block/comment regions skipped).
            //
            // A method call is not a compile-time constant, so the post-if return is reachable.
            // The cast uses the JDK type in a type position, even if a local is named "java".
            // That keeps the wrapper compiling whether or not the user's body ends with an
            // explicit `return X;`. At runtime the guard is always true → user body always runs;
            // `return null;` is the safety net for blocks that fall through without returning.
            final String userBody = trimmed.substring(1, bodyEnd);
            methodBody =
                "        // User block:\n" +
                "        if (((java.lang.Boolean) true).booleanValue()) {\n" +
                userBody + '\n' +
                "        }\n" +
                "        // Fallthrough guard — only reached when the user's block doesn't return.\n" +
                "        return null;\n";
        } else {
            methodBody =
                "        // User expression:\n" +
                "        return (java.lang.Object) (" + safeExpression + "\n        );\n";
        }
        return methodBody;
    }

    /**
     * Returns {@code true} when the user input is in block mode — i.e. its trimmed form starts
     * with '{' and closes with the matching '}', optionally followed by comments. Tokenizer-aware: braces inside string / char /
     * text-block literals and inside line ({@code //…}) or block ({@code /*…*}{@code /}) comments
     * are ignored, so inputs like {@code "{x}".length()} (a method call on a string literal that
     * happens to start with '{') stay in expression mode, and a comment containing '}' inside an
     * otherwise-block input does not prematurely close the outer block.
     *
     * <p>Static + package-private so it can be unit-tested without a real {@link StackFrame}.
     */
    static boolean isBlockMode(String expression) {
        return blockEnd(expression.trim()) >= 0;
    }

    /** Returns the closing brace index of a Java block followed only by whitespace or comments, or {@code -1}. */
    private static int blockEnd(String trimmed) {
        if (trimmed.length() < 2 || trimmed.charAt(0) != '{') {
            return -1;
        }
        final int n = trimmed.length();
        int depth = 1;
        int i = 1;
        while (i < n) {
            final char c = trimmed.charAt(i);
            if (c == '"') {
                i = skipStringLiteral(trimmed, i);
                continue;
            }
            if (c == '\'') {
                i = skipCharLiteral(trimmed, i);
                continue;
            }
            if (c == '/' && i + 1 < n) {
                final char next = trimmed.charAt(i + 1);
                if (next == '/') {
                    i = skipLineComment(trimmed, i);
                    continue;
                }
                if (next == '*') {
                    i = skipBlockComment(trimmed, i);
                    continue;
                }
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return onlyTrailingComments(trimmed, i + 1) ? i : -1;
                }
            }
            i++;
        }
        return -1;
    }

    /** Returns whether the suffix contains only whitespace and complete Java comments. */
    private static boolean onlyTrailingComments(String text, int from) {
        int i = from;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
            } else if (text.startsWith("//", i)) {
                i = skipLineComment(text, i);
            } else if (text.startsWith("/*", i)) {
                final int end = text.indexOf("*/", i + 2);
                if (end < 0) {
                    return false;
                }
                i = end + 2;
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the index just past the end of a {@code //…} line comment that starts at
     * {@code start}. Terminator is the next newline; if no newline is found, returns the end of
     * the string (best-effort tolerance — never throws).
     */
    private static int skipLineComment(String s, int start) {
        final int n = s.length();
        int i = start + 2;
        while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
            i++;
        }
        // Caller resumes scanning AT the newline (or end). The newline itself is not part of the
        // comment, so we don't advance past it here.
        return i;
    }

    /**
     * Returns the index just past the end of a {@code /*…*}{@code /} block comment that starts at
     * {@code start}. If the closing {@code *}{@code /} is missing, returns the end of the string
     * (best-effort tolerance — never throws).
     */
    private static int skipBlockComment(String s, int start) {
        final int n = s.length();
        int i = start + 2;
        while (i + 1 < n) {
            if (s.charAt(i) == '*' && s.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return n;
    }

    /**
     * Get a name suitable to use in the generated wrapper class for the given runtime type.
     * Handles three cases the wrapper compiler cannot otherwise express:
     * <ul>
     *   <li><b>Dynamic proxies</b> (Guice, CGLIB, Mockito, Spring AOP) — unwrap to the real class.</li>
     *   <li><b>Non-public types in {@code wrapperPackage}</b> — exposed by their actual binary name
     *       (with nested-class {@code $} → {@code .} so the compiler accepts the source form). The
     *       wrapper lives in the same package, so package-private types and members are reachable.</li>
     *   <li><b>Non-public class types in other packages</b> — walk up the superclass chain until a
     *       type the wrapper CAN reference is found (public, or sharing {@code wrapperPackage}),
     *       falling back to {@code java.lang.Object}.</li>
     *   <li><b>Non-public interface types in other packages</b> — a local declared as a non-public
     *       interface (e.g. a package-private interface) reaches here via {@link #resolveLocalVarType}.
     *       Walk the {@code superinterfaces()} graph for a reachable interface, falling back to
     *       {@code java.lang.Object}. Without this the raw interface name would be emitted and the
     *       wrapper would fail to compile.</li>
     * </ul>
     *
     * @param wrapperPackage the package the wrapper class lives in; types in this package are
     *                       reachable even when package-private. Pass {@link #DEFAULT_EVALUATION_PACKAGE}
     *                       for the legacy "only public types reachable" behaviour.
     */
    private static String getDeclaredType(ReferenceType type, String wrapperPackage) {
        while (true) {
            String typeName = type.name();

            // Check if it's a dynamic proxy (contains $$ which is common for Guice, CGLIB, Mockito, etc.)
            if (typeName.contains("$$")) {
                // Try to get the superclass (proxies usually extend the real class)
                if (type instanceof ClassType classType) {
                    final ClassType superclass = classType.superclass();
                    if (superclass != null && !"java.lang.Object".equals(superclass.name())) {
                        type = superclass;
                        continue;
                    }
                }

                // Fallback: try to extract the base class name before $$
                final int dollarIndex = typeName.indexOf("$$");
                if (dollarIndex > 0) {
                    typeName = typeName.substring(0, dollarIndex);
                }
            }

            // Walk up to find a supertype the wrapper can reference: either public, or a non-public
            // type that shares the wrapper's package (which makes package-private access legal).
            if (type instanceof ClassType classType) {
                ClassType current = classType;
                while (current != null && !isReachableFromWrapper(current, wrapperPackage)) {
                    current = current.superclass();
                }
                if (current != null) {
                    return toSourceTypeName(current.name());
                }
                return "java.lang.Object";
            }

            // A non-public interface (typically a package-private interface used as a local's
            // declared type) cannot be named from the wrapper. Walk its superinterface graph for
            // one that can, else fall back to Object — the value is always assignable to it.
            if (type instanceof InterfaceType interfaceType) {
                final InterfaceType reachable = findReachableInterface(interfaceType, wrapperPackage, new HashSet<>());
                return reachable != null ? toSourceTypeName(reachable.name()) : "java.lang.Object";
            }

            return toSourceTypeName(typeName);
        }
    }

    /**
     * Depth-first search of {@code type}'s superinterface graph for an interface the wrapper class
     * can reference (public, or non-public but sharing {@code wrapperPackage}). Returns {@code null}
     * when none is reachable, so the caller falls back to {@code java.lang.Object}. Interface
     * inheritance is acyclic, so the walk terminates regardless; the {@code visited} set just
     * avoids re-expanding a shared parent in the diamonds interfaces routinely exhibit (and stays
     * safe against pathological/cyclic input).
     */
    @Nullable
    private static InterfaceType findReachableInterface(InterfaceType type, String wrapperPackage, Set<String> visited) {
        if (!visited.add(type.name())) {
            return null;
        }
        if (isReachableFromWrapper(type, wrapperPackage)) {
            return type;
        }
        for (InterfaceType parent : type.superinterfaces()) {
            final InterfaceType found = findReachableInterface(parent, wrapperPackage, visited);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Whether {@code type} (a class or interface) can be referenced by name from a wrapper class
     * living in {@code wrapperPackage}. True if the type is public, or if it lives in the same
     * package and is neither {@code private} nor a local/anonymous class. Private nested types are
     * accessible only from inside their enclosing class — even another top-level class in the
     * same package can't reach them — so they walk to a public supertype like any other
     * unreachable case. Local/anonymous classes ({@code $<digit>} in the binary name) are
     * unaddressable from source anywhere.
     */
    private static boolean isReachableFromWrapper(ReferenceType type, String wrapperPackage) {
        if (type.isPublic()) {
            return true;
        }
        if (type.isPrivate()) {
            return false;
        }
        if (!packageOf(type.name()).equals(wrapperPackage)) {
            return false;
        }
        return !isLocalOrAnonymous(type.name());
    }

    /**
     * Whether {@code member} — a field or a method — can be referenced from the generated wrapper
     * class, given the wrapper's package and whether it shares that package with {@code this}'s
     * declared type.
     * <ul>
     *   <li>{@code public} members are always reachable.</li>
     *   <li>When the wrapper does NOT share {@code this}'s package, only public members are
     *       reachable — the wrapper is in the isolated {@code mcp.jdi.evaluation} and cannot see
     *       package-private or protected members of app code.</li>
     *   <li>When the wrapper DOES share {@code this}'s package, non-private members are reachable
     *       only if their declaring type ALSO lives in that package. A protected member inherited
     *       from a class in a different package is accessible only via subclassing, and the
     *       wrapper is not a subclass — so we leave such members un-rewritten to avoid a
     *       misleading "not visible" compile error.</li>
     *   <li>Private members are never rewritten (the wrapper is not the declaring class).</li>
     * </ul>
     */
    private static boolean isMemberAccessibleFromWrapper(TypeComponent member, String wrapperPackage,
                                                         boolean sharingPackage) {
        if (member.isPublic()) {
            return true;
        }
        if (!sharingPackage || member.isPrivate()) {
            return false;
        }
        return packageOf(member.declaringType().name()).equals(wrapperPackage);
    }

    /**
     * Extracts the package portion of a fully qualified binary class name. Returns the empty
     * string for default-package classes. Treats only the last {@code .} before any {@code $} as
     * the package/class separator — inner-class {@code $} segments stay attached to the simple
     * name.
     */
    private static String packageOf(String binaryName) {
        // Stop at the first $ so nested-class binary names like com.example.Outer$Inner still
        // report "com.example" rather than walking into the nested portion.
        final int dollar = binaryName.indexOf('$');
        final String topLevel = dollar < 0 ? binaryName : binaryName.substring(0, dollar);
        final int dot = topLevel.lastIndexOf('.');
        return dot < 0 ? "" : topLevel.substring(0, dot);
    }

    /**
     * Recognises local-class ({@code Outer$1Local}) and anonymous-class ({@code Outer$1}) binary
     * names. The JLS only allows nested classes to be referenced from source by their dotted form,
     * so a binary name where a segment after {@code $} starts with a digit cannot be written as a
     * Java type reference anywhere — not even from the enclosing class's own package.
     */
    private static boolean isLocalOrAnonymous(String binaryName) {
        int dollar = binaryName.indexOf('$');
        while (dollar >= 0 && dollar + 1 < binaryName.length()) {
            if (Character.isDigit(binaryName.charAt(dollar + 1))) {
                return true;
            }
            dollar = binaryName.indexOf('$', dollar + 1);
        }
        return false;
    }

    /**
     * Converts a JDI binary class name to its Java source form by replacing nested-class
     * {@code $} separators with {@code .}. Only rewrites a {@code $} when it is a genuine
     * nested-class separator: preceded AND followed by a non-{@code $} Java identifier part,
     * and not immediately after a {@code .}. Specifically left unchanged:
     * <ul>
     *   <li>Dynamic-proxy names containing {@code $$} (CGLIB / Guice / Mockito) — proxy markers
     *       are not nested separators; rewriting produces nonsense like {@code Bar..Mock}.</li>
     *   <li>JDK dynamic-proxy names like {@code com.sun.proxy.$Proxy12} where {@code $} starts a
     *       simple-name component (preceded by {@code .}); rewriting would yield the invalid
     *       {@code com.sun.proxy..Proxy12}.</li>
     *   <li>Names where {@code $} sits at the start or end of the binary name.</li>
     * </ul>
     * Local and anonymous classes ({@code $<digit>}) must be filtered upstream; this method
     * does not reject them because the same conversion is occasionally useful for diagnostics.
     *
     * <p><b>Known limitation:</b> a {@code $} embedded between identifier characters is treated as a
     * nesting separator even for a <em>top-level</em> type whose simple name genuinely contains
     * {@code $} (e.g. {@code class Foo$Bar {}}), so {@code Foo$Bar} is rendered as {@code Foo.Bar}
     * and the wrapper fails to compile for that type. A binary name alone cannot distinguish the two
     * cases — {@code Outer$Inner} (a member type) and a top-level {@code Foo$Bar} are identical
     * strings — and disambiguating would require a per-type VM round-trip (or compile-then-fallback)
     * disproportionate to the risk: JLS §3.8 reserves {@code $} for "mechanically generated source
     * code", so such top-level names do not occur in normal application code. Accepted as a
     * limitation rather than guarded.
     */
    private static String toSourceTypeName(String binaryName) {
        if (binaryName.indexOf('$') < 0 || binaryName.contains("$$")) {
            return binaryName;
        }
        final int n = binaryName.length();
        final StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            final char c = binaryName.charAt(i);
            if (c == '$' && isNestedClassSeparator(binaryName, i)) {
                sb.append('.');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} when the {@code $} at {@code pos} in {@code name} is a genuine
     * nested-class separator (i.e. the form emitted by {@code javac} for {@code Outer.Inner}
     * compiled to {@code Outer$Inner}) and not, e.g., the leading {@code $} of a JDK dynamic-proxy
     * simple name. Helper for {@link #toSourceTypeName}.
     */
    private static boolean isNestedClassSeparator(String name, int pos) {
        if (pos == 0 || pos == name.length() - 1) {
            return false;
        }
        final char prev = name.charAt(pos - 1);
        final char next = name.charAt(pos + 1);
        // `prev == '.'` covers `com.sun.proxy.$Proxy12` and similar JDK proxy forms.
        // `prev`/`next == '$'` covers adjacent proxy `$$` (the contains-check above already
        // short-circuits the typical case, but stays defensive against future name shapes).
        return prev != '.' && prev != '$' && next != '$'
            && Character.isJavaIdentifierPart(prev)
            && Character.isJavaIdentifierPart(next);
    }

    /**
     * Decides which package the generated wrapper class should live in for {@code thisObject}.
     * Returns {@code thisObject}'s own package when emitting the wrapper there will buy us
     * additional reachability — i.e. {@code this}'s declared type is non-public AND lives in a
     * package we are allowed to define new classes in (not {@code java.*} / {@code javax.*},
     * not a local/anonymous class). Otherwise returns {@link #DEFAULT_EVALUATION_PACKAGE} so the
     * wrapper stays isolated from app code, matching the pre-existing behaviour.
     *
     * <p>Dynamic proxies are unwrapped first so a CGLIB proxy of a package-private service still
     * resolves to the underlying service's package.
     */
    private static String resolveWrapperPackage(@Nullable ObjectReference thisObject) {
        if (thisObject == null || !(thisObject.referenceType() instanceof ClassType startClass)) {
            return DEFAULT_EVALUATION_PACKAGE;
        }
        // Walk proxies to the real class; reuse the same heuristic as getDeclaredType.
        ClassType effective = startClass;
        while (effective != null && effective.name().contains("$$")) {
            final ClassType next = effective.superclass();
            if (next == null || "java.lang.Object".equals(next.name())) {
                break;
            }
            effective = next;
        }
        if (effective == null || effective.isPublic()) {
            return DEFAULT_EVALUATION_PACKAGE;
        }
        final String binaryName = effective.name();
        if (isLocalOrAnonymous(binaryName)) {
            return DEFAULT_EVALUATION_PACKAGE;
        }
        final String pkg = packageOf(binaryName);
        // Refuse to define classes into restricted JDK packages — `java.*` is enforced by the JVM
        // itself, the rest are flagged here so we get a clean log message rather than a runtime
        // SecurityException far from the decision site.
        if (pkg.isEmpty() || pkg.startsWith("java.") || "java".equals(pkg)
            || pkg.startsWith("javax.") || "javax".equals(pkg)
            || pkg.startsWith("sun.") || "sun".equals(pkg)
            || pkg.startsWith("jdk.") || "jdk".equals(pkg)) {
            return DEFAULT_EVALUATION_PACKAGE;
        }
        return pkg;
    }

    /**
     * The class loader for injecting the wrapper class, read from the frame:
     * 1. `frame.thisObject().referenceType().classLoader()` — works for instance methods.
     * 2. `frame.location().declaringType().classLoader()` — works for static methods.
     * <p>
     * {@code null} for a bootstrap-loaded class; {@link #systemClassLoader} is the last resort then.
     */
    @Nullable
    private static ClassLoaderReference frameClassLoader(StackFrame frame) {
        final ObjectReference thisObject = frame.thisObject();
        if (thisObject != null) {
            return thisObject.referenceType().classLoader();
        }
        // Static method — use the declaring type's classloader
        return frame.location().declaringType().classLoader();
    }

    /**
     * Invokes `ClassLoader.getSystemClassLoader()` in the target VM, for a frame of a
     * bootstrap-loaded class ({@link #frameClassLoader} returned null). Runs under the caller's lease.
     * <p>
     * Throws {@link JdiEvaluationException} if it is unavailable too — typically meaning the frame
     * is in a bootstrap-loaded class on a JVM where the system classloader is also unreachable.
     */
    private static ClassLoaderReference systemClassLoader(VirtualMachine vm, ThreadReference thread)
        throws JdiEvaluationException {
        try {
            final List<ReferenceType> clTypes = vm.classesByName("java.lang.ClassLoader");
            if (!clTypes.isEmpty()) {
                final ClassType clType = (ClassType) clTypes.get(0);
                final Method getSystemCL = clType.concreteMethodByName("getSystemClassLoader", "()Ljava/lang/ClassLoader;");
                if (getSystemCL != null) {
                    final Value result = clType.invokeMethod(
                        thread, getSystemCL, Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED
                    );
                    if (result instanceof ClassLoaderReference clRef) {
                        return clRef;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Evaluator] Failed to invoke ClassLoader.getSystemClassLoader() in target VM", e);
        }

        throw new JdiEvaluationException(
            "Could not find a non-null ClassLoader. The frame may be in a bootstrap-loaded class " +
                "and ClassLoader.getSystemClassLoader() was not available."
        );
    }

    /**
     * Evaluates `expression` in the context of `frame` and returns the result as a JDI {@link Value}.
     * Convenience overload — equivalent to {@link #evaluate(StackFrame, String, Map)} with no extra
     * bindings.
     */
    public @Nullable Value evaluate(StackFrame frame, String expression) throws JdiEvaluationException {
        return evaluate(frame, expression, Map.of());
    }

    /**
     * Evaluates `expression` in the context of `frame` plus any caller-supplied synthetic bindings,
     * and returns the result as a JDI {@link Value}. Side effect: populates {@link CompilerState#cache()}.
     *
     * <p>The auto-rewrite of bare field identifiers ({@code field} → {@code _this.field}, via
     * {@link #rewriteThisFieldReferences}) runs when EITHER {@code this}'s declared type is public
     * OR the wrapper is emitted into {@code this}'s own package (see {@link #resolveWrapperPackage}).
     * In the latter case package-private and protected fields also become candidates — subject to
     * {@link #isFieldAccessibleFromWrapper}'s per-field accessibility check. (Explicit {@code this}
     * and {@code this.field} are handled separately and unconditionally by
     * {@link #rewriteThisKeyword}.) The bare-identifier rewrite is SKIPPED entirely in block mode
     * (see {@link #isBlockMode}), where a statement body may declare locals the identifier-level
     * rewriter would mistake for field references.
     *
     * <p>Synthetic bindings (e.g. {@code "$exception" -> ObjectReference}) appear in the wrapper's
     * parameter list after the real locals and can be referenced by name in the expression. This is
     * how {@link one.edee.mcp.jdwp.JdiEventListener} exposes the thrown object on the log path of
     * an exception breakpoint.
     *
     * @param frame         stack frame providing the context (local variables and `this`)
     * @param expression    Java expression to evaluate
     * @param extraBindings additional name → value pairs to expose as synthetic locals; pass
     *                      {@code Map.of()} for none
     * @return the JDI value produced by the user expression (autoboxed to `Object`)
     * @throws JdiEvaluationException wrapping any underlying compilation, classloader, or invocation failure
     */
    public @Nullable Value evaluate(StackFrame frame, String expression, Map<String, Value> extraBindings)
        throws JdiEvaluationException {
        return evaluate(frame, expression, extraBindings, null, false, value -> value);
    }

    /**
     * Returns the reader's copy of an arming result on the dispatcher while the wrapper holds it
     * in the target. The reader must not return a JDI mirror.
     * @throws JdiEvaluationException if evaluation fails, including when classpath discovery is pending
     */
    public <T extends @Nullable Object> T readArmingResult(StackFrame frame, String expression,
                                                           Function<@Nullable Value, T> reader)
        throws JdiEvaluationException {
        return evaluate(frame, expression, Map.of(), null, true, reader);
    }

    /**
     * Evaluates an arming capture under the normal invocation lease, using the probe's receiver
     * binding and source package. Returns the target-rendered value or failure text to the dispatcher.
     * @throws JdiEvaluationException if compiling, invoking, or reading the wrapper fails, including pending discovery
     */
    public String evaluateCapture(StackFrame frame, String expression, String probePackage) throws JdiEvaluationException {
        return evaluate(frame, expression, Map.of(), probePackage, true, value -> {
            if (value instanceof StringReference captured) {
                return captured.value();
            }
            throw new IllegalStateException("the capture wrapper did not return text");
        });
    }

    /**
     * Evaluates against the suspended frame, retrying invalidated frame reads before invocation.
     * Returns the reader's result, keeping retained arming values reachable through that read.
     * @throws JdiEvaluationException if reading the frame, preparing the wrapper, invoking it, or reading the result fails
     */
    private <T extends @Nullable Object> T evaluate(StackFrame frame, String expression, Map<String, Value> extraBindings,
                                                    @Nullable String capturePackage, boolean retainResult,
                                                    Function<@Nullable Value, T> reader) throws JdiEvaluationException {
        // The invocations run under a lease of the thread (see evaluateInPackage), which is the
        // reentrancy guard and, since issue #45, the admission: a thread not stopped at an event
        // of its own is refused with the reason, reported as the evaluation's failure.
        final long startTime = System.currentTimeMillis();
        try {
            // NOTE: Classpath configuration must be done BEFORE calling evaluate() to avoid nested JDI calls
            // The caller (e.g., jdwp_evaluate_watchers) is responsible for calling configureCompilerClasspath()

            // JDI invalidates every StackFrame of a thread the debugger resumes — also a resume that
            // leaves it suspended, such as another set's release or the arming's VM-wide suspend
            // (issue #45). Only the reads before the invocation use the frame, and they change
            // nothing in the target, so they are repeated on the same frame read afresh.
            final ThreadReference thread = frame.thread();
            final Location location = frame.location();
            StackFrame current = frame;
            for (int attempt = 1; ; attempt++) {
                try {
                    return evaluateAt(current, thread, location, expression, extraBindings, capturePackage,
                        retainResult, reader, startTime);
                } catch (InvalidStackFrameException invalidated) {
                    final StackFrame refreshed = attempt < FRAME_READ_ATTEMPTS ? sameFrame(thread, location) : null;
                    if (refreshed == null) {
                        throw invalidated;
                    }
                    current = refreshed;
                }
            }
        } catch (Exception e) {
            log.warn("[Evaluator] Expression evaluation failed after {}ms: {}",
                System.currentTimeMillis() - startTime, e.getMessage());
            // Already a pipeline exception (incl. JdiClassDefinitionException) — propagate it
            // unchanged so the define-vs-invoke subtype and its original message survive rather
            // than being flattened into a generic "Expression evaluation failed" wrapper.
            if (e instanceof JdiEvaluationException jdi) {
                throw jdi;
            }
            // Un-wrap runtime exception from cache computation
            if (e instanceof RuntimeException && e.getCause() instanceof JdiEvaluationException jdiEx) {
                throw jdiEx;
            }
            // Never render "Expression evaluation failed: null". A null-message exception (e.g. a
            // bare AbsentInformationException or NPE) used to surface as a dead-end "null"; fall back
            // to the exception's simple class name so the cause is always identifiable.
            final String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new JdiEvaluationException("Expression evaluation failed: " + cause, e);
        }
    }

    /**
     * {@link #evaluate} on {@code frame}: picks the wrapper package, and retries once in the default
     * package when defining the wrapper into the preferred one is refused.
     */
    private <T extends @Nullable Object> T evaluateAt(StackFrame frame, ThreadReference thread, Location location,
                                                      String expression, Map<String, Value> extraBindings,
                                                      @Nullable String capturePackage, boolean retainResult,
                                                      Function<@Nullable Value, T> reader, long startTime)
        throws JdiEvaluationException, AbsentInformationException {
        // 0. Decide which package the wrapper class will live in. For a non-public `this` in an
        //    addressable package, we emit the wrapper alongside it so package-private fields and
        //    the type itself become reachable; otherwise we use the default isolated package.
        final String wrapperPackage = capturePackage != null ? capturePackage : resolveWrapperPackage(frame.thisObject());
        final boolean renderCapture = capturePackage != null;

        try {
            return evaluateInPackage(frame, expression, extraBindings, wrapperPackage, renderCapture, retainResult,
                reader, startTime);
        } catch (JdiClassDefinitionException defineFailure) {
            // The wrapper could not be DEFINED into the chosen package — a sealed package, module
            // strong-encapsulation, or a restrictive custom classloader can reject defineClass for
            // an application package while still accepting the isolated default package. The user
            // expression never ran (the failure is in the define phase, before invoke), so it is
            // safe to retry once in the default package. This preserves the pre-target-package
            // behaviour for public-only expressions instead of regressing them to a hard failure.
            if (wrapperPackage.equals(DEFAULT_EVALUATION_PACKAGE)) {
                throw defineFailure;
            }
            log.warn("[Evaluator] Defining wrapper into package '{}' failed ({}); retrying in default package '{}'",
                wrapperPackage, defineFailure.getMessage(), DEFAULT_EVALUATION_PACKAGE);
            // The defineClass invocation invalidated the frame: read it afresh.
            final StackFrame refreshed = sameFrame(thread, location);
            if (refreshed == null) {
                throw defineFailure;
            }
            return evaluateInPackage(refreshed, expression, extraBindings, DEFAULT_EVALUATION_PACKAGE, renderCapture,
                retainResult, reader, startTime);
        }
    }

    /**
     * The frame of {@code thread} at {@code location}, read afresh; {@code null} when the thread no
     * longer has exactly one frame there — it ran on, or recursion makes the frame ambiguous.
     */
    @Nullable
    private static StackFrame sameFrame(ThreadReference thread, Location location) {
        try {
            StackFrame match = null;
            for (StackFrame candidate : thread.frames()) {
                if (candidate.location().equals(location)) {
                    if (match != null) {
                        return null;
                    }
                    match = candidate;
                }
            }
            return match;
        } catch (IncompatibleThreadStateException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Builds the evaluation context, compiles (or reuses a cached) wrapper class living in
     * {@code wrapperPackage}, defines it into the target VM, and invokes it. Factored out of
     * {@link #evaluate} so that method can retry in {@link #DEFAULT_EVALUATION_PACKAGE} when a
     * non-default package is rejected at define time — see {@link JdiClassDefinitionException}.
     * <p>
     * The {@code this.field} rewrite is performed here (not in the caller) because which fields are
     * reachable from the wrapper depends on {@code wrapperPackage}; a retry in a different package
     * must redo it from the original expression. {@code expression} is the local parameter, so the
     * reassignment never leaks back to {@link #evaluate}.
     */
    private <T extends @Nullable Object> T evaluateInPackage(StackFrame frame, String expression,
                                                            Map<String, Value> extraBindings, String wrapperPackage,
                                                            boolean renderCapture, boolean retainResult,
                                                            Function<@Nullable Value, T> reader, long startTime)
        throws JdiEvaluationException, AbsentInformationException {
        // 1. Analyze the frame to build the evaluation context
        final ReferenceType receiverType = evaluationReceiverType(frame, renderCapture);
        final EvaluationContext context = buildContext(frame, extraBindings, wrapperPackage, receiverType);

        // Auto-rewrite bare references to fields of `this` so users can write
        // `sessions.containsKey(session)` instead of `_this.sessions.containsKey(session)`.
        // SKIPPED in block mode — the rewriter is identifier-level and cannot distinguish
        // a field reference from a local-variable declaration. Rewriting a statement-body
        // input like `int count = 1; ...` would corrupt the declaration into
        // `int _this.count = 1; ...`. Block-mode users are expected to use explicit
        // `this.field` / `_this.field` references (the keyword rewrite in `generateSourceCode`
        // still handles `this.field` → `_this.field`).
        // Each candidate field is filtered for accessibility from the wrapper class:
        //  - When the wrapper lives in the default isolated package, only public fields on a
        //    public declaring type are reachable.
        //  - When the wrapper lives in `this`'s own package, public/protected/package-private
        //    fields are reachable PROVIDED the field's declaring type ALSO lives in that
        //    package. A protected field inherited from a class in some other package is not
        //    accessible from a non-subclass even when `this`'s runtime type is in the wrapper
        //    package — so we'd produce a misleading "not visible" error if we rewrote it.
        //  - Private fields are never rewritten.
        if (receiverType instanceof ClassType thisClass) {
            final Set<String> shadowingLocals = context.getVariables().stream()
                .map(v -> v.name)
                .collect(Collectors.toSet());
            expression = rewriteForReceiver(expression, thisClass, wrapperPackage, shadowingLocals,
                context.getVariables().get(0).name);
        }

        // 2. Cache key includes the wrapper package: the same (signature, expression) may be
        //    compiled into two different packages (e.g. after a define-time fallback), and the
        //    cached className + bytecode are package-specific.
        final String cacheKey = wrapperPackage + "###" + renderCapture + "###" + retainResult
            + "###" + context.getSignature() + "###" + expression;

        final CompilerState state = compilerStateFor(frame.virtualMachine());
        final Map<String, CachedCompilation> cache = state.cache();
        // Full-flush eviction is deliberate: LRU bookkeeping isn't worth it for a cache whose
        // miss cost (compile + cache) is already orders of magnitude larger than just rebuilding
        // the few entries that get hot again.
        if (cache.size() >= MAX_CACHE_SIZE) {
            log.info("[Evaluator] Compilation cache reached {} entries, clearing", cache.size());
            cache.clear();
        }

        final CachedCompilation cached = cache.get(cacheKey);

        final String className;
        final byte[] bytecode;

        if (cached != null) {
            // Cache hit — reuse previously compiled class name and bytecode
            className = cached.className;
            bytecode = cached.bytecode;
        } else {
            // Cache miss — generate unique class name, compile, and cache
            final String uniqueId = UUID.randomUUID().toString().replace("-", "");
            className = wrapperPackage + '.' + EVALUATION_CLASS_PREFIX + uniqueId;

            final String sourceCode = generateSourceCode(className, context, expression, renderCapture, retainResult);

            final Compilation compilation = compileWithClasspathContext(className, sourceCode, state);
            bytecode = findCompiledBytecode(compilation.classes(), className);
            if (compilation.cacheable()) {
                cache.put(cacheKey, new CachedCompilation(className, bytecode));
            }
        }

        // 3. Everything else the invocation needs from the frame is read before the lease: taking
        //    the thread out of another stop resumes it (issue #45), and JDI invalidates every
        //    StackFrame of a thread it resumes.
        final ThreadReference thread = frame.thread();
        final VirtualMachine vm = frame.virtualMachine();
        final ClassLoaderReference frameLoader = frameClassLoader(frame);

        // 4. Execute the code remotely under a lease: the reentrancy guard — the listener suppresses
        //    a breakpoint / exception event the invoked code raises on this thread — and the
        //    admission. A define-phase rejection surfaces as JdiClassDefinitionException, which the
        //    caller may catch to retry in the default package.
        final Function<@Nullable Value, T> readAndReport = value -> {
            log.info("[Evaluator] Expression evaluated in {}ms (cache {})",
                System.currentTimeMillis() - startTime, cached != null ? "hit" : "miss");
            return reader.apply(value);
        };
        final Value value;
        try (EvaluationGuard.Lease lease = evaluationGuard.lease(thread)) {
            final ClassLoaderReference classLoader = frameLoader != null ? frameLoader : systemClassLoader(vm, thread);
            if (retainResult) {
                return RemoteCodeExecutor.execute(vm, thread, classLoader, className, bytecode, EVALUATION_METHOD_NAME,
                    context.getValues(), true, readAndReport);
            }
            value = RemoteCodeExecutor.execute(vm, thread, classLoader, className, bytecode, EVALUATION_METHOD_NAME,
                context.getValues());
        }
        return readAndReport.apply(value);
    }

    /**
     * The type the wrapper binds as {@code _this}: the receiver's runtime type for an ordinary
     * evaluation; for an arming capture the method's declaring type, as the installed probe binds it,
     * and none for static methods and constructors. {@code null} when there is no receiver.
     */
    @Nullable
    private static ReferenceType evaluationReceiverType(StackFrame frame, boolean renderCapture) {
        if (renderCapture) {
            final Method method = frame.location().method();
            // Match the installed probe, including constructor lines before this is initialized.
            return method.isStatic() || method.isConstructor() ? null : method.declaringType();
        }
        final ObjectReference thisObject = frame.thisObject();
        return thisObject == null ? null : thisObject.referenceType();
    }

    /**
     * Returns the expression with the selected receiver binding. Outside block mode it also qualifies
     * reachable bare fields and method calls, subject to the wrapper package's access rules.
     */
    private static String rewriteForReceiver(String expression, ClassType thisClass, String wrapperPackage,
                                             Set<String> shadowingLocals, String receiver) {
        if (!"_this".equals(receiver)) {
            expression = rewriteThisKeyword(expression, receiver);
        }
        if (isBlockMode(expression)) {
            return expression;
        }
        final boolean sharingPackage = !wrapperPackage.equals(DEFAULT_EVALUATION_PACKAGE)
            && wrapperPackage.equals(packageOf(thisClass.name()));
        if (!thisClass.isPublic() && !sharingPackage) {
            return expression;
        }
        final Set<String> rewritableFieldNames = thisClass.allFields().stream()
            .filter(f -> isMemberAccessibleFromWrapper(f, wrapperPackage, sharingPackage))
            .map(Field::name)
            .collect(Collectors.toSet());
        final Set<String> rewritableMethodNames = thisClass.allMethods().stream()
            .filter(m -> !m.isConstructor() && !m.isStaticInitializer() && !m.isSynthetic())
            .filter(m -> isMemberAccessibleFromWrapper(m, wrapperPackage, sharingPackage))
            .map(Method::name)
            .collect(Collectors.toSet());
        return rewriteThisMemberReferences(expression, rewritableFieldNames, rewritableMethodNames, shadowingLocals,
            receiver);
    }

    /**
     * Compiles the in-target probe class of a fast logpoint ({@code jdwp_set_fast_logpoint}) for
     * the location {@code frame} is stopped at. The probe's static {@code hit} method takes the
     * receiver ({@code _this}, when {@code includeThis}) and the locals visible at the frame's
     * location as parameters — the same context an evaluation wrapper gets — and evaluates the
     * optional condition and the expression inside the target VM (see
     * {@link FastLogpointProbeSource}).
     * <p>
     * Unlike an evaluation wrapper, {@code _this} is typed from the method's <em>declaring</em>
     * class, not the receiver's runtime class: the instrumented call site passes {@code this} with
     * the declaring class as its static type, and the verifier rejects passing it where a subclass
     * is expected. Nothing is cached — every probe gets a fresh UUID-suffixed class name.
     * <p>
     * Called by the dispatcher with an event-suspended frame after {@link #configureCompilerClasspathForEvent}.
     *
     * @param frame          the frame stopped at the logpoint's location
     * @param includeThis    whether {@code this} is passed to the probe (false in static methods
     *                       and constructors, where {@code this} may still be uninitialized)
     * @param wrapperPackage package the probe class is generated into
     * @param bufferSize     ring-buffer capacity; must be a power of two
     * @param metadata       what the probe records about itself (target, user text, arming session)
     * @return the probe's class name, bytecode, and {@code hit} parameter names in declaration order
     * @throws JdiEvaluationException when the probe does not compile (including the compiler's
     *                                diagnostics) or the frame's context cannot be read
     */
    public FastLogpointProbe compileFastLogpointProbe(StackFrame frame, boolean includeThis, String wrapperPackage,
                                                      String expression, @Nullable String condition, int bufferSize,
                                                      FastLogpointProbeSource.Metadata metadata)
        throws JdiEvaluationException {
        final List<FastLogpointProbeSource.Parameter> parameters = probeParameters(frame, includeThis, wrapperPackage);
        final String effectiveExpression = rewriteForProbe(expression, frame, includeThis, wrapperPackage, parameters);
        final String effectiveCondition = condition != null
            ? rewriteForProbe(condition, frame, includeThis, wrapperPackage, parameters)
            : null;
        final String className = probeClassName(wrapperPackage, FastLogpointProbeSource.CLASS_PREFIX);
        return compileProbe(className, parameters, compilerStateFor(frame.virtualMachine()), FastLogpointProbeSource.generate(
            className, parameters, effectiveExpression, effectiveCondition, bufferSize, metadata));
    }

    /**
     * Compiles the in-target probe class of a fast breakpoint ({@code jdwp_set_fast_breakpoint}) for
     * the location {@code frame} is stopped at: the same {@code hit} parameters, {@code _this}
     * typing and naming as {@link #compileFastLogpointProbe}, with the condition evaluated inside
     * the target VM and a {@code stop} method for the server's breakpoint (see
     * {@link FastBreakpointProbeSource}). Both attempts use the same VM's compiler state. A permanent
     * capture compilation failure is recorded in the probe if compiling the condition alone succeeds.
     * <p>
     * Called by the dispatcher with an event-suspended frame after {@link #configureCompilerClasspathForEvent}.
     *
     * @param frame          the frame stopped at the breakpoint's location
     * @param includeThis    whether {@code this} is passed to the probe
     * @param wrapperPackage package the probe class is generated into
     * @param condition      the condition, as the user entered it
     * @param capture        the optional capture expression, as the user entered it
     * @param stopOnce       whether only the first true hit of an epoch stops
     * @param bufferSize     capacity of the condition-failure ring; must be a power of two
     * @param metadata       what the probe records about itself (target, user text, arming session)
     * @return the probe's class name, bytecode, and {@code hit} parameter names in declaration order
     * @throws JdiEvaluationException when the probe does not compile or the frame's context cannot be read
     */
    public FastLogpointProbe compileFastBreakpointProbe(StackFrame frame, boolean includeThis, String wrapperPackage,
                                                        String condition, @Nullable String capture, boolean stopOnce, int bufferSize,
                                                        FastLogpointProbeSource.Metadata metadata)
        throws JdiEvaluationException {
        final List<FastLogpointProbeSource.Parameter> parameters = probeParameters(frame, includeThis, wrapperPackage);
        final String effectiveCondition = rewriteForProbe(condition, frame, includeThis, wrapperPackage, parameters);
        final String effectiveCapture = capture == null ? null
            : rewriteForProbe(capture, frame, includeThis, wrapperPackage, parameters);
        final String className = probeClassName(wrapperPackage, FastBreakpointProbeSource.CLASS_PREFIX);
        final CompilerState state = compilerStateFor(frame.virtualMachine());
        try {
            return compileProbe(className, parameters, state, FastBreakpointProbeSource.generate(
                className, parameters, effectiveCondition, effectiveCapture, stopOnce, bufferSize, metadata));
        } catch (LocalClasspathPendingException pending) {
            throw pending;
        } catch (JdiEvaluationException failure) {
            if (effectiveCapture == null) {
                throw failure;
            }
            // A successful compile without the expression isolates the failure to the capture.
            final String reason = Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
            return compileProbe(className, parameters, state, FastBreakpointProbeSource.generate(
                className, parameters, effectiveCondition, null, reason, stopOnce, bufferSize, metadata));
        }
    }

    /**
     * The {@code hit} parameters of a probe at {@code frame}'s location: {@code _this} (typed from the
     * declaring class) when {@code includeThis}, then every visible local except the outer-instance
     * {@code this$N}.
     */
    private List<FastLogpointProbeSource.Parameter> probeParameters(StackFrame frame, boolean includeThis,
                                                                    String wrapperPackage) {
        final List<FastLogpointProbeSource.Parameter> parameters = new ArrayList<>();
        try {
            for (LocalVariable var : frame.visibleVariables()) {
                // Same `this$N` filter as buildContext — the outer-instance reference is unaddressable.
                if (var.isArgument() || !var.name().startsWith("this$")) {
                    parameters.add(new FastLogpointProbeSource.Parameter(var.name(), resolveLocalVarType(var, wrapperPackage)));
                }
            }
        } catch (AbsentInformationException noLocals) {
            log.debug("[Evaluator] No local-variable table at the probe location — probe receives `this` only.");
        }
        if (includeThis) {
            final String receiver = availableReceiverName(parameters.stream().map(FastLogpointProbeSource.Parameter::name)
                .toList());
            parameters.add(0, new FastLogpointProbeSource.Parameter(receiver,
                getDeclaredType(frame.location().declaringType(), wrapperPackage)));
        }
        return parameters;
    }

    /**
     * Rewrites the bare {@code this} fields in a probe's user text to {@code _this.field}, as an
     * evaluation wrapper does; unchanged when {@code this} is not passed.
     */
    private String rewriteForProbe(String text, StackFrame frame, boolean includeThis, String wrapperPackage,
                                   List<FastLogpointProbeSource.Parameter> parameters) {
        if (!includeThis || !(frame.location().declaringType() instanceof ClassType thisClass)) {
            return text;
        }
        final Set<String> localNames = parameters.stream()
            .map(FastLogpointProbeSource.Parameter::name)
            .collect(Collectors.toSet());
        return rewriteForReceiver(text, thisClass, wrapperPackage, localNames, parameters.get(0).name());
    }

    private static String probeClassName(String wrapperPackage, String classPrefix) {
        final String uniqueId = UUID.randomUUID().toString().replace("-", "");
        return wrapperPackage.isEmpty() ? classPrefix + uniqueId : wrapperPackage + '.' + classPrefix + uniqueId;
    }

    /**
     * Compiles the dispatcher probe with discovery context and requires exactly one class.
     */
    private FastLogpointProbe compileProbe(String className, List<FastLogpointProbeSource.Parameter> parameters,
                                           CompilerState state, String sourceCode) throws JdiEvaluationException {
        final Map<String, byte[]> compiledCode = compileWithClasspathContext(className, sourceCode, state).classes();
        if (compiledCode.size() > 1) {
            // The probe is defined as a single class; a nested/anonymous class in the user code
            // would compile to a second class file that is never defined in the target.
            throw new JdiEvaluationException("Fast logpoint and fast breakpoint expressions and conditions cannot "
                + "declare anonymous or nested classes (compiled to " + compiledCode.size() + " classes)");
        }
        final byte[] bytecode = findCompiledBytecode(compiledCode, className);
        return new FastLogpointProbe(className, bytecode,
            parameters.stream().map(FastLogpointProbeSource.Parameter::name).toList());
    }

    /**
     * Picks {@code className}'s bytecode out of the compiler's output map. Some compilers key by
     * binary name with slashes, simple name, or with leading slashes — falls back to a suffix
     * match on the simple class name.
     */
    private static byte[] findCompiledBytecode(Map<String, byte[]> compiledCode, String className)
        throws JdiEvaluationException {
        final byte[] direct = compiledCode.get(className);
        if (direct != null) {
            return direct;
        }
        final String simpleName = className.substring(className.lastIndexOf('.') + 1);
        for (Map.Entry<String, byte[]> entry : compiledCode.entrySet()) {
            final String key = entry.getKey();
            String keyTail = key.substring(key.lastIndexOf('/') + 1).replace(".class", "");
            keyTail = keyTail.substring(keyTail.lastIndexOf('.') + 1);
            if (keyTail.equals(simpleName)) {
                log.debug("[Evaluator] Bytecode found via fallback key '{}' for class '{}'", key, className);
                return entry.getValue();
            }
        }
        throw new JdiEvaluationException("Could not find compiled bytecode for class " + className
            + " (available keys: " + compiledCode.keySet() + ')');
    }

    /** Starts an independent local scan for the newly attached connection. */
    public void startLocalClasspathDiscovery(VirtualMachine vm) {
        localClasspathProvider.startForConnection(vm);
    }

    /** Cancels work for this VM without joining the discovery daemon. */
    public void stopLocalClasspathDiscovery(VirtualMachine vm) {
        localClasspathProvider.stopForConnection(vm);
    }

    /** Lets an arming request skip recompilation while it is waiting for local types. */
    public boolean localClasspathDiscoveryRunning() {
        return localClasspathProvider.discoveryStatus().running();
    }

    /**
     * Configures the compiler for an MCP tool evaluation. The tool's invocation worker can wait
     * up to the Maven timeout for the current project's background discovery. The wait happens
     * before entering the configuration monitor so the event dispatcher can keep evaluating.
     * Merges a completed local result with the remote classpath when available, and reuses the
     * existing configuration if it already contains that result for the current connection.
     *
     * @param suspendedThread a target thread already suspended at a breakpoint or other event
     * @throws JdiEvaluationException if the matching JDK or a usable classpath is unavailable.
     *     The message identifies the missing input, includes local discovery status when relevant,
     *     and points to diagnostics the user can check.
     */
    public void configureCompilerClasspath(ThreadReference suspendedThread) throws JdiEvaluationException {
        final VirtualMachine vm = suspendedThread.virtualMachine();
        localClasspathProvider.awaitDiscovery(vm);
        configureCompilerClasspath(suspendedThread, vm, true);
    }

    /**
     * Configures the compiler on the event dispatcher for a breakpoint condition, logpoint
     * expression, or fast probe arming. Merges a completed local result if available. While local
     * discovery runs, uses the remote classpath so the dispatcher can keep processing events.
     * A later evaluation can merge the result as soon as it is ready.
     *
     * @param suspendedThread a target thread already suspended at a breakpoint or other event
     * @throws JdiEvaluationException if the matching JDK or a usable classpath is unavailable.
     *     The event handler reports the actionable message, including pending local discovery or
     *     a discovery failure when relevant, as an evaluation error such as {@code LOGPOINT_ERROR}.
     */
    public void configureCompilerClasspathForEvent(ThreadReference suspendedThread) throws JdiEvaluationException {
        configureCompilerClasspath(suspendedThread, suspendedThread.virtualMachine(), true);
    }

    /**
     * Shared configuration for tool evaluations, dispatcher evaluations, and
     * {@link #prewarmClasspath}. Evaluations pass {@code includeLocal=true} to merge a ready local
     * result or reuse the current remote-only configuration while discovery is pending.
     * Prewarm passes {@code false} because the first suspending stop needs to warm only the
     * remote classloader walk and JDK detection. Discovery started independently at attach.
     * Publication retries with the finished local result if it arrived during the remote walk.
     *
     * @param suspendedThread an event-suspended target thread for JDI method invocations
     * @param vm the connection that owns the suspended thread
     * @param includeLocal whether to merge an available local result
     * @throws JdiEvaluationException if the matching JDK or a usable classpath is unavailable
     */
    private synchronized void configureCompilerClasspath(ThreadReference suspendedThread,
                                                         @Nullable VirtualMachine vm, boolean includeLocal)
        throws JdiEvaluationException {
        final Snapshot first = requireClasspathSnapshot(vm, includeLocal);
        final boolean jdkReady = jdiConnectionService.getDiscoveredJdkPath() != null;
        final CompilerState configured = compilerState;
        final Snapshot previous = configured.snapshot();
        if (jdkReady && configured.vm() == vm && previous != null && previous.sameInput(first, includeLocal)
            && (!includeLocal || configured.mergedLocal() == first.status().breakdown())
            && localClasspathProvider.publishIfCurrent(vm, first, includeLocal, () -> {})) {
            return;
        }

        // The lease lets the remote classloader walk borrow the target thread's suspension for
        // invokeMethod. The EvaluationGuard tells the listener to suppress any breakpoint the
        // call hits. Suspending the thread again there would prevent the invocation from finishing.
        try (EvaluationGuard.Lease lease = evaluationGuard.lease(suspendedThread)) {
            // An absent JDK path marks first use, a fresh connection, or a failed discovery.
            // Reset before trying again so a failure cannot leave the previous target's options
            // or wrappers available. Once the JDK is known, preserve the remote discovery while
            // updating the local options. Successful reconfiguration replaces the wrapper cache.
            if (!jdkReady) {
                compiler.reset();
                compilerState = new CompilerState(null, null, null, false, null, new ConcurrentHashMap<>());
            }

            final String remoteClasspath = jdiConnectionService.discoverClasspath(suspendedThread);
            final String jdkPath = jdiConnectionService.getDiscoveredJdkPath();
            Snapshot snapshot = first;
            // Directory reports and local results can arrive during the remote walk or while
            // assembling options. Retry with a fresh snapshot without repeating either discovery.
            for (int attempt = 0; attempt < 3; attempt++) {
                final DiscoveryStatus localStatus = snapshot.status();
                final LocalProjectClasspathProvider.Breakdown local = includeLocal ? localStatus.breakdown() : null;
                final String problem = localStatus.problem();
                final String localHint = problem == null ? "" : " " + problem;
                // ECJ resolves entries left to right. Put the live target's entries first so they
                // win over stale local copies. Append local-only entries to fill classloader gaps.
                final Set<String> localEntries = local == null ? Set.of() : local.all();
                final String mergedClasspath = mergeClasspaths(remoteClasspath, localEntries);
                final boolean localEntriesAdded = !localEntries.isEmpty()
                    && !mergedClasspath.equals(mergeClasspaths(remoteClasspath, Set.of()));
                final int version = jdiConnectionService.getTargetMajorVersion();
                final Snapshot candidate = snapshot;
                if (localClasspathProvider.publishIfCurrent(vm, candidate, includeLocal, () -> {
                    if (jdkPath != null && !mergedClasspath.isEmpty()) {
                        compiler.configure(jdkPath, mergedClasspath, version);
                        compilerState = new CompilerState(vm, candidate, local, localEntriesAdded,
                            compiler.currentConfiguration(), new ConcurrentHashMap<>());
                    }
                })) {
                    // Remote discovery signals unavailable inputs by leaving the JDK or classpath
                    // empty. Report that here with actionable context before trying to compile.
                    if (jdkPath == null || mergedClasspath.isEmpty()) {
                        throw classpathFailure(jdkPath, localStatus, localHint, includeLocal);
                    }
                    // The first two counts show each input before merging. The third is the
                    // deduplicated union passed to ECJ. Overlap makes it smaller than the sum.
                    log.info("[LocalClasspath] Compiler classpath: {} remote + {} local entries → {} configured{}",
                        countEntries(remoteClasspath), localEntries.size(), countEntries(mergedClasspath), localHint);
                    return;
                }
                snapshot = requireClasspathSnapshot(vm, includeLocal);
            }
            throw new JdiEvaluationException("The project directory changed during classpath configuration. Retry the evaluation.");
        }
    }

    /**
     * Gets the current discovery snapshot for compiler configuration on a tool worker or the
     * dispatcher without waiting. Throws {@link JdiEvaluationException} if the VM no longer owns it.
     */
    private Snapshot requireClasspathSnapshot(@Nullable VirtualMachine vm, boolean includeLocal)
        throws JdiEvaluationException {
        final Snapshot snapshot = localClasspathProvider.snapshotFor(vm, includeLocal);
        if (snapshot == null) {
            throw new JdiEvaluationException("The target connection ended during classpath configuration.");
        }
        return snapshot;
    }

    /**
     * Builds the configuration failure for a missing JDK or empty classpath. Returns a
     * {@link LocalClasspathPendingException} when an empty classpath may recover after discovery.
     */
    private static JdiEvaluationException classpathFailure(@Nullable String jdkPath, DiscoveryStatus status,
                                                             String localHint, boolean includeLocal) {
        final String prefix = "Classpath discovery failed for the current connection: ";
        if (jdkPath == null) {
            return new JdiEvaluationException(prefix + "no local JDK matching the target VM could be located, "
                + "so application types cannot be resolved." + localHint
                + " Check the server log (search '[JDI]') for the underlying cause.");
        }
        final String reason = !includeLocal || status.breakdown() == null
            ? "the target VM classloader hierarchy yielded no classpath entries and no local result is available."
            : "neither the target VM classloader hierarchy nor the local project yielded any classpath entries.";
        final String message = prefix + reason + localHint + " Local project directory: '" + status.directory() + "' ("
            + (status.reportedByClient() ? "the session directory the MCP client reported"
                : "the directory the MCP server was launched from") + "). "
            + "Run jdwp_diagnose to check the discovery status. To supply missing entries, start the "
            + "agent session in the Maven project or set the JDWP_EXTRA_CLASSPATH environment variable.";
        return includeLocal && status.running()
            ? new LocalClasspathPendingException(message, null) : new JdiEvaluationException(message);
    }

    /**
     * Captures the options and cache for a wrapper or probe compilation. Throws
     * {@link JdiEvaluationException} if the published state belongs to a different VM.
     */
    private CompilerState compilerStateFor(@Nullable VirtualMachine vm) throws JdiEvaluationException {
        final CompilerState state = compilerState;
        if (state.vm() != vm && state.vm() != null) {
            throw new JdiEvaluationException("The target connection changed before compilation.");
        }
        return state;
    }

    /**
     * Compiles on the dispatcher or tool worker. After a remote-only missing-type error, reads
     * the current workspace's scan without waiting. Retries once if ready, or throws a pending
     * exception if discovery is still running or the retry's classpath is superseded.
     */
    private Compilation compileWithClasspathContext(String className, String sourceCode, CompilerState state)
        throws JdiEvaluationException {
        final InMemoryJavaCompiler.Configuration options = state.options();
        try {
            final Map<String, byte[]> classes = options != null ? compiler.compile(className, sourceCode, options)
                : compiler.compile(className, sourceCode);
            return new Compilation(classes, true);
        } catch (JdiEvaluationException failure) {
            final Snapshot captured = state.snapshot();
            if (captured == null) {
                throw failure;
            }
            final boolean missingFromRemote = !state.localEntriesAdded()
                && failure instanceof InMemoryJavaCompiler.MissingClasspathTypes;
            // A workspace reported during ECJ may not have a scan yet. Starting it here
            // lets this evaluation defer against the replacement without waiting for Maven.
            final Snapshot current = localClasspathProvider.snapshotFor(state.vm(), missingFromRemote);
            if (current == null || (!missingFromRemote && !captured.sameInput(current, false))) {
                throw failure;
            }
            final DiscoveryStatus status = current.status();
            if (missingFromRemote) {
                if (status.running()) {
                    throw new LocalClasspathPendingException(failure.getMessage()
                        + "\nLocal classpath discovery is still running. See jdwp_diagnose.", failure);
                }
                final LocalProjectClasspathProvider.Breakdown ready = status.breakdown();
                if (ready != null && options != null) {
                    return retryWithLocalClasspath(className, sourceCode, state.vm(), options, current, ready);
                }
            }
            throw withDiscoveryProblem(failure, status);
        }
    }

    /** Retries once with local entries and accepts the result only while its snapshot is current. */
    private Compilation retryWithLocalClasspath(String className, String sourceCode, @Nullable VirtualMachine vm,
                                               InMemoryJavaCompiler.Configuration remote, Snapshot current,
                                               LocalProjectClasspathProvider.Breakdown ready)
        throws JdiEvaluationException {
        final InMemoryJavaCompiler.Configuration merged = new InMemoryJavaCompiler.Configuration(
            remote.jdkPath(), mergeClasspaths(remote.classpath(), ready.all()), remote.targetMajorVersion());
        requireCurrentRetryClasspath(vm, current);
        final Map<String, byte[]> classes;
        try {
            classes = compiler.compile(className, sourceCode, merged);
        } catch (JdiEvaluationException failure) {
            requireCurrentRetryClasspath(vm, current);
            throw withDiscoveryProblem(failure, current.status());
        }
        // The retry does not populate the cache tied to the captured remote-only options.
        requireCurrentRetryClasspath(vm, current);
        return new Compilation(classes, false);
    }

    /** Rejects superseded options or results without waiting on discovery or invoking the target. */
    private void requireCurrentRetryClasspath(@Nullable VirtualMachine vm, Snapshot snapshot)
        throws LocalClasspathPendingException {
        if (!localClasspathProvider.publishIfCurrent(vm, snapshot, true, () -> {})) {
            throw new LocalClasspathPendingException(
                "The local classpath changed during compilation. Retry with the current project classpath.", null);
        }
    }

    /** Preserves ECJ's failure and adds a diagnostic only when discovery has one. */
    private static JdiEvaluationException withDiscoveryProblem(JdiEvaluationException failure, DiscoveryStatus status) {
        final String problem = status.problem();
        return problem == null ? failure : new JdiEvaluationException(failure.getMessage() + "\n" + problem, failure);
    }

    /**
     * Best-effort pre-warm of the compiler classpath at the first thread suspension, so the agent's
     * first {@code evaluate_expression} (or the first logpoint/condition hit) does not pay the
     * one-time remote classpath discovery cost — which can take 1-3s while it walks the target VM's
     * classloader hierarchy via {@code invokeMethod} — on the critical path. Discovery is paid once
     * per connection; subsequent calls short-circuit on the cached JDK path.
     * <p>
     * Warms only remote discovery and JDK detection. The local scan starts independently at
     * attach and is merged by evaluation when ready.
     * <p>
     * Unlike {@link #configureCompilerClasspath}, this NEVER throws: it is invoked from the JDI
     * event dispatcher while a thread is parked for inspection, and a warming failure must not disrupt
     * the breakpoint flow. Any failure is logged at debug and left for the real evaluation call to
     * re-attempt and surface with a precise error.
     *
     * @param suspendedThread a thread already suspended at a breakpoint/step/exception/watchpoint
     */
    public void prewarmClasspath(ThreadReference suspendedThread) {
        if (jdiConnectionService.getDiscoveredJdkPath() != null) {
            return;
        }
        try {
            configureCompilerClasspath(suspendedThread, suspendedThread.virtualMachine(), false);
        } catch (Exception e) {
            log.debug("[Evaluator] Classpath pre-warm failed (will retry on first evaluation): {}",
                e.getMessage());
        }
    }

    /**
     * Unions the remote-discovered classpath (target VM) with the local-project entries, deduping
     * while preserving insertion order so remote entries appear first. Putting remote entries first
     * ensures JDT binds against the live target's definition when the same class also appears in a
     * (possibly stale) local entry.
     *
     * <p>Splits the remote string via {@link #splitRemoteClasspath} so a single-entry Windows path
     * like {@code "C:\foo"} stays intact rather than being shredded on the colon.
     *
     * @param remote remote-discovered classpath joined by the target VM's path separator;
     *               {@code null}, empty, and blank are tolerated
     * @param local  local-project entries in insertion order (typically from
     *               {@link LocalProjectClasspathProvider#discover()})
     * @return single string joined by the host {@link File#pathSeparator}, suitable to hand directly
     *         to the in-memory compiler's {@code -classpath} argument
     */
    private static String mergeClasspaths(@Nullable String remote, Set<String> local) {
        final Set<String> union = new LinkedHashSet<>();
        if (remote != null && !remote.isEmpty()) {
            for (String entry : splitRemoteClasspath(remote)) {
                final String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    union.add(trimmed);
                }
            }
        }
        union.addAll(local);
        return String.join(File.pathSeparator, union);
    }

    /**
     * Counts the non-blank entries in a classpath string, reusing {@link #splitRemoteClasspath}'s
     * separator heuristic. Used purely for the INFO log line summarising the merge result — the
     * value never drives control flow.
     *
     * <p>Caveat: {@code mergeClasspaths} always joins with the host {@link File#pathSeparator},
     * whereas this re-detects the separator from content. In the same-OS case (the only one that
     * actually compiles — a Windows target's paths don't resolve on a Unix host and vice-versa)
     * the two agree. In a hypothetical cross-OS mix the logged count can under-report; that is an
     * accepted cosmetic imprecision, not a correctness issue.
     */
    private static int countEntries(@Nullable String classpath) {
        if (classpath == null || classpath.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String e : splitRemoteClasspath(classpath)) {
            if (!e.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Splits a remote classpath into entries. Three cases:
     * <ol>
     *   <li>Contains {@code ;} — Windows-style classpath, split on {@code ;}.</li>
     *   <li>Looks like a single Windows path ({@code <letter>:\...}) — treat as one entry.</li>
     *   <li>Otherwise — Unix-style classpath, split on {@code :}.</li>
     * </ol>
     */
    private static String[] splitRemoteClasspath(String classpath) {
        if (classpath.contains(";")) {
            return classpath.split(";", -1);
        }
        if (looksLikeSingleWindowsPath(classpath)) {
            return new String[] { classpath };
        }
        return classpath.split(":", -1);
    }

    /**
     * Heuristic: {@code true} when {@code value} starts with {@code <letter>:\} or {@code <letter>:/},
     * indicating a single Windows path that must not be split on {@code :}. Multi-entry Windows
     * classpaths contain {@code ;} and are filtered by the caller before this check runs.
     *
     * <p><b>Limitation.</b> A Unix-style classpath whose first entry happens to look like a Windows
     * drive prefix (vanishingly unlikely in practice) would be misclassified as a single entry. This
     * tradeoff is accepted because the alternative — host-OS sniffing — fails when the MCP server
     * and target VM run on different operating systems.
     */
    private static boolean looksLikeSingleWindowsPath(String value) {
        return value.length() >= 3
            && Character.isLetter(value.charAt(0))
            && value.charAt(1) == ':'
            && (value.charAt(2) == '\\' || value.charAt(2) == '/');
    }

    /**
     * Captures the variable names, types, values, and a derived signature from a stack frame for
     * expression compilation. The signature is used as part of the compilation cache key so frames
     * with the same shape can share a compiled wrapper.
     */
    private static class EvaluationContext {
        private final List<ContextVariable> variables;
        private final List<Value> values;
        private final String signature;

        EvaluationContext(List<ContextVariable> variables, List<Value> values) {
            this.variables = variables;
            this.values = values;
            signature = variables.stream().map(v -> v.type + ' ' + v.name).collect(Collectors.joining(","));
        }

        List<ContextVariable> getVariables() {
            return variables;
        }

        List<Value> getValues() {
            return values;
        }

        String getSignature() {
            return signature;
        }
    }

    /**
     * Holds a compiled class name and its bytecode for caching across evaluations.
     */
    private record CachedCompilation(String className, byte[] bytecode) {
    }

    /**
     * A name-type pair representing a single variable from a stack frame.
     */
    private record ContextVariable(String name, String type) {
    }
}
