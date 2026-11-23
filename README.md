# MCP JDWP Inspector

MCP server that gives AI agents full debugger control over running Java applications — inspect state, set breakpoints, evaluate expressions, and mutate values at runtime via JDWP/JDI.

> **Release notes** — see [`CHANGELOG.md`](CHANGELOG.md).

**Built on the foundations of [mcp-jdwp-java](https://github.com/NicolasVautrin/mcp-jdwp-java) by [Nicolas Vautrin](https://github.com/NicolasVautrin)** — the original project that provided core JDI connectivity, thread/stack/variable inspection, stepping, and basic breakpoint management. Everything described below as "beyond standard JDWP" was built on top of that base.

## What you get (TL;DR)

An MCP server that speaks **JDWP** — the same protocol IntelliJ / Eclipse / VS Code use to attach — and exposes the debugger to Claude Code and Codex as **50 tools + 2 MCP resources** over STDIO. Once the agent attaches to your JVM, it can:

- **See runtime state, not stack traces** — read locals, fields, threads, the entire object graph
- **Evaluate Java in the suspended frame** — read private fields through JDI and compile expressions that require target code
- **Mutate the running program** — set locals, write fields, test "what if?" without restart
- **Stop on the bad case only** — conditional breakpoints, exception breakpoints at the throw site, deferred BPs that arm before the class loads, chains that gate one BP on another
- **Trace without stopping** — line/exception/field logpoints write to an event log; the hitting thread pauses only while the server evaluates the expression, then runs on
- **Probe hot code at full speed** — with the user's consent, fast logpoints and fast breakpoints evaluate inside the target JVM: hits that do not match never reach the debugger, and the method stays JIT-compiled
- **Catch the writer of a mutating field** — watchpoints suspend at every bytecode store to the field, wherever it is; a write through reflection or `Unsafe` is the one they cannot see, and the `[VM_DEATH]` reply's hit count shows when a watchpoint stayed silent
- **Pure STDIO** — no daemon, no extra port, no GUI

The agent gets the same power as IntelliJ's debugger, with extras that exist specifically because an agent isn't a human staring at a UI (filtering, blocking resume, one-shot context dumps, recursion guards, reconnect after a wedge).

## Security & trust

This MCP server runs **on your machine**, as a child process of the agent:

- **No phone-home** — the server talks to the agent over STDIO and to the target JVM over a JDWP socket (local by default; `jdwp_wait_for_attach` accepts another host). It sends no HTTP requests of its own: no telemetry, no analytics. Two things around it can use the network:
  - **Getting the JAR** — see the next point.
  - **Maven, for expression evaluation** — when the server attaches and the project directory holds a `pom.xml`, it runs `mvn dependency:build-classpath` there in the background (`./mvnw` when the project has it) to find JARs the target's class loaders hide. Maven may download the project's dependencies from the repositories the project configures. Each run requests a private temporary output directory and removes it after discovery. A dependency plugin `outputFile` configured in the POM overrides that destination.
- **Built from source, or the release JAR of this repository** — under Claude Code, the plugin's SessionStart hook compiles the JAR on your machine from the sources in this repository (or build it yourself with `./mvnw clean package`); the build fetches Maven and the dependencies from Maven Central on first use. Under Codex, the plugin's launcher downloads `mcp-jdwp-java-<version>.jar` from this repository's GitHub release of the installed version when the installed server sources are the ones tagged for that release (a copy without git history is trusted by its version number); otherwise, or when the download fails, it compiles from source like the hook. Every release attaches that JAR, built from the tagged sources by the release workflow in GitHub Actions.
- **What it does to the target** — what any debugger does: it reads and changes the target's state and runs code in it (expression evaluation defines small wrapper classes in the target). Rewriting the application's own bytecode — fast logpoints and fast breakpoints — requires the user's approval of a risk briefing. That approval covers the same probe on later JVMs at the same endpoint for the MCP server's lifetime. Clearing them, `jdwp_reset`, `jdwp_disconnect` and a clean server shutdown restore the original bytecode; what a crashed server leaves behind is listed at the next attach (see [Fast logpoints](#fast-logpoints-in-target-consent-required)).
- **Auditable** — the full source is here. The server is a standard Spring Boot application with no obfuscation or native code. Try asking your agent: *"audit these sources for anything that touches network or filesystem beyond the JVM target"*.

## Quick start

### Prerequisites

- **JDK 17+** on PATH to **run** the server (must be a JDK, not a JRE — JDI lives in `jdk.jdi`).
- **JDK 21+ to build from source** — the build toolchain pins to JDK ≥ 21 because Error Prone 2.48 ships Java-21 bytecode. The bytecode target stays at Java 17, so the resulting JAR still runs on a JDK 17 runtime. A Codex install that downloads the release JAR needs no build, so JDK 17 is enough there.
- **Codex plugin only:** `bash`, plus `curl` or `wget` for the release download (without them it builds from source).

No separate Maven install is required — the repository ships with the Maven Wrapper (`./mvnw`), which downloads a pinned Maven 3.9.x into `~/.m2/wrapper/` on first use. Every build command in this README uses the wrapper; so do the SessionStart hook and the Codex launcher, which fall back to `mvn` on `PATH` when the wrapper cannot set itself up.

### 1. Install the plugin

**Option A: Claude Code plugin (recommended for Claude Code)**

Installs the MCP server, the `java-debug` skill (debugging workflows, recipes, gotchas), and the MCP server registration in one step:

```bash
/plugin marketplace add https://github.com/FgForrest/mcp-jdwp-java.git
/plugin install jdwp-debugging@mcp-jdwp-java
```

The server JAR is built automatically on first session start via the bundled `./mvnw` wrapper (requires JDK 21+ — no Maven install needed). The hook is content-aware: it rebuilds when the JAR is missing or, in a git checkout, when `HEAD` moves, so `git pull` + restart picks up updates without manual intervention. When the wrapper cannot set itself up (for example, `~/.m2` is read-only in a sandbox), the hook tries `mvn -o` and then `mvn` from `PATH`. If every attempt fails, a warning at session start names the build log and the exact command to build by hand. A build runs while Claude Code starts the MCP server and the JAR is absent until it finishes, so after a build (first install, update, moved `HEAD`) `jdwp-inspector` may show as failed in `/mcp`; reconnect it there once the build is done. Restart Claude Code to pick up the plugin.

**Option B: Codex plugin (recommended for Codex)**

The same repository is a Codex plugin marketplace:

```bash
codex plugin marketplace add FgForrest/mcp-jdwp-java
codex plugin add jdwp-debugging@mcp-jdwp-java
```

(or `/plugins` in the Codex TUI). Codex reads the plugin's root `plugin.json` and `mcp.json` (the portable [Agent Plugins](https://agent-plugins.org) format) and installs the same `java-debug` skill and `jdwp-inspector` server. It runs no hook before starting a plugin's server, so the server is started through `scripts/jdwp-inspector.sh`, which gets the JAR first: it downloads the release JAR when the installed sources are the ones tagged for that release, and builds it otherwise (`./mvnw`, `mvn -o`, `mvn`, as the hook does). After that it starts the server at once.

- **First session after install or update:** Codex does not wait long for a server to start. If the `jdwp-inspector` tools are missing, start a new session: the download or build carries on in the background, and a later session finds the JAR (see [When `jdwp-inspector` does not connect](#when-jdwp-inspector-does-not-connect)). The JAR is kept in `server/` in the plugin's data directory (`~/.codex/plugins/data/…`), so it survives Codex reinstalling the plugin's files; the preparation's log is `logs/server-jar-setup.log` there, next to the server log `logs/mcp-jdwp-inspector.log`.
- **Project directory:** Codex starts plugin servers in the plugin's own directory and tells the server the session's directory with each tool call. `jdwp_diagnose` shows that directory, and the local-project classpath fallback scans it, so start Codex in your project as you would Claude Code.
- **Codex's sandbox:** commands Codex runs in its `workspace-write` sandbox have no network access by default. A JVM started there may not be able to offer its JDWP port to the server, which runs outside the sandbox. If attaching fails, give the sandbox network access (`[sandbox_workspace_write] network_access = true` in `~/.codex/config.toml`) or start the JVM outside it.
- **Windows:** the launcher is a POSIX shell script, so the plugin's server does not start under Codex on native Windows; register the JAR by hand as below.

<details>
<summary><strong>Alternative: manual MCP registration (without plugin)</strong></summary>

If you prefer to register the MCP server directly without the plugin (no skill, no auto-build, no updates):

**1. Build the JAR** (or download `mcp-jdwp-java-<version>.jar` from a [release](https://github.com/FgForrest/mcp-jdwp-java/releases)):

```bash
git clone https://github.com/FgForrest/mcp-jdwp-java.git
cd mcp-jdwp-java
./mvnw clean package -DskipTests   # use mvnw.cmd on Windows
```

The JAR is `jdwp-mcp-server/target/mcp-jdwp-java.jar`. To change the JDWP port `jdwp_connect` uses (default 5005), add `-DJVM_JDWP_PORT=12345` before `-jar` in the commands below; `jdwp_wait_for_attach` takes any host and port anyway. The server logs to `mcp-jdwp-inspector.log` in the temp directory unless you add `-DLOG_PATH=/path/to/mcp-jdwp-inspector.log` the same way (the Claude Code plugin writes it to `logs/` in the plugin directory, the Codex plugin to `logs/` in its data directory).

**2a. Register with Claude Code:**

```bash
claude mcp add jdwp-inspector -s user \
  -- java --add-modules jdk.jdi,jdk.attach -jar /path/to/mcp-jdwp-java.jar
```

Re-installing requires removing first: `claude mcp remove jdwp-inspector -s user`. Drop `-s user` to scope to the current project only, or put the same entry in the project's `.mcp.json`:

```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "--add-modules", "jdk.jdi,jdk.attach",
        "-jar", "/path/to/mcp-jdwp-java.jar"
      ]
    }
  }
}
```

The server starts in a few seconds, well within the 30 seconds Claude Code waits by default. On a slow machine, start Claude Code with a longer wait, e.g. `MCP_TIMEOUT=60000 claude`. `MCP_TIMEOUT` and `MCP_TOOL_TIMEOUT` are read from Claude Code's own environment; an `env` entry in the server registration only reaches the server, which ignores them.

**2b. Register with Codex:**

```bash
codex mcp add jdwp-inspector -- java --add-modules jdk.jdi,jdk.attach -jar /path/to/mcp-jdwp-java.jar
```

This writes an entry to `~/.codex/config.toml`. Spring Boot takes a few seconds to start. Set the client tool-call timeout above the longest wait you expect. `jdwp_resume_until_event` and `jdwp_wait_for_attach` can block for the supplied `timeoutMs`, and a step with `wait=true` can block for 30 seconds.

```toml
[mcp_servers.jdwp-inspector]
command = "java"
args = ["--add-modules", "jdk.jdi,jdk.attach", "-jar", "/path/to/mcp-jdwp-java.jar"]
startup_timeout_sec = 30
tool_timeout_sec = 300
```

Without the plugin, the `java-debug` skill is not installed; copy `skills/java-debug/` into your agent's skills directory if you want it.

</details>

#### When `jdwp-inspector` does not connect

After an install or an update the server often does not come up on the first try: the agent starts it while its JAR is still being built or downloaded, or gives up waiting for it. That is expected, and it usually takes one or two more tries:

- **Claude Code:** run `/mcp`, select `jdwp-inspector` and reconnect. If it fails again, the build is still running — wait for the session-start message that reports it and reconnect once more.
- **Codex:** start a new session. The download or build goes on in the background, so if the next session still has no tools, it was not finished yet: try again a little later.
- **Ask the agent.** The `java-debug` skill is installed even when the server is not connected, and it tells the agent where to look. Ask something like *"the jdwp-inspector MCP server does not connect — find out why"*: the agent checks the JAR, reads the build and server logs, checks `java -version`, can rerun the build, and tells you when to reconnect.

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

### 3. Attach from your agent

From there, just tell the agent (Claude Code or Codex) what to do — *"attach to JDWP, set a breakpoint in `OrderService.createOrder` line 42, run the failing test, dump the context when it hits"*. The bundled `java-debug` skill teaches the agent the right tool sequence; the next section gives you a smoke test that exercises the surface.

## Find the Bug — test flights

The `jdwp-sandbox` module ships 10 deliberately broken Java classes. Each one compiles fine, looks reasonable at first glance, and **fails its test with a confusing message**. Your job: attach with the JDWP MCP server and find the root cause.

This doubles as a setup verification — if you can solve these, everything works.

The fast-probe consent steps below assume a fresh MCP server. If the server reports a standing approval for the identical probe at the same endpoint, continue with the probe it set.

Each flight is built so that one tool group is the path of least resistance, and lists a **par** — the minimum tool calls that cleanly reveal the root cause. Hitting par is the elegant solve; the suite as a whole exercises the full tool surface (expression eval, exception breakpoints, field watchpoints, event history, marks, logpoints, runtime mutation, multi-thread inspection, and in-target probes).

### Just installed the plugin? Grab the sandbox zip

The fastest path for fresh plugin users is a self-contained zip — no clone, no reactor build:

```bash
curl -L -o jdwp-sandbox.zip \
  https://github.com/FgForrest/mcp-jdwp-java/releases/latest/download/jdwp-sandbox.zip
unzip jdwp-sandbox.zip && cd jdwp-sandbox
```

Inside, you get a parentless Maven project with `src/`, a `pom.xml`, a flight-game `README.md`, and an `AGENTS.md` that briefs the agent on the game's house rules — Codex reads it directly, and the bundled `CLAUDE.md` imports it for Claude Code. Open the folder in your agent and ask it to play flight #1 — the house rules keep it honest (no peeking at source, no spoiler-fetching). Continue with the workflow below.

### How to launch a test flight

Start Claude Code or Codex from the sandbox folder (or this repo's root, if you cloned) and type:

```
Use JDWP to debug <TestClass> in the jdwp-sandbox module — the test is failing, find the root cause.
```

For each new test JVM, attach with `jdwp_wait_for_attach(port=5005, requireVmStart=true)`. Between runs, disconnect and confirm the previous test JVM and background Maven shell have ended.

---

### #1 The Phantom Session

**Difficulty:** Moderate | **Test:** `SessionStoreTest` | **Package:** `session` | **Par:** 4 | **Exercises:** expression eval

**Symptom:** `retrieve() returned null` — a session was stored, upgraded, and then... vanished from the map.

<details>
<summary><strong>Reveal root cause</strong></summary>

`UserSession` is used as a HashMap key, with both `userId` and `role` in `hashCode()`. `upgradeUserRole()` calls `session.upgradeRole("PREMIUM")`, which mutates `role` — changing the hashCode while the key is still in the map. The entry sits in the old hash bucket; lookups compute the new hash and search the wrong bucket.

**Debug path:** Breakpoint before and after `upgradeRole()`. Use `jdwp_assert_expression("session.hashCode()", "<value-before>")` after the upgrade — `MISMATCH` confirms the hash drifted.

</details>

---

### #2 The Swallowed Exception

**Difficulty:** Hard | **Test:** `EventBusTest` | **Package:** `events` | **Par:** 4 | **Exercises:** exception breakpoint + trigger gate

**Symptom:** `expected stock < 100 but was 100` and an empty error summary — the order was supposed to reserve inventory, but nothing happened and nobody complained.

<details>
<summary><strong>Reveal root cause</strong></summary>

`OrderEvent` narrows the raw quantity through `byte` (200 → -56), so `Inventory.reserve()` throws `IllegalStateException`. `EventBus.dispatch()` runs each handler on a single-thread executor as a fire-and-forget task — the `Future` is never inspected, so the exception is captured inside `java.util.concurrent.FutureTask.run` (a JDK frame) and discarded. No sandbox frame ever holds the throwable, so a breakpoint-context dump has nothing to show, and `getErrorSummary()` stays empty.

**Debug path:** Because the exception *is* caught (by FutureTask), an `uncaught`-only breakpoint never fires. Set a line BP at the test/dispatch entry as a trigger, then `jdwp_set_exception_breakpoint("java.lang.IllegalStateException", caught=true, triggerBreakpointId=<trigger>)` so bootstrap exceptions don't drown the signal. The throw site suspends with `qty = -56` in the frame's locals.

</details>

---

### #3 The Time Traveler's Config

**Difficulty:** Hard | **Test:** `ConfigurationProviderTest` | **Package:** `config` | **Par:** 4 | **Exercises:** field watchpoint + event history

**Symptom:** `expected timeout=5000 but was 0` — the timeout was set during construction, yet it reads back as the default.

<details>
<summary><strong>Reveal root cause</strong></summary>

`ConfigurationProvider`'s constructor calls `init(5000)`, so the timeout is correct. Then `runMaintenanceSweep()` starts a background `config-reaper` thread that wrongly treats the live instance as stale and calls `resetToDefaults()`, writing the timeout back to 0. By the time the test reads it, the heap shows 0 and no local holds 5000 — a single context dump looks like the value was never set.

**Debug path:** `jdwp_set_field_breakpoint(className="…config.Configuration", fieldName="timeout", mode="modification")`, then `jdwp_get_events` shows the two stores in order — 0→5000 on the main thread, then 5000→0 on `config-reaper`. `jdwp_get_stack` on the second event names the reaper as the clobberer.

</details>

---

### #4 The Audit That Lies

**Difficulty:** Hard | **Test:** `TransferServiceTest` | **Package:** `bank` | **Par:** 4 | **Exercises:** field watchpoint + stack narration

**Symptom:** `expected discrepancy=0 but was non-zero` — money is neither created nor destroyed, yet the audit says the books don't balance.

<details>
<summary><strong>Reveal root cause</strong></summary>

`TransferService.transfer()` is not atomic: it calls `source.withdraw()`, then `auditService.snapshotBalances()`, then `destination.deposit()`. The snapshot captures the intermediate state where money has left the source but hasn't arrived at the destination — showing a total of 1500 instead of 2000.

**Debug path:** `jdwp_set_field_breakpoint` on `AuditService.lastTotalSnapshot` (modification). It fires mid-transfer with the stack at `TransferService.transfer` between the debit and the credit; `jdwp_get_stack` plus a live balance read confirm the captured dip.

</details>

---

### #5 The Field That Lies

**Difficulty:** Hard | **Test:** `UserProfileTest` | **Package:** `userprofile` | **Par:** 3 | **Exercises:** field watchpoint blind spot + before/after bisect

**Symptom:** `expected: <Alice> but was: <alice>` — the welcome message rendered correctly, yet the user's stored display name has silently changed casing.

<details>
<summary><strong>Reveal root cause</strong></summary>

`LoginNormalizer.welcomeMessage` calls a private `canonicalForm` helper, which delegates to a static `DisplayNameMirror` nested class. The mirror uses `Field.setAccessible(true)` + `Field.set(profile, canonical)` to write the lower-cased form straight into `UserProfile.displayName`, bypassing the public setter. From the test's side the formatter looks read-only, and a field watchpoint cannot see the write either: JDI watchpoints fire only on the `putfield`/`getfield` bytecodes (and JNI field access). `Field.set` ends in an `Unsafe` store straight to memory, which trips no watchpoint, in `modification` mode or in `access` mode.

**Debug path:** The tell is a modification watchpoint that fires once, for the constructor's ordinary assignment, and never again, although the value flips before the assertion: the write does not go through a field store. The par path takes three calls:

1. `jdwp_set_field_breakpoint(className="one.edee.jdwp.sandbox.userprofile.UserProfile", fieldName="displayName", mode="modification")`. The class is not loaded yet, so the watchpoint waits for it.
2. `jdwp_resume_until_event`: the watchpoint fires in the `UserProfile` constructor, which stores `"Alice"`.
3. `jdwp_resume_until_event`: the test fails and its JVM exits. The `[VM_DEATH]` reply lists the watchpoint with one hit. The only store the JVM saw wrote `"Alice"`, yet the test read `"alice"`, so the write is reflective or goes through `Unsafe`. Between the two, the test calls only `welcomeMessage`, on line 24.

Bisecting takes three calls as well and shows where the value flips, though not how: `jdwp_set_breakpoint("one.edee.jdwp.sandbox.userprofile.UserProfileTest", 27)`, just after `welcomeMessage`, then `jdwp_resume_until_event`, then `jdwp_evaluate_expression("profile.getDisplayName()")`, which is already `"alice"`. When the old and new strings could be equal, compare `System.identityHashCode(profile.getDisplayName())` instead.

That is the evidence to read `LoginNormalizer` with: `canonicalForm` → `DisplayNameMirror.mirror` → `Field.set`. To name the writing call from the debugger alone, break at line 24 instead and keep bisecting inside `welcomeMessage` with `jdwp_step_into` / `jdwp_step_over`, comparing the identity after each line: it flips across `DisplayNameMirror.mirror(profile, canonical)` in `canonicalForm`. A line BP on `UserProfile.setDisplayName` never fires — the setter is genuinely unused.

</details>

---

### #6 The Doppelgänger Cart

**Difficulty:** Moderate | **Test:** `CheckoutTest` | **Package:** `cart` | **Par:** 6 | **Exercises:** marked instances (`$label`)

**Symptom:** `expected 45.0 but was 0.0` — the cart goes through pricing and discounting, yet its total never changes.

<details>
<summary><strong>Reveal root cause</strong></summary>

`Checkout.process` runs the cart through `validate → price → discount`. `validate` was changed to return a defensive snapshot — `return new Cart(cart)` — so every later stage mutates the *copy*. `process` reassigns its local to the copy as it threads the stages, so no single frame holds both the caller's cart and the copy at once. The test ignores the return value (assuming in-place mutation), so its cart stays at total 0. The copy's fields are identical to the original — only identity differs.

**Debug path:** Break in the test once the cart exists, `jdwp_mark_instance(label="input", objectId=<id>)` to pin it, then set a breakpoint in `price` (or `applyDiscount`) with condition `cart != $input`. It fires — the stage is working on a doppelgänger, not the caller's cart.

</details>

---

### #7 The Heisenbug Race

**Difficulty:** Hard | **Test:** `RaceCounterTest` | **Package:** `race` | **Par:** 3 | **Exercises:** logpoints (non-stopping)

**Symptom:** `expected 2 but was 1` — two threads each increment a counter once, but one increment vanishes.

<details>
<summary><strong>Reveal root cause</strong></summary>

`RaceCounter.increment` reads the count, waits at a `CyclicBarrier` so both threads have read before either writes, then writes back `read + 1`. Both threads read 0 and both write 1 — one increment is lost. A suspending breakpoint serializes the threads and makes you juggle two parked threads to reconstruct what happened.

**Debug path:** Set a `jdwp_set_logpoint` (it does not leave the thread stopped) at the read site logging the thread name and `count` (or a field logpoint on `count`). Let the test run, then `jdwp_get_events` — both `racer-1` and `racer-2` are recorded reading 0 before either writes, which is the lost update laid out in order.

</details>

---

### #8 The Magic Patch

**Difficulty:** Warm-up | **Test:** `DateParserTest` | **Package:** `parser` | **Par:** 4 | **Exercises:** runtime mutation (`set_local`)

**Symptom:** `NumberFormatException: For input string: "15 "` — a date string that looks right fails to parse.

<details>
<summary><strong>Reveal root cause</strong></summary>

`DateParser.parse` splits `YYYY-MM-DD` and `Integer.parseInt`s each part. The feed delivers `"2026-05-15 "` with a trailing space, so `parseInt("15 ")` throws. The parser never trims its input.

**Debug path:** Four calls. `jdwp_set_breakpoint` at the top of `parse`, `jdwp_resume_until_event` to stop there, `jdwp_set_local("input", "2026-05-15")` to drop the trailing space (or break on the test's line 19 and patch `fromFeed` instead), and `jdwp_resume`. The parse now succeeds and the test passes — confirming the trailing space is the whole story, with no rebuild. The exception message already shows the stray space, so the stop needs no inspection call; the confirming resume counts.

</details>

---

### #9 The Polite Standoff

**Difficulty:** Hard | **Test:** `TransferDeadlockTest` | **Package:** `deadlock` | **Par:** 4 | **Exercises:** multi-thread inspection (no breakpoints)

**Symptom:** The test hangs and then fails its join — both transfers never complete.

<details>
<summary><strong>Reveal root cause</strong></summary>

`Account.transfer` locks the source account, pauses, then locks the destination. Two transfers in opposite directions (`A→B` and `B→A`) each grab their first lock and then wait forever for the other's — a classic lock-ordering deadlock.

**Debug path:** No breakpoint can fire — the threads execute nothing. `jdwp_get_threads` shows `transfer-A-to-B` and `transfer-B-to-A` in `MONITOR` state; `jdwp_dump_locks` prints the lock graph with both monitor holders and the inverse-order cycle; `jdwp_get_stack` on each thread shows the lock-ordering inversion. (Trying `jdwp_evaluate_expression` / `jdwp_to_string` on these threads is refused — invoking a method on a monitor-blocked thread would hang the debugger, which is itself the tell.)

</details>

---

### #10 The Oversold House

**Difficulty:** Hard | **Test:** `BoxOfficeTest` | **Package:** `boxoffice` | **Par:** 4 | **Exercises:** in-target probes (fast logpoint / fast breakpoint, with the user's consent)

**Symptom:** `[Tickets sold for 300 performances of 200000 seats] expected: 60000000L but was: 60001634L` — the season ends oversold by a few tickets per performance.

**Solved when:** you name one specific double sale: the seat, the window that sold it first and the window that sold it again. Knowing that the house is oversold, or by how much, is not enough.

<details>
<summary><strong>Reveal root cause</strong></summary>

`BoxOffice.trySell` checks that the seat is free, prices it (reading the pricing desk's volatile surcharge), and only then records the buyer: a check-then-act race. Two windows that check the same free seat within the pricing step both sell it, and `sold` counts both. About six seats per performance are sold twice. Customer numbers are window numbers, and `owners[]` keeps only the last buyer, so counting seats against `sold()` after the fact shows the overselling but cannot name the window that sold a seat first. Only catching the collision can.

**Debug path:** On the sell line (`BoxOffice.java:54`, `seatOwners[seat] = customer`), the condition `seatOwners[seat] != 0` is true when another window sold the seat after this one checked it. Both in-target tools rewrite the class's bytecode, so each needs the user's explicit consent: the first call, without `userApproved`, arms nothing and returns a risk briefing to relay word for word. `BoxOffice` is not loaded when the test JVM starts; the approved probe waits for the class to load and arms on its next hit.
- **Fast logpoint, 4 calls (par):**
  1. `jdwp_set_fast_logpoint` on `one.edee.jdwp.sandbox.boxoffice.BoxOffice` line 54, with that condition and the expression `seat + " sold to " + seatOwners[seat] + ", resold to " + customer`, without `userApproved`: the risk briefing, relayed to the user.
  2. The same call with `userApproved=true`, once the user agrees.
  3. `jdwp_resume_until_event`: the test runs to its end. The reply is `[VM_DEATH]` and says how many `FAST_LOGPOINT` hits were recorded.
  4. `jdwp_get_events`: the double sales, each with its seat and the first and second window.
- **Fast breakpoint, 5 calls:** the briefing and the approved `jdwp_set_fast_breakpoint` call with the same condition, then `jdwp_resume_until_event`: the first double sale stops with every window parked. `jdwp_get_breakpoint_context` shows `seat` and its second buyer, `customer`. `jdwp_evaluate_expression("seatOwners[seat]")` gives the first buyer; array elements are not in the context dump.

The logpoint does not catch every double sale. Its condition reads a plain `int[]` without synchronization, so a window that reads the seat before the other window's write is visible logs nothing; one live run caught 1,460 of 2,472 double sales. One is enough to solve the flight. The probe also sits inside the race window, between the check and the write, so it widens the race rather than hiding it: runs with the probe sold more seats twice, not fewer.

An ordinary conditional breakpoint or logpoint on that line goes through the debugger on each of about 60 million passes, at 3–30 ms each. The box office closes first, and the serialized windows stop colliding.

</details>

---

### Scorecard

**Flights solved** — did you find the root cause?

| Solved | Rating                                                               |
|--------|----------------------------------------------------------------------|
| 0-2    | The JVM is winning. Check your setup.                                |
| 3-5    | Solid start. You're getting the hang of breakpoint-driven debugging. |
| 6-9    | Impressive. You found bugs that would take hours with println.       |
| 10     | Bug terminator. Nothing survives your debugger.                      |

**Style** — how close to par? Every `jdwp_*` call counts, from the first call after attaching until the root cause is evident; the attach and the clean-up afterwards are free. Award ⭐⭐⭐ for a flight solved at its par tool count, ⭐⭐ within 2× par, ⭐ solved at all. A flawless run is 30 stars across the ten flights.

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

The breakpoint fires on every hit, but the thread only stays stopped when the condition evaluates to `true`: the server evaluates the condition while the hitting thread waits and resumes it at once when the condition is false. This is essential for AI agents — without conditions, a breakpoint in a hot loop would generate thousands of useless stops. Conditions (and expressions, and logpoint bodies) all accept **block syntax** — wrap in `{ ...; return X; }` to use intermediate locals, try/catch, or early returns.

### Logpoints (non-stopping breakpoints)

```
jdwp_set_logpoint(
  className="com.example.OrderService",
  lineNumber=42,
  expression="\"Processing order \" + order.getId() + \" total=\" + order.getTotal()",
  condition="order.getTotal() > 1000"
)
```

A logpoint evaluates an expression every time the line is hit, logs the result to the event history, and **never leaves the thread stopped**: the hitting thread waits only while the server evaluates the condition and expression, then runs on. Combined with an optional condition, this gives the agent tracing without stopping execution. View results with `jdwp_get_events()`. Boxed primitives appear as values, and other objects appear as `Object (type)`. Where even that pause matters — races, hot loops — see fast logpoints below.

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

On the next hit of the line the server compiles a small probe class, defines it in the class's own class loader, and redefines the class (`VirtualMachine.redefineClasses`) with a call to the probe at that line. From then on the probe evaluates the condition and expression on the application thread and appends the result to a lock-free ring buffer in the target; `jdwp_get_events` reads that buffer through plain field reads — no thread is ever suspended — and reports entries lost to a buffer overrun as `FAST_LOGPOINT_DROPPED`. Hits nobody read before the target exits — a test JVM finishing its run — are read as it exits: the target waits at its exit until the server has drained the buffer, and the `[VM_DEATH]` reply says how many were recorded. A class that is not loaded yet is waited for: the fast logpoint arms once the class loads, so it can be set at VM start (where `forceLoad` cannot load anything). Clearing the logpoint, `jdwp_reset`, `jdwp_disconnect` and a clean server shutdown restore the original bytecode.

Because this **rewrites the running application's bytecode**, the tool requires explicit user consent. Call without `userApproved`. If there's no standing grant, the tool returns a risk briefing (instrumentation that outlives a crashed server, user code on application threads, interaction with other bytecode agents, breakpoint re-creation, two stop-the-world pauses). Relay the briefing word for word and ask the user before retrying with `userApproved=true`.

Approval covers the exact probe at the same host:port while this MCP server runs. An approved retry returns a fresh briefing if the last briefing preceded attachment or named a different endpoint. The match includes probe kind, exact binary class name, line, logpoint expression and buffer capacity, breakpoint capture, condition, suspend policy, stop mode, and `forceLoad`. The briefing shows the logpoint capacity and discloses that `forceLoad=true` can run the class initializer. Loopback aliases share a grant at the same port when the attach uses a loopback address. A wildcard destination uses the resolved local address for its approval key. On later JVMs, call again without `userApproved`. The reply reports the original grant time, and the fast-probe sections of `jdwp_overview` list the grants. JVM exit, disconnect, reconnect and reset preserve the grants. An MCP server restart drops them.

If the server dies without restoring the class, the probe keeps running — but it describes itself, and the next attach finds it: the attach reply and `jdwp_overview` list it as `RECOVERED` (the class is exactly as that session left it) or `STRANDED` (it is not), with the expression and who armed it when. `jdwp_clear_breakpoint` switches a leftover off without touching the bytecode; `jdwp_restore_fast_logpoints(id, userApproved=true)` restores a `RECOVERED` class's original bytecode, with fresh approval for each restore. Nothing an earlier session left is ever restored implicitly. Arming is refused when the live bytecode differs from the class file — e.g. JaCoCo or the Mockito inline mock maker instrumented the class — because redefining from the class file would silently strip their changes. Measured on the live test target (two threads calling the instrumented method in a loop, condition never true): throughput stayed at the no-logpoint level (about 25 million calls/s on the test machine), against a few hundred calls/s — 3–7 ms per hit — under `jdwp_set_logpoint`. `jdwp_set_logpoint` stays the default; reach for the fast variant only when suspending on every hit would distort what you are looking at.

### Fast breakpoints (in-target, consent required)

A conditional breakpoint on a hot line has the same cost: every hit — including one whose condition is false — is a round trip to the server, and HotSpot never JIT-compiles a method that holds a breakpoint. `jdwp_set_fast_breakpoint` evaluates the condition **inside the target JVM** and calls into the debugger only when it holds:

```
jdwp_set_fast_breakpoint(
  className="com.example.OrderService",
  lineNumber=42,
  condition="order.getTotal() < 0",
  capture="order.getTotal()",
  suspendPolicy="all",       // default; "thread" parks only the thread that met the condition
  stopOnce=true,             // default: the first hit that meets it stops; call again with the same arguments to re-arm
  userApproved=true          // only after the user explicitly agreed — same protocol as fast logpoints
)
```

The first hit of the line goes through the debugger: if the condition holds, that hit is the stop; otherwise the server installs a probe, as for a fast logpoint. While local classpath discovery runs, arming can move to a later hit. The probe calls an empty `stop()` method only when the condition holds, and the debugger's breakpoint sits on `stop()` — so the instrumented method keeps running compiled. In the spike, a method holding a breakpoint that never fired ran at 74 ns per call against 7 ns; with a fast breakpoint the difference stayed within run-to-run noise. Wait for the stop with `jdwp_resume_until_event`; the report reads `fast_breakpoint=N, condition true`. The stack, locals, evaluation, `jdwp_set_local`, watchers and steps all work from the user's frame — the probe frames on top are hidden, and a step lands on the stop line. A condition that throws stops once and is then only counted (`FAST_BREAKPOINT_ERROR` in `jdwp_get_events`).

Use optional `capture` for values that may change just after the condition holds. When a true hit passes the stop gate, the Java expression or `{ ...; return value; }` block runs on the hitting thread. The dispatcher evaluates capture on a true arming stop, and the installed probe evaluates it before suspension on later stops. Both expressions must be thread-safe and free of side effects. The result or failure appears as `Captured at the hit: …` in the stop, breakpoint context and event history. For several values, return one string. Changing or omitting the capture requires its own standing approval.

If capture compilation fails during probe installation and the condition compiles, the probe installs and reports the capture diagnostic at every stop. A capture compile error on a true arming hit appears in that stop’s report. When a regular breakpoint supplies the stop at the same line, the fast capture appears with its own breakpoint ID.

The consent protocol, the risk briefing, waiting for a class that is not loaded yet and the handling of leftovers after a crashed server are those of fast logpoints. Clearing a fast breakpoint while a thread is stopped in its method switches it off at once and restores the class once that thread is released, so the stopped frame keeps its local variables; `jdwp_overview` lists the restore as pending until then. The condition runs unsynchronized on every thread that passes the line: it must be thread-safe and free of side effects, and private fields are out of its reach. `jdwp_set_breakpoint` with a condition stays the default; reach for the fast variant only when going through the debugger on every hit would distort what you are looking at — a race, a hot loop, a latency-sensitive path where the condition is rarely true.

### Deferred breakpoints

By default, every line BP, logpoint, exception BP, and field BP is registered **passively**: if the target class isn't loaded when the breakpoint is set, the server registers a `ClassPrepareRequest` and promotes the breakpoint to active when the JVM loads the class on its own — same behaviour as IntelliJ / Eclipse / jdb.

```
jdwp_set_breakpoint(className="com.example.LazyService", lineNumber=15)
→ "Breakpoint deferred for com.example.LazyService:15 (ID: 1, suspend: all). Class not yet loaded — will activate automatically when the JVM loads it."
```

`jdwp_overview()` shows pending breakpoints with their status; `jdwp_get_events()` records a `CLASS_PREPARE` entry when a load activates them.

This works from VM start, so it is the attach prologue for a failing test: launch the JVM suspended (`-Dmaven.surefire.debug`), call `jdwp_wait_for_attach(requireVmStart=true)`, set every line BP, logpoint, exception BP and field BP while the JVM is parked, then `jdwp_resume_until_event`. Each one activates when its class loads — JDK classes included, so an exception BP on `java.lang.AssertionError` set at VM start stops on the first failing JUnit assertion.

Pass `forceLoad=true` only when you need the breakpoint to bind *immediately* — the server will call `Class.forName` in the target VM to load the class up front. That runs the class's `<clinit>` (early static init, eager dependency loads), which can mask lazy-init / classloader-leak diagnostics. Use sparingly. It needs a thread stopped at an event to run the load, so at VM start it cannot load anything: the breakpoint is deferred instead, and the reply says `forceLoad=true could not load it`.

### Exception breakpoints

```
jdwp_set_exception_breakpoint(
  className="java.lang.NullPointerException",
  caught=true,
  uncaught=true
)
```

Catch exceptions at the throw site — before the stack unwinds. The breakpoint also matches subclasses: one on `java.lang.AssertionError` stops on JUnit's `org.opentest4j.AssertionFailedError`. The stop names the exception's type and message (`via=exception, java.lang.IllegalStateException: Insufficient stock (thrown at …, caught at …)`). Only the throwing thread stops unless you pass `suspendPolicy="all"` — use it when the throw happens on a pool thread and the test's main thread would otherwise run on to its timeout and end the JVM while you look. Use `jdwp_overview(types="exception_breakpoint")` to see active and pending exception breakpoints.

An exception class that is not loaded yet makes the breakpoint `[PENDING]`; it activates by itself when the JVM loads the class — JDK classes such as `IllegalStateException` or `AssertionError` included, and before the first instance can be thrown. It needs no upstream line breakpoint and no `forceLoad`, and it can be set at VM start (see [Deferred breakpoints](#deferred-breakpoints)). Classes the JVM preloads, such as `NullPointerException`, bind at once.

**Log-only mode** — record the throw without stopping the thread, evaluating an expression with `$exception` bound to the thrown object. Use the dedicated `jdwp_set_exception_logpoint` tool:

```
jdwp_set_exception_logpoint(
  className="java.sql.SQLException",
  expression="$exception.getSQLState() + \": \" + $exception.getMessage()"
)
```

Each hit records an `EXCEPTION_LOG` entry to `jdwp_get_events`; failures during evaluation surface as `EXCEPTION_LOG_ERROR` so the listener never throws. Use this to trace exception flows in long-running services without stopping them.

Both exception tools support a Java `condition` with `$exception` bound to the thrown object. To scope broad exception types, pass one throw-site class pattern in `thrownIn` (for example, `thrownIn="com.example.*"`) or comma-separated exclusion patterns in `notThrownIn` (for example, `notThrownIn="jdk.*,org.junit.*"`). Use exact class names or one `*` at either end. The VM drops throws that match any exclusion. Every throw site is eligible by default. The filters appear in `jdwp_overview` and survive deferral and `jdwp_reconnect`.

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

**Log-only mode** — record reads/writes without stopping the thread, evaluating an expression with the same bindings:

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
  expression="order.getTotal()",
  frameIndex=0
)
→ Result of order.getTotal(): 1250.0
```

Field navigation, including private and package-private reads, runs without compiling or invoking target code. It works on manual holds, stop-all bystanders, and suspended threads blocked on a monitor or in `wait()`. Ordinary method calls, lambdas and blocks compile against the available classpath and run in the target. On JDK 17 and 21, ordinary compilation can retry access errors through reflection for private application field reads, method calls and constructors. Closed JDK packages, inaccessible types named in declarations and private field writes can still cause errors. Compiled code that is still running after 20 s returns `[STILL_RUNNING]`. The eventual result appears as `EVALUATION_COMPLETED_LATE` in `jdwp_get_events`.

Expressions see no imports. A simple type name covers `java.lang` and the current class's own and nested types; write other application types fully qualified, for example `com.acme.Status.ACTIVE`.

Two input modes:

- **Single expression** — `order.getTotal()`, `x + y`, `list.size() == 5`. The same expression form works for navigation and compilation.
- **Block mode** — wrap the input in `{ ... }` to send a multi-statement body with try/catch, intermediate locals, or early `return`. The agent writes explicit `return X;` to yield a value. Available everywhere expressions are accepted: `jdwp_evaluate_expression`, `jdwp_assert_expression`, breakpoint conditions, logpoint expressions, watchers.

Set `JDWP_EVAL_ENGINE=auto|compiler|interpreter` in the server environment. `jdwp_diagnose` reports engine counts and why expressions needed compilation. [Expression evaluation](docs/expression-evaluation.md)

### Assertions

```
jdwp_assert_expression(
  expression="order.getStatus().name()",
  expected="CONFIRMED",
  threadId=25
)
→ OK — order.getStatus().name() = CONFIRMED
  or
  MISMATCH — order.getStatus().name()
    expected: CONFIRMED
    actual:   PENDING
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

Watchers are MCP-side state — they survive across breakpoint hits and are cleaned up when the breakpoint is deleted. They attach to fast breakpoints too (not to fast logpoints, which never stop). `scope="full_stack"` evaluates, at every frame of the stack, the watchers of every breakpoint at that frame's line.

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

An unfiltered `jdwp_resume_until_event()` presents the oldest unseen stop immediately. With no unseen stop, it releases presented stops and manual holds before waiting, unless any step is pending. Pass `threadId=T` to present the oldest unseen stop on T and, if a release is needed, release only T. A pending step suppresses the targeted release pass only when the step is on T. If T is already running, the call can wait for its next stop without releasing anything. Other threads' unseen stops stay suspended and the reply counts them when there are any. The wait identifies a blocker, such as another thread's unseen stop-all, even if it arrives after the wait starts.

The reply describes how the wait ended.

- `Event fired …` — a BP / step / exception suspended a thread; the message includes thread + stop site
- `[TIMEOUT] …` — the wait expired without an event; the message offers `wait_more` / `reconnect` / `abort` and reports JDI health so the agent never blindly hangs
- `[RESUME_FAILED] …` — a release failed or lease debt remains unpaid. A targeted wait reports only recovery holds affecting its thread. The reply reports completed releases and gives targeted retry calls when available.
- `[VM_DEATH] …` — the target VM died. The reply names a selected stop if there was one.
- `[CONNECTION_CHANGED] …` — a targeted wait or resume fetched a VM from a different connection. Verify the target and look up a fresh ID with `jdwp_get_threads` before retrying.
- `[NO_EVENT] …` — `jdwp_reset` ran while the call waited. See the reset reply for any holds retained after a failed release, then set breakpoints again.

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

When the target VM is restarted, or the JDI session wedges and you need a fresh transport, `jdwp_reconnect` re-attaches to the most recent endpoint and replays every BP spec onto the new VM. Synthetic BP IDs are preserved — BP #7 stays #7 — so the agent doesn't have to re-derive its bookkeeping. The marks registry, object cache, last-thread pointer, and classpath cache do not survive `vm.dispose()` and are cleared by design. Reconnect restores the old JVM's original bytecode and drops the fast probes and their watchers. Standing approvals survive, so an identical probe can be set again at the same endpoint.

### One-shot breakpoint context

```
jdwp_get_breakpoint_context(maxFrames=5, includeThisFields=true)
```

Returns thread info, top stack frames, locals at frame 0, and `this` fields in a single call — replaces the four-call sequence `get_current_thread → get_stack → get_locals → get_fields(this)` that an agent would otherwise need at every breakpoint hit.

### Stepping: when it's worth it

`jdwp_step_over`, `jdwp_step_into` and `jdwp_step_out` release the selected thread and report remaining stops and manual holds. A stop-all still covers threads JDI omits from the list, including virtual threads on some JVMs. Omit `threadId` to step the thread of the current stop.

Use `wait=true` to append that step's landing or interrupting stop in the same call. If another call already presented that stop, the step reply identifies it as already presented. Stops on other threads stay unseen. The wait is bounded at 30 seconds. If it times out, the step stays pending. Use `jdwp_resume_until_event(threadId=T)` to continue waiting or to collect the stop after a step made with the default `wait=false`.

Each step is one round-trip, so prefer a breakpoint at the destination + `jdwp_resume_until_event` whenever you can predict where execution will go. Stepping pays off in three narrow situations:

- **`step_into`** — polymorphic dispatch is unclear; you can't tell from source which override will run.
- **`step_out`** — an exception/early-abort left you deep in a frame you don't care about and finding the caller line for a breakpoint is awkward.
- **`step_over`** — the *single* next line, when observing a state mutation is faster than predicting it. More than ~3 in a row → set a breakpoint instead.

## Tool reference (50 tools)

### Connection (4)

| Tool                   | Parameters                     | Description                                                                                          |
|------------------------|--------------------------------|------------------------------------------------------------------------------------------------------|
| `jdwp_connect`         | —                              | Connect to JDWP on configured host:port                                                              |
| `jdwp_disconnect`      | —                              | Disconnect (sends JDWP Dispose) and clears session state                                             |
| `jdwp_reconnect`       | —                              | Re-attach to the last target; BP IDs, conditions, logpoints, chains, watchers replay onto the new VM |
| `jdwp_wait_for_attach` | `host?`, `port?`, `timeoutMs?`, `requireVmStart?` | Poll for a listening JVM and attach. `requireVmStart=true` requires a fresh start suspension. |

Attach and disconnect replies show the local PID, start time, and launch selector when available. For an unverified local JVM, disconnect gives commands to check the port owner. A forwarder may appear as the TCP peer. A live `server=y` process listens again after disconnect. `[NOT_FRESH]` means a session already exists or the new attachment had no suspending `VM_START` during its startup check.

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
| `jdwp_get_breakpoint_context` | `maxFrames?`, `includeThisFields?`        | One-shot context dump at the current breakpoint, including any fast-breakpoint capture |
| `jdwp_get_current_thread`     | —                                         | Thread ID of the last breakpoint hit                  |

### Execution control (7)

| Tool                      | Parameters   | Description                                                  |
|---------------------------|--------------|--------------------------------------------------------------|
| `jdwp_resume`             | —            | Release the stops you have seen and `jdwp_suspend_thread` holds; event sets still being processed keep their threads until processed |
| `jdwp_resume_thread`      | `threadId`   | Resume a specific thread. Retrying a failed automatic stop-all release releases the VM-wide suspension. |
| `jdwp_suspend_thread`     | `threadId`   | Suspend a specific thread                                    |
| `jdwp_resume_until_event` | `timeoutMs?`, `threadId?` | Resume and wait for the next stop, optionally scoped to one thread. Stop replies include any fast-breakpoint captures. Returns `[RESUME_FAILED]` on a failed release |
| `jdwp_step_over`          | `threadId?`, `wait?` | Step over the current line and optionally wait for the thread's next stop |
| `jdwp_step_into`          | `threadId?`, `wait?` | Step into a method call and optionally wait for the thread's next stop |
| `jdwp_step_out`           | `threadId?`, `wait?` | Step out of the current frame and optionally wait for the thread's next stop |

Resume replies list which holds were released and which failed, with the JDI exception. For `[RESUME_FAILED]`, use `jdwp_resume_thread(threadId=…)` with the reported retry thread ID when available. Retrying a failed automatic stop-all releases its VM-wide suspension. `jdwp_resume` also releases every other presented stop and every `jdwp_suspend_thread` hold, including any stop-all a pending step relies on. A disconnect ends the session and directs you to reattach. A step with `wait=true` includes any captures from the stop that ended it.

### Breakpoints (10)

| Tool                              | Parameters                                                | Description                                         |
|-----------------------------------|-----------------------------------------------------------|-----------------------------------------------------|
| `jdwp_set_breakpoint`             | `className`, `lineNumber`, `suspendPolicy?`, `condition?`, `triggerBreakpointId?`, `oneShot?`, `forceLoad?` | Set line breakpoint (conditions, deferred-by-default, trigger chaining; `forceLoad=true` binds immediately and runs `<clinit>`) |
| `jdwp_set_logpoint`               | `className`, `lineNumber`, `expression`, `condition?`, `forceLoad?` | Non-stopping line breakpoint that logs expression result |
| `jdwp_set_fast_logpoint`          | `className`, `lineNumber`, `expression`, `condition?`, `userApproved?`, `bufferSize?`, `forceLoad?` | **Rare, consent required.** Logpoint evaluated inside the target by injected bytecode — hits never suspend. Arms with standing approval for the exact probe at this host:port, or asks for `userApproved=true` after a risk briefing |
| `jdwp_set_fast_breakpoint`        | `className`, `lineNumber`, `condition`, `userApproved?`, `suspendPolicy?`, `stopOnce?`, `forceLoad?`, `capture?` | **Rare, consent required.** Evaluates the condition inside the target and optionally captures a value on each true stop, including the arming hit. Arms with standing approval for the exact probe at this host:port, or asks for `userApproved=true` after a risk briefing |
| `jdwp_restore_fast_logpoints`     | `id`, `userApproved?`                                     | **Rare, consent required.** Restores the original bytecode of a class carrying fast logpoints or fast breakpoints an earlier, crashed session left behind (`RECOVERED` in `jdwp_overview`). Without `userApproved=true` it changes nothing and returns a briefing |
| `jdwp_clear_breakpoint`           | `breakpointId`                                            | Remove a breakpoint by ID — routes by kind across line, exception, field BPs, fast logpoints and fast breakpoints (restoring the original bytecode, after the release of a thread stopped in the method; a leftover of an earlier session is only switched off) and removes its watchers |
| `jdwp_set_exception_breakpoint`   | `className`, `caught?`, `uncaught?`, `triggerBreakpointId?`, `oneShot?`, `forceLoad?`, `suspendPolicy?`, `condition?`, `thrownIn?`, `notThrownIn?` | Suspend on exception throw with optional condition and throw-site filters. Supports deferral, trigger chaining and `suspendPolicy="all"`. |
| `jdwp_set_exception_logpoint`     | `className`, `expression`, `condition?`, `caught?`, `uncaught?`, `triggerBreakpointId?`, `oneShot?`, `forceLoad?`, `thrownIn?`, `notThrownIn?` | Non-stopping exception breakpoint with `$exception` bound and optional throw-site filters. |
| `jdwp_set_field_breakpoint`       | `className`, `fieldName`, `mode`, `condition?`, `threadFilterId?`, `objectFilterId?`, `triggerBreakpointId?`, `oneShot?`, `forceLoad?`, `excludeConstructors?` | Suspend on field access/modification/both (supports conditions, filters, deferred, chaining; `excludeConstructors=true` ignores writes in the declaring class's constructors and static initializer) |
| `jdwp_set_field_logpoint`         | `className`, `fieldName`, `mode`, `expression`, `condition?`, `threadFilterId?`, `objectFilterId?`, `triggerBreakpointId?`, `oneShot?`, `forceLoad?`, `excludeConstructors?` | Non-stopping field watchpoint with `$oldValue`/`$newValue`/`$object`/`$fieldName`/`$mode` bound |

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
| `jdwp_evaluate_expression` | `expression`, `threadId?`, `frameIndex?`            | Evaluate Java expression at suspended frame (supports `{ block }`; `threadId` defaults to the current stop's thread) |
| `jdwp_assert_expression`   | `expression`, `expected`, `threadId?`, `frameIndex?` | Evaluate and compare against expected value                       |
| `jdwp_set_local`           | `threadId`, `frameIndex`, `varName`, `value`        | Set a local variable's value                                      |
| `jdwp_set_field`           | `objectId`, `fieldName`, `value`                    | Set a field's value on a cached object                            |

### Events (2)

| Tool                | Parameters | Description                                               |
|---------------------|------------|-----------------------------------------------------------|
| `jdwp_get_events`   | `count?`   | Recent events (breakpoints, steps, exceptions, logpoints, exception logs, fast logpoint hits, fast-breakpoint stops with captures, chain events, late evaluation results) |
| `jdwp_clear_events` | —          | Clear event history                                       |

### Diagnostics (1)

| Tool            | Parameters    | Description                                                                                                                                            |
|-----------------|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jdwp_diagnose` | `inspectAll?` | Three-block "state of the world" snapshot: (1) MCP server (PID/uptime/configured target), (2) JDWP connection — last-attempt error when disconnected, breakpoints+events report when connected, (3) Local JVMs visible to the user with their JDWP ports (confirmed via handshake). Pass `inspectAll=true` to attach briefly to every same-user JVM whose port could not be read from `/proc`, to discover the port via `sun.jdwp.listenerAddress` (default false — attaches are visible to targets). Recognises the chain-stuck state where every armed BP is WAITING on a non-fired trigger. Also shows project classpath discovery progress, entries by source, and failures, including unreadable Maven output. Warns when Maven helper processes may outlive a cancelled or timed-out run. Run this first when nothing seems to work. |

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
| `jdwp_overview` | `types?`, `filter?`, `showEmpty?` | Unified read-only listing of breakpoints, exception breakpoints, field breakpoints, logpoints, fast logpoints and fast breakpoints with standing approvals, watchers, and marks. Filter by type subset and/or case-insensitive substring (class/label/expression/type). |
| `jdwp_clear`    | `types`, `filter?`  | Bulk-delete by type and/or substring filter. `types` is REQUIRED (use `'all'` to clear every supported kind). Preview with `jdwp_overview` using the same args. Standing approval rows are informational. |

### Session (1)

| Tool         | Parameters | Description                                                                          |
|--------------|------------|--------------------------------------------------------------------------------------|
| `jdwp_reset` | —          | Clear session state without disconnecting. Restores the original bytecode of fast probes and retains standing approvals |

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
2. Ask the agent:
   "Set a breakpoint in OrderService.createOrder line 42 and wait for a hit"

3. The agent:
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

2. The agent:
   jdwp_set_logpoint(
     "com.example.OrderService", 42,
     "\"order=\" + order.getId() + \" total=\" + order.getTotal()",
     "order.getTotal() > 1000"
   )
   jdwp_resume()

3. Later:
   jdwp_get_events(count=20)
   → Shows LOGPOINT entries with evaluated expressions; no thread stayed stopped
```

### Catching exceptions at the throw site

```
1. "I'm getting a NullPointerException somewhere in the order flow"

2. The agent:
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

# From the agent:
jdwp_wait_for_attach(requireVmStart=true)
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
Claude Code / Codex ──MCP/STDIO──> Spring Boot MCP Server ──JDI──> Target JVM (port 5005)
```

The server is `SYNC` mode, `web-application-type=none` — JSON over STDIO, no HTTP.

### Core components

| Component                | Role                                                                                                                                               |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| **JDWPTools**            | 50 `@McpTool` methods and 2 `@McpResource` methods — the MCP surface. Thin orchestration over services below.                                      |
| **JDIConnectionService** | Singleton `VirtualMachine` connection. Object cache (`ConcurrentHashMap<Long, ObjectReference>`), smart collection rendering, classpath discovery. |
| **BreakpointTracker**    | Breakpoint registry with synthetic IDs. Tracks pending/deferred state, conditions, logpoint expressions, exception breakpoints, and chain dependencies (with cycle detection and trigger-fire memory across pending → active promotion). |
| **JdiEventListener**     | Daemon thread that drains the JDI event queue and routes each event set; the sets are processed in order on a `jdi-event-dispatcher` thread, which evaluates conditions and logpoints. Sets raised inside an MCP-driven evaluation are suppressed on the spot, so the listener never calls into the target. |
| **SuspensionSession**    | One per connection: tracks every routed event set and releases a suspension only through the context that owns it, so tools never resume threads blindly. Also runs steps, `jdwp_reset` and invocation leases. |
| **SuspensionWatchdog** / **InvocationRunner** | Keep invocations from hanging the server: the watchdog frees a dispatcher evaluation stuck on a lock a parked thread holds; the runner bounds a tool's invocation at 20 s (`[STILL_RUNNING]`, later `EVALUATION_COMPLETED_LATE`). |
| **EvaluationGuard**      | Per-thread reentrancy guard preventing deadlocks during expression evaluation.                                                                     |
| **EventHistory**         | Ring buffer of the last 500 events (including suppressed ones).                                                                                    |
| **FastLogpointService** (`fastlogpoint/`) | Fast logpoints and fast breakpoints: compiles the probe, redefines the class (**ProbeInstrumenter**, ASM), reads the in-target ring buffers, restores the original bytecode, and recognises probes a crashed server left behind. |
| **FrameView**            | Numbers a stopped thread's frames as the tools show them, hiding the probe frames above the user's code at a fast-breakpoint stop.               |
| **JdiHealthMonitor**     | Probes the JDI transport so soft-wait responses can report `wait_more` / `reconnect` / `abort` instead of blindly hanging.                          |
| **DeadlockAnalyzer**     | Builds the monitor-lock graph and finds lock-ordering cycles for `jdwp_dump_locks`.                                                                |
| **MarkedInstanceRegistry** (`marks/`) | Registry of named, pinned object references (`$label`) usable from condition / logpoint / watcher / assertion expressions.            |
| **JvmDiscoveryService** (`discovery/`) | Enumerates local JVMs, parses `-agentlib:jdwp=…` arguments, and confirms JDWP ports via handshake — powers `jdwp_diagnose` and the `jdwp://jvms` resource. |
| **MultiVersionStdioServerTransportProvider** (`transport/`) | Overrides Spring AI's hardcoded MCP protocol version on the STDIO transport so the server announces whatever the client negotiates. |
| **ClientWorkspace** (`transport/`) | Records the session directory Codex sends with every tool call (Codex starts plugin servers in the plugin directory); the local-project classpath fallback scans it. |

### Expression evaluation pipeline (`evaluation/`)

1. **JdiExpressionEvaluator** — Analyzes the stack frame, generates a wrapper class with a UUID name, delegates compilation, caches results.
2. **ClasspathDiscoverer** — Walks target JVM classloader hierarchy (including Tomcat/container) to find all JARs. Uses **JdkDiscoveryService** to locate a local JDK matching the target version.
3. **LocalProjectClasspathProvider** — Additive fallback for when the target's classloader hierarchy hides JARs (Tomcat, Spring Boot dev-tools, custom `URLClassLoader`s). Composes `JDWP_EXTRA_CLASSPATH` (override), a depth-5 scan of `target/classes` / `target/test-classes` under the project directory, and `mvn dependency:build-classpath` there. Discovery starts on attach and dispatcher evaluations use the remote classpath until the local entries are ready. The project directory is the server's working directory, or the session directory the client reports (Codex). Set `JDWP_EXTRA_CLASSPATH=/path/extra.jar:/path/more.jar` (colon/semicolon-separated) to plug specific gaps; `jdwp_diagnose` shows a per-source breakdown. Details in [docs/expression-evaluation.md](docs/expression-evaluation.md#localprojectclasspathprovider--local-project-classpath-fallback).
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
├── .claude-plugin/
│   ├── plugin.json                      # Claude Code plugin metadata + MCP server configuration
│   └── marketplace.json                 # Plugin marketplace registry (Claude Code and Codex)
├── plugin.json                          # Agent Plugins manifest (Codex): metadata + Codex listing
├── mcp.json                             # Agent Plugins MCP server registration (Codex)
├── scripts/
│   └── jdwp-inspector.sh                # Codex launcher: gets the JAR, then starts the server
├── hooks/
│   ├── hooks.json                       # SessionStart hook registration
│   └── ensure-server-jar.sh             # Auto-builds the JAR if missing or git HEAD moved (downloads it for the launcher)
├── skills/
│   └── java-debug/                      # Debugging skill (workflows, recipes, gotchas)
├── .claude/skills/release/              # Release-cutting skill (for maintainers)
├── release/sandbox/                     # Assembles the standalone jdwp-sandbox.zip of each release
├── docs/                                # Developer reference (architecture, lifecycle, …)
│
├── jdwp-mcp-server/                     # The MCP server
│   ├── pom.xml
│   └── src/main/java/one/edee/mcp/jdwp/
│       ├── JDWPMcpServerApplication.java
│       ├── JDWPTools.java               # 50 @McpTool methods + 2 @McpResource
│       ├── JDIConnectionService.java    # JDI connection + object cache
│       ├── BreakpointTracker.java       # Breakpoint registry + deferred state + chains
│       ├── JdiEventListener.java        # JDI event consumer
│       ├── SuspensionSession.java       # Who owns which suspension; steps, reset, invocation leases
│       ├── SuspensionWatchdog.java      # Frees a dispatcher evaluation stuck on a parked thread's lock
│       ├── InvocationRunner.java        # 20 s bound on a tool's invocation in the target
│       ├── FrameView.java               # Frame numbering that hides fast-breakpoint probe frames
│       ├── EvaluationGuard.java         # Recursive breakpoint protection
│       ├── EventHistory.java            # Event ring buffer
│       ├── JdiHealthMonitor.java        # Transport liveness for soft-wait
│       ├── DeadlockAnalyzer.java        # Monitor-lock graph + cycle detection
│       ├── ClassNameMatcher.java        # Glob → JDI ReferenceType matcher
│       ├── ThreadFormatting.java        # Thread/frame noise filtering
│       ├── evaluation/                  # Expression routing, JDI interpretation and compilation
│       │   ├── JdiExpressionEvaluator.java
│       │   ├── RemoteCodeExecutor.java
│       │   ├── InMemoryJavaCompiler.java
│       │   ├── ClasspathDiscoverer.java
│       │   ├── LocalProjectClasspathProvider.java
│       │   ├── JdkDiscoveryService.java
│       │   ├── FastLogpointProbeSource.java   # Probe sources of fast logpoints / fast breakpoints
│       │   ├── FastBreakpointProbeSource.java
│       │   └── exceptions/
│       ├── fastlogpoint/                # Fast logpoints + fast breakpoints (in-target probes)
│       │   ├── FastLogpointService.java
│       │   ├── ProbeInstrumenter.java   # ASM class rewriting
│       │   ├── ProbeRing.java           # Reads the in-target ring buffer
│       │   ├── LeftoverClassifier.java  # Probes a crashed server left behind
│       │   └── …
│       ├── marks/                       # Named, pinned heap references ($label)
│       │   ├── MarkedInstanceRegistry.java
│       │   ├── MarkInfo.java
│       │   └── ReservedBindings.java
│       ├── discovery/                   # Local-JVM inventory + JDWP port probing
│       │   ├── JvmDiscoveryService.java
│       │   ├── DiagnoseReportRenderer.java
│       │   ├── JdwpAgentArgParser.java
│       │   ├── JdwpEndpoint.java
│       │   └── JvmDescriptor.java
│       ├── transport/                   # STDIO transport overrides + client workspace
│       │   ├── MultiVersionStdioServerTransportProvider.java
│       │   ├── StdioTransportConfig.java
│       │   ├── ClientWorkspace.java     # Session directory reported by Codex
│       │   └── ClientWorkspaceConfig.java
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
- **ASM** — Bytecode rewriting for fast logpoints and fast breakpoints
- **JSpecify + NullAway** — Compile-time nullness enforcement

## Technical documentation

For Java developers who want to understand the internals — safety guarantees, memory allocation, threading, bootstrapping, state clearing, the test architecture — the [`docs/`](docs/) folder is the developer reference.

Start at **[docs/index.md](docs/index.md)** for navigation and a reading guide. The chapters cover:

- [architecture.md](docs/architecture.md) — system overview, components, data flow
- [lifecycle.md](docs/lifecycle.md) — bootstrapping, connect, disconnect, reset, the clearing matrix
- [threading-and-safety.md](docs/threading-and-safety.md) — concurrency, MCP SYNC, `INVOKE_SINGLE_THREADED`, the `EvaluationGuard` reentrancy mechanism
- [memory-and-references.md](docs/memory-and-references.md) — JDI mirrors, the object cache, marks, the compilation cache, byte-array mirroring
- [event-pipeline.md](docs/event-pipeline.md) — the JDI event listener loop and the suspension decision matrix
- [breakpoints.md](docs/breakpoints.md) — the registry, synthetic IDs, deferred breakpoints, conditions, logpoints, chains, fast logpoints and fast breakpoints
- [expression-evaluation.md](docs/expression-evaluation.md) — JDI navigation and the compiled evaluation pipeline
- [diagnostics.md](docs/diagnostics.md) — `jdwp_diagnose`, JVM discovery, the handshake probe, transport-loss envelopes, logging
- [testing.md](docs/testing.md) — test architecture for contributors

## Troubleshooting

| Problem                                         | Solution                                                                                                                                                            |
|-------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Module jdk.jdi not found` at server start      | The `java` on `PATH` is a JRE, not a JDK. Put a JDK 17+ first on `PATH` (the server is started with `--add-modules jdk.jdi,jdk.attach`).                            |
| Connection refused                              | Verify target JVM has `-agentlib:jdwp=...address=*:5005`. Check the port matches `-DJVM_JDWP_PORT`, or pass `port` (and `host`) to `jdwp_wait_for_attach`.         |
| MCP server doesn't respond                      | Rebuild: `./mvnw clean package -DskipTests`. Check jar path. Restart the agent.                                                                                     |
| `jdwp-debugging: could not build the MCP server JAR` at session start | The plugin's SessionStart hook tried `./mvnw`, `mvn -o` and `mvn`. Read the build log the message names, run the command it gives (it also writes the `.build-sha` marker), then reconnect `jdwp-inspector` in `/mcp`. |
| `jdwp-inspector` failed right after install, update or `git pull` | The SessionStart hook was still building the JAR when Claude Code started the server, and Claude Code skips a failed plugin server for 15 minutes. Reconnect it in `/mcp`, more than once if the build is still running. See [When `jdwp-inspector` does not connect](#when-jdwp-inspector-does-not-connect). |
| Codex: no `jdwp-inspector` tools right after install or update | The launcher was still downloading or building the JAR when Codex stopped waiting; it finishes in the background. Start a new session. If the tools stay missing, ask the agent to find out why, or read `logs/server-jar-setup.log` and `server/server-build.log` in the plugin's data directory. |
| MCP server times out on startup                 | JVM startup takes several seconds. Claude Code: start it with a longer wait, e.g. `MCP_TIMEOUT=60000 claude` (the variable must be in Claude Code's environment, not in the server's `env`). Codex: raise `startup_timeout_sec` of a manual registration; with the plugin, start a new session. |
| "Thread is not suspended"                       | The thread must be stopped at a breakpoint for stack/locals/expression tools.                                                                                       |
| First expression evaluation returns `[STILL_RUNNING]` | If the reply names local classpath discovery, check its progress with `jdwp_diagnose`. An evaluation that takes over 20 s returns `[STILL_RUNNING]` and finishes in the background; its result shows up in `jdwp_get_events` as `EVALUATION_COMPLETED_LATE`. Once it has finished, later evaluations use the cached classpath. |
| The client cuts off a blocking resume, attach, or step | Set the client tool-call timeout above the call's `timeoutMs` or the 30-second limit for a step with `wait=true`. Codex: raise `tool_timeout_sec` of a manual registration. Claude Code waits long by default; raise `MCP_TOOL_TIMEOUT` in its environment if it is set low. |
| `resume_until_event` returns `[TIMEOUT]` / `[RESUME_FAILED]` / `[NO_EVENT]` | `[TIMEOUT]` reports the transport's health when the wait expires. For `[RESUME_FAILED]`, use the targeted retry in the reply when available. See [Execution control](#execution-control-7) for the scope of each resume tool. `[NO_EVENT]` means reset ran during the wait. Check its reply for failed holds and set breakpoints again. |
| Build fails with Error Prone / toolchain error  | Building from source needs JDK 21+ on the toolchain. The bytecode target stays at Java 17, but the build step itself needs ≥21 because Error Prone 2.48 ships Java-21 bytecode. |

## License

MIT
