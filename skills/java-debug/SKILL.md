---
name: java-debug
description: Debug live Java applications via the jdwp-inspector MCP server — set breakpoints, inspect runtime state, evaluate expressions, mutate variables at runtime, catch exceptions at their throw site, and trace execution non-intrusively with line logpoints or log-only exception breakpoints (with $exception-bound expressions). Use when investigating Java bugs, test failures, runtime exceptions, race conditions, or any JVM behavior that is hard to understand from reading code alone.
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

The target JVM must be running with the JDWP agent on port 5005:

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005
```

`suspend=y` blocks the JVM at startup until you attach — use for tests and early-startup bugs. `suspend=n` lets the JVM run freely — use for long-running services where you attach on demand.

**Quick launch shortcuts:**
- Maven Surefire: `mvn test -Dtest=<TestClass> -Dmaven.surefire.debug`
- Gradle: `./gradlew test --tests "com.example.MyTest" --debug-jvm`
- Standalone: `java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar`

For build-system-specific gotchas (Surefire `<argLine>` overrides, Gradle `maxParallelForks`, `bootRun`): see [references/prerequisites.md](references/prerequisites.md).

## Core Workflow

Every debug session follows this sequence:

1. **Launch the target** in a separate shell with `suspend=y`. The JVM blocks until step 2.
2. **Attach:** `jdwp_wait_for_attach()` — polls until the JVM is listening, then attaches. No manual port-checking needed.
3. **Set breakpoints** at suspected bug locations: `jdwp_set_breakpoint(className, lineNumber)`. Add exception breakpoints or logpoints as needed.
4. **Resume and wait:** `jdwp_resume_until_event()` — releases the JVM and BLOCKS until the next BP/step/exception fires. Returns the suspended thread info.
5. **Inspect in one call:** `jdwp_get_breakpoint_context()` — returns thread, top frames, locals (incl. `this`), and `this` field dump.
6. **Form a hypothesis,** test it: step through, `jdwp_assert_expression(...)` to check invariants, `jdwp_set_local`/`jdwp_set_field` to mutate state and ask "would the test pass if X were Y?"
7. **Resume to next event** (`jdwp_resume_until_event`) or **disconnect** when done. For sequential scenarios against the same target, use `jdwp_reset` between flights to clear state without dropping the connection.

For follow-up investigations against the same target: `jdwp_reset` + new breakpoints, no need to reconnect.

## Debugging Recipes

### "The function shouldn't have changed that"

A value looks correct before a method call and wrong after. The method appears to only do reads.

1. BP at the call site, *before* the suspicious call.
2. `jdwp_evaluate_expression` on the value -> correct.
3. `jdwp_step_over` past the call.
4. `jdwp_evaluate_expression` again -> wrong! The call mutates.
5. Restart, BP at the same site, `jdwp_step_into` to land inside the suspicious method, then `jdwp_step_over` line by line and eval after each — find the exact mutation point.

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

1. `jdwp_set_exception_breakpoint("java.sql.SQLException", logOnly=true, expression="$exception.getSQLState() + \\\": \\\" + $exception.getMessage()")` — `$exception` is bound to the thrown object; the listener auto-resumes after recording.
2. Let the service run. Each throw produces an `EXCEPTION_LOG` entry (or `EXCEPTION_LOG_ERROR` if the expression fails).
3. `jdwp_get_events(50)` to inspect throw locations + evaluated expression results in chronological order.
4. Pass `logOnly=true` with no `expression` for a pure non-intrusive trace (just type, throw location, catch location, thread).

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

## Critical Gotchas

- **Expression eval auto-rewrites bare field references** to `_this.field` when the enclosing class and field are both public. For PACKAGE-PRIVATE enclosing classes this is skipped — the error message will tell you to use `jdwp_get_fields(<thisObjectId>)` instead.
- **`set_local` / `set_field` only support** primitives, `String`, and `null`. To mutate a complex object, mutate its individual fields.
- **Exception breakpoints on bootstrap classes** (`NullPointerException`, `IllegalStateException`, etc.) start as `[PENDING]`. They auto-promote when any tool runs while a thread is suspended at a breakpoint. Pair with a regular line BP upstream — see the "Exception buried under wrappers" recipe.
- **VMStart suspension is special.** When connected to a JVM with `suspend=y`, all threads are suspended but no thread is at a breakpoint yet. `evaluate_expression`, `to_string`, and `set_exception_breakpoint` cannot work until at least one BP has been hit. Set breakpoints first, then resume.
- **First `evaluate_expression` is slow** (~1-3s) — the expression compiler discovers the target's classpath lazily. Subsequent evals are fast (cached).
- **Logpoints cost time** — each fires the expression evaluator. Don't put a logpoint inside a tight loop with millions of iterations.
- **Object IDs are session-scoped.** They become invalid after `disconnect` or if GC collects the object. If you see "Object not found in cache", re-fetch via `jdwp_get_locals`.

## Anti-patterns

- **Don't restart the test for every hypothesis.** Use `jdwp_set_local` / `jdwp_set_field` to mutate state in place and resume. If the test passes, your hypothesis is confirmed — no rebuild needed.
- **Don't step through 50 loop iterations manually.** Use a conditional breakpoint or logpoint to jump straight to the iteration that matters.
- **Don't catch `Throwable` or `Exception` "to be safe".** Target the specific exception type. Broad exception breakpoints fire on every JDK internal exception — extremely noisy and slow.
- **Don't stop at the first wrapped exception.** The original throw site is almost always more informative. Set an exception BP on the inner type and re-run.

## First Questions At a New Breakpoint

When you land at a breakpoint and don't know what to look at:

1. `jdwp_get_breakpoint_context()` — one-shot dump: thread + top frames + locals + `this` fields. 90% of the time this is all you need.
2. For each interesting `Object#N` reference: `jdwp_get_fields(objectId)` to drill in, then `jdwp_to_string(objectId)` for a quick view.
3. `jdwp_assert_expression(<expression>, <expected>)` to test "is the state what I expected?" — much terser than evaluating and eyeballing.

**Server troubleshooting:** see [references/troubleshooting.md](references/troubleshooting.md).
