#!/usr/bin/env bash
# SessionStart hook of the jdwp-debugging plugin: makes sure the MCP server JAR
# that the plugin's jdwp-inspector entry launches exists and was built from the
# plugin's current sources.
#
# It builds when the JAR is missing or jdwp-mcp-server/target/.build-sha differs
# from `git rev-parse HEAD` ("no-git" outside a git checkout, as in the plugin
# cache). It tries ./mvnw, then `mvn -o`, then `mvn`: the wrapper fails when it
# cannot install Maven under ~/.m2 (read-only in some sandboxes), and offline
# goes first because it needs no network and fails fast when the local
# repository lacks something.
#
# Maven output goes to jdwp-mcp-server/target/server-build.log. Stdout carries
# at most one JSON object: its systemMessage is shown to the user, its
# additionalContext reaches Claude. Nothing is printed when no build was needed.
# The script always exits 0 so that Claude Code applies that JSON.
#
# Plain bash 3.2 (macOS) and Linux; no jq.

PLUGIN="${CLAUDE_PLUGIN_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
TARGET="$PLUGIN/jdwp-mcp-server/target"
JAR="$TARGET/mcp-jdwp-java.jar"
PREV="$JAR.prev"
MARKER="$TARGET/.build-sha"
LOCK="$TARGET/.build-lock"
LOG="$TARGET/server-build.log"
EXPECTED=$(git -C "$PLUGIN" rev-parse HEAD 2>/dev/null || echo no-git)

needs_build() {
  [ ! -f "$JAR" ] || [ "$EXPECTED" != "$(cat "$MARKER" 2>/dev/null)" ]
}

needs_build || exit 0

json_escape() {
  printf '%s' "$1" | LC_ALL=C tr -d '\000-\037' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# $1 = message shown to the user, $2 = context for Claude
emit() {
  printf '{"systemMessage":"%s","hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"%s"}}\n' \
    "$(json_escape "$1")" "$(json_escape "$2")"
}

MANUAL="cd '$PLUGIN' && ./mvnw -pl jdwp-mcp-server -am package -DskipTests && printf %s $EXPECTED > jdwp-mcp-server/target/.build-sha"
RECONNECT="If jdwp-inspector failed to connect at startup, run /mcp and reconnect it."

fail() {
  emit "jdwp-debugging: $1 Build it by hand, then reconnect jdwp-inspector in /mcp: $MANUAL" \
    "The jdwp-debugging plugin could not build its MCP server JAR at session start: $1 If the jdwp-inspector tools are missing or misbehave, tell the user to build it with: $MANUAL"
  exit 0
}

# Two sessions starting at once must not run Maven in the same target/. A lock
# is stale when its owner is gone (say, killed at the hook timeout) or, in case
# its PID was reused, when it is older than 30 minutes.
lock_is_stale() {
  owner=$(cat "$LOCK/pid" 2>/dev/null)
  { [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; } ||
    [ -n "$(find "$LOCK" -prune -mmin +30 2>/dev/null)" ]
}

mkdir -p "$TARGET" 2>/dev/null
waited=0
until mkdir "$LOCK" 2>/dev/null; do
  [ -d "$LOCK" ] || fail "could not write to $TARGET, so the MCP server JAR cannot be built."
  lock_is_stale && rm -rf "$LOCK" 2>/dev/null && continue
  [ "$waited" -ge 480 ] && fail "another session has been building the MCP server JAR for 8 minutes (lock $LOCK)."
  sleep 5
  waited=$((waited + 5))
done
echo $$ > "$LOCK/pid"
# A signal mid-build puts the old JAR back; on every other path that is a no-op.
trap '[ -f "$PREV" ] && mv -f "$PREV" "$JAR"; rm -rf "$LOCK"' EXIT
trap 'exit 1' HUP INT TERM

# The JAR left aside by a run that was killed mid-build is the last good one.
[ -f "$PREV" ] && mv -f "$PREV" "$JAR"

if ! needs_build; then
  # Another session built it while this one waited for the lock.
  [ "$waited" -gt 0 ] && emit "jdwp-debugging: another session built the jdwp-inspector MCP server JAR. $RECONNECT" \
    "The jdwp-inspector MCP server JAR was built at session start. If its tools are missing, ask the user to reconnect jdwp-inspector in /mcp."
  exit 0
fi

# Maven writes the JAR in place. Moving the old one aside keeps a failed build
# from leaving a partial JAR behind, and leaves the file a running server has
# open untouched. A server started during the build finds no JAR and needs a
# reconnect, which the success message asks for.
[ -f "$JAR" ] && mv -f "$JAR" "$PREV"

: > "$LOG"
# $1 = label for the log, the rest = the Maven command
build() {
  label=$1
  shift
  printf '\n=== %s: %s\n' "$label" "$(date)" >> "$LOG"
  (cd "$PLUGIN" && "$@" -pl jdwp-mcp-server -am package -DskipTests -B) >> "$LOG" 2>&1 < /dev/null && [ -s "$JAR" ]
}

built=""
tried="./mvnw"
if build "./mvnw" sh ./mvnw; then
  built="./mvnw"
elif ! command -v mvn > /dev/null 2>&1; then
  tried="./mvnw (no mvn on PATH)"
else
  tried="./mvnw, mvn -o, mvn"
  if build "mvn -o" mvn -o; then
    built="mvn -o"
  elif build "mvn" mvn; then
    built="mvn"
  fi
fi

if [ -n "$built" ]; then
  rm -f "$PREV"
  printf '%s' "$EXPECTED" > "$MARKER.tmp" && mv -f "$MARKER.tmp" "$MARKER"
  emit "jdwp-debugging: built the jdwp-inspector MCP server JAR with $built. $RECONNECT" \
    "The jdwp-inspector MCP server JAR was built at session start. If its tools are missing, ask the user to reconnect jdwp-inspector in /mcp."
  exit 0
fi

if [ -f "$PREV" ]; then
  mv -f "$PREV" "$JAR"
  fail "could not rebuild the MCP server JAR ($tried failed; log: $LOG), so jdwp-inspector runs the previous build."
fi
rm -f "$JAR"
fail "could not build the MCP server JAR ($tried failed; log: $LOG), so jdwp-inspector cannot start."
