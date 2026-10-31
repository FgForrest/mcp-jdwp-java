# Lifecycle: bootstrap, connect, disconnect, reset

This chapter covers what happens between launching the JAR and shutting it down — including every code path that creates, recreates, or discards server-side state. If you ever wonder "did `jdwp_reset` actually clear that?" or "why does my session resume after the target restarts?", the answer is somewhere in this chapter.

## 1. Server bootstrap

The MCP server is a Spring Boot application with a one-line `main` (`JDWPMcpServerApplication.java:14`):

```java
SpringApplication.run(JDWPMcpServerApplication.class, args);
```

`application.properties` keeps the surface deliberately small:

- `spring.main.web-application-type=none` — no Tomcat, no Netty. The JVM holds the Spring context, the MCP transport, and a handful of `@Service` beans. That is it.
- `spring.main.banner-mode=off` — the banner would pollute stdout, which carries MCP frames.
- `spring.ai.mcp.server.type=SYNC` — see [threading-and-safety.md](threading-and-safety.md). Tool calls run on the STDIO reader thread, one at a time.
- `spring.ai.mcp.server.enabled=true` — auto-discovers `@McpTool` methods on `JDWPTools` and the `@McpResource` methods alongside them.

### The custom STDIO transport

Spring AI ships a `StdioServerTransportProvider` bean, but we replace it. `StdioTransportConfig.stdioServerTransport(...)` (`transport/StdioTransportConfig.java:18-22`) is marked `@ConditionalOnMissingBean` on the Spring AI side, so providing our own override suppresses theirs cleanly.

```java
@Bean
public StdioServerTransportProvider stdioServerTransport(McpJsonMapper jsonMapper) {
    return new MultiVersionStdioServerTransportProvider(jsonMapper);
}
```

The override exists because the upstream `StdioServerTransportProvider` in mcp-core 1.1.0 through 2.0.0-M2 hardcodes `List.of("2024-11-05")` as its advertised protocol versions. A newer client (Claude Code 2.1.143 requests `2025-11-25`) gets a silent downgrade, then the session stops responding with `-32000 Failed to reconnect`. Our subclass overrides `protocolVersions()` to advertise all four versions the bundled SDK knows about (`transport/MultiVersionStdioServerTransportProvider.java:26-33`):

```java
List.of(
    ProtocolVersions.MCP_2025_11_25,
    ProtocolVersions.MCP_2025_06_18,
    ProtocolVersions.MCP_2025_03_26,
    ProtocolVersions.MCP_2024_11_05
);
```

The two STDIO streams are owned by the framework; we never read or write `System.in` / `System.out` ourselves. Logs are explicitly redirected to `System.err` and to a file — see [diagnostics.md](diagnostics.md).

### What is *not* started at boot

The Spring context boots without any JDI activity. No `VirtualMachine`, no event listener thread, no JDWP socket. The server is dormant until the first tool call that needs the target — typically `jdwp_connect` or `jdwp_wait_for_attach`. This matters: Claude Code can start the MCP server (and pay its ~3-second cold-start cost) at session start, and the target JVM does not need to exist yet.

> **Background — how the target JVM exposes JDWP in the first place**
>
> The JVM accepts a `-agentlib:jdwp=` command-line option that loads the bundled JDWP agent (a shared library shipped with the JDK). The agent parses its options and either listens on a socket (`server=y`) or attaches outbound to a debugger (`server=n`). The full option string the README documents — `transport=dt_socket,server=y,suspend=n,address=*:5005` — tells the agent to use TCP, listen, not suspend on start, and bind to all interfaces on port 5005. The agent is what implements the JDWP server side; the JVM itself does not know JDWP, it provides a JVMTI (Java Virtual Machine Tool Interface) surface that the agent uses to install breakpoints, read frames, etc. Anything the debugger can do is something the JVMTI can do.
>
> The `suspend=y` variant pauses every thread at VMStart before the application's `main` runs. This is what you want when you need to set a breakpoint before the first interesting line executes; combined with `jdwp_wait_for_attach`, it is how the test-flight scenarios stay reproducible.

## 2. Attaching to the target — `jdwp_connect` and `jdwp_wait_for_attach`

