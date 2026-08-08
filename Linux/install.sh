#!/usr/bin/env bash
# Installs the Allure-Katalon Bridge into a Katalon Studio project.
#
# Usage:
#   ./install.sh /path/to/katalon/project [--force]
#
# --force also overwrites an existing Include/config/allure/allure.properties
# in the target project. Without it, a customized config is left alone.
#
# Safe to re-run for upgrades: library/keyword/jar files are always
# refreshed to the version shipped in this package.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PAYLOAD_DIR="$REPO_ROOT/payload"
VERSION="$(cat "$REPO_ROOT/VERSION" | tr -d '[:space:]')"

PROJECT_PATH="${1:-}"
FORCE=false
for arg in "$@"; do
    if [ "$arg" = "--force" ]; then
        FORCE=true
    fi
done

if [ -z "$PROJECT_PATH" ]; then
    read -r -p "Enter the path to your Katalon Studio project: " PROJECT_PATH
fi

if [ -z "$PROJECT_PATH" ]; then
    echo "Usage: $0 /path/to/katalon/project [--force]" >&2
    exit 1
fi

if [ ! -d "$PROJECT_PATH" ]; then
    echo "ProjectPath does not exist: $PROJECT_PATH" >&2
    exit 1
fi
PROJECT_PATH="$(cd "$PROJECT_PATH" && pwd)"

if ! find "$PROJECT_PATH" -maxdepth 1 -name '*.prj' -print -quit | grep -q .; then
    echo "No *.prj file found directly under '$PROJECT_PATH'. This does not look like a Katalon Studio project root - aborting." >&2
    exit 1
fi

echo "Installing Allure-Katalon Bridge v$VERSION into: $PROJECT_PATH"

MANIFEST_DIR="$PROJECT_PATH/.allure-bridge"
mkdir -p "$MANIFEST_DIR"
MANIFEST_FILE="$MANIFEST_DIR/manifest.txt"
echo "$VERSION" > "$MANIFEST_FILE"

CONFIG_REL="Include/config/allure/allure.properties"

while IFS= read -r -d '' file; do
    rel="${file#"$PAYLOAD_DIR"/}"
    dest="$PROJECT_PATH/$rel"

    if [ "$rel" = "$CONFIG_REL" ] && [ -f "$dest" ] && [ "$FORCE" = false ]; then
        echo "  SKIP (already customized, use --force to overwrite): $rel"
        continue
    fi

    mkdir -p "$(dirname "$dest")"
    cp "$file" "$dest"
    case "$rel" in
        *.sh|*.command) chmod +x "$dest" ;;
    esac
    echo "$rel" >> "$MANIFEST_FILE"
    echo "  OK   $rel"
done < <(find "$PAYLOAD_DIR" -type f -print0)

echo ""
echo "Install complete."
echo "Next steps:"
echo "  1. Reopen (or refresh) the project in Katalon Studio."
echo "  2. Run any Test Suite as usual - no changes needed to existing tests."
echo "  3. Look for '[Allure]' lines in the console, and an allure-results/ folder afterwards."
echo "  4. View it: npm install -g allure-commandline; allure serve allure-results"
