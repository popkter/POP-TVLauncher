#!/usr/bin/env python3
"""Build the single-file Windows installer from explicit APKs and official ADB ZIP."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--go', default='go')
parser.add_argument('--adb-zip', required=True, type=Path)
parser.add_argument('--app-apk', required=True, type=Path)
parser.add_argument('--bridge-apk', required=True, type=Path)
args = parser.parse_args()
payload = root / 'payload'
payload.mkdir(exist_ok=True)
with zipfile.ZipFile(args.adb_zip) as archive:
    for name in ['adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt', 'source.properties']:
        (payload / name).write_bytes(archive.read('platform-tools/' + name))
shutil.copyfile(args.app_apk, payload / 'PTV.apk')
shutil.copyfile(args.bridge_apk, payload / 'HomeBridge.apk')
shutil.copyfile(root.parent / 'LICENSE', payload / 'PTV-LICENSE.txt')
shutil.copyfile(root.parent / 'THIRD_PARTY_NOTICES.md', payload / 'PTV-THIRD-PARTY-NOTICES.txt')
goroot = Path(subprocess.check_output([args.go, 'env', 'GOROOT'], text=True).strip())
shutil.copyfile(goroot / 'LICENSE', payload / 'Go-LICENSE.txt')
checksums = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(payload.iterdir()) if p.is_file()}
(root / 'payload-checksums.json').write_text(json.dumps(checksums, indent=2) + '\n')
subprocess.run([sys.executable, str(root / 'make_resources.py')], check=True)
subprocess.run([args.go, 'test', './...'], cwd=root, check=True)
subprocess.run([args.go, 'vet', './...'], cwd=root, check=True)
env = dict(os.environ, GOOS='windows', GOARCH='amd64', CGO_ENABLED='0')
out = root / 'dist' / 'PTV-Installer-Windows-x64.exe'
out.parent.mkdir(exist_ok=True)
subprocess.run([args.go, 'build', '-trimpath', '-ldflags=-s -w -H=windowsgui', '-o', str(out), '.'], cwd=root, env=env, check=True)
digest = hashlib.sha256(out.read_bytes()).hexdigest()
(out.parent / 'SHA256SUMS.txt').write_text(digest + '  ' + out.name + '\n')
print(f'{out}\n{out.stat().st_size / 1024 / 1024:.1f} MiB\nSHA256 {digest}')
