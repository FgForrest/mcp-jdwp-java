# Expression evaluation

Ordinary debugger expressions use `EvaluationRouter`. It plans ordinary Java expressions as JDI reads (NAVIGATE), interpreted calls (INVOKE), or Eclipse JDT (ECJ) compilation (COMPILE). Evaluation, assertions, watchers, and ordinary conditions and logpoints share the router. The threading and memory chapters describe the costs of each path. [Threading and safety](threading-and-safety.md) [Memory and references](memory-and-references.md)

## JDI interpreter

`ExpressionParser` parses a single expression or a balanced `{ ... }` statement body with ECJ, without consulting the target. `Planner` snapshots the frame's declaring type, `this`, locals and debugger bindings. It resolves fields from declared types and searches loaded types visible to the frame's loader. Types and members bind before execution. A syntax error or an unresolved name that can only be a variable is reported directly.

A simple type name can name the frame's class, an enclosing class, or a member type declared or inherited by one of those classes. It can also name a `java.lang` class if the frame's package has no loaded class with that name and the constant pools examined during lookup contain no conflicting name. Use a fully qualified name to interpret other types from the frame's package or from an import. A simple name that needs one of those bindings goes to the compiler. If a same-named class in the frame's package is still unloaded, both the interpreter and the compiled wrapper can resolve the name to `java.lang`.

Each lexical scope's member types take precedence over its own simple name. At an unloaded enclosing type, lookup can select a loaded direct member only when it arrives from a member class. A lowercase first segment of a dotted name can still start package lookup. Other names route to compilation. Formal type parameters of the frame's method and of loaded lexical classes or their methods, possible local-class matches, and unreadable formal lists can also cause fallback. Constant-pool checks include reference array component names. Once the first segment of a dotted name binds to a type, each following segment must resolve as a member of the type bound so far.

Navigation includes private and package-private fields, inherited and static fields, enum constants, outer instances, locals, marks, event bindings, arrays, class literals, casts, `instanceof`, primitive operators and lazy `&&`, `||` and `?:`. A type pattern such as `value instanceof String text` binds `text` through the rest of an `&&` chain and in the true branch of `?:`. In a block, a pattern introduced by an `if` condition can also bind in the true branch. Uses that rely on flow scoping after the `if`, including after a negated guard returns, use compilation. The planner routes other uses of a name declared by a pattern to compilation before looking for locals, fields, or types of the same name. A binding pattern needs a provable casting conversion from the full source type. Generic, raw, captured, type-variable, intersection and imprecise scrutinee types go to compilation. Unconditional binding patterns are interpreted only on target Java 21 or later. It also handles concatenation of strings, null, integers, characters and booleans. Supported intrinsics are `String.length/isEmpty/equals/charAt`, `Enum.name/ordinal/equals`, and the final primitive wrappers' value accessors and `equals`. The interpreter uses JDI reads and host-side primitive operations, so it works on a manually suspended thread, a bystander held by another thread's stop-all, or a suspended thread blocked on a monitor or in `wait()`. Newly mirrored strings stay pinned through result formatting. The interpreter checks the original frame after the reads and after formatting so a resume invalidates the result.

### Method calls

The interpreter can invoke private and package-private methods, including calls through private fields such as `store.map.size()`. It first looks up Java members of the static receiver type, including declarations of any access on that type, inherited non-private methods, and package-private methods inherited within the same package. The debugger's relaxed access lookup runs only when that name has no Java member. A call is eligible when exactly one signature matches the argument count and the arguments' static types can convert to its parameters by identity, widening, boxing or unboxing. A varargs member of the name routes the call to compilation. Overrides count as one signature. Ordinary virtual dispatch selects the implementation in the target.

Unqualified calls use the frame's declaring class and its inherited methods. If that class has no method of the name, compilation handles enclosing classes and static imports. An unqualified instance call in a static frame reports an error. Static calls use the type-name rules above, and `super.m()` searches the superclass and dispatches non-virtually. Constructors use the same candidate and argument checks on an initialized, concrete class. Constructors of inner, local and anonymous classes, constructions with class type arguments or diamond inference, and static calls into uninitialized classes use compilation.

Generic signatures on fields, locals and preceding results supply the receiver's type arguments. For example, `Map<String, Session>.get` yields the static type `Session`, so `map.get(key).role` binds the field on `Session`. A wildcard capture uses the most specific provable bound from its explicit upper bound and the type parameter's declared bounds. Captures that still need an intersection go to compilation. An erased local's captured value can supply a receiver type during planning. If an invocation would require discovering a member after execution starts, the planner selects compilation. Navigation can still use deferred field reads, where multiple matching declarations trigger fallback. When a member read or argument conversion needs a narrower type than the generic result’s JVM descriptor, the interpreter checks the value at that use. An unavailable type goes to compilation. A failed check raises `ClassCastException` before subsequent operations.

