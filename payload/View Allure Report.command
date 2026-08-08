#!/usr/bin/env bash
# Double-click entry point for macOS - runs view-allure-report.sh from
# wherever this file was installed, then keeps the window open so any
# error message (or the running local server) stays visible.

cd "$(dirname "$0")" || exit 1
./view-allure-report.sh
echo ""
read -r -p "Press Enter to close..." _
