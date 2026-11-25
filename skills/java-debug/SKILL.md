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

The `jdwp_*` tools come from the plugin's `jdwp-inspector` MCP server. **If they are missing**, the server is not connected — usually right after an install or update, while its JAR is still being prepared. Do not fall back to println debugging: follow [references/troubleshooting.md](references/troubleshooting.md#the-jdwp_-tools-are-missing-the-mcp-server-is-not-connected) to find out why, and ask the user to reconnect.

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
2. **Attach** following the port-resolution priority above. `jdwp_wait_for_attach` polls until the JVM is listening, then attaches. For a test launched with `suspend=y`, pass `requireVmStart=true` to check that it is the fresh test JVM.
3. **Set breakpoints** at suspected bug locations: `jdwp_set_breakpoint(className, lineNumber)`. Add exception breakpoints or logpoints as needed.
4. **Resume and wait** with `jdwp_resume_until_event()`. It presents an unseen stop immediately or releases presented stops and manual holds before waiting (30 seconds by default). Pass `threadId=T` when you want to resume and wait on one thread.
5. **Inspect in one call** with `jdwp_get_breakpoint_context()`. It returns the thread, top frames, locals, and `this` fields. Pass `threadId=T` to inspect a particular suspended thread.
6. **Form a hypothesis,** test it: step through, `jdwp_assert_expression(...)` to check invariants, `jdwp_set_local`/`jdwp_set_field` to mutate state and ask "would the test pass if X were Y?"
7. **Resume to next event** with `jdwp_resume_until_event`. When done with a test run, disconnect and check that the test JVM and background build shell have ended. Use the cleanup steps under Critical Gotchas if either is still running. Launch the next test and attach with `jdwp_wait_for_attach(port=…, requireVmStart=true)`.

**On `[TIMEOUT]`:** the response includes a structured diagnostic — *read it*. If your breakpoints are `PENDING` (target class not loaded), the code path is not executing and a larger timeout will not help — verify the entry point or class name. If a pending BP shows `[FAILED]`, the line/class is invalid. If recent events show `LOGPOINT` or `BREAKPOINT_SUPPRESSED` hits, your BP *is* firing but auto-resuming (logpoint or false condition). Do **not** blindly retry with a bigger timeout. Call `jdwp_diagnose()` any time for the same snapshot without resuming — useful as a sanity check before waiting on a long path.

**On `[RESUME_FAILED]`:** read which holds were released and which need recovery. Use `jdwp_resume_thread(threadId=…)` with the retry ID in the reply when available. That ID can be the event thread of a failed automatic stop-all covering the thread you were waiting on. Retrying that release lifts its VM-wide suspension. `jdwp_resume` also releases every other presented stop and every `jdwp_suspend_thread` hold, including any stop-all a pending step relies on.

For follow-up investigations in the same long-running JVM, use `jdwp_reset` to clear session state, then set new breakpoints.

## Debugging Recipes

### "The function shouldn't have changed that"

A value looks correct before a method call and wrong after. The method appears to only do reads.

1. BP at the call site, *before* the suspicious call.
2. `jdwp_evaluate_expression` on the value -> correct.
3. `jdwp_step_over(wait=true)` to step and collect the thread's next stop.
4. `jdwp_evaluate_expression` again -> wrong! The call mutates.
5. Restart with a BP at the same site. Use `jdwp_step_into(wait=true)` to enter the suspicious method, then use `jdwp_step_over(wait=true)` a few times and evaluate after each stop. If the suspect line is more than about three steps away, set a breakpoint there and resume to it.

### "Race / partial init / observable intermediate state"

A field has a value at one read site that doesn't match what was written, or a thread reads a half-built object.

1. BP at the read site (where the wrong value is observed).
2. `jdwp_get_locals` -> find the broken object's ID.
3. `jdwp_get_fields(<objectId>)` -> see the partially-initialized state.
4. `jdwp_set_field(<objectId>, "timeout", "5000")` to fix it at runtime.
5. `jdwp_resume_until_event` -> if the test passes now, the root cause is confirmed.
6. Find and fix the actual write order in the source.

### "Everything's stuck — is it a deadlock?"

Threads hang, the test never completes, or `jdwp_get_threads` shows several threads in `MONITOR` status and you need to know what they're each waiting on.