Calls evaluate the receiver first and then the arguments left to right. Primitive widening and unboxing use debugger-side operations, and boxing invokes the target wrapper's `valueOf` to preserve its cache identity. Object concatenation invokes `String.valueOf(Object)`, including for arrays and null references. A null return from `toString()` contributes `"null"`. Floating-point operands use the target's `String.valueOf` so formatting matches its JDK. Integral, character and boolean formatting stays in the debugger.

Before a String constant materialized by the debugger enters a target call as a receiver or argument, the interpreter invokes `String.intern()` in the target. This includes folded constants such as `"to" + "ken"`. A String field already supplies its target reference. When a folded concatenation depends on a field whose constant value cannot be proven, the interpreter uses compilation. String identity comparisons that involve a constant still use compilation. A String constant containing a floating-point operand goes to compilation when it enters target code, including as a folded fragment of a larger concatenation. The application’s compiler JDK can differ from the running JDK.

A concatenation passed into a method or constructor, or used as a method receiver, can use host assembly when each operand is a constant without unpaired surrogates, an integral value other than a dynamic `char`, a boolean, or null. Other concatenations at that boundary use compilation to preserve UTF-16 code units. Display-only concatenation keeps its existing formatting. A pattern binding of a debugger-created String constant or concatenation goes to compilation if it flows into target code. Pattern bindings of existing target references still evaluate their scrutinee once. An invoking plan binds target String methods in place of read-only String intrinsics that rely on debugger-side text transfer.

INVOKE snapshots the frame before acquiring one invocation lease. JDI runs each call with `INVOKE_SINGLE_THREADED` and invalidates frames as the thread resumes. The planner resolves invocation-time members and conversions, and routes unsupported lookups and array component checks to compilation in advance. An executing INVOKE plan has no compiler fallback. Target exceptions use the compiled reply format, and interpreter-raised exceptions name the Java exception type.

Created strings, boxes and invocation results are pinned through formatting. The debugger retries a collected debugger-created mirror or a collected result from a repeatable helper such as boxing, primitive `String.valueOf`, or `intern()` up to three attempts. When collection wins the pin race for a user method or constructor, the error identifies collection, counts the completed target calls, and says that re-running the expression repeats them. OpenJDK protects object IDs during an active global suspend. The interpreter reports the handoff failure when a returned object can be collected between the reply and pin.

### Statement blocks

Wrap multiple statements in `{ ...; return value; }`. The interpreter supports local declarations with an explicit type or a precisely typed `var` initializer, plain assignments to those locals, supported method calls as statements, `if` / `else`, nested blocks, and value returns. It checks every branch before execution, including branches that will be skipped. A local's scope starts at its declaration, and its value must be definitely assigned at every read. Local declarations cannot shadow another local, a frame parameter, a mark, or an event binding. Frame locals can be read. Assigning to one uses compilation and changes only the wrapper's parameter copy.

Constant conditions and constant operands of short-circuit tests use compilation. A conditional expression inside a test and a pattern name declared more than once in the block also send the whole block to compilation. For an erased generic result stored in a narrower local, the interpreter checks the declared or inferred type before storing the value. A failed check raises `ClassCastException` before the next statement.

The planner checks that all paths return a value compatible with the wrapper's `java.lang.Object` result, including primitive values, and that no statement follows an unconditional return. Blocks that can fall through use the compiler's existing `null` result on that path. The compiler also handles loops, `try`, `switch`, `throw`, `synchronized`, labeled statements, `break`, `continue`, `yield`, `assert`, local classes, compound assignments, increments and decrements, and writes to fields or array elements. If the planner cannot prove a static check or an expression needs compilation, the entire block is routed to the compiler before execution.

The planner follows block-local assignments to each target argument or receiver. It prepares any required String interning at the source of the stored value and routes unsafe concatenation through compilation before the block starts. A block using only JDI reads and local operations needs no invocation lease. An interpreted block with a call that requires target invocation anywhere in its body needs an invocable thread and takes one lease, even if the call ends up not running. The interpreter snapshots frame values before taking the lease, keeps mirrors pinned through formatting, and executes only the selected branches. It cannot replay the block through the compiler after invoking.

### Compiler fallback

Calls with multiple same-arity candidates, no matching candidate, inapplicable arguments, variable arity or method type parameters use compilation. Unavailable or ambiguous type bindings, uninitialized classes and unsupported constructors can also require it. The compiler handles lambdas, method references, array creation, switch expressions and assignments that fall outside the block subset. AST paths deeper than 256 nodes, literals with unpaired surrogates, String identity comparisons involving potential constants, parameterized casts and type tests, and String intrinsics whose result may have lost a code unit during JDWP transfer also use compilation. A failed fallback includes the interpreter's reason. Breakpoint and logpoint conditions report `CONDITION_ERROR` and treat an error as true.

