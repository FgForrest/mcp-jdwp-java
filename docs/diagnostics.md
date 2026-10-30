# Diagnostics and observability

When something is wrong — the target isn't where the agent thinks it is, a tool call dies mysteriously, the connection drops mid-evaluation — the server has three sources of truth: the `jdwp_diagnose` tool, the structured error envelopes returned by tool calls, and the log file. This chapter is how each one is produced and what they mean.

## 1. `jdwp_diagnose` — three-block report

The tool entry point is `JDWPTools.jdwp_diagnose(boolean inspectAll)` (`JDWPTools.java:1136-1145`); the report itself is built by `buildFullDiagnosticReport` (lines 1187-1245) and rendered by `DiagnoseReportRenderer`. The output has three deliberately-separated sections:

### Block 1 — MCP server

`DiagnoseReportRenderer.renderMcpServerBlock` (`discovery/DiagnoseReportRenderer.java:41-58`). Reports:

- PID and uptime.
- Java version.
- Registered tool count, computed reflectively via `countMcpTools()` (`JDWPTools.java:1290-1298`). The reflection makes the report self-correcting if a new `@McpTool` is added without updating any documentation.
- Configured target host/port (from `JVM_JDWP_PORT` / `-DJVM_JDWP_PORT`, fall back to `localhost:5005`).
- Working directory.

This block answers "what process is the agent talking to?". Useful when a session went sideways and you need to know which JAR you actually launched.

### Block 2 — JDWP connection

`DiagnoseReportRenderer.renderConnectionBlock` (lines 68-87). Two states:

- **Connected** — `✓ Connected to host:port`, followed by an optional VM-capabilities block (`canWatchFieldAccess` / `canWatchFieldModification`, `DiagnoseReportRenderer.java:99-114`, gated at `JDWPTools.java:1223-1234`). The capabilities block also carries a perf warning when field watchpoints are supported — a heads-up that watching a hot field can dominate target-VM CPU.
- **Not connected** — `⚠ Not connected`, plus the timestamp of the last attempt, the error message, and a one-line suggestion ("Launch target with `-agentlib:jdwp=...,address=*:<port>`").

When connected, an inline breakpoint-and-events sub-report is appended (`JDWPTools.java:1239-1241`, via `buildDiagnosticReport(false, null)` at line 1307+). That gives the agent the breakpoint registry state and the recent event timeline without having to fan out across multiple tool calls.

### Block 3 — Local JVMs

`renderLocalJvmsBlock` (`JDWPTools.java:1252-1283`) lists every same-user Java process the server could detect, with its JDWP port (when known) and reachability state. The MCP server's own PID is filtered out (`DiagnoseReportRenderer.java:120-127`) to avoid noisy self-entries.

The discovery sources are covered in § 2; the port-reachability classification is in § 3.

## 2. JVM discovery — Attach API + `/proc`

`JvmDiscoveryService.discover()` (`discovery/JvmDiscoveryService.java:137-144`) runs two strategies and merges by PID:

### Source A: the Attach API

> **Background — what the Attach API is**
>
> `com.sun.tools.attach.VirtualMachine` (note: **not** the JDI `VirtualMachine`!) is the Java *Attach API*, defined in the `jdk.attach` module. It is the mechanism behind tools like `jcmd`, `jstack`, `jmap`, and IntelliJ's "Attach to Process". It does two things: enumerates same-user JVMs (`VirtualMachine.list()`) and lets the caller attach to one to load a JVMTI agent or read system properties.
>
> Internally it uses platform-specific channels — on Linux, a Unix domain socket in `/tmp/.java_pid<PID>` that the target JVM creates lazily on the first attach attempt; on Windows, a named pipe. The API is same-user only by design: only a process running as the same OS user can connect to that socket / pipe.
>
> The Attach API is independent of JDWP. A JVM with `-agentlib:jdwp` set and a JVM without it are both visible to `VirtualMachine.list()`; the API tells you *what* JVMs are running, not *which* ones expose debugging. To learn the JDWP endpoint, the inspector has to either parse the argv (the `/proc` path) or attach via the Attach API and read the `sun.jdwp.listenerAddress` agent property (the `inspectAll=true` path).

`viaAttachApi()` (`JvmDiscoveryService.java:151-182`) wraps `com.sun.tools.attach.VirtualMachine.list()` (`listViaAttachApi` at lines 734-757). Cross-platform, same-user only, returns PID + display name. **Does not reveal a JDWP port** — the Attach API is for hot-attach of management agents, not debug-agent introspection. Soft 100 ms budget (constant at line 56).

### Source B: `/proc` scan (Linux only)