1. `jdwp_dump_locks()` — one call takes a transient VM-wide snapshot and prints which threads are blocked on a monitor, who holds each one, and any **deadlock cycle** (e.g. `transfer-A-to-B → transfer-B-to-A → transfer-A-to-B`). The suspend/resume is balanced, so a genuine deadlock stays put and a non-deadlocked VM is undisturbed.
2. If a cycle is reported, `jdwp_suspend_thread(threadId)` a member, then `jdwp_get_stack(threadId)` to see the exact lock-acquisition line (the two members usually contend the same line in mirror order — the AB-BA signature).
3. No cycle but threads blocked? The hang is a lock held by a *runnable* thread that's slow/looping, or — if `jdwp_dump_locks` shows nothing — the threads are parked in `Object.wait()` or a `java.util.concurrent` Lock, which monitor dumps don't cover. Fall back to `jdwp_get_threads` for `WAIT`-status threads.

### "Exception is buried under wrappers"

Test shows `CompletionException("Async task failed")`, but the real cause is 3 frames deeper.

1. `jdwp_set_exception_breakpoint("java.lang.IllegalStateException", caught=true, uncaught=false)`. A "deferred" reply (`[PENDING]`) only means the class is not loaded yet — nothing more to do: the BP activates when the JVM loads the class, JDK classes included, which is always before the first throw.
2. `jdwp_resume_until_event`.
3. The exception BP catches the throw -> `jdwp_get_stack` shows the **real** root frame, not the wrapper.
4. Same type thrown many times before the code you care about (framework start-up, earlier tests)? Chain it: set a line BP at the entry of the suspect code and pass its ID as `triggerBreakpointId`, so the exception BP stays disarmed until that line runs — see "Same method runs 1000×" below.

### "Trace exceptions without stopping the app"

A long-running service throws something occasionally and you want to see when/where without halting traffic.

1. `jdwp_set_exception_logpoint("java.sql.SQLException", expression="$exception.getSQLState() + \\\": \\\" + $exception.getMessage()")` — `$exception` is bound to the thrown object; the listener auto-resumes after recording.
2. Let the service run. Each throw produces an `EXCEPTION_LOG` entry (or `EXCEPTION_LOG_ERROR` if the expression fails).
3. `jdwp_get_events(50)` to inspect throw locations + evaluated expression results in chronological order.
4. To stop at a throw, use `jdwp_set_exception_breakpoint`. Both tools accept a Java `condition` with `$exception` bound to the thrown object.

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

A failed condition records `CONDITION_ERROR` and processes the hit as though the condition were true. A suspending breakpoint stops and a logpoint proceeds with its log expression. Ordinary conditions can read private fields directly, for example `condition="!this.ready"`. The interpreter also handles supported calls and blocks. On JDK 17 and 21, the compiler can retry eligible private member access through reflection.

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
1. Call `jdwp_set_fast_logpoint(className, lineNumber, expression, condition?)` **without** `userApproved`. The server arms directly if it holds a standing approval for this exact probe at this host:port. Its reply gives the grant time.
2. If the briefing has no endpoint yet, attach and request a named briefing. Relay the named risk briefing to the user **word for word** and ask whether to proceed.
3. Only after the user has explicitly approved this exact probe at this endpoint **in this conversation**, call again with the same arguments and `userApproved=true`. If the server returns a fresh briefing after an endpoint change, relay it and ask again. Never infer consent. If the user declines, use `jdwp_set_logpoint`.
4. For later JVMs at the same endpoint, make the same call without `userApproved`. The grant lasts for this MCP server process, including across JVM exit, disconnect, reconnect and reset. `jdwp_overview(types="fast_logpoint")` lists the grants.

