@echo off
setlocal enabledelayedexpansion
title Allure Report
cd /d "%~dp0"

REM The report is generated automatically when a test suite finishes - this
REM script only opens the most recent one. By default that's a single
REM ".html" file directly under allure-report\ (Allure's --single-file mode -
REM opens straight in your browser, no server needed). If
REM allure.report.single.file=false, it's a "<Name>_<timestamp>\" folder
REM instead, which needs "allure open" (a local server) to view correctly.

set "LATEST_HTML="
if exist "allure-report" (
    for /f "delims=" %%F in ('dir /b /a-d /o-d "allure-report\*.html" 2^>nul') do (
        if not defined LATEST_HTML set "LATEST_HTML=%%F"
    )
)

if defined LATEST_HTML (
    echo Opening report: allure-report\%LATEST_HTML%
    start "" "allure-report\%LATEST_HTML%"
    exit /b 0
)

where allure >nul 2>nul
if errorlevel 1 (
    echo Allure commandline is not installed or not on PATH.
    echo.
    echo Install it once with:
    echo     npm install -g allure-commandline
    echo or:
    echo     scoop install allure
    echo.
    pause
    exit /b 1
)

set "LATEST_DIR="
if exist "allure-report" (
    for /f "delims=" %%D in ('dir /b /ad /o-d "allure-report" 2^>nul') do (
        if not defined LATEST_DIR set "LATEST_DIR=%%D"
    )
)

if not defined LATEST_DIR (
    echo No report found yet under allure-report\.
    echo Run a Katalon test suite first - the report is generated automatically when it finishes.
    echo.
    pause
    exit /b 1
)

echo Opening report: allure-report\%LATEST_DIR%
echo (Close this window to stop serving the report.)
call allure open "allure-report\%LATEST_DIR%"