`viaProcFs()` (`JvmDiscoveryService.java:190-220`), gated by `isLinux()` (lines 759-761). For each `/proc/<pid>/cmdline`:

1. Read and split on NUL bytes (`readProcEntry` at lines 228-274, `splitNulSeparated` at lines 280-295).
2. Heuristically classify as Java (`looksLikeJava` at lines 302-315).
3. Extract `-agentlib:jdwp=...` or `-Xrunjdwp:...` arguments via `JdwpAgentArgParser.parse` (`discovery/JdwpAgentArgParser.java:32-87`).
4. Resolve `JAVA_HOME` via the `/proc/<pid>/exe` symlink (`JvmDiscoveryService.java:385-398`).
5. Mask sensitive argv tokens — keys named `password`, `secret`, `token`, `apikey`, `api_key` (`JvmDiscoveryService.java:80-82, 410-412`).

Soft 200 ms budget (line 57). The masking matters because `jdwp_diagnose` output is text the agent will quote back; passwords in command lines are an attack vector we explicitly defend against.

### Merge rules

`mergeByPid` (`JvmDiscoveryService.java:705-728`): Attach-API rows are added first; `/proc` rows win on conflict (they carry the JDWP endpoint). A PID seen by both gets `Source.BOTH`.

### `inspectAll=true` — opt-in attach probe

`inspectAll()` (lines 424-445) is an enrichment pass: for any PID whose cmdline did not reveal a JDWP port, briefly attach via `com.sun.tools.attach.VirtualMachine.attach(pid)`, read the `sun.jdwp.listenerAddress` agent property (`inspectViaAttach` at lines 454-483), and detach. The attach is **visible to the target** (it shows up in management-tool listings and can run agent code), hence opt-in. Default `inspectAll=false`.

The MCP server never attaches to itself — own-PID is filtered out (line 430).

## 3. Handshake probe — `confirmAll`

> **Background — the JDWP handshake on the wire**
>
> Every JDWP session, regardless of transport, starts with a 14-byte handshake. The client (debugger) writes the ASCII string `"JDWP-Handshake"` — exactly 14 bytes, no terminator, no length prefix — and waits for the server (target JVM) to write the same 14 bytes back. After that, both sides switch to the JDWP binary packet format: 11-byte header (length, request ID, flags, command-set/command bytes or error code) plus a variable-length payload.
>
> The handshake is documented as part of the JDWP specification. It is what lets a debugger confirm "yes, this socket really is a JDWP server" before issuing any commands. A wrong port — say, an HTTP server — accepts the connection, ignores the 14 bytes, and either replies with HTTP garbage or eventually times out. Real JDWP servers reply within microseconds.
>
> Because the handshake is just a brief echo, sending it and immediately closing is the cheapest way to ask "is JDWP listening here?". The target's JDWP agent sees a 14-byte read, writes a 14-byte response, then sees the socket close before any command arrives. From the target's perspective, a debugger started to connect and bailed; no breakpoints touched, no events fired, no permanent state changed.

`confirmAll()` (`JvmDiscoveryService.java:510-549`) classifies known JDWP endpoints by running a **non-intrusive** handshake probe.

The protocol: open a TCP socket to `host:port`, send the 14 ASCII bytes `"JDWP-Handshake"`, read back 14 bytes, byte-compare. That is the full JDWP handshake. The target sends nothing else and is not informed that a "real" debugger was about to connect — from its perspective, a malformed connection appeared and immediately closed.

The probe code lives at `JvmDiscoveryService.probeHandshake(host, port, timeoutMs)` (lines 593-629). Properties:

- Pool of 4 daemon threads.
- Per-probe budget: 100 ms (`HANDSHAKE_PROBE_BUDGET_MS`, line 59).
- Total wall budget: 500 ms (`HANDSHAKE_TOTAL_BUDGET_MS`, line 61).
- Shared deadline drives both `connect` timeout and `setSoTimeout`.
- Returns `false` on any `IOException`, never throws.
- **Off-host endpoints are never probed.** Allow-list at `isLocalHost` (lines 666-672) — `*`, `localhost`, `127.0.0.1`, `::1`, `0.0.0.0` only. Wildcards are dialled as `127.0.0.1` (`resolveProbeHost`, lines 675-680). We do not connect to arbitrary off-host targets even if they appear in argv.
- The endpoint we're already attached to is recognised via `matchesConnectedTarget` (lines 652-664) and marked `CONNECTED_TO_US` rather than re-dialled (which would steal a port slot from our own connection).

### States

`JdwpEndpoint.State` (`discovery/JdwpEndpoint.java:29-47`):

