# MCP JDWP Inspector

MCP server that gives AI agents full debugger control over running Java applications — inspect state, set breakpoints, evaluate expressions, and mutate values at runtime via JDWP/JDI.

> **Release notes** — see [`CHANGELOG.md`](CHANGELOG.md). 2.0.0 is a breaking release: several `jdwp_clear_*` tools were unified, and field watchpoints are new.

**Built on the foundations of [mcp-jdwp-java](https://github.com/NicolasVautrin/mcp-jdwp-java) by [Nicolas Vautrin](https://github.com/NicolasVautrin)** — the original project that provided core JDI connectivity, thread/stack/variable inspection, stepping, and basic breakpoint management. Everything described below as "beyond standard JDWP" was built on top of that base.

## What you get (TL;DR)

An MCP server that speaks **JDWP** — the same protocol IntelliJ / Eclipse / VS Code use to attach — and exposes the debugger to Claude Code as **48 tools + 2 MCP resources** over STDIO. Once the agent attaches to your JVM, it can:

- **See runtime state, not stack traces** — read locals, fields, threads, the entire object graph
- **Evaluate arbitrary Java in the suspended frame** — compiled to real bytecode, full classpath, including private/package-private members
- **Mutate the running program** — set locals, write fields, test "what if?" without restart
- **Stop on the bad case only** — conditional breakpoints, exception breakpoints at the throw site, deferred BPs that arm before the class loads, chains that gate one BP on another
- **Trace without stopping** — line/exception/field logpoints write to an event log, the thread runs on
- **Catch the writer of a mutating field** — watchpoints suspend at the JVM-level store, including reflective `Field.set` that a line BP can never see
- **Pure STDIO** — no daemon, no extra port, no GUI

The agent gets the same power as IntelliJ's debugger, with extras that exist specifically because an agent isn't a human staring at a UI (filtering, blocking resume, one-shot context dumps, recursion guards, reconnect after a wedge).

## Security & trust

This MCP server runs **entirely on your local machine**:

- **No network calls** — the server communicates with Claude Code over STDIO and with the target JVM over a local JDWP socket. It makes zero outbound HTTP/internet requests. No telemetry, no analytics, no phone-home.
- **Built from source** — the JAR is compiled locally on your machine from the source code in this repository (either via the plugin auto-build hook or manually with `./mvnw clean package`). No pre-built binaries are downloaded or distributed.
- **Auditable** — the full source is here. The server is a standard Spring Boot application with no obfuscation or native code. Try asking Claude Code itself: *"audit these sources for anything that touches network or filesystem beyond the JVM target"*.

## Quick start

### Prerequisites

- **JDK 17+** on PATH to **run** the server (must be a JDK, not a JRE — JDI lives in `jdk.jdi`).
- **JDK 21+ to build from source** — the build toolchain pins to JDK ≥ 21 because Error Prone 2.48 ships Java-21 bytecode. The bytecode target stays at Java 17, so the resulting JAR still runs on a JDK 17 runtime.

No separate Maven install is required — the repository ships with the Maven Wrapper (`./mvnw`), which downloads a pinned Maven 3.9.x into `~/.m2/wrapper/` on first use. The SessionStart hook and every build command in this README use the wrapper.

### 1. Install the plugin

**Option A: Plugin marketplace (recommended)**

Installs the MCP server, the `java-debug` skill (debugging workflows, recipes, gotchas), and the `.mcp.json` configuration in one step:

```bash
/plugin marketplace add https://github.com/FgForrest/mcp-jdwp-java.git
/plugin install jdwp-debugging@mcp-jdwp-java
```

The server JAR is built automatically on first session start via the bundled `./mvnw` wrapper (requires JDK 17+ on PATH — no Maven install needed). The hook is content-aware: it rebuilds when `git HEAD` moves, not just when the JAR is missing, so `git pull` + restart picks up updates without manual intervention. Restart Claude Code to pick up the plugin.

<details>
<summary><strong>Alternative: manual MCP registration (without plugin)</strong></summary>

If you prefer to register the MCP server directly without the plugin (no skill, no auto-build):

**1. Build the JAR:**

```bash
git clone https://github.com/FgForrest/mcp-jdwp-java.git
cd mcp-jdwp-java
./mvnw clean package -DskipTests   # use mvnw.cmd on Windows
```

**2. Register with Claude Code:**

```bash
claude mcp add jdwp-inspector -s user \
  -e MCP_TIMEOUT=30000 \
  -e MCP_TOOL_TIMEOUT=120000 \
  -- java --add-modules jdk.jdi,jdk.attach -jar /path/to/mcp-jdwp-java.jar
```

To change the JDWP port (default 5005), add `-DJVM_JDWP_PORT=12345` before `-jar`.

The `MCP_TIMEOUT` and `MCP_TOOL_TIMEOUT` environment variables are important — JVM startup is not instant (class loading, Spring context initialization), so the default MCP timeouts will cause Claude Code to give up before the server is ready. `MCP_TIMEOUT=30000` gives the server 30 seconds to start, and `MCP_TOOL_TIMEOUT=120000` allows up to 2 minutes for long-running tools like first-time expression evaluation (which discovers the target's classpath and compiles bytecode).

Re-installing requires removing first: `claude mcp remove jdwp-inspector -s user`

Drop `-s user` to scope to the current project only.

**`.mcp.json`:**

```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.jdi,jdk.attach",
        "-jar", "/path/to/mcp-jdwp-java.jar"
      ],
      "env": {
        "MCP_TIMEOUT": "30000",
        "MCP_TOOL_TIMEOUT": "120000"
      }
    }
  }
}
```

The plugin-managed install also passes `-DLOG_PATH=${CLAUDE_PLUGIN_ROOT}/logs/mcp-jdwp-inspector.log` so the server's SLF4J log lands inside the plugin directory; add the same flag if you want a fixed log path here.

</details>

### 2. Launch your Java application with JDWP

**Maven Surefire (test debugging):**

```bash
mvn test -Dmaven.surefire.debug
```

Starts the JVM with JDWP on port 5005, suspended until a debugger connects.

**Any Java application:**

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

### 3. Attach from Claude Code

From there, just tell the agent what to do — *"attach to JDWP, set a breakpoint in `OrderService.createOrder` line 42, run the failing test, dump the context when it hits"*. The bundled `java-debug` skill teaches the agent the right tool sequence; the next section gives you a smoke test that exercises the surface.

## Find the Bug — test flights

The `jdwp-sandbox` module ships 9 deliberately broken Java classes. Each one compiles fine, looks reasonable at first glance, and **fails its test with a confusing message**. Your job: attach with the JDWP MCP server and find the root cause.

This doubles as a setup verification — if you can solve these, everything works.

Each flight is built so that one tool group is the path of least resistance, and lists a **par** — the minimum tool calls that cleanly reveal the root cause. Hitting par is the elegant solve; the suite as a whole exercises the full tool surface (expression eval, exception breakpoints, field watchpoints, event history, marks, logpoints, runtime mutation, and multi-thread inspection).

### Just installed the plugin? Grab the sandbox zip

The fastest path for fresh plugin users is a self-contained zip — no clone, no reactor build:

```bash
curl -L -o jdwp-sandbox.zip \
  https://github.com/FgForrest/mcp-jdwp-java/releases/latest/download/jdwp-sandbox.zip
unzip jdwp-sandbox.zip && cd jdwp-sandbox
```

Inside, you get a parentless Maven project with `src/`, a `pom.xml`, a flight-game `README.md`, and a `CLAUDE.md` that briefs the agent on the game's house rules. Open the folder in Claude Code and ask it to play flight #1 — the bundled `CLAUDE.md` keeps it honest (no peeking at source, no spoiler-fetching). Continue with the workflow below.

### How to launch a test flight

Start Claude Code from the sandbox folder (or this repo's root, if you cloned) and type:

```
Use JDWP to debug <TestClass> in the jdwp-sandbox module — the test is failing, find the root cause.
```

---

### #1 The Phantom Session

**Difficulty:** Moderate | **Test:** `SessionStoreTest` | **Package:** `session` | **Par:** 4 | **Exercises:** expression eval

**Symptom:** `retrieve() returned null` — a session was stored, upgraded, and then... vanished from the map.

**Hint:** The session is still *in* the HashMap. The HashMap just can't *find* it anymore.

<details>
<summary><strong>Reveal root cause</strong></summary>

`UserSession` is used as a HashMap key, with both `userId` and `role` in `hashCode()`. `upgradeUserRole()` calls `session.upgradeRole("PREMIUM")`, which mutates `role` — changing the hashCode while the key is still in the map. The entry sits in the old hash bucket; lookups compute the new hash and search the wrong bucket.

**Debug path:** Breakpoint before and after `upgradeRole()`. Use `jdwp_assert_expression("session.hashCode()", "<value-before>")` after the upgrade — `MISMATCH` confirms the hash drifted.

</details>

---

### #2 The Swallowed Exception

**Difficulty:** Hard | **Test:** `EventBusTest` | **Package:** `events` | **Par:** 4 | **Exercises:** exception breakpoint + trigger gate

**Symptom:** `expected stock < 100 but was 100` and an empty error summary — the order was supposed to reserve inventory, but nothing happened and nobody complained.

**Hint:** The handler runs on a background thread. Nothing in your code catches its failure — so there's no catch block to break on. Catch the throw itself.

<details>
<summary><strong>Reveal root cause</strong></summary>

`OrderEvent` narrows the raw quantity through `byte` (200 → -56), so `Inventory.reserve()` throws `IllegalStateException`. `EventBus.dispatch()` runs each handler on a single-thread executor as a fire-and-forget task — the `Future` is never inspected, so the exception is captured inside `java.util.concurrent.FutureTask.run` (a JDK frame) and discarded. No sandbox frame ever holds the throwable, so a breakpoint-context dump has nothing to show, and `getErrorSummary()` stays empty.

**Debug path:** Because the exception *is* caught (by FutureTask), an `uncaught`-only breakpoint never fires. Set a line BP at the test/dispatch entry as a trigger, then `jdwp_set_exception_breakpoint("java.lang.IllegalStateException", caught=true, triggerBreakpointId=<trigger>)` so bootstrap exceptions don't drown the signal. The throw site suspends with `qty = -56` in the frame's locals.

</details>

---

### #3 The Time Traveler's Config

**Difficulty:** Hard | **Test:** `ConfigurationProviderTest` | **Package:** `config` | **Par:** 4 | **Exercises:** field watchpoint + event history

**Symptom:** `expected timeout=5000 but was 0` — the timeout was set during construction, yet it reads back as the default.

**Hint:** The value *was* set correctly. Something wrote over it afterward. There's no half-built object to inspect — the damage is in the order of writes.

<details>
<summary><strong>Reveal root cause</strong></summary>

`ConfigurationProvider`'s constructor calls `init(5000)`, so the timeout is correct. Then `runMaintenanceSweep()` starts a background `config-reaper` thread that wrongly treats the live instance as stale and calls `resetToDefaults()`, writing the timeout back to 0. By the time the test reads it, the heap shows 0 and no local holds 5000 — a single context dump looks like the value was never set.

**Debug path:** `jdwp_set_field_breakpoint(className="…config.Configuration", fieldName="timeout", mode="modification")`, then `jdwp_get_events` shows the two stores in order — 0→5000 on the main thread, then 5000→0 on `config-reaper`. `jdwp_get_stack` on the second event names the reaper as the clobberer.

</details>

---

### #4 The Audit That Lies

**Difficulty:** Hard | **Test:** `TransferServiceTest` | **Package:** `bank` | **Par:** 4 | **Exercises:** field watchpoint + stack narration

**Symptom:** `expected discrepancy=0 but was non-zero` — money is neither created nor destroyed, yet the audit says the books don't balance.

**Hint:** The transfer moves money in two steps. The audit snapshot is taken between them.

<details>
<summary><strong>Reveal root cause</strong></summary>

`TransferService.transfer()` is not atomic: it calls `source.withdraw()`, then `auditService.snapshotBalances()`, then `destination.deposit()`. The snapshot captures the intermediate state where money has left the source but hasn't arrived at the destination — showing a total of 1500 instead of 2000.

**Debug path:** `jdwp_set_field_breakpoint` on `AuditService.lastTotalSnapshot` (modification). It fires mid-transfer with the stack at `TransferService.transfer` between the debit and the credit; `jdwp_get_stack` plus a live balance read confirm the captured dip.

</details>

---

### #5 The Field That Lies

**Difficulty:** Hard | **Test:** `UserProfileTest` | **Package:** `userprofile` | **Par:** 3 | **Exercises:** event history as evidence

**Symptom:** `expected: <Alice> but was: <alice>` — the welcome message rendered correctly, yet the user's stored display name has silently changed casing.

**Hint:** You will not find the write by ripgrep'ing for `setDisplayName` — the public setter is never called. A line BP on the setter never fires. Whatever path the write travels, the field's value still flips. Let the JVM tell you exactly when it changes, regardless of how the write reaches the field.

<details>
<summary><strong>Reveal root cause</strong></summary>

`LoginNormalizer.welcomeMessage` calls a private `canonicalForm` helper which delegates to a static `DisplayNameMirror` nested class. The mirror uses `Field.setAccessible(true)` + `Field.set(profile, canonical)` to write the lower-cased form straight into `UserProfile.displayName` — bypassing the public setter entirely. From the test's perspective the formatter is read-only; from JDI's perspective every reflective write is still a real field modification.

**Debug path:** `jdwp_set_field_breakpoint(className="one.edee.jdwp.sandbox.userprofile.UserProfile", fieldName="displayName", mode="modification")` — JDI watchpoints fire on every JVM-level field store, including stores issued through `Field.set` and `Unsafe`. The next write suspends the thread inside `DisplayNameMirror.mirror`; `jdwp_get_stack` walks back through `canonicalForm` to `LoginNormalizer.welcomeMessage` and names the culprit. A line BP on `UserProfile.setDisplayName` would never fire — the setter is genuinely unused.

</details>

---

### #6 The Doppelgänger Cart

**Difficulty:** Moderate | **Test:** `CheckoutTest` | **Package:** `cart` | **Par:** 6 | **Exercises:** marked instances (`$label`)

**Symptom:** `expected 45.0 but was 0.0` — the cart goes through pricing and discounting, yet its total never changes.

**Hint:** The object you get back from the pipeline is not the object you passed in. Prove it.

<details>
<summary><strong>Reveal root cause</strong></summary>

`Checkout.process` runs the cart through `validate → price → discount`. `validate` was changed to return a defensive snapshot — `return new Cart(cart)` — so every later stage mutates the *copy*. `process` reassigns its local to the copy as it threads the stages, so no single frame holds both the caller's cart and the copy at once. The test ignores the return value (assuming in-place mutation), so its cart stays at total 0. The copy's fields are identical to the original — only identity differs.

**Debug path:** Break in the test once the cart exists, `jdwp_mark_instance(label="input", objectId=<id>)` to pin it, then set a breakpoint in `price` (or `applyDiscount`) with condition `cart != $input`. It fires — the stage is working on a doppelgänger, not the caller's cart.

</details>

---

### #7 The Heisenbug Race

**Difficulty:** Hard | **Test:** `RaceCounterTest` | **Package:** `race` | **Par:** 3 | **Exercises:** logpoints (non-stopping)

**Symptom:** `expected 2 but was 1` — two threads each increment a counter once, but one increment vanishes.

**Hint:** Two threads, one lost update. Suspending a thread to look changes the timing — watch the reads without stopping anything.

<details>
<summary><strong>Reveal root cause</strong></summary>

`RaceCounter.increment` reads the count, waits at a `CyclicBarrier` so both threads have read before either writes, then writes back `read + 1`. Both threads read 0 and both write 1 — one increment is lost. A suspending breakpoint serializes the threads and makes you juggle two parked threads to reconstruct what happened.

**Debug path:** Set a non-suspending `jdwp_set_logpoint` at the read site logging the thread name and `count` (or a field logpoint on `count`). Let the test run, then `jdwp_get_events` — both `racer-1` and `racer-2` are recorded reading 0 before either writes, which is the lost update laid out in order.

</details>

---

### #8 The Magic Patch

**Difficulty:** Warm-up | **Test:** `DateParserTest` | **Package:** `parser` | **Par:** 3 | **Exercises:** runtime mutation (`set_local`)

**Symptom:** `NumberFormatException: For input string: "15 "` — a date string that looks right fails to parse.

**Hint:** The input looks *almost* right. Rather than rebuild with a fix, patch the value in place and see if the test goes green.

<details>
<summary><strong>Reveal root cause</strong></summary>

`DateParser.parse` splits `YYYY-MM-DD` and `Integer.parseInt`s each part. The feed delivers `"2026-05-15 "` with a trailing space, so `parseInt("15 ")` throws. The parser never trims its input.

**Debug path:** Break at the top of `parse`, observe `input = "2026-05-15 "`, then `jdwp_set_local("input", "2026-05-15")` and resume. The parse now succeeds and the test passes — confirming the trailing space is the whole story, with no rebuild.

</details>

---

### #9 The Polite Standoff

**Difficulty:** Hard | **Test:** `TransferDeadlockTest` | **Package:** `deadlock` | **Par:** 4 | **Exercises:** multi-thread inspection (no breakpoints)

**Symptom:** The test hangs and then fails its join — both transfers never complete.

**Hint:** Nothing is executing. No exception is thrown. Find out what each thread is waiting for.

<details>
<summary><strong>Reveal root cause</strong></summary>

`Account.transfer` locks the source account, pauses, then locks the destination. Two transfers in opposite directions (`A→B` and `B→A`) each grab their first lock and then wait forever for the other's — a classic lock-ordering deadlock.

**Debug path:** No breakpoint can fire — the threads execute nothing. `jdwp_get_threads` shows `transfer-A-to-B` and `transfer-B-to-A` in `MONITOR` state; `jdwp_dump_locks` prints the lock graph with both monitor holders and the inverse-order cycle; `jdwp_get_stack` on each thread shows the lock-ordering inversion. (Trying `jdwp_evaluate_expression` / `jdwp_to_string` on these threads is refused — invoking a method on a monitor-blocked thread would hang the debugger, which is itself the tell.)

</details>

---

### Scorecard

**Flights solved** — did you find the root cause?

| Solved | Rating                                                               |
|--------|----------------------------------------------------------------------|
| 0-2    | The JVM is winning. Check your setup.                                |
| 3-5    | Solid start. You're getting the hang of breakpoint-driven debugging. |
| 6-8    | Impressive. You found bugs that would take hours with println.       |
| 9      | Bug terminator. Nothing survives your debugger.                      |

**Style** — how close to par? Award ⭐⭐⭐ for a flight solved at its par tool count, ⭐⭐ within 2× par, ⭐ solved at all. A flawless run is 27 stars across the nine flights.

## Features beyond standard JDWP

These are the capabilities this server adds on top of raw JDI — the reason to use it instead of writing JDI calls directly.

### Conditional breakpoints

```
jdwp_set_breakpoint(
  className="com.example.OrderService",
  lineNumber=42,
  condition="order.getTotal() > 1000"
)
```

The breakpoint fires on every hit, but the thread is only suspended when the condition evaluates to `true`. False hits are auto-resumed transparently. This is essential for AI agents — without conditions, a breakpoint in a hot loop would generate thousands of useless stops. Conditions (and expressions, and logpoint bodies) all accept **block syntax** — wrap in `{ ...; return X; }` to use intermediate locals, try/catch, or early returns.

### Logpoints (non-stopping breakpoints)

```
jdwp_set_logpoint(
  className="com.example.OrderService",
  lineNumber=42,
  expression="\"Processing order \" + order.getId() + \" total=\" + order.getTotal()",
  condition="order.getTotal() > 1000"
)
```

A logpoint evaluates an expression every time the line is hit, logs the result to the event history, and **never suspends the thread**. Combined with an optional condition, this gives the agent non-intrusive tracing without stopping execution. View results with `jdwp_get_events()`.

### Fast logpoints (in-target, consent required)

A regular logpoint does not stop the thread for long, but it does stop it: every hit — including one whose condition is false — pauses the hitting thread while the server evaluates the condition and expression over JDWP (milliseconds per hit, one hit at a time). In races, lock contention and hot loops that pause changes the very interleaving under investigation. `jdwp_set_fast_logpoint` moves the evaluation **into the target JVM**:

```
jdwp_set_fast_logpoint(
  className="com.example.OrderService",
  lineNumber=42,
  expression="\"order \" + order.getId() + \" on \" + Thread.currentThread().getName()",
  condition="order.getTotal() < 0",
  userApproved=true          // only after the user explicitly agreed — see below
)
```

On the next hit of the line the server compiles a small probe class, defines it in the class's own class loader, and redefines the class (`VirtualMachine.redefineClasses`) with a call to the probe at that line. From then on the probe evaluates the condition and expression on the application thread and appends the result to a lock-free ring buffer in the target; `jdwp_get_events` reads that buffer through plain field reads — no thread is ever suspended — and reports entries lost to a buffer overrun as `FAST_LOGPOINT_DROPPED`. Clearing the logpoint, `jdwp_reset`, `jdwp_disconnect` and a clean server shutdown restore the original bytecode.

Because this **rewrites the running application's bytecode**, the tool requires explicit user consent: called without `userApproved=true` it arms nothing and returns a risk briefing (instrumentation that outlives a crashed server, user code on application threads, interaction with other bytecode agents, breakpoint re-creation, two stop-the-world pauses) for the agent to relay to the user word for word.

If the server dies without restoring the class, the probe keeps running — but it describes itself, and the next attach finds it: the attach reply and `jdwp_overview` list it as `RECOVERED` (the class is exactly as that session left it) or `STRANDED` (it is not), with the expression and who armed it when. `jdwp_clear_breakpoint` switches a leftover off without touching the bytecode; `jdwp_restore_fast_logpoints(id, userApproved=true)` restores a `RECOVERED` class's original bytecode, again only after the user agreed to a briefing. Nothing an earlier session left is ever restored implicitly. Arming is refused when the live bytecode differs from the class file — e.g. JaCoCo or the Mockito inline mock maker instrumented the class — because redefining from the class file would silently strip their changes. Measured on the live test target (two threads calling the instrumented method in a loop, condition never true): throughput stayed at the no-logpoint level (about 25 million calls/s on the test machine), against a few hundred calls/s — 3–7 ms per hit — under `jdwp_set_logpoint`. `jdwp_set_logpoint` stays the default; reach for the fast variant only when suspending on every hit would distort what you are looking at.

### Deferred breakpoints

By default, every line BP, logpoint, exception BP, and field BP is registered **passively**: if the target class isn't loaded when the breakpoint is set, the server registers a `ClassPrepareRequest` and promotes the breakpoint to active when the JVM loads the class on its own — same behaviour as IntelliJ / Eclipse / jdb.

```
jdwp_set_breakpoint(className="com.example.LazyService", lineNumber=15)
→ "Breakpoint deferred — class com.example.LazyService not yet loaded. Will activate on class load."
```

`jdwp_overview()` shows pending breakpoints with their status.

Pass `forceLoad=true` only when you need the breakpoint to bind *immediately* — the server will call `Class.forName` in the target VM to load the class up front. That runs the class's `<clinit>` (early static init, eager dependency loads), which can mask lazy-init / classloader-leak diagnostics. Use sparingly.

### Exception breakpoints

```
jdwp_set_exception_breakpoint(
  exceptionClass="java.lang.NullPointerException",
  caught=true,
  uncaught=true
)
```

Catch exceptions at the throw site — before the stack unwinds. Supports deferred activation (if the exception class isn't loaded yet). Use `jdwp_overview(types="exception_breakpoint")` to see active and pending exception breakpoints.

**Log-only mode** — record the throw without suspending the thread, evaluating an expression with `$exception` bound to the thrown object. Use the dedicated `jdwp_set_exception_logpoint` tool:

```
jdwp_set_exception_logpoint(
  exceptionClass="java.sql.SQLException",
  expression="$exception.getSQLState() + \": \" + $exception.getMessage()"
)
```

Each hit records an `EXCEPTION_LOG` entry to `jdwp_get_events`; failures during evaluation surface as `EXCEPTION_LOG_ERROR` so the listener never throws. Use this to trace exception flows in long-running services without stopping them.

### Field breakpoints (watchpoints)

```
jdwp_set_field_breakpoint(
  className="com.example.OrderState",
  fieldName="status",
  mode="modification"
)
```

Suspend whenever a field is read (`access`), written (`modification`), or both (`both` — IntelliJ-style, binds both directions to one synthetic ID). Conditions are evaluated against the firing frame with five synthetic bindings:

- `$oldValue` — value before the event (the value being read, or the value about to be overwritten)
- `$newValue` — value about to be written (modification events only)
- `$object` — the instance the field belongs to, or `null` for static fields
- `$fieldName` — the field name as a string
- `$mode` — `"access"` or `"modification"`, identifying which direction fired

**Log-only mode** — record reads/writes without suspending, evaluating an expression with the same bindings:

```
jdwp_set_field_logpoint(
  className="com.example.OrderState",
  fieldName="status",
  mode="modification",
  expression="$oldValue + \" -> \" + $newValue"
)
```

Each hit records a `FIELD_LOGPOINT` entry to `jdwp_get_events`; failures surface as `FIELD_LOGPOINT_ERROR`. Filters (`threadFilterId`, `objectFilterId`) and trigger chaining (`triggerBreakpointId`, `oneShot`) work the same as for line and exception BPs. Deferred activation is supported — the watchpoint installs the moment the declaring class loads, so static-initializer writes are caught.

Hard errors (no silent fallback): invalid `mode`, ambiguous or missing field, `objectFilterId` on a static field. The deferred path can't validate static-ness until class load, so it surfaces a warning Note in the response.

**Performance:** field watchpoints fire on **every** read/write of the watched field. For hot fields they can dominate target-VM CPU — prefer narrow filters or short-lived sessions. `jdwp_diagnose` surfaces `canWatchFieldAccess` / `canWatchFieldModification` plus a perf warning when connected.

Use `jdwp_overview(types="field_breakpoint")` to see active and pending field breakpoints with chain status, mode, and any pending failure reason.

### Chained breakpoints

```
jdwp_set_breakpoint(
  className="com.example.OrderProcessor",
  lineNumber=87,
  triggerBreakpointId=12,
  oneShot=true
)
```

Make one breakpoint depend on another. The dependent BP stays disabled until its trigger fires; from then on it's armed and behaves normally. Use this when a hit is uninteresting unless reached via a specific code path — e.g., suspend at the order-pricing line only after a "VIP customer" path has been entered, or break inside a generic utility only when called from a specific feature.

Two modes:

- **Sticky** (default, `oneShot=false`) — once the trigger fires, the dependent stays armed forever. Use when you want to filter by an early gate but observe every subsequent hit.
- **One-shot** (`oneShot=true`) — the dependent self-disarms after firing, so the next hit requires the trigger to fire again. Matches IntelliJ's "Remove once hit" behavior.

Chains compose: a trigger BP can itself be a dependent of an earlier trigger. Cycles are rejected at registration time with the offending path in the error message. Pending (not-yet-loaded) BPs are first-class participants — a chained dependent registered against a class that hasn't loaded yet is promoted with the chain bit intact, so the very first event after class load can't fire before the trigger has. Trigger fires that happen while the dependent is still pending are remembered, so a deferred dependent isn't penalised for arriving late.

Both line breakpoints and exception breakpoints can be chain dependents and chain triggers; mix them freely.

Manage chains with:

- `jdwp_set_breakpoint_dependency(dependentId, triggerId, oneShot?)` — add an edge between two existing BPs (active or pending)
- `jdwp_clear_breakpoint_dependency(dependentId)` — detach the edge and re-arm the dependent
- `jdwp_disarm_until_trigger(dependentId)` — manually re-disable a dependent without changing its trigger (e.g., after a one-shot fired and you want another round)

When the trigger BP is removed, every dependent is automatically armed and a `CHAIN_BROKEN` event is recorded for each — `jdwp_get_events` shows exactly which BPs lost their guard. `jdwp_diagnose` recognises the "every armed BP is waiting on a trigger that hasn't fired" state and tells you why no BP is currently firing, including any pending dependents waiting on class load.

### Expression evaluation

```
jdwp_evaluate_expression(
  threadId=25,
  expression="request.getData().get(\"_domain\")",
  frameIndex=0
)
→ "self.operationTypeSelect = 3"
```

Compiles arbitrary Java expressions to bytecode using Eclipse JDT, injects them into the target JVM via `ClassLoader.defineClass()`, and executes them in the context of the suspended frame. Full classpath is discovered automatically (including container classloaders like Tomcat). Results are cached for performance. Handles Guice/CGLIB proxies automatically.

Two input modes:

- **Single expression** — `order.getTotal()`, `x + y`, `list.size() == 5`. The compiler treats the input as the body of `return ...;`.
- **Block mode** — wrap the input in `{ ... }` to send a multi-statement body with try/catch, intermediate locals, or early `return`. The agent writes explicit `return X;` to yield a value. Available everywhere expressions are accepted: `jdwp_evaluate_expression`, `jdwp_assert_expression`, breakpoint conditions, logpoint expressions, watchers.

See [docs/expression-evaluation.md](docs/expression-evaluation.md) for the compilation pipeline details, or [docs/index.md](docs/index.md) for the full developer reference.

### Assertions

```
jdwp_assert_expression(
  expression="order.getStatus()",
  expected="CONFIRMED",
  threadId=25
)
→ "OK" or "MISMATCH: actual='PENDING', expected='CONFIRMED'"
```

Evaluate and compare in one call — useful for agents running verification sequences.

### Value mutation

```
jdwp_set_local(threadId=25, frameIndex=0, varName="retryCount", value="3")
jdwp_set_field(objectId=26886, fieldName="limit", value="100")
```

Change local variables and object fields at runtime. The agent can test hypotheses ("what if this value were different?") without restarting the application.

### Marked instances

```
jdwp_mark_instance(label="input", objectId=27531)
```

Pin a cached object in the target heap and label it as `$label`, so the agent can reference it across many events from inside expressions — conditions, logpoints, watchers, assertions. Reserved bindings (`$exception`, `$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`, `_this`) and label collisions are rejected. Pinned by default via `disableCollection`; pass `pin=false` to allow natural GC.

### Watchers

Attach persistent expressions to breakpoints that are evaluated automatically on every hit:

```
jdwp_attach_watcher(breakpointId=27, label="request data", expression="request.getData()")
jdwp_attach_watcher(breakpointId=27, label="user context", expression="request.getUser().getName()")

# Later, when breakpoint 27 fires:
jdwp_evaluate_watchers(threadId=25, scope="current_frame", breakpointId=27)
```

Watchers are MCP-side state — they survive across breakpoint hits and are cleaned up when the breakpoint is deleted.

### Recursive breakpoint protection

When an expression evaluation at a breakpoint re-enters the breakpointed line (e.g., `this.compute(n - 1)` evaluated inside `compute`), JDI would re-suspend the thread and deadlock the server. This server wraps every `invokeMethod` chain in a per-thread reentrancy guard:

1. Recursive breakpoint/exception/step events are **auto-resumed** instead of suspending
2. A `BREAKPOINT_SUPPRESSED` / `EXCEPTION_SUPPRESSED` / `STEP_SUPPRESSED` entry is recorded in event history
3. The outer breakpoint context is preserved

**Covered invocation sites:** `jdwp_evaluate_expression`, `jdwp_assert_expression`, `jdwp_evaluate_watchers`, logpoint evaluation, conditional breakpoint evaluation, `jdwp_to_string`, classpath discovery, and deferred class loading via `Class.forName`.

### Smart filtering

**Threads:** `jdwp_get_threads()` hides JVM internals (Reference Handler, Finalizer, surefire workers) by default. Pass `includeSystemThreads=true` to see everything. Defaults to a compact 1-line table per thread (a 200-thread Tomcat renders in ~25 lines); pass `verbose=true` for the legacy block-per-thread format.

**Stack frames:** `jdwp_get_stack()` collapses junit/surefire/reflection noise frames by default. Pass `includeNoise=true` to see the full stack.

### Blocking resume & soft-wait

```
jdwp_resume_until_event(timeoutMs=30000)
→ blocks until next breakpoint/step/exception, returns context immediately
```

Replaces the manual "resume → poll events → poll events" pattern. The agent resumes and gets the next stop in one synchronous call. Returns one of:

- `Event fired …` — a BP / step / exception suspended a thread; the message includes thread + stop site
- `[TIMEOUT] …` — the wait expired without an event; the message offers `wait_more` / `reconnect` / `abort` and reports JDI health so the agent never blindly hangs
- `[VM_DEATH] …` — the target VM died before any event
- `[NO_EVENT] …` — the wait was released without a stop (e.g., a passive disarm); the message states whether the session is still armed or was cleared

The same soft-wait envelope wraps `jdwp_wait_for_attach`, so bootstrap and resume share one "wait sensibly, decide deliberately" idiom.

### Lock-graph dump for deadlock investigations

```
jdwp_dump_locks()
→ shows monitor holders, blocked threads, and any deadlock cycles
```

Takes a transient VM-wide suspend/resume snapshot to enumerate Java monitor ownership — who is blocked on what, who holds it, and any deadlock cycle (lock-ordering inversions are surfaced explicitly). Genuine deadlocks stay deadlocked after the snapshot resumes (the probe is read-only in practice). Only synchronized-monitor contention shows; `Object.wait()` and `java.util.concurrent` locks are out of scope by design.

### Reconnect without losing your BPs

```
jdwp_reconnect()
→ re-attaches to the last target; BP IDs, conditions, logpoints, chains, watchers all survive
```

When the target VM is restarted, or the JDI session wedges and you need a fresh transport, `jdwp_reconnect` re-attaches to the most recent endpoint and replays every BP spec onto the new VM. Synthetic BP IDs are preserved — BP #7 stays #7 — so the agent doesn't have to re-derive its bookkeeping. The marks registry, object cache, last-thread pointer, and classpath cache do not survive `vm.dispose()` and are cleared by design.

### One-shot breakpoint context

```
jdwp_get_breakpoint_context(maxFrames=5, includeThisFields=true)
```

Returns thread info, top stack frames, locals at frame 0, and `this` fields in a single call — replaces the four-call sequence `get_current_thread → get_stack → get_locals → get_fields(this)` that an agent would otherwise need at every breakpoint hit.

### Stepping: when it's worth it

`jdwp_step_over` / `jdwp_step_into` / `jdwp_step_out` are wired through the same event-and-latch pipeline as breakpoints — the step resumes the thread, the next `STEP` event suspends it again, and `jdwp_resume_until_event` blocks on it. `threadId` is optional; omitted, the step targets the thread of the last breakpoint hit.

Each step is one round-trip, so prefer a breakpoint at the destination + `jdwp_resume_until_event` whenever you can predict where execution will go. Stepping pays off in three narrow situations:

- **`step_into`** — polymorphic dispatch is unclear; you can't tell from source which override will run.
- **`step_out`** — an exception/early-abort left you deep in a frame you don't care about and finding the caller line for a breakpoint is awkward.
- **`step_over`** — the *single* next line, when observing a state mutation is faster than predicting it. More than ~3 in a row → set a breakpoint instead.

## Tool reference (48 tools)

### Connection (4)

| Tool                   | Parameters                     | Description                                                                                          |
|------------------------|--------------------------------|------------------------------------------------------------------------------------------------------|
| `jdwp_connect`         | —                              | Connect to JDWP on configured host:port                                                              |
| `jdwp_disconnect`      | —                              | Disconnect (sends JDWP Dispose) and clears session state                                             |
| `jdwp_reconnect`       | —                              | Re-attach to the last target; BP IDs, conditions, logpoints, chains, watchers replay onto the new VM |
| `jdwp_wait_for_attach` | `host?`, `port?`, `timeoutMs?` | Poll until JVM is listening, then attach (soft-wait envelope at the ceiling)                         |

### Inspection (9)

| Tool                          | Parameters                                | Description                                           |
|-------------------------------|-------------------------------------------|-------------------------------------------------------|
| `jdwp_get_version`            | —                                         | JVM version info                                      |
| `jdwp_get_threads`            | `includeSystemThreads?`, `verbose?`       | List threads with status and frame counts (compact 1-line table by default) |
| `jdwp_dump_locks`             | `includeSystemThreads?`                   | Monitor lock graph: holders, blocked threads, deadlock cycles |
| `jdwp_get_stack`              | `threadId`, `maxFrames?`, `includeNoise?` | Stack trace (noise frames collapsed by default)       |
| `jdwp_get_locals`             | `threadId`, `frameIndex`                  | Local variables at a frame (includes `this`)          |
| `jdwp_get_fields`             | `objectId`                                | Object fields, collection elements, or array contents |
| `jdwp_to_string`              | `objectId`, `threadId?`                   | Invoke `toString()` on a cached object                |
| `jdwp_get_breakpoint_context` | `maxFrames?`, `includeThisFields?`        | One-shot context dump at current breakpoint           |
| `jdwp_get_current_thread`     | —                                         | Thread ID of the last breakpoint hit                  |

### Execution control (7)

| Tool                      | Parameters   | Description                                                  |
|---------------------------|--------------|--------------------------------------------------------------|
| `jdwp_resume`             | —            | Resume all threads                                           |
| `jdwp_resume_thread`      | `threadId`   | Resume a specific thread                                     |
| `jdwp_suspend_thread`     | `threadId`   | Suspend a specific thread                                    |
| `jdwp_resume_until_event` | `timeoutMs?` | Resume and block until next breakpoint/step/exception (soft-wait envelope at the ceiling) |
| `jdwp_step_over`          | `threadId?`  | Step over current line; follow with `resume_until_event`     |
| `jdwp_step_into`          | `threadId?`  | Step into method call; follow with `resume_until_event`      |
| `jdwp_step_out`           | `threadId?`  | Step out of current frame; follow with `resume_until_event`  |

### Breakpoints (8)

| Tool                              | Parameters                                                | Description                                         |
|-----------------------------------|-----------------------------------------------------------|-----------------------------------------------------|
| `jdwp_set_breakpoint`             | `className`, `lineNumber`, `suspendPolicy?`, `condition?`, `triggerBreakpointId?`, `oneShot?`, `forceLoad?` | Set line breakpoint (conditions, deferred-by-default, trigger chaining; `forceLoad=true` binds immediately and runs `<clinit>`) |
| `jdwp_set_logpoint`               | `className`, `lineNumber`, `expression`, `condition?`, `forceLoad?` | Non-stopping line breakpoint that logs expression result |
| `jdwp_set_fast_logpoint`          | `className`, `lineNumber`, `expression`, `condition?`, `userApproved?`, `bufferSize?`, `forceLoad?` | **Rare, consent required.** Logpoint evaluated inside the target by injected bytecode — hits never suspend. Without `userApproved=true` it arms nothing and returns a risk briefing |
| `jdwp_restore_fast_logpoints`     | `id`, `userApproved?`                                     | **Rare, consent required.** Restores the original bytecode of a class carrying fast logpoints an earlier, crashed session left behind (`RECOVERED` in `jdwp_overview`). Without `userApproved=true` it changes nothing and returns a briefing |
| `jdwp_clear_breakpoint`           | `breakpointId`                                            | Remove a breakpoint by ID — routes by kind across line, exception, field BPs and fast logpoints (restoring the original bytecode; a leftover of an earlier session is only switched off) |
| `jdwp_set_exception_breakpoint`   | `exceptionClass`, `caught?`, `uncaught?`, `triggerBreakpointId?`, `oneShot?` | Suspend on exception throw (supports deferred and trigger chaining) |
| `jdwp_set_exception_logpoint`     | `exceptionClass`, `expression`, `condition?`, `caught?`, `uncaught?`, `triggerBreakpointId?`, `oneShot?` | Non-stopping exception breakpoint with `$exception` bound |
| `jdwp_set_field_breakpoint`       | `className`, `fieldName`, `mode`, `condition?`, `threadFilterId?`, `objectFilterId?`, `triggerBreakpointId?`, `oneShot?` | Suspend on field access/modification/both (supports conditions, filters, deferred, chaining) |
| `jdwp_set_field_logpoint`         | `className`, `fieldName`, `mode`, `expression`, `condition?`, `threadFilterId?`, `objectFilterId?`, `triggerBreakpointId?`, `oneShot?` | Non-stopping field watchpoint with `$oldValue`/`$newValue`/`$object`/`$fieldName`/`$mode` bound |

(For listing or bulk-clearing breakpoints across any combination of kinds, see `jdwp_overview` and `jdwp_clear` in the Debug state section below.)

### Breakpoint chains (3)

| Tool                               | Parameters                            | Description                                                                   |
|------------------------------------|---------------------------------------|-------------------------------------------------------------------------------|
| `jdwp_set_breakpoint_dependency`   | `dependentId`, `triggerId`, `oneShot?` | Make `dependentId` depend on `triggerId` firing first; cycles are rejected    |
| `jdwp_clear_breakpoint_dependency` | `dependentId`                          | Remove the chain edge and re-arm the dependent                                |
| `jdwp_disarm_until_trigger`        | `dependentId`                          | Re-disable a dependent that already has a chain (e.g., after a one-shot fired) |

### Expression evaluation and mutation (4)

| Tool                       | Parameters                                          | Description                                                       |
|----------------------------|-----------------------------------------------------|-------------------------------------------------------------------|
| `jdwp_evaluate_expression` | `threadId`, `expression`, `frameIndex?`             | Evaluate Java expression at suspended frame (supports `{ block }`) |
| `jdwp_assert_expression`   | `expression`, `expected`, `threadId?`, `frameIndex?` | Evaluate and compare against expected value                       |
| `jdwp_set_local`           | `threadId`, `frameIndex`, `varName`, `value`        | Set a local variable's value                                      |
| `jdwp_set_field`           | `objectId`, `fieldName`, `value`                    | Set a field's value on a cached object                            |

### Events (2)

| Tool                | Parameters | Description                                               |
|---------------------|------------|-----------------------------------------------------------|
| `jdwp_get_events`   | `count?`   | Recent events (breakpoints, steps, exceptions, logpoints, exception logs, chain events) |
| `jdwp_clear_events` | —          | Clear event history                                       |

### Diagnostics (1)

| Tool            | Parameters    | Description                                                                                                                                            |
|-----------------|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jdwp_diagnose` | `inspectAll?` | Three-block "state of the world" snapshot: (1) MCP server (PID/uptime/configured target), (2) JDWP connection — last-attempt error when disconnected, breakpoints+events report when connected, (3) Local JVMs visible to the user with their JDWP ports (confirmed via handshake). Pass `inspectAll=true` to attach briefly to every same-user JVM whose port could not be read from `/proc`, to discover the port via `sun.jdwp.listenerAddress` (default false — attaches are visible to targets). Recognises the chain-stuck state where every armed BP is WAITING on a non-fired trigger. Run this first when nothing seems to work. |

### Watchers (4)

| Tool                                | Parameters                            | Description                                         |
|-------------------------------------|---------------------------------------|-----------------------------------------------------|
| `jdwp_attach_watcher`               | `breakpointId`, `label`, `expression` | Attach expression watcher to a breakpoint           |
| `jdwp_detach_watcher`               | `watcherId`                           | Remove a watcher                                    |
| `jdwp_list_watchers_for_breakpoint` | `breakpointId`                        | List watchers on a specific breakpoint              |
| `jdwp_evaluate_watchers`            | `threadId`, `scope`, `breakpointId?`  | Evaluate watchers (`current_frame` or `full_stack`) |

### Marked instances (3)

| Tool                     | Parameters                       | Description                                                                              |
|--------------------------|----------------------------------|------------------------------------------------------------------------------------------|
| `jdwp_mark_instance`     | `label`, `objectId`, `pin?`      | Label a cached object as `$label` so expressions (conditions, logpoints, watchers) can reference it; pinned by default (`disableCollection`) |
| `jdwp_unmark_instance`   | `label`                          | Remove a mark and release its pin                                                        |
| `jdwp_rename_mark`       | `oldLabel`, `newLabel`           | Rename a mark, preserving its pin and underlying object                                  |

### Debug state (2)

| Tool            | Parameters         | Description                                                                                                          |
|-----------------|--------------------|----------------------------------------------------------------------------------------------------------------------|
| `jdwp_overview` | `types?`, `filter?`, `showEmpty?` | Unified read-only listing of breakpoints, exception breakpoints, field breakpoints, logpoints, fast logpoints, watchers, and marks. Filter by type subset and/or case-insensitive substring (class/label/expression/type). |
| `jdwp_clear`    | `types`, `filter?`  | Bulk-delete by type and/or substring filter. `types` is REQUIRED (use `'all'` to clear every supported kind). To preview, call `jdwp_overview` with the same args first. |

### Session (1)

| Tool         | Parameters | Description                                                                          |
|--------------|------------|--------------------------------------------------------------------------------------|
| `jdwp_reset` | —          | Clear all state (breakpoints, watchers, marks, cache, events) without disconnecting  |

## Resources (2)

In addition to tools, the server exposes two read-only MCP **resources**. In Claude Code, type `@` in the prompt and pick them from the autocomplete (URI form: `@jdwp-inspector:jdwp://...`). Attaching a resource pulls its rendered text straight into the prompt without spending a model turn on a tool call — handy for "is my target up, and on which port?" without involving the agent.

| Resource          | URI               | Content                                                                                                                                |
|-------------------|-------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| JDWP diagnose     | `jdwp://diagnose` | Same three-block snapshot as the `jdwp_diagnose` tool with `inspectAll=false`: MCP-server status, JDWP connection (or last-attempt error), local-JVM inventory with detected ports. |
| Local JVMs        | `jdwp://jvms`     | Local-JVM inventory only — which Java processes are running, which expose a JDWP agent, and the state of each port (LISTENING / SUSPENDED / UNREACHABLE / …). Cheaper than `jdwp://diagnose` when you only need a port list. |

Note: resource updates are not pushed to the client — re-attach the URI to see fresh content. For probe-the-world style refreshes (briefly attach to every same-user JVM to learn its port), use the `jdwp_diagnose` tool with `inspectAll=true`; the resources do not run those attaches.

## Usage workflows

### Debugging a REST request

```
1. Launch your app with JDWP enabled
2. In Claude Code:
   "Set a breakpoint in OrderService.createOrder line 42 and wait for a hit"

3. Claude:
   jdwp_connect()
   jdwp_set_breakpoint("com.example.OrderService", 42)
   jdwp_resume_until_event(timeoutMs=60000)
   jdwp_get_breakpoint_context()
   → "Thread http-nio-8080-exec-3 stopped at OrderService:42.
      Local 'order' has total=0.0, status=null — looks like
      the order wasn't initialized before reaching this line."
```

### Non-intrusive tracing with logpoints

```
1. "Add a logpoint to trace every order over $1000"

2. Claude:
   jdwp_set_logpoint(
     "com.example.OrderService", 42,
     "\"order=\" + order.getId() + \" total=\" + order.getTotal()",
     "order.getTotal() > 1000"
   )
   jdwp_resume()

3. Later:
   jdwp_get_events(count=20)
   → Shows LOGPOINT entries with evaluated expressions, no thread was ever stopped
```

### Catching exceptions at the throw site

```
1. "I'm getting a NullPointerException somewhere in the order flow"

2. Claude:
   jdwp_set_exception_breakpoint("java.lang.NullPointerException", caught=true, uncaught=true)
   jdwp_resume_until_event(timeoutMs=60000)
   jdwp_get_breakpoint_context()
   → "NullPointerException thrown at OrderValidator:87.
      Local 'customer' is null — the order was submitted without a customer reference."
```

### Test flight: recursive breakpoint scenario

The `jdwp-sandbox` module includes a deterministic scenario (`one.edee.jdwp.sandbox.recursion` package) that reproduces the recursive breakpoint case:

```bash
# Terminal 1 — launch sandbox, suspended on port 5005
./mvnw -pl jdwp-sandbox test -Dtest=RecursiveCalculatorTest -DskipTests=false -Dmaven.surefire.debug

# From Claude Code:
jdwp_wait_for_attach()
jdwp_set_breakpoint("one.edee.jdwp.sandbox.recursion.RecursiveCalculator", 22)
jdwp_resume_until_event()
# → BP fires inside compute(5)
jdwp_evaluate_expression(threadId, "this.compute(3)")
# → returns 2 without deadlock
jdwp_get_events()
# → shows BREAKPOINT_SUPPRESSED entries for each recursive hit
```

## Architecture

```
Claude Code ──MCP/STDIO──> Spring Boot MCP Server ──JDI──> Target JVM (port 5005)
```

The server is `SYNC` mode, `web-application-type=none` — JSON over STDIO, no HTTP.

### Core components

| Component                | Role                                                                                                                                               |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| **JDWPTools**            | 47 `@McpTool` methods — the MCP surface. Thin orchestration over services below.                                                                   |
| **JDIConnectionService** | Singleton `VirtualMachine` connection. Object cache (`ConcurrentHashMap<Long, ObjectReference>`), smart collection rendering, classpath discovery. |
| **BreakpointTracker**    | Breakpoint registry with synthetic IDs. Tracks pending/deferred state, conditions, logpoint expressions, exception breakpoints, and chain dependencies (with cycle detection and trigger-fire memory across pending → active promotion). |
| **JdiEventListener**     | Daemon thread consuming the JDI event queue. Routes events, evaluates conditions/logpoints, handles recursive suppression.                         |
| **EvaluationGuard**      | Per-thread reentrancy guard preventing deadlocks during expression evaluation.                                                                     |
| **EventHistory**         | Ring buffer of the last 100 JDWP events (including suppressed).                                                                                    |
| **JdiHealthMonitor**     | Probes the JDI transport so soft-wait responses can report `wait_more` / `reconnect` / `abort` instead of blindly hanging.                          |
| **DeadlockAnalyzer**     | Builds the monitor-lock graph and finds lock-ordering cycles for `jdwp_dump_locks`.                                                                |
| **MarkedInstanceRegistry** (`marks/`) | Registry of named, pinned object references (`$label`) usable from condition / logpoint / watcher / assertion expressions.            |
| **JvmDiscoveryService** (`discovery/`) | Enumerates local JVMs, parses `-agentlib:jdwp=…` arguments, and confirms JDWP ports via handshake — powers `jdwp_diagnose` and the `jdwp://jvms` resource. |
| **MultiVersionStdioServerTransportProvider** (`transport/`) | Overrides Spring AI's hardcoded MCP protocol version on the STDIO transport so the server announces whatever Claude Code negotiates. |

### Expression evaluation pipeline (`evaluation/`)

1. **JdiExpressionEvaluator** — Analyzes the stack frame, generates a wrapper class with a UUID name, delegates compilation, caches results.
2. **ClasspathDiscoverer** — Walks target JVM classloader hierarchy (including Tomcat/container) to find all JARs. Uses **JdkDiscoveryService** to locate a local JDK matching the target version.
3. **LocalProjectClasspathProvider** — Additive fallback for when the target's classloader hierarchy hides JARs (Tomcat, Spring Boot dev-tools, custom `URLClassLoader`s). Composes `JDWP_EXTRA_CLASSPATH` (override), a depth-5 scan of `target/classes` / `target/test-classes` under the server's CWD, and `mvn dependency:build-classpath`. Set `JDWP_EXTRA_CLASSPATH=/path/extra.jar:/path/more.jar` (colon/semicolon-separated) to plug specific gaps; `jdwp_diagnose` shows a per-source breakdown. Details in [docs/expression-evaluation.md](docs/expression-evaluation.md#localprojectclasspathprovider--local-project-classpath-fallback).
4. **InMemoryJavaCompiler** — Compiles Java source to bytecode using Eclipse JDT (ECJ), entirely in memory.
5. **RemoteCodeExecutor** — Injects bytecode via `ClassLoader.defineClass()` and invokes it.

### Watcher system (`watchers/`)

- **WatcherManager** — CRUD, dual-indexed by watcher UUID and breakpoint ID. Auto-cleans when breakpoint is deleted.
- **Watcher** — Immutable model: id, label, breakpointId, expression.

## Project structure

```
mcp-jdwp-java/
├── pom.xml                              # Parent POM (reactor)
├── mvnw / mvnw.cmd                      # Maven wrapper
├── README.md
├── CHANGELOG.md
├── .mcp.json                            # MCP server configuration
├── .claude-plugin/
│   ├── plugin.json                      # Claude Code plugin metadata
│   └── marketplace.json                 # Plugin marketplace registry
├── hooks/
│   └── hooks.json                       # SessionStart hook: auto-builds JAR if missing or git HEAD moved
├── skills/
│   ├── java-debug/                      # Debugging skill (workflows, recipes, gotchas)
│   └── release/                         # Release-cutting skill
├── docs/                                # Developer reference (architecture, lifecycle, …)
│
├── jdwp-mcp-server/                     # The MCP server
│   ├── pom.xml
│   └── src/main/java/one/edee/mcp/jdwp/
│       ├── JDWPMcpServerApplication.java
│       ├── JDWPTools.java               # 47 @McpTool methods + 2 @McpResource
│       ├── JDIConnectionService.java    # JDI connection + object cache
│       ├── BreakpointTracker.java       # Breakpoint registry + deferred state + chains
│       ├── JdiEventListener.java        # JDI event consumer
│       ├── EvaluationGuard.java         # Recursive breakpoint protection
│       ├── EventHistory.java            # Event ring buffer
│       ├── JdiHealthMonitor.java        # Transport liveness for soft-wait
│       ├── DeadlockAnalyzer.java        # Monitor-lock graph + cycle detection
│       ├── ClassNameMatcher.java        # Glob → JDI ReferenceType matcher
│       ├── ThreadFormatting.java        # Thread/frame noise filtering
│       ├── evaluation/                  # Expression compile-and-inject pipeline
│       │   ├── JdiExpressionEvaluator.java
│       │   ├── RemoteCodeExecutor.java
│       │   ├── InMemoryJavaCompiler.java
│       │   ├── ClasspathDiscoverer.java
│       │   └── JdkDiscoveryService.java
│       ├── marks/                       # Named, pinned heap references ($label)
│       │   ├── MarkedInstanceRegistry.java
│       │   ├── MarkInfo.java
│       │   └── ReservedBindings.java
│       ├── discovery/                   # Local-JVM inventory + JDWP port probing
│       │   ├── JvmDiscoveryService.java
│       │   ├── DiagnoseReportRenderer.java
│       │   ├── JdwpAgentArgParser.java
│       │   ├── JdwpEndpoint.java
│       │   ├── JvmDescriptor.java
│       │   └── exceptions/
│       ├── transport/                   # STDIO transport overrides
│       │   ├── MultiVersionStdioServerTransportProvider.java
│       │   └── StdioTransportConfig.java
│       └── watchers/                    # Breakpoint-attached expression watchers
│           ├── WatcherManager.java
│           └── Watcher.java
│
└── jdwp-sandbox/                        # Debugging targets (test flights)
    ├── pom.xml                          # Tests skipped by default
    └── src/                             # Deliberately broken scenarios
```

## Dependencies

- **Spring Boot 4.0.5** — Framework
- **Spring AI MCP 2.0.0-M4** — MCP protocol integration
- **JDI** (`jdk.jdi` module) — Java Debug Interface
- **Eclipse JDT Compiler (ECJ)** — In-memory expression compilation
- **JSpecify + NullAway** — Compile-time nullness enforcement

## Technical documentation

For Java developers who want to understand the internals — safety guarantees, memory allocation, threading, bootstrapping, state clearing, the test architecture — the [`docs/`](docs/) folder is the developer reference.

Start at **[docs/index.md](docs/index.md)** for navigation and a reading guide. The chapters cover:

- [architecture.md](docs/architecture.md) — system overview, components, data flow
- [lifecycle.md](docs/lifecycle.md) — bootstrapping, connect, disconnect, reset, the clearing matrix
- [threading-and-safety.md](docs/threading-and-safety.md) — concurrency, MCP SYNC, `INVOKE_SINGLE_THREADED`, the `EvaluationGuard` reentrancy mechanism
- [memory-and-references.md](docs/memory-and-references.md) — JDI mirrors, the object cache, marks, the compilation cache, byte-array mirroring
- [event-pipeline.md](docs/event-pipeline.md) — the JDI event listener loop and the suspension decision matrix
- [breakpoints.md](docs/breakpoints.md) — the registry, synthetic IDs, deferred breakpoints, conditions, logpoints, chains
- [expression-evaluation.md](docs/expression-evaluation.md) — the compile-and-inject pipeline (ECJ, `defineClass`, `invokeMethod`)
- [diagnostics.md](docs/diagnostics.md) — `jdwp_diagnose`, JVM discovery, the handshake probe, transport-loss envelopes, logging
- [testing.md](docs/testing.md) — test architecture for contributors

## Troubleshooting

| Problem                                         | Solution                                                                                                                                                            |
|-------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `tools.jar not found` / `jdk.jdi not available` | Ensure `JAVA_HOME` points to a JDK, not a JRE. Launch with `--add-modules jdk.jdi`.                                                                                 |
| Connection refused                              | Verify target JVM has `-agentlib:jdwp=...address=*:5005`. Check port matches `-DJVM_JDWP_PORT`.                                                                     |
| MCP server doesn't respond                      | Rebuild: `./mvnw clean package -DskipTests`. Check jar path. Restart Claude Code.                                                                                   |
| MCP server times out on startup                 | JVM startup takes several seconds. Ensure `MCP_TIMEOUT=30000` (or higher) is set in the MCP registration — the default is too short for a Spring Boot Java process. |
| "Thread is not suspended"                       | The thread must be stopped at a breakpoint for stack/locals/expression tools.                                                                                       |
| Expression evaluation timeout                   | First evaluation is slow (classpath discovery). Increase `MCP_TOOL_TIMEOUT`. Subsequent evaluations use cache.                                                      |
| `resume_until_event` returns `[TIMEOUT]` / `[NO_EVENT]` | The wait expired without a stop, or the session was disarmed. The response message says whether the session is still armed (just call again), wedged (try `jdwp_reconnect`), or cleared (re-set BPs). |
| Build fails with Error Prone / toolchain error  | Building from source needs JDK 21+ on the toolchain. The bytecode target stays at Java 17, but the build step itself needs ≥21 because Error Prone 2.48 ships Java-21 bytecode. |

## License

MIT
