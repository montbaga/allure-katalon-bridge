<#
.SYNOPSIS
    Interactive, no-typing wrapper around uninstall.ps1.

    Launched by double-clicking Uninstall.bat. Not meant to be run directly
    with arguments - see uninstall.ps1 for the scriptable/CI entry point.
#>

Add-Type -AssemblyName System.Windows.Forms

function Show-Info($message) {
    [System.Windows.Forms.MessageBox]::Show(
        $message, 'Allure-Katalon Bridge',
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
}

function Show-Warning($message) {
    [System.Windows.Forms.MessageBox]::Show(
        $message, 'Allure-Katalon Bridge',
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Warning) | Out-Null
}

$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = "Select the Katalon Studio project to remove the Allure-Katalon Bridge from"
$dialog.ShowNewFolderButton = $false

Write-Host "Waiting for folder selection ..."
$result = $dialog.ShowDialog()

if ($result -ne [System.Windows.Forms.DialogResult]::OK) {
    Write-Host "Cancelled - no folder selected."
    exit 0
}

$projectPath = $dialog.SelectedPath

$manifestPath = Join-Path $projectPath '.allure-bridge\manifest.txt'
if (-not (Test-Path $manifestPath)) {
    Show-Warning "No Allure-Katalon Bridge installation found in:`n$projectPath`n`n(No .allure-bridge\manifest.txt - it may not be installed there, or was installed by copying files manually instead of running the installer.)"
    exit 1
}

$confirm = [System.Windows.Forms.MessageBox]::Show(
    "Remove the Allure-Katalon Bridge from:`n$projectPath`n`nYour allure.properties/categories.json and any generated allure-results will be kept. Continue?",
    'Allure-Katalon Bridge', [System.Windows.Forms.MessageBoxButtons]::YesNo,
    [System.Windows.Forms.MessageBoxIcon]::Question)

if ($confirm -ne [System.Windows.Forms.DialogResult]::Yes) {
    Write-Host "Cancelled."
    exit 0
}

Write-Host "Uninstalling from: $projectPath"
Write-Host ""

try {
    & (Join-Path $PSScriptRoot 'uninstall.ps1') -ProjectPath $projectPath
}
catch {
    Show-Warning "Uninstall failed:`n`n$($_.Exception.Message)`n`nSee the console window for full details."
    exit 1
}

Show-Info "Allure-Katalon Bridge removed from:`n$projectPath"
