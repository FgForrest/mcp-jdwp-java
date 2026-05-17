# Plan: Marked Instances + Unified Listing/Clearing

## Goal

Add IntelliJ-style "Mark Object" capability: agent assigns a label to a heap
`ObjectReference`, then references it by name (`$label`) inside conditional
breakpoints, logpoint expressions, exception logpoints, and watchers. Object
identity persists across breakpoint hits and is pinned in the target heap so
the label cannot dangle while in use. Also unify the sprawling
`jdwp_list_*` / `jdwp_clear_*` surface into one introspection tool plus one
clear tool with type and substring filters.

## Confirmed decisions

| # | Topic | Decision |
|---|-------|----------|
| 1 | Sigil | Reuse `$` (matches `$exception`, `$oldValue`, …) |
| 2 | Label source | Agent-chosen; user never interacts directly |
| 3 | Stale refs | Pin via JDI `ObjectReference.disableCollection()`; unpin on remove; treat `isCollected()=true` and `ObjectCollectedException` paths as gracefully-stale |
| 4 | VM lifecycle | Clear marks on VMDeath, disconnect, `jdwp_reset` (mirror objectCache) |
| 5 | Error policy | Reject every invalid input with a descriptive message; never silently succeed |
| 6 | Collision | Reject — caller must `unmark` first or use `rename_mark` |
| 7 | Reserved bindings | Centralised `ReservedBindings` constant set; reject any label that collides with an MCP-injected binding |
| 8 | Locals visibility | Surface marks in `jdwp_get_locals` and `jdwp_get_breakpoint_context` |
| 9 | Tool surface | Unify listing/clearing into `jdwp_overview` + `jdwp_clear` with type/filter args; keep narrow tools, mark as legacy in descriptions |

## Architecture: the binding seam

All expression evaluation flows through one method:

```
JdiExpressionEvaluator.evaluate(StackFrame frame, String expression,
                                Map<String, Value> extraBindings)
  — JdiExpressionEvaluator.java:518
```

`extraBindings` are appended to the wrapper class signature as named
parameters (file:line 374-376), with cache keys varying on binding types
(file:line 321). The architecture is unchanged — we only inject more entries
into the `extraBindings` maps at the four existing call sites.

## Reserved binding names

A new `ReservedBindings` static constant holds every name MCP injects today:

| Reserved | Injected at |
|----------|-------------|
| `$exception` | exception logpoints (`JdiEventListener.handleExceptionEvent`) |
| `$oldValue` | field watchpoints |
| `$newValue` | field watchpoints (modification only) |
| `$object` | field watchpoints (instance fields) |
| `$fieldName` | field watchpoints |
| `$mode` | field watchpoints |
| `_this` | every frame eval (rewrites bare `this`) |

Labels are also rejected if they:
- Don't match `[a-zA-Z_][a-zA-Z0-9_]*` (Java identifier rules; the wrapper uses the label as a parameter name)
- Are a Java reserved word (`class`, `if`, `return`, …)
- Conflict with another live mark (caller must `unmark` first)

## Components

### 1. `marks/MarkedInstanceRegistry.java`

```java
@Service
public class MarkedInstanceRegistry {
    private final Map<String, MarkInfo> marks = new ConcurrentHashMap<>();

    // Pin the object's target-heap lifetime when pin=true (default).
    // Throws IllegalArgumentException for invalid label, IllegalStateException for collisions,
    // ObjectCollectedException when the object is already dead.
    synchronized String mark(String label, ObjectReference ref, boolean pin);

    synchronized boolean unmark(String label);                   // unpins + removes
    synchronized boolean rename(String oldLabel, String newLabel);
    @Nullable MarkInfo get(String label);
    List<MarkInfo> list();

    // Returns "$label" -> Value map; drops marks whose ref is collected().
    Map<String, Value> buildBindings();

    synchronized void clearAll();                                // unpin all + drop
}
```

### 2. `marks/MarkInfo.java`

Immutable record exposing the label, JDI `ObjectReference`, pinned flag, and a
`collected()` snapshot for diagnostics.

### 3. `marks/ReservedBindings.java`

Final class holding `RESERVED_LABELS` (without sigil) and a static
`requireValidLabel(String)` helper.

### 4. `marks/package-info.java`

`@NullMarked` declaration mirroring the watchers package.

## Wiring