**Then:**
- Use the JVM's exact binary class name, such as `com.example.Outer$Inner` or `example.Order-item`. The approval includes the effective `bufferSize` and `forceLoad` settings. Changing either requires approval for the new settings.
- A class that is not loaded yet is waited for: the reply says `WAITING`, and `FAST_LOGPOINT_CLASS_LOADED` (or `FAST_LOGPOINT_ARM_FAILED`, when the line cannot host it) follows when it loads — no regular breakpoint or `forceLoad` first (`forceLoad` cannot load anything at VM start anyway). The next hit of the line arms the probe: that one thread pauses briefly, and that hit is logged through the debugger (`armingHit`).
- Read hits with `jdwp_get_events` — `FAST_LOGPOINT` / `FAST_LOGPOINT_ERROR` entries carry the in-target thread, `seq` and `nanoTime`. Hits still unread when the target exits (a test JVM finishing) are read before it goes: `[VM_DEATH]` lists them, and `FAST_LOGPOINT_READ_AT_EXIT` says how many. `jdwp_overview(types="fast_logpoint")` shows the state and counters.
- Narrow the `condition` to log only the anomaly so rare hits are easier to see. The shared history keeps the newest 500 events across all sources, and `jdwp_get_events` returns at most the newest 100. Each read ingests up to 500 of the newest hits per ring. Read before more than 500 hits pile up to avoid ingest skips. For buffer overruns, read more often, raise `bufferSize` (power of two, ≤ 65536) or narrow the condition.
- `FAST_LOGPOINT_ARM_FAILED` explains a refused arm — most often "the class bytes ... differ from the live bytecode": another agent (JaCoCo, Mockito inline mocks, an enhancer) rewrote the class. That refusal is the safety check working; fall back to `jdwp_set_logpoint`.
- Results render without calling `toString()` on arbitrary objects (`type@hash`); call it yourself in the expression if you need it. Marked-instance labels (`$label`) and private members are not available.
- Threads already inside the method keep running the old code until they call it again — a line inside a method that never returns (a worker's main loop) will not log; put the fast logpoint in a method the loop calls.
- Clear it as soon as you have your answer: `jdwp_clear_breakpoint(breakpointId)` restores the original bytecode (so do `jdwp_reset`, `jdwp_disconnect` and server shutdown). Tell the user if a `FAST_LOGPOINT_WARNING` says the bytecode could not be restored — the logpoint then stays listed as `DISABLED` and clearing it again retries.

**Fast logpoints left by an earlier session.** If an attach reply (or `jdwp_overview`, or a `FAST_LOGPOINT_RECOVERED` event) reports fast logpoints left by an earlier debugging session, a previous server died without restoring the class — they keep evaluating in the target. Tell the user what was found (class, line, expression, who armed it when) and **leave them alone unless the user asks**: the earlier investigation may still want them.
- `RECOVERED` — the class is exactly as that session left it. `jdwp_clear_breakpoint(breakpointId)` switches it off without touching the bytecode (no consent needed). `jdwp_restore_fast_logpoints(id)` restores the class's original bytecode, removing every leftover in it. Get fresh approval for each restore. Call without `userApproved`, relay the briefing word for word, and pass `userApproved=true` only after the user agrees.
- `STRANDED` — the class changed after the probe was installed, or its wiring is unknown. Only `jdwp_clear_breakpoint(breakpointId)` (the kill switch) is offered; the injected call stays until the JVM restarts.
- A class with leftovers refuses new fast logpoints until they are restored; use `jdwp_set_logpoint` meanwhile.

### "Stop only on the rare bad case in a hot path" — fast breakpoint (rare, needs the user's consent)

A conditional `jdwp_set_breakpoint` pauses for debugger round trips on every hit, and a method holding a breakpoint is never JIT-compiled. On a hot path where the condition is rarely true, debugger overhead can change the timing of the race you are investigating. `jdwp_set_fast_breakpoint(className, lineNumber, condition, suspendPolicy?, stopOnce?, capture?)` evaluates the condition **inside the target JVM** and stops only when it holds. The method keeps running compiled.

Same rule as for the fast logpoint: `jdwp_set_breakpoint` with a condition stays the default. Use the fast variant only when going through the debugger on every hit would distort the behaviour under investigation.

Follow the fast logpoint's consent protocol. Call without `userApproved` and let the server check for a standing approval for the exact breakpoint, including its condition, optional capture, suspend policy, stop mode and `forceLoad`, at this host:port. If the tool returns a briefing, relay it word for word and ask before passing `userApproved=true`. See grants with `jdwp_overview(types="fast_breakpoint")`. If the user declines, use `jdwp_set_breakpoint` with the condition.

**Then:**
- A class that is not loaded yet is waited for (`WAITING`, then `FAST_BREAKPOINT_CLASS_LOADED`). The next hit of the line evaluates the condition through the debugger: if it holds, that hit is the stop; otherwise the probe is installed then. While local classpath discovery runs, arming can move to a later hit.
- Wait with `jdwp_resume_until_event`. The report says `fast_breakpoint=N, condition true`, or `condition failed: <text>` — a condition that throws stops once; later failures are only counted (`FAST_BREAKPOINT_ERROR` in `jdwp_get_events`).
- `suspendPolicy="all"` (default) parks every thread, but the other threads a moment after the hitting one, so they may have moved past the state you want; `"thread"` parks only the hitting thread.
- Use `capture` when a value can change just after the condition holds. On a true arming stop the debugger evaluates it after the condition on the same thread. The installed probe evaluates it after the stop gate and before suspension. Use the probe condition's bindings and a Java expression or `{ ...; return value; }` block. For several values return a string such as `"id=" + id + " status=" + statuses[id]`. Look for `Captured at the hit: …` in the stop, the breakpoint context or `jdwp_get_events`. A shared stop labels captures from other candidates with their fast-breakpoint IDs. Capture failures appear in that line, including a capture compiler error when the condition compiles and the probe can still stop.
- With `stopOnce=true` (default), only the first hit that meets the condition stops. If overview reports a pending stop while capture runs, wait with `jdwp_resume_until_event`. After the stop, call again with the same probe settings to re-arm under the same ID. If a gate update fails, the reply identifies the affected gate. With `stopOnce=false`, every true hit stops.
- At the stop, frame #0 is your method: the probe's frames are hidden. Stack, locals, `jdwp_evaluate_expression`, `jdwp_set_local` and steps work as at any breakpoint; a step first lands on the stop line itself. On a virtual thread, `jdwp_set_local` needs one step first.
- The condition and capture run unsynchronized and must be thread-safe and free of side effects. Both may read stale values. Locals, `this` and non-private members are reachable.
- Watchers attach by the fast breakpoint's ID (`jdwp_attach_watcher`) and evaluate at the stop. Chains (`jdwp_set_breakpoint_dependency`, `triggerBreakpointId`) refuse fast breakpoints.
- Clear it as soon as you have your answer: `jdwp_clear_breakpoint(breakpointId)`. While a thread is stopped in the method, the clear only switches it off — the class is restored once `jdwp_resume` releases that thread, and the reply says so. `jdwp_reset` restores at once.
- Leftovers of an earlier session are handled as for fast logpoints (above); a leftover fast breakpoint never stops.

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
3. `jdwp_list_watchers_for_breakpoint(1)` / `jdwp_overview(types="watcher", filter="...")` to list, `jdwp_detach_watcher(watcherId)` to remove using the displayed ID or full UUID.

### "The target JVM died / I need to re-run with new breakpoints"

Test ended (VM_DEATH), the surefire JVM was killed for a new run, or the target JVM was relaunched on the same port. You want to continue debugging with **all current breakpoints preserved** — no need to re-set them by hand.

1. Relaunch the target on the same `address=<port>` (e.g. `mvn test -Dmaven.surefire.debug ...`).
2. `jdwp_reconnect()` — disposes the dead VM handle and reattaches to the last known host:port. **Breakpoint specs (line / exception / field), conditions, logpoint expressions, chain edges, watchers, and synthetic BP IDs are preserved** — BP `#7` is still `#7` after.
3. Resume normally: `jdwp_resume_until_event`.

**What's lost on reconnect** — marked instances (`jdwp_mark_instance` labels), the object cache, the last-suspended-thread context, and the classpath-discovery cache. Reconnect starts local classpath discovery again. An early `jdwp_evaluate_expression` can wait for it. A `[STILL_RUNNING]` reply that names local classpath discovery directs you to `jdwp_diagnose` for progress. Object IDs from the previous session are invalid — re-fetch via `jdwp_get_locals` / `jdwp_get_fields`.

**Don't** `jdwp_disconnect` + `jdwp_wait_for_attach` for this — it works but you lose every BP. Reserve `jdwp_connect` / `jdwp_wait_for_attach` for attaching to a **different** target.

### "Where exactly did the assertion fail?"

A test fails with an unhelpful `AssertionError` message and tears down before you can inspect state. Pin the JVM at the throw site.

1. `jdwp_set_exception_breakpoint("java.lang.AssertionError", caught=true, uncaught=true, suspendPolicy="all")` — fires on the assertion itself, before JUnit's reporter wraps it and before the VM tears down. It also catches subclasses, such as the `org.opentest4j.AssertionFailedError` that JUnit 5's `assertEquals` throws. At VM_START the reply says "deferred": `AssertionError` is not loaded yet, and the BP activates when it loads, before the first assertion can throw — no line BP or `forceLoad` needed. With the default `suspendPolicy="thread"` only the throwing thread stops: when it is a pool thread, the test's main thread runs on, may hit its timeout and end the JVM while you look — `"all"` parks every thread.
2. `jdwp_resume_until_event` — lands at the throw frame with the thread suspended; the reply names the exception's type and message (`via=exception, org.opentest4j.AssertionFailedError: … ==> expected: <…> but was: <…> (thrown at …, caught at …)`).
3. `jdwp_get_breakpoint_context` — frame 0 is the assertion library's throw site (e.g. JUnit's `AssertionFailureBuilder`, whose `this` fields show `expected` / `actual` / `message`); the test method is listed a few frames down as `#N`, with the library frames collapsed above it.
4. `jdwp_get_locals(threadId, frameIndex=N)` — the test's own locals at the failing assertion. From here you can `jdwp_evaluate_expression(..., frameIndex=N)` to test invariants or `jdwp_set_local` / `jdwp_set_field` to try fixes in place.

