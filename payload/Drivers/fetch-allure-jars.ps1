<#
.SYNOPSIS
    Downloads the two jars the Allure-Katalon Bridge needs into this Drivers folder.

.DESCRIPTION
    Some enterprises don't allow binary blobs in source control. If that's you,
    delete the two allure-*.jar files from this folder, add "Drivers/*.jar" to
    .gitignore, and run this script as a setup/CI step instead - it downloads
    the exact same, checksum-verified jars from Maven Central.

    Only these two jars are needed. allure-java-commons shades its own Jackson
    dependency (relocated under io.qameta.allure.internal.shadowed.jackson.*),
    so it cannot collide with Katalon's bundled Jackson. slf4j-api is not
    downloaded because Katalon Studio already ships slf4j-api on the project
    classpath (see .classpath) at a version that satisfies allure-java-commons'
    requirement.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File Drivers/fetch-allure-jars.ps1
#>

$ErrorActionPreference = 'Stop'

$version = '2.35.4'
$base = "https://repo1.maven.org/maven2/io/qameta/allure"
$targetDir = $PSScriptRoot

$artifacts = @(
    @{ Name = 'allure-java-commons'; Sha1 = '1db09953779036377d466b4f1a9c304a1c0f3023' },
    @{ Name = 'allure-model';        Sha1 = '3bc5d455b65e94ba7e3253d0c7cfd90d0b0e12c5' }
)

foreach ($artifact in $artifacts) {
    $name = $artifact.Name
    $jarFile = Join-Path $targetDir "$name-$version.jar"
    $url = "$base/$name/$version/$name-$version.jar"

    Write-Host "Downloading $name $version ..."
    Invoke-WebRequest -Uri $url -OutFile $jarFile -UseBasicParsing

    $actualSha1 = (Get-FileHash -Path $jarFile -Algorithm SHA1).Hash.ToLower()
    if ($actualSha1 -ne $artifact.Sha1) {
        Remove-Item $jarFile -Force
        throw "SHA1 mismatch for $name $version. Expected $($artifact.Sha1), got $actualSha1. Aborting."
    }
    Write-Host "  OK  $jarFile (sha1 verified)"
}

Write-Host "Done. Restart Katalon Studio (or re-open the project) so it picks up the new Drivers/*.jar files."
