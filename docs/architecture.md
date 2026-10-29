# Architecture

The MCP JDWP Inspector is a Spring Boot application that sits between a Claude Code session and a target JVM. It speaks **MCP over STDIO** to the client and **JDI over a JDWP socket** to the target. Everything else — the breakpoint registry, the expression-evaluation pipeline, the event history — is server-local state that exists to make those two protocols meet.

> **Background — JDI, JDWP, MCP, and why they need each other**
>
> Three acronyms anchor this whole codebase; they look interchangeable but each names a different layer.
>
> **JDWP** (Java Debug Wire Protocol) is the *wire format* the OpenJDK debugger uses. It is a binary request/response protocol carried over a TCP socket (transport `dt_socket`) or a named pipe (`dt_shmem` on Windows). Every conversation starts with both sides exchanging the 14 ASCII bytes `"JDWP-Handshake"`; after that, the client sends command packets (command-set ID + command ID + payload) and the target sends reply packets and asynchronous event packets. The protocol is part of the OpenJDK platform and is the same on every conforming JVM.
>
> **JDI** (Java Debug Interface, `jdk.jdi` module) is the *Java API* on the debugger side. `com.sun.jdi.VirtualMachine`, `ObjectReference`, `BreakpointRequest`, `EventQueue` — all those types are JDI. Under the hood, every JDI call serialises a JDWP packet, writes it to the socket, and decodes the reply. The MCP server is a JDI client; the target JVM is a JDWP server. JDI lives in a separate JDK module from the rest of the JDK, which is why the server launch line includes `--add-modules jdk.jdi` — without it, `com.sun.jdi.*` is not resolvable at runtime. JDI is shipped only with the JDK (not the JRE) because the implementation depends on JDK-internal classes.
>
> **MCP** (Model Context Protocol) is a separate, unrelated protocol — JSON-RPC framed over any transport (STDIO, HTTP/SSE, WebSockets). It is what Claude Code uses to talk to "tools" running outside the model. The MCP server here advertises tools (the `@McpTool` methods) and resources (`@McpResource`), and the client invokes them by name with JSON arguments. The Spring AI MCP integration handles the framing, schema generation from `@McpToolParam`, and dispatch.
>
> The plugin exists to translate between the two worlds: an MCP tool call comes in over STDIO → the server makes the equivalent JDI call → the target replies over JDWP → the answer flows back to the agent as JSON. Most of this documentation is about what happens in the middle.

## System diagram

```
                ┌──────────────────────────────────────────────────────┐
                │                  Claude Code (client)                │
                │                                                      │
                │   Tool calls (JSON-RPC over MCP)         Tool result │
                └─────────────────┬──────────────────────────▲─────────┘
                                  │ stdin                   stdout
                                  ▼                          │
                ┌──────────────────────────────────────────────────────┐
                │                MCP server JVM (this code)            │
                │ ┌────────────────────────────────────────────────┐   │
                │ │ MultiVersionStdioServerTransportProvider       │   │
                │ │  → Spring AI MCP dispatch (SYNC, single-thread)│   │
                │ └────────────────────┬───────────────────────────┘   │
                │                      ▼                               │
                │ ┌────────────────────────────────────────────────┐   │
                │ │ JDWPTools (≈ 46 @McpTool methods)              │   │
                │ └──┬──────────┬────────────┬───────────┬──────────┘   │
                │    ▼          ▼            ▼           ▼              │
                │  JDIConn-  Breakpoint-   Watcher-   Marked-           │
                │  Service   Tracker       Manager    Instance-         │
                │   │  ▲                                Registry        │
                │   │  │ events                                         │
                │   │  │                                                │
                │   │  └────── JdiEventListener (daemon thread)         │
                │   │                                                   │
                │   │ JDI calls (suspend, methodInvoke, defineClass…)   │
                │   ▼                                                   │
                │ com.sun.jdi.VirtualMachine                            │
                └──────────────────────────┬───────────────────────────┘
                                           │ JDWP socket (default :5005)
                                           ▼
                ┌──────────────────────────────────────────────────────┐
                │                      Target JVM                      │
                │  -agentlib:jdwp=transport=dt_socket,server=y,…       │
                │  ↳ application threads, classes, heap                │
                └──────────────────────────────────────────────────────┘
```

