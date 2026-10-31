"""Verify the built APK and native test evidence, then archive source without keys/private logs."""
from pathlib import Path
import hashlib,json,subprocess,sys,xml.etree.ElementTree as ET,zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.tools/python'))
from loguru import logger
logger.disable('androguard')
from androguard.core.apk import APK
from androguard.core.dex import DEX

NS='{http://schemas.android.com/apk/res/android}'
manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
version=manifest.get(NS+'versionName')
dist=ROOT/'dist';path=dist/f'ROV-Companion-{version}.apk'
digest=hashlib.sha256(path.read_bytes()).hexdigest()
apk=APK(str(path));xml=apk.get_android_manifest_xml();app=xml.find('application')
assert apk.get_package()=='uk.co.rovcompanion'
assert apk.get_androidversion_name()==version and apk.get_androidversion_code()==manifest.get(NS+'versionCode')
assert apk.get_min_sdk_version()=='31' and apk.get_target_sdk_version()=='36'
assert app.get(NS+'debuggable','false')=='false' and xml.find('instrumentation') is None
service=app.find('service')
assert service.get(NS+'name')=='.RovService' and service.get(NS+'exported')=='false'
assert int(service.get(NS+'foregroundServiceType'),0)==16 and service.get(NS+'stopWithTask')=='false'
classes=[c.get_name() for c in DEX(apk.get_dex()).get_classes()]
assert all(name.startswith('Luk/co/rovcompanion/') for name in classes)
assert not any('UiWalkthrough' in name or 'Tests' in name for name in classes)
expected_certificate='ba6159194136ed8406cf790eb9cd76c4809c093c734bab30976c3c3517ff3a77'
certificates=[hashlib.sha256(certificate).hexdigest() for certificate in apk.get_certificates_der_v3()]
assert certificates==[expected_certificate]
signature=(dist/'signature-verification.txt').read_text(encoding='utf-8')
assert expected_certificate in signature and 'Verified using v3 scheme (APK Signature Scheme v3): true' in signature
permissions=sorted(apk.get_permissions())
assert 'android.permission.INTERNET' not in permissions and 'android.permission.ACCESS_BACKGROUND_LOCATION' not in permissions
for permission in ['FOREGROUND_SERVICE','FOREGROUND_SERVICE_CONNECTED_DEVICE','POST_NOTIFICATIONS','WAKE_LOCK']:
    assert 'android.permission.'+permission in permissions
align=next((ROOT/'.tools/build-tools').glob('*/zipalign.exe'))
alignment=subprocess.run([str(align),'-c','-v','4',str(path)],check=True,capture_output=True,text=True)
(dist/'alignment-verification.txt').write_text(alignment.stdout,encoding='utf-8')
native=[]
for folder,scale in [('ui-'+version,1.0),('ui-'+version+'-large',1.3)]:
    evidence=json.loads((dist/folder/'validation.json').read_text(encoding='utf-8'))
    assert evidence['result']=='PASS' and evidence['apk_sha256']==digest and evidence['font_scale']==scale
    native.append({'path':folder+'/validation.json','font_scale':scale,'result':'PASS'})
files=[ROOT/'README.md',ROOT/'RELEASE_NOTES.md',ROOT/'.gitignore',ROOT/'analysis'/f'RELEASE-{version}.md']
for directory in ['app/src/main','tests','docs']:
    files.extend(p for p in (ROOT/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix!='.pyc')
for name in ['bootstrap_build.py','build_apk.py','bootstrap_emulator.py','ui_walkthrough.py','package_release.py']:
    files.append(ROOT/'tools'/name)
archive=dist/f'ROV-Companion-{version}-source.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as bundle:
    for file in sorted(set(files)):bundle.write(file,file.relative_to(ROOT).as_posix())
with zipfile.ZipFile(archive) as bundle:
    assert bundle.testzip() is None
    assert not any(name.startswith(('.tools/','build/','original/','analysis/hardware/')) or name.endswith(('.keystore','.xapk','.apk')) for name in bundle.namelist())
source_digest=hashlib.sha256(archive.read_bytes()).hexdigest()
report={'version':version,'version_code':int(apk.get_androidversion_code()),'package':apk.get_package(),
    'artifact':path.name,'size_bytes':path.stat().st_size,'sha256':digest,
    'source_archive':archive.name,'source_sha256':source_digest,'source_files':len(files),
    'minimum_api':31,'target_api':36,'permissions':permissions,
    'signature':'apksigner verify passed; v3; existing update certificate retained','certificate_sha256':expected_certificate,
    'alignment':'zipalign -c -v 4 passed','debuggable':False,'test_instrumentation_in_shipping_apk':False,
    'jvm_suites':['ControlTests','ControllerFlowTests','ScanFlowTests','LoopFlowTests','QuietEndTests','LaunchAndRangeTests'],
    'jvm_result':'PASS: six suites in the successful release build','native_android_16_checks':native,
    'background_service':{'type':'connectedDevice','exported':False,'stop_with_task':False,'restart':'START_NOT_STICKY'},
    'boundary':'Real Android lifecycle/service/notification/UI with injected BLE state and queue barrier. No physical slider test for this version.',
    'physical_device_tested_this_version':False,'distribution':'Signed APK for direct installation; no app-store publication'}
for name in ['validation.json',f'validation-{version}.json']:(dist/name).write_text(json.dumps(report,indent=2),encoding='utf-8')
(dist/'SHA256SUMS.txt').write_text(f'{digest}  {path.name}\n{source_digest}  {archive.name}\n',encoding='utf-8')
print(json.dumps({key:report[key] for key in ['artifact','size_bytes','sha256','source_archive','source_files','alignment','signature']},indent=2))
