#!/usr/bin/env python3
"""Compile the public Java source without requiring vendor assets or an APK build."""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
API = Path(os.environ.get('HYPERBAR_ANDROID_JAR', ROOT / 'tools/android.jar'))
BUILD = ROOT / 'build/source-check'
if not API.is_file():
    raise SystemExit('Set HYPERBAR_ANDROID_JAR to an Android API 35+ platform JAR')

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

def fresh(path):
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)

BUILD.mkdir(parents=True, exist_ok=True)
generated = BUILD / 'generated'
stubs = BUILD / 'stubs'
classes = BUILD / 'classes'
for path in [generated, stubs, classes]:
    fresh(path)
run('aapt2', 'compile', '--dir', ROOT / 'app/res', '-o', BUILD / 'resources.zip')
run('aapt2', 'link', '-I', API, '--manifest', ROOT / 'app/AndroidManifest.xml',
    '--auto-add-overlay', '--java', generated, '-o', BUILD / 'resources.apk', BUILD / 'resources.zip')
run('javac', '--release', '8', '-Xlint:-options', '-cp', API, '-d', stubs,
    *sorted((ROOT / 'compile-stubs/de').rglob('*.java')))
run('jar', 'cf', BUILD / 'xposed-api.jar', '-C', stubs, '.')
sources = [*sorted((ROOT / 'src').rglob('*.java')), *sorted(generated.rglob('*.java'))]
run('javac', '--release', '8', '-Xlint:-options', '-cp', str(API) + ':' + str(BUILD / 'xposed-api.jar'),
    '-d', classes, *sources)
run('jar', 'cf', BUILD / 'app.jar', '-C', classes, '.')
print('Java source compilation passed:', len(sources), 'files')
