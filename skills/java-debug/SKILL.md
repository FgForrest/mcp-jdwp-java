---
description: Debug a live Java application via the jdwp-inspector MCP server — breakpoints, runtime state, expression eval, variable mutation, exception-throw-site catches, non-intrusive line/exception logpoints, and field watchpoints (suspend or log on every read/write of a specific field).
when_to_use: |
  A test fails and the assertion message is unhelpful; an exception is buried under wrappers; a value is wrong but you can't tell where it changes; a field gets mutated and you need to know who wrote it; a race / partial-init / off-by-one / edge-case bug; stepping in your head doesn't match runtime. Triggers: "this test is failing", "why is X null/wrong", "who's writing to field Y", "trace this exception", "attach to JDWP", "port 5005/8003/...", "debug the issue".
argument-hint: "[port]"
arguments: port
allowed-tools: mcp__plugin_jdwp-debugging_jdwp-inspector__*
paths:
  - "**/*.java"
  - "**/pom.xml"
  - "**/build.gradle*"
  - "**/build.gradle.kts"
---

# Java Debug

Live debugging of a running JVM via JDWP. Replaces "add a println, re-run, repeat" with: set breakpoint -> hit it -> inspect everything -> mutate state -> resume.

**Use when:**
- A test fails and the assertion message doesn't tell you why
- An exception is buried under several layers of wrapping
- A value is wrong but you can't tell where it changes
- A bug only happens under specific conditions (race, off-by-one, edge case)
- Stepping through code in your head doesn't match what the runtime does

**Don't use when:**
- The bug is clear from code review alone
- You already know the fix and just need to write it
- It's a build/compile failure (not a runtime bug)

## Prerequisites

