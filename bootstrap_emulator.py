"""Prepare a private Android UI-test emulator; publisher checksums, no system installation."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib,json,urllib.request,xml.etree.ElementTree as ET,zipfile

ROOT=Path(__file__).resolve().parents[1]
SDK=ROOT/'.tools/emulator-sdk'
DOWNLOADS=ROOT/'.tools/downloads'
SDK.mkdir(parents=True,exist_ok=True)
def fetch(url):
    with urllib.request.urlopen(url,timeout=120) as r:return r.read()
def package_task(package,base,dest):
    archive=next(a for a in package.findall('./archives/archive') if a.findtext('host-os') in (None,'windows')).find('complete')
    checksum=archive.find('checksum')
    return base+archive.findtext('url'),checksum.attrib.get('type','sha1'),checksum.text,dest
repo=ET.fromstring(fetch('https://dl.google.com/android/repository/repository2-1.xml'))
tasks=[package_task(next(e for e in repo if e.attrib.get('path')==p),'https://dl.google.com/android/repository/',SDK)
       for p in ['emulator','platform-tools']]
images=ET.fromstring(fetch('https://dl.google.com/android/repository/sys-img/android/sys-img2-1.xml'))
image=next(e for e in images if e.attrib.get('path')=='system-images;android-36;default;x86_64')
tasks.append(package_task(image,'https://dl.google.com/android/repository/sys-img/android/',SDK/'system-images/android-36/default'))
def download(task):
    url,algorithm,expected,dest=task
    path=DOWNLOADS/url.rsplit('/',1)[-1]
    if not path.exists() or hashlib.new(algorithm,path.read_bytes()).hexdigest()!=expected:
        print('Downloading '+path.name,flush=True)
        with urllib.request.urlopen(url,timeout=180) as response,path.open('wb') as output:
            while True:
                chunk=response.read(1024*1024)
                if not chunk:break
                output.write(chunk)
    assert hashlib.new(algorithm,path.read_bytes()).hexdigest()==expected,'Checksum mismatch'
    dest.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path) as z:
        assert all((dest/i.filename).resolve().is_relative_to(dest.resolve()) for i in z.infolist())
        z.extractall(dest)
    print('Verified and unpacked '+path.name,flush=True)
    return {'url':url,'algorithm':algorithm,'checksum':expected}
with ThreadPoolExecutor(max_workers=3) as pool:records=list(pool.map(download,tasks))
(SDK/'provenance.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
avd=ROOT/'.tools/avd/ROV_Studio.avd'
avd.mkdir(parents=True,exist_ok=True)
config={'AvdId':'ROV_Studio','avd.ini.encoding':'UTF-8','avd.ini.displayname':'ROV Studio','abi.type':'x86_64',
'hw.cpu.arch':'x86_64','hw.device.name':'pixel_7','hw.device.manufacturer':'Google','hw.lcd.width':'1080',
'hw.lcd.height':'2340','hw.lcd.density':'420','hw.gpu.enabled':'yes','hw.gpu.mode':'swiftshader_indirect',
'hw.ramSize':'2048','hw.keyboard':'yes','disk.dataPartition.size':'2G','tag.id':'default','tag.display':'Default',
'image.sysdir.1':str(SDK/'system-images/android-36/default/x86_64'),'PlayStore.enabled':'false','showDeviceFrame':'no'}
(avd/'config.ini').write_text(''.join(k+'='+v+'\n' for k,v in config.items()),encoding='utf-8')
(avd.parent/'ROV_Studio.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(avd)+'\ntarget=android-36\n',encoding='utf-8')
print('Private Android 16 emulator prepared.',flush=True)
