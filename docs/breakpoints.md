# Breakpoint system

`BreakpointTracker` is the registry. It owns every active breakpoint, every deferred (pending) breakpoint, every chain-dependency edge, and every piece of metadata (conditions, logpoint expressions) that does not fit in a JDI `EventRequest`. The class is 2,025 lines because there are six different breakpoint kinds and the matrix of `{active, pending} × {line, exception, field}` is most of them. This chapter is the map.

> **Background — what a breakpoint is, from the JVM's point of view**
>
> JDI's `BreakpointRequest` is not a special data structure inside the JVM; it is a JDWP command (`EventRequest/Set`) that registers a *location filter* with the JVM's debug agent. A location in JDWP is a `(class ID, method ID, code index)` triple — class, method, and an offset into that method's bytecode. The agent rewrites the bytecode (or uses the JVMTI breakpoint API, depending on the implementation) so that when the program counter reaches that location, the JVM emits a JDWP composite event packet containing a `BreakpointEvent`.
>
> The same shape applies to other kinds. An `ExceptionRequest` registers a *type filter* (an exception class and the `caught` / `uncaught` switches) — the JVM emits an event whenever a matching `Throwable` is thrown. A `ClassPrepareRequest` registers a *class-name filter* and fires whenever the JVM finishes preparing a matching class. An `AccessWatchpointRequest` / `ModificationWatchpointRequest` registers a *field* and fires on every read or write of that field's storage slot, regardless of how the read or write was issued (bytecode, reflection, `Unsafe`).
>
> Crucially, all of these are *requests* — the debugger creates them, configures them, calls `setEnabled(true)`, and remembers the returned `EventRequest` handle. The handle is what gets disabled or deleted later. This codebase wraps the JDI handle plus metadata (condition expressions, logpoint expressions, chain links) in its own info classes, but the underlying JDWP-registered request is always the JDI `EventRequest`.

## 1. Breakpoint kinds

| Kind | Active model | Pending model | Backing JDI request |
|---|---|---|---|
| Line BP | raw `BreakpointRequest` | `PendingBreakpoint` (`BreakpointTracker.java:1701-1743`) | `BreakpointRequest` |
| Exception BP / exception logpoint | `ExceptionBreakpointInfo` wrapping `ExceptionRequest` + `ExceptionBreakpointSpec` (lines 1769-1892) | `PendingExceptionBreakpoint` (lines 1812-1857) | `ExceptionRequest` |
| Field BP / field logpoint | `FieldBreakpointInfo` wrapping 0/1 `AccessWatchpointRequest` + 0/1 `ModificationWatchpointRequest` + `FieldBreakpointSpec` (lines 1903-1986) | `PendingFieldBreakpoint` (lines 1992-2024) | `AccessWatchpointRequest`, `ModificationWatchpointRequest`, or both |

Line and exception "logpoint" variants are not separate models — they piggyback on the same active class with `BreakpointMetadata.logpointExpression` set (`BreakpointTracker.java:1748-1759`; setter at `setLogpointExpression`, lines 517-521). Field logpoints set `FieldBreakpointSpec.logOnly = true` (lines 1911-1946). Conditions live in the same `BreakpointMetadata`, set via `setCondition` (lines 495-499).

### MCP entry points

| Tool | Method | Lines |
|---|---|---|
| `jdwp_set_breakpoint` | `JDWPTools.jdwp_set_breakpoint` | 1811-1935 |
| `jdwp_set_logpoint` | `JDWPTools.jdwp_set_logpoint` | 1938-2019 |
| `jdwp_set_exception_breakpoint` | `JDWPTools.jdwp_set_exception_breakpoint` → `registerExceptionBreakpointInternal` | 2272-2282 / 2319-2409 |
| `jdwp_set_exception_logpoint` | `JDWPTools.jdwp_set_exception_logpoint` → same internal | 2291-2307 / 2319-2409 |
| `jdwp_set_field_breakpoint` | `JDWPTools.jdwp_set_field_breakpoint` → `registerFieldBreakpointInternal` | 2416-2430 |
| `jdwp_set_field_logpoint` | `JDWPTools.jdwp_set_field_logpoint` → `registerFieldBreakpointInternal` | 2437-2456 |
| `jdwp_set_fast_logpoint` / `jdwp_set_fast_breakpoint` | `FastLogpointService.register` / `registerBreakpoint` — service-owned, not in the tracker (§ 5a, § 5c) | — |
| `jdwp_clear_breakpoint` | routes by kind across all six, fast ones first | 2038-2059 |

The funnel pattern is deliberate: every breakpoint kind has one tool method per direction (set / log), and both directions delegate to a single internal registration method. New variants (e.g. a "method-entry" breakpoint) should follow the same shape.

## 2. Synthetic integer IDs

`BreakpointTracker.java:45`:

```java
private final AtomicInteger idCounter = new AtomicInteger(1);
```

Every register-* path calls `idCounter.getAndIncrement()` (lines 195, 372, 553, 629, 696, 739). **Line, exception, and field breakpoints share one monotonic ID space** — there is exactly one `idCounter` per tracker instance.

Stability properties:

- **Never recycled within a session.** Removals leave gaps; `idCounter` is only reset to 1 by `clearAllInMemoryStateLocked` (line 357), which runs in `clearAll` / `reset`.
- **Stable across the lifetime of a breakpoint.** An ID assigned at registration is the same ID the agent uses to clear it, attach a watcher to it, depend on it in a chain, or read it from `lastBreakpoint`.
- **Per-ID stickiness defended on removal.** `triggersFiredAtLeastOnce.remove(id)` and `breakpointMetadata.remove(id)` are called whenever an ID goes away (lines 230, 240, 602, 609, 811, 819). If the int counter ever did wrap (it would take 2 billion registrations), reused IDs cannot inherit ghost state.

