#!/data/data/com.termux/files/usr/bin/python
"""Keep the Android module library free of Termux compiler-injected RUNPATH."""
import os
import sys

original = sys.argv[1:]
output = original[original.index('-o') + 1] if '-o' in original else ''
if not ('-shared' in original and os.path.basename(output) == 'libhyperos_island.so'):
    os.execv('/data/data/com.termux/files/usr/bin/clang', ['clang'] + original)

args = []
for value in original:
    if value.startswith('-Wl,'):
        args.extend(value[4:].split(','))
    elif value in ('-nodefaultlibs', '-nostartfiles', '-nostdlib', '-pie', '-no-pie', '-pthread'):
        if value == '-pie':
            args.append('-pie')
    elif value.startswith(('-fuse-ld=', '-m', '--target=')):
        continue
    else:
        args.append(value)
args.extend(['-L/system/lib64', '-L/data/data/com.termux/files/usr/lib',
             '-soname', 'libhyperos_island.so'])
os.execv('/data/data/com.termux/files/usr/bin/ld.lld', ['ld.lld'] + args)
