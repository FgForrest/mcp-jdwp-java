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

### "Everything's stuck — is it a deadlock?"

Threads hang, the test never completes, or `jdwp_get_threads` shows several threads in `MONITOR` status and you need to know what they're each waiting on.

1. `jdwp_dump_locks()` — one call takes a transient VM-wide snapshot and prints which threads are blocked on a monitor, who holds each one, and any **deadlock cycle** (e.g. `transfer-A-to-B → transfer-B-to-A → transfer-A-to-B`). The suspend/resume is balanced, so a genuine deadlock stays put and a non-deadlocked VM is undisturbed.
2. If a cycle is reported, `jdwp_suspend_thread(id)` a member, then `jdwp_get_stack(id)` to see the exact lock-acquisition line (the two members usually contend the same line in mirror order — the AB-BA signature).
3. No cycle but threads blocked? The hang is a lock held by a *runnable* thread that's slow/looping, or — if `jdwp_dump_locks` shows nothing — the threads are parked in `Object.wait()` or a `java.util.concurrent` Lock, which monitor dumps don't cover. Fall back to `jdwp_get_threads` for `WAIT`-status threads.

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
6. **Drowning in constructor-storm writes before the interesting mutation?** Pass `excludeConstructors=true` — writes inside the declaring class's `<init>` / `<clinit>` are silently dropped (no event, no chain trigger, no suspend), so the BP only fires on post-construction mutations. Use when a field is set by many constructors and you only care about later changes.

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

**When a condition fails to compile or throws, it does not filter — the hit is processed as if the condition had passed (a suspending BP stops; a logpoint records its log and auto-resumes) and a `CONDITION_ERROR` event is recorded.** If you see `CONDITION_ERROR` in `jdwp_get_events`, the condition isn't doing what you think — fix it rather than trusting the hits. The most common cause is a **private field** of `this`: the bare-field auto-rewrite is skipped for non-public fields, so `condition="myPrivateFlag == false"` won't compile. Use the block-form reflection instead:
`condition="{ java.lang.reflect.Field f = this.getClass().getDeclaredField(\"myPrivateFlag\"); f.setAccessible(true); return f.getBoolean(this) == false; }"`

### "Trace many call sites without stopping"

A value gets set in many places and you want to know which write produced the bad value.

1. `jdwp_set_logpoint(<setter class>, <setter line>, "\"set called with: \" + value")`
2. Run the test.
3. `jdwp_get_events(50)` -> all logpoint hits in chronological order.
4. The last entry before the test fails is the culprit.

### "Suspending on every hit changes the bug" — fast logpoint (rare, needs the user's consent)

Every `jdwp_set_logpoint` hit pauses the hitting thread for milliseconds while the server evaluates — even when the condition is false — and hits are processed one at a time. In a race, lock contention, a latency-sensitive path or a hot loop with a rarely-true condition, that pause reshuffles the very interleaving you are trying to see (the suspended thread keeps its monitors; the other threads queue behind it). `jdwp_set_fast_logpoint` evaluates the condition and expression **inside the target JVM** by rewriting the class's bytecode, so hits never suspend.

This is a situational tool, not an upgrade: `jdwp_set_logpoint` stays the default. Use the fast variant only when the per-hit pause would distort the behaviour under investigation.

**Consent protocol — mandatory:**
1. Call `jdwp_set_fast_logpoint(className, lineNumber, expression, condition?)` **without** `userApproved`. Nothing is armed; you get a risk briefing.
2. Relay the briefing to the user **word for word** and ask whether to proceed.
3. Only after the user has explicitly agreed **in this conversation**, call again with the same arguments and `userApproved=true`.
4. Never infer consent ("the user asked me to debug the race" is not consent), never reuse consent given for a different class, line or target VM, and never pass `userApproved=true` on the first call. If the user declines, use `jdwp_set_logpoint`.

