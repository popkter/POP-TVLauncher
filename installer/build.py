#!/usr/bin/env python3
"""Build Android APKs and both Windows editions through the shared PowerShell pipeline."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--go', default='go')
parser.add_argument('--dotnet', default='dotnet')
parser.add_argument('--flavor', choices=['Both', 'WithRuntime', 'NoRuntime'], default='Both')
parser.add_argument('--nuget-source', help='Optional offline NuGet package feed')
parser.add_argument('--adb-zip', type=Path, help='Official ADB ZIP; otherwise reuse existing payload ADB')
parser.add_argument('--app-apk', type=Path, help='Use prebuilt APKs instead of compiling Android')
parser.add_argument('--bridge-apk', type=Path)
parser.add_argument('--network-bridge-apk', type=Path)
parser.add_argument('--skip-android-build', action='store_true', help='Reuse APKs already in installer/payload')
args = parser.parse_args()
if sys.platform != 'win32':
    parser.error('Build on Windows with Android SDK, JDK, Go and .NET 10 SDK.')
if bool(args.app_apk) != bool(args.bridge_apk):
    parser.error('--app-apk and --bridge-apk must be provided together')
if args.app_apk and not args.network_bridge_apk:
    parser.error('Explicit APK builds also require --network-bridge-apk')
if args.skip_android_build and args.app_apk:
    parser.error('Use either --skip-android-build or explicit APK paths')
shell = shutil.which('pwsh') or shutil.which('powershell')
if not shell:
    raise SystemExit('PowerShell is required to build Windows releases.')
command = [shell, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(root / 'native' / 'build.ps1'),
           '-Go', args.go, '-DotNet', args.dotnet, '-Python', sys.executable, '-Flavor', args.flavor]
for option, value in [('-NugetSource', args.nuget_source), ('-AdbZip', args.adb_zip),
                      ('-AppApk', args.app_apk), ('-BridgeApk', args.bridge_apk), ('-NetworkBridgeApk', args.network_bridge_apk)]:
    if value:
        command += [option, str(value)]
if args.skip_android_build:
    command += ['-SkipAndroidBuild']
subprocess.run(command, check=True)
