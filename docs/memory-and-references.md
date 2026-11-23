# Memory and references

The server holds very little of its own data — kilobytes for the breakpoint registry, megabytes at most for the event history and compilation cache. The interesting memory story is in the **target JVM**: every navigation step, every expression, every byte of injected bytecode lives over there, behind a JDI mirror handle. This chapter is a guided tour of what is allocated where, when it is reclaimed, and what does not get reclaimed.

## 1. JDI `ObjectReference` semantics

> **Background — what a JDWP object ID actually is**
>
> When the JDWP agent inside the target JVM is asked for a reference to an object (say, the result of reading a field of type `Object`), it does not send the object's contents. It sends an opaque integer — the *object ID* — that the agent maps to the real object in a per-session table. From then on, the debugger refers to that object by its ID; any operation ("what type is it?", "give me field F", "call method M") is a JDWP command carrying the ID, and the agent looks it up server-side. The agent is the only side that can dereference an ID.
>
> The same scheme exists for class IDs, thread IDs, method IDs, field IDs, and frame IDs. They are all opaque longs, all minted by the agent, all stable for the lifetime of the corresponding target-VM entity. This is what makes JDWP work over a slow link — no object graph is ever serialised, only the IDs that name what you want to inspect.

The fundamental type to understand is `com.sun.jdi.ObjectReference`. It is **not** a Java reference — it is a *mirror handle* that lives in the server JVM and identifies an object in the target heap. The mirror carries a JDWP-side ID. Every method call on it (`referenceType()`, `getValue(field)`, `invokeMethod(...)`) round-trips through the JDWP socket.

Two consequences shape every API in the codebase:

- **JDI calls are slow.** "Slow" means tens to hundreds of microseconds for local-socket JDWP, dominated by serialization and one OS context switch. A naive walk of a hundred-entry HashMap that touches every key and value can easily cost tens of milliseconds. The smart-collection rendering (see § 8) exists to keep that cost bounded.
- **The target VM can GC the underlying object out from under the mirror.** The mirror handle stays valid in the server, but every method call on it throws `ObjectCollectedException`. The codebase handles this everywhere it matters — see `MarkedInstanceRegistry.java:65-68, 190`, `MarkInfo.java:33-40`, `EvaluationGuard.java:22, 82`.

The escape hatch is JDI's `disableCollection()` — "do not GC the object behind this mirror until I say so". It is precious (it leaks if you forget the matching `enableCollection()`) and the codebase uses it only via the **marks** subsystem.

## 2. The object cache

`JDIConnectionService.java:81`:

```java
private final Map<Long, ObjectReference> objectCache = new ConcurrentHashMap<>();
```

Keyed on `ObjectReference.uniqueID()` — a stable JDWP-side `long` that does not change as long as the target VM keeps the object alive. The cache is what lets the agent type `jdwp_get_fields(26886)` ten tool calls after first encountering object #26886: the server remembers the mirror for the lifetime of the connection.

### When entries are added

Only `cacheObject(ObjectReference)` adds entries (`JDIConnectionService.java:494-498`), and it is called from `formatFieldValue(...)` whenever the value is an `ArrayReference` (line 870) or any other `ObjectReference` (line 880). Every code path that produces a user-visible representation of an object — `jdwp_get_fields`, `jdwp_get_locals`, breakpoint-context dumps, watcher evaluations — funnels through `formatFieldValue`. So **navigation seeds the cache transitively**: when the agent inspects `order`, every field of `order` whose value is an object gets a cache entry too, ready for the next `jdwp_get_fields(<id>)` drill-in.

Regular line, exception and field logpoint output renders boxed primitives as their values and other objects as `Object (type)`. Event history can see many distinct objects during a long-running trace. Keeping those mirrors out of the cache prevents that trace from growing the session’s inspection state.

### When entries are removed

The cache is cleared atomically on:

- **VM death** (`notifyVmDied`, `JDIConnectionService.java:412`)
- **Disconnect** (`cleanupSessionState`, `JDIConnectionService.java:444`)
- **`jdwp_reset`** (`clearObjectCache`, `JDIConnectionService.java:987-989`)

There is **no per-entry eviction**, no size cap, no LRU, no TTL. The cache grows monotonically within a session.

### Is this a leak?

In practice, no — but the bounds depend on the agent. The cache holds references to the small Java *handle* objects on the server side; each entry is on the order of bytes-to-tens-of-bytes. A session that drills into ten thousand distinct objects costs the server JVM well under a megabyte. The underlying target-VM objects, on the other hand, are **not** pinned by being cached (see next section). They can be GC'd, and the next dereference will simply throw `ObjectCollectedException`.

If your agent automation runs in a long-lived session that visits hundreds of thousands of distinct objects, that is a real concern — call `jdwp_reset` between unrelated workloads. For interactive debugging, the monotonic growth is invisible.

