# Troubleshooting the JDWP MCP Server

## The `jdwp_*` tools are missing: the MCP server is not connected

Without the server there is no `jdwp_diagnose`. This is common right after the plugin is installed or updated: the server JAR was still being built (or, under Codex, downloaded) when the agent started the server. Find out what is wrong and tell the user what to do — you cannot reconnect the server yourself.

1. **Find the plugin directory.** It is the directory that holds this skill (`skills/java-debug/`): under Claude Code `~/.claude/plugins/cache/<marketplace>/jdwp-debugging/<version>/`, under Codex `~/.codex/plugins/cache/<marketplace>/jdwp-debugging/<version>/`, or the root of a checkout used as the plugin.
2. **Is the JAR there?** Under Claude Code (and in a checkout) `jdwp-mcp-server/target/mcp-jdwp-java.jar` in the plugin directory; under Codex `server/mcp-jdwp-java.jar` in the plugin's data directory (`~/.codex/plugins/data/…`). Next to it, `.build-sha` names the commit it was built for (`no-git` in a copy without git history; under Codex prefixed with the plugin version).
3. **Read the logs.**
   - `server-build.log` next to the JAR — the Maven build, and under Codex the release download.
   - Codex only: `logs/server-jar-setup.log` in the plugin's data directory (`~/.codex/plugins/data/…`) — what the launcher did.
   - The server's own log, `logs/mcp-jdwp-inspector.log` — in the plugin directory under Claude Code, in the data directory under Codex. A JAR that exists but does not start shows its error here.
4. **Check Java.** `java -version` must report a JDK 17 or newer; a JRE fails with `Module jdk.jdi not found`. Building needs JDK 21+.
5. **Prepare the JAR by hand** when the build or download failed or is not finished: `bash <plugin dir>/hooks/ensure-server-jar.sh` (under Codex set `JDWP_JAR_DIR=<data directory>/server` and add `--download`, as the launcher `scripts/jdwp-inspector.sh` does, so the JAR lands where the server looks for it and the release JAR is tried first). It does nothing when the JAR is current; otherwise it builds (`./mvnw`, `mvn -o`, `mvn`), writes `.build-sha`, and prints one JSON line saying what it did or why it failed. Another session's build in progress makes it wait.
6. **Ask the user to reconnect.** Claude Code: `/mcp`, select `jdwp-inspector`, reconnect — again if a build was still running; Claude Code skips a failed plugin server for 15 minutes unless it is reconnected by hand. Codex: start a new session.

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

Check the local target PID and start time in the attach or disconnect reply. A live `server=y` target listens again after detach. `jdwp_wait_for_attach(requireVmStart=true)` reports `[NOT_FRESH]` for an existing session or if a new attachment fails to receive a suspending `VM_START` event. Disconnect and confirm the previous test process has ended before relaunching. A TCP peer may be a port forwarder. For a TCP peer, `probably PID`, ambiguous match, or missing PID, verify the port owner before stopping the process.

To look up a listener from the shell, use one of these commands.

```bash
ss -ltnp 'sport = :5005'        # listening TCP port and owner on Linux
lsof -nP -iTCP:5005 -sTCP:LISTEN # listening TCP port and owner on macOS / BSD
```

Only kill a process you launched yourself in this session. If the process is unrecognized or was not started by you, **ask the user before killing it** — it may be a long-running service the user *intends* you to attach to, or another developer's debug session. When in doubt, ask "is this the JVM I should attach to?" before doing anything destructive.

## Reattaching after a crash

Reconnect the MCP server first (`/mcp` in Claude Code, a new session in Codex). Check whether the test JVM is still alive, confirm it has ended, then relaunch the test and attach with `requireVmStart=true`.

## Expression evaluation fails with "X cannot be resolved"

Symptoms: `jdwp_evaluate_expression`, `jdwp_assert_expression`, a logpoint, or a conditional breakpoint returns a JDT compile diagnostic like `X cannot be resolved to a type` / `cannot be resolved to a variable`, or `configureCompilerClasspath` throws `Classpath discovery failed for the current connection: neither the target VM classloader hierarchy nor the local project yielded any classpath entries`.

The target type is visible to the running JVM but invisible to the wrapper compile. This happens when the target's classloader hierarchy hides JARs from `getURLs()` — Tomcat's `WebappClassLoaderBase`, Spring Boot's `LaunchedClassLoader`, dev-tools `RestartClassLoader`, or any custom `URLClassLoader`. The MCP server augments the discovered classpath with entries from the local project (`target/classes`, `target/test-classes`, Maven-resolved dependencies) to plug those gaps. The project is the directory the agent session runs in: Claude Code starts the server there, and Codex, which starts the server in the plugin's own directory, reports it with every tool call. If the local sources are empty too, the compile breaks.

Work through these in order:

1. **Run `jdwp_diagnose` and read the `Local project classpath` block.** Its `CWD` line names the project directory that was scanned (marked `session directory reported by the MCP client` under Codex), whether `pom.xml` was found, and a per-source breakdown — `Sources: env-override=N, filesystem=N, maven=N`. While discovery runs, this block shows its status. After discovery, check the counts and any Maven failure.

2. **Set `JDWP_EXTRA_CLASSPATH` to add specific jars or class directories.** Colon-separated on Linux/macOS, semicolon-separated on Windows (parsed with `File.pathSeparator`). Restart the MCP server after setting the variable — the provider memoizes for the JDI connection's lifetime. This is the right lever when a single JAR is missing or the project is Gradle (Gradle is not auto-detected in v1).

3. **Start the agent session in a directory containing a Maven `pom.xml`.** The filesystem scan walks depth-5 looking for `target/classes` / `target/test-classes`, and the server runs `dependency:build-classpath` from the project root on attach. Cold Maven caches can take up to 3 minutes. Conditions and logpoints use the available remote classpath while discovery runs.

4. **Check the server log for `[LocalClasspath]` lines.** Every source emits at least one entry: `INFO` for successful contributions and timings, `WARN` for Maven non-zero exits / timeouts / malformed env values (with the captured stdout). The same prefix covers `LocalProjectClasspathProvider`, `ProcessBuilderMavenRunner`, and the union step in `JdiExpressionEvaluator`. Remote-side failures use the `[Discoverer]` / `[JDI]` prefixes — both worth grepping when eval breaks.

Remote-discovered entries win on resolution (they come first in the merged classpath), so the local fallback can only *add* types — it cannot mask a stale remote definition. If you rebuilt locally and expect the eval to pick up the new bytecode but it doesn't, the remote target is still authoritative for any class it already exposes.
