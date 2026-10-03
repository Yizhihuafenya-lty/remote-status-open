# Build the reconstructed V4/V6 APK.
#
# Default output is the minified release build. It is signed with the debug key
# for local testing; configure a real release keystore before distribution.
#
# The build runs from an ASCII mirror because Android Gradle tooling can be
# sensitive to non-ASCII checkout paths. The source checkout stays unchanged.

[CmdletBinding()]
param(
    [string]$Mirror = (Join-Path ([IO.Path]::GetTempPath()) 'remote-status-open-build'),
    [switch]$DebugBuild,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$source = $PSScriptRoot

Write-Host "Source : $source"
Write-Host "Mirror : $Mirror"

robocopy $source $Mirror /MIR /XD build .gradle .kotlin /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with exit code $LASTEXITCODE" }

Push-Location $Mirror
try {
    if (-not $SkipTests) {
        & .\gradlew.bat :app:testDebugUnitTest
        if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }
    }

    $task = if ($DebugBuild) { ':app:assembleDebug' } else { ':app:assembleRelease' }
    & .\gradlew.bat $task
    if ($LASTEXITCODE -ne 0) { throw "$task failed" }
} finally {
    Pop-Location
}

$variant = if ($DebugBuild) { 'debug\app-debug.apk' } else { 'release\app-release.apk' }
$built = Join-Path $Mirror "app\build\outputs\apk\$variant"
$dist = Join-Path $source 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$output = Join-Path $dist 'remote-status-open.apk'
Copy-Item $built $output -Force

$apk = Get-Item $built
Write-Host ("APK: {0} ({1:N0} bytes)" -f $apk.FullName, $apk.Length)
Write-Host "Copied to $output"
