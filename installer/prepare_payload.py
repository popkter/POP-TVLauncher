"""Refresh APKs, licenses and checksums before compiling the embedded Go payload."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--go', default='go')
parser.add_argument('--adb-zip', type=Path)
parser.add_argument('--app-apk', required=True, type=Path)
parser.add_argument('--bridge-apk', required=True, type=Path)
parser.add_argument('--network-bridge-apk', required=True, type=Path)
args = parser.parse_args()
payload = root / 'payload'
adb_files = ['adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt', 'source.properties']
# Validate both build outputs before replacing either payload APK.
for apk in (args.app_apk, args.bridge_apk, args.network_bridge_apk):
    with zipfile.ZipFile(apk) as archive:
        if 'AndroidManifest.xml' not in archive.namelist() or archive.testzip():
            raise SystemExit(f'Invalid APK: {apk}')
if not args.adb_zip:
    for name in adb_files:
        if not (payload / name).is_file():
            raise SystemExit(f'Missing payload/{name}; provide -AdbZip with the official platform-tools ZIP.')
payload.mkdir(exist_ok=True)
if args.adb_zip:
    with zipfile.ZipFile(args.adb_zip) as archive:
        adb_data = {name: archive.read('platform-tools/' + name) for name in adb_files}
    for name, data in adb_data.items():
        (payload / name).write_bytes(data)
for source, name in [(args.app_apk, 'PTV.apk'), (args.bridge_apk, 'HomeBridge.apk'), (args.network_bridge_apk, 'NetworkBridge.apk')]:
    target = payload / name
    if source.resolve() != target.resolve():
        shutil.copyfile(source, target)
    print(f'{name}: {hashlib.sha256(target.read_bytes()).hexdigest()}')
shutil.copyfile(root.parent / 'LICENSE', payload / 'PTV-LICENSE.txt')
shutil.copyfile(root.parent / 'THIRD_PARTY_NOTICES.md', payload / 'PTV-THIRD-PARTY-NOTICES.txt')
goroot = Path(subprocess.check_output([args.go, 'env', 'GOROOT'], text=True).strip())
shutil.copyfile(goroot / 'LICENSE', payload / 'Go-LICENSE.txt')
checksums = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(payload.iterdir()) if p.is_file()}
(root / 'payload-checksums.json').write_text(json.dumps(checksums, indent=2) + '\n', encoding='utf-8')
subprocess.run([sys.executable, str(root / 'make_resources.py')], check=True)
