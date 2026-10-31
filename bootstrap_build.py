"""Download portable, checksum-verified build tools into this workspace only."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)
DOWNLOADS = TOOLS / 'downloads'
DOWNLOADS.mkdir(exist_ok=True)

def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'ROV-Companion-build/0.1'})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read()

def download(task):
    name, url, algorithm, expected, destination = task
    archive = DOWNLOADS / name
    if not archive.exists() or hashlib.new(algorithm, archive.read_bytes()).hexdigest() != expected:
        print('Downloading ' + name, flush=True)
        archive.write_bytes(fetch(url))
    digest = hashlib.new(algorithm, archive.read_bytes()).hexdigest()
    if digest != expected:
        raise RuntimeError('Checksum mismatch: ' + name)
    print('Verified ' + name, flush=True)
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            target = (destination / info.filename).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise RuntimeError('Archive path escapes destination')
        z.extractall(destination)
    return {'name': name, 'url': url, 'checksum_algorithm': algorithm, 'checksum': digest,
            'destination': str(destination.relative_to(ROOT))}

repo_bytes = fetch('https://dl.google.com/android/repository/repository2-1.xml')
(DOWNLOADS / 'repository2-1.xml').write_bytes(repo_bytes)
repo = ET.fromstring(repo_bytes)
tasks = []
for package_id, directory in [('platforms;android-36', 'platform'), ('build-tools;36.0.0', 'build-tools')]:
    package = next(e for e in repo if e.tag.endswith('remotePackage') and e.attrib.get('path') == package_id)
    candidates = [a for a in package.findall('./archives/archive')
                  if a.findtext('host-os') in (None, 'windows')]
    archive = candidates[0].find('complete')
    url = 'https://dl.google.com/android/repository/' + archive.findtext('url')
    checksum = archive.find('checksum')
    tasks.append((url.rsplit('/', 1)[-1], url, checksum.attrib.get('type', 'sha1'),
                  checksum.text, TOOLS / directory))

jdk = json.loads(fetch('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'))[0]
binary = jdk['binary']['package']
tasks.append((binary['name'], binary['link'], 'sha256', binary['checksum'], TOOLS / 'jdk'))
with ThreadPoolExecutor(max_workers=3) as pool:
    records = list(pool.map(download, tasks))
(TOOLS / 'build-tool-provenance.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
print('Portable build tools ready.', flush=True)
