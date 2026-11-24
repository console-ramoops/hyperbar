#!/usr/bin/env python3
"""Build the application entirely from Java/Rust sources and declared resources."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import struct
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
BUILD=ROOT/'build/source-app'
API=Path(os.environ.get('HYPERBAR_ANDROID_JAR',ROOT/'tools/android.jar'))
JADX=Path(os.environ.get('HYPERBAR_D8_JAR',ROOT/'tools/r8.jar'))
PRIVATE=Path(os.environ.get('HYPERBAR_PRIVATE_ROOT',ROOT/'private-inputs'))
dependencies=Path(os.environ.get('HYPERBAR_CONSTRAINT_DIR',PRIVATE/'constraint-library'))
libraries=[dependencies/'classes.jar',dependencies/'constraintlayout-core-1.1.0.jar',dependencies/'resource-bindings.jar']
for required in [API,JADX,*libraries,PRIVATE/'libMiMainColor.so']:
    if not required.is_file():raise SystemExit('Missing build input: '+str(required)+' (see README.md)')
BUILD.mkdir(parents=True,exist_ok=True)
def run(*args,**kwargs):return subprocess.run([str(x) for x in args],check=True,**kwargs)
def fresh(path):
    if path.exists():shutil.rmtree(path)
    path.mkdir(parents=True)
run('cargo','build','--release','--offline','--manifest-path',ROOT/'native/Cargo.toml',cwd=ROOT)
run('python',ROOT/'tools/build_assets.py')
java=BUILD/'resources-java';fresh(java)
run('aapt2','compile','--dir',ROOT/'app/res','-o',BUILD/'compiled-resources.zip')
run('aapt2','link','-I',API,'--manifest',ROOT/'app/AndroidManifest.xml','--auto-add-overlay',
    '--java',java,'-o',BUILD/'resources.apk',BUILD/'compiled-resources.zip')

stub_source=BUILD/'api-src';fresh(stub_source)
for source in (ROOT/'compile-stubs/de').rglob('*.java'):
    target=stub_source/source.relative_to(ROOT/'compile-stubs');target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
stub_classes=BUILD/'api-classes';fresh(stub_classes)
run('javac','--release','8','-Xlint:-options','-cp',API,'-d',stub_classes,*stub_source.rglob('*.java'))
run('jar','cf',BUILD/'xposed-api.jar','-C',stub_classes,'.')
classes=BUILD/'classes';fresh(classes)
sources=[*(ROOT/'src').rglob('*.java'),*java.rglob('*.java')]
run('javac','--release','8','-Xlint:-options','-cp',str(API)+':'+str(BUILD/'xposed-api.jar'),
    '-d',classes,*sources)
run('jar','cf',BUILD/'app.jar','-C',classes,'.')
dex=BUILD/'dex';fresh(dex)
run('java','-cp',JADX,'com.android.tools.r8.D8','--min-api','35','--lib',API,
    '--lib',BUILD/'xposed-api.jar','--output',dex,BUILD/'app.jar',*libraries)

def write(output,name,data,stored=False):
    info=zipfile.ZipInfo(name,(2026,11,22,12,0,0));info.compress_type=zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    if stored:
        alignment=16384 if name.endswith('.so') else 4
        offset=output.fp.tell()+30+len(name.encode('utf-8'));padding=(alignment-(offset+6)%alignment)%alignment
        info.extra=struct.pack('<HHH',0xd935,padding+2,alignment)+bytes(padding)
    output.writestr(info,data)
unsigned=BUILD/'HyperBar-source-unsigned.test-package'
with (zipfile.ZipFile(BUILD/'resources.apk') as resources,
    zipfile.ZipFile(ROOT/'build/hyperos-assets.zip') as assets,
    zipfile.ZipFile(unsigned,'w') as output):
    for item in resources.infolist():
        if not item.is_dir():write(output,item.filename,resources.read(item),item.filename=='resources.arsc')
    for file in (ROOT/'app/assets').rglob('*'):
        if file.is_file():write(output,'assets/'+str(file.relative_to(ROOT/'app/assets')),file.read_bytes())
    for file in dex.glob('*.dex'):write(output,file.name,file.read_bytes(),True)
    for item in assets.infolist():write(output,item.filename,assets.read(item))
    write(output,'lib/arm64-v8a/libhyperos_island.so',(ROOT/'native/target/release/libhyperos_island.so').read_bytes(),True)
    write(output,'lib/arm64-v8a/libMiMainColor.so',(PRIVATE/'libMiMainColor.so').read_bytes(),True)

signed=unsigned
keystore=os.environ.get('HYPERBAR_KEYSTORE')
if keystore:
    alias=os.environ.get('HYPERBAR_KEY_ALIAS')
    if not alias or not os.environ.get('HYPERBAR_KEYSTORE_PASSWORD'):
        raise SystemExit('Signing requires HYPERBAR_KEY_ALIAS and HYPERBAR_KEYSTORE_PASSWORD')
    signed=BUILD/'HyperBar-source-unreleased.test-package'
    environment=os.environ.copy()
    environment.setdefault('HYPERBAR_KEY_PASSWORD',environment['HYPERBAR_KEYSTORE_PASSWORD'])
    run('apksigner','sign','--ks',keystore,'--ks-key-alias',alias,
        '--ks-pass','env:HYPERBAR_KEYSTORE_PASSWORD','--key-pass','env:HYPERBAR_KEY_PASSWORD',
        '--v1-signing-enabled','false','--alignment-preserved','true','--lib-page-alignment','16384',
        '--out',signed,unsigned,env=environment)
    run('apksigner','verify','--verbose',signed)
raw=signed.read_bytes()
with zipfile.ZipFile(signed) as archive:
    if archive.testzip()!=None:raise RuntimeError('APK integrity failure')
    for item in archive.infolist():
        data=archive.read(item)
        if item.filename.endswith('.dex') and (data[:8]==b'dex\n041\0' or struct.unpack_from('<I',data,36)[0]!=112):raise RuntimeError('Unsupported dex header')
        if item.compress_type==zipfile.ZIP_STORED:
            names,extra=struct.unpack_from('<HH',raw,item.header_offset+26)
            offset=item.header_offset+30+names+extra;alignment=16384 if item.filename.endswith('.so') else 4
            if offset%alignment:raise RuntimeError('Unaligned '+item.filename)
report={'package':'com.alpha269.hyperisland','version':'0.1.0','version_code':1,'source_build':True,'signed':bool(keystore),
    'legacy_apk_code_included':False,'apk_sha256':hashlib.sha256(raw).hexdigest(),'bytes':len(raw),
    'state':'Source build prepared for runtime verification; not released',
    'source_sha256':{str(file.relative_to(ROOT)):hashlib.sha256(file.read_bytes()).hexdigest() for file in sources if file.is_relative_to(ROOT)}}
(BUILD/'validation.json').write_text(json.dumps(report,indent=2)+'\n')
print('Source-built application prepared for runtime verification:',signed)
