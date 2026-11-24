#!/usr/bin/env python3
"""Package immutable donor resources separately from executable application code."""
from pathlib import Path
import hashlib
import json
import os
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = Path(os.environ.get('HYPERBAR_PRIVATE_ROOT',ROOT/'private-inputs'))
BUILD = ROOT / 'build/assets'
BUILD.mkdir(parents=True, exist_ok=True)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

DONORS = [
    ('plugin-res.apk', PRIVATE / 'MIUISystemUIPlugin.apk',
     'b76d60fee59b83b9a1edaddb694f30b274a6491281dd87fa82b995c85df56632'),
    ('call-res.apk', PRIVATE / 'InCallUI.apk',
     '45dff33a8d6ce5b942a98de696dbcfd01039895f8b7e5cb729ed92e7d44b9e43'),
    ('systemui-res.apk', PRIVATE / 'MiuiSystemUI.apk',
     'cf42fe88504a850df308616e0d18ec8fad43cb0d1335481c9f3bffd7fe7f628d'),
]

records = []
for filename, original, expected in DONORS:
    if digest(original) != expected:
        raise RuntimeError('Donor identity changed: ' + str(original))
    destination = BUILD / filename
    members = []
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(destination, 'w') as output:
        if source.testzip() is not None:
            raise RuntimeError('Corrupt source APK: ' + str(original))
        for item in source.infolist():
            if item.is_dir():
                continue
            if not (item.filename == 'resources.arsc'
                    or item.filename.startswith(('res/', 'assets/'))):
                continue
            # Profile data describes donor execution and is irrelevant here.
            if item.filename.startswith('assets/dexopt/'):
                continue
            data = source.read(item)
            info = zipfile.ZipInfo(item.filename, (2026, 11, 22, 12, 0, 0))
            info.compress_type = zipfile.ZIP_STORED if item.filename == 'resources.arsc' else zipfile.ZIP_DEFLATED
            if info.compress_type == zipfile.ZIP_STORED:
                offset = output.fp.tell() + 30 + len(info.filename.encode('utf-8'))
                padding = (4 - (offset + 6) % 4) % 4
                info.extra = struct.pack('<HHH',0xd935,padding+2,4) + bytes(padding)
            output.writestr(info, data)
            members.append(item.filename)
    with zipfile.ZipFile(destination) as checked:
        if checked.testzip() is not None or any(name.endswith(('.dex', '.so')) for name in checked.namelist()):
            raise RuntimeError('Resource carrier includes code or failed integrity: ' + filename)
    records.append({'carrier': filename, 'source': str(original), 'source_sha256': expected,
                    'sha256': digest(destination), 'bytes': destination.stat().st_size,
                    'members': members, 'application_code_included': False})

fonts = PRIVATE / 'fonts'
extra = {
    'fonts/MiSansVF.ttf': fonts / 'MiSansVF.ttf',
    'fonts/MiSansLatinVF.ttf': fonts / 'MiSansLatinVF.ttf',
    'fonts/MiSansCondensed2T.ttf': fonts / 'MiSansCondensed2T.ttf',
    'fonts/mi_fonts_customization.xml': fonts / 'mi_fonts_customization.xml',
    'material_blend.sksl': PRIVATE / 'material_blend.sksl',
    'glass_normal.sksl': PRIVATE / 'glass_normal.sksl',
    'glass_material.sksl': PRIVATE / 'glass_material.sksl',
    'glass_press_mask.sksl': PRIVATE / 'glass_press_mask.sksl',
    'smooth_corner_mips.bin': PRIVATE / 'smooth_corner_mips.bin',
    'lottie-runtime.dex': PRIVATE / 'lottie-runtime.dex',
}
bundle = ROOT / 'build/hyperos-assets.zip'
with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_DEFLATED) as output:
    for row in records:
        output.write(BUILD / row['carrier'], 'assets/hyperos/' + row['carrier'])
    for name, source in extra.items():
        output.write(source, 'assets/hyperos/' + name)
    for name in ['light_foreground.agsl','light_background.agsl','light_texture.agsl']:
        output.write(PRIVATE / 'glow' / name,'assets/hyperbar/glow/' + name)
with zipfile.ZipFile(bundle) as checked:
    if checked.testzip() is not None:
        raise RuntimeError('Asset bundle integrity failure')
report = {'carriers': records,
          'extra_assets': {name: {'source': str(path), 'sha256': digest(path)} for name, path in extra.items()},
          'bundle_sha256': digest(bundle), 'bundle_bytes': bundle.stat().st_size,
          'scope': 'Resource data and verified isolated Lottie library runtime; no donor application classes'}
(BUILD / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
print('Packaged HyperOS resources:', bundle, bundle.stat().st_size, 'bytes')
