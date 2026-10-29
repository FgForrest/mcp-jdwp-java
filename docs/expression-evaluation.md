# Expression evaluation

How the server compiles, injects, and executes arbitrary Java expressions against a live target JVM suspended at an invocation event. This is the heaviest subsystem in the codebase. The safety story (recursive-breakpoint protection, the `EvaluationGuard`) is in [threading-and-safety.md](threading-and-safety.md); the memory story (object cache, compilation cache, byte-array mirroring) is in [memory-and-references.md](memory-and-references.md); this chapter focuses on the compile-and-inject pipeline itself.

## 1. Pipeline overview

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

Every successful evaluation produces a permanent class in the target classloader (see [memory-and-references.md](memory-and-references.md) § 5 for the lifecycle). Every recursive hit during the in-VM call is swallowed by the reentrancy guard (see [threading-and-safety.md](threading-and-safety.md) § 5).

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

Three-phase injection: `defineClass` → `Class.forName` → `invokeMethod`. Idempotent — checks `vm.classesByName()` before defining (`evaluation/RemoteCodeExecutor.java:119-123`), so cached compilations that reuse the same class name skip the define step. Without that check, a second `defineClass` with the same name would throw `LinkageError`.

### `ClasspathDiscoverer`

Walks the target VM's classloader hierarchy to collect all JARs. The initial `java.class.path` system property is often incomplete — for example, a Tomcat process only reports bootstrap JARs there. Discovery aggregates from three sources:

1. `System.getProperty("java.class.path")` via `invokeMethod`.
2. `URLClassLoader.getURLs()` on each classloader in the chain.
3. Tomcat `WebappClassLoaderBase.getURLs()` when present.

Each URL is dereferenced to its path string and added to the deduplicated list. The result is cached in `JDIConnectionService.cachedClasspath`. See [memory-and-references.md](memory-and-references.md) § 9 for the allocation behaviour during the walk (transient mirrors, not cached).

### `LocalProjectClasspathProvider` — local-project classpath fallback

The remote classloader walk above is the source of truth, but it does not see everything. Tomcat's `WebappClassLoaderBase`, Spring Boot's `LaunchedClassLoader` / dev-tools `RestartClassLoader`, and arbitrary user-written `URLClassLoader`s hide their JARs from `getURLs()` or expose them only behind reflection — so JDT can fail to resolve types that are clearly visible to the running application. To plug those gaps, `LocalProjectClasspathProvider` (Spring singleton in the `evaluation/` package) computes a second, additive classpath from the **MCP server's own working directory** and `JdiExpressionEvaluator.configureCompilerClasspath` unions it with the remote-discovered one before handing the result to `InMemoryJavaCompiler.configure`.

**Three sources, composed in order:**

1. **`JDWP_EXTRA_CLASSPATH` env var** — colon/semicolon-separated (parsed with `File.pathSeparator`) list of jars and class directories. Explicit override for cases the other sources can't reach (e.g. a JAR installed outside the project, or a vendor build artefact).
2. **Filesystem scan** — walks the CWD up to depth 5 looking for `target/classes` and `target/test-classes` directories. Multi-module reactors are covered without the provider needing to parse a `pom.xml`.
3. **Maven `dependency:build-classpath`** — for each detected module, invokes `./mvnw dependency:build-classpath -Dmdep.outputFile=target/.jdwp-mcp-classpath` (preferring `./mvnw` over `mvn`) and harvests the printed list. Synchronous, capped at 180 seconds, output captured into a 64 KB buffer so failures are diagnosable.

**Union order matters.** The merge in `configureCompilerClasspath` is `[remote..., local-only...]` (de-duplicated, host `File.pathSeparator` as the joiner). JDT resolves types left-to-right, so a class present in BOTH a remote entry and a stale local copy binds against the remote definition first — the live target VM always wins on resolution. The local entries are reachable only as a fallback for classes the remote view did not provide.

**Limitations.**

