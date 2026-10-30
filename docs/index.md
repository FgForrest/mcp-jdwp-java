# Technical documentation

This folder is the developer reference for the **MCP JDWP Inspector** — the Spring Boot MCP server that gives AI agents debugger-grade access to a running JVM over JDWP.

The user-facing material (install, quick-start, the test-flight game, the MCP tool catalogue) lives in the top-level [`README.md`](../README.md). What follows here is aimed at a different reader: a **Java developer who wants to understand the internals** — how the plugin keeps the target JVM safe, where memory is allocated and reclaimed, how the server bootstraps, how state is cleared, how the threads cooperate, how it is tested.

If you are extending the plugin, hunting a bug in it, or trying to convince yourself it is safe to point at a production process — start here.

## How this documentation is organised

The chapters go from the outside in, then sideways:

1. **[architecture.md](architecture.md)** — system overview. Two JVMs, MCP over STDIO, JDI over a JDWP socket, the ten main components and how they collaborate. Read this first.
2. **[lifecycle.md](lifecycle.md)** — bootstrapping and cleanup. Spring start, MCP transport, JDI attach, `disconnect`, `jdwp_reset`, VM-death detection, what gets cleared and when.
3. **[threading-and-safety.md](threading-and-safety.md)** — the threading model. Every thread in the server JVM, the SYNC MCP dispatch model, the JDI event listener daemon, `INVOKE_SINGLE_THREADED`, the `EvaluationGuard` leases that keep recursive breakpoints from deadlocking, the bounded wait for tool invocations, frame tools during an evaluation, and the lock order.
4. **[memory-and-references.md](memory-and-references.md)** — memory and JDI object references. The object cache, marks and pinning, the compilation cache, byte-array mirroring costs, smart collection rendering, what leaks (and why it is bounded).
5. **[event-pipeline.md](event-pipeline.md)** — the JDI event loop. How events route, when threads suspend versus auto-resume, the event history ring buffer, the suspension decision matrix, and suspension ownership: who may release each suspension, and the invocation watchdog.
6. **[breakpoints.md](breakpoints.md)** — the breakpoint registry. Synthetic IDs, deferred breakpoints, conditions, logpoint dispatch, breakpoint chains, the `ClassPrepareRequest` promotion path.
7. **[expression-evaluation.md](expression-evaluation.md)** — the compile-and-inject pipeline. Wrapper generation, ECJ compilation, `defineClass` into the target classloader, synthetic bindings. Cross-references chapters 3–5 for the safety story.
8. **[diagnostics.md](diagnostics.md)** — diagnostics and observability. `jdwp_diagnose`, the JVM-discovery sources, the JDWP handshake probe, logging configuration, transport-loss envelopes.
9. **[testing.md](testing.md)** — test architecture for contributors. Unit vs. mocked-JDI vs. in-process integration tests, the role of the sandbox module, NullAway enforcement.

## Reading paths by question

| You want to know… | Start here |
|---|---|
| How does the plugin avoid deadlocking the target JVM? | [threading-and-safety.md](threading-and-safety.md) → `EvaluationGuard` |
| Where does memory live, on which side of the wire, and when is it freed? | [memory-and-references.md](memory-and-references.md) |
| What happens between starting the JAR and the first `jdwp_connect`? | [lifecycle.md](lifecycle.md) → bootstrapping |
| Why is `jdwp_reset` different from `jdwp_disconnect`? | [lifecycle.md](lifecycle.md) → the clearing matrix |
| How does a deferred breakpoint actually become active? | [breakpoints.md](breakpoints.md) → deferred and `ClassPrepareRequest` |
| What does an MCP tool see when the target JVM dies mid-call? | [diagnostics.md](diagnostics.md) → transport-loss envelopes |
| Are breakpoint hits delivered in FIFO order? Can I trust the event history? | [event-pipeline.md](event-pipeline.md) |
| Which tool may resume a suspended thread, and why did `jdwp_resume` leave one parked? | [event-pipeline.md](event-pipeline.md) → § 14 suspension ownership |
| Why is my expression cache evicting all at once? | [memory-and-references.md](memory-and-references.md) → compilation cache |
| What is mocked in the tests and what isn't? | [testing.md](testing.md) |
| How do I add a new MCP tool / a new breakpoint kind? | [architecture.md](architecture.md) + the package layout sections in the relevant chapter |

## Conventions used in this documentation

- **File:line citations** point at the code that owns a behaviour: `JdiEventListener.java:377-388`. Paths are relative to `jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/` unless otherwise noted.
- **"Server JVM"** is the JVM running the MCP server JAR. **"Target JVM"** is the application being debugged. JDI `ObjectReference`, `ThreadReference`, etc. are mirror handles that live in the server JVM and identify objects in the target JVM over the JDWP wire.
- **JDI** = Java Debug Interface (`jdk.jdi`), the in-JVM API. **JDWP** = Java Debug Wire Protocol, the on-the-wire framing. The server is a JDI client; the target speaks JDWP.
- **MCP** = Model Context Protocol, the JSON-RPC dialect Claude Code speaks. The server runs in `SYNC` mode over STDIO — see [threading-and-safety.md](threading-and-safety.md).

## Where things live in the tree

```
jdwp-mcp-server/src/main/java/one/edee/mcp/jdwp/
├── JDWPMcpServerApplication.java   ← Spring Boot entry point
├── JDWPTools.java                  ← @McpTool methods, the agent-facing surface
├── JDIConnectionService.java       ← the singleton VirtualMachine + object cache
├── JdiEventListener.java           ← daemon thread, JDI event queue consumer
├── BreakpointTracker.java          ← breakpoint registry, deferred, chains
├── EvaluationGuard.java            ← invocation leases (reentrancy guard)
├── SuspensionSession.java          ← who owns each suspension of a connection
├── SuspensionWatchdog.java         ← frees a stuck dispatcher evaluation
├── InvocationRunner.java           ← bounded wait for a tool's invocation
├── EventHistory.java               ← 500-entry ring buffer
├── ThreadFormatting.java           ← noise-filtering helpers
├── evaluation/                     ← compile-and-inject expression pipeline
├── watchers/                       ← MCP-side expression bookmarks
├── marks/                          ← named object pins ($label bindings)
├── discovery/                      ← local-JVM enumeration + handshake probe
└── transport/                      ← custom STDIO transport (version override)
```

The package layout maps roughly one-to-one to chapters 3–9 of this documentation.