The target JVM must be running with the JDWP agent. **The port is whatever the developer (or their deployment) chose** — port 5005 is only the convention for build-system test shortcuts. Long-running services very often expose JDWP on a different port (8003, 8000, 9009, …).

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:<port>
```

`suspend=y` blocks the JVM at startup until you attach — use for tests and early-startup bugs. `suspend=n` lets the JVM run freely — use for long-running services where you attach on demand.

**Two attach scenarios:**

1. **Launch-and-debug (tests, reproducers):** start a fresh JVM yourself, usually via one of the quick-launch shortcuts below.
2. **Attach-to-running (services already up):** skip the launch step entirely — go straight to attach. The port follows the resolution priority in the next section.

**Quick launch shortcuts (these all default to port 5005):**
- Maven Surefire: `mvn test -Dtest=<TestClass> -Dmaven.surefire.debug`
- Gradle: `./gradlew test --tests "com.example.MyTest" --debug-jvm`
- Standalone: `java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar`

For build-system-specific gotchas (Surefire `<argLine>` overrides, Gradle `maxParallelForks`, `bootRun`) and the already-running-service workflow: see [references/prerequisites.md](references/prerequisites.md).

## Attach: port resolution

Follow this priority — do not skip steps:

1. **User-specified port** — given in the conversation, or as the `/java-debug $port` argument — use it directly: `jdwp_wait_for_attach(port=<that-port>)`.
2. **No port given** — try the default once: `jdwp_wait_for_attach()` (= `localhost:5005`).
3. **Default failed / nothing listening on 5005** — call `jdwp_diagnose()`. It returns the list of local JVMs with their JDWP ports. Pick the right one (ask the user if more than one is plausible) and retry `jdwp_wait_for_attach(port=<discovered>)`.

Never silently fall back to 5005 if the user specified a port — that's a bug, not a default.

## Core Workflow

Every debug session follows this sequence:

1. **Launch the target** in a separate shell with `suspend=y` — *or skip this step if the JVM is already running with JDWP open*. The JVM blocks until step 2.
2. **Attach** following the port-resolution priority above. `jdwp_wait_for_attach` polls until the JVM is listening, then attaches.
3. **Set breakpoints** at suspected bug locations: `jdwp_set_breakpoint(className, lineNumber)`. Add exception breakpoints or logpoints as needed.
4. **Resume and wait:** `jdwp_resume_until_event()` — releases the JVM and BLOCKS until the next BP/step/exception fires (30s default). Returns the suspended thread info.
5. **Inspect in one call:** `jdwp_get_breakpoint_context()` — returns thread, top frames, locals (incl. `this`), and `this` field dump.
6. **Form a hypothesis,** test it: step through, `jdwp_assert_expression(...)` to check invariants, `jdwp_set_local`/`jdwp_set_field` to mutate state and ask "would the test pass if X were Y?"
7. **Resume to next event** (`jdwp_resume_until_event`) or **disconnect** when done. For sequential scenarios against the same target, use `jdwp_reset` between flights to clear state without dropping the connection.

**On `[TIMEOUT]`:** the response includes a structured diagnostic — *read it*. If your breakpoints are `PENDING` (target class not loaded), the code path is not executing and a larger timeout will not help — verify the entry point or class name. If a pending BP shows `[FAILED]`, the line/class is invalid. If recent events show `LOGPOINT` or `BREAKPOINT_SUPPRESSED` hits, your BP *is* firing but auto-resuming (logpoint or false condition). Do **not** blindly retry with a bigger timeout. Call `jdwp_diagnose()` any time for the same snapshot without resuming — useful as a sanity check before waiting on a long path.

For follow-up investigations against the same target: `jdwp_reset` + new breakpoints, no need to reconnect.

## Debugging Recipes

### "The function shouldn't have changed that"

A value looks correct before a method call and wrong after. The method appears to only do reads.

1. BP at the call site, *before* the suspicious call.
2. `jdwp_evaluate_expression` on the value -> correct.
3. `jdwp_step_over` followed by `jdwp_resume_until_event` (the step resumes the thread; the latch fires when the STEP event lands).
4. `jdwp_evaluate_expression` again -> wrong! The call mutates.
5. Restart, BP at the same site, `jdwp_step_into` to land inside the suspicious method, then a small number of `jdwp_step_over` + `jdwp_resume_until_event` cycles, eval after each — find the exact mutation point. If you find yourself stepping more than ~3 lines, set a breakpoint at the suspect line and resume to it instead.

### "Race / partial init / observable intermediate state"

A field has a value at one read site that doesn't match what was written, or a thread reads a half-built object.

1. BP at the read site (where the wrong value is observed).
2. `jdwp_get_locals` -> find the broken object's ID.
3. `jdwp_get_fields(<id>)` -> see the partially-initialized state.
4. `jdwp_set_field(<id>, "timeout", "5000")` to fix it at runtime.
5. `jdwp_resume_until_event` -> if the test passes now, the root cause is confirmed.
6. Find and fix the actual write order in the source.

### "Exception is buried under wrappers"

Test shows `CompletionException("Async task failed")`, but the real cause is 3 frames deeper.

1. `jdwp_set_exception_breakpoint("java.lang.IllegalStateException", caught=true, uncaught=false)`.
2. If it returns "deferred" (class not yet loaded), **also set a regular line BP somewhere upstream** of the throw. Both BPs must be in place before resuming.
3. `jdwp_resume_until_event`.
4. The line BP hits -> call any inspection tool (e.g. `jdwp_get_locals`) -> this triggers class loading -> exception BP self-promotes from `[PENDING]` to active.
5. `jdwp_resume_until_event` past the line BP.
6. The exception BP catches the throw -> `jdwp_get_stack` shows the **real** root frame, not the wrapper.

### "Trace exceptions without stopping the app"

A long-running service throws something occasionally and you want to see when/where without halting traffic.

1. `jdwp_set_exception_logpoint("java.sql.SQLException", expression="$exception.getSQLState() + \\\": \\\" + $exception.getMessage()")` — `$exception` is bound to the thrown object; the listener auto-resumes after recording.
2. Let the service run. Each throw produces an `EXCEPTION_LOG` entry (or `EXCEPTION_LOG_ERROR` if the expression fails).
3. `jdwp_get_events(50)` to inspect throw locations + evaluated expression results in chronological order.
4. For a pure suspending exception BP without expression evaluation, use `jdwp_set_exception_breakpoint` instead — that tool no longer carries log-only flags.

### "Who is overwriting this field?"

A field has the wrong value at read time and you can't tell which of many code paths wrote it.

1. `jdwp_set_field_breakpoint(className="com.example.OrderState", fieldName="status", mode="modification")` — suspends on every write of the field. Conditions and the `$oldValue` / `$newValue` / `$object` / `$fieldName` / `$mode` bindings narrow the catch.
2. `jdwp_resume_until_event` — the next write to the field suspends the thread at the write site.
3. `jdwp_get_stack` — the caller frame is the culprit.
4. **Want to know the value AND keep going?** Use `jdwp_set_field_logpoint(..., expression="$oldValue + \" -> \" + $newValue")` instead — every write records a `FIELD_LOGPOINT` event with the transition, no suspends. `jdwp_get_events(50)` shows the full history.
5. **Need only one instance?** Pass `objectFilterId=<instance-id from jdwp_get_locals or jdwp_get_fields>` to filter to that one object. Pass `threadFilterId=<thread uniqueID>` to restrict by thread.

### "Field is mutated during `<clinit>` but my BP misses the first write"

Static initializers run the moment the class loads. A line BP fires on the first event after load, but a static-init write inside the class itself happens *before* the class is fully visible.

1. `jdwp_set_field_breakpoint(className="com.example.Config", fieldName="DEFAULTS", mode="modification")` — even when the class hasn't loaded yet, the watchpoint is registered as PENDING.
2. On class load, the watchpoint promotes synchronously inside the ClassPrepareEvent — *before* the loading thread runs `<clinit>`. The first static-init write is caught.
3. `jdwp_get_stack` shows whether the write came from `<clinit>` (static initializer) or a normal call site.

### "Object inside a HashMap is no longer findable"

`map.put(k, v)` then `map.get(k) -> null` even though `k` looks identical.

1. BP before AND after the suspected mutation point.
2. At BP1: `jdwp_evaluate_expression("session.hashCode()")` — remember the value.
3. `jdwp_resume_until_event` to advance to BP2.
4. At BP2: `jdwp_assert_expression("session.hashCode()", "<value-from-step-2>")` — `MISMATCH` confirms the hash drifted.
5. Fix: use immutable keys, or `remove` + re-insert around the mutation.

### "Bug only at large input / specific value"

Test fails at one input, passes at another. Stepping through every iteration is impractical.

**Approach A — conditional breakpoint:**
`jdwp_set_breakpoint("MyClass", 42, "all", condition="i > 100 && items.size() > 50")`

**Approach B — logpoint then conditional:**
1. `jdwp_set_logpoint("MyClass", 42, "\"i=\" + i + \" v=\" + value")`
2. Run the test uninterrupted.
3. `jdwp_get_events(50)` -> find the FIRST iteration where the value goes bad.
4. Set a conditional BP for that exact iteration.

**Approach C — conditional logpoint (best of both):**
`jdwp_set_logpoint("MyClass", 42, "\"i=\" + i + \" v=\" + value", condition="value < 0")` — logs only when the suspicious shape appears.

### "Trace many call sites without stopping"

A value gets set in many places and you want to know which write produced the bad value.

1. `jdwp_set_logpoint(<setter class>, <setter line>, "\"set called with: \" + value")`
2. Run the test.
3. `jdwp_get_events(50)` -> all logpoint hits in chronological order.
4. The last entry before the test fails is the culprit.

### "Track a specific instance across many breakpoints"

You found the interesting object at one breakpoint (a particular `Cart`, `Session`, `User`, etc.) and want to reference it by name later — in conditions, in logpoint expressions, in watchers — even from frames where the variable name is different or absent.

1. At the BP where you spotted it, label it: `jdwp_mark_instance(label="cart_42", objectId=<id from jdwp_get_locals>)`. By default the object is **pinned** in the target heap (`disableCollection`) so the label remains valid across the rest of the session even if the application drops every other reference.
2. Now reference it in any later expression as `$cart_42`. Works in conditional breakpoints, logpoint expressions, watchers, and exception logpoint expressions.
3. List active marks: `jdwp_overview(types="mark")`. They also appear in the "Marked instances visible to expressions" footer of `jdwp_get_locals` and `jdwp_get_breakpoint_context`, so you see them at every stop without an extra call.
4. Done with it: `jdwp_unmark_instance("cart_42")` (releases the pin).

**Per-instance condition** — break only when *this specific* user is being processed:

```
jdwp_set_breakpoint("CartService", 99, condition="user == $watched_user")
```

**Cross-frame logpoint** — log a property of a tracked object from a deep frame where the variable doesn't exist by that name:

```
jdwp_set_logpoint("PaymentService", 42, "\"cart total: \" + $cart_42.getTotal()")
```

**Reserved labels** (will be rejected): `exception`, `oldValue`, `newValue`, `object`, `fieldName`, `mode`, `_this`. Plus any Java keyword. Plus the label of an already-marked object — `jdwp_unmark_instance` or `jdwp_rename_mark` first.

**Pinning caveat:** if you want to observe natural GC of the marked object, pass `pin=false`. The mark then survives in the registry but `buildBindings` will skip it once `isCollected()` returns true; the overview shows it with `[collected — binding will be skipped]`.

### "Same method runs 1000× but I only care about the call after login"

A noisy method fires repeatedly throughout the run; you only want to stop on it within a specific context (after a particular trigger).

1. Set the trigger BP at the context entry: `jdwp_set_breakpoint("LoginService", 42)` — call it BP `#A`.
2. Set the dependent BP at the noisy method, chained to `#A`:
   `jdwp_set_breakpoint("CartService", 99, triggerBreakpointId=A)`
   The dependent comes up disabled and only arms once `#A` fires.