Set `JDWP_EVAL_ENGINE` to `auto` (default routing), `compiler` (compile every expression), or `interpreter` (run supported reads and calls, or report the refusal). Replies share one value format. `jdwp_diagnose` counts interpreted evaluations with and without target calls, compiled evaluations and frequent fallback reasons. Fast probes and their arming-hit evaluations use the compiled engine.

## 1. Compiled pipeline overview

```
User expression  e.g.  order.getTotal()
  │
  ▼
JdiExpressionEvaluator.evaluate(frame, expression, extraBindings)
  │  1. Build EvaluationContext from the frame (locals + this + extras)
  │  2. Rewrite bare `this`                 → `_this`
  │  3. Rewrite bare field references       → `_this.field` (when safe)
  │  4. Cache lookup
  │     (key = contextSignature + "###" + expression)
  │  5. On miss: generate wrapper class, compile via InMemoryJavaCompiler
  ▼
RemoteCodeExecutor.execute(vm, thread, classLoader,
                           className, bytecode, args)
  │  1. defineClass()  — inject bytecode into the target classloader
  │  2. Class.forName(name, true, classLoader)  — force preparation
  │  3. invokeMethod(INVOKE_SINGLE_THREADED)
  │     — run the static evaluate(...) and return the JDI Value
  ▼
JDI Value  → MCP tool layer for formatting
```

A compilation cache miss can add a permanent class to the target classloader (see [memory-and-references.md](memory-and-references.md) § 5 for the lifecycle). Every invocation runs under an `EvaluationGuard` lease: the connection's `SuspensionSession` admits the thread first, and every recursive hit during the in-VM call is suppressed (see [threading-and-safety.md](threading-and-safety.md) § 5).

## 2. Component responsibilities

### `JdiExpressionEvaluator`

The orchestrator. Builds the evaluation context from a stack frame, generates a wrapper class with a UUID-suffixed name, compiles it, and delegates execution. Owns the compilation cache and the `this`-rewriting logic.

The generated wrapper has this shape:

```java
package mcp.jdi.evaluation;

public class ExpressionEvaluator_<UUID> {
    public static Object evaluate(MyService _this, Request request, int count) {
        return (Object) (request.getData());
    }
}
```

One static method. Its parameters are the visible variables of the frame (locals + `this` + extra bindings such as `$exception` or marks). The body is the user expression, wrapped in `(Object)(...)` to coerce primitives.

### `InMemoryJavaCompiler`

Wraps Eclipse JDT (ECJ) via JSR-199. Output is captured in memory through a custom `MemoryJavaFileManager`, but input source round-trips through a temp directory because JDT's `JavaFileObject` API requires a real path. The temp directory is deleted unconditionally in `finally`.

