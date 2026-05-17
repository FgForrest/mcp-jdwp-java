#!/usr/bin/env bash
# Assemble the standalone jdwp-sandbox distribution zip.
#
# Run from the repository root:
#   ./release/sandbox/assemble.sh [version]
#
# Output: release/sandbox/dist/jdwp-sandbox-<version>.zip
#
# The zip is a self-contained Maven project that fresh plugin users can
# download from GitHub Releases, unzip, and run without cloning this repo.

set -euo pipefail

# Resolve repo root (parent of release/) regardless of where the script is invoked from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

SOURCE_DIR="$REPO_ROOT/jdwp-sandbox/src"
SANDBOX_TEMPLATE_DIR="$SCRIPT_DIR"

if [[ ! -d "$SOURCE_DIR" ]]; then
    echo "error: $SOURCE_DIR does not exist — run from a clean checkout" >&2
    exit 1
fi

# Version: explicit arg, else derive from marketplace.json.
if [[ $# -ge 1 ]]; then
    VERSION="$1"
else
    MARKETPLACE_JSON="$REPO_ROOT/.claude-plugin/marketplace.json"
    if ! VERSION="$(grep -m1 '"version"' "$MARKETPLACE_JSON" | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"; then
        echo "error: could not derive version from $MARKETPLACE_JSON; pass it explicitly" >&2
        exit 1
    fi
fi

STAGING_DIR="$SCRIPT_DIR/dist/jdwp-sandbox"
OUTPUT_ZIP="$SCRIPT_DIR/dist/jdwp-sandbox-$VERSION.zip"

echo "Assembling jdwp-sandbox $VERSION"
echo "  source:  $SOURCE_DIR"
echo "  staging: $STAGING_DIR"
echo "  output:  $OUTPUT_ZIP"

rm -rf "$SCRIPT_DIR/dist"
mkdir -p "$STAGING_DIR"

# Copy the sandbox source tree as-is.
cp -R "$SOURCE_DIR" "$STAGING_DIR/src"

# Copy the standalone project files.
cp "$SANDBOX_TEMPLATE_DIR/pom.xml"    "$STAGING_DIR/pom.xml"
cp "$SANDBOX_TEMPLATE_DIR/README.md"  "$STAGING_DIR/README.md"
cp "$SANDBOX_TEMPLATE_DIR/CLAUDE.md"  "$STAGING_DIR/CLAUDE.md"

# Patch the pom version to match the release version.
# Portable sed: write to a temp file then move into place (works on both GNU and BSD sed).
PATCHED_POM="$STAGING_DIR/pom.xml.tmp"
sed -E "0,/<version>[^<]+<\/version>/s//<version>$VERSION<\/version>/" \
    "$STAGING_DIR/pom.xml" > "$PATCHED_POM"
mv "$PATCHED_POM" "$STAGING_DIR/pom.xml"

# Zip from inside the dist dir so the archive expands to a clean jdwp-sandbox/ folder.
# Use whichever zip tool is available — both produce standard archives.
if command -v zip >/dev/null 2>&1; then
    ( cd "$SCRIPT_DIR/dist" && zip -r -q "$(basename "$OUTPUT_ZIP")" "jdwp-sandbox" )
elif command -v python3 >/dev/null 2>&1; then
    ( cd "$SCRIPT_DIR/dist" && python3 -m zipfile -c "$(basename "$OUTPUT_ZIP")" "jdwp-sandbox" )
else
    echo "error: neither 'zip' nor 'python3' is on PATH; cannot create archive" >&2
    exit 1
fi

echo "✓ $OUTPUT_ZIP"
ls -lh "$OUTPUT_ZIP"
