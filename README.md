# MCP JDWP Inspector

MCP server that gives AI agents full debugger control over running Java applications — inspect state, set breakpoints, evaluate expressions, and mutate values at runtime via JDWP/JDI.

**Built on the foundations of [mcp-jdwp-java](https://github.com/NicolasVautrin/mcp-jdwp-java) by [Nicolas Vautrin](https://github.com/NicolasVautrin)** — the original project that provided core JDI connectivity, thread/stack/variable inspection, stepping, and basic breakpoint management. Everything described below as "beyond standard JDWP" was built on top of that base.

## Security & trust

This MCP server runs **entirely on your local machine**:

- **No network calls** — the server communicates with Claude Code over STDIO and with the target JVM over a local JDWP socket. It makes zero outbound HTTP/internet requests. No telemetry, no analytics, no phone-home.
- **Built from source** — the JAR is compiled locally on your machine from the source code in this repository (either via the plugin auto-build hook or manually with `mvn clean package`). No pre-built binaries are downloaded or distributed.
- **Auditable** — the full source is here. The server is a standard Spring Boot application with no obfuscation or native code.

## Why this exists

Raw JDWP/JDI gives you threads, stack frames, and variables. That's enough for a human with IntelliJ — but an AI agent needs more:

- **Conditional breakpoints** — stop only when a Java expression is true, so the agent isn't flooded with irrelevant hits
- **Logpoints** — evaluate an expression and log the result *without* stopping, for non-intrusive tracing
- **Deferred breakpoints** — set breakpoints on classes that haven't loaded yet; they activate automatically
- **Exception breakpoints** — catch exceptions at the throw site, not after they've unwound the stack
- **Expression evaluation** — compile and execute arbitrary Java at any breakpoint, with full classpath
- **Value mutation** — change local variables and object fields at runtime to test hypotheses
- **Recursive breakpoint protection** — expression evaluation is safe even when it re-enters the breakpointed line
- **Smart filtering** — JVM internal threads and framework noise frames are hidden by default
- **Blocking resume** — `resume_until_event` eliminates the "resume → poll → poll" dance

40 MCP tools, exposed over STDIO. Your agent gets the same power as IntelliJ's debugger.

## Quick start

### Prerequisites

- **JDK 17+** (must be a JDK, not a JRE — JDI lives in `jdk.jdi`)
- **Maven 3.8+** (wrapper included)

### 1. Install the plugin

**Option A: Plugin marketplace (recommended)**

Installs the MCP server, the `java-debug` skill (debugging workflows, recipes, gotchas), and the `.mcp.json` configuration in one step:

```bash
/plugin marketplace add https://github.com/FgForrest/mcp-jdwp-java.git
/plugin install jdwp-debugging@mcp-jdwp-java
```

The server JAR is built automatically on first session start (requires JDK 17+ and Maven on PATH). Restart Claude Code to pick up the plugin.

<details>
<summary><strong>Alternative: manual MCP registration (without plugin)</strong></summary>

If you prefer to register the MCP server directly without the plugin (no skill, no auto-build):

**1. Build the JAR:**

```bash
git clone https://github.com/FgForrest/mcp-jdwp-java.git
cd mcp-jdwp-java
mvn clean package -DskipTests
```

**2. Register with Claude Code:**

```bash
claude mcp add jdwp-inspector -s user \
  -e MCP_TIMEOUT=30000 \
  -e MCP_TOOL_TIMEOUT=120000 \
  -- java --add-modules jdk.jdi -jar /path/to/mcp-jdwp-java.jar
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
        "--add-modules", "jdk.jdi",
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

## Find the Bug — test flights

The `jdwp-sandbox` module ships 5 deliberately broken Java classes. Each one compiles fine, looks reasonable at first glance, and **fails its test with a confusing message**. Your job: attach with the JDWP MCP server and find the root cause.

This doubles as a setup verification — if you can solve these, everything works.

### How to launch a test flight

Start Claude Code from the repo root and type:

```
Use JDWP to debug <TestClass> in the jdwp-sandbox module — the test is failing, find the root cause.
```

---

### #1 The Vanishing Pennies

**Difficulty:** Warm-up | **Test:** `OrderProcessorTest` | **Package:** `order`

**Symptom:** `expected 71.982 but was 71.0` — the order total loses its decimal part somewhere between calculation and return.

**Hint:** The calculation is correct. Something *after* it changes the total. Who would mutate an order during logging?

<details>
<summary><strong>Reveal root cause</strong></summary>

`AuditLogger.log(order)` calls `cacheFormattedTotal(order)`, which silently mutates the order: `order.setTotal((double)(int)(order.getTotal()))` — truncating 71.982 to 71.0. The caller assumes `log()` is read-only, but it isn't.

**Debug path:** Set a breakpoint in `OrderProcessor.process()`, step over the `log()` call, and eval `order.getTotal()` before and after. The value changes across what should be a side-effect-free call.

</details>

---

### #2 The Phantom Session

**Difficulty:** Moderate | **Test:** `SessionStoreTest` | **Package:** `session`

**Symptom:** `retrieve() returned null` — a session was stored, upgraded, and then... vanished from the map.

**Hint:** The session is still *in* the HashMap. The HashMap just can't *find* it anymore.

<details>
<summary><strong>Reveal root cause</strong></summary>

`UserSession` is used as a HashMap key, with both `userId` and `role` in `hashCode()`. `upgradeUserRole()` calls `session.upgradeRole("PREMIUM")`, which mutates `role` — changing the hashCode while the key is still in the map. The entry sits in the old hash bucket; lookups compute the new hash and search the wrong bucket.

**Debug path:** Breakpoint before and after `upgradeRole()`. Use `jdwp_assert_expression("session.hashCode()", "<value-before>")` after the upgrade — `MISMATCH` confirms the hash drifted.

</details>

---

### #3 The Swallowed Exception

**Difficulty:** Moderate | **Test:** `EventBusTest` | **Package:** `events`

**Symptom:** `expected stock < 100 but was 100` and no error summary — the order was supposed to reserve inventory, but nothing happened and nobody complained.

**Hint:** There are actually *two* bugs. One is hiding the other. Start with the exception — why isn't the error summary showing anything?

<details>
<summary><strong>Reveal root cause</strong></summary>

**Bug 1 (hidden):** `OrderEvent` casts the raw quantity through `byte`: a quantity of 200 overflows to -56. `Inventory.reserve()` throws `IllegalStateException("Cannot reserve negative quantity: -56")`.

**Bug 2 (hiding bug 1):** `EventBus.dispatch()` catches the exception and wraps it in `CompletionException` -> `EventHandlerException` -> original cause. But `getErrorSummary()` only reports top-level messages ("Async task failed"), losing the root cause entirely.

**Debug path:** Set an exception breakpoint on `IllegalStateException`. The throw site reveals the -56 quantity. Then inspect the `OrderEvent` construction to see the byte cast.

</details>

---

### #4 The Time Traveler's Config

**Difficulty:** Hard | **Test:** `ConfigurationProviderTest` | **Package:** `config`

**Symptom:** `expected timeout=5000 but was 0` — the configuration exists but its timeout field is still at the default value.

**Hint:** The config object is assigned to the shared field *before* it's fully initialized. A reader thread sees the reference but reads a half-constructed object.

<details>
<summary><strong>Reveal root cause</strong></summary>

`ConfigurationProvider.getConfig()` assigns `instance = new Configuration(...)` and then signals a latch — but calls `instance.init()` (which sets `timeout=5000`) *after* the latch release. A reader thread waiting on that latch sees `instance != null` and returns the un-initialized object with `timeout=0`.

**Debug path:** Set breakpoints in both the initializer and reader paths of `getConfig()`. Use `jdwp_get_threads()` to see both threads, then inspect the `Configuration` object from each thread's perspective. The initializer hasn't called `init()` yet when the reader returns.

</details>

---

### #5 The Audit That Lies

**Difficulty:** Hard | **Test:** `TransferServiceTest` | **Package:** `bank`

**Symptom:** `expected discrepancy=0 but was non-zero` — money is neither created nor destroyed, yet the audit says the books don't balance.

**Hint:** The transfer moves money in two steps. The audit snapshot is taken between them.

<details>
<summary><strong>Reveal root cause</strong></summary>

`TransferService.transfer()` is not atomic: it calls `source.withdraw()`, then `auditService.snapshotBalances()`, then `destination.deposit()`. The snapshot captures the intermediate state where money has left the source but hasn't arrived at the destination — showing a total of 1500 instead of 2000.

**Debug path:** Set breakpoints on `withdraw()`, `snapshotBalances()`, and `deposit()`. Step through and eval `source.getBalance() + destination.getBalance()` at each stop. The total drops after withdraw and recovers after deposit — the snapshot catches the dip.

</details>

---

### Scorecard

| Solved | Rating                                                               |
|--------|----------------------------------------------------------------------|
| 0-1    | The JVM is winning. Check your setup.                                |
| 2-3    | Solid start. You're getting the hang of breakpoint-driven debugging. |
| 4      | Impressive. You found bugs that would take hours with println.       |
| 5      | Bug terminator. Nothing survives your debugger.                      |

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

The breakpoint fires on every hit, but the thread is only suspended when the condition evaluates to `true`. False hits are auto-resumed transparently. This is essential for AI agents — without conditions, a breakpoint in a hot loop would generate thousands of useless stops.

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

### Deferred breakpoints

```
jdwp_set_breakpoint(className="com.example.LazyService", lineNumber=15)
→ "Breakpoint deferred — class com.example.LazyService not yet loaded. Will activate on class load."
```

If the target class isn't loaded when the breakpoint is set, the server registers a `ClassPrepareRequest` and automatically promotes the breakpoint to active when the JVM loads the class. Works for line breakpoints, logpoints, and exception breakpoints. `jdwp_list_breakpoints()` shows pending breakpoints with their status.

### Exception breakpoints

```
jdwp_set_exception_breakpoint(
  exceptionClass="java.lang.NullPointerException",
  caught=true,
  uncaught=true
)
```

Catch exceptions at the throw site — before the stack unwinds. Supports deferred activation (if the exception class isn't loaded yet). Use `jdwp_list_exception_breakpoints()` to see active and pending exception breakpoints.

**Log-only mode** — record the throw without suspending the thread, optionally evaluating an expression with `$exception` bound to the thrown object:

```
jdwp_set_exception_breakpoint(
  exceptionClass="java.sql.SQLException",
  logOnly=true,
  expression="$exception.getSQLState() + \": \" + $exception.getMessage()"
)
```

Each hit records an `EXCEPTION_LOG` entry to `jdwp_get_events`; failures during evaluation surface as `EXCEPTION_LOG_ERROR` so the listener never throws. A non-null `expression` implies `logOnly=true`. Use this to trace exception flows in long-running services without stopping them.

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

See [docs/EXPRESSION_EVALUATION.md](docs/EXPRESSION_EVALUATION.md) for the compilation pipeline details.

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

**Threads:** `jdwp_get_threads()` hides JVM internals (Reference Handler, Finalizer, surefire workers) by default. Pass `includeSystemThreads=true` to see everything.

**Stack frames:** `jdwp_get_stack()` collapses junit/surefire/reflection noise frames by default. Pass `includeNoise=true` to see the full stack.

### Blocking resume

```
jdwp_resume_until_event(timeoutMs=30000)
→ blocks until next breakpoint/step/exception, returns context immediately
```

Replaces the manual "resume → poll events → poll events" pattern. The agent resumes and gets the next stop in one synchronous call.

### One-shot breakpoint context

```
jdwp_get_breakpoint_context(maxFrames=5, includeThisFields=true)
```

Returns thread info, top stack frames, locals at frame 0, and `this` fields in a single call — replaces the four-call sequence `get_current_thread → get_stack → get_locals → get_fields(this)` that an agent would otherwise need at every breakpoint hit.

## Tool reference (40 tools)

### Connection (3)

| Tool                   | Parameters                     | Description                              |
|------------------------|--------------------------------|------------------------------------------|
| `jdwp_connect`         | —                              | Connect to JDWP on configured host:port  |
| `jdwp_disconnect`      | —                              | Disconnect (sends JDWP Dispose)          |
| `jdwp_wait_for_attach` | `host?`, `port?`, `timeoutMs?` | Poll until JVM is listening, then attach |

### Inspection (8)

| Tool                          | Parameters                                | Description                                           |
|-------------------------------|-------------------------------------------|-------------------------------------------------------|
| `jdwp_get_version`            | —                                         | JVM version info                                      |
| `jdwp_get_threads`            | `includeSystemThreads?`                   | List threads with status and frame counts             |
| `jdwp_get_stack`              | `threadId`, `maxFrames?`, `includeNoise?` | Stack trace (noise frames collapsed by default)       |
| `jdwp_get_locals`             | `threadId`, `frameIndex`                  | Local variables at a frame (includes `this`)          |
| `jdwp_get_fields`             | `objectId`                                | Object fields, collection elements, or array contents |
| `jdwp_to_string`              | `objectId`, `threadId`                    | Invoke `toString()` on a cached object                |
| `jdwp_get_breakpoint_context` | `maxFrames?`, `includeThisFields?`        | One-shot context dump at current breakpoint           |
| `jdwp_get_current_thread`     | —                                         | Thread ID of the last breakpoint hit                  |

### Execution control (7)

| Tool                      | Parameters   | Description                                           |
|---------------------------|--------------|-------------------------------------------------------|
| `jdwp_resume`             | —            | Resume all threads                                    |
| `jdwp_resume_thread`      | `threadId`   | Resume a specific thread                              |
| `jdwp_suspend_thread`     | `threadId`   | Suspend a specific thread                             |
| `jdwp_resume_until_event` | `timeoutMs?` | Resume and block until next breakpoint/step/exception |
| `jdwp_step_over`          | `threadId`   | Step over (F6)                                        |
| `jdwp_step_into`          | `threadId`   | Step into (F7)                                        |
| `jdwp_step_out`           | `threadId`   | Step out (Shift+F8)                                   |

### Breakpoints (9)

| Tool                              | Parameters                                                | Description                                         |
|-----------------------------------|-----------------------------------------------------------|-----------------------------------------------------|
| `jdwp_set_breakpoint`             | `className`, `lineNumber`, `suspendPolicy?`, `condition?` | Set breakpoint (supports conditions and deferred)   |
| `jdwp_set_logpoint`               | `className`, `lineNumber`, `expression`, `condition?`     | Non-stopping breakpoint that logs expression result |
| `jdwp_clear_breakpoint`           | `className`, `lineNumber`                                 | Remove breakpoint by location                       |
| `jdwp_clear_breakpoint_by_id`     | `breakpointId`                                            | Remove breakpoint by ID                             |
| `jdwp_list_breakpoints`           | —                                                         | List all breakpoints (active, pending, failed)      |
| `jdwp_clear_all_breakpoints`      | —                                                         | Remove all breakpoints                              |
| `jdwp_set_exception_breakpoint`   | `exceptionClass`, `caught?`, `uncaught?`, `logOnly?`, `expression?` | Break on exception throw (supports deferred and log-only with `$exception` binding) |
| `jdwp_clear_exception_breakpoint` | `breakpointId`                                            | Remove exception breakpoint                         |
| `jdwp_list_exception_breakpoints` | —                                                         | List exception breakpoints (active and pending)     |

### Expression evaluation and mutation (4)

| Tool                       | Parameters                                          | Description                                 |
|----------------------------|-----------------------------------------------------|---------------------------------------------|
| `jdwp_evaluate_expression` | `threadId`, `expression`, `frameIndex?`             | Evaluate Java expression at suspended frame |
| `jdwp_assert_expression`   | `expression`, `expected`, `threadId`, `frameIndex?` | Evaluate and compare against expected value |
| `jdwp_set_local`           | `threadId`, `frameIndex`, `varName`, `value`        | Set a local variable's value                |
| `jdwp_set_field`           | `objectId`, `fieldName`, `value`                    | Set a field's value on a cached object      |

### Events (2)

| Tool                | Parameters | Description                                               |
|---------------------|------------|-----------------------------------------------------------|
| `jdwp_get_events`   | `count?`   | Recent events (breakpoints, steps, exceptions, logpoints, exception logs) |
| `jdwp_clear_events` | —          | Clear event history                                       |

### Watchers (6)

| Tool                                | Parameters                            | Description                                         |
|-------------------------------------|---------------------------------------|-----------------------------------------------------|
| `jdwp_attach_watcher`               | `breakpointId`, `label`, `expression` | Attach expression watcher to a breakpoint           |
| `jdwp_detach_watcher`               | `watcherId`                           | Remove a watcher                                    |
| `jdwp_list_watchers_for_breakpoint` | `breakpointId`                        | List watchers on a breakpoint                       |
| `jdwp_list_all_watchers`            | —                                     | List all watchers across all breakpoints            |
| `jdwp_evaluate_watchers`            | `threadId`, `scope`, `breakpointId?`  | Evaluate watchers (`current_frame` or `full_stack`) |
| `jdwp_clear_all_watchers`           | —                                     | Remove all watchers                                 |

### Session (1)

| Tool         | Parameters | Description                                                                  |
|--------------|------------|------------------------------------------------------------------------------|
| `jdwp_reset` | —          | Clear all state (breakpoints, watchers, cache, events) without disconnecting |

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
mvn -pl jdwp-sandbox test -Dtest=RecursiveCalculatorTest -DskipTests=false -Dmaven.surefire.debug

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
| **JDWPTools**            | 40 `@McpTool` methods — the MCP surface. Thin orchestration over services below.                                                                   |
| **JDIConnectionService** | Singleton `VirtualMachine` connection. Object cache (`ConcurrentHashMap<Long, ObjectReference>`), smart collection rendering, classpath discovery. |
| **BreakpointTracker**    | Breakpoint registry with synthetic IDs. Tracks pending/deferred state, conditions, logpoint expressions, exception breakpoints.                    |
| **JdiEventListener**     | Daemon thread consuming the JDI event queue. Routes events, evaluates conditions/logpoints, handles recursive suppression.                         |
| **EvaluationGuard**      | Per-thread reentrancy guard preventing deadlocks during expression evaluation.                                                                     |
| **EventHistory**         | Ring buffer of the last 100 JDWP events (including suppressed).                                                                                    |

### Expression evaluation pipeline (`evaluation/`)

1. **JdiExpressionEvaluator** — Analyzes the stack frame, generates a wrapper class with a UUID name, delegates compilation, caches results.
2. **ClasspathDiscoverer** — Walks target JVM classloader hierarchy (including Tomcat/container) to find all JARs. Uses **JdkDiscoveryService** to locate a local JDK matching the target version.
3. **InMemoryJavaCompiler** — Compiles Java source to bytecode using Eclipse JDT (ECJ), entirely in memory.
4. **RemoteCodeExecutor** — Injects bytecode via `ClassLoader.defineClass()` and invokes it.

### Watcher system (`watchers/`)

- **WatcherManager** — CRUD, dual-indexed by watcher UUID and breakpoint ID. Auto-cleans when breakpoint is deleted.
- **Watcher** — Immutable model: id, label, breakpointId, expression.

## Project structure

```
mcp-jdwp-java/
├── pom.xml                              # Parent POM (reactor)
├── mvnw / mvnw.cmd                      # Maven wrapper
├── README.md
├── WORKFLOW.md                          # Development guide
├── .mcp.json                            # MCP server configuration
├── .claude-plugin/
│   ├── plugin.json                      # Claude Code plugin metadata
│   └── marketplace.json                 # Plugin marketplace registry
├── hooks/
│   └── hooks.json                       # SessionStart hook: auto-builds JAR if missing
├── skills/
│   └── java-debug/
│       ├── SKILL.md                     # Debugging skill (workflows, recipes, gotchas)
│       └── references/
│           ├── prerequisites.md         # Build-system-specific JDWP launch details
│           └── troubleshooting.md       # MCP server troubleshooting
├── docs/
│   └── EXPRESSION_EVALUATION.md         # Expression evaluation pipeline docs
│
├── jdwp-mcp-server/                     # The MCP server
│   ├── pom.xml
│   └── src/main/java/one/edee/mcp/jdwp/
│       ├── JDWPMcpServerApplication.java
│       ├── JDWPTools.java               # 40 @McpTool methods
│       ├── JDIConnectionService.java    # JDI connection + object cache
│       ├── BreakpointTracker.java       # Breakpoint registry + deferred state
│       ├── JdiEventListener.java        # JDI event consumer
│       ├── EvaluationGuard.java         # Recursive breakpoint protection
│       ├── EventHistory.java            # Event ring buffer
│       ├── ThreadFormatting.java        # Thread/frame noise filtering
│       ├── evaluation/
│       │   ├── JdiExpressionEvaluator.java
│       │   ├── RemoteCodeExecutor.java
│       │   ├── InMemoryJavaCompiler.java
│       │   ├── ClasspathDiscoverer.java
│       │   └── JdkDiscoveryService.java
│       └── watchers/
│           ├── WatcherManager.java
│           └── Watcher.java
│
└── jdwp-sandbox/                        # Debugging targets (test flights)
    ├── pom.xml                          # Tests skipped by default
    └── src/                             # Deliberately broken scenarios
```

## Dependencies

- **Spring Boot 4.0** — Framework
- **Spring AI MCP 2.0.0-M4** — MCP protocol integration
- **JDI** (`jdk.jdi` module) — Java Debug Interface
- **Eclipse JDT Compiler (ECJ)** — In-memory expression compilation
- **JSpecify + NullAway** — Compile-time nullness enforcement

## Troubleshooting

| Problem                                         | Solution                                                                                                                                                            |
|-------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `tools.jar not found` / `jdk.jdi not available` | Ensure `JAVA_HOME` points to a JDK, not a JRE. Launch with `--add-modules jdk.jdi`.                                                                                 |
| Connection refused                              | Verify target JVM has `-agentlib:jdwp=...address=*:5005`. Check port matches `-DJVM_JDWP_PORT`.                                                                     |
| MCP server doesn't respond                      | Rebuild: `mvn clean package -DskipTests`. Check jar path. Restart Claude Code.                                                                                      |
| MCP server times out on startup                 | JVM startup takes several seconds. Ensure `MCP_TIMEOUT=30000` (or higher) is set in the MCP registration — the default is too short for a Spring Boot Java process. |
| "Thread is not suspended"                       | The thread must be stopped at a breakpoint for stack/locals/expression tools.                                                                                       |
| Expression evaluation timeout                   | First evaluation is slow (classpath discovery). Increase `MCP_TOOL_TIMEOUT`. Subsequent evaluations use cache.                                                      |

## License

MIT