**Then:**
- The class must already be loaded (or pass `forceLoad=true`). The next hit of the line arms the probe: that one thread pauses briefly, and that hit is logged through the debugger (`armingHit`).
- Read hits with `jdwp_get_events` — `FAST_LOGPOINT` / `FAST_LOGPOINT_ERROR` entries carry the in-target thread, `seq` and `nanoTime`. `FAST_LOGPOINT_DROPPED` means entries were overwritten before you read them: read more often, raise `bufferSize` (power of two, ≤ 65536) or narrow the condition. `jdwp_overview(types="fast_logpoint")` shows the state and counters.
- `FAST_LOGPOINT_ARM_FAILED` explains a refused arm — most often "the class bytes ... differ from the live bytecode": another agent (JaCoCo, Mockito inline mocks, an enhancer) rewrote the class. That refusal is the safety check working; fall back to `jdwp_set_logpoint`.
- Results render without calling `toString()` on arbitrary objects (`type@hash`); call it yourself in the expression if you need it. Marked-instance labels (`$label`) and private members are not available.
- Threads already inside the method keep running the old code until they call it again — a line inside a method that never returns (a worker's main loop) will not log; put the fast logpoint in a method the loop calls.
- Clear it as soon as you have your answer: `jdwp_clear_breakpoint(id)` restores the original bytecode (so do `jdwp_reset`, `jdwp_disconnect` and server shutdown). Tell the user if a `FAST_LOGPOINT_WARNING` says the bytecode could not be restored — the logpoint then stays listed as `DISABLED` and clearing it again retries.

**Fast logpoints left by an earlier session.** If an attach reply (or `jdwp_overview`, or a `FAST_LOGPOINT_RECOVERED` event) reports fast logpoints left by an earlier debugging session, a previous server died without restoring the class — they keep evaluating in the target. Tell the user what was found (class, line, expression, who armed it when) and **leave them alone unless the user asks**: the earlier investigation may still want them.
- `RECOVERED` — the class is exactly as that session left it. `jdwp_clear_breakpoint(id)` switches it off without touching the bytecode (no consent needed). `jdwp_restore_fast_logpoints(id)` restores the class's original bytecode, removing every leftover in it — it follows the same consent protocol as arming: call it without `userApproved`, relay the briefing word for word, and pass `userApproved=true` only after the user agreed.
- `STRANDED` — the class changed after the probe was installed, or its wiring is unknown. Only `jdwp_clear_breakpoint(id)` (the kill switch) is offered; the injected call stays until the JVM restarts.
- A class with leftovers refuses new fast logpoints until they are restored; use `jdwp_set_logpoint` meanwhile.

### "Track a specific instance across many breakpoints"

You found the interesting object at one breakpoint (a particular `Cart`, `Session`, `User`, etc.) and want to reference it by name later — in conditions, in logpoint expressions, in watchers — even from frames where the variable name is different or absent.

1. At the BP where you spotted it, label it: `jdwp_mark_instance(label="cart_42", objectId=<id from jdwp_get_locals>)`. By default the object is **pinned** in the target heap (`disableCollection`) so the label remains valid across the rest of the session even if the application drops every other reference.
2. Now reference it in any later expression as `$cart_42`. Works in: conditional breakpoints, logpoint expressions, watchers, exception logpoint expressions, **and** `jdwp_evaluate_expression` / `jdwp_assert_expression` (so you can `jdwp_assert_expression("$cart_42.getTotal()", "0")` at any later BP).
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

### "Verify an invariant in one call"

You think the state at a BP should be `X` and want a one-line yes/no instead of an eyeballed `evaluate_expression` result.

```
jdwp_assert_expression(expression="order.getStatus()", expected="CONFIRMED")
→ "OK — order.getStatus() = CONFIRMED"
or "MISMATCH — order.getStatus()  expected: CONFIRMED  actual: PENDING"
```

Cheapest possible verification step. `threadId` defaults to the last breakpoint thread, so chained `jdwp_assert_expression` calls work without re-specifying it. Mark bindings (`$cart_42` etc.) are available here too.

### "Watcher panel — see a fixed set of values on every hit"

Several values are interesting at one BP and you don't want to issue N separate `jdwp_evaluate_expression` calls each time.

1. Attach watchers (one per expression): `jdwp_attach_watcher(breakpointId=1, label="total", expression="order.getTotal()")`, then `(breakpointId=1, label="items", expression="order.getItems().size()")`, ...
2. At each BP hit: `jdwp_evaluate_watchers(threadId, scope="current_frame", breakpointId=1)` — returns every watcher's value (and an inline `[ERROR: ...]` per watcher that fails — others continue). The total line splits succeeded vs errored so partial failures are explicit.
3. `jdwp_list_watchers_for_breakpoint(1)` / `jdwp_overview(types="watcher", filter="...")` to list, `jdwp_detach_watcher(<short-id>)` to remove.

### "The target JVM died / I need to re-run with new breakpoints"

Test ended (VM_DEATH), the surefire JVM was killed for a new run, or the target JVM was relaunched on the same port. You want to continue debugging with **all current breakpoints preserved** — no need to re-set them by hand.

1. Relaunch the target on the same `address=<port>` (e.g. `mvn test -Dmaven.surefire.debug ...`).
2. `jdwp_reconnect()` — disposes the dead VM handle and reattaches to the last known host:port. **Breakpoint specs (line / exception / field), conditions, logpoint expressions, chain edges, watchers, and synthetic BP IDs are preserved** — BP `#7` is still `#7` after.
3. Resume normally: `jdwp_resume_until_event`.

**What's lost on reconnect** — marked instances (`jdwp_mark_instance` labels), the object cache, the last-suspended-thread context, and the classpath-discovery cache. The first `jdwp_evaluate_expression` after reconnect is slow again. Object IDs from the previous session are invalid — re-fetch via `jdwp_get_locals` / `jdwp_get_fields`.

**Don't** `jdwp_disconnect` + `jdwp_wait_for_attach` for this — it works but you lose every BP. Reserve `jdwp_connect` / `jdwp_wait_for_attach` for attaching to a **different** target.

### "Where exactly did the assertion fail?"

A test fails with an unhelpful `AssertionError` message and tears down before you can inspect state. Pin the JVM at the throw site.

1. `jdwp_set_exception_breakpoint("java.lang.AssertionError", caught=true, uncaught=true)` — fires on the assertion itself, before JUnit's reporter wraps it and before the VM tears down.
2. `jdwp_resume_until_event` — lands at the throw frame with the thread suspended.
3. `jdwp_get_breakpoint_context` — full state at the failure point: locals, `this` fields, stack. From here you can `jdwp_evaluate_expression` to test invariants or `jdwp_set_local` / `jdwp_set_field` to try fixes in place.

This is the safest default for "I want to see what the test saw when it gave up." Set it as part of the attach prologue when launching a failing test.

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

- **Expression eval auto-rewrites bare field references** to `_this.field` when the enclosing class and field are both public. For PACKAGE-PRIVATE enclosing classes this is skipped — the error message will tell you to use `jdwp_get_fields(<thisObjectId>)` instead. **If you need to call a method on a non-public peer field** (e.g. `eventBus.getErrorSummary()` where `eventBus` is package-private on `this`), `jdwp_get_fields` only reads — use a block-mode reflection snippet:
  ```
  jdwp_evaluate_expression(expression="{
      java.lang.reflect.Field f = _this.getClass().getDeclaredField(\"eventBus\");
      f.setAccessible(true);
      Object bus = f.get(_this);
      return bus.getClass().getMethod(\"getErrorSummary\").invoke(bus);
  }")
  ```
  Block mode (`{ ...; return X; }`) is supported by `jdwp_evaluate_expression` and `jdwp_assert_expression`, and by every condition / logpoint expression field.
- **`set_local` / `set_field` only support** primitives, `String`, and `null`. To mutate a complex object, mutate its individual fields.
- **Exception breakpoints on bootstrap classes** (`NullPointerException`, `IllegalStateException`, etc.) start as `[PENDING]`. They auto-promote when any tool runs while a thread is suspended at a breakpoint. Pair with a regular line BP upstream — see the "Exception buried under wrappers" recipe.
- **VMStart suspension is special.** When connected to a JVM with `suspend=y`, all threads are suspended but no thread is at a breakpoint yet. `evaluate_expression`, `to_string`, and `set_exception_breakpoint` cannot work until at least one BP has been hit. Set breakpoints first, then resume.
- **First `evaluate_expression` is slow** (~1-3s) — the expression compiler discovers the target's classpath lazily. Subsequent evals are fast (cached).
- **Logpoints cost time** — each fires the expression evaluator. Don't put a logpoint inside a tight loop with millions of iterations. If the per-hit pause itself is the problem (a race that disappears under a logpoint), see the fast-logpoint recipe — but only with the user's explicit consent.
- **Field watchpoints are expensive.** Each access/modification of a watched field traps into the debugger; on a hot field this can dominate target-VM CPU. Use the narrowest mode that answers your question (`modification` is usually enough), and add `threadFilterId` / `objectFilterId` / `condition` to scope the catches. `jdwp_diagnose` reports `canWatchFieldAccess` / `canWatchFieldModification` plus this warning when connected. Pending field BPs registered before class load promote synchronously on `ClassPrepareEvent`, so `<clinit>` writes are caught.
- **Short-running tests can finish before a watchpoint suspends.** A field watchpoint (or any breakpoint armed late) on a test that completes in milliseconds can race `VM_DEATH`: the write happens, but the test tears the VM down before the suspend lands, so `jdwp_resume_until_event` returns `[VM_DEATH]` with no usable stop. **Set a line breakpoint at the failing assertion first, as a safety net,** then add the field watchpoint. The assertion BP guarantees at least one inspectable stop even if the watchpoint loses the race, and you can still read the write's effect from there. The deeper fix for a sub-second test: launch it with `suspend=y` (e.g. `-Dmaven.surefire.debug`) and attach with `jdwp_wait_for_attach` — the JVM blocks at VM_START until you've armed every breakpoint, so a fast test can't tear the VM down before your setup lands.
- **`$newValue` is bound on both halves of a `mode="both"` field watchpoint.** On a modification event it is the value-to-be; on an access event it is bound as `null` (there is no incoming value). So an expression like `"$oldValue + \" -> \" + $newValue"` renders `… -> null` on reads instead of failing — you do **not** need two separate `access` / `modification` logpoints just to keep the expression compiling.
- **Field watchpoints DON'T see reflective or `Unsafe` writes.** JDI/JVMTI watchpoints fire only on the `putfield`/`putstatic`/`getfield`/`getstatic` bytecodes (and JNI accessors). A `java.lang.reflect.Field.set(...)` bottoms out in `sun.misc.Unsafe`, which stores straight to memory and trips no watch — this is **independent of whether the field is final**, and `access` mode is just as blind to it as `modification`. The tell: a watchpoint that fires on the constructor's ordinary assignment and then stays silent while the value provably changes. When you see that, the write is reflective — drop the watchpoint and **bisect with line breakpoints, comparing `System.identityHashCode(target.getField())` before and after a suspect call**; the identity flips across exactly the method doing the hidden write. **Related blind spot — reference vs. contents:** a watchpoint sees writes to the field's *slot*, not in-place mutation of the object it references. If the field's reference is unchanged but the referenced object's internals (a `String`'s backing `byte[]`, a collection's elements) changed, no `PUTFIELD` fired — put the watchpoint on a field *of the referenced object* instead. (Verified live on JDK 17 and 21: a `modification` watchpoint fires on the constructor and direct assignments but stays silent on a reflective `Field.set` of the same field.)
- **Object IDs are session-scoped.** They become invalid after `disconnect` or if GC collects the object. If you see "Object not found in cache", re-fetch via `jdwp_get_locals`.
- **Don't invoke methods on `MONITOR` / `WAIT` threads.** A thread that is JDI-suspended on top of a Java-monitor block (`THREAD_STATUS_MONITOR`) or inside `Object.wait()` (`THREAD_STATUS_WAIT`) reports `isSuspended() == true` but cannot make progress when single-threaded resumed — the lock is held by another suspended thread, or the `notify()` that would wake it can never fire. `jdwp_evaluate_expression`, `jdwp_assert_expression`, `jdwp_to_string`, and `jdwp_evaluate_watchers` will refuse with an explicit error pointing you at `jdwp_get_stack` + `jdwp_get_threads` instead. The error is the diagnosis path — when you see it, you've already found something useful (typically a deadlock or a missing notify).
- **To read a deadlocked thread's stack, `jdwp_suspend_thread(id)` it first.** A thread blocked on a monitor (or in `Object.wait()`) that you did *not* stop at a breakpoint shows `Suspended: no` in `jdwp_get_threads` and never stops on its own — so `jdwp_get_stack` / `jdwp_get_locals` refuse until you freeze it (their error now names `jdwp_suspend_thread(id)` as the fix). Suspending pins it in place so its frames and locals become readable. Note it makes the thread *inspectable*, not *invocable* — the bullet above still bars evaluate / to_string on it.
- **If an invocation tool hangs anyway, kill the target JVM.** The MONITOR/WAIT guard catches the predictable cases, but a normally-RUNNING thread can still race into a contended lock *inside* the invoked method — JDI's `invokeMethod` is not cancellable and the MCP server will block until the VM dies. Recovery: terminate the target JVM externally (e.g. `kill <pid>`), which surfaces `VMDisconnectedException` and unblocks the tool call. Then relaunch + `jdwp_reconnect` — BPs and watchers are preserved across the cycle.
- **`jdwp_disconnect` does not stop the target JVM** — it only drops the debugger client; the JVM owns its JDWP port. A target with live non-daemon threads (a deadlock, a server, a hung test) keeps running and keeps the port bound after you disconnect. If a relaunch on the same port fails to bind, a previous JVM is still alive — kill it (`kill <pid>`, or `pkill -f 'jdwp=.*<port>'`) before relaunching. The only tell from here is the bind failure (a human just sees the stuck build in the terminal), so when a launch misbehaves, check for a lingering JVM first.

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
- **Don't break right after `Thread.start()` for a concurrency bug.** The threads haven't raced, deadlocked, or lost an update yet — you'll stop too early and see nothing wrong. Set the breakpoint at the `join()` / assertion line instead: by the time the main thread parks there, the contended state has fully formed and `jdwp_get_threads` / `jdwp_get_stack` — or `jdwp_dump_locks` for the wait-for graph and any deadlock cycle in one call — show the real `MONITOR` / `WAIT` standoff or the lost write.
- **Don't pipe the launch command through `tail` / `head` in a background shell** (`mvn … 2>&1 | tail -3`). The truncation hides the Surefire summary you need to confirm a fix went green, and the background runner already captures the full stream — let it. If you only see truncated output, read `target/surefire-reports/*.txt` for the real result.

## Inspecting and clearing debug state

Use `jdwp_overview()` for a unified read of every kind of debug state (breakpoints, exception breakpoints, field breakpoints, logpoints, fast logpoints, watchers, marked instances) in one call. Filter by type (`types="breakpoint,watcher"`) or by substring (`filter="Cart"`).

To bulk-clear: `jdwp_clear(types="...", filter="...")`. The `types` parameter is required so an empty call cannot wipe everything. To preview a clear safely, call `jdwp_overview` with the same `types`/`filter` first — the matching rows are exactly what `jdwp_clear` would remove. Per-id clears still go through `jdwp_clear_breakpoint(id)` / `jdwp_detach_watcher(id)`.

**Event history vs. the rest of the state.** The `jdwp_get_events` log is separate from breakpoints/watchers and survives a *VM death* on purpose (so the `VM_DEATH` and the events leading to it stay readable). It is wiped by an explicit `jdwp_disconnect` / `jdwp_reset`, but **not** by an auto-reconnect to a relaunched target — so across a relaunch you'll see the old VM's events alongside the new ones. They're disambiguated by a session tag (`[s1]`, `[s2]`, …) with a divider at each new attach; if you'd rather start clean, `jdwp_clear_events` empties just the log without touching your breakpoints.

## First Questions At a New Breakpoint

When you land at a breakpoint and don't know what to look at:

1. `jdwp_get_breakpoint_context()` — one-shot dump: thread + top frames + locals + `this` fields. 90% of the time this is all you need.
2. For each interesting `Object#N` reference: `jdwp_get_fields(objectId)` to drill in, then `jdwp_to_string(objectId)` for a quick view.
3. `jdwp_assert_expression(<expression>, <expected>)` to test "is the state what I expected?" — much terser than evaluating and eyeballing.

**When something isn't working:** call `jdwp_diagnose` first — it returns the MCP server status, the JDWP connection (with last-attempt error if disconnected), and a list of local JVMs with their JDWP ports in a single call. Skips the `ps`/`lsof`/`jps` round-trip. See [references/troubleshooting.md](references/troubleshooting.md) for more.

**Cheaper status checks:** the server also exposes two MCP resources — `jdwp://diagnose` and `jdwp://jvms`. Attach `@jdwp-inspector:jdwp://diagnose` (or `:jdwp://jvms` for just the JVM inventory) from the autocomplete to read live status into the conversation without a model turn.

## When the [VM_DEATH] message lists FAILED breakpoints

`jdwp_resume_until_event` returning `[VM_DEATH]` always means "the target VM is gone — nothing more to wait on." If the response includes a `Note: deferred breakpoint(s) were promoted but failed to install: #N at Class:line (reason)` line, that BP was set on a non-executable position (comment, blank line, method signature, or a class with no debug info). Re-set the BP on a real statement line and re-attach.

## State-checking semantics of resume_until_event

`jdwp_resume_until_event` is state-aware, not just signal-driven. If a BP / STEP / exception event has fired since your last call, it returns immediately with the captured snapshot rather than re-resuming the thread (which would overshoot the suspended location). So `jdwp_step_over` → any number of intervening tool calls → `jdwp_resume_until_event` is safe — you'll land on the STEP event, not the BP after it.
