#!/usr/bin/env bash
# Removes an Allure-Katalon Bridge installation from a Katalon Studio project.
#
# Usage:
#   ./uninstall.sh /path/to/katalon/project [--remove-config]
#
# Reads <project>/.allure-bridge/manifest.txt (written by install.sh) and
# deletes exactly the files it recorded. Never deletes allure-results/.
# By default Include/config/allure/*.json|properties are kept so a future
# reinstall doesn't lose your settings; pass --remove-config to delete them.

set -euo pipefail

PROJECT_PATH="${1:-}"
REMOVE_CONFIG=false
for arg in "$@"; do
    if [ "$arg" = "--remove-config" ]; then
        REMOVE_CONFIG=true
    fi
done

if [ -z "$PROJECT_PATH" ]; then
    read -r -p "Enter the path to your Katalon Studio project: " PROJECT_PATH
fi

if [ -z "$PROJECT_PATH" ]; then
    echo "Usage: $0 /path/to/katalon/project [--remove-config]" >&2
    exit 1
fi
if [ ! -d "$PROJECT_PATH" ]; then
    echo "ProjectPath does not exist: $PROJECT_PATH" >&2
    exit 1
fi
PROJECT_PATH="$(cd "$PROJECT_PATH" && pwd)"

MANIFEST_FILE="$PROJECT_PATH/.allure-bridge/manifest.txt"
if [ ! -f "$MANIFEST_FILE" ]; then
    echo "No install manifest found at $MANIFEST_FILE - this project doesn't look like it has the bridge installed via install.sh." >&2
    exit 1
fi

VERSION="$(head -n1 "$MANIFEST_FILE")"
echo "Uninstalling Allure-Katalon Bridge v$VERSION from: $PROJECT_PATH"

CONFIG_FILES="Include/config/allure/allure.properties
Include/config/allure/categories.json"

tail -n +2 "$MANIFEST_FILE" | while IFS= read -r rel; do
    [ -z "$rel" ] && continue

    if [ "$REMOVE_CONFIG" = false ] && echo "$CONFIG_FILES" | grep -qx "$rel"; then
        echo "  KEEP (config; pass --remove-config to delete): $rel"
        continue
    fi

    target="$PROJECT_PATH/$rel"
    if [ -f "$target" ]; then
        rm -f "$target"
        echo "  REMOVED  $rel"
    fi
done

for dir in "Keywords/allure" "Libs/allure" "Include/config/allure" "Test Listeners" "Drivers"; do
    full="$PROJECT_PATH/$dir"
    if [ -d "$full" ] && [ -z "$(ls -A "$full")" ]; then
        rmdir "$full"
        echo "  REMOVED  $dir/ (now empty)"
    fi
done

rm -f "$MANIFEST_FILE"
MANIFEST_DIR="$PROJECT_PATH/.allure-bridge"
if [ -d "$MANIFEST_DIR" ] && [ -z "$(ls -A "$MANIFEST_DIR")" ]; then
    rmdir "$MANIFEST_DIR"
fi

echo ""
echo "Uninstall complete."
echo "Note: allure-results/ (generated test output) was left in place - delete it manually if you want it gone too."