- **Source/binary drift.** If the local checkout is on a different commit than the running target, the *remote* definition still wins because of merge order — but any class missing from the remote view binds against whatever the local jar contains. Rebuild locally to align if eval results look stale.
- **Gradle is not supported in v1.** Only Maven layouts contribute via the Maven source; Gradle projects fall back to filesystem scan + env-override only.
- **First call is slow, but never on the event thread.** Cold-cache Maven takes 1–3 minutes; the result is memoized for the JDI connection's lifetime and invalidated on the same edges that reset `InMemoryJavaCompiler` (disconnect / first use of a fresh connection). The speculative `prewarmClasspath` — which fires on the JDI event dispatcher thread the instant a breakpoint hits — warms **only** the remote classpath + JDK detection and skips the local provider entirely, so a Maven shell-out can never stall event processing for a user who never evaluates anything. The local fallback (including Maven) is paid lazily on the **first actual evaluation / logpoint / condition**, on the MCP worker thread.
- **No targeted-module mode.** All modules detected in the reactor union into one classpath; the provider does not try to match the breakpoint frame back to its owning module.

**Inspection.** `jdwp_diagnose` shows a `Local project classpath` block listing the CWD, whether a `pom.xml` was found, a per-source breakdown (`env-override=N, filesystem=N, maven=N`), the total entry count, and whether the env-var override is set. The provider also logs every step under the `[LocalClasspath]` prefix — `INFO` for source contributions and timings, `WARN` for Maven non-zero exits / timeouts / malformed env values, `DEBUG` for per-entry tracing and Maven stdout on failure.

**How to disable.** Don't set `JDWP_EXTRA_CLASSPATH` AND launch the MCP server from a directory that has no `pom.xml` and no `target/classes` under it (e.g. `/tmp`). With all three sources silent, the merged classpath is identical to the remote-discovered one.

If both sides come up empty, `configureCompilerClasspath` throws a `JdiEvaluationException` that names the server's CWD and the two recovery levers (restart from a Maven project, or set `JDWP_EXTRA_CLASSPATH`).

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

Per-thread reentrancy guard. Tracks which target-VM threads are currently mid-evaluation so the JDI event listener can suppress recursive breakpoint / exception / step / watchpoint events on them. Without this, the listener would try to suspend a thread the outer `invokeMethod` is waiting on, producing a cross-thread deadlock. Counted, not boolean, so layered call sites (`configureCompilerClasspath` → `discoverClasspath` → `invokeMethod` nested inside an outer `evaluate()`) stack correctly. Full coverage in [threading-and-safety.md](threading-and-safety.md) § 5.

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

The wrapper class lives in package `mcp.jdi.evaluation`. It can only reference public types. When `this` (or a local variable) has a package-private type, `getDeclaredType()` walks up the superclass chain to find the first public ancestor, falling back to `java.lang.Object`.

### `this` field auto-rewriting

Users naturally write `sessions.containsKey(k)` when they mean `this.sessions.containsKey(k)`. The evaluator auto-rewrites bare field references to `_this.field` when:

- The enclosing class (`this`) is public.
- The specific field is public.
- No local variable shadows the field name.

The rewriter is a hand-rolled tokenizer (not regex) that correctly handles:

- String literals (`"name"` is not rewritten).
- Text blocks (`"""…"""`).
- Character literals.
- Qualified references (`obj.field` is **not** rewritten — only bare identifiers).

A naive `\bfield\b` replacement would corrupt string contents. `"name=" + name` with a field `name` would become `"_this.name=" + _this.name`.

### `this` keyword rewriting

Same tokenizer technique for `this` → `_this`. The wrapper's `evaluate()` is a static method — it has no `this`. The original `this` is passed as a parameter named `_this`. The tokenizer ensures identifiers like `myThis` and `thisFoo` are untouched.

### Compilation cache

Key: `contextSignature + "###" + expression`, where `contextSignature` is the concatenated `type name` pairs of all visible variables. Two frames with the same local types/names sharing the same expression hit the same compiled class.