`JDIConnectionService.connect(host, port)` (`JDIConnectionService.java:263-319`) does the JDI attach. The sequence:

1. Record `lastConnectAttempt` so `jdwp_diagnose` can report the most recent attempt.
2. Short-circuit if already attached to the same host:port (`JDIConnectionService.java:267-271`).
3. Otherwise tear down any stale session via `cleanupSessionState()` (lines 276 and 281).
4. Look up the `com.sun.jdi.SocketAttach` connector by name from `Bootstrap.virtualMachineManager().attachingConnectors()` (lines 284-292).
5. Set `hostname` / `port` arguments and call `connector.attach(args)` (lines 299-304).
6. On success: store the `VirtualMachine`, record `lastHost` / `lastPort`, spawn `JdiEventListener` via `eventListener.start(vm)` (line 316).

The connector lookup is deliberate: the JDI implementation is plugged in via `ServiceLoader`, and on some runtimes the `dt_socket` connector is named differently. Selecting by exact name (`"com.sun.jdi.SocketAttach"`) fails fast if the runtime is missing JDI entirely (Java without `--add-modules jdk.jdi`), instead of silently picking the wrong connector.

> **Background — what `attach()` does under the hood**
>
> `SocketAttachingConnector.attach(args)` opens a TCP socket to the host/port, then runs the JDWP handshake: write the 14 ASCII bytes `"JDWP-Handshake"`, expect the same 14 bytes back. After the handshake, JDI issues a few opening JDWP commands to learn the target's vendor / version / capabilities (whether it supports field watchpoints, source filename queries, etc. — `VirtualMachine.canWatchFieldModification()` and friends each map to a `capabilities` reply byte). The returned `VirtualMachine` object is a JDI-side facade; from this point on, every method call on it serialises a JDWP command and waits for the reply on the same socket.
>
> A handful of JDWP commands are run eagerly during the attach — for instance, listing already-loaded classes. That is one reason `connect()` is slower than a raw TCP socket open: even on a localhost target with no application work to do, the attach completes after several round-trips.

### Failure handling — no silent retry

If `connector.attach(args)` throws, `lastConnectError` is recorded **but `lastHost` / `lastPort` are not** (`JDIConnectionService.java:303-311`). That asymmetry is the safety mechanism: `ensureConnected()` will only auto-reconnect to a target that previously succeeded. A typo-ed host or a never-running port stays "not connected" rather than getting retried every tool call.

### Auto-reconnect on the next tool call

`getVM()` (`JDIConnectionService.java:469`) is what every tool method calls when it needs the live VM. Internally it invokes `ensureConnected()` (`JDIConnectionService.java:368-376`), which:

1. Probes `isVMAlive()` — calls `vm.name()` in a try/catch (`JDIConnectionService.java:240-251`). A live VM returns its name; a dead one throws `VMDisconnectedException`.
2. If alive: return.
3. If dead but `lastHost` / `lastPort` are set: re-`connect(lastHost, lastPort)`. Exactly one attempt.
4. If dead and no `lastHost`: throw "Use jdwp_connect first."

The "exactly one attempt" behaviour matters: a `getVM()` call cannot spiral into a retry loop. If reconnect fails, the tool returns the error and the agent decides whether to call `jdwp_wait_for_attach` again.

### `jdwp_wait_for_attach`

Polls `connect()` every 200 ms up to a timeout (default 30 s) — `JDWPTools.java:296-359`. `IllegalConnectorArgumentsException` fails fast (a bad host or port — no point retrying). `IOException` and other exceptions are retried (the target is simply not listening yet). On timeout, the tool runs JVM discovery (see [diagnostics.md](diagnostics.md)) so the agent can see "what JVMs did come up but on a different port".

On success — as after `jdwp_connect` — the tool waits, outside the connection service's monitor, up to 500 ms for the VMStart event (`SuspensionSession.acknowledgeStart`). A VM suspended at start has that stop marked as seen, and the reply says it is suspended at `VM_START`; a VM started with `suspend=n` is reported as running. Either way the hint is the same: set breakpoints, then `jdwp_resume_until_event`, whose first call releases the start stop.