This is the safest default for "I want to see what the test saw when it gave up." It works from VM_START, so make it part of the attach prologue when launching a failing test: launch suspended (`-Dmaven.surefire.debug`), call `jdwp_wait_for_attach(requireVmStart=true)`, set this BP plus any line / field BPs, then `jdwp_resume_until_event`.

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

- **Read private fields directly.** Use a single path such as `cache.entries[0].expiresAt` from the suspended frame; it crosses private fields and package-private classes, even across package boundaries. Expressions can use fields, arrays, casts, operators, marks and event bindings without invoking. A private read such as `this.buffer.position` works in an expression, assertion, watcher, condition or logpoint. The interpreter can call a private or package-private method with one applicable signature on a thread stopped at its own event. Same-arity overloads, varargs, generic methods and lambdas compile.
- **Ordinary compilation can retry private access.** On JDK 17 and 21, application field reads, method calls and constructors can retry through reflection. The retry preserves exact value types and inlines type-qualified constants. Assignment targets, closed JDK packages, inaccessible types and expression-qualified constants can still fail. Fast probes, their arming hits and fast-breakpoint captures follow Java access rules.
- **Write application types fully qualified in expressions.** Expressions see no imports. A simple type name covers `java.lang` and the current class's own and nested types. For a type from your package or an import, write `com.acme.Status.ACTIVE`, not `Status.ACTIVE`. Otherwise the expression is compiled, and the compiled wrapper has no imports either, so the simple name usually fails there too.
- **Block mode** (`{ ...; return X; }`) is available in evaluation, assertions, watchers, conditions and logpoints. The interpreter supports block locals, assignments to those locals, supported calls, `if` / `else`, nested blocks and value returns. Read-only blocks work on manual holds. Loops, `try`, writes to fields or frame locals, and other unsupported constructs send the whole block to the compiler. The generated method declares `throws Throwable`.
- **`set_local` / `set_field` only support** primitives, `String`, and `null`. To mutate a complex object, mutate its individual fields.
- **Use the fully qualified class name for exception breakpoints.** With a fully qualified name, an exception class that is not loaded yet (`java.lang.IllegalStateException`, `java.lang.AssertionError`, …) starts as `[PENDING]` and activates when the JVM loads it — JDK classes included, and always before the first instance can be thrown; `jdwp_get_events` then shows a `CLASS_PREPARE` entry for it. Classes the JVM preloads (e.g. `java.lang.NullPointerException`) bind at once. If you pass a bare name, check the hint for the intended class's fully qualified name and set the breakpoint again. Neither an upstream line BP nor an inspection tool promotes it — the server never loads a class on its own; only `forceLoad=true` does, and that cannot work at VM_START.
- **VMStart suspension is special.** Navigation can read initialized system statics and marks. Interpreted calls, compiled expressions, `jdwp_to_string`, and `forceLoad=true` need a later invocation event. Set pending breakpoints first and resume to reach application frames. A force-load that cannot run at VMStart is deferred.
- **Check force-load warnings.** A warning that initialization failed means the debugger's `Class.forName` call left the class loaded but unusable until the target restarts, even if the breakpoint bound. If the class is still missing, look for the target exception in the deferral note.
- **The first compiled evaluation can be slow.** The first compiled evaluation or fast-probe arming compile discovers the remote classpath and matching JDK on demand. Later compiles add newly selected defining loaders and retry incomplete reads. Local discovery starts on attach, and compiled tool evaluations can wait for Maven. Compiled conditions and logpoints can use the remote classpath while Maven runs. A remote-only compile that reports a possibly missing type can retry on the same hit when the current workspace’s entries are ready. A fast probe stays pending for a later hit if discovery is still running or the retry becomes stale. `jdwp_diagnose` shows discovery progress and evaluation counters.
- **Logpoint values.** Boxed primitives appear as values. Other objects appear as `Object (type)`. Use a logpoint expression that selects the fields or values you need.
- **Logpoints cost time** — each fires the expression evaluator. Don't put a logpoint inside a tight loop with millions of iterations. If the per-hit pause itself is the problem (a race that disappears under a logpoint), see the fast-logpoint recipe — but only with the user's explicit consent. The same goes for a conditional breakpoint on a hot line whose condition is rarely true: see the fast-breakpoint recipe.
- **Field watchpoints are expensive.** Each access/modification of a watched field traps into the debugger; on a hot field this can dominate target-VM CPU. Use the narrowest mode that answers your question (`modification` is usually enough), and add `threadFilterId` / `objectFilterId` / `condition` to scope the catches. `jdwp_diagnose` reports `canWatchFieldAccess` / `canWatchFieldModification` plus this warning when connected. Pending field BPs registered before class load promote synchronously on `ClassPrepareEvent`, so `<clinit>` writes are caught.
- **Short-running tests can finish before a watchpoint suspends.** A field watchpoint (or any breakpoint armed late) on a test that completes in milliseconds can race `VM_DEATH`: the write happens, but the test tears the VM down before the suspend lands, so `jdwp_resume_until_event` returns `[VM_DEATH]` with no usable stop. **Set a line breakpoint at the failing assertion first, as a safety net,** then add the field watchpoint. The assertion BP guarantees at least one inspectable stop even if the watchpoint loses the race, and you can still read the write's effect from there. The deeper fix for a sub-second test: launch it with `suspend=y` (e.g. `-Dmaven.surefire.debug`) and attach with `jdwp_wait_for_attach(requireVmStart=true)` — the JVM blocks at VM_START until you've armed every breakpoint, so a fast test can't tear the VM down before your setup lands.
- **`$newValue` is bound on both halves of a `mode="both"` field watchpoint.** On a modification event it is the value-to-be; on an access event it is bound as `null` (there is no incoming value). So an expression like `"$oldValue + \" -> \" + $newValue"` renders `… -> null` on reads instead of failing — you do **not** need two separate `access` / `modification` logpoints just to keep the expression compiling. Every field event already records the site and the values (`at RetryPolicy.reload:27: 3 -> 5`, `reads 5`), so an expression is needed only for something else; a stop says `field_breakpoint=N, RetryPolicy.maxAttempts modification at …`.
- **Field watchpoints DON'T see reflective or `Unsafe` writes.** JDI/JVMTI watchpoints fire only on the `putfield`/`putstatic`/`getfield`/`getstatic` bytecodes (and JNI accessors). A `java.lang.reflect.Field.set(...)` bottoms out in `sun.misc.Unsafe`, which stores straight to memory and trips no watch — this is **independent of whether the field is final**, and `access` mode is just as blind to it as `modification`. The tell: a watchpoint that fires on the constructor's ordinary assignment and then stays silent while the value provably changes. When you see that, the write is reflective — drop the watchpoint and **bisect with line breakpoints, comparing `System.identityHashCode(target.getField())` before and after a suspect call**; the identity flips across exactly the method doing the hidden write. **Related blind spot — reference vs. contents:** a watchpoint sees writes to the field's *slot*, not in-place mutation of the object it references. If the field's reference is unchanged but the referenced object's internals (a `String`'s backing `byte[]`, a collection's elements) changed, no `PUTFIELD` fired — put the watchpoint on a field *of the referenced object* instead. (Verified live on JDK 17 and 21: a `modification` watchpoint fires on the constructor and direct assignments but stays silent on a reflective `Field.set` of the same field.)
- **Object IDs are session-scoped.** They become invalid after `disconnect` or if GC collects the object. If you see "Object not found in cache", re-fetch via `jdwp_get_locals`.
- **Evaluate navigation on held and blocked threads.** With a thread ID you can inspect fields or locals on a manual hold, a bystander of a stop-all, or a suspended thread in MONITOR or WAIT. Use `jdwp_suspend_thread(threadId)` first if the thread is blocked and reports `Suspended: no`. Compiled expressions and `jdwp_to_string` require an invocable event stop and still refuse MONITOR or WAIT.
- **If an invocation tool hangs anyway, kill the target JVM.** The MONITOR/WAIT guard catches the predictable cases, but a normally-RUNNING thread can still race into a contended lock *inside* the invoked method — JDI's `invokeMethod` is not cancellable and the MCP server will block until the VM dies. Use the verified socket-owner PID or check the established JDWP connection with `ss -tnp` or `lsof -nP -iTCP:<port>` before terminating your test JVM with `kill <pid>`. Its exit surfaces `VMDisconnectedException` and unblocks the tool call. Then relaunch + `jdwp_reconnect` — BPs and watchers are preserved across the cycle.
- **A detached JVM can keep its port.** A live `server=y` JVM listens again after `jdwp_disconnect`. If the verified socket owner is your still-running test JVM, use `kill <pid>` with that PID. For a TCP peer or an unverified or missing PID, check the listener with `ss -ltnp 'sport = :<port>'` or `lsof -nP -iTCP:<port> -sTCP:LISTEN` first. Confirm it has exited before relaunching. Stop the background Maven shell if needed. The forked Surefire JVM can survive when that shell stops.