> **Background — why ECJ and not `javac`?**
>
> Java has had a standardised compiler API since Java 6 — JSR-199 (`javax.tools.JavaCompiler`). Both `javac` and Eclipse's JDT compiler implement it. Either could in principle be used here. We pick ECJ for a practical reason:
>
> `javac`'s implementation lives in the `jdk.compiler` module (Java 9+) or in `tools.jar` (Java 8). To call it programmatically the host JVM must have that module/JAR on its classpath. Adding the module at runtime is awkward — `ToolProvider.getSystemJavaCompiler()` returns `null` if the running JVM was started without `jdk.compiler` available, which is the default for many Spring Boot setups. ECJ, by contrast, is a regular Maven JAR — declare the dependency, get the compiler. No JVM-launch flags required.
>
> ECJ is the same compiler the Eclipse IDE uses for its incremental builds. It is feature-complete for the Java language (matching the spec rather than `javac`'s implementation quirks) and is typically faster than `javac` for small, repeated compilations because it is built for incremental use.

### `RemoteCodeExecutor`

The executor defines the primary wrapper, initializes it with `Class.forName` and calls `invokeMethod`. It checks `vm.classesByName()` first, so cached compilations that reuse the same class name skip the definition.

### `ClasspathDiscoverer`

Discovery starts with the launch classpath and base directory from the same JDWP `VirtualMachine.ClassPaths` reply. JDWP captures these values during agent initialization. A relative launch entry becomes absolute when the target's base directory is an absolute local path and the resolved entry exists on the server's filesystem. Otherwise discovery uses the reported spelling. For each local launch jar, discovery follows the manifest's `Class-Path` depth first in search order. It adds existing files and directories at their first occurrence and skips invalid and non-file URLs. An unreadable jar stays on the classpath and produces a debug log entry. The connection caches this launch walk.

The first compile adds the thread's context loader chain. Compiles add the selected frame's defining loader chain as needed, in first-seen order. Each chain follows `ClassLoader.parent` from child to parent. Discovery uses loader URL paths as reported and applies manifest expansion only to the launch classpath. Entries keep their first position when a later chain contains the same path.

Dispatcher compiles use the inherited `Thread.contextClassLoader` field and the frame's defining loader. Discovery leaves an overridden `getContextClassLoader()` pending for its originating thread. A compiled tool evaluation on that thread can invoke the getter under a lease in its 20-second `InvocationRunner` job, before taking the compiler configuration monitor. Failed or deferred getters keep the field result provisional until the getter succeeds, its thread dies, or the connection ends.

On JDK 17 and 21, standard URL loaders expose `ucp → URLClassPath.path`. The built-in loader's path supplies entries when JDWP provides no launch entries. Only `URLClassLoader` and its subclasses can use the public instance `getURLs()` fallback. This covers overrides that expose separate repositories, including Tomcat's `WebappClassLoaderBase`. Both URL paths use the same JDI field reads and UTF-8 decoding.

Compilation can use partial entries. The next compile retries transient field and public URL accessor failures. An unsupported URL layout keeps its diagnostic for the connection and discovery caches that result by loader ID. Diagnose groups identical class and reason pairs with counts. A missing-type compilation error includes the remote discovery problem. [Mirror lifetimes](memory-and-references.md#9-classpath-loader-and-url-mirrors)

`JdkDiscoveryService` uses the JDWP-reported VM version to select a matching local JDK. Where available, it reads the target's startup Java home through JDI and checks that path locally before searching `JAVA_HOME` and standard installation directories.

### `LocalProjectClasspathProvider` — local-project classpath fallback

Container and custom classloaders can resolve types from nested archives or repositories that their exposed classpath entries do not describe. JDT can then fail to resolve types that the target can load. To plug those gaps, `LocalProjectClasspathProvider` (Spring singleton in the `evaluation/` package) computes a second, additive classpath from the **project directory** and `JdiExpressionEvaluator.configureCompilerClasspath` unions it with the remote-discovered one before handing the result to `InMemoryJavaCompiler.configure`.

The project directory is the directory the agent session runs in. Claude Code starts the MCP server there, so it is `user.dir`. Codex starts plugin servers in the plugin's install directory and reports the session directory with each tool call (`_meta["codex/sandbox-state"].sandboxCwd`, see [diagnostics.md](diagnostics.md) § 8). `ClientWorkspace` records it, and when one was reported the provider scans it instead of `user.dir`. Each attach captures the current directory. If a subsequent evaluation sees a different reported directory, it starts a fresh scan for that directory.

**Three sources, composed in order:**

1. **`JDWP_EXTRA_CLASSPATH` env var** — colon/semicolon-separated (parsed with `File.pathSeparator`) list of jars and class directories. Explicit override for cases the other sources can't reach (e.g. a JAR installed outside the project, or a vendor build artefact).
2. **Filesystem scan** — walks the project directory up to depth 5 looking for `target/classes` and `target/test-classes` directories. Multi-module reactors are covered without the provider needing to parse a `pom.xml`.
3. **Maven `dependency:build-classpath`** — when the root contains a `pom.xml`, runs the goal once from the project directory, preferring its Maven wrapper. Maven expands `${env.JDWP_MCP_CLASSPATH_DIR}/${project.groupId}@${project.artifactId}@${project.version}.cp` per reactor module. The server passes the private JVM temp directory through the child process environment. Deep and external modules and symlinked project paths work the same way. The runner reads regular files recursively without following links and removes the directory after success, failure, or cancellation. A dependency plugin `outputFile` configured in the POM overrides the private destination. Discovery reports a Maven failure if the private directory contains no output file or if an output path contains an unexpanded expression. If the private directory is empty and the working directory contains a literal `${env.JDWP_MCP_CLASSPATH_DIR}` directory, the failure identifies a launcher that dropped the environment and shows the generated directory for manual cleanup. The runner allows up to 180 seconds and captures up to 64 KB of output for failure logs.

**Union order matters.** The merge in `configureCompilerClasspath` is `[remote..., local-only...]` (de-duplicated, host `File.pathSeparator` as the joiner). JDT resolves types left-to-right, so a class present in BOTH a remote entry and a stale local copy binds against the remote definition first — the live target VM always wins on resolution. The local entries are reachable only as a fallback for classes the remote view did not provide.

Each connection starts local discovery on its own `jdi-local-classpath` daemon thread. Disconnect, target exit, and reconnect teardown cancel the departing connection's discovery. A fresh attach or directory change can start a new scan while the old one is still unwinding. Where `setsid` is available, an owned supervisor runs Maven in a private process group and signals its own group at the end of every run, including on timeout or cancellation. On hosts without `setsid`, the runner polls for descendants. A helper reparented between polls may outlive a cancelled or timed-out run, and `jdwp_diagnose` reports that limitation.

The first compiled evaluation or fast-probe arming compile discovers the remote classpath and matching JDK on demand. Compiled dispatcher evaluations can use the remote classpath while the scan runs. If local discovery finishes during the remote classloader walk, publication retries with the ready result using the same remote classpath. The compiler checks the connection, directory, and local result before publishing options and their wrapper cache. If remote-only compilation reports a possibly missing type, the evaluator checks the current workspace for that connection. It retries once with ready entries and discards the retry result if the workspace changes during compilation. A fast probe stays pending for a later hit if discovery is still running or its retry result was superseded. Compiled tool evaluations can wait for discovery for up to 180 seconds. If the tool deadline expires during that wait, `[STILL_RUNNING]` names local discovery and points to `jdwp_diagnose`.

**Limitations.**

- **Source/binary drift.** If the local checkout is on a different commit than the running target, the *remote* definition still wins because of merge order — but any class missing from the remote view binds against whatever the local jar contains. Rebuild locally to align if eval results look stale.
- **Gradle is not supported in v1.** Only Maven layouts contribute via the Maven source; Gradle projects fall back to filesystem scan + env-override only.
- **No targeted-module mode.** All modules detected in the reactor union into one classpath; the provider does not try to match the breakpoint frame back to its owning module.

**Inspection.** The `Local project classpath` block in `jdwp_diagnose` lists the CWD, whether it contains a `pom.xml`, and whether the override is set. It shows discovery progress until the per-source counts (`env-override=N, filesystem=N, maven=N`) and total are available. Maven failures, including executable startup problems and missing, unreadable, or malformed output, appear alongside entries collected from other modules, the environment, and the filesystem. The provider logs timings and source contributions under `[LocalClasspath]`, including captured Maven output on nonzero exit or timeout.

**How to disable.** Don't set `JDWP_EXTRA_CLASSPATH` AND run the agent session in a directory that has no `pom.xml` and no `target/classes` under it (e.g. `/tmp`). With all three sources silent, the merged classpath is identical to the remote-discovered one.

If the compiler has no entries available, the evaluation error names the project directory and the status of discovery. Missing local types can resolve on a later hit once discovery finishes.

### `JdkDiscoveryService`

Locates a local JDK matching the target JVM's major version. JDT needs `--system <jdkPath>` to resolve `java.*` system classes. Search strategy:

1. The target's own `java.home`, if accessible from the MCP server's filesystem.
2. Common per-OS install paths (Adoptium, Oracle, OpenJDK, Zulu on Windows; `/usr/lib/jvm`, `/opt` on Linux).
3. Directory scan of parent paths matching a version-suffix pattern.

JDK validation checks:

- `jmods/` or `lib/jrt-fs.jar` — Java 9+.
- `lib/rt.jar` — Java 8.
- `jre/lib/rt.jar` — bundled-JRE layout.

### `EvaluationGuard`

The lease façade for invocations. Every `invokeMethod` the pipeline makes runs inside `evaluationGuard.lease(thread)`. A target thread is held by one invocation chain — the Java thread running it — which may re-enter (a nested lease ends nothing); any other caller is refused. The outermost lease is admitted by the connection's `SuspensionSession`: the thread must be stopped at an event of its own that a tool has presented, and it is taken out of every other stop that suspends it, so the invocation can run. The JDI event listener suppresses the breakpoint / exception / step / watchpoint events the invoked code raises on a leased thread. Without this, the listener would try to suspend a thread the outer `invokeMethod` is waiting on, producing a cross-thread deadlock.

A lease spans invocations only, never frame reads: JDI invalidates every `StackFrame` of a thread whenever the debugger resumes it, and the lease's own exemption or another stop's release is such a resume. So `evaluate` reads its frame — the locals, `this`, the frame's class loader — before it leases the thread, and leases it only around the remote execution (with its `getSystemClassLoader` fallback). When a resume elsewhere invalidated the frame during those reads, it reads the same frame afresh (the thread's one frame at the same location) and repeats them, at most three times. Full coverage in [threading-and-safety.md](threading-and-safety.md) § 5.

## 3. Design decisions

### UUID-based class naming

Every generated wrapper class gets a fresh UUID suffix: `ExpressionEvaluator_<UUID>`.

A counter scheme would reset to 0 on MCP server restart, but the previous classes persist in the target JVM's classloader (defineClass output is not unloaded by the server). A second evaluation of the "same" expression after a restart would attempt `defineClass` with the same name and hit `LinkageError: duplicate class definition`. UUIDs eliminate this entirely — no collision across restarts, no cleanup needed.

### `Class.forName()` after `defineClass()`

> **Background — what `defineClass` actually does, and why it isn't enough**
>
> `defineClass` is the JVM-level operation that turns a `byte[]` into a `Class<?>` object inside a specific `ClassLoader`. Per JLS § 12.2, the operation performs the *loading* phase only: parse the bytecode, build the internal `Class` metadata, hook it into the loader's namespace. After `defineClass`, the class exists but is not yet usable — its methods do not have callable entry points, its constant pool may not be resolved, its static fields have no storage allocated.
>
> Three more JLS-defined phases must complete before the class can run code:
>
> 1. **Verification** — the JVM checks the bytecode for structural validity (no jumps out of method bounds, type-stack discipline holds, etc.).
> 2. **Preparation** — static fields get their default values, methods get their dispatch slots.
> 3. **Resolution** — symbolic references (to other classes, methods, fields) in the constant pool are resolved to direct pointers, lazily as encountered.
>
> Then *initialization* — running `<clinit>` — happens just before the first use. The JVM is allowed to do verification, preparation, and resolution lazily; the only guarantee is that initialization runs before any code that actually uses the class.
>
> JDI's `methodsByName()` looks for a method's dispatch slot, which is set during preparation. On a class that has only been loaded (via `defineClass`) but not yet linked, the lookup returns empty or throws `ClassNotPreparedException`. The runtime has not yet decided where the method lives.
>
> Forcing initialization via `Class.forName(name, true, loader)` is the JVM-blessed escape hatch: it triggers the full chain of lazy work — verification, preparation, resolution, initialization — and is documented to be safe to call at any time. Once `forName` returns, the class is fully usable, including by JDI lookups.

`ClassLoader.defineClass()` loads the bytecode but does **not** prepare the class. JDI's `methodsByName()` throws `ClassNotPreparedException` (or returns empty) on an unprepared class. The fix:

```java
Class.forName(className, true, classLoader);
```

The `true` flag forces full initialization. This is the JVM's standard lifecycle mechanism and is robust across all JVM implementations.

Alternatives tried and rejected:

- `allMethods()` — accesses all inherited methods, inefficient and unreliable.
- Busy-waiting on `isPrepared()` — race-prone, JDI doesn't guarantee timing.

### Three-level classloader fallback

Finding the right classloader for injection matters — the wrapper class must see the same types the user expression references. The fallback chain:

1. `frame.thisObject().referenceType().classLoader()` — works for instance methods.
2. `frame.location().declaringType().classLoader()` — works for static methods.
3. `ClassLoader.getSystemClassLoader()` invoked in the target VM — last resort for bootstrap class contexts.

### Dynamic proxy unwrapping

When `this` is a Guice / CGLIB / Spring AOP proxy, `thisObject.type().name()` returns something like `RestService$EnhancerByGuice$110706492`. The generated wrapper can't reference this type — it's synthetic and runtime-generated.

Solution: detect the `$$` pattern and walk up the superclass chain to find the real class. Fallback: extract the name before `$$`.

### Non-public type visibility

The compiled evaluator normally uses `mcp.jdi.evaluation`. For a non-public `this` in an addressable package it emits the wrapper into that package, where Java permits access to the type and its package-accessible members. If the target refuses the definition, it retries in the isolated wrapper package. When the compiler builds the initial wrapper signature, frame parameter types unavailable to the wrapper are represented by an accessible supertype or `Object`.

### `this` field auto-rewriting

The compiled evaluator rewrites an unqualified field name to `_this.field` in single-expression mode when the wrapper can access the member and a visible local or binding does not shadow it. This includes public fields on a public `this` and package-accessible fields in the wrapper's own package.

The rewriter is a hand-rolled tokenizer (not regex) that correctly handles:

- String literals (`"name"` is not rewritten).
- Text blocks (`"""…"""`).
- Character literals.
- Qualified references (`obj.field` is **not** rewritten — only bare identifiers).

A naive `\bfield\b` replacement would corrupt string contents. `"name=" + name` with a field `name` would become `"_this.name=" + _this.name`.

### `this` keyword rewriting

Same tokenizer technique for `this` → `_this`. The wrapper's `evaluate()` is a static method — it has no `this`. The original `this` is passed as a parameter named `_this`. The tokenizer ensures identifiers like `myThis` and `thisFoo` are untouched.

### Compilation cache

The cache key includes the wrapper package, the wrapper mode, the ordered frame and binding types and names, and the expression source.

Eviction: full flush at 100 entries. LRU bookkeeping is not worth the complexity when the miss cost (compile + inject) dwarfs the cost of recompiling a few hot entries. A connection reset or a change to the compiler classpath also replaces the cache. Full discussion in [memory-and-references.md](memory-and-references.md) § 6.

### `INVOKE_SINGLE_THREADED`

All JDI method invocations use `INVOKE_SINGLE_THREADED`. This requires the thread to be suspended at a method-invocation event (breakpoint / step / exception / class-prepare) and prevents other threads from running during the invocation. Without it, concurrent thread execution during evaluation can mutate state out from under you. See [threading-and-safety.md](threading-and-safety.md) § 4.

### Compiler source/target version

Dynamically derived from the target JVM's major version: `1.8` for Java 8, the bare number for Java 9+. The `-g` flag is always passed to preserve local variable names in the compiled bytecode.

## 4. Compiled evaluation constraints and edge cases

### Thread must be suspended at an invocation event

`INVOKE_SINGLE_THREADED` requires a thread stopped at a breakpoint, step, or exception event. A thread suspended via `ThreadReference.suspend()` (manual suspension) is **not** at an invocation event — method calls would throw `IncompatibleThreadStateException`. The lease's admission refuses such a thread before anything is invoked and names the reason: a `jdwp_suspend_thread` hold, a bystander of another thread's stop-all, a VM-start stop, or a thread this session did not stop.

### Classpath configuration precedes compiled evaluation

The router reads the selected frame's defining loader before compiler setup and reads the frame again before evaluation because an accessor can resume its thread. Fast probes pass their method's defining loader. Optional context or URL accessor invocations take an invocation lease.

### Injected classes persist in the target JVM

Classes loaded via `defineClass()` remain in the target classloader for the lifetime of that classloader. They are **not** cleaned up per evaluation. The idempotent check in `loadClass` (checking `vm.classesByName` before defining) is what makes cache reuse safe — it returns the existing definition instead of double-defining. The aggregate cost is bounded in [memory-and-references.md](memory-and-references.md) § 5.

### Uploading and defining wrapper bytecode

Primitive byte mirrors are host-side objects. The server uploads the wrapper bytes with a single `ArrayReference.setValues` call and invokes the target classloader to define the class. [Byte-array mirroring](memory-and-references.md#7-byte-array-mirroring--the-on-cost)

### Compiled member access

The interpreter reads private fields and handles supported private calls in expressions and blocks. The navigation interpreter can follow one field path through private fields and package-private classes across package boundaries. The compiled wrapper starts with Java access rules for its package. When ECJ finds eligible visibility errors on JDK 17 or 21, ordinary evaluations, assertions, watchers, breakpoint conditions and logpoints can retry private field reads, method calls and constructors through reflection. Use an explicit receiver such as `this.store` for a frame member whose bare name the wrapper cannot resolve. In a private nested, local or anonymous class, the wrapper binds `this` as a nameable supertype, so use `jdwp_get_fields` or an interpreted read for the class's own fields.

The retry uses ECJ bindings and caches reflected members with the wrapper's defining loader. Values retain their exact types. Statements and void-compatible lambdas can discard the result. Assignment targets, inaccessible types, method references, constructors requiring an enclosing instance, and lambdas whose overload selection could change keep their original access checks.

Type-qualified constants are inlined without class initialization. Expression-qualified constants keep their original diagnostic. A reflected constructor initializes its class before argument evaluation, and reflected calls rethrow the original target exception. Null-receiver errors name the member. Fields check for null immediately, while calls check after evaluating the arguments.

The compiler makes up to four rewrite passes and reports the original diagnostics if compilation still fails. A closed package such as the one containing `String.value` also produces a module note. The executor defines only the primary wrapper, so reaching a separately emitted local or anonymous class can produce `NoClassDefFoundError`. Lambdas use JVM linkage. Fast probes, including fast-breakpoint captures and arming-hit evaluations, use direct Java access rules and retain their ordinary-tool hint.

### Exceptions thrown by evaluated code

A target exception is reported by type and message on one line, such as `Target VM threw exception: java.lang.IllegalStateException: closed`. The message is `Throwable.detailMessage`, read as a field without invoking anything, cut at 300 characters. A message that a class builds in `getMessage()`, such as the JVM's helpful `NullPointerException` text, is not in the field, so the reply has the type only. For `ExceptionInInitializerError`, the report also includes the immediate cause when readable, using `Throwable.cause` or the older `ExceptionInInitializerError.exception` field. The same one-line and 300-character rules apply to the cause's message. `jdwp_to_string` and `EVALUATION_COMPLETED_LATE` report target exceptions the same way.

Fast probes describe a failure inside the target by their own rule: an application exception type is named without its message.

### VMStart suspension

At VMStart the JVM has yet to execute application code. Navigation can inspect marks and statics of loaded, initialized system classes. Interpreted calls, compiled evaluation, `jdwp_to_string` and force-loading require an invocation event later in the run. Set breakpoints at VMStart and resume to reach application frames. [Breakpoint class-prepare promotion](breakpoints.md#3-deferring-a-breakpoint-until-its-class-loads)

### Exception breakpoints on classes not loaded yet start as PENDING

An exception breakpoint on a class that is not loaded yet — JDK classes such as `java.lang.IllegalStateException` or `java.lang.AssertionError` included — returns as `[PENDING]`. The JVM reports the load of every class loaded after the debugger attached, bootstrap classes too, so the breakpoint is promoted by its `ClassPrepareEvent`, before `<clinit>` and so before the first instance can be thrown. Classes the JVM preloads during start-up (e.g. `java.lang.NullPointerException`) are already visible and bind at once. Tool calls do not promote a pending entry by loading its class: the `tryPromotePending` safety net is passive (`findLoadedClass`). Verified on JDK 21 by setting `java.lang.AssertionError` at VMStart with no other breakpoint: it was promoted on the class's `ClassPrepareEvent` and stopped on JUnit's `AssertionFailedError`.

## 5. Watcher integration

Watchers are MCP-side expression bookmarks attached to breakpoints. They use the same evaluation pipeline:

1. `jdwp_attach_watcher(breakpointId, label, expression)` — registers an expression to evaluate when a specific breakpoint hits.
2. `jdwp_evaluate_watchers(threadId, scope?, breakpointId?)` evaluates watchers in the current frame when `scope` is omitted or blank. Pass `full_stack` to check matching breakpoints across the stack.
3. The router plans each watcher. An all-navigation batch runs as a frame inspection. A batch that needs to invoke or compile runs on the invocation worker and uses a fresh frame per watcher.

Watchers are dual-indexed by watcher UUID and breakpoint ID via `WatcherManager`. They are cleared on `jdwp_reset()` and on disconnect/reconnect.

Pass the displayed eight-character ID or full UUID to `jdwp_detach_watcher(watcherId)`. If several watchers match, the reply lists their labels, breakpoints and full IDs so you can pick one.

## 6. Logpoint and conditional-breakpoint evaluation

Logpoints (`jdwp_set_logpoint`), exception logpoints (`jdwp_set_exception_logpoint`), field watchpoints / field logpoints (`jdwp_set_field_breakpoint`, `jdwp_set_field_logpoint`), and conditional breakpoints all use the expression evaluation pipeline:

- **Logpoints** — evaluate the expression on every hit, record the result to event history, and auto-resume. Support an optional condition expression that gates whether the log fires.
- **Conditional breakpoints** — evaluate the condition expression on every hit; the thread stays suspended only if the condition evaluates to `true`.

Both exception tools bind `$exception` for their conditions. Optional throw-site class filters run in the target before an event reaches the expression evaluator. Regular logpoint history shows boxed primitives as values and prints the type for other object results.

Both run on the event dispatcher. Read-only expressions and blocks use the held frame and JDI reads. Interpreted calls and compilation use the event invocation rules. Each hit still pays for the debugger pause and round trips. [Event pipeline](event-pipeline.md) [Breakpoints](breakpoints.md)

### Synthetic bindings

Several breakpoint kinds inject extra named values into the expression scope. Both engines expose these bindings under their `$`-prefixed names.

| Binding       | Available in                                       | Type / Description                                                                                  |
|---------------|----------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `$exception`  | exception breakpoints, exception logpoints         | The thrown `Throwable`. Always non-null.                                                            |
| `$oldValue`   | field breakpoints, field logpoints                 | Value being read or about to be overwritten, including Java null. |
| `$newValue`   | field breakpoints, field logpoints                 | Value about to be written, or null on access events. |
| `$object`     | field breakpoints, field logpoints                 | The instance the field belongs to. Omitted (key absent) for static fields.                          |
| `$fieldName`  | field breakpoints, field logpoints                 | String mirror of the watched field's simple name.                                                   |
| `$mode`       | field breakpoints, field logpoints                 | String mirror — `"access"` or `"modification"` — identifying which direction fired. Useful in BOTH-mode handlers. |

The router supplies these names alongside frame locals. A binding that applies only to some events can be absent. For example, `$object` is only available for instance fields.

User-defined marks (`$label`) flow through the same `extraBindings` map. See [memory-and-references.md](memory-and-references.md) § 4.

## 7. Reentrancy protection (cross-reference)

The `EvaluationGuard` is the key mechanism preventing deadlocks during evaluation. Short summary: every MCP-driven `invokeMethod` path runs inside a lease of its thread; the JDI event listener suppresses a set raised on a leased thread before routing it, and the handlers check again. The full mechanism — one invocation chain per thread, admission by the `SuspensionSession`, why a lease spans invocations only, why `long` for the key, the recursive walkthrough, and the bounded wait for tool invocations — is in [threading-and-safety.md](threading-and-safety.md) § 5.

Covered invocation sites:

- Compiled evaluations from `jdwp_evaluate_expression`, `jdwp_assert_expression` and `jdwp_evaluate_watchers`.
- Compiled conditions and logpoint expressions for line, exception and field events.
- `jdwp_to_string`.
- An overridden `Thread.getContextClassLoader()` or public `URLClassLoader.getURLs()` during compiler classpath discovery.
- Deferred class loading via `Class.forName`.
- Fast-logpoint arming: reading the class file, defining the probe.
