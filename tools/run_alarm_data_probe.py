#!/usr/bin/env python3
"""Validate private alarm metadata in an owned Android ART process."""
from pathlib import Path
import os
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
API = Path(os.environ.get('HYPERBAR_ANDROID_JAR', ROOT / 'tools/android.jar'))
D8 = Path(os.environ.get('HYPERBAR_D8_JAR', ROOT / 'tools/r8.jar'))
BUILD = ROOT / 'build/alarm-data-probe'
for name in ['classes', 'dex']:
    (BUILD / name).mkdir(parents=True, exist_ok=True)

def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)

run('javac', '--release', '8', '-cp', API, '-d', BUILD / 'classes',
    ROOT / 'src/com/alpha269/hyperisland/island/HyperOsAlarmData.java', ROOT / 'tests/AlarmDataProbe.java')
run('jar', 'cf', BUILD / 'classes.jar', '-C', BUILD / 'classes', '.')
run('java', '-cp', D8, 'com.android.tools.r8.D8', '--min-api', '35', '--lib', API,
    '--output', BUILD / 'dex', BUILD / 'classes.jar')
run('jar', 'cf', BUILD / 'probe.jar', '-C', BUILD / 'dex', '.')
command = ['env', '-u', 'LD_PRELOAD', '-u', 'LD_LIBRARY_PATH', 'CLASSPATH=' + str(BUILD / 'probe.jar'),
           '/system/bin/app_process', '/system/bin', 'AlarmDataProbe']
run('su', '-c', shlex.join(command))