## 3. Caching versus pinning — they are different

**Caching does not call `disableCollection()`.** This is worth stating explicitly because the confusion is natural.

- Caching = the server remembers a `Long → ObjectReference` mapping. Costs server-JVM memory only. Target-side GC is unaffected.
- Pinning = the server tells the target "do not GC this object". Costs nothing on the server but extends the lifetime of a target-VM object.

Pinning is **only** done via the marks subsystem (`marks/MarkedInstanceRegistry.java:72`). A merely-cached object can disappear from the target heap between two tool calls; the second tool call sees `ObjectCollectedException`. The code paths that surface object data swallow these exceptions defensively (e.g. `JDWPTools.java:3439`) so a partial GC during a field dump shows up as "this entry collected" rather than a tool failure.

> **Background — `DisableCollection` as a JDWP command**
>
> JDI's `ObjectReference.disableCollection()` maps directly to a JDWP command in the `ObjectReference` command set. The target VM's JDWP agent maintains a *disable count* per object ID. While the count is greater than zero, the JVM's GC root set treats that object as reachable — the agent holds a strong reference internally. `enableCollection()` decrements the count; when it reaches zero, the agent drops its strong reference and the object is GC-eligible again, exactly as it would be without a debugger attached.
>
> A consequence: pinning is not free. Every pinned object reduces the JVM's heap-management flexibility (the GC cannot move or collect it). The implementation typically pins by adding the object to a JNI global-reference set, which is cheap to enter but contributes to the JNI handle table. For a handful of marks this is invisible; for thousands, it can noticeably affect target-VM GC pause times.

## 4. Marks — pinned and named

A **mark** is a labelled, pinned reference to a target-VM object. Registered via `jdwp_mark_instance(label, objectId, pin=true)`; lives in `MarkedInstanceRegistry`.

### Registry shape

`MarkedInstanceRegistry.java:45`:

```java
private final Map<String, Entry> entries = new ConcurrentHashMap<>();
```

Where `Entry` is the private record `(ObjectReference reference, boolean pinned)` at line 231. Labels are validated by `ReservedBindings.requireValidLabel` (`MarkedInstanceRegistry.java:60`; rules at `marks/ReservedBindings.java:68-86`) — letters, digits, underscore, must not collide with JDI-reserved names like `this`.

### Mark lifecycle

`mark(label, ref, pin=true)` (`MarkedInstanceRegistry.java:59-87`):