Eviction: full flush at 100 entries. LRU bookkeeping is not worth the complexity when the miss cost (compile + inject) dwarfs the cost of recompiling a few hot entries. The cache is also cleared on every `configureCompilerClasspath` call (new connections may invalidate old bytecode). Full discussion in [memory-and-references.md](memory-and-references.md) § 6.

### `INVOKE_SINGLE_THREADED`

All JDI method invocations use `INVOKE_SINGLE_THREADED`. This requires the thread to be suspended at a method-invocation event (breakpoint / step / exception / class-prepare) and prevents other threads from running during the invocation. Without it, concurrent thread execution during evaluation can mutate state out from under you. See [threading-and-safety.md](threading-and-safety.md) § 4.

### Compiler source/target version

Dynamically derived from the target JVM's major version: `1.8` for Java 8, the bare number for Java 9+. The `-g` flag is always passed to preserve local variable names in the compiled bytecode.

## 4. Constraints and edge cases

### Thread must be suspended at an invocation event

`INVOKE_SINGLE_THREADED` requires a thread stopped at a breakpoint, step, or exception event. A thread suspended via `ThreadReference.suspend()` (manual suspension) is **not** at an invocation event — method calls will throw `IncompatibleThreadStateException`.

### Classpath configuration must precede evaluation

`configureCompilerClasspath(thread)` issues its own `invokeMethod` calls (to discover JARs via the classloader hierarchy). Calling it from inside `evaluate()` would nest JDI invocations, risking deadlocks or `IncompatibleThreadStateException`. The caller is responsible for calling `configureCompilerClasspath` **before** `evaluate`. The reentrancy guard handles the nesting correctly (it is counted), but `ClasspathDiscoverer` needs a thread that is genuinely at an invocation event.

### Injected classes persist in the target JVM

Classes loaded via `defineClass()` remain in the target classloader for the lifetime of that classloader. They are **not** cleaned up per evaluation. The idempotent check in `loadClass` (checking `vm.classesByName` before defining) is what makes cache reuse safe — it returns the existing definition instead of double-defining. The aggregate cost is bounded in [memory-and-references.md](memory-and-references.md) § 5.

### Byte-array mirroring is expensive

`RemoteCodeExecutor.createRemoteByteArray()` mirrors bytecode into the target VM byte-by-byte — one JDWP round-trip per `vm.mirrorOf(byte)` call. This O(n) cost is unavoidable without cooperating native code in the target VM, since `ArrayReference.setValues` requires already-mirrored values. See [memory-and-references.md](memory-and-references.md) § 7.

### Package-private enclosing class blocks field rewriting

When `this`'s declared type is package-private, the wrapper class (in `mcp.jdi.evaluation`) can't reference the type at all. In this case:

- The `this` field auto-rewrite is skipped entirely.
- The `this` parameter is typed as the first public ancestor (or `Object`).
- Users must use `jdwp_get_fields(<thisObjectId>)` to inspect fields instead.

The error message names the field and suggests the workaround.

### VMStart suspension is special

When connecting to a JVM started with `suspend=y`, all threads are suspended at `VMStart` but no thread is at a method-invocation event yet. This means `evaluate_expression`, `to_string`, and `set_exception_breakpoint` (which use `invokeMethod`) cannot work until at least one breakpoint has been hit. Set breakpoints first, then resume, then inspect.

### Bootstrap class exception breakpoints start as PENDING

Exception breakpoints on classes like `java.lang.NullPointerException` may return as `[PENDING]` if the class isn't loaded yet. They auto-promote when any tool that calls `getVM()` runs while a thread is suspended at a method-invocation event. Pair with a regular line breakpoint upstream to ensure promotion.

## 5. Watcher integration

Watchers are MCP-side expression bookmarks attached to breakpoints. They use the same evaluation pipeline:

