package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiClassDefinitionException;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates evaluation of a user-supplied Java expression against a live JDI {@link StackFrame}.
 * <p>
 * Pipeline (per call to {@link #evaluate}):
 * 1. Build evaluation context from the frame (locals + `this`).
 * 2. Auto-rewrite bare field identifiers (`field` → `_this.field`) via
 * {@link #rewriteThisFieldReferences} when safe; explicit `this`/`this.field` is handled
 * separately by {@link #rewriteThisKeyword}.
 * 3. Cache lookup keyed on `wrapperPackage + "###" + signature + "###" + expression` (the package
 * is part of the key because a define-time fallback may recompile the same expression into a
 * different package); on miss, generate a wrapper class with a UUID-suffixed name, compile via
 * {@link InMemoryJavaCompiler}, and cache the bytecode.
 * 4. Inject the bytecode into the target VM and execute via {@link RemoteCodeExecutor}.
 * <p>
 * Cache eviction: when {@link #compilationCache} reaches {@link #MAX_CACHE_SIZE} entries it is
 * fully flushed rather than LRU-evicted (see the inline comment on the eviction call for the
 * design rationale).
 * <p>
 * Class naming: every generated wrapper uses a fresh `UUID`-suffixed name. This avoids
 * `LinkageError` when the MCP server reconnects to a freshly-restarted target VM and tries to
 * load bytecode that the previous session had already pinned to a class name.
 * <p>
 * Thread model: {@link #configureCompilerClasspath} MUST be called from the MCP worker thread
 * BEFORE {@link #evaluate}, never from inside the JDI event listener. The configuration step
 * issues `invokeMethod` calls that would deadlock the listener if it called itself.
 */
@Service
public class JdiExpressionEvaluator {
    private static final Logger log = LoggerFactory.getLogger(JdiExpressionEvaluator.class);

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
     * Threshold for the full-flush eviction — see {@link #compilationCache}.
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
     * Compilation cache. Key is `contextSignature + "###" + expression`, so two frames with the
     * same local types and names sharing the same expression hit the same compiled class. Cleared
     * on overflow ({@link #MAX_CACHE_SIZE}) and on every {@link #configureCompilerClasspath} call
     * (new connections may invalidate old bytecode).
     */
    private final Map<String, CachedCompilation> compilationCache = new ConcurrentHashMap<>();

    /**
     * Whether the (potentially Maven-blocking) local-project classpath has already been merged into
     * the compiler config for the current connection. Distinct from "JDK path discovered": the
     * best-effort {@link #prewarmClasspath} warms only the remote classpath + JDK detection and
     * leaves this {@code false}, so the first real evaluation still completes the local merge.
     * Reset to {@code false} on the reconnect edge (null JDK path) alongside the other caches.
     * Guarded by the {@code synchronized} monitor of {@link #configureCompilerClasspath}.
     */
    private boolean localClasspathMerged = false;

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
        if (thisFieldNames.isEmpty()) {
            return expression;
        }
        final Set<String> rewritable = thisFieldNames.stream()
            .filter(name -> !shadowingLocals.contains(name))
            .collect(Collectors.toSet());
        if (rewritable.isEmpty()) {
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
                if (!rewritable.contains(identifier) || isPrecededByDot(expression, i)) {
                    out.append(identifier);
                } else {
                    out.append("_this.").append(identifier);
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
                    out.append("_this");
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
     * Extracts locals and the `this` reference from a stack frame, then appends any caller-supplied
     * synthetic bindings. Uses declared type rather than runtime type for `this` to handle proxy
     * classes (Guice, CGLIB, etc.). Throws `AbsentInformationException` if the frame's method was
     * compiled without `-g` (no local variable debug info).
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
     */
    private static EvaluationContext buildContext(StackFrame frame, Map<String, Value> extraBindings,
                                                  String wrapperPackage)
        throws AbsentInformationException {
        final List<ContextVariable> variables = new ArrayList<>();
        final List<Value> values = new ArrayList<>();

        final ObjectReference thisObject = frame.thisObject();
        if (thisObject != null) {
            // Use declared type instead of runtime type to avoid issues with dynamic proxies (Guice, CGLIB, etc.)
            final String declaredType = getDeclaredType(thisObject.referenceType(), wrapperPackage);
            variables.add(new ContextVariable("_this", declaredType));
            values.add(thisObject);
        }

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

        return new EvaluationContext(variables, values);
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
    private static String generateSourceCode(String className, EvaluationContext context, String expression) {
        final int lastDot = className.lastIndexOf('.');
        final String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);
        final String simpleClassName = lastDot < 0 ? className : className.substring(lastDot + 1);

        final String methodParameters = context.getVariables().stream()
            .map(v -> v.type + ' ' + v.name)
            .collect(Collectors.joining(", "));

        final String packageDecl = packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
        return packageDecl +
            "// Automatically generated class for JDI expression evaluation\n" +
            "public class " + simpleClassName + " {\n" +
            "    public static Object " + EVALUATION_METHOD_NAME + '(' + methodParameters + ") {\n" +
            generateMethodBody(expression) +
            "    }\n" +
            "}\n";
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

        final String methodBody;
        if (isBlockMode(safeExpression)) {
            // Extract the body inside the outermost braces — already balanced because
            // isBlockMode verified the structure (start with `{`, balanced end with `}`,
            // string/char/text-block/comment regions skipped).
            //
            // The user body is wrapped in `if (__mcpFallthroughGuard) { ... }` where the guard
            // is a non-final local set to true. The compiler can't treat it as a compile-time
            // constant (JLS §15.29 — only `final` variables initialised with constant
            // expressions qualify), so it does NOT prove the post-if `return null;` unreachable.
            // That keeps the wrapper compiling whether or not the user's body ends with an
            // explicit `return X;`. At runtime the guard is always true → user body always runs;
            // `return null;` is the safety net for blocks that fall through without returning.
            final String trimmed = safeExpression.trim();
            final String userBody = trimmed.substring(1, trimmed.length() - 1);
            methodBody =
                "        // User block:\n" +
                "        boolean __mcpFallthroughGuard = true;\n" +
                "        if (__mcpFallthroughGuard) {\n" +
                userBody + '\n' +
                "        }\n" +
                "        // Fallthrough guard — only reached when the user's block doesn't return.\n" +
                "        return null;\n";
        } else {
            methodBody =
                "        // User expression:\n" +
                "        return (Object) (" + safeExpression + ");\n";
        }
        return methodBody;
    }

    /**
     * Returns {@code true} when the user input is in block mode — i.e. its trimmed form starts
     * with '{' and ends with the matching '}'. Tokenizer-aware: braces inside string / char /
     * text-block literals and inside line ({@code //…}) or block ({@code /*…*}{@code /}) comments
     * are ignored, so inputs like {@code "{x}".length()} (a method call on a string literal that
     * happens to start with '{') stay in expression mode, and a comment containing '}' inside an
     * otherwise-block input does not prematurely close the outer block.
     *
     * <p>Static + package-private so it can be unit-tested without a real {@link StackFrame}.
     */
    static boolean isBlockMode(String expression) {
        final String trimmed = expression.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return false;
        }
        // Walk the inside and confirm brace balance returns to zero at exactly the closing `}`.
        // Tokenizer-aware so braces inside string / char / text-block literals and Java comments
        // are ignored.
        final int n = trimmed.length() - 1;
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
                    // A line comment that runs to end-of-string would have swallowed the
                    // trailing `}` (no newline before it) — there is no real closer, so the
                    // input is not block-mode.
                    if (i > n) {
                        return false;
                    }
                    continue;
                }
                if (next == '*') {
                    i = skipBlockComment(trimmed, i);
                    // Unterminated block comment swallows the trailing `}` too. Same rationale.
                    if (i > n) {
                        return false;
                    }
                    continue;
                }
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    // Hit a top-level `}` before the trailing one — input is something like
                    // `{stmt;} + foo` so the trailing `}` of the trimmed form is a different
                    // closer and block-mode does not apply.
                    return false;
                }
            }
            i++;
        }
        // Trailing `}` at index n closes the outer brace.
        return depth == 1;
    }

    /**
     * Returns the index just past the end of a {@code //…} line comment that starts at
     * {@code start}. Terminator is the next newline; if no newline is found, returns the end of
     * the string (best-effort tolerance — never throws).
     */
    private static int skipLineComment(String s, int start) {
        final int n = s.length();
        int i = start + 2;
        while (i < n && s.charAt(i) != '\n') {
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
     * Whether {@code field} can be referenced from the generated wrapper class, given the
     * wrapper's package and whether it shares that package with {@code this}'s declared type.
     * <ul>
     *   <li>{@code public} fields are always reachable.</li>
     *   <li>When the wrapper does NOT share {@code this}'s package, only public fields are
     *       reachable — the wrapper is in the isolated {@code mcp.jdi.evaluation} and cannot see
     *       package-private or protected members of app code.</li>
     *   <li>When the wrapper DOES share {@code this}'s package, non-private fields are reachable
     *       only if their declaring type ALSO lives in that package. A protected field inherited
     *       from a class in a different package is accessible only via subclassing, and the
     *       wrapper is not a subclass — so we leave such fields un-rewritten to avoid a
     *       misleading "not visible" compile error.</li>
     *   <li>Private fields are never rewritten (the wrapper is not the declaring class).</li>
     * </ul>
     */
    private static boolean isFieldAccessibleFromWrapper(Field field, String wrapperPackage, boolean sharingPackage) {
        if (field.isPublic()) {
            return true;
        }
        if (!sharingPackage || field.isPrivate()) {
            return false;
        }
        return packageOf(field.declaringType().name()).equals(wrapperPackage);
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
     * Locates a non-null {@link ClassLoaderReference} for injecting the wrapper class. Three-level
     * fallback:
     * 1. `frame.thisObject().referenceType().classLoader()` — works for instance methods.
     * 2. `frame.location().declaringType().classLoader()` — works for static methods.
     * 3. Invokes `ClassLoader.getSystemClassLoader()` in the target VM as a last resort.
     * <p>
     * Throws {@link JdiEvaluationException} if all three return null — typically meaning the frame
     * is in a bootstrap-loaded class on a JVM where the system classloader is also unreachable.
     */
    private static ClassLoaderReference findClassLoader(StackFrame frame) throws JdiEvaluationException {
        final ObjectReference thisObject = frame.thisObject();
        final ClassLoaderReference cl;
        if (thisObject != null) {
            cl = thisObject.referenceType().classLoader();
        } else {
            // Static method — use the declaring type's classloader
            cl = frame.location().declaringType().classLoader();
        }
        if (cl != null) {
            return cl;
        }

        // All lookups returned null (bootstrap class context) — invoke ClassLoader.getSystemClassLoader() in the target VM
        try {
            final List<ReferenceType> clTypes = frame.virtualMachine().classesByName("java.lang.ClassLoader");
            if (!clTypes.isEmpty()) {
                final ClassType clType = (ClassType) clTypes.get(0);
                final Method getSystemCL = clType.concreteMethodByName("getSystemClassLoader", "()Ljava/lang/ClassLoader;");
                if (getSystemCL != null) {
                    final Value result = clType.invokeMethod(
                        frame.thread(), getSystemCL, Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED
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
     * and returns the result as a JDI {@link Value}. Side effect: populates {@link #compilationCache}.
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
        // Reentrancy guard: mark the firing thread as mid-evaluation BEFORE touching JDI so the
        // event listener suppresses any recursive breakpoint / exception event that fires while
        // we are inside the invokeMethod chain (defineClass / forName / user wrapper method /
        // findClassLoader's getSystemClassLoader fallback). The guard is counted, so a nested
        // call — e.g. from a conditional breakpoint expression — safely stacks onto an enclosing
        // enter.
        //
        // Capture uniqueID up front and pass the long to both enter and exit. If the target
        // thread dies mid-evaluation, re-querying uniqueID() on the dead ThreadReference would
        // throw ObjectCollectedException and leak a dangling entry in the guard's depth map.
        final long guardedThreadId = frame.thread().uniqueID();
        final long startTime = System.currentTimeMillis();
        evaluationGuard.enter(guardedThreadId);
        try {
            // NOTE: Classpath configuration must be done BEFORE calling evaluate() to avoid nested JDI calls
            // The caller (e.g., jdwp_evaluate_watchers) is responsible for calling configureCompilerClasspath()

            // 0. Decide which package the wrapper class will live in. For a non-public `this` in an
            //    addressable package, we emit the wrapper alongside it so package-private fields and
            //    the type itself become reachable; otherwise we use the default isolated package.
            final ObjectReference thisObject = frame.thisObject();
            final String wrapperPackage = resolveWrapperPackage(thisObject);

            try {
                return evaluateInPackage(frame, expression, extraBindings, wrapperPackage, startTime);
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
                return evaluateInPackage(frame, expression, extraBindings, DEFAULT_EVALUATION_PACKAGE, startTime);
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
        } finally {
            evaluationGuard.exit(guardedThreadId);
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
    private @Nullable Value evaluateInPackage(StackFrame frame, String expression,
                                              Map<String, Value> extraBindings, String wrapperPackage,
                                              long startTime)
        throws JdiEvaluationException, AbsentInformationException {
        // 1. Analyze the frame to build the evaluation context
        final EvaluationContext context = buildContext(frame, extraBindings, wrapperPackage);

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
        final ObjectReference thisObject = frame.thisObject();
        if (thisObject != null && thisObject.referenceType() instanceof ClassType thisClass) {
            final Set<String> shadowingLocals = context.getVariables().stream()
                .map(v -> v.name)
                .collect(Collectors.toSet());
            expression = rewriteBareThisFields(expression, thisClass, wrapperPackage, shadowingLocals);
        }

        // 2. Cache key includes the wrapper package: the same (signature, expression) may be
        //    compiled into two different packages (e.g. after a define-time fallback), and the
        //    cached className + bytecode are package-specific.
        final String cacheKey = wrapperPackage + "###" + context.getSignature() + "###" + expression;

        // Full-flush eviction is deliberate: LRU bookkeeping isn't worth it for a cache whose
        // miss cost (compile + cache) is already orders of magnitude larger than just rebuilding
        // the few entries that get hot again.
        if (compilationCache.size() >= MAX_CACHE_SIZE) {
            log.info("[Evaluator] Compilation cache reached {} entries, clearing", compilationCache.size());
            compilationCache.clear();
        }

        final CachedCompilation cached = compilationCache.get(cacheKey);

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

            final String sourceCode = generateSourceCode(className, context, expression);

            final Map<String, byte[]> compiledCode = compiler.compile(className, sourceCode);
            bytecode = findCompiledBytecode(compiledCode, className);

            compilationCache.put(cacheKey, new CachedCompilation(className, bytecode));
        }

        // 3. Find a suitable class loader in the target VM
        final ClassLoaderReference classLoader = findClassLoader(frame);

        // 4. Execute the code remotely. A define-phase rejection surfaces as
        //    JdiClassDefinitionException, which the caller may catch to retry in the default package.
        final Value value = RemoteCodeExecutor.execute(
            frame.virtualMachine(),
            frame.thread(),
            classLoader,
            className,
            bytecode,
            EVALUATION_METHOD_NAME,
            context.getValues()
        );
        log.info("[Evaluator] Expression evaluated in {}ms (cache {})",
            System.currentTimeMillis() - startTime, cached != null ? "hit" : "miss");
        return value;
    }

    /**
     * Applies the bare-field auto-rewrite ({@code field} → {@code _this.field}) for the fields of
     * {@code thisClass} that the wrapper in {@code wrapperPackage} can reach — see the comment in
     * {@link #evaluateInPackage} for the accessibility rules. Returns {@code expression} unchanged in
     * block mode, and when {@code thisClass} is neither public nor in the wrapper's package.
     */
    private static String rewriteBareThisFields(String expression, ClassType thisClass, String wrapperPackage,
                                                Set<String> shadowingLocals) {
        if (isBlockMode(expression)) {
            return expression;
        }
        final boolean sharingPackage = !wrapperPackage.equals(DEFAULT_EVALUATION_PACKAGE)
            && wrapperPackage.equals(packageOf(thisClass.name()));
        if (!thisClass.isPublic() && !sharingPackage) {
            return expression;
        }
        final Set<String> rewritableFieldNames = thisClass.allFields().stream()
            .filter(f -> isFieldAccessibleFromWrapper(f, wrapperPackage, sharingPackage))
            .map(Field::name)
            .collect(Collectors.toSet());
        return rewriteThisFieldReferences(expression, rewritableFieldNames, shadowingLocals);
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
     * Must be called on a thread suspended by an event, after {@link #configureCompilerClasspath}.
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
        final List<FastLogpointProbeSource.Parameter> parameters = new ArrayList<>();
        final ReferenceType declaringType = frame.location().declaringType();
        if (includeThis) {
            parameters.add(new FastLogpointProbeSource.Parameter("_this", getDeclaredType(declaringType, wrapperPackage)));
        }
        try {
            for (LocalVariable var : frame.visibleVariables()) {
                // Same `this$N` filter as buildContext — the outer-instance reference is unaddressable.
                if (var.isArgument() || !var.name().startsWith("this$")) {
                    parameters.add(new FastLogpointProbeSource.Parameter(var.name(), resolveLocalVarType(var, wrapperPackage)));
                }
            }
        } catch (AbsentInformationException noLocals) {
            log.debug("[Evaluator] No local-variable table at the fast-logpoint location — probe receives `this` only.");
        }

        String effectiveExpression = expression;
        String effectiveCondition = condition;
        if (includeThis && declaringType instanceof ClassType thisClass) {
            final Set<String> localNames = parameters.stream()
                .map(FastLogpointProbeSource.Parameter::name)
                .collect(Collectors.toSet());
            effectiveExpression = rewriteBareThisFields(expression, thisClass, wrapperPackage, localNames);
            if (condition != null) {
                effectiveCondition = rewriteBareThisFields(condition, thisClass, wrapperPackage, localNames);
            }
        }

        final String uniqueId = UUID.randomUUID().toString().replace("-", "");
        final String className = wrapperPackage.isEmpty()
            ? FastLogpointProbeSource.CLASS_PREFIX + uniqueId
            : wrapperPackage + '.' + FastLogpointProbeSource.CLASS_PREFIX + uniqueId;
        final String sourceCode = FastLogpointProbeSource.generate(
            className, parameters, effectiveExpression, effectiveCondition, bufferSize, metadata);
        final Map<String, byte[]> compiledCode = compiler.compile(className, sourceCode);
        if (compiledCode.size() > 1) {
            // The probe is defined as a single class; a nested/anonymous class in the user code
            // would compile to a second class file that is never defined in the target.
            throw new JdiEvaluationException("Fast logpoint expressions and conditions cannot declare "
                + "anonymous or nested classes (compiled to " + compiledCode.size() + " classes)");
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

    /**
     * Configures the compiler with the target JVM's classpath, including the local-project classpath
     * fallback. Skips if already fully configured for the current connection. Automatically
     * reconfigures after a disconnect/reconnect cycle (detected via null JDK path): clears the
     * compilation cache AND resets the compiler config because stale bytecode and a stale classpath
     * may reference classes from a previous connection. Also resets the
     * {@link LocalProjectClasspathProvider} cache on the same edge so a reconnect into a different
     * working directory sees the new project layout. Must be called BEFORE any expression evaluation
     * to avoid nested JDI calls.
     *
     * @param suspendedThread a thread already suspended at a breakpoint (REQUIRED)
     * @throws JdiEvaluationException if classpath/JDK discovery fails for the current connection;
     *                                the message is actionable and is surfaced to the user as an
     *                                evaluation error or a {@code LOGPOINT_ERROR} event rather than
     *                                left to manifest as a cryptic downstream JDT diagnostic
     */
    public synchronized void configureCompilerClasspath(ThreadReference suspendedThread)
        throws JdiEvaluationException {
        configureCompilerClasspath(suspendedThread, true);
    }

    /**
     * Worker for {@link #configureCompilerClasspath(ThreadReference)} and {@link #prewarmClasspath}.
     *
     * <p>{@code includeLocal} controls whether the local-project classpath fallback (env override +
     * filesystem scan + Maven {@code dependency:build-classpath}) is merged in. The real evaluation
     * paths pass {@code true}; the speculative {@link #prewarmClasspath} — which runs on the JDI
     * event-listener thread the instant a breakpoint hits, before the user has asked for anything —
     * passes {@code false}. The local fallback can shell out to Maven with a multi-minute timeout, so
     * paying it on the event-listener thread would block event processing (and every other breakpoint)
     * for minutes even if the user never evaluates an expression. Prewarm therefore warms only the
     * remote classpath + JDK detection, and {@link #localClasspathMerged} stays {@code false} so the
     * first actual evaluation/logpoint/condition completes the local merge.
     *
     * @param suspendedThread a thread already suspended at a breakpoint (REQUIRED)
     * @param includeLocal    whether to merge the (possibly Maven-blocking) local-project classpath
     */
    private synchronized void configureCompilerClasspath(ThreadReference suspendedThread, boolean includeLocal)
        throws JdiEvaluationException {
        final boolean jdkReady = jdiConnectionService.getDiscoveredJdkPath() != null;
        // Self-healing short-circuit. The remote classpath + JDK are warm once the JDK path is set,
        // which is all a prewarm (includeLocal=false) cares about. A real evaluation additionally
        // needs the local fallback merged — so it only short-circuits once localClasspathMerged is
        // true, otherwise it falls through to complete the merge prewarm deliberately skipped.
        if (jdkReady && (!includeLocal || localClasspathMerged)) {
            return;
        }

        // Reentrancy guard: discoverClasspath walks the target-VM classloader hierarchy via
        // invokeMethod calls. If any of those invocations land on a breakpointed line the
        // listener must suppress the hit rather than re-suspend the thread we are driving.
        // Capture uniqueID up front so a thread death mid-discovery does not leak a map entry.
        final long guardedThreadId = suspendedThread.uniqueID();
        evaluationGuard.enter(guardedThreadId);
        try {
            // A null discovered-JDK path means either first use on this connection or a
            // post-reconnect cache wipe. Either way, any compiler state left over from a previous
            // connection now points at the wrong target VM. Clear BOTH the compilation cache and
            // the compiler config before rediscovering: if discovery then fails, compile() refuses
            // with a clear "not configured" error instead of silently emitting bytecode resolved
            // against the previous target's classpath.
            // The local-classpath provider lives in the same Spring singleton and may hold a
            // memoised view of a *previous* connection's project layout — reset it on the same
            // connection-lifecycle edge so the next discover() sees the current CWD/env/Maven view.
            // Guard on !jdkReady: when we fall through here with the JDK already discovered (a real
            // evaluation finishing the merge a prewarm deferred), the remote discovery is still valid
            // and must NOT be wiped.
            if (!jdkReady) {
                compilationCache.clear();
                compiler.reset();
                localClasspathProvider.reset();
                localClasspathMerged = false;
            }

            final long startTime = System.currentTimeMillis();

            // discoverClasspath swallows its own failures (JdkNotFoundException, classloader-walk
            // errors) and signals them by returning null / leaving the JDK path unset. Translate
            // that into an actionable exception rather than returning silently — otherwise the
            // caller proceeds to compile() and the user only sees a raw JDT diagnostic (e.g.
            // "io cannot be resolved") with no hint that the real cause was a failed discovery.
            final String remoteClasspath = jdiConnectionService.discoverClasspath(suspendedThread);
            final String jdkPath = jdiConnectionService.getDiscoveredJdkPath();

            if (jdkPath == null) {
                throw new JdiEvaluationException(
                    "Classpath discovery failed for the current connection: no local JDK matching "
                        + "the target VM could be located, so application types cannot be resolved. "
                        + "Check the server log (search '[JDI]') for the underlying cause.");
            }

            // Local classpath augments — does NOT replace — the remote one. The union goes
            // [remote..., local-only...] so live target VM entries continue to win on JDT
            // resolution; local entries only fill gaps the remote walk could not see (Tomcat /
            // Spring Boot / custom URLClassLoaders that hide their JARs from getURLs()).
            // Resolution order: ECJ scans the classpath left-to-right (InMemoryJavaCompiler
            // passes the joined string as `-classpath`), so a class present in BOTH a remote and
            // a stale local entry binds against the remote definition first — desired behaviour
            // for the source/binary-drift risk.
            final long mergeStart = System.currentTimeMillis();
            // includeLocal=false (prewarm) skips the local provider entirely — it can shell out to
            // Maven with a multi-minute timeout, which must never run on the JDI event-listener
            // thread. The first real evaluation re-enters with includeLocal=true and completes the
            // merge below.
            final Set<String> localEntries = includeLocal ? localClasspathProvider.discover() : Set.of();
            final String mergedClasspath = mergeClasspaths(remoteClasspath, localEntries);
            // Report three numbers so an operator can see overlap explicitly:
            // remote + local are the raw source counts; merged is the deduped union actually
            // handed to the compiler. Some local entries may overlap with remote (live target VM
            // and local project share JARs) — the overlap shows up as "merged < remote + local".
            final int remoteCount = countEntries(remoteClasspath);
            final int mergedCount = countEntries(mergedClasspath);
            log.info("[LocalClasspath] Merged classpath: {} remote + {} local entries → {} merged in {}ms"
                    + (includeLocal ? "" : " (prewarm — local fallback deferred to first evaluation)"),
                remoteCount, localEntries.size(), mergedCount,
                System.currentTimeMillis() - mergeStart);

            if (mergedClasspath.isEmpty()) {
                throw new JdiEvaluationException(
                    "Classpath discovery failed for the current connection: neither the target VM "
                        + "classloader hierarchy nor the local project yielded any classpath entries. "
                        + "The MCP server was launched from the directory '"
                        + Objects.toString(System.getProperty("user.dir"), "<unknown>") + "'. "
                        + "Either (a) restart the server from a directory containing a Maven project "
                        + "(pom.xml + target/classes), or (b) set the JDWP_EXTRA_CLASSPATH environment "
                        + "variable to a colon/semicolon-separated list of jars and class directories. "
                        + "Run jdwp_diagnose to inspect what was scanned, and check the server log for "
                        + "'[LocalClasspath]' and '[Discoverer]' entries.");
            }

            final int version = jdiConnectionService.getTargetMajorVersion();
            compiler.configure(jdkPath, mergedClasspath, version);
            // Mark the local fallback merged only on the real-evaluation path. A prewarm leaves this
            // false so the first actual evaluation falls through the short-circuit and merges it.
            if (includeLocal) {
                localClasspathMerged = true;
            }
            log.info("[Evaluator] Compiler configured in {}ms", System.currentTimeMillis() - startTime);
        } finally {
            evaluationGuard.exit(guardedThreadId);
        }
    }

    /**
     * Best-effort pre-warm of the compiler classpath at the first thread suspension, so the agent's
     * first {@code evaluate_expression} (or the first logpoint/condition hit) does not pay the
     * one-time remote classpath discovery cost — which can take 1-3s while it walks the target VM's
     * classloader hierarchy via {@code invokeMethod} — on the critical path. Discovery is paid once
     * per connection; subsequent calls short-circuit on the cached JDK path.
     * <p>
     * Warms ONLY the remote classpath + JDK detection (it passes {@code includeLocal=false}). The
     * local-project fallback is deliberately NOT run here: it can shell out to Maven with a
     * multi-minute timeout, and this method runs on the JDI event-listener thread the moment a
     * breakpoint hits — blocking that thread on Maven would stall event processing (and every other
     * breakpoint) for minutes even if the user never evaluates anything. The first real evaluation
     * completes the local merge via {@link #configureCompilerClasspath(ThreadReference)}.
     * <p>
     * Unlike {@link #configureCompilerClasspath}, this NEVER throws: it is invoked from the JDI
     * event listener while a thread is parked for inspection, and a warming failure must not disrupt
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
            configureCompilerClasspath(suspendedThread, false);
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