### `jdwp_clear_breakpoint` — routing by kind

The clear tool tries each registry in priority order (`JDWPTools.java:2038-2059`):

0. Fast logpoint or fast breakpoint — `FastLogpointService.clear` (§ 5a, § 5c), which also removes the breakpoint's watchers.
1. Line BP — `getBreakpoint` / `getPendingBreakpoint`.
2. Exception BP — `getAllExceptionBreakpoints` / `getAllPendingExceptionBreakpoints`.
3. Field BP — analogous accessors.

Only one match is possible because IDs are globally unique. Cascade-breaking of dependents (see § 6) runs **before** removal so `CHAIN_BROKEN` history entries appear in the right order.

## 3. Active versus pending — deferred breakpoints

> **Background — the JVM class lifecycle and why `ClassPrepareRequest` fires when it does**
>
> The JLS (§ 12.2–12.4) defines four phases a class goes through: **loading** (bytecode read), **linking** (which itself decomposes into *verification*, *preparation*, and *resolution*), and **initialization** (running `<clinit>`, the static initializer). The JVM is allowed to do these phases lazily — preparation can happen any time between loading and the first use; initialization happens just before the first instance, first static-field write, etc.
>
> JDI's `ClassPrepareRequest` fires at the boundary between linking and initialization — once the class is fully linked (verified and prepared) but before `<clinit>` runs. That is the earliest moment the JVM has settled the class enough to install breakpoints on it: methods have bytecode offsets, fields have storage slots, the constant pool is resolved. From the debugger's perspective, the class "becomes addressable" here.
>
> The timing matters for one specific case: field watchpoints. The very next thing the JVM does after delivering the `ClassPrepareEvent` is run `<clinit>`. Any static-field write inside `<clinit>` happens *immediately* after preparation. If the watchpoint is installed asynchronously, the static-init writes are lost. The synchronous-promotion path documented in § 3 below is what catches them.

A breakpoint is *pending* when the agent set it on a class that hasn't loaded yet. The tracker keeps two separate registries (active and pending) for each kind so the two states don't get confused, and so `jdwp_overview` can render them differently.

### Pending registration

The line-BP path is canonical; the others follow the same shape. `JDWPTools.java:1856-1898`:

1. Tool tries to resolve the class via `findOrForceLoadClass`.
2. If the class is not loaded and cannot be force-loaded, the tool calls `registerPendingBreakpoint` and attaches condition / logpoint / chain metadata.
3. The tool creates a `ClassPrepareRequest` filtered by the class name with policy `SUSPEND_EVENT_THREAD`. Only one CPR per class (`hasClassPrepareRequest` check at line 1866; registry on `BreakpointTracker.java:66, 445-462`).
4. Race guard: re-check `classesByName` after CPR registration, in case the class loaded between the original lookup and the CPR enable.

### Promotion path 1 — `ClassPrepareEvent`

The primary driver. `JdiEventListener.handleClassPrepareEvent` (`JdiEventListener.java:979-1072`):

- Line BPs — lines 1001-1030.
- Exception BPs — lines 1032-1049.
- Field BPs — lines 1056-1058, delegating to `BreakpointTracker.promotePendingFieldsForClass` (`BreakpointTracker.java:966-989`).

**Field-BP promotion is synchronous inside the CPR handler.** This is load-bearing: the loading thread auto-resumes right after the handler returns and runs `<clinit>`. A static-initializer write would be lost if the watchpoint were installed asynchronously. The synchronous path uses `setSuspendPolicy(SUSPEND_EVENT_THREAD)` on the CPR so the loading thread is parked until promotion completes.

After promoting, the CPR is deleted if no pending entries still reference the class (lines 1060-1067) — to keep the CPR registry from growing forever.

### Promotion path 2 — `tryPromotePending` safety net

Called from `JDIConnectionService.getVM()` on every MCP tool call (`BreakpointTracker.java:843-955`). Catches bootstrap classes that loaded before any debugger event was delivered — for example, classes preloaded by the JVM during VM-start, where the CPR was set after they were already prepared.

Idempotent: a class already promoted via path 1 is a no-op here.

### Promotion failures