Two JVMs, three protocols, one synchronous request-response pipeline. Nothing in the picture is bidirectional except the JDWP socket itself — MCP is request/response from the client, and the listener-driven event flow inside the server JVM is one-directional fan-in.

## The two JVMs

| | Server JVM | Target JVM |
|---|---|---|
| Runs | the MCP-server JAR | the application being debugged |
| Reachable from | Claude Code (parent process) | JDWP socket (loopback only) |
| Holds | breakpoint registry, object cache, event history, compilation cache | the real Java objects, the real call stacks, the real `Class`es |
| Allocates | small short-lived structures (mirrors, request objects) | every bytecode the server injects + every byte array used to ship it (see [memory-and-references.md](memory-and-references.md)) |

Everything the agent reads through the server — an `order` local, a `request.getData()` result, the contents of a `HashMap` — lives in the target JVM. The server only holds *handles* (`com.sun.jdi.ObjectReference`) that identify those values across the wire. That distinction drives every memory and threading decision in the codebase.

## Components

The seven core classes do the heavy lifting. Everything else is shape: data records, formatters, package-info files.

### `JDWPMcpServerApplication`

The Spring Boot entry point (`JDWPMcpServerApplication.java:14`). One line: `SpringApplication.run(...)`. All wiring is annotation-driven. `application.properties` sets `spring.main.web-application-type=none` (no Tomcat, no Netty) and `spring.ai.mcp.server.type=SYNC` (no reactor).

### `JDWPTools`

The agent-facing surface. Each of its ≈ 46 public methods carries an `@McpTool` annotation; the Spring AI MCP framework auto-discovers them at startup and registers them with the transport. The tool methods are deliberately thin — they validate parameters, call into the services below, and format the result string. The whole MCP boundary is one class so the agent's surface stays auditable in a single read.

The class also exposes two `@McpResource` methods — `jdwp://diagnose` and `jdwp://jvms` (`JDWPTools.java:1147-1180`) — covered in [diagnostics.md](diagnostics.md).

### `JDIConnectionService`

The singleton that holds the live `com.sun.jdi.VirtualMachine`. It is the one place where JDI handles are created, the only place that owns the `vm` reference, and the gatekeeper for the object cache (`JDIConnectionService.java:81`). Tool methods that need access to the target VM go through `getVM()` (`JDIConnectionService.java:469`) which lazily auto-reconnects via `ensureConnected()` (`JDIConnectionService.java:368-376`) when the last successful target is still around.

### `BreakpointTracker`

The breakpoint registry. Six concurrent maps hold every flavour of breakpoint — line, exception, field, plus pending (deferred) variants — and the dependency graph that powers breakpoint chains. Synthetic integer IDs are minted from a single `AtomicInteger` so line, exception, and field BPs share one ID space (`BreakpointTracker.java:45`). Details in [breakpoints.md](breakpoints.md).

### `JdiEventListener`

The daemon thread that drains the JDI event queue (`JdiEventListener.java:200-212`). One thread, started per connection, joined on disconnect. Every breakpoint hit, step, exception, field access, and class-load event in the target arrives here. The listener decides — per event — whether to suspend the thread, auto-resume it, log it, evaluate a condition, promote a deferred breakpoint, or break a chain. The decision matrix is documented in [event-pipeline.md](event-pipeline.md).

### `EvaluationGuard`

A counted, per-thread reentrancy guard (`EvaluationGuard.java`). Keys are target-VM `ThreadReference.uniqueID()` values. Its sole job is to tell the event listener "the MCP server is already running code on this thread — don't suspend it again". Without this guard, an expression evaluation that re-enters a breakpointed line would deadlock the entire server. See [threading-and-safety.md](threading-and-safety.md) for the full mechanism.

### `EventHistory`

A 500-entry FIFO ring buffer (`EventHistory.java`) of every JDWP event the listener has seen — including the ones it suppressed. Backed by a `ConcurrentLinkedDeque` so the producer (listener thread) and the consumers (MCP tool calls) never block each other.