## When to step vs. when to set a breakpoint

Each step is a JDWP round-trip — slow and token-expensive. Default to a breakpoint at the destination + `jdwp_resume_until_event` whenever you can predict where execution will go next. Stepping wins in three narrow cases:

- **`jdwp_step_into`** — polymorphic dispatch is unclear and you can't tell from source which override will actually run. One call, then go back to inspecting.
- **`jdwp_step_out`** — an exception or early-abort dropped you in a frame you don't care about and finding the right caller line for a breakpoint would be awkward. One call escapes the frame.
- **`jdwp_step_over`** — only for the *single* next statement, when observing a state mutation is faster than predicting it. More than ~3 `step_over`s in a row means "I should have set a breakpoint."

A step releases the selected thread and reports any remaining stop-all, thread stops, and manual holds. A stop-all can still cover virtual threads that JDI omits from the displayed list. Omit `threadId` to step the thread of the current stop. When other threads can stop too, prefer `jdwp_step_over(threadId=T, wait=true)` or the equivalent step into or out. The reply identifies that step's landing or interrupting stop, even if another call already presented it. Stops on other threads stay held and unseen.

`wait` defaults to false. To collect the stop from an asynchronous step or continue after a 30-second waiting-step timeout, use `jdwp_resume_until_event(threadId=T)`.

