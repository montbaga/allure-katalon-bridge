#!/usr/bin/env bash
# Opens the most recently generated Allure report. Generation itself
# happens automatically when a test suite finishes. By default that's a
# single .html file directly under allure-report/ (Allure's --single-file
# mode - opens straight in your browser, no server needed). If
# allure.report.single.file=false, it's a "<Name>_<timestamp>/" folder
# instead, which needs "allure open" (a local server) since browsers block
# a multi-file report's local data fetches when opened via file://.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

LATEST_HTML=$(ls -t "allure-report"/*.html 2>/dev/null | head -1 || true)

if [ -n "$LATEST_HTML" ]; then
    echo "Opening report: $LATEST_HTML"
    case "$(uname -s)" in
        Darwin) open "$LATEST_HTML" ;;
        *) xdg-open "$LATEST_HTML" 2>/dev/null || echo "Open this file in your browser: $LATEST_HTML" ;;
    esac
    exit 0
fi

if ! command -v allure >/dev/null 2>&1; then
    echo "Allure commandline is not installed or not on PATH."
    echo ""
    echo "Install it once with:"
    echo "    npm install -g allure-commandline"
    echo "or:"
    echo "    brew install allure"
    exit 1
fi

LATEST_DIR=$(ls -td "allure-report"/*/ 2>/dev/null | head -1 || true)
LATEST_DIR="${LATEST_DIR%/}"

if [ -z "$LATEST_DIR" ]; then
    echo "No report found yet under allure-report/."
    echo "Run a Katalon test suite first - the report is generated automatically when it finishes."
    exit 1
fi

echo "Opening report: $LATEST_DIR"
echo "(Ctrl+C to stop serving the report.)"
allure open "$LATEST_DIR"