3. `jdwp_resume_until_event` — runs through every pre-login call to `CartService:99` without stopping. As soon as the login flow hits BP `#A`, the dependent is armed (you'll see a `CHAIN_ARMED` event in `jdwp_get_events`).
4. The very next `CartService:99` call after that stops as a normal BP — inspect away.
5. **Sticky default:** once armed, the dependent stays armed for the rest of the session. To catch the *next* run of the flow fresh again, call `jdwp_disarm_until_trigger(<dependentId>)` — this re-engages the chain without rebuilding the BP.
6. **`oneShot=true` mode** is also available — the dependent re-disarms itself after each hit (IntelliJ-style). Use this when you want the noisy BP to fire exactly once per trigger event in a loop.

Chains can be retrofitted to existing BPs via `jdwp_set_breakpoint_dependency(dependentId, triggerId)`, removed via `jdwp_clear_breakpoint_dependency(dependentId)`, and they survive `jdwp_reset` only if the BPs themselves do (reset clears everything). Removing the trigger BP collapses the chain — every dependent gets armed and a `CHAIN_BROKEN` event is recorded.

## Critical Gotchas

- **Expression eval auto-rewrites bare field references** to `_this.field` when the enclosing class and field are both public. For PACKAGE-PRIVATE enclosing classes this is skipped — the error message will tell you to use `jdwp_get_fields(<thisObjectId>)` instead.
- **`set_local` / `set_field` only support** primitives, `String`, and `null`. To mutate a complex object, mutate its individual fields.
- **Exception breakpoints on bootstrap classes** (`NullPointerException`, `IllegalStateException`, etc.) start as `[PENDING]`. They auto-promote when any tool runs while a thread is suspended at a breakpoint. Pair with a regular line BP upstream — see the "Exception buried under wrappers" recipe.
- **VMStart suspension is special.** When connected to a JVM with `suspend=y`, all threads are suspended but no thread is at a breakpoint yet. `evaluate_expression`, `to_string`, and `set_exception_breakpoint` cannot work until at least one BP has been hit. Set breakpoints first, then resume.
- **First `evaluate_expression` is slow** (~1-3s) — the expression compiler discovers the target's classpath lazily. Subsequent evals are fast (cached).
- **Logpoints cost time** — each fires the expression evaluator. Don't put a logpoint inside a tight loop with millions of iterations.
- **Field watchpoints are expensive.** Each access/modification of a watched field traps into the debugger; on a hot field this can dominate target-VM CPU. Use the narrowest mode that answers your question (`modification` is usually enough), and add `threadFilterId` / `objectFilterId` / `condition` to scope the catches. `jdwp_diagnose` reports `canWatchFieldAccess` / `canWatchFieldModification` plus this warning when connected. Pending field BPs registered before class load promote synchronously on `ClassPrepareEvent`, so `<clinit>` writes are caught.
- **Object IDs are session-scoped.** They become invalid after `disconnect` or if GC collects the object. If you see "Object not found in cache", re-fetch via `jdwp_get_locals`.

## When to step vs. when to set a breakpoint

Each step is a JDWP round-trip — slow and token-expensive. Default to a breakpoint at the destination + `jdwp_resume_until_event` whenever you can predict where execution will go next. Stepping wins in three narrow cases:

- **`jdwp_step_into`** — polymorphic dispatch is unclear and you can't tell from source which override will actually run. One call, then go back to inspecting.
- **`jdwp_step_out`** — an exception or early-abort dropped you in a frame you don't care about and finding the right caller line for a breakpoint would be awkward. One call escapes the frame.
- **`jdwp_step_over`** — only for the *single* next statement, when observing a state mutation is faster than predicting it. More than ~3 `step_over`s in a row means "I should have set a breakpoint."

After any step, `jdwp_resume_until_event` blocks until the `STEP` event lands. The step itself only resumes the thread — it does not wait. `threadId` is optional on all three step tools: omitted, it falls back to the thread of the last breakpoint hit.

## Anti-patterns

- **Don't restart the test for every hypothesis.** Use `jdwp_set_local` / `jdwp_set_field` to mutate state in place and resume. If the test passes, your hypothesis is confirmed — no rebuild needed.
- **Don't step over more than ~3 lines in a row.** If you already know which line you want to inspect, put a breakpoint there and `jdwp_resume_until_event`. One round-trip beats N. The same goes for stepping through loop iterations — use a conditional breakpoint or logpoint.
- **Don't catch `Throwable` or `Exception` "to be safe".** Target the specific exception type. Broad exception breakpoints fire on every JDK internal exception — extremely noisy and slow.
- **Don't stop at the first wrapped exception.** The original throw site is almost always more informative. Set an exception BP on the inner type and re-run.

## Inspecting and clearing debug state

Use `jdwp_overview()` for a unified read of every kind of debug state (breakpoints, exception breakpoints, field breakpoints, logpoints, watchers, marked instances) in one call. Filter by type (`types="breakpoint,watcher"`) or by substring (`filter="Cart"`).

To bulk-clear: `jdwp_clear(types="...", filter="...")`. The `types` parameter is required so an empty call cannot wipe everything. To preview a clear safely, call `jdwp_overview` with the same `types`/`filter` first — the matching rows are exactly what `jdwp_clear` would remove. Per-id clears still go through `jdwp_clear_breakpoint(id)` / `jdwp_detach_watcher(id)`.

## First Questions At a New Breakpoint

When you land at a breakpoint and don't know what to look at:

1. `jdwp_get_breakpoint_context()` — one-shot dump: thread + top frames + locals + `this` fields. 90% of the time this is all you need.
2. For each interesting `Object#N` reference: `jdwp_get_fields(objectId)` to drill in, then `jdwp_to_string(objectId)` for a quick view.
3. `jdwp_assert_expression(<expression>, <expected>)` to test "is the state what I expected?" — much terser than evaluating and eyeballing.

**When something isn't working:** call `jdwp_diagnose` first — it returns the MCP server status, the JDWP connection (with last-attempt error if disconnected), and a list of local JVMs with their JDWP ports in a single call. Skips the `ps`/`lsof`/`jps` round-trip. See [references/troubleshooting.md](references/troubleshooting.md) for more.
