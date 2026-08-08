<#
.SYNOPSIS
    Installs the Allure-Katalon Bridge into a Katalon Studio project.

.DESCRIPTION
    Copies the bridge's Test Listener, Keywords, config, and Drivers jars
    into the target Katalon project, then records exactly what it installed
    in <project>/.allure-bridge/manifest.txt so uninstall.ps1 can remove it
    cleanly later without touching anything else in the project.

    Safe to re-run (upgrade): library/keyword/jar files are always
    refreshed to the version shipped in this package.
    Include/config/allure/allure.properties is left alone if the target
    project already customized it - pass -Force to overwrite it too.

.PARAMETER ProjectPath
    Path to the target Katalon Studio project root (the folder containing
    the project's *.prj file).

.PARAMETER Force
    Also overwrite an existing Include/config/allure/allure.properties in
    the target project with the one shipped here.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install.ps1 -ProjectPath "C:\Users\User\Katalon Studio\my-other-project"

.EXAMPLE
    .\install.ps1 -ProjectPath "..\another-project" -Force
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectPath,

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path $PSScriptRoot -Parent
$payloadRoot = Join-Path $repoRoot 'payload'
$version = (Get-Content (Join-Path $repoRoot 'VERSION') -Raw).Trim()

# --- Preflight: make sure this looks like a real Katalon project, not an
#     arbitrary folder, before writing anything into it. -------------------
if (-not (Test-Path $ProjectPath)) {
    throw "ProjectPath does not exist: $ProjectPath"
}
$ProjectPath = (Resolve-Path $ProjectPath).Path

$prjFile = Get-ChildItem -Path $ProjectPath -Filter '*.prj' -File -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $prjFile) {
    throw "No *.prj file found directly under '$ProjectPath'. This does not look like a Katalon Studio project root - aborting to avoid writing into the wrong folder."
}

Write-Host "Installing Allure-Katalon Bridge v$version into: $ProjectPath" -ForegroundColor Cyan
Write-Host "  (detected Katalon project: $($prjFile.Name))"

# --- Copy the payload, tracking what we touch for a clean uninstall. ------
$manifestDir = Join-Path $ProjectPath '.allure-bridge'
New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null
$installedFiles = New-Object System.Collections.Generic.List[string]

$payloadFiles = Get-ChildItem -Path $payloadRoot -Recurse -File
foreach ($file in $payloadFiles) {
    $relativePath = $file.FullName.Substring($payloadRoot.Length).TrimStart('\', '/')
    $destPath = Join-Path $ProjectPath $relativePath

    $isUserConfig = $relativePath -eq 'Include\config\allure\allure.properties' -or $relativePath -eq 'Include/config/allure/allure.properties'
    if ($isUserConfig -and (Test-Path $destPath) -and -not $Force) {
        Write-Host "  SKIP (already customized, use -Force to overwrite): $relativePath" -ForegroundColor Yellow
        continue
    }

    $destDir = Split-Path $destPath -Parent
    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }
    Copy-Item -Path $file.FullName -Destination $destPath -Force
    $installedFiles.Add($relativePath) | Out-Null
    Write-Host "  OK   $relativePath"
}

# --- Record the manifest (version + every path we installed or refreshed). -
$manifestPath = Join-Path $manifestDir 'manifest.txt'
$manifestContent = @($version) + $installedFiles
Set-Content -Path $manifestPath -Value $manifestContent -Encoding utf8

# --- Best-effort: register the two Drivers jars in .classpath if the
#     project already has one open in the IDE, so Katalon's editor resolves
#     the Allure classes immediately instead of after a manual refresh. ---
$classpathFile = Join-Path $ProjectPath '.classpath'
if (Test-Path $classpathFile) {
    [xml]$classpathXml = Get-Content $classpathFile -Raw
    $root = $classpathXml.classpath
    $existingPaths = $root.classpathentry | ForEach-Object { $_.path }
    $jarsToRegister = @('Drivers/allure-java-commons-2.35.4.jar', 'Drivers/allure-model-2.35.4.jar')
    $changed = $false
    foreach ($jarPath in $jarsToRegister) {
        if ($existingPaths -notcontains $jarPath) {
            $entry = $classpathXml.CreateElement('classpathentry')
            $entry.SetAttribute('kind', 'lib')
            $entry.SetAttribute('path', $jarPath)
            $root.AppendChild($entry) | Out-Null
            $changed = $true
        }
    }
    if ($changed) {
        $classpathXml.Save($classpathFile)
        Write-Host "  OK   .classpath (registered Drivers jars for the IDE editor)"
    }
}

Write-Host ""
Write-Host "Install complete." -ForegroundColor Green
Write-Host "Next steps:"
Write-Host "  1. Reopen (or refresh) the project in Katalon Studio."
Write-Host "  2. Run any Test Suite as usual - no changes needed to existing tests."
Write-Host "  3. Look for '[Allure]' lines in the console, and an allure-results/ folder afterwards."
Write-Host "  4. View it: npm install -g allure-commandline; allure serve allure-results"
