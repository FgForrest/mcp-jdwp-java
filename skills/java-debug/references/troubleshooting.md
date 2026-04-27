# Troubleshooting the JDWP MCP Server

## Tool returns an unexpected error or server seems stuck

1. Check the server log if your installation writes one — the JDWP MCP server logs JDI operations and errors there.
2. Reconnect the MCP server: run `/mcp` in Claude Code and reconnect `jdwp-inspector`. This spawns a fresh subprocess.

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

The test JVM exits when its debugger detaches; the MCP server may also have crashed. Reconnect the MCP server first (`/mcp`), then relaunch the test JVM from scratch.
