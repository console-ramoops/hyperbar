from pathlib import Path
import subprocess,shutil,os,json,hashlib,sys
root=Path(__file__).resolve().parents[1]
p=root/'tests/android-root'
sdk=Path(os.environ.get('HYPERBAR_ANDROID_JAR',root/'tools/android.jar'))
jadx=Path(os.environ.get('HYPERBAR_D8_JAR',root/'tools/r8.jar'))
apk=Path(os.environ.get('HYPERBAR_PROBE_PACKAGE',root/'build/source-app/HyperBar-source-unreleased.test-package'))
if not apk.is_file():apk=root/'build/source-app/HyperBar-source-unsigned.test-package'
if not apk.is_file():raise SystemExit('Build an internal package first; see README.md')
(p/'inputs').mkdir(parents=True,exist_ok=True)
probeapk=p/'inputs/current-source.test-package';shutil.copyfile(apk,probeapk)
app=p/'inputs/current-source.jar';shutil.copyfile(root/'build/source-app/app.jar',app)
classes=p/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir()
subprocess.run(['javac','--release','8','-cp',f'{sdk}:{app}','-d',str(classes),*[str(source) for source in p.glob('*.java')]],check=True)
subprocess.run(['jar','cf',str(p/'probe-classes.jar'),'-C',str(classes),'.'],check=True)
dex=p/'dex';shutil.rmtree(dex,ignore_errors=True);dex.mkdir()
subprocess.run(['java','-cp',str(jadx),'com.android.tools.r8.D8','--min-api','35','--lib',str(sdk),'--lib',str(app),'--output',str(dex),str(p/'probe-classes.jar')],check=True)
subprocess.run(['jar','cf',str(p/'probe.jar'),'-C',str(dex),'.'],check=True)
output=p/(sys.argv[2] if len(sys.argv)>2 else 'rendered-fixed')
selection=sys.argv[1] if len(sys.argv)>1 else ''
mode=sys.argv[3] if len(sys.argv)>3 else ''
import shlex
command=['env','-u','LD_PRELOAD','-u','LD_LIBRARY_PATH','CLASSPATH='+str(probeapk)+':'+str(p/'probe.jar'),'/system/bin/app_process','/system/bin','com.alpha269.hyperisland.probe.RootViewIntegrationProbe',str(probeapk),str(output),selection,mode]
subprocess.run(['su','-c',shlex.join(command)],check=True)
subprocess.run(['su','-c',shlex.join(['chown','-R',str(os.getuid())+':'+str(os.getgid()),str(output)])],check=True)
if mode in ['effects','press','swipe','queue','torch']:
    report=output/(mode+'-validation.json');record=json.loads(report.read_text());record['apk_sha256']=hashlib.sha256(probeapk.read_bytes()).hexdigest()
    temporary=output/(mode+'-validation.tmp');temporary.write_text(json.dumps(record,indent=2)+'\n');temporary.replace(report);print(json.dumps(record,indent=2));sys.exit(0)
records=json.loads((output/'composed-findings.json').read_text())
print(json.dumps({'apk_sha256':hashlib.sha256(probeapk.read_bytes()).hexdigest(),'cards':len(records),'clipped':[{k:r[k] for k in ['item','clippedText']} for r in records if r['clippedText']]},indent=2))
