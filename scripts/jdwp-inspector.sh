#!/bin/sh
# Starts the jdwp-inspector MCP server for hosts that read the plugin's root
# manifest (plugin.json + mcp.json), such as Codex. Claude Code reads
# .claude-plugin/plugin.json instead: it starts the JAR directly and builds it
# in the SessionStart hook.
#
# Codex runs no plugin hook before it starts the server, so this script first
# makes sure the JAR is there and current: it runs
# hooks/ensure-server-jar.sh --download and waits for it. That returns at once
# when the JAR is up to date; otherwise it downloads the release JAR or builds
# it, under the hook's lock, so a concurrent session waits instead of starting
# a half-written JAR.
#
# The JAR lives in <data>/server, <data> being $PLUGIN_DATA (Codex sets it): Codex
# replaces the plugin's own files when it reinstalls the plugin, even at the
# same version, and a JAR kept among them would go with them. Without
# PLUGIN_DATA it stays in the plugin's jdwp-mcp-server/target.
#
# The preparation runs in a session of its own (setsid). Codex kills the
# server's process group when it gives up on a slow start or ends the session;
# a download or build in a new session still finishes, and the next session
# finds the JAR.
#
# Stdout is the MCP channel: everything the preparation prints goes to
# <logs>/server-jar-setup.log, where <logs> is $PLUGIN_DATA/logs (Codex sets
# PLUGIN_DATA) or the plugin's logs/ directory.
#
# POSIX sh (macOS and Linux).

ROOT=${PLUGIN_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
LOGS="${PLUGIN_DATA:-$ROOT}/logs"
mkdir -p "$LOGS" 2> /dev/null || LOGS=${TMPDIR:-/tmp}
JAR_DIR="$ROOT/jdwp-mcp-server/target"
if [ -n "${PLUGIN_DATA:-}" ] && mkdir -p "$PLUGIN_DATA/server" 2> /dev/null; then
  JAR_DIR="$PLUGIN_DATA/server"
  export JDWP_JAR_DIR="$JAR_DIR"
fi
JAR="$JAR_DIR/mcp-jdwp-java.jar"
SETUP_LOG="$LOGS/server-jar-setup.log"

# Runs "$@" in a new session where the platform offers a way to, and returns
# once it ends. setsid -w (util-linux) waits for the command even when it has
# to fork. macOS has no setsid command, so perl's POSIX::setsid stands in: it
# keeps the PID when it can, and when the process leads its group, where
# setsid() fails, it forks and the parent waits for the child, like setsid -w.
detached() {
  if command -v setsid > /dev/null 2>&1 && setsid -w true 2> /dev/null; then
    setsid -w "$@"
  elif command -v perl > /dev/null 2>&1; then
    perl -MPOSIX -e '
      my $sid = POSIX::setsid();
      if (!defined $sid || $sid < 0) {
        defined(my $pid = fork) or exit 127;
        if ($pid) { waitpid($pid, 0); exit($? >> 8); }
        POSIX::setsid();
      }
      exec { $ARGV[0] } @ARGV or exit 127;' "$@"
  else
    "$@"
  fi
}

detached bash "$ROOT/hooks/ensure-server-jar.sh" --download < /dev/null >> "$SETUP_LOG" 2>&1 &
wait $!

if [ ! -s "$JAR" ]; then
  echo "jdwp-inspector: the server JAR $JAR is not available. See $SETUP_LOG and $JAR_DIR/server-build.log." >&2
  exit 1
fi

exec java --add-modules jdk.jdi,jdk.attach -DLOG_PATH="$LOGS/mcp-jdwp-inspector.log" -jar "$JAR"