- `CONNECTED_TO_US` — this is the port we're attached to right now.
- `LISTENING` — handshake succeeded, no current debugger.
- `SUSPENDED` — handshake succeeded but `suspend=y` was set in argv (the JVM is waiting for a debugger).
- `UNREACHABLE` — socket refused or timed out.
- `UNKNOWN` — endpoint not probed (off-host, or budget exhausted).

The classification matters because `LISTENING` and `SUSPENDED` both mean "you can attach right now", whereas `UNREACHABLE` usually means "the agent is up but the port is misconfigured" — different remediations.

## 4. Logging configuration

`jdwp-mcp-server/src/main/resources/logback-spring.xml`:

- Root logger at `INFO`.
- **STDERR appender** — `System.err` only (lines 4-9). Never stdout, since stdout carries MCP JSON-RPC frames.
- **FILE appender** — `${LOG_PATH:-${java.io.tmpdir}/mcp-jdwp-inspector.log}` (line 13). Plain `FileAppender`, `append=true`.

**No rotation, no size cap, no time-based roll.** Long-running sessions grow the log file unbounded. For automation, plan an external `logrotate` or a launcher that truncates between runs.

The `.mcp.json` shipped with the plugin (`/.mcp.json:7`) sets `-DLOG_PATH=${CLAUDE_PLUGIN_ROOT}/logs/mcp-jdwp-inspector.log`. Rationale: when launched as a Claude Code plugin, the log should live next to the plugin install rather than in the user's working directory. When the JAR is launched directly without that flag, the log falls back to `${java.io.tmpdir}`. Documented inline at `application.properties:12-18`.

### A stale logger override

`logback-spring.xml:27-30` defines an `io.mcp.jdwp` logger at level `DEBUG` with `additivity=false`. **The actual code package is `one.edee.mcp.jdwp`** — the override does not currently match anything. It looks like dead config from an earlier rename. Worth fixing if you want a DEBUG override to take effect; the global `INFO` root is still in force for everything else.

## 5. Transport-loss envelopes — what the agent sees when JDWP dies

A long-running MCP tool call (`jdwp_evaluate_expression`, `jdwp_to_string`, `jdwp_resume_until_event`) can be interrupted by the target JVM disappearing — crash, OS kill, network blip on a non-loopback target. Two canonical envelope strings handle the two flavours of "VM gone":

### `[VM_DEATH]`

```
[VM_DEATH] target VM disconnected during <tool> —
re-attach via jdwp_connect / jdwp_wait_for_attach.
```

Produced by `vmDisconnectedMessage` (`JDWPTools.java:654-657`). Returned by tools that were mid-call when the JDI subsystem signalled disconnect:

- `jdwp_to_string` (`JDWPTools.java:728-730`)
- `jdwp_evaluate_expression` (lines 771-772)
- `jdwp_assert_expression` (lines 820-821)
- `jdwp_evaluate_watchers` (lines 3634-3635)

### `[VM_GONE]`

```
[VM_GONE] <tool>: target VM is no longer reachable (<reason>).
Run jdwp_diagnose to inspect, or jdwp_connect / jdwp_wait_for_attach to re-attach.
```

Produced by `vmGoneEnvelope`. Returned by `jdwp_resume_until_event` when the VM was disposed underneath it or a transport failure surfaces while it waits for the next stop (`SuspensionSession.nextStop`). The envelope walks the cause chain to surface the deepest transport-level message — "Connection refused" rather than the outer JDI wrapper.

### Detection

The classifier is centralised:

- `isVmGone` (`JDWPTools.java:1086-1099`) — walks the cause chain bounded by `MAX_CAUSE_DEPTH = 8` (line 1039), with self-cycle guards, checking class types **and** message substrings.
- `isTransportFailureFrame` (lines 1105-1117) — per-frame check.
- `VM_GONE_MESSAGE_FRAGMENTS` (lines 1021-1030) — the substring list: `"Connection refused"`, `"Connection reset"`, `"Broken pipe"`, `"Pipe closed"`, `"handshake failed"`, `"Bad file descriptor"`, and others.

The classes checked are `VMDisconnectedException`, `SocketException`, `EOFException`. The combination of class + substring matching catches the various ways JDI implementations report a dead socket — different runtimes wrap the underlying `IOException` differently.

### Why two envelopes?

`[VM_DEATH]` is for "the call was running, JDI told us the VM disappeared". The envelope is short because the agent is mid-task and just needs a "stop and re-attach" hint.

`[VM_GONE]` is for "we were waiting for an event, and detected the VM is gone via the watchdog". This path has more context to provide (the underlying reason from the cause chain) and points the agent at `jdwp_diagnose` for a fuller picture.

