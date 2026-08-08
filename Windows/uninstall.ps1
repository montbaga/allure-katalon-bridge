<#
.SYNOPSIS
    Removes an Allure-Katalon Bridge installation from a Katalon Studio project.

.DESCRIPTION
    Reads <project>/.allure-bridge/manifest.txt (written by install.ps1) and
    deletes exactly the files it recorded - nothing else in the project is
    touched. Never deletes allure-results/ (that's generated test output,
    not part of the install) and, by default, leaves
    Include/config/allure/allure.properties in place so a future reinstall
    doesn't lose your settings; pass -RemoveConfig to delete it too.

.PARAMETER ProjectPath
    Path to the target Katalon Studio project root.

.PARAMETER RemoveConfig
    Also delete Include/config/allure/allure.properties and categories.json.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File uninstall.ps1 -ProjectPath "C:\Users\User\Katalon Studio\my-other-project"
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectPath,

    [switch]$RemoveConfig
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $ProjectPath)) {
    throw "ProjectPath does not exist: $ProjectPath"
}
$ProjectPath = (Resolve-Path $ProjectPath).Path

$manifestDir = Join-Path $ProjectPath '.allure-bridge'
$manifestPath = Join-Path $manifestDir 'manifest.txt'
if (-not (Test-Path $manifestPath)) {
    throw "No install manifest found at $manifestPath - this project doesn't look like it has the bridge installed via install.ps1."
}

$lines = Get-Content $manifestPath
$version = $lines[0]
$files = $lines | Select-Object -Skip 1

Write-Host "Uninstalling Allure-Katalon Bridge v$version from: $ProjectPath" -ForegroundColor Cyan

$configFiles = @('Include\config\allure\allure.properties', 'Include\config\allure\categories.json')

foreach ($relativePath in $files) {
    if ([string]::IsNullOrWhiteSpace($relativePath)) { continue }

    if (($configFiles -contains $relativePath) -and -not $RemoveConfig) {
        Write-Host "  KEEP (config; pass -RemoveConfig to delete): $relativePath" -ForegroundColor Yellow
        continue
    }

    $targetFile = Join-Path $ProjectPath $relativePath
    if (Test-Path $targetFile) {
        Remove-Item $targetFile -Force
        Write-Host "  REMOVED  $relativePath"
    }
}

# Best-effort cleanup of now-empty directories the bridge created.
$dirsToCheck = @(
    'Keywords\allure', 'Libs\allure', 'Include\config\allure',
    'Test Listeners', 'Drivers'
)
foreach ($dir in $dirsToCheck) {
    $fullDir = Join-Path $ProjectPath $dir
    if ((Test-Path $fullDir) -and ((Get-ChildItem $fullDir -Force | Measure-Object).Count -eq 0)) {
        Remove-Item $fullDir -Force
        Write-Host "  REMOVED  $dir\ (now empty)"
    }
}

Remove-Item $manifestPath -Force
if ((Get-ChildItem $manifestDir -Force | Measure-Object).Count -eq 0) {
    Remove-Item $manifestDir -Force
}

Write-Host ""
Write-Host "Uninstall complete." -ForegroundColor Green
Write-Host "Note: allure-results/ (generated test output) was left in place - delete it manually if you want it gone too."
