param(
    [string]$Go = "go",
    [string]$DotNet = "dotnet",
    [string]$Python = "python",
    [string]$NugetSource = "",
    [ValidateSet('Both', 'WithRuntime', 'NoRuntime')][string]$Flavor = 'Both',
    [switch]$SkipAndroidBuild,
    [string]$AppApk = "",
    [string]$BridgeApk = "",
    [string]$NetworkBridgeApk = "",
    [string]$AdbZip = ""
)
$ErrorActionPreference = "Stop"
$installerRoot = Split-Path $PSScriptRoot -Parent
$projectRoot = Split-Path $installerRoot -Parent
if ($Go -eq 'go' -and -not (Get-Command $Go -ErrorAction SilentlyContinue)) {
    $localGo = Join-Path $installerRoot '.build-tools\go\bin\go.exe'
    if (Test-Path -LiteralPath $localGo) { $Go = $localGo }
}
if ([bool]$AppApk -ne [bool]$BridgeApk) { throw 'AppApk and BridgeApk must be provided together.' }
if ($SkipAndroidBuild -and $AppApk) { throw 'Use either SkipAndroidBuild or explicit APK paths.' }
# Resolve caller-relative paths before changing the working directory.
if ($AppApk) {
    if (-not $NetworkBridgeApk) { throw 'Explicit APK builds also require NetworkBridgeApk.' }
    $AppApk = (Resolve-Path -LiteralPath $AppApk).Path
    $BridgeApk = (Resolve-Path -LiteralPath $BridgeApk).Path
    $NetworkBridgeApk = (Resolve-Path -LiteralPath $NetworkBridgeApk).Path
}
if ($AdbZip) { $AdbZip = (Resolve-Path -LiteralPath $AdbZip).Path }
if (-not $SkipAndroidBuild -and -not $AppApk) {
    Push-Location $projectRoot
    try {
        Write-Output 'Building PTV and HomeBridge APKs...'
        & .\gradlew.bat :app:assembleDebug :homebridge:assembleDebug :networkbridge:assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Android build failed; Windows packaging stopped.' }
    } finally { Pop-Location }
    $AppApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $BridgeApk = Join-Path $projectRoot 'homebridge\build\outputs\apk\debug\homebridge-debug.apk'
    $NetworkBridgeApk = Join-Path $projectRoot 'networkbridge\build\outputs\apk\debug\networkbridge-debug.apk'
}
if ($SkipAndroidBuild) {
    Write-Output 'Skipping Android build; reusing APKs already in installer/payload.'
    $AppApk = Join-Path $installerRoot 'payload\PTV.apk'
    $BridgeApk = Join-Path $installerRoot 'payload\HomeBridge.apk'
    $NetworkBridgeApk = Join-Path $installerRoot 'payload\NetworkBridge.apk'
}
Push-Location $installerRoot
try {
    $prepare = @((Join-Path $installerRoot 'prepare_payload.py'), '--go', $Go, '--app-apk', $AppApk, '--bridge-apk', $BridgeApk, '--network-bridge-apk', $NetworkBridgeApk)
    if ($AdbZip) { $prepare += @('--adb-zip', $AdbZip) }
    & $Python @prepare
    if ($LASTEXITCODE -ne 0) { throw 'Payload preparation failed; Windows packaging stopped.' }
    & $Go test ./...
    if ($LASTEXITCODE -ne 0) { throw "Go tests failed" }
    & $Go vet ./...
    if ($LASTEXITCODE -ne 0) { throw "Go checks failed" }
    & $Go build -trimpath '-ldflags=-s -w' -o native\ptv-backend.exe .
    if ($LASTEXITCODE -ne 0) { throw "Backend build failed" }
    & (Join-Path $PSScriptRoot 'publish.ps1') -DotNet $DotNet -NugetSource $NugetSource -Flavor $Flavor
} finally { Pop-Location }