## Anti-patterns

- **Don't restart the test for every hypothesis.** Use `jdwp_set_local` / `jdwp_set_field` to mutate state in place and resume. If the test passes, your hypothesis is confirmed — no rebuild needed.
- **Don't step over more than ~3 lines in a row.** If you already know which line you want to inspect, put a breakpoint there and `jdwp_resume_until_event`. One round-trip beats N. The same goes for stepping through loop iterations — use a conditional breakpoint or logpoint.
- **Don't catch `Throwable` or `Exception` "to be safe".** Target the specific exception type first. Broad requests can be noisy and slow because JDK and test-framework throw sites are eligible by default. When you need a broad type, scope it with throw-site filters. `thrownIn` takes one class pattern (for example, `thrownIn="com.example.*"`) and `notThrownIn` takes comma-separated exclusions (for example, `notThrownIn="java.*,jdk.*"`). Use exact class names or one `*` at either end.
- **Don't stop at the first wrapped exception.** The original throw site is almost always more informative. Set an exception BP on the inner type and re-run.
- **Don't break right after `Thread.start()` for a concurrency bug.** The threads haven't raced, deadlocked, or lost an update yet — you'll stop too early and see nothing wrong. Set the breakpoint at the `join()` / assertion line instead: by the time the main thread parks there, the contended state has fully formed and `jdwp_get_threads` / `jdwp_get_stack` — or `jdwp_dump_locks` for the wait-for graph and any deadlock cycle in one call — show the real `MONITOR` / `WAIT` standoff or the lost write.
- **Don't pipe the launch command through `tail` / `head` in a background shell** (`mvn … 2>&1 | tail -3`). The truncation hides the Surefire summary you need to confirm a fix went green, and the background runner already captures the full stream — let it. If you only see truncated output, read `target/surefire-reports/*.txt` for the real result.