1. Validate the label.
2. Refuse the mark if the underlying object has already been collected (line 65).
3. Call `reference.disableCollection()` — line 72. This is what makes the mark a **pin**.
4. If `disableCollection` itself throws (some JDI backends don't support it), refuse the mark rather than silently dropping the pin (lines 76-82).

`unmark(label)` (`MarkedInstanceRegistry.java:97-104`) removes the entry and calls `releasePin` → `enableCollection()` (lines 220-229). Best-effort: any exception is swallowed because the VM may already be gone.

`rename(old, new)` (`MarkedInstanceRegistry.java:111-129`) preserves the `Entry` record — and therefore the pin — without an unpin/repin cycle. That matters: if the agent renames a mark in the middle of a long expression evaluation, the underlying object cannot be GC'd between the unmark and the remark.

### Clearing

Marks are cleared on VM death (`JDIConnectionService.java:411`), explicit disconnect (line 443), and any `clearAll()` call. **`jdwp_reset` deliberately does not clear marks** — they survive a reset because the agent set them intentionally and a reset is meant to clear *automatic* state.

### Marks in expressions — `$label` bindings

`MarkedInstanceRegistry.buildBindings()` (`MarkedInstanceRegistry.java:191`) returns a `Map<String, Value>` keyed `"$" + label`. The "$" sigil is a constant at `marks/MarkedInstanceRegistry.java:43`. Entries whose underlying ObjectReference has been collected since the mark are silently skipped (line 190) but stay in the registry so `jdwp_overview` can still surface them with a "(collected)" hint.

The evaluation, assertion and watcher tools pass these marks to the router. Ordinary conditions and logpoints merge in event bindings before routing. The interpreter resolves a mark alongside frame locals, and the compiled wrapper receives marks as method parameters. Reserved names keep marks distinct from bindings such as `$exception` and `$oldValue`.

## 5. Wrapper bytecode in the target — the permanent allocation

Interpreted reads and method calls use existing target classes. String literals and concatenation can create target String mirrors. The interpreter pins those strings, boxed arguments and returned objects through result formatting and releases its pins afterwards.

> **Background — when is a Java class actually unloaded?**
>
> The JVM Specification (JVMS § 12.7) defines the rule: a class may be unloaded only when its defining `ClassLoader` becomes unreachable, at which point every class loaded by it is eligible for unloading too. There is no `Class.undefine()`, no `ClassLoader.unload(Class)` — the language deliberately gives no way to remove an individual class from a live classloader. The reason is that classes are interconnected: linked references between classes, constants pooled across them, instances on the heap whose class metadata must still be intact. Unloading one class while another holds references to it would corrupt the runtime.
>
> So the only way out is collective: garbage-collect the classloader. That happens when no live thread, root reference, or live class holds onto it. Application classloaders (the system one, Spring's, Tomcat's `WebappClassLoader`) live for the entire process lifetime in normal use, which means classes loaded into them stay forever.

Every successful `defineClass` call **permanently adds a class** to a target-VM classloader. Classes are not unloaded by the MCP server. Unloading a class requires its defining `ClassLoader` to become unreachable, which the server cannot orchestrate from the outside.

`RemoteCodeExecutor.loadClass` (`evaluation/RemoteCodeExecutor.java:119-123`) guards this with a `vm.classesByName(name)` check first: if a class with that name already exists, reuse it instead of calling `defineClass` again (which would throw `LinkageError`). This is what makes the in-memory compilation cache safe to reuse — a cache hit does not re-inject the bytecode.

### Bounded by the cache, then leaked forever

The compilation cache holds up to 100 entries (`JdiExpressionEvaluator.java:57`). When it fills, the entire map is cleared in one shot. The next `evaluate()` compiles a fresh wrapper with a brand-new UUID-suffixed name. The **previously injected classes stay loaded in the target classloader** — they are simply never referenced again.

So a long-lived target VM session, with an agent that evaluates many distinct expressions, accumulates `mcp.jdi.evaluation.ExpressionEvaluator_<UUID>` classes in its classloader. Each wrapper is a small class — a few methods, no fields, typically 1–4 KB of bytecode. A session that crosses the 100-entry threshold three times has 300 stranded wrapper classes. The target's class-loader metaspace grows accordingly: order of hundreds of KB to a few MB for normal use, never gigabytes.

If a session does enough churn to make this matter, the answer is to disconnect-and-reconnect (the target's classloader hierarchy persists across reconnects — the wrappers are still there until the JVM dies). For automated agents doing thousands of distinct evaluations, this is a real consideration; for interactive debugging, it is below the noise floor.

### Why UUID names

`JdiExpressionEvaluator.java:578-579` generates a fresh UUID suffix on every cache miss. A counter-based scheme (`Eval_1`, `Eval_2`, …) would race a `LinkageError` on the second session against the same target VM — the counter resets in the server but the previous classes persist in the target. UUIDs make collisions across server restarts effectively impossible. Within a single session, the cache reuses the same UUID for the same `signature ### expression` key, so the second evaluation of the same expression hits the `classesByName` fast path with no re-injection.

## 6. The compilation cache

Only compiled evaluations populate the wrapper cache. Navigation evaluates a fresh snapshot each time.

`JdiExpressionEvaluator.java:69`:

```java
private final Map<String, CachedCompilation> compilationCache = new ConcurrentHashMap<>();
```

Where `CachedCompilation` carries `(className, bytecode)`. The key is `context.getSignature() + "###" + expression` (`JdiExpressionEvaluator.java:557`) — `getSignature()` produces the ordered `(type, name)` list of all visible variables in the frame (locals, `this`, injected `$exception` / `$oldValue` / etc., marks). Two structurally-identical frames evaluating the same expression hit the same compiled class; a difference in even one local's type produces a fresh compile.

### Capacity and eviction

`MAX_CACHE_SIZE = 100` (`JdiExpressionEvaluator.java:57`). When `size() >= 100`, the entire map is cleared in one shot (lines 562-565) and the new entry compiles fresh. The rationale is inline at lines 559-561:

> LRU bookkeeping isn't worth it for a cache whose miss cost (compile + cache) is already orders of magnitude larger than just rebuilding the few entries that get hot again.

A cache miss compiles with ECJ, uploads the wrapper bytecode and invokes the target classloader to define it. Re-doing a hot entry after a flush is cheap relative to maintaining a per-access LRU list under concurrent reads.

### Other clear paths

The evaluator replaces the compilation cache when the compiler is reset for a new connection or reconfigured after merging local classpath entries. An in-flight compilation keeps its original cache reference, so its result cannot repopulate the replacement cache.

## 7. Byte-array mirroring — the O(n) cost

Shipping bytecode to the target requires creating a `byte[]` in the target heap. `RemoteCodeExecutor.createRemoteByteArray` (`evaluation/RemoteCodeExecutor.java:185-211`):

1. `byteArrayType.newInstance(length)` — single round-trip to allocate the array (line 192).
2. **Per-byte loop** calling `vm.mirrorOf(b)` to box each Java `byte` into a JDI `ByteValue` mirror (line 199).
3. `arrayRef.setValues(0, mirrorBytes, 0, length)` — single round-trip to push the whole list (line 203).

The per-byte loop creates host-side mirrors. JDWP sends the entire byte array in the single `setValues` command. A cache hit reuses the wrapper already defined in the target and avoids the upload and definition. [Packet measurement](expression-interpreter-harness.md)

## 8. Smart collection rendering — keeping `jdwp_get_fields` bounded

When the agent calls `jdwp_get_fields(objectId)`, the server dispatches based on the cached object's type (`JDIConnectionService.java:528-536`):

- `ArrayReference` → `getArrayElements` (line 827-847) — up to **100** elements, with `... (N more)` summary beyond.
- A recognised collection type → `getCollectionView` (line 514).
- Anything else → generic field dump (mirror walking the declared and inherited fields, line 580+).

### What counts as a collection

`collectionKind(...)` (`JDIConnectionService.java:168-175`) uses an exact-name allow-list:

```
ArrayList, LinkedList, HashMap, LinkedHashMap, TreeMap, HashSet, LinkedHashSet, TreeSet
```

`ConcurrentHashMap`, `ConcurrentSkipListMap`, `CopyOnWriteArrayList`, custom collections — all fall through to the generic field dump. The rationale is at lines 163-167: smart rendering needs to know the internal layout (the field names and types of the backing structure), which is stable for the JDK's standard collections but not for arbitrary user code or concurrent variants.

### Limits

- `COLLECTION_VIEW_LIMIT = 50` — first 50 entries shown, the rest summarized (`JDIConnectionService.java:51`).
- `MAX_TREE_DEPTH = 64` — depth cutoff for `walkTreeMapInOrder` (`JDIConnectionService.java:63`).
- Array rendering uses a separate cap of 100 (line 827-847).

### How it walks each layout

- **`ArrayList`** — reads internal `elementData` Object[] up to the first 50 slots (lines 616-630). Does **not** pull the entire backing array — just the prefix.
- **`LinkedList`** — walks the `first → next` Node chain, reading `item` per node (lines 633-653). Stops at 50.
- **`LinkedHashMap`** — follows the `head → after` insertion-order chain (lines 681-697).
- **`HashMap`** — scans `table[]` buckets, walks each `next` chain, stops at 50 (lines 701-722).
- **`TreeMap`** — in-order traversal from `root` via `left` / `right`, with depth and count cutoffs (lines 725-731, plus `walkTreeMapInOrder` at 750-776).
- **`HashSet` / `TreeSet`** — locate the backing map field (`map` for `HashSet`, `m` for `TreeSet`) and delegate to `getMapEntries` (lines 798-821).

### Depth control for values

There is **no recursive descent into entry values**. Each key and value goes through `formatFieldValue` (`JDIConnectionService.java:856-885`), which renders nested objects as `Object#N (typename)` and caches them. Drilling in is the agent's responsibility — call `jdwp_get_fields(N)` on the next turn. The depth control is "one level at a time, agent-driven", not a depth-limited recursion. The smart view also appends the raw internal fields of the collection (e.g. `size`, `modCount`, `loadFactor`) at lines 589-595 for completeness.

## 9. Classpath loader and URL mirrors

`ClasspathDiscoverer` reads loader chains for the compiler. URL and collection mirrors are transient and bypass the object cache. The JDI implementation releases their corresponding JDWP IDs lazily.

The connection retains the ordered classpath and successful loader reads. That state allows later compiles to add defining loaders and retry incomplete reads. Disconnect and VM death drop the loader mirrors and classpath. [Expression evaluation](expression-evaluation.md#classpathdiscoverer)

## 10. Memory summary

| Lives in… | What | Bounds | Cleared by |
|---|---|---|---|
| Server JVM | Object cache | Unbounded, monotonic | disconnect / `notifyVmDied` / `jdwp_reset` |
| Server JVM | Compilation cache | 100 entries → full flush | classpath reconfigure / disconnect |
| Server JVM | Event history | 500-entry ring buffer | `jdwp_reset` / disconnect / `jdwp_clear_events` |
| Server JVM | Breakpoint registry | Bounded by agent activity | `jdwp_reset` / disconnect |
| Server JVM | Marks registry | Bounded by agent activity | disconnect / VM death (**not** by `jdwp_reset`) |
| Target JVM | Pinned mark objects | Bounded by mark count | `unmark` / disconnect |
| Target JVM | Injected wrapper classes | Bounded by *total* distinct expressions over VM lifetime | only target-VM restart |
| Target JVM | Transient byte arrays during `defineClass` | Per-injection, GC-eligible after | normal target GC |

The only entry that grows with the lifetime of the target VM, regardless of server resets, is the injected wrapper-class set. Everything else is bounded by either a hard cap, the agent's session, or both.