## 3. The event listener — started here, stopped on disconnect

`JdiEventListener.start(vm)` spawns the daemon thread that drains the JDI event queue, plus the `jdi-event-dispatcher` thread that processes the drained event sets. The listener thread is named `jdi-event-listener` and is created fresh on every `connect()` — `start()` calls `stop()` first so two listeners never overlap. `start()` also opens the connection's `SuspensionSession` (which owns every suspension the debugger causes), binds it to the `EvaluationGuard`, and starts its `jdi-suspension-watchdog`.

`stop()`:

1. Closes the `SuspensionSession`. Every later call on it is a no-op, and a tool blocked in `jdwp_resume_until_event` (`SuspensionSession.nextStop`) wakes and answers `[VM_DEATH]`. The session resumes nothing — `vm.dispose()` releases the target's threads.
2. Stops the watchdog and shuts the dispatcher down.
3. Interrupts the listener thread and joins it for up to 500 ms.
4. Runs the VM-death hook (at most once per session).

The full event-routing contract is in [event-pipeline.md](event-pipeline.md).

## 4. Disconnecting — `disconnect()` vs `notifyVmDied()`

There are two cleanup paths, and the difference matters.

### User-initiated: `disconnect()`

`JDIConnectionService.disconnect()` (`JDIConnectionService.java:383-389`) is called when the agent explicitly asks to detach (`jdwp_disconnect`). It delegates to `cleanupSessionState()` (`JDIConnectionService.java:428-461`):

1. `eventListener.stop()` — which also closes the suspension session (§ 3).
2. `breakpointTracker.clearAll(vm.eventRequestManager())` deletes live JDI requests, falling back to in-memory `reset()` if the VM is already dead (lines 434-440).
3. Clears `watcherManager`, `markedInstances`, `objectCache`, classpath cache, `discoveredJdkPath`, `targetMajorVersion`, `eventHistory` (lines 442-448).
4. Calls `vm.dispose()` for a clean JDWP teardown (lines 451-456). Best-effort: any exception is swallowed because the VM may already be gone.
5. **Resets `lastHost` / `lastPort` to 0 / null** (lines 459-460).

That last step is the key distinction: after `disconnect()`, the next tool call **cannot** auto-reconnect, because there is no remembered target. The agent has to ask explicitly.

### VM-death: `notifyVmDied()`

The listener wires up a death hook in the connection-service constructor (`JDIConnectionService.java:143`):

```java
eventListener.setVmDeathHook(this::notifyVmDied);
```

The hook fires when the listener loop observes a `VMDeathEvent` or `VMDisconnectEvent`, catches `VMDisconnectedException` / `IllegalStateException` from the queue, or is interrupted (`JdiEventListener.java:309-339`). Gated by a single-shot CAS (`vmDeathHookInvoked`, `JdiEventListener.java:87,218-231`) so a noisy shutdown does not invoke the hook twice.

`notifyVmDied()` (`JDIConnectionService.java:399-416`) is the post-mortem path. Unlike `disconnect()`:

- It **preserves `lastHost` / `lastPort`** — so the next tool call's `ensureConnected()` can auto-reconnect after the target restarts.
- It **preserves `eventHistory`** — so the `VM_DEATH` entry stays queryable for diagnostics.
- What the exit dropped — breakpoints, field breakpoints with their hit counts, in-target probes, watchers — was snapshotted just before, by the listener's exiting hook (`captureExit` → `lastExit`), so `[VM_DEATH]`, `jdwp_reset` and `jdwp_disconnect` say that the target exited and what went with it, instead of reporting an empty session. The next attach clears it.

Everything else (object cache, watchers, marks, classpath cache, target version) is cleared. The model is "the VM is gone but the conversation is not over" — keep just enough state to recover gracefully, drop everything that is tied to a specific target-VM lifetime.

## 5. `jdwp_reset` — clear without disconnecting

`jdwp_reset` (in `JDWPTools.java`, routed to `BreakpointTracker.reset()` and a handful of cache clears) is the agent's "start fresh while keeping the connection" button. It clears:

