#!/usr/bin/env bash
# Double-click entry point for macOS uninstall. See Install.command for
# the folder-picker/typed-prompt fallback behavior. Delegates to
# ../Linux/uninstall.sh - bash is identical on macOS and Linux.

cd "$(dirname "$0")/../Linux" || exit 1

PROJECT_PATH=""
if command -v osascript >/dev/null 2>&1; then
    PROJECT_PATH=$(osascript -e 'POSIX path of (choose folder with prompt "Select the Katalon Studio project to remove the Allure-Katalon Bridge from")' 2>/dev/null)
fi

if [ -z "$PROJECT_PATH" ]; then
    read -r -p "Enter the path to your Katalon Studio project: " PROJECT_PATH
fi

if [ -z "$PROJECT_PATH" ]; then
    echo "Cancelled - no folder selected."
    read -r -p "Press Enter to close..." _
    exit 0
fi

./uninstall.sh "$PROJECT_PATH"

echo ""
read -r -p "Press Enter to close..." _
