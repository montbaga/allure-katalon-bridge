#!/usr/bin/env bash
# Double-click entry point for macOS: opens a folder picker instead of
# requiring a typed path. Falls back to a typed prompt if osascript/Finder
# isn't available (e.g. run over SSH). For CI/scripted installs, use
# ../Linux/install.sh <path> directly instead.
#
# Delegates to ../Linux/install.sh - bash is identical on macOS and Linux,
# so there's one engine script instead of two copies to keep in sync.

cd "$(dirname "$0")/../Linux" || exit 1

PROJECT_PATH=""
if command -v osascript >/dev/null 2>&1; then
    PROJECT_PATH=$(osascript -e 'POSIX path of (choose folder with prompt "Select your Katalon Studio project folder")' 2>/dev/null)
fi

if [ -z "$PROJECT_PATH" ]; then
    read -r -p "Enter the path to your Katalon Studio project: " PROJECT_PATH
fi

if [ -z "$PROJECT_PATH" ]; then
    echo "Cancelled - no folder selected."
    read -r -p "Press Enter to close..." _
    exit 0
fi

./install.sh "$PROJECT_PATH"

echo ""
read -r -p "Press Enter to close..." _