## Subsystem packages

Around the core, four packages each own a small focused concern:

- **`evaluation/`** — the compile-and-inject pipeline. Takes a Java expression string, generates a wrapper class, compiles it with Eclipse JDT, ships the bytecode to the target via `ClassLoader.defineClass`, and invokes the static `evaluate()` method on the suspended thread. Documented in [expression-evaluation.md](expression-evaluation.md).
- **`watchers/`** — MCP-side expression bookmarks. A watcher is an `(id, label, breakpointId, expression)` record that the agent can attach to a breakpoint; on a hit, the agent asks `jdwp_evaluate_watchers` and the server replays every attached expression through the evaluation pipeline. Pure server-side state — the target JVM knows nothing about watchers.
- **`marks/`** — labelled object pins. `jdwp_mark_instance` registers an `ObjectReference` under a `$label` name and calls `disableCollection()` on it so the target VM cannot GC the object out from under the next expression. Marks are exposed as `$label` bindings to every expression evaluation.
- **`discovery/`** — local-JVM enumeration. Combines `com.sun.tools.attach.VirtualMachine.list()` with a `/proc/<pid>/cmdline` scan (Linux) and an opt-in JDWP handshake probe so `jdwp_diagnose` can tell you "this PID is a Java process listening on port 5005 right now" without ever calling `VirtualMachine.attach()`. Documented in [diagnostics.md](diagnostics.md).
- **`transport/`** — a one-method override of the Spring AI stdio transport (`MultiVersionStdioServerTransportProvider.java:26-33`) that advertises every MCP protocol version the bundled SDK knows about, not just the oldest. Works around an mcp-core bug where clients requesting a newer protocol version get a downgrade response and then silent failure.

## Request flow at a glance

A typical "set a breakpoint and inspect" cycle:

```
1. Claude → jdwp_set_breakpoint("OrderService", 42)
   STDIO → MCP framework → JDWPTools.jdwp_set_breakpoint
   → JDIConnectionService.getVM() (auto-reconnect if needed)
   → BreakpointTracker.registerBreakpoint(...)
   → result string back through MCP → Claude

2. Claude → jdwp_resume_until_event(timeoutMs=60000)
   → JDWPTools arms a CountDownLatch on BreakpointTracker
   → vm.resume()
   → MCP tool thread blocks on the latch

3. Target JVM hits line 42:
   JDI event queue ← BreakpointEvent
   → JdiEventListener.handleBreakpointEvent
   → reentrancy guard check (no, not a recursive hit)
   → BreakpointTracker.setLastBreakpointThread(...)
   → EventHistory.record("BREAKPOINT", …)
   → BreakpointTracker.fireNextEvent() (releases latch)

4. The MCP tool thread wakes up, builds the event-context response,
   returns it through MCP → Claude.
```

Everything in step 3 happens on the event dispatcher thread the listener hands the event set to; everything in step 4 happens on the MCP dispatch thread. The hand-off is the latch. The full sequence is covered in [event-pipeline.md](event-pipeline.md).

## Project layout

```
mcp-jdwp-java/
├── pom.xml                    Maven reactor
├── mvnw / mvnw.cmd            Maven wrapper (pinned 3.9.x)
├── .mcp.json                  Claude Code MCP registration
├── hooks/hooks.json           SessionStart auto-build hook
├── docs/                      ← you are here
├── jdwp-mcp-server/           the real MCP server
│   ├── pom.xml                NullAway + Error Prone wired into javac
│   └── src/
│       ├── main/java/…        ~ 12,100 lines, 7 main classes + 4 packages
│       ├── main/resources/    application.properties, logback-spring.xml
│       └── test/java/…        ~ 80 test files, ~ 850 @Test methods
└── jdwp-sandbox/              deliberately broken target classes
    └── pom.xml                tests skipped by default
```

The reactor build (`./mvnw clean package -DskipTests` from the root) produces `jdwp-mcp-server/target/mcp-jdwp-java.jar`. The sandbox is independent — it is a JDWP target, not a server dependency. Detailed test conventions are in [testing.md](testing.md).