## Inspecting and clearing debug state

Use `jdwp_overview()` for a unified read of every kind of debug state (breakpoints, exception breakpoints, field breakpoints, logpoints, fast logpoints, fast breakpoints, watchers, marked instances) in one call. Filter by type (`types="breakpoint,watcher"`) or by substring (`filter="Cart"`).

To bulk-clear: `jdwp_clear(types="...", filter="...")`. The `types` parameter is required so an empty call cannot wipe everything. To preview a clear safely, call `jdwp_overview` with the same `types`/`filter` first — the matching rows are exactly what `jdwp_clear` would remove. Per-id clears still go through `jdwp_clear_breakpoint(breakpointId)` / `jdwp_detach_watcher(watcherId)`.

**Event history vs. the rest of the state.** The `jdwp_get_events` log is separate from breakpoints/watchers and survives a *VM death* on purpose (so the `VM_DEATH` and the events leading to it stay readable). A stop reply names the logpoint hits recorded since your last `jdwp_get_events` (`Recorded since your last jdwp_get_events: 2 FIELD_LOGPOINT`), so they are not missed. It is wiped by an explicit `jdwp_disconnect` / `jdwp_reset`, but **not** by an auto-reconnect to a relaunched target — so across a relaunch you'll see the old VM's events alongside the new ones. They're disambiguated by a session tag (`[s1]`, `[s2]`, …) with a divider at each new attach; if you'd rather start clean, `jdwp_clear_events` empties just the log without touching your breakpoints.

