package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates evaluation of a user-supplied Java expression against a live JDI {@link StackFrame}.
 * <p>
 * Pipeline (per call to {@link #evaluate}):
 * 1. Build evaluation context from the frame (locals + `this`).
 * 2. Auto-rewrite bare `this.field` references via {@link #rewriteThisFieldReferences} when safe.
 * 3. Cache lookup keyed on `signature + "###" + expression`; on miss, generate a wrapper class
 * with a UUID-suffixed name, compile via {@link InMemoryJavaCompiler}, and cache the bytecode.
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
     * Package name baked into every generated wrapper class — kept short and isolated from app code.
     */
    private static final String EVALUATION_PACKAGE = "mcp.jdi.evaluation";
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
     * Compilation cache. Key is `contextSignature + "###" + expression`, so two frames with the
     * same local types and names sharing the same expression hit the same compiled class. Cleared
     * on overflow ({@link #MAX_CACHE_SIZE}) and on every {@link #configureCompilerClasspath} call
     * (new connections may invalidate old bytecode).
     */
    private final Map<String, CachedCompilation> compilationCache = new ConcurrentHashMap<>();

    public JdiExpressionEvaluator(
        InMemoryJavaCompiler compiler,
        JDIConnectionService jdiConnectionService,
        EvaluationGuard evaluationGuard
    ) {
        this.compiler = compiler;
        this.jdiConnectionService = jdiConnectionService;
        this.evaluationGuard = evaluationGuard;
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
     */
    private static EvaluationContext buildContext(StackFrame frame, Map<String, Value> extraBindings)
        throws AbsentInformationException {
        final List<ContextVariable> variables = new ArrayList<>();
        final List<Value> values = new ArrayList<>();

        final ObjectReference thisObject = frame.thisObject();
        if (thisObject != null) {
            // Use declared type instead of runtime type to avoid issues with dynamic proxies (Guice, CGLIB, etc.)
            final String declaredType = getDeclaredType(thisObject.referenceType());
            variables.add(new ContextVariable("_this", declaredType));
            values.add(thisObject);
        }

        for (LocalVariable var : frame.visibleVariables()) {
            // Filter out synthetic `this$N` outer-class references emitted by `javac` for inner classes:
            // they live in a different package than the wrapper class and so cannot be addressed from
            // inside it. The `isArgument()` allowance is defensive — a real argument named `this$N`
            // would be unusual but is technically valid bytecode.
            if (var.isArgument() || !var.name().startsWith("this$")) {
                final String typeName = resolveLocalVarType(var);
                variables.add(new ContextVariable(var.name(), typeName));
                values.add(frame.getValue(var));
            }
        }

        // Synthetic bindings are appended last so they appear after the real locals in the wrapper
        // signature. Cache key (signature + expression) naturally varies on the bound type, so two
        // different exception subclasses thrown at the same site get distinct cached compilations.
        for (Map.Entry<String, Value> entry : extraBindings.entrySet()) {
            variables.add(new ContextVariable(entry.getKey(), inferDeclaredType(entry.getValue())));
            values.add(entry.getValue());
        }

        return new EvaluationContext(variables, values);
    }

    /**
     * Resolves a wrapper-visible declared type for a JDI {@link Value}. Mirrors the public-supertype
     * walk performed by {@link #getDeclaredType(ReferenceType)} so a non-public exception subclass
     * still binds as a public ancestor (worst case {@code java.lang.Object}). Falls back to
     * {@code java.lang.Object} for {@code null} and unrecognised value kinds.
     */
    private static String inferDeclaredType(@Nullable Value value) {
        if (value instanceof ObjectReference objRef) {
            return getDeclaredType(objRef.referenceType());
        }
        if (value instanceof PrimitiveValue primValue) {
            return primValue.type().name();
        }
        return "java.lang.Object";
    }

    /**
     * Resolves a local variable's declared type to one the wrapper class can reference.
     * Falls back to a public supertype (or Object) for non-public types.
     */
    private static String resolveLocalVarType(LocalVariable var) {
        try {
            final Type t = var.type();
            if (t instanceof ReferenceType refType) {
                return getDeclaredType(refType);
            }
            return t.name();
        } catch (ClassNotLoadedException e) {
            // Type not loaded yet — fall back to declared name (may still be valid if a public type)
            return var.typeName();
        }
    }

    /**
     * Generates a wrapper class with a static `evaluate()` method that accepts the context
     * variables as parameters and returns the result of the user expression. The user expression
     * is wrapped in `(Object)(...)` so any value type — including primitives via autoboxing —
     * can be returned. The bare `this` keyword in the expression is tokenizer-rewritten to `_this`
     * because the wrapper class doesn't have a `this` reference (it's a static method).
     */
    private static String generateSourceCode(String className, EvaluationContext context, String expression) {
        final String packageName = EVALUATION_PACKAGE;
        final String simpleClassName = className.substring(packageName.length() + 1);

        final String methodParameters = context.getVariables().stream()
            .map(v -> v.type + ' ' + v.name)
            .collect(Collectors.joining(", "));

        // Replace bare `this` keyword with `_this` to match the wrapper's static-method parameter name.
        // Tokenizer-aware so identifiers like `myThis`/`thisFoo` and `this` tokens inside string/char
        // literals are NOT rewritten — see {@link #rewriteThisKeyword(String)}.
        final String safeExpression = rewriteThisKeyword(expression);

        return "package " + packageName + ";\n" +
            '\n' +
            "// Automatically generated class for JDI expression evaluation\n" +
            "public class " + simpleClassName + " {\n" +
            "    public static Object " + EVALUATION_METHOD_NAME + '(' + methodParameters + ") {\n" +
            "        // User expression:\n" +
            "        return (Object) (" + safeExpression + ");\n" +
            "    }\n" +
            "}\n";
    }

    /**
     * Get a name suitable to use in the generated wrapper class for the given runtime type.
     * Handles two cases the wrapper compiler cannot otherwise express:
     * <ul>
     *   <li><b>Dynamic proxies</b> (Guice, CGLIB, Mockito, Spring AOP) — unwrap to the real class.</li>
     *   <li><b>Non-public types</b> (e.g., package-private test classes) — walk up the superclass
     *       chain until a public type is found, falling back to {@code java.lang.Object}. The wrapper
     *       class lives in {@code mcp.jdi.evaluation} and can only reference public types.</li>
     * </ul>
     */
    private static String getDeclaredType(ReferenceType type) {
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

            // Walk up to find a public supertype the wrapper can reference
            if (type instanceof ClassType classType) {
                ClassType current = classType;
                while (current != null && !current.isPublic()) {
                    current = current.superclass();
                }
                if (current != null) {
                    return current.name();
                }
                return "java.lang.Object";
            }

            return typeName;
        }
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
     * The auto-rewrite of bare `this.field` references only runs when `this`'s declared type is
     * public — for non-public types the wrapper class can't reference the type at all, so the
     * rewrite would just produce a misleading "type not visible" error.
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
        evaluationGuard.enter(guardedThreadId);
        try {
            // NOTE: Classpath configuration must be done BEFORE calling evaluate() to avoid nested JDI calls
            // The caller (e.g., jdwp_evaluate_watchers) is responsible for calling configureCompilerClasspath()

            // 1. Analyze the frame to build the evaluation context
            final EvaluationContext context = buildContext(frame, extraBindings);

            // Auto-rewrite bare references to fields of `this` so users can write
            // `sessions.containsKey(session)` instead of `_this.sessions.containsKey(session)`.
            // Only safe when `this`'s declared type is public AND the specific field is public —
            // otherwise the wrapper class either can't reference the type or can't access the field,
            // and we'd just produce a misleading compile error ("not visible" instead of a real hint).
            final ObjectReference thisObject = frame.thisObject();
            if (thisObject != null && thisObject.referenceType() instanceof ClassType thisClass && thisClass.isPublic()) {
                final Set<String> shadowingLocals = context.getVariables().stream()
                    .map(v -> v.name)
                    .collect(Collectors.toSet());
                final Set<String> publicFieldNames = thisClass.allFields().stream()
                    .filter(Field::isPublic)
                    .map(Field::name)
                    .collect(Collectors.toSet());
                expression = rewriteThisFieldReferences(expression, publicFieldNames, shadowingLocals);
            }

            // 2. Use cache key based on context + expression (excludes UUID for cache hits)
            final String cacheKey = context.getSignature() + "###" + expression;

            // Full-flush eviction is deliberate: LRU bookkeeping isn't worth it for a cache whose
            // miss cost (compile + cache) is already orders of magnitude larger than just rebuilding
            // the few entries that get hot again.
            if (compilationCache.size() >= MAX_CACHE_SIZE) {
                log.info("[Evaluator] Compilation cache reached {} entries, clearing", compilationCache.size());
                compilationCache.clear();
            }

            final CachedCompilation cached = compilationCache.get(cacheKey);

            final String className;
            byte[] bytecode;

            if (cached != null) {
                // Cache hit — reuse previously compiled class name and bytecode
                className = cached.className;
                bytecode = cached.bytecode;
            } else {
                // Cache miss — generate unique class name, compile, and cache
                final String uniqueId = UUID.randomUUID().toString().replace("-", "");
                className = EVALUATION_PACKAGE + '.' + EVALUATION_CLASS_PREFIX + uniqueId;

                final String sourceCode = generateSourceCode(className, context, expression);

                final Map<String, byte[]> compiledCode = compiler.compile(className, sourceCode);

                bytecode = compiledCode.get(className);
                if (bytecode == null) {
                    // Some compilers key by binary name with slashes, simple name, or with leading slashes —
                    // fall back to a suffix match on the simple class name.
                    final String simpleName = className.substring(className.lastIndexOf('.') + 1);
                    for (Map.Entry<String, byte[]> entry : compiledCode.entrySet()) {
                        final String key = entry.getKey();
                        String keyTail = key.substring(key.lastIndexOf('/') + 1).replace(".class", "");
                        keyTail = keyTail.substring(keyTail.lastIndexOf('.') + 1);
                        if (keyTail.equals(simpleName)) {
                            bytecode = entry.getValue();
                            log.debug("[Evaluator] Bytecode found via fallback key '{}' for class '{}'", key, className);
                            break;
                        }
                    }
                }
                if (bytecode == null) {
                    throw new JdiEvaluationException("Could not find compiled bytecode for class " + className
                        + " (available keys: " + compiledCode.keySet() + ')');
                }

                compilationCache.put(cacheKey, new CachedCompilation(className, bytecode));
            }

            // 3. Find a suitable class loader in the target VM
            final ClassLoaderReference classLoader = findClassLoader(frame);

            // 4. Execute the code remotely
            return RemoteCodeExecutor.execute(
                frame.virtualMachine(),
                frame.thread(),
                classLoader,
                className,
                bytecode,
                EVALUATION_METHOD_NAME,
                context.getValues()
            );
        } catch (Exception e) {
            // Un-wrap runtime exception from cache computation
            if (e instanceof RuntimeException && e.getCause() instanceof JdiEvaluationException jdiEx) {
                throw jdiEx;
            }
            throw new JdiEvaluationException("Expression evaluation failed: " + e.getMessage(), e);
        } finally {
            evaluationGuard.exit(guardedThreadId);
        }
    }

    /**
     * Configures the compiler with the target JVM's classpath. Skips if already configured for the
     * current connection. Automatically reconfigures after a disconnect/reconnect cycle (detected
     * via null JDK path) and clears the compilation cache on reconfiguration because stale bytecode
     * may reference classes from a previous connection. Must be called BEFORE any expression
     * evaluation to avoid nested JDI calls.
     *
     * @param suspendedThread a thread already suspended at a breakpoint (REQUIRED)
     */
    public synchronized void configureCompilerClasspath(ThreadReference suspendedThread) {
        // Self-healing: if JDK path is already set, classpath is already configured for this connection
        if (jdiConnectionService.getDiscoveredJdkPath() != null) {
            return;
        }

        // Reentrancy guard: discoverClasspath walks the target-VM classloader hierarchy via
        // invokeMethod calls. If any of those invocations land on a breakpointed line the
        // listener must suppress the hit rather than re-suspend the thread we are driving.
        // Capture uniqueID up front so a thread death mid-discovery does not leak a map entry.
        final long guardedThreadId = suspendedThread.uniqueID();
        evaluationGuard.enter(guardedThreadId);
        try {
            // New connection or reconnect — clear stale compilation cache
            compilationCache.clear();

            final long startTime = System.currentTimeMillis();

            try {
                final String classpath = jdiConnectionService.discoverClasspath(suspendedThread);
                final String jdkPath = jdiConnectionService.getDiscoveredJdkPath();

                if (jdkPath == null) {
                    log.error("[Evaluator] JDK path not discovered, cannot configure compiler");
                    return;
                }

                final int version = jdiConnectionService.getTargetMajorVersion();
                if (classpath != null && !classpath.isEmpty()) {
                    compiler.configure(jdkPath, classpath, version);

                    final long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[Evaluator] Compiler configured in {}ms", elapsed);
                } else {
                    log.error("[Evaluator] Failed to discover classpath, expression evaluation may fail for application classes");
                }

            } catch (Exception e) {
                final long elapsed = System.currentTimeMillis() - startTime;
                log.error("[Evaluator] Error configuring classpath after {}ms", elapsed, e);
            }
        } finally {
            evaluationGuard.exit(guardedThreadId);
        }
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
