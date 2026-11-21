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
# With --download (passed only by scripts/jdwp-inspector.sh, the launcher for
# hosts that read the root plugin.json, such as Codex), it first tries to
# download the release asset mcp-jdwp-java-<version>.jar, <version> taken from
# the root plugin.json. It does so only when that release was built from the sources at
# hand: the server sources equal tag v<version>, or there is no git history to
# compare with. It builds when the download is skipped or fails.
#
# When JDWP_JAR_DIR is set (the launcher sets it to a directory in the plugin's
# data directory), the JAR the server runs, its marker, the lock and the log
# live there instead of in jdwp-mcp-server/target: Codex replaces the plugin's
# files when it reinstalls the plugin but keeps its data directory. A download
# goes straight there; Maven still builds in jdwp-mcp-server/target, and the
# built JAR is copied over. The marker then also carries the plugin version, as
# the data directory outlives versions and "no-git" alone would not tell them
# apart.
#
# Maven output goes to server-build.log next to the JAR. Stdout carries at most
# one JSON object: its systemMessage is shown to the user, its
# additionalContext reaches Claude. Nothing is printed when no build was needed.
# The script always exits 0 so that Claude Code applies that JSON.
#
# Plain bash 3.2 (macOS) and Linux; no jq.

PLUGIN="${CLAUDE_PLUGIN_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
BUILT="$PLUGIN/jdwp-mcp-server/target/mcp-jdwp-java.jar"
TARGET="${JDWP_JAR_DIR:-$PLUGIN/jdwp-mcp-server/target}"
JAR="$TARGET/mcp-jdwp-java.jar"
PREV="$JAR.prev"
MARKER="$TARGET/.build-sha"
LOCK="$TARGET/.build-lock"
LOG="$TARGET/server-build.log"
# true when the JAR lives outside the build directory (JDWP_JAR_DIR is set)
JAR_ELSEWHERE=false
[ "$JAR" != "$BUILT" ] && JAR_ELSEWHERE=true
DOWNLOAD=false
[ "${1:-}" = "--download" ] && DOWNLOAD=true
RELEASES="https://github.com/FgForrest/mcp-jdwp-java/releases/download"

# The plugin version from the root plugin.json; empty when there is none.
plugin_version() {
  sed -n 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$PLUGIN/plugin.json" 2>/dev/null | head -n 1
}

EXPECTED=$(git -C "$PLUGIN" rev-parse HEAD 2>/dev/null || echo no-git)
[ "$JAR_ELSEWHERE" = true ] && EXPECTED="v$(plugin_version) $EXPECTED"

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

if [ "$JAR_ELSEWHERE" = true ]; then
  MANUAL="cd '$PLUGIN' && ./mvnw -pl jdwp-mcp-server -am package -DskipTests && cp jdwp-mcp-server/target/mcp-jdwp-java.jar '$JAR' && printf %s '$EXPECTED' > '$MARKER'"
else
  MANUAL="cd '$PLUGIN' && ./mvnw -pl jdwp-mcp-server -am package -DskipTests && printf %s $EXPECTED > jdwp-mcp-server/target/.build-sha"
fi
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
# $1 = label for the log, the rest = the Maven command. Maven writes $BUILT;
# with JDWP_JAR_DIR set, the JAR is then copied to $JAR. A leftover $BUILT is
# removed first so that a failed build cannot pass for a successful one.
build() {
  label=$1
  shift
  printf '\n=== %s: %s\n' "$label" "$(date)" >> "$LOG"
  [ "$JAR_ELSEWHERE" = true ] && rm -f "$BUILT"
  (cd "$PLUGIN" && "$@" -pl jdwp-mcp-server -am package -DskipTests -B) >> "$LOG" 2>&1 < /dev/null && [ -s "$BUILT" ] || return 1
  [ "$JAR_ELSEWHERE" = true ] || return 0
  cp "$BUILT" "$JAR.copy" && mv -f "$JAR.copy" "$JAR"
}

# True when release v$1 was built from the server sources at hand. Without git
# history (an installed copy) the plugin version is all there is to go by.
release_matches_sources() {
  git -C "$PLUGIN" rev-parse --git-dir > /dev/null 2>&1 || return 0
  git -C "$PLUGIN" rev-parse -q --verify "refs/tags/v$1^{commit}" > /dev/null 2>&1 &&
    git -C "$PLUGIN" diff --quiet "v$1" -- pom.xml jdwp-mcp-server > /dev/null 2>&1
}

# $1 = URL, $2 = file; exit status of the transfer.
fetch() {
  if command -v curl > /dev/null 2>&1; then
    curl -fsSL --connect-timeout 20 --max-time 600 -o "$2" "$1" >> "$LOG" 2>&1 < /dev/null
  elif command -v wget > /dev/null 2>&1; then
    wget -q -T 20 -O "$2" "$1" >> "$LOG" 2>&1 < /dev/null
  else
    echo "neither curl nor wget is on PATH" >> "$LOG"
    return 1
  fi
}

# Downloads the release JAR of version $1 into place; a partial download never
# becomes the JAR.
download() {
  url="$RELEASES/v$1/mcp-jdwp-java-$1.jar"
  part="$JAR.download"
  printf '\n=== download %s: %s\n' "$url" "$(date)" >> "$LOG"
  rm -f "$part"
  # A failed or cut-off transfer fails fetch (curl -f, wget); the "PK" ZIP
  # header check additionally rejects anything that is not a JAR, such as an
  # HTML page served with a success status.
  if fetch "$url" "$part" && [ "$(head -c 2 "$part" 2>/dev/null)" = "PK" ] && mv -f "$part" "$JAR"; then
    return 0
  fi
  rm -f "$part"
  return 1
}

done_what=""
tried=""
version=$(plugin_version)
if [ "$DOWNLOAD" = true ] && [ -n "$version" ]; then
  if release_matches_sources "$version"; then
    tried="the v$version release download, "
    download "$version" && done_what="downloaded the jdwp-inspector MCP server JAR of release v$version"
  else
    printf '\n=== no download: the server sources differ from tag v%s\n' "$version" >> "$LOG"
  fi
fi

if [ -z "$done_what" ]; then
  built=""
  tried="${tried}./mvnw"
  if build "./mvnw" sh ./mvnw; then
    built="./mvnw"
  elif ! command -v mvn > /dev/null 2>&1; then
    tried="$tried (no mvn on PATH)"
  else
    tried="$tried, mvn -o, mvn"
    if build "mvn -o" mvn -o; then
      built="mvn -o"
    elif build "mvn" mvn; then
      built="mvn"
    fi
  fi
  [ -n "$built" ] && done_what="built the jdwp-inspector MCP server JAR with $built"
fi

if [ -n "$done_what" ]; then
  rm -f "$PREV"
  printf '%s' "$EXPECTED" > "$MARKER.tmp" && mv -f "$MARKER.tmp" "$MARKER"
  emit "jdwp-debugging: $done_what. $RECONNECT" \
    "The jdwp-inspector MCP server JAR was built at session start. If its tools are missing, ask the user to reconnect jdwp-inspector in /mcp."
  exit 0
fi

if [ -f "$PREV" ]; then
  mv -f "$PREV" "$JAR"
  fail "could not rebuild the MCP server JAR ($tried failed; log: $LOG), so jdwp-inspector runs the previous build."
fi
rm -f "$JAR"
fail "could not build the MCP server JAR ($tried failed; log: $LOG), so jdwp-inspector cannot start."