## First Questions At a New Breakpoint

When you land at a breakpoint and don't know what to look at:

1. `jdwp_get_breakpoint_context()` shows the thread, top frames, locals, and `this` fields. With `threadId=T`, it presents T's oldest unseen stop or shows T's held, presented stop. If T is suspended without a published stop of its own, it shows T's frames and says so.
2. For each interesting `Object#N` reference: `jdwp_get_fields(objectId)` to drill in, then `jdwp_to_string(objectId)` for a quick view.
3. `jdwp_assert_expression(<expression>, <expected>)` to test "is the state what I expected?" — much terser than evaluating and eyeballing.

**When something isn't working:** call `jdwp_diagnose` first — it returns the MCP server status, the JDWP connection (with last-attempt error if disconnected), and a list of local JVMs with their JDWP ports in a single call. Skips the `ps`/`lsof`/`jps` round-trip. See [references/troubleshooting.md](references/troubleshooting.md) for more.

**Cheaper status checks:** the server also exposes two MCP resources — `jdwp://diagnose` and `jdwp://jvms`. Attach `@jdwp-inspector:jdwp://diagnose` (or `:jdwp://jvms` for just the JVM inventory) from the autocomplete to read live status into the conversation without a model turn.

## When the [VM_DEATH] message lists FAILED breakpoints

`jdwp_resume_until_event` returning `[VM_DEATH]` means the connection you waited on has ended. When available for that connection, the reply lists what the run recorded and the field breakpoints with their hit counts. A field breakpoint with `0 hits` while the value provably changed points to the reflection / `Unsafe` blind spot (see Gotchas). After the exit, `jdwp_reset` and `jdwp_disconnect` say the target exited and what went with it. If the response includes a `Note: deferred breakpoint(s) were promoted but failed to install: #N at Class:line (reason)` line, that BP was set on a non-executable position (comment, blank line, method signature, or a class with no debug info). Re-set the BP on a real statement line and re-attach.

## State-checking semantics of resume_until_event

`jdwp_resume_until_event(threadId=T)` presents T's oldest unseen stop immediately and reports how many unseen stops from other threads remain held (when there are any). It can find a thread the session holds or is stepping, including a virtual thread omitted from the VM's enumeration. If T is already running or has a pending step, the call waits without releasing anything. Otherwise it can release only T before waiting.

If a targeted context, wait, or resume call returns `[CONNECTION_CHANGED]`, use `jdwp_get_threads` to verify the target and look up a fresh thread ID before retrying. A replacement JVM can reuse an old ID.

When T has no unseen stop, the targeted wait lets queued or processing event sets that suspend T settle before starting any eligible release. The deadline bounds when releases can start. If a set is still pending at the deadline, `[TIMEOUT]` names the set and its event thread. An evaluation running on T still causes a refusal. A step with `wait=true` waits through processing of its own landing. If another thread has an unseen stop-all holding T, use unfiltered `jdwp_resume_until_event()` to present that stop in order. If a step is pending on T, use `jdwp_resume_thread(threadId=T)` to free T from the presented hold, then continue the targeted wait. That resume resolves session-owned virtual threads by ID too. A blocker leaves the step armed. If a selected stop coincides with a disconnect, the VM-death or VM-gone reply identifies that stop.

When live thread or frame reads fail, a wait or presenting getter can still identify the selected stop from the saved report. A reset during a waiting step returns `[NO_EVENT]`, even if another call already presented the ending stop.
