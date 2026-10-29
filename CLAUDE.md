# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Run from the reactor root. Builds both modules.
# Prefer ./mvnw (the bundled Maven Wrapper) — it pins the Maven version and
# does not require Maven to be installed system-wide. Plain `mvn` works too
# if it is on PATH.
./mvnw compile                                    # Compile only
./mvnw clean package -DskipTests                  # Full build (produces jdwp-mcp-server/target/mcp-jdwp-java.jar)

# Build only the MCP server (skips the sandbox module entirely):
./mvnw -pl jdwp-mcp-server -am clean package -DskipTests
```

The repository is a 2-module Maven reactor:

- **`jdwp-mcp-server/`** — the real MCP server. Contains all genuine unit tests; `./mvnw -pl jdwp-mcp-server test` runs them.
- **`jdwp-sandbox/`** — deliberately broken Java classes used as JDWP debugging targets ("test flights"). Sandbox tests are expected-to-fail by design and are **skipped by default** so a normal `./mvnw test` stays green. Run them explicitly during a test flight with: `./mvnw -pl jdwp-sandbox test -DskipTests=false`.

The compiler requires `--add-modules jdk.jdi` (configured in `jdwp-mcp-server/pom.xml`). At runtime, the JAR must also be launched with `--add-modules jdk.jdi`.

## Architecture

This is a **Spring Boot MCP Server** that lets Claude Code inspect and control running Java applications via JDWP (Java Debug Wire Protocol). It connects directly to a target JVM's JDWP port (default 5005).

```
Claude Code ──MCP/STDIO──> Spring Boot MCP Server ──JDI──> Target JVM (port 5005)
```

### Core Components

- **JDWPTools** — Exposes ~30 MCP tools via `@McpTool` annotations. This is the main interface: connection, thread/stack/variable inspection, breakpoints, stepping, and watcher evaluation. All tool methods return formatted `String` responses.
- **JDIConnectionService** — Singleton holding the persistent `VirtualMachine` (JDI) connection. Manages object caching (`ConcurrentHashMap<Long, ObjectReference>`) and smart collection rendering (ArrayList, HashMap, HashSet internals). Also drives classpath discovery for expression evaluation.
- **BreakpointTracker** — Maintains a registry of breakpoints with synthetic integer IDs (since JDI `BreakpointRequest` has no user-facing ID). Keeps the last published stop's report for display; which stop is current is `SuspensionSession`'s call.
- **JdiEventListener** — Daemon thread consuming the JDI event queue; the event sets are processed in order on a `jdi-event-dispatcher` thread, except a set raised inside an MCP-driven evaluation, which the listener suppresses and releases at once. Handlers only propose stop candidates; `SuspensionSession.finish` publishes the first one or releases the set. A published stop stays suspended for user inspection.
- **SuspensionSession** (#45) — One per connection; every routed event set is a context (QUEUED → PROCESSING → HELD | RELEASED) and only its context releases its suspension. Tools release only stops a tool has presented, plus `jdwp_suspend_thread` holds — never blindly. It also runs steps, the `jdwp_reset` generation and invocation leases (admission, exemptions, ownership transfer). **SuspensionWatchdog** frees a dispatcher evaluation stuck on a monitor a queued set's thread holds; **InvocationRunner** bounds a tool's invocation at 20 s (`[STILL_RUNNING]`, later `EVALUATION_COMPLETED_LATE`). See `docs/event-pipeline.md` § 14.

### Expression Evaluation Pipeline (`evaluation/` package)

Evaluates arbitrary Java expressions at breakpoints by compiling and injecting bytecode into the target JVM:

1. **JdiExpressionEvaluator** — Orchestrator. Analyzes the stack frame, generates a wrapper class with a unique UUID name, delegates compilation, and caches results.
2. **ClasspathDiscoverer** — Walks the target JVM's classloader hierarchy (including Tomcat/container classloaders) to discover all JARs. Also uses **JdkDiscoveryService** to find a local JDK matching the target JVM version.
3. **InMemoryJavaCompiler** — Compiles Java source to bytecode using Eclipse JDT (ECJ), entirely in memory.
4. **RemoteCodeExecutor** — Injects compiled bytecode into the target JVM via `ClassLoader.defineClass()` and invokes it.

### Watcher System (`watchers/` package)

- **WatcherManager** — CRUD for watchers, dual-indexed by watcher UUID and breakpoint ID.
- **Watcher** — Immutable model: id, label, breakpointId, expression.

Watchers are MCP-side only — they store expressions that get evaluated via the expression pipeline when a breakpoint is hit.

### Fast Logpoints (`fastlogpoint/` package)

`jdwp_set_fast_logpoint` evaluates a logpoint inside the target JVM: on the first hit, **FastLogpointService** compiles a probe class (`FastLogpointProbeSource`), defines it in the declaring class's loader, and redefines the class with a call to it (**ProbeInstrumenter**, ASM). Hits append to an in-target ring buffer that `jdwp_get_events` drains via plain field reads (**ProbeRing**). It rewrites the application's bytecode, so the tool arms nothing without `userApproved=true` (it returns **FastLogpointRiskBriefing** instead). Arming is refused when the live bytecode differs from the class file (**ClassFileMethods**); tracked breakpoints in the class are re-created after every redefinition; clear / reset / disconnect / reconnect / shutdown restore the original bytes. Each probe describes itself (`MCP_*` constants) and carries the class's install record (**GenerationRecord**: original bytes + the bytecode HotSpot installed), so the next attach finds fast logpoints a dead server left behind (**LeftoverClassifier**, wiring observed by **ProbeCallScanner**) and lists them as RECOVERED / STRANDED; they are never owned by the new session — clear only disables them, and `jdwp_restore_fast_logpoints` restores their class only with `userApproved=true`. See `docs/breakpoints.md` § 5a and § 5b.

### Nullness

`jdwp-mcp-server` is `@NullMarked` at the package level — every type, field, parameter and return in the module is **non-null by default**. The small number of legitimately nullable slots are annotated with `org.jspecify.annotations.Nullable`. The convention is enforced at compile time by NullAway running as an Error Prone plugin in `maven-compiler-plugin` (see `jdwp-mcp-server/pom.xml`); a `./mvnw -pl jdwp-mcp-server compile` that emits any `[NullAway]` diagnostic fails the build.

When adding new code, do not introduce un-annotated nullable slots — if a value can legitimately be null, annotate the declaration with `@Nullable` from `org.jspecify.annotations`. `jdwp-sandbox` is intentionally unmarked: its classes are deliberately broken debugging targets and annotations there would muddy the exercises.

**MCP tool parameters:** Every `@McpToolParam(required = false)` parameter is nullable at runtime — the MCP framework passes `null` when the client omits the value. These parameters are a system boundary, not internal code. Always null-guard them before dereferencing, even though `@NullMarked` makes them look non-null. Boxed types (`Integer`, `Boolean`, `Long`) will NPE on auto-unbox; `String` will NPE on `.isBlank()` or `.toLowerCase()`. The standard patterns are:
- `Integer`: `(param != null && param > 0) ? param : defaultValue`
- `Boolean`: `param != null && param` (default false) or `param == null || param` (default true)
- `String`: `(param == null || param.isBlank()) ? "default" : param`
- `Long` (optional thread ID): null → fall back to `breakpointTracker.getLastBreakpointThread()`

## Key Design Decisions

- MCP server type is `SYNC` and `web-application-type=none` — communication is JSON over STDIO, no HTTP server.
- `JDIConnectionService.disconnect()` calls `vm.dispose()` for clean JDWP teardown.
- Object references are cached globally so users can navigate object graphs across multiple tool calls.
- Expression wrapper classes use UUID-based naming to avoid `LinkageError` across server restarts.
- The JDI event listener thread must consume all events to prevent the target JVM from blocking — so it never calls into the target itself: an invoked method can hit a breakpoint whose event only the listener can drain (see `docs/event-pipeline.md` § 1).
