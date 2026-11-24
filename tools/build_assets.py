#!/usr/bin/env python3
"""Package pruned donor resources and compact fonts separately from executable code."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = Path(os.environ.get('HYPERBAR_PRIVATE_ROOT', ROOT / 'private-inputs'))
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

DYNAMIC_PATTERNS = {
    'plugin-res.apk': [
        r'^dynamic_big_island_', r'^dynamic_island_', r'^focus_notification_', r'^island_',
        r'^charger_', r'^power_save', r'^flash_light', r'^mi_flashlight', r'^calling_light',
        r'^music_wave', r'^music_pause', r'^voice_wave', r'^alarm_small', r'^alarm_big',
        r'^hourglass', r'^mark_timing', r'^mark_timer', r'^double_charge', r'^ic_music_note',
        r'^pause_timer', r'^restart_timer', r'^record_end', r'^hangup', r'^speaker',
        r'^close_v3', r'^done_v3', r'^end_v3', r'^later_v3', r'^pause$', r'^restart$',
        r'^aon$', r'^lottie_(disturb|silent|quick_scan)',
        r'^miuix_appcompat_hyper_progress', r'^text_shade_width', r'^effect_view_margin',
        r'^small_island_width', r'^title_color', r'^content_color',
    ],
    'call-res.apk': [
        r'^call_focus_', r'^ic_focus_', r'^incall_focus_',
    ],
    'systemui-res.apk': [
        r'^miui_media_session_island', r'^ic_media_(prev|next|play_button|pause_button|seamless)',
        r'^media_', r'^album_art', r'^seamless_',
    ],
}

def collect_code_resource_literals():
    refs = set()
    for folder in (ROOT / 'src', ROOT / 'tests'):
        if not folder.is_dir():
            continue
        for java_file in folder.rglob('*.java'):
            text = java_file.read_text(errors='ignore')
            for match in re.finditer(r'"([a-z][a-z0-9_]{2,})"', text):
                refs.add(match.group(1))
    return refs

def compute_reachable_files(carrier, apk_path, code_literals):
    """Compute exact 2-pass transitive closure of res/* files via aapt2; returns None to fall back to all res/."""
    if os.environ.get('HYPERBAR_FULL_ASSETS') == '1':
        return None
    try:
        dump = subprocess.check_output(
            ['aapt2', 'dump', 'resources', str(apk_path)],
            text=True, stderr=subprocess.DEVNULL
        )
    except Exception:
        return None

    lines = dump.splitlines()
    id_to_files = {}
    id_refs = {}
    name_to_ids = {}
    cur_id = None
    for line in lines:
        m = re.match(r'\s*resource (0x[0-9a-f]+) (\w+)/(\S+)', line)
        if m:
            cur_id = int(m.group(1), 16)
            rname = m.group(3)
            id_to_files[cur_id] = set()
            id_refs.setdefault(cur_id, set())
            name_to_ids.setdefault(rname, set()).add(cur_id)
        elif cur_id is not None:
            fm = re.search(r'\(file\)\s+(\S+)', line)
            if fm:
                id_to_files[cur_id].add(fm.group(1))
    cur_id = None
    for line in lines:
        m = re.match(r'\s*resource (0x[0-9a-f]+) ', line)
        if m:
            cur_id = int(m.group(1), 16)
        elif cur_id is not None:
            for hex_ref in re.findall(r'0x7f[0-9a-f]{6}', line):
                id_refs[cur_id].add(int(hex_ref, 16))
            for rm in re.finditer(r'(?:@|parent=)(\w+)/([\w.$-]+)', line):
                rn = rm.group(2)
                if rn in name_to_ids:
                    id_refs[cur_id].update(name_to_ids[rn])

    patterns = [re.compile(p) for p in DYNAMIC_PATTERNS.get(carrier, ())]
    queue = []
    for rname, rids in name_to_ids.items():
        if rname in code_literals or any(p.search(rname) for p in patterns):
            queue.extend(rids)

    kept_files = set()
    visited = set()
    with zipfile.ZipFile(apk_path) as zf:
        names = set(zf.namelist())
        while queue:
            rid = queue.pop()
            if rid in visited or rid not in id_to_files:
                continue
            visited.add(rid)
            for ref in id_refs.get(rid, ()):
                if ref not in visited:
                    queue.append(ref)
            for fpath in id_to_files[rid]:
                if fpath in kept_files or fpath not in names:
                    continue
                kept_files.add(fpath)
                if fpath.endswith('.xml'):
                    data = zf.read(fpath)
                    for off in range(0, len(data) - 3, 4):
                        val = struct.unpack_from('<I', data, off)[0]
                        if (val & 0xff000000) == 0x7f000000 and val in id_to_files and val not in visited:
                            queue.append(val)
    return kept_files if kept_files else None

def prepare_compact_font(src_vf, src_latin, out_path):
    """Subset MiSansVF.ttf (~20MB -> ~0.4MB) keeping Latin/Cyrillic/Greek/digits/symbols/tnum + wght axis;
    Android system fallback handles CJK/Arabic/Indic/Emoji. Falls back cleanly if fontTools is absent."""
    if os.environ.get('HYPERBAR_FULL_FONTS') == '1':
        shutil.copyfile(src_vf, out_path)
        return 'full'
    try:
        from fontTools import subset
        unicodes = (
            'U+0020-024F,U+02B0-02FF,U+0370-052F,'
            'U+1E00-27BF,U+FE00-FE0F,U+FF00-FFEF'
        )
        options = subset.Options()
        options.layout_features = ['*']
        options.glyph_names = False
        options.symbol_cmap = True
        options.name_IDs = ['*']
        options.name_legacy = True
        options.name_languages = ['*']
        options.hinting = False
        font = subset.load_font(str(src_vf), options)
        subsetter = subset.Subsetter(options=options)
        subsetter.populate(unicodes=subset.parse_unicodes(unicodes))
        subsetter.subset(font)
        subset.save_font(font, str(out_path), options)
        font.close()
        if out_path.is_file() and out_path.stat().st_size > 20_000:
            return 'subset'
    except Exception:
        pass
    if src_latin.is_file():
        shutil.copyfile(src_latin, out_path)
        return 'latin-fallback'
    shutil.copyfile(src_vf, out_path)
    return 'full-fallback'

code_literals = collect_code_resource_literals()
records = []
for filename, original, expected in DONORS:
    if digest(original) != expected:
        raise RuntimeError('Donor identity changed: ' + str(original))
    reachable = compute_reachable_files(filename, original, code_literals)
    destination = BUILD / filename
    members = []
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(destination, 'w', compresslevel=9) as output:
        if source.testzip() is not None:
            raise RuntimeError('Corrupt source APK: ' + str(original))
        for item in source.infolist():
            if item.is_dir():
                continue
            if item.filename == 'resources.arsc':
                keep = True
            elif reachable is not None:
                keep = item.filename in reachable or (
                    filename == 'plugin-res.apk' and item.filename.startswith('assets/') and item.filename.endswith('.json')
                )
            else:
                keep = item.filename.startswith(('res/', 'assets/')) and not item.filename.startswith('assets/dexopt/')
            if not keep:
                continue
            data = source.read(item)
            info = zipfile.ZipInfo(item.filename, (2026, 11, 22, 12, 0, 0))
            info.compress_type = zipfile.ZIP_STORED if item.filename == 'resources.arsc' else zipfile.ZIP_DEFLATED
            if info.compress_type == zipfile.ZIP_STORED:
                offset = output.fp.tell() + 30 + len(info.filename.encode('utf-8'))
                padding = (4 - (offset + 6) % 4) % 4
                info.extra = struct.pack('<HHH', 0xd935, padding + 2, 4) + bytes(padding)
            output.writestr(info, data, compresslevel=9 if info.compress_type == zipfile.ZIP_DEFLATED else None)
            members.append(item.filename)
    with zipfile.ZipFile(destination) as checked:
        if checked.testzip() is not None or any(name.endswith(('.dex', '.so')) for name in checked.namelist()):
            raise RuntimeError('Resource carrier includes code or failed integrity: ' + filename)
    records.append({
        'carrier': filename, 'source': str(original), 'source_sha256': expected,
        'sha256': digest(destination), 'bytes': destination.stat().st_size,
        'pruned': reachable is not None, 'member_count': len(members),
        'members': members, 'application_code_included': False
    })

fonts = PRIVATE / 'fonts'
compact_vf = BUILD / 'MiSansVF.ttf'
vf_mode = prepare_compact_font(fonts / 'MiSansVF.ttf', fonts / 'MiSansLatinVF.ttf', compact_vf)

extra = {
    'fonts/MiSansVF.ttf': compact_vf,
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
with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for row in records:
        output.write(BUILD / row['carrier'], 'assets/hyperos/' + row['carrier'])
    for name, source in extra.items():
        output.write(source, 'assets/hyperos/' + name)
    for name in ['light_foreground.agsl', 'light_background.agsl', 'light_texture.agsl']:
        glow_file = PRIVATE / 'glow' / name
        if glow_file.is_file():
            output.write(glow_file, 'assets/hyperbar/glow/' + name)
with zipfile.ZipFile(bundle) as checked:
    if checked.testzip() is not None:
        raise RuntimeError('Asset bundle integrity failure')
report = {
    'carriers': records,
    'font_mode': vf_mode,
    'extra_assets': {name: {'source': str(path), 'sha256': digest(path), 'bytes': path.stat().st_size} for name, path in extra.items()},
    'bundle_sha256': digest(bundle), 'bundle_bytes': bundle.stat().st_size,
    'scope': 'Pruned resource carriers, compact fonts and verified isolated Lottie library runtime; no donor application classes'
}
(BUILD / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'Packaged HyperOS resources ({vf_mode}): {bundle} {bundle.stat().st_size} bytes')
