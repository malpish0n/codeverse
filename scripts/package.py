#!/usr/bin/env python3
"""Package the verified installDist with its own JVM. Run on the target OS."""
import argparse
import os
import pathlib
import platform
import subprocess
import hashlib

parser = argparse.ArgumentParser()
parser.add_argument('--type', default='app-image')
parser.add_argument('--dest', default='dist')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
source = root / 'desktop-ui/build/install/desktop-ui/lib'
dest = pathlib.Path(args.dest).resolve()
dest.mkdir(parents=True, exist_ok=True)
command = ['jpackage', '--type', args.type, '--name', 'CODEVERSE', '--input', str(source),
           '--main-jar', 'desktop-ui-0.1.0.jar', '--main-class', 'dev.codeverse.desktop.Launcher',
           '--dest', str(dest), '--app-version', '1.0.0', '--vendor', 'CODEVERSE',
           '--description', 'Offline Git history explorer / preview 0.1.0',
           '--java-options', '--enable-native-access=ALL-UNNAMED']
if platform.system() == 'Darwin':
    command += ['--java-options', '-XstartOnFirstThread', '--mac-package-identifier', 'dev.codeverse.desktop']
if platform.system() == 'Windows' and args.type != 'app-image':
    command += ['--win-menu', '--win-shortcut', '--win-dir-chooser']
subprocess.run(command, check=True)
if platform.system() == 'Darwin' and args.type == 'app-image':
    app = dest / 'CODEVERSE.app'
    subprocess.run(['xattr', '-d', 'com.apple.FinderInfo', str(app)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    subprocess.run(['codesign', '--force', '--deep', '--sign', '-', str(app)], check=True)
for file in dest.iterdir():
    if file.is_file() and file.suffix != '.sha256':
        digest = hashlib.file_digest(file.open('rb'), 'sha256').hexdigest()
        file.with_suffix(file.suffix + '.sha256').write_text(f'{digest}  {file.name}\n')