- Every active and pending breakpoint
- The synthetic ID counter
- The chain-dependency maps and `triggersFiredAtLeastOnce` memory
- All watchers
- The object cache
- The event history

It also releases every held stop — seen or not — and every `jdwp_suspend_thread` hold, deletes pending step requests, and starts a new suspension generation, so an event set still being processed can no longer publish a stop.

It does **not** clear marks (those are persistent labels the agent set deliberately) and does **not** touch `lastHost` / `lastPort` (the connection stays live).

## 6. The clearing matrix

| State | `disconnect()` | `notifyVmDied()` | `jdwp_reset` | `connect()` same target | `connect()` different target |
|---|---|---|---|---|---|
| `vm` reference + `vm.dispose()` | yes | yes | no | no-op short-circuit | yes (then re-attach) |
| Event listener thread | stopped | stops itself | no | no | stopped, restarted |
| Suspension session (held stops, manual holds) | closed; `vm.dispose()` releases the threads | closed | stops and holds released, new generation | no | closed, new session |
| Active JDI breakpoint requests | yes | reset in-memory | yes | no | yes |
| Pending BPs, ID counter, dependency graph | yes | yes | yes | no | yes |
| Watchers | yes | yes | yes | no | yes |
| Object cache | yes | yes | yes | no | yes |
| Marked instances | yes | yes | **no** | no | yes |
| Classpath / JDK / target-version caches | yes | yes | no | no | yes |
| Event history | yes | **preserved** | yes | no | yes |
| `lastHost` / `lastPort` (auto-reconnect seed) | cleared | **preserved** | no | unchanged | replaced |
| `lastConnectError` | n/a | n/a | n/a | cleared on success | cleared on success |

(All entries reference `JDIConnectionService.java:428-461` for `cleanupSessionState`, `JDIConnectionService.java:399-416` for `notifyVmDied`, `BreakpointTracker.java:336-358` for `clearAllInMemoryStateLocked` — which is what the reset paths call internally.)

The pattern: **user-initiated tear-downs are clean slates; VM-death tear-downs preserve just enough to recover.**

## 7. Re-attach scenarios

Several realistic flows fall out of the matrix above:

- **Target JVM restarts during a session.** The listener catches `VMDisconnectedException`, runs the death hook, and the connection service clears caches but keeps `lastHost`/`lastPort`. The next tool call's `getVM()` runs `ensureConnected()` and reconnects automatically. Breakpoints from before the restart are gone (they lived in the dead VM); the agent re-sets them.
- **Agent calls `jdwp_disconnect` then `jdwp_connect host port`.** Same as a fresh attach — caches were fully cleared in step 1, then `connect()` rebuilds everything.
- **Agent re-runs `jdwp_connect host port` while already attached to the same target.** No-op short-circuit at `JDIConnectionService.java:267-271`. The session continues unchanged. (Use `jdwp_reset` if the intent is "clear state without dropping the connection".)
- **Agent runs `jdwp_connect host2 port2` while attached to `host1 port1`.** The "different target" column. `cleanupSessionState` runs (clears everything, including marks), then the attach to the new target proceeds.

## 8. Where logs go

Logback is configured in `jdwp-mcp-server/src/main/resources/logback-spring.xml`. Two appenders, root level `INFO`:

- **STDERR** — `System.err` only (lines 4-9). Never stdout, which carries MCP JSON.
- **FILE** — path is `${LOG_PATH:-${java.io.tmpdir}/mcp-jdwp-inspector.log}` (line 13). Plain `FileAppender`, `append=true`, **no rotation**.

The plugin's MCP server entry (`mcpServers` in `.claude-plugin/plugin.json`) sets `-DLOG_PATH=${CLAUDE_PLUGIN_ROOT}/logs/mcp-jdwp-inspector.log` so the log file lives next to the plugin install rather than in the user's working directory. When running the JAR directly without that flag, the log falls back to `${java.io.tmpdir}`. The rationale is documented inline in `application.properties:12-18`.

The lack of rotation is intentional — sessions are typically short-lived and bounded by the agent's lifetime. For long-running automation, plan to truncate the file externally or wrap the `java` command in a launcher that handles rotation.
