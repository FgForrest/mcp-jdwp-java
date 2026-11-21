# Troubleshooting the JDWP MCP Server

## Start here: `jdwp_diagnose`

When something isn't working — can't connect, breakpoint won't fire, target JVM seems to be gone — run `jdwp_diagnose` first. It returns a three-block "state of the world" snapshot in one call:

1. **MCP server** — PID, uptime, configured target host/port.
2. **JDWP connection** — connected/disconnected with the last-attempt error; when connected, the breakpoint+events report follows inline.
3. **Local JVMs** — every JVM visible to your user, with its JDWP port (read from `/proc` on Linux, confirmed by handshake) and whether it's listening, suspended on startup, attached to us, or unreachable.

This answers most "what's going on?" questions without `ps`/`lsof`/`jps`. Add `inspectAll=true` only if a JVM shows no port and you suspect JDWP is on — that briefly attaches to read `sun.jdwp.listenerAddress` (visible to the target, hence opt-in).

## Tool returns an unexpected error or server seems stuck

1. Check the server log if your installation writes one — the JDWP MCP server logs JDI operations and errors there.
2. Reconnect the MCP server: run `/mcp` in Claude Code and reconnect `jdwp-inspector`. This spawns a fresh subprocess. In Codex, start a new session; Codex starts its MCP servers per session.

## JDWP port is already in use

Applies to any JDWP port — 5005 for build-system shortcuts, but also non-default ports (8003, 8000, 9009, …) used by already-running services.

Find what holds the port:

```bash
ps -ef | grep "jdwp=transport"     # all JDWP-enabled JVMs
ss -tnlp | grep <port>             # what's listening on a specific port (Linux)
lsof -i :<port>                    # same, macOS / BSD
```

Only kill a process you launched yourself in this session. If the process is unrecognized or was not started by you, **ask the user before killing it** — it may be a long-running service the user *intends* you to attach to, or another developer's debug session. When in doubt, ask "is this the JVM I should attach to?" before doing anything destructive.

## Both processes gone after a crash

The test JVM exits when its debugger detaches; the MCP server may also have crashed. Reconnect the MCP server first (`/mcp` in Claude Code, a new session in Codex), then relaunch the test JVM from scratch.

## Expression evaluation fails with "X cannot be resolved"

Symptoms: `jdwp_evaluate_expression`, `jdwp_assert_expression`, a logpoint, or a conditional breakpoint returns a JDT compile diagnostic like `X cannot be resolved to a type` / `cannot be resolved to a variable`, or `configureCompilerClasspath` throws `Classpath discovery failed for the current connection: neither the target VM classloader hierarchy nor the local project yielded any classpath entries`.

The target type is visible to the running JVM but invisible to the wrapper compile. This happens when the target's classloader hierarchy hides JARs from `getURLs()` — Tomcat's `WebappClassLoaderBase`, Spring Boot's `LaunchedClassLoader`, dev-tools `RestartClassLoader`, or any custom `URLClassLoader`. The MCP server augments the discovered classpath with entries from the local project (`target/classes`, `target/test-classes`, Maven-resolved dependencies) to plug those gaps. The project is the directory the agent session runs in: Claude Code starts the server there, and Codex, which starts the server in the plugin's own directory, reports it with every tool call. If the local sources are empty too, the compile breaks.

Work through these in order:

1. **Run `jdwp_diagnose` and read the `Local project classpath` block.** Its `CWD` line names the project directory that was scanned (marked `session directory reported by the MCP client` under Codex), whether `pom.xml` was found, and a per-source breakdown — `Sources: env-override=N, filesystem=N, maven=N`. All zeros means the fallback contributed nothing; that's the gap to fill.

2. **Set `JDWP_EXTRA_CLASSPATH` to add specific jars or class directories.** Colon-separated on Linux/macOS, semicolon-separated on Windows (parsed with `File.pathSeparator`). Restart the MCP server after setting the variable — the provider memoizes for the JDI connection's lifetime. This is the right lever when a single JAR is missing or the project is Gradle (Gradle is not auto-detected in v1).

3. **Start the agent session in a directory containing a Maven `pom.xml`.** The filesystem scan walks depth-5 looking for `target/classes` / `target/test-classes`, and `dependency:build-classpath` runs per detected module. First call on a cold Maven cache can take up to 3 minutes — that's expected, not a hang. `jdwp_diagnose` warns about the latency in the section header.

4. **Check the server log for `[LocalClasspath]` lines.** Every source emits at least one entry: `INFO` for successful contributions and timings, `WARN` for Maven non-zero exits / timeouts / malformed env values (with the captured stdout). The same prefix covers `LocalProjectClasspathProvider`, `ProcessBuilderMavenRunner`, and the union step in `JdiExpressionEvaluator`. Remote-side failures use the `[Discoverer]` / `[JDI]` prefixes — both worth grepping when eval breaks.

Remote-discovered entries win on resolution (they come first in the merged classpath), so the local fallback can only *add* types — it cannot mask a stale remote definition. If you rebuilt locally and expect the eval to pick up the new bytecode but it doesn't, the remote target is still authoritative for any class it already exposes.
