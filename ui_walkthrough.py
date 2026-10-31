"""Build a separate Android instrumentation APK and capture actual emulator UI screens.

Only emulator-5556 on the isolated test bridge is addressed. No physical devices.
"""
from pathlib import Path
import argparse,os,subprocess,sys,zipfile,json,hashlib,xml.etree.ElementTree as ET
parser=argparse.ArgumentParser();parser.add_argument('--font-scale',default='1.0');parser.add_argument('--output',default='ui-preview');args=parser.parse_args()

ROOT=Path(__file__).resolve().parents[1]
BUILD=ROOT/'build/ui-instrumentation';BUILD.mkdir(parents=True,exist_ok=True)
SDK=next((ROOT/'.tools/build-tools').glob('*/aapt2.exe')).parent
JDK=next((ROOT/'.tools/jdk').glob('*/bin/java.exe')).parent
ANDROID=next((ROOT/'.tools/platform').glob('*/android.jar'))
version=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
ADB=ROOT/'.tools/emulator-sdk/platform-tools/adb.exe'
def run(*args,**kw):return subprocess.run([str(a) for a in args],check=True,cwd=ROOT,**kw)
def adb(*args,**kw):return run(ADB,'-P','5038','-s','emulator-5556',*args,**kw)
classes=BUILD/'classes';classes.mkdir(exist_ok=True)
run(JDK/'javac.exe','--release','8','-encoding','UTF-8','-cp',str(ANDROID)+os.pathsep+str(ROOT/('build/release-'+version)/'classes'),'-d',classes,ROOT/'tests/android/UiWalkthrough.java')
jar=BUILD/'tests.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
    for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
dex=BUILD/'dex';dex.mkdir(exist_ok=True)
run(JDK/'java.exe','-cp',SDK/'lib/d8.jar','com.android.tools.r8.D8','--min-api','31','--lib',ANDROID,'--output',dex,jar)
run(SDK/'aapt2.exe','link','-o',BUILD/'unsigned.apk','-I',ANDROID,'--manifest',ROOT/'tests/android/AndroidManifest.xml')
with zipfile.ZipFile(BUILD/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:z.write(dex/'classes.dex','classes.dex')
test_apk=BUILD/'ui-tests.apk'
run(JDK/'java.exe','-jar',SDK/'lib/apksigner.jar','sign','--ks',ROOT/'.tools/preview.keystore','--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','preview','--v4-signing-enabled','false','--out',test_apk,BUILD/'unsigned.apk')
adb('install','-r',ROOT/'dist'/f'ROV-Companion-{version}.apk')
adb('install','-r',test_apk)
adb('shell','pm','clear','uk.co.rovcompanion')
for permission in ['BLUETOOTH_SCAN','BLUETOOTH_CONNECT','ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','POST_NOTIFICATIONS']:
    adb('shell','pm','grant','uk.co.rovcompanion','android.permission.'+permission)
adb('shell','cmd','location','set-location-enabled','true')
adb('shell','svc','bluetooth','enable')
adb('shell','wm','size','720x1560');adb('shell','wm','density','280')
adb('shell','settings','put','system','font_scale',args.font_scale)
adb('shell','input','keyevent','82')
recording=subprocess.Popen([str(ADB),'-P','5038','-s','emulator-5556','shell','screenrecord','--time-limit','12','/sdcard/rov-ui-preview.mp4'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
result=adb('shell','am','instrument','-w','uk.co.rovcompanion.uitests/uk.co.rovcompanion.UiWalkthrough',capture_output=True,text=True,timeout=240)
recording.wait(timeout=20)
(BUILD/'result.txt').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout)
if 'PASS:' not in result.stdout:raise RuntimeError('UI instrumentation did not pass')
output=ROOT/'dist'/args.output;output.mkdir(exist_ok=True)
adb('pull','/sdcard/Android/data/uk.co.rovcompanion/files/ui-walkthrough/.',output)
adb('pull','/sdcard/rov-ui-preview.mp4',output/'connection-and-controls.mp4')
(output/'validation.json').write_text(json.dumps({'version':version,'apk_sha256':hashlib.sha256((ROOT/'dist'/f'ROV-Companion-{version}.apk').read_bytes()).hexdigest(),'android_api':36,'font_scale':float(args.font_scale),'result':'PASS','instrumentation_output':result.stdout,'boundary':'Real Android Activity/service/notification/lifecycle with injected BLE readiness/movement and queue barrier. No physical slider.'},indent=2),encoding='utf-8')
print('Native emulator screenshots saved to '+str(output))
