param(
    [string]$DotNet = "dotnet",
    [string]$NugetSource = "",
    [ValidateSet('Both', 'WithRuntime', 'NoRuntime')][string]$Flavor = 'Both'
)
$ErrorActionPreference = 'Stop'
$installerRoot = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $installerRoot 'dist'
$publishRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'publish'))
$project = Join-Path $PSScriptRoot 'PTVInstaller.csproj'
$appName = 'PTV-Installer-Windows-x64'
$flavors = @('WithRuntime', 'NoRuntime')
if ($Flavor -ne 'Both') { $flavors = @($Flavor) }
New-Item -ItemType Directory -Path $dist -Force | Out-Null
foreach ($edition in $flavors) {
    # A running preview can lock the previous publish directory. Stage each build
    # separately so rebuilding does not require closing the user's assistant.
    $stageName = $edition + '-' + [Guid]::NewGuid().ToString('N')
    $output = [IO.Path]::GetFullPath((Join-Path $publishRoot $stageName))
    $properties = @("-p:InstallerFlavor=$edition", "-p:BaseIntermediateOutputPath=obj/$edition/", "-p:BaseOutputPath=bin/$edition/")
    $restore = @('restore', $project, '--nologo') + $properties
    if ($NugetSource) { $restore += @('--source', $NugetSource) }
    & $DotNet @restore
    if ($LASTEXITCODE -ne 0) { throw "$edition dependency restore failed" }
    & $DotNet publish $project -c Release -r win-x64 -o $output --no-restore --nologo @properties
    if ($LASTEXITCODE -ne 0) { throw "$edition publish failed" }
    if ($edition -eq 'WithRuntime') {
        $artifact = Join-Path $dist "$appName-WithRuntime.exe"
        Copy-Item -LiteralPath (Join-Path $output "$appName.exe") -Destination $artifact -Force
    } else {
        $config = Get-Content -LiteralPath (Join-Path $output "$appName.runtimeconfig.json") -Raw | ConvertFrom-Json
        if (-not $config.runtimeOptions.framework -and -not $config.runtimeOptions.frameworks) { throw 'Light package is not framework-dependent' }
        foreach ($runtimeFile in @('coreclr.dll', 'hostfxr.dll', 'System.Private.CoreLib.dll', 'Microsoft.ui.xaml.dll', 'onnxruntime.dll', 'DirectML.dll')) {
            if (Test-Path -LiteralPath (Join-Path $output $runtimeFile)) { throw "Light package unexpectedly includes $runtimeFile" }
        }
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'NO-RUNTIME-README.txt') -Destination (Join-Path $output 'README.txt') -Force
        $artifact = Join-Path $dist "$appName-NoRuntime.zip"
        # Exclude debug symbols, but keep every runtime-resolved app dependency.
        $releaseFiles = Get-ChildItem -LiteralPath $output | Where-Object { $_.Extension -ne '.pdb' }
        Compress-Archive -LiteralPath $releaseFiles.FullName -DestinationPath $artifact -CompressionLevel Optimal -Force
    }
    Write-Output ("{0}: {1:N2} MiB" -f $artifact, ((Get-Item -LiteralPath $artifact).Length / 1MB))
    # Only remove the uniquely named staging directory created by this build.
    if ((Split-Path $output -Parent) -ne $publishRoot -or (Split-Path $output -Leaf) -ne $stageName -or $stageName -notmatch '^(WithRuntime|NoRuntime)-[a-f0-9]{32}$') { throw 'Unsafe staging directory' }
    Remove-Item -LiteralPath $output -Recurse -Force
}
$artifacts = @("$appName-WithRuntime.exe", "$appName-NoRuntime.zip")
$hashes = foreach ($name in $artifacts) {
    $file = Join-Path $dist $name
    if (Test-Path -LiteralPath $file) { (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLower() + '  ' + $name }
}
$hashes | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding ascii