| File | Change |
|---|---|
| `JDIConnectionService.java` | Inject `MarkedInstanceRegistry` via constructor; call `clearAll()` from `notifyVmDied()` and `cleanupSessionState()` |
| `JdiEventListener.java:885` (`evaluateConditionWithBindings`) | Merge `registry.buildBindings()` into the bindings map; reserved-name collisions resolve in favour of the caller-supplied extras (e.g. `$exception` always wins) |
| `JdiEventListener.java:833` (`evaluateLogpoint`) | Same merge — pass through `evaluateAndFormat` |
| `JdiEventListener.java:622` (`evaluateExceptionLogpoint` body — calls `evaluateAndFormat`) | Same merge |
| `JdiEventListener.java:801` (field-event logpoint via `evaluateAndFormat`) | Same merge |
| `JDWPTools.java:2978, 3027` (`evaluateWatchersCurrentFrame`, `evaluateWatchersFullStack`) | Switch from `evaluate(frame, expr)` to `evaluate(frame, expr, registry.buildBindings())` |
| `JDWPTools.java:441` (`jdwp_get_locals`) | After existing locals dump, append a "── Marked instances visible to expressions ──" footer; empty section omitted |
| `JDWPTools.java:2628` (`jdwp_get_breakpoint_context`) | Same footer after the existing `this` dump |

**Merge policy:** marks are merged into the bindings map FIRST, then the
caller's `extraBindings` are `putAll`ed on top. That guarantees `$exception`,
`$oldValue`, etc. always win over a (hypothetical, validator-blocked)
collision. The validator also blocks creating such marks up front.

## New MCP tools (replacing/augmenting the existing surface)

```java
// Add
@McpTool jdwp_mark_instance(String label, long objectId, @Nullable Boolean pin = true)
@McpTool jdwp_unmark_instance(String label)
@McpTool jdwp_rename_mark(String oldLabel, String newLabel)

// Add (unified)
@McpTool jdwp_overview(@Nullable String types, @Nullable String filter)
@McpTool jdwp_clear(String types, @Nullable String filter, @Nullable Boolean dryRun)

// Mark existing tools as legacy in their @McpTool description
// (do not remove — pre-approved in client configs)
//   jdwp_list_breakpoints
//   jdwp_list_exception_breakpoints
//   jdwp_list_all_watchers
//   jdwp_list_watchers_for_breakpoint
//   jdwp_clear_all_breakpoints
//   jdwp_clear_all_watchers
```

### `jdwp_overview` semantics

```
types  - comma-separated subset of:
           breakpoint, exception_breakpoint, field_breakpoint,
           logpoint, watcher, mark
         When omitted: all types.
filter - substring (case-insensitive). Matches against:
           breakpoints   → className / label
           watchers      → label / expression
           marks         → label / type name
         When omitted: no filter.
```

Output groups by type with counts and per-row brief lines, e.g.:

```
=== Overview ===
Marks (2):
  $cart_42 → Object#140737… (com.example.Cart) [pinned]
  $session → Object#140737… (com.example.Session) [pinned]
Breakpoints (1):
  #3  com.example.CartService:99 (sticky, suspend=all)
Watchers (1):
  [abc12345] cart total — on BP #3 — "cart.getTotal()"
```

### `jdwp_clear` semantics

```
types   - REQUIRED, comma-separated. Same vocabulary as overview.
          Special value "all" clears everything (still explicit).
filter  - same substring rule as overview.
dryRun  - default false; when true, list what would be cleared.
```

Refuses without `types`. Per-type clear delegates to the existing
`BreakpointTracker.clearAll(...)`, `WatcherManager.clearAll()`,
`MarkedInstanceRegistry.clearAll()`. Per-filter clears iterate the matching
entries and call the existing per-id deletion APIs.

## Tests

Unit tests for `MarkedInstanceRegistry`:
- mark/unmark/rename happy path
- identifier validation (empty, leading digit, hyphen, dot, reserved word)
- reserved-binding rejection (every entry in `ReservedBindings`)
- collision rejection
- `disableCollection()` called on mark, `enableCollection()` called on unmark
- `buildBindings()` strips collected refs
- `clearAll()` unpins everything

Mocked `ObjectReference` via Mockito to avoid needing a live VM.

Integration: out of scope for this commit — sandbox flight is a follow-up.

## Skill update

Append a "Marked instances" recipe section to `skills/java-debug/SKILL.md`:
- Identify-and-track workflow ("mark on first encounter")
- Per-instance filtering condition (`session == $watched_session`)
- Cross-frame logpoint usage (`"price=" + $cart_42.getTotal()`)
- Pinning + GC semantics one-liner
- Pointer to `jdwp_overview` instead of the legacy narrow listers

## Implementation order

1. `marks/` package: `ReservedBindings` → `MarkInfo` → `MarkedInstanceRegistry` + `package-info`
2. Unit tests for the registry
3. Wire registry into `JDIConnectionService` (cleanup hooks)
4. Inject bindings at the four evaluation call sites
5. Add five new `@McpTool` methods to `JDWPTools`
6. Update legacy tool descriptions
7. Surface marks in `get_locals` / `breakpoint_context` footer
8. Update skill recipes
9. `./mvnw -pl jdwp-mcp-server clean test`
10. `/code-quality-pipeline` on changed sources