### `EventKind` is *not* a transport-loss code

`BreakpointTracker.EventKind` is the four-value enum `BREAKPOINT | STEP | EXCEPTION | VM_START` attached to `LastBreakpoint` snapshots (`VM_START` marks the stop of a VM suspended at start). It is the classifier that lets event rendering describe *why* a thread is parked when transport is fine — see [event-pipeline.md](event-pipeline.md) § 10. Not to be confused with the transport-loss envelopes above.

## 6. MCP timeouts — `MCP_TIMEOUT` and `MCP_TOOL_TIMEOUT`

Both are **client-side** Claude Code timeouts, set in `.mcp.json:11-12` (`30000` and `120000` ms respectively). They do not appear in the Java code — `grep` returns zero hits across the server source. The reasoning lives at `README.md:79`:

- `MCP_TIMEOUT=30000` — Spring Boot context init plus JDI module load takes several seconds. The default Claude Code stdio timeout is too short for a Spring Boot Java process; without this, Claude Code gives up before the server is ready.
- `MCP_TOOL_TIMEOUT=120000` — first-time `jdwp_evaluate_expression` walks the target classloader hierarchy, calls `JdkDiscoveryService`, and compiles a wrapper class with Eclipse JDT. That first call can run for many seconds; subsequent calls hit the compilation cache and are fast.

If you fork the project, do not lower these without testing on a cold cache against a non-trivial target (Tomcat, Spring Boot, etc.).

## 7. The MCP resources — `jdwp://diagnose` and `jdwp://jvms`

Two `@McpResource`-annotated methods on `JDWPTools` expose read-only resources:

- `jdwp://diagnose` — `diagnoseResource()` (`JDWPTools.java:1147-1159`). Same content as the tool with `inspectAll=false`.
- `jdwp://jvms` — `jvmsResource()` (lines 1161-1180). Cheaper subset — only the local-JVMs block, useful for "what can I attach to right now?" without spending a model turn on the full report.

The annotation is `org.springframework.ai.mcp.annotation.McpResource` (`JDWPTools.java:17`). Resources are discovered automatically by Spring AI via the same `spring.ai.mcp.server.enabled=true` flag that picks up `@McpTool`. Resource updates are **not pushed** to the client — re-attach the URI to see fresh content.

## 8. `.mcp.json` and the SessionStart hook

Two adjacent files at the repo root configure the plugin lifecycle.

### `.mcp.json`

17 lines. Registers the `jdwp-inspector` MCP server with a `java --add-modules jdk.jdi,jdk.attach -jar …` command. Sets `-DLOG_PATH` and the two timeout env vars. The `${CLAUDE_PLUGIN_ROOT}` expansion is what makes the plugin relocatable — log path, JAR path, and any other paths resolve to the plugin install directory regardless of where Claude Code is launched.

### `hooks/hooks.json`

15 lines, one hook: a `SessionStart` shell command that **rebuilds the JAR on session start when needed**. The command:

1. Resolves `$JAR = $PLUGIN/jdwp-mcp-server/target/mcp-jdwp-java.jar` and `$MARKER = $PLUGIN/jdwp-mcp-server/target/.build-sha`.
2. Computes `EXPECTED = git rev-parse HEAD` (falls back to `no-git` if not in a checkout).
3. Computes `ACTUAL = cat $MARKER`.
4. If the JAR is missing or `EXPECTED != ACTUAL`, rebuilds via `$PLUGIN/mvnw -pl jdwp-mcp-server -am package -DskipTests -q` and writes `EXPECTED` to `.build-sha`.
5. Otherwise, no-op.

Net effect: every session launches against a JAR built from the current commit, with a fast-path when nothing has changed. The hook depends on `JAVA_HOME` pointing at a JDK 17+, but does not require Maven to be installed — `./mvnw` is the bundled wrapper.

## 9. The diagnostic mental model

When something is wrong, in order:

1. **`jdwp_diagnose`** — confirms the server is alive, lists what it can see. If the server itself is broken, the report won't render and you go to step 4.
2. **`[VM_DEATH]` or `[VM_GONE]` in a recent tool response** — the JDI side has signalled the target is dead. Re-attach.
3. **`jdwp_get_events`** — recent events including suppressed and `BP_PROMOTION_FAILED`. Tells you what fired and what was silently re-resumed.
4. **The log file** — at `$LOG_PATH` or `${java.io.tmpdir}/mcp-jdwp-inspector.log`. Last resort, has the unredacted stack trace.

The first three are agent-driven; the fourth needs human eyes. The principle: every observable failure mode should have an answer somewhere in the first three.