When a pending entry cannot be promoted (e.g. the line number doesn't exist in the loaded class, the field is missing or the wrong kind, the exception class is not a `Throwable`), `recordPromotionFailure` (`JdiEventListener.java:957-968`):

- Records a `BP_PROMOTION_FAILED` event with the reason.
- Sets `PendingBreakpoint.failureReason` so the failed entry stays visible in `jdwp_overview` with the explanation.

Pending entries are not auto-removed on failure — the agent gets to see why the deferred BP didn't take. Removal is explicit via `jdwp_clear_breakpoint`.

### Deferred state does not survive reconnect

`JDIConnectionService.cleanupSessionState` (called from both `connect` and `disconnect`) runs `breakpointTracker.reset()`, which clears every map including pending ones. Each session starts fresh — by design, because the target VM may have changed and the pending classnames may resolve to different code.

## 4. Conditions

`BreakpointMetadata.condition` is a Java expression string. When the BP fires, the listener evaluates it via `evaluateConditionWithBindings` (`JdiEventListener.java:919-949`). Three outcomes:

- **`true`** — proceed with the suspending path (logpoint or normal BP).
- **`false`** — auto-resume. No history entry, no chain effect (the BP didn't meaningfully hit). Documented inline at `JdiEventListener.java:428`.
- **Exception during evaluation** — return `true` (suspend). Fail-safe.

The fail-safe rule deserves emphasis: a broken condition does **not** silently swallow breakpoint hits. The agent gets the stop, with the error in the event details, and can call `jdwp_evaluate_expression` to see why. The alternative — silent resume on a broken expression — is one of the most-feared failure modes in interactive debugging, so we explicitly avoid it.

Conditions accept either primitive `BooleanValue` or boxed `java.lang.Boolean` (lines 927-941). Convenient when the expression calls a Java API that boxes.

## 5. Logpoints — the auto-resume mechanic

A logpoint is a breakpoint that records an evaluated expression to the event history and does not suspend the thread. The JDI request is still created with `SUSPEND_EVENT_THREAD` because expression evaluation needs `invokeMethod`, which needs a suspended thread at an invocation event. The listener evaluates, records `LOGPOINT`, and returns `false`. The loop's `eventSet.resume()` undoes the JDI-level suspension. From the agent's view, the thread never paused.

The flow inside `handleBreakpointEvent`:

```
1. Reentrancy guard (suppress if recursive).
2. setLastBreakpointThread — but only because some handler paths
   still snapshot for chain effects.
3. logpointExpr = metadata.getLogpointExpression()
   condition  = metadata.getCondition()
4. If logpointExpr != null:
     if condition != null && !evaluateCondition() → return false (no log)
     evaluateLogpoint() → records LOGPOINT entry
     applyChainEffectsAfterHit()
     return false
5. (otherwise the plain BP path)
```

Same pattern in:

- Exception logpoint — `handleExceptionEvent` lines 566-582, evaluator at `evaluateExceptionLogpoint` (lines 617-658).
- Field logpoint — `handleWatchpointEvent` lines 737-742, evaluator at `evaluateFieldLogpoint` (lines 799-837).

All three respect the same fail-safe rule: if the log expression fails to evaluate, record a `*_ERROR` entry (e.g. `LOGPOINT_ERROR`) with the failure reason and continue. The thread still auto-resumes.

### 5a. Fast logpoints — evaluation moved into the target

"Does not suspend" above is the agent's view; the thread *is* parked for the whole evaluation — several JDWP round trips per hit, one hit at a time on the single event dispatcher thread, and a method with a JVMTI breakpoint stays interpreted. `jdwp_set_fast_logpoint` (package `fastlogpoint`) removes the debugger from the per-hit path. It changes the running application's bytecode, so the tool refuses to arm without `userApproved=true` and returns a risk briefing instead (`FastLogpointRiskBriefing`).

Lifecycle (`FastLogpointService`):

1. **Register** — a one-shot arming breakpoint (`SUSPEND_EVENT_THREAD`, `addCountFilter(1)`, so concurrent hits on other threads are filtered in the target) at the line's first location. ID drawn from the shared space (`BreakpointTracker.allocateId`). The class must be loaded.
2. **Arm on the first hit** — the listener hands the event to `handleArmingHit` *after* every other event of the set (arming redefines the class, replacing the requests behind those events). While the one thread is parked, under the `EvaluationGuard`:
   - the hit itself is evaluated through the normal debugger path (it runs the old code) and recorded with `armingHit=true`;
   - the class file is fetched through `Class.getResourceAsStream` in the target and compared, method by method, with `Method.bytecodes()` of the live class (`ClassFileMethods`). Any difference — JaCoCo, the Mockito inline mock maker, an enhancer, a different class file — refuses the arm: redefining from the class file would silently strip those changes;
   - the probe is compiled (`JdiExpressionEvaluator.compileFastLogpointProbe` → `FastLogpointProbeSource`), defined in the **declaring class's own loader** and package, and initialized — **disabled** (`ENABLED=false`); `RemoteCodeExecutor.defineAndInitialize` checks that the loader resolves the probe's name to that class, so the injected call cannot fail to link at the application's call site;
   - `ProbeInstrumenter` (ASM tree API) inserts `xLOAD`s of `this` and the visible locals plus `invokestatic Probe.hit(...)` after the label / line number / stack-map frame of the line's first instruction, and `redefineClasses` installs it;
   - the server reads back what HotSpot installed — `Method.bytecodes()` of every method (the **installed snapshot**) and, through the live constant pool, which probes the code calls (`ProbeCallScanner`) — writes an install record into the target (§ 5b) and only then sets `ENABLED=true`. A failure after the redefinition leaves the logpoint listed as `DISABLED` (installed, not evaluating), never as "not modified".
3. **Hits** — the probe checks its `ENABLED` kill switch, evaluates the condition and expression, catches every `Throwable` (recorded as an `E`/`C` entry), and appends one immutable string `seq␀millis␀nanos␀kind␀thread␀value` to a power-of-two `String[]` ring claimed with `AtomicLong.getAndIncrement`.
4. **Read** — `jdwp_get_events` first calls `drainAll`: one static-field read of `SEQ`, one `ArrayReference.getValues` of the new slice, one `StringReference.value()` per entry. No thread is suspended, nothing is invoked. The per-entry sequence number tells ready / overwritten / still-being-written slots apart (`ProbeRing`); losses surface as `FAST_LOGPOINT_DROPPED`.
5. **Disarm** — `jdwp_clear_breakpoint`, `jdwp_reset`, `jdwp_disconnect`, `jdwp_reconnect` and server shutdown (`@PreDestroy`) set `ENABLED=false`, drain one last time (a clear while a thread is stopped in the method waits for its release before it redefines — § 5d), check that the live code still equals the installed snapshot (otherwise someone else redefined the class, and restoring would undo that — refused), and redefine the class from the original bytes plus any remaining probes. A failed restore keeps the logpoint listed as `DISABLED`; clearing it again retries. Teardown is bounded (5 s) because `redefineClasses` on a wedged VM blocks forever; past the deadline the instrumentation is abandoned with a `FAST_LOGPOINT_WARNING` — the next attach lists it (§ 5b). The teardown worker only ever clears the entries it captured, so a late one cannot clear a later session's logpoint that reuses an ID. VM death only forgets the state — the classes died with the VM.

**Breakpoints survive redefinition.** JDI's `redefineClasses` deletes every `BreakpointRequest` located in the redefined class. Before each redefinition the service snapshots the tracked line breakpoints in the class (and other pending arming requests), then re-creates them on the new bytecode at the same line and method with the same suspend policy and enabled state, swapping them in with `BreakpointTracker.replaceBreakpointRequest` so the ID, condition, logpoint expression and chain edges stay attached.

Every instrumentation is computed from the class's original bytes plus the full set of probe sites in it, so arming and disarming never compound. The guard for the next change compares live code with the *installed snapshot*, not with the bytes sent: HotSpot renumbers constant-pool entries during a redefinition when the new pool is not an extension of the old one, and rewrites the operands in the installed code.

Known limits: within one session the checks use JDI's cached view, which JDI refreshes only after its own `redefineClasses` — a redefinition by an agent inside the target during the session goes unnoticed until the next attach (an IDE HotSwap cannot happen meanwhile: JDWP admits one debugger). JDWP exposes method code and the constant pool, never exception tables, annotations or other attributes, so only method code is compared.

### 5b. Fast logpoints left behind by a dead server

When the server dies without restoring (SIGKILL, crash) or a teardown times out, the probe keeps running in the target. Each probe therefore describes itself and the class it instruments:

- compile-time constants: `MCP_FORMAT`, the target class / method / descriptor / line, the expression and condition as entered (each capped at 2,048 chars), `MCP_ARMED_AT`, `MCP_ARMED_BY` (`mcp-jdwp-java, session <8 hex>` — no host, no PID), `MCP_ABOUT` (for whoever finds the class in a heap dump);
- `MCP_IMAGE`: the class's **install record** (`GenerationRecord`), a `byte[]` shared by every probe the class calls: the original class file, the probe names the installed code calls, and the installed snapshot. It is rewritten after every redefinition and dropped from a probe once the probe is off and no longer called.

`FastLogpointService.discover` runs after every attach (`jdwp_connect`, `jdwp_wait_for_attach`, the auto-reconnect, `jdwp_reconnect`), after `jdwp_reset`, and from `jdwp_overview` when the previous run did not finish. It is read-only and bounded by 5 s; the reads hold no lock. For every probe class (`McpFastLogpoint_*`) it reads the constants, resolves the target in the probe's own class loader, decodes which probes the target's live code calls, and classifies (`LeftoverClassifier`):

| found | listed as | offered |
|---|---|---|
| called by a class whose code equals the install record, which names exactly the probes the code calls | `RECOVERED` | disable; restore with the user's approval |
| called, but the record is missing, unreadable, ambiguous or does not match the live code | `STRANDED` | disable only |
| not called, still enabled (a thread running the pre-redefinition method can still call it) | `STRANDED` | disable, which retires it |
| not called and disabled | not listed — inert | — |

Fast breakpoints (§ 5c) leave probes too (`McpFastBreakpoint_*`, `MCP_KIND = "BREAKPOINT"`; a probe without `MCP_KIND` is a fast logpoint) and are classified the same way; a probe of an unknown kind is `STRANDED`, with only its kill switch offered.

Leftovers get IDs from the shared space and are drained from their write position at discovery (entries written before are not replayed; the overview shows an upper bound of how many the ring may still hold). They are never owned by the session: disconnect, reconnect, target switch and shutdown leave them alone. `jdwp_clear_breakpoint` / `jdwp_clear` / `jdwp_reset` only set their kill switch. `jdwp_restore_fast_logpoints(id, userApproved)` redefines the class with the recorded original — removing every leftover in it — and follows the arming consent protocol: without approval it returns a briefing of what is removed and what was and was not verified (the recorded original is the class file the loader served at arming time, whose method code then matched the live class). A class with leftovers refuses new fast logpoints until they are restored.

### 5c. Fast breakpoints — the condition moved into the target

A conditional breakpoint on a hot line costs the same as a logpoint (§ 5a): every hit, true or false, is a JDWP round trip on the single dispatcher thread, and the method stays interpreted — HotSpot never JIT-compiles a method that holds a JVMTI breakpoint. `jdwp_set_fast_breakpoint` evaluates the condition in the target and involves the debugger only when it holds. It is the same machinery as a fast logpoint — `FastLogpointService`, entry kind `Kind.BREAKPOINT`, the same instrumentation, ID space, consent gate (`FastBreakpointRiskBriefing`), teardown and leftover handling — so this section lists only what differs.

**The probe** (`FastBreakpointProbeSource`, `McpFastBreakpoint_*`). The instrumented line calls `hit(locals)`, which evaluates the condition; when it holds, `hit()` calls the empty `stop(int kind, int epoch, String error)`. The ordinary JDI breakpoint sits on `stop()`, with the user's suspend policy (`all`, the default, or `thread`). The user's method therefore holds no breakpoint and stays compiled: the spike measured no difference beyond run-to-run noise (≤ 1 ns per pass) over 480 million passes. The `stop()` request is the service's own (`byStopRequest`), not a `BreakpointTracker` request, and it is created before the probe is enabled.

**Gates.** Two gates decide which hits reach `stop()`, each a generation the server writes (`EPOCH`, `ERROR_EPOCH`) and a claimed generation the probe advances by compare-and-set (`CLAIMED`, `ERROR_CLAIMED`):

- the true-hit gate: with `stopOnce=true` (the default) only the first true hit of a generation stops, however many threads meet the condition at once; with `stopOnce=false` every true hit stops;
- the error gate: a condition that throws or does not yield a boolean stops once per generation; later failures are only counted (`ERRORS`) and recorded in the ring as `FAST_BREAKPOINT_ERROR`.

A generation only grows, and a thread claims the one it read on entry — both generations are read before the condition runs — so a thread that started evaluating before a re-arm can never claim the newer generation. Calling the tool again with exactly the same arguments re-arms the breakpoint: both generations move on (`rearmBreakpoint`), and the ID stays — also while it still waits for its first hit, since an arming hit whose condition failed spent the error gate. Other arguments for the same line are refused ("clear it first"). `stop()` is empty, so a step into it from the stop enters nothing; the probe counts the call (`STOPS`) just before it makes it.

**Arming.** The arming request carries the requested policy. On its hit the condition is evaluated through the debugger, against server-side copies of the gates:

| Arming hit | Effect |
|---|---|
| true, true-hit gate open | This hit is the stop (frame 0 is the user's frame). Nothing is installed: in once mode the entry is spent until re-armed; in every-hit mode a fresh arming request waits for the next hit. |
| condition fails, error gate open | An error stop. Nothing is installed; a fresh arming request. |
| anything else | The probe is installed: compiled, defined, gates written, `stop()` request created, class redefined (subject to § 5d), read back, `ENABLED=true`. |

So a condition that always throws is installed on the second arming hit and never stays on the debugger path. A fast breakpoint's arming hit is handled before a fast logpoint's that arrives in the same event set (both on one line): arming the logpoint first would redefine the class and replace the breakpoint's arming request, losing what may be its only true hit. After a stop the logpoint's arming is deferred (§ 5d).

**The stop.** The listener asks `claimsStopEvent` before `handleBreakpointEvent`. `handleStopHit` reads `kind`, `epoch` and `error` from physical frame 0 (`getArgumentValues`, which needs no local-variable table; read again when a resume elsewhere invalidated the frame) and checks them under the service's monitor: an entry that is gone, or an epoch that is no longer current, makes it a stale stop — `FAST_BREAKPOINT_STALE`, resumed. A stop through a retired request (its breakpoint was cleared) is resumed unpublished. A valid stop is published like a breakpoint hit: the tracker's stop snapshot (thread, ID, reason), then a `FAST_BREAKPOINT` entry at the user's class and line. The stop report reads `fast_breakpoint=N, condition true` or `condition failed: <text>` — or `reason unknown` when the arguments cannot be read; such a stop is published (the fail-safe), and a suppressed one reopens every gate it may have claimed. A request is retired before it is unmapped, so `claimsStopEvent` always knows its events. A stop on a thread inside an MCP-driven evaluation is suppressed (`FAST_BREAKPOINT_SUPPRESSED`) and its gate's generation moved on, so the next hit can stop.

**Logical frames** (`FrameView`). At a fast stop the thread is inside `stop()` and `hit()`. Every tool that takes a frame index, and every printed `#N`, counts from the first frame that is not a probe: frame #0 is the user's method at the line. `jdwp_get_stack` says how many probe frames it hides. The arming, `handleStopHit` and the invocation-eligibility checks stay physical. Steps add an exact class-exclusion filter per loaded probe class (own, leftover and retired) before their count filter, so every kind of step from a fast stop lands on the stop line before its code. On a virtual thread only the top frame's locals can be changed (a JDK 21 limit); `jdwp_set_local` at a fast stop says so and suggests a step first.

**Watchers and dependencies.** Watchers attach to a fast breakpoint that can still stop (`PENDING` or `ARMED`; `FastLogpointService.watcherRefusal`) and survive a re-arm. A fast logpoint never stops, and neither does a leftover or a `FAILED` / `DISABLED` fast breakpoint, so watchers on those are refused. `current_frame` evaluates at logical frame 0; `full_stack` maps each frame's `class:line` to every ID at that line — the tracker's active breakpoints (`getBreakpointLocationMap`) and the service's fast breakpoints (`breakpointIdsByLocation`). Clearing a fast breakpoint removes its watchers at once, even while its class restore waits (§ 5d); `jdwp_reconnect`, which disarms fast breakpoints, drops their watchers. A breakpoint dependency refuses a fast breakpoint or fast logpoint as trigger or dependent: the target decides their hits, so they can neither arm a dependent nor be disarmed.

**Leftovers.** A leftover fast breakpoint (§ 5b) has no `stop()` request in the new session, so `stop()` is an empty call: the gates, the condition, the counters and the error ring still run, but nothing stops. It is never owned or re-armed; clear sets its kill switch, and `jdwp_restore_fast_logpoints` restores its class with the user's approval.

### 5d. The redefinition rule — never under a stopped frame

Redefining a method makes every frame running it obsolete, and JDI cannot read an obsolete frame's locals. Fast logpoints and fast breakpoints therefore never redefine a method in which a thread the user may see stopped has a frame — neither to arm nor to restore (`FastLogpointService.heldFrameIn`).

- **Held threads** come from `SuspensionSession.heldThreads`: a context that is `HELD`, or `QUEUED` / `PROCESSING` in the current generation and able to stop; a `jdwp_suspend_thread` hold; a step in flight; and a suspension the session cannot yet account for (`UNKNOWN_HOLD`, a set raised but not routed). A leased thread's count is in flux, so it is not judged by count — but once its stop was released mid-evaluation (its share handed to the lease), it counts as `UNKNOWN_HOLD` until the evaluation ends. Whether a set can stop is judged at routing (`JdiEventListener.mayStop`): a set made only of logpoints, fast-logpoint arming hits and class prepares never stops, so it holds nothing — otherwise a busy logpoint in the same method would keep arming waiting forever. Such a set never publishes even a handler's fail-safe stop (`SuspensionSession.finish`), since its thread was not counted. The arming set's own context counts only when the set already has a stop.
- **The check** reads the frames of each held thread and blocks on a non-obsolete frame in a *changed* method — one whose probe sites change; the class's other methods are equivalent and are not obsoleted. It runs under a balanced `vm.suspend()` that spans the check and the redefinition, so no thread can stop in between. A thread held only by `UNKNOWN_HOLD` is nearly always a logpoint hit the listener has not routed yet: under the pause, the check waits up to 200 ms, polling every 5 ms, for the listener to route it.
- **Arming** checks twice: once without a pause before anything is compiled (a deferred hit costs no probe class), then authoritatively under the pause. Blocked, it is deferred — `FAST_*_ARMING_DEFERRED`, once per hold — and the arming request is re-created; a probe already defined is kept for the next attempt. An arming hit whose frame is already obsolete (it waited while another arming redefined the method) is lost, not failed: the entry re-arms.
- **Removal.** A blocked clear sets the kill switch, retires the `stop()` request and leaves the entry as `REMOVAL_PENDING` — no longer listed — with `FAST_*_REMOVAL_DEFERRED`; the reply says "disabled now" and names the stopped thread. Pending removals are retried after `jdwp_resume`, `jdwp_resume_until_event`, `jdwp_resume_thread`, from `jdwp_overview` and inside the service's register, clear and drain; one redefinition per class takes every pending entry of the class, recomputed from its current sites, and the releasing tool's reply says "Cleared fast breakpoint N: the original bytecode of X is restored." `jdwp_reset`, disconnect, reconnect and shutdown restore at once: reset releases every stop right after, and the others let go of the VM. A server that dies with a removal pending leaves a disabled probe, which the next attach lists.
- **Leftovers.** An approved `jdwp_restore_fast_logpoints` follows the rule too: under the same pause, a held thread with a frame in one of the restored methods refuses it ("Not restored: … Nothing was changed."), before any kill switch is set. The user releases the thread and calls again.
- **Cost.** One `suspendCount` round trip per target thread, plus `frames()` per held thread. Arming pauses the VM for the authoritative check and the redefinition — about 50–100 ms with some 200 threads. Retries run only while a removal is pending.

## 6. Breakpoint chains

The chain mechanism lets one breakpoint stay disabled until another fires. The data model:

- `TriggerLink(int triggerId, boolean oneShot)` — record at `BreakpointTracker.java:1692-1693`.
- `dependencyByDependent: Map<Integer, TriggerLink>` (line 160) — dependent → its trigger.
- `dependentsByTrigger: Map<Integer, Set<Integer>>` (line 167) — reverse index for fast cascade.
- `triggersFiredAtLeastOnce: Set<Integer>` (line 176) — memory of "this trigger has fired at least once in this session".

### Sticky versus one-shot

- **`oneShot = false`** (default, "sticky") — once the trigger fires, the dependent stays armed for the rest of the session. Use when you want to filter "only count hits after the gate", but observe every hit thereafter.
- **`oneShot = true`** — the dependent self-disarms after firing. Matches IntelliJ's "Remove once hit" behaviour. After it fires, the trigger has to fire again before the dependent re-arms.

To manually re-disarm a sticky chain that already fired, the agent uses `jdwp_disarm_until_trigger` (`JDWPTools.java:2206-2223`), which just calls `setBreakpointEnabledById(false)`.

### Cycle detection

`registerDependency` (`BreakpointTracker.java:1163-1176`) walks the existing graph from `triggerId` toward chain roots up to `dependencyByDependent.size() + 1` steps via `findCyclePath` (lines 1188-1207). A cycle is rejected with `ChainRegistrationException.cycle(path)` (lines 1655-1666) — the exception carries the cycle path so the error message can name the offending IDs.

The same call validates that the trigger ID is still live (closes a TOCTOU window with the boundary check in `JDWPTools.java:2148`).

### Trigger memory across pending → active

The interesting case: a dependent was registered as **pending** (its class wasn't loaded). The trigger fires while the dependent is still pending. The trigger fact is remembered in `triggersFiredAtLeastOnce` (added by `markTriggerFired` at line 1420-1422, called from `applyChainEffectsAfterHit` at `JdiEventListener.java:120`). When the dependent finally promotes, `disarmIfChained` (`BreakpointTracker.java:1394-1407`) checks the set and **leaves the dependent armed** instead of disabling it. The deferred dependent is not penalised for arriving late.

The opposite case is also handled: a brand-new dependent registered via `jdwp_set_breakpoint_dependency` after the trigger has fired does **not** auto-arm (documented at `BreakpointTracker.java:1415-1418`). Explicit user intent overrides the historical fire — if the agent links a chain after the fact, the agent gets the chain it asked for, not whatever the history would imply.

### Chain effects after a hit

`applyChainEffectsAfterHit` (`JdiEventListener.java:116-161`) is what runs after a real, suspending hit (not a logpoint, not a condition-false skip):

- Emit `CHAIN_DISARMED` for any one-shot dependent that just self-disarmed (lines 131-135).
- Emit `CHAIN_ARMED` for any dependent that just got armed for the first time (lines 152-155).
- Mark `triggersFiredAtLeastOnce`.

### Cascade on removal

When a trigger BP is removed, every dependent loses its guard. `cascadeChainBreak` (`JDWPTools.java:2105-2133`) runs **before** the removal so `CHAIN_BROKEN` events appear in the timeline before the removal confirmation. Detached dependents are re-armed via `setBreakpointEnabledById(true)` — they collapse back to plain BPs. The summary distinguishes:

- "Armed unconditionally" — an active dependent, now firing on every hit.
- "Still pending; will come up armed when its class loads" — a pending dependent.

## 7. Field breakpoints — single ID, two halves

> **Background — what a watchpoint catches**
>
> A field watchpoint is installed via JDI's `AccessWatchpointRequest` (for reads) and `ModificationWatchpointRequest` (for writes). At the JVM level, these hook into the field's storage slot — the JVM emits an event on every `GETFIELD` / `GETSTATIC` bytecode for the read variant, and on every `PUTFIELD` / `PUTSTATIC` for the write variant. The hook is at the level of the actual memory access, *not* at the Java-source level.
>
> Consequence: a watchpoint catches every write that goes through the field's actual `PUTFIELD` / `PUTSTATIC` bytecode — the public setter, an internal package-private mutator, a write from another class. What it does **not** catch is a write that *bypasses* that bytecode. A reflective `java.lang.reflect.Field.set(obj, value)` and a `sun.misc.Unsafe.putObject(obj, offset, value)` bottom out in a direct-memory store (the JDK's `UnsafeFieldAccessor`) — they never execute a `PUTFIELD`, so they trip no watchpoint. This is *independent of whether the field is `final`*, and `access` mode is just as blind as `modification`. (Verified live on JDK 17 and 21: a watchpoint fires on the constructor's ordinary assignment, then stays silent while a `Field.set` provably changes the value.) `Unsafe.compareAndSwap*` / `VarHandle` operations on JDK internals are the same story — they route through intrinsics that escape the field-event machinery.
>
> A second, subtler blind spot — **reference vs. contents**: a watchpoint fires on writes to the field's *own slot*, not on mutations of the object the field *points at*. If `config.handler` references a mutable object and something changes that object's internals in place, the reference held in `config.handler` is unchanged — no `PUTFIELD`, no event. Likewise, mutating the backing `byte[]` of a `String` (or the elements of a collection) the field references is invisible to a watchpoint on the field. Distinguish "the field's reference changed" (caught) from "the referenced object's contents changed" (not caught); for the latter, put the watchpoint on a field *of the referenced object*, or bisect with line breakpoints comparing `System.identityHashCode(...)` of the reference across suspect calls.
>
> The JVM-level support also explains the performance warning: a watchpoint on a hot field installs a JVM-side hook that fires on *every* access. For an inner-loop variable read a million times a second, that is a million events per second on the JDWP socket — easily enough to dominate target-VM CPU. Hence the perf hint `jdwp_diagnose` surfaces.

A field BP in `BOTH` mode binds **one** synthetic ID to **two** underlying requests: an `AccessWatchpointRequest` and a `ModificationWatchpointRequest`. The model is `FieldBreakpointInfo` (lines 1903-1986). Mode enum at line 1903: `ACCESS | MODIFICATION | BOTH`.

Invariants:

- `registerFieldBreakpoint` validates at least one of the request slots is non-null (lines 692-694).
- `indexFieldRequest` is called for each non-null slot (lines 699-704), so the lookup `firingRequest → ID` works for either half.
- Removal tears down both halves (`removeFieldBreakpoint` at lines 801-822).
- Chain enable/disable also flips both halves (`setBreakpointEnabledById` at lines 1341-1359). The two requests are treated as one logical breakpoint everywhere except inside the watchpoint event handler, where we still need to know whether this event was an access or a modification.

### Promotion rollback

`promoteSinglePendingField` (`BreakpointTracker.java:1002-1079`) creates the two requests sequentially. If the second creation fails (rare, but possible — JDI can refuse if VM capabilities change), the first one is rolled back. `createdRequests` (line 1036) tracks what was created; the rollback runs at lines 1061-1078 to delete any half-armed request. A half-armed pair would suspend on access but not on modification (or vice versa) — confusing and easy to misdiagnose. The rollback guarantees atomicity.

### Filter wiring

`configureFieldRequest` (lines 1087-1110) applies optional filters:

- `addThreadFilter(threadRef)` — restrict to one thread.
- `addInstanceFilter(objectRef)` — restrict to one specific object instance.

The thread filter is useful for narrowing down "who writes to this static field, but only from the worker pool". The instance filter is the way to watch `order.status` without firing on every other `Order`'s `status`.

## 8. Synthetic bindings on field events

When a watchpoint fires, the expression scope gets five extra bindings injected via `buildFieldEventBindings` (`JdiEventListener.java:776-791`):

| Binding | Value | Notes |
|---|---|---|
| `$oldValue` | `event.valueCurrent()` | Always present |
| `$newValue` | `((ModificationWatchpointEvent) event).valueToBe()` | Modification events only |
| `$object` | `event.object()` | `null` for static fields |
| `$fieldName` | `vm.mirrorOf(fieldName)` | String mirror |
| `$mode` | `vm.mirrorOf("access" \| "modification")` | String mirror |

(The `$` sigil is the same one used by marks. Reserved-binding validation refuses to let a mark collide with these names.)

The bindings flow through `mergeMarkedBindings` (`JdiEventListener.java:891-899`) — which combines them with `MarkedInstanceRegistry.buildBindings()` (per-event names win on collision) — and into the evaluator. The user's condition or logpoint expression sees them as ordinary method parameters in the generated wrapper class.

Conditional presence is **absence in the map** rather than a null sentinel: `$object` is simply not in the bindings for a static-field event. Referencing it in an expression for a static-field event yields a JDT compile error, not a misleading NPE at evaluation time.

The synthetic bindings on **exception** breakpoints follow the same pattern:

- `$exception` — the thrown `Throwable`, always non-null.

## 9. Untracked events

Three edge cases produce events with no matching registry entry:

- **Untracked line BP** (`JdiEventListener.java:396-400`) — `event.request()` is a `BreakpointRequest` but `findIdByRequest` returns null. Log a warning, return `true` (suspend). The agent gets to inspect. No tracker mutation, no history entry.
- **Untracked field watchpoint** (`JdiEventListener.java:707-711`) — same shape, same outcome.
- **Untracked StepEvent** — the listener still deletes the one-shot `StepRequest` defensively (lines 462-464) and runs the normal STEP path. There is no "untracked" classification because step requests are not registered in the tracker — they are inherently one-shot per call.
- **Untracked ExceptionEvent** — `event.request()` is not in the tracker. Both `info` and `firingId` come back null. The handler falls through to the default suspending branch (records `EXCEPTION`, fires latch, returns `true` at lines 587-603) without applying chain effects (`firingId == null` skips them, line 597).

The pattern: **defensive suspend, no chain effects.** An untracked event suggests a registration race or a JDI peculiarity; suspending lets the agent investigate, and skipping chain effects prevents an unmodelled hit from disarming a dependent.

## 10. Suspension policies — JDI level

Each BP-creating tool method picks a JDI-level suspension policy:

- `SUSPEND_ALL` — every thread in the target pauses on hit. Useful when the agent wants to inspect global state consistently.
- `SUSPEND_EVENT_THREAD` (default) — only the firing thread pauses. Other threads keep running. This is what IntelliJ's "Suspend: Thread" does.
- `SUSPEND_NONE` — JDI does not pause anything. Used implicitly nowhere in the tracker because the listener needs `invokeMethod` to be legal, which requires the thread to be suspended.

Line BPs accept all three via the `suspendPolicy` parameter (`JDWPTools.java:1829-1844`, applied at 1885 and 1912). Exception and field BPs are always `SUSPEND_EVENT_THREAD` because every code path that fires them may evaluate a logpoint or condition expression — and that needs a suspended thread.

The JDI-level policy is independent of the listener's "should suspend" decision. The JDI policy determines which threads JDI parks when the event fires; the listener decides whether to call `eventSet.resume()` once it has handled them. Even with `SUSPEND_ALL`, a logpoint hit resumes the set on its way out — so the temporary pause is invisible to the agent.

## 11. Adding a new breakpoint kind

If you find yourself adding a new variant, the shape to follow:

1. **Models** — add an active info class and a pending info class to `BreakpointTracker`. Make them records or near-records; immutability after creation simplifies reasoning.
2. **Registries** — two `ConcurrentHashMap`s on `BreakpointTracker`, one active and one pending. Indexed by synthetic ID and (where applicable) by `EventRequest` for the listener's hot-path lookup.
3. **Register entry points** — one public method per `BreakpointTracker`; let it bump `idCounter` and call into the shared metadata machinery.
4. **Promotion** — if the kind has a deferred form, wire it into `handleClassPrepareEvent` and add a `promotePendingXxxForClass` method on the tracker. Decide whether promotion must be synchronous (any kind that observes static initializer effects must) or can run via `tryPromotePending`.
5. **Listener handler** — new `dispatchByType` branch in the listener loop. Follow the standard shape: reentrancy check → identify firing ID → snapshot/history/latch (suspending) or evaluate/history (logpoint).
6. **MCP tool methods** — one per direction (set/log) on `JDWPTools`, both delegating to a single `registerXxxInternal`.
7. **Chain participation** — opt in by calling `applyChainEffectsAfterHit` from your handler and by accepting `triggerBreakpointId` / `oneShot` parameters in the registration path. Existing chain machinery handles the rest.
8. **`jdwp_overview` rendering** — add the new kind to the rendering switch so the agent can see it.
9. **`jdwp_clear_breakpoint` routing** — add the new registry to the lookup chain.

Fast logpoints (§ 5a) are the exception to this shape: they live in `FastLogpointService`, not in `BreakpointTracker`, borrow an ID with `allocateId`, and are routed first by `jdwp_clear_breakpoint`.

The whole machine is shaped so that new kinds slot in via parallel structure, not by adding special cases to existing kinds.