1. `jdwp_attach_watcher(breakpointId, label, expression)` — registers an expression to evaluate when a specific breakpoint hits.
2. `jdwp_evaluate_watchers(threadId, scope, breakpointId?)` — evaluates all watchers for the current context.
3. The watcher's expression goes through the same `JdiExpressionEvaluator.evaluate()` pipeline.

Watchers are dual-indexed by watcher UUID and breakpoint ID via `WatcherManager`. They are cleared on `jdwp_reset()` and on disconnect/reconnect.

## 6. Logpoint and conditional-breakpoint evaluation

Logpoints (`jdwp_set_logpoint`), exception logpoints (`jdwp_set_exception_logpoint`), field watchpoints / field logpoints (`jdwp_set_field_breakpoint`, `jdwp_set_field_logpoint`), and conditional breakpoints all use the expression evaluation pipeline:

- **Logpoints** — evaluate the expression on every hit, record the result to event history, and auto-resume. Support an optional condition expression that gates whether the log fires.
- **Conditional breakpoints** — evaluate the condition expression on every hit; the thread stays suspended only if the condition evaluates to `true`.

Both are subject to the same constraints: the expression must compile against the frame's visible types, and each evaluation incurs the `invokeMethod` cost. Placing a logpoint inside a tight loop with millions of iterations will be expensive. The listener-side dispatch is documented in [event-pipeline.md](event-pipeline.md) and [breakpoints.md](breakpoints.md).

### Synthetic bindings

Several breakpoint kinds inject extra named values into the expression scope. These bindings appear as ordinary local variables in the wrapper class generated for the expression — reference them with their `$`-prefixed name.

| Binding       | Available in                                       | Type / Description                                                                                  |
|---------------|----------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `$exception`  | exception breakpoints, exception logpoints         | The thrown `Throwable`. Always non-null.                                                            |
| `$oldValue`   | field breakpoints, field logpoints                 | Value before the event — the value being read (access) or about to be overwritten (modification). Omitted when the JDI value is Java-null. |
| `$newValue`   | field breakpoints, field logpoints (modification)  | Value about to be written. Modification events only; absent on access events.                       |
| `$object`     | field breakpoints, field logpoints                 | The instance the field belongs to. Omitted (key absent) for static fields.                          |
| `$fieldName`  | field breakpoints, field logpoints                 | String mirror of the watched field's simple name.                                                   |
| `$mode`       | field breakpoints, field logpoints                 | String mirror — `"access"` or `"modification"` — identifying which direction fired. Useful in BOTH-mode handlers. |

Bindings are passed as the third argument to `JdiExpressionEvaluator.evaluate(frame, expression, extraBindings)`. The evaluator merges them with the frame's visible locals, so an expression like `$oldValue.equals(currentLocal)` resolves both names. Conditional bindings (e.g. `$object` for instance vs static fields) are intentionally **absent from the map** rather than bound to a null sentinel — referencing them on the wrong event kind yields a compile-time error from JDT, not a misleading NPE at evaluation time.

User-defined marks (`$label`) flow through the same `extraBindings` map. See [memory-and-references.md](memory-and-references.md) § 4.

## 7. Reentrancy protection (cross-reference)

The `EvaluationGuard` is the key mechanism preventing deadlocks during evaluation. Short summary: every MCP-driven `invokeMethod` path increments a per-thread depth counter; the JDI event listener checks the counter on every suspending event and suppresses events on guarded threads. The full mechanism — including why `long` not `ThreadReference`, why counted not boolean, and the recursive walkthrough — is in [threading-and-safety.md](threading-and-safety.md) § 5.

Covered invocation sites:

- `jdwp_evaluate_expression`, `jdwp_assert_expression`, `jdwp_evaluate_watchers`.
- Logpoint evaluation, conditional-breakpoint evaluation, exception-logpoint evaluation, field-logpoint evaluation.
- `jdwp_to_string`.
- Classpath discovery (`ClasspathDiscoverer` calls via the evaluator's `configureCompilerClasspath`).
- Deferred class loading via `Class.forName`.
