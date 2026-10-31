"""Build/test/sign the release using portable tools; preserve the existing update certificate."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
src = ROOT / 'app' / 'src' / 'main'
version = ET.parse(src / 'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
BUILD = ROOT / 'build' / ('release-' + version)
BUILD.mkdir(parents=True, exist_ok=True)
JDK = next((TOOLS / 'jdk').glob('*/bin/javac.exe')).parent
ANDROID = next((TOOLS / 'platform').glob('*/android.jar'))
SDK = next((TOOLS / 'build-tools').glob('*/aapt2.exe')).parent
ENV = os.environ.copy()
ENV['JAVA_HOME'] = str(JDK.parent)

def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT, env=ENV)

classes, tests, dex = BUILD / 'classes', BUILD / 'test-classes', BUILD / 'dex'
for directory in [classes, tests, dex]:
    directory.mkdir(exist_ok=True)
for directory in [classes,tests]:
    for compiled in directory.rglob('*.class'):
        if not compiled.resolve().is_relative_to(BUILD.resolve()):
            raise RuntimeError('Generated class escapes this build directory')
        compiled.unlink()
generated=BUILD/'generated'
generated.mkdir(exist_ok=True)
run(SDK / 'aapt2.exe', 'compile', '--dir', src / 'res', '-o', BUILD / 'resources.zip')
run(SDK / 'aapt2.exe', 'link', '-o', BUILD / 'unsigned.apk', '-I', ANDROID,
    '--manifest', src / 'AndroidManifest.xml', '--min-sdk-version', '31', '--target-sdk-version', '36',
    '--java', generated, BUILD / 'resources.zip')
sources = sorted((src / 'java').rglob('*.java'))+sorted(generated.rglob('*.java'))
run(JDK / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-classpath', ANDROID, '-d', classes, *sources)
run(JDK / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-d', tests,
    src / 'java/uk/co/rovcompanion/Protocol.java', src / 'java/uk/co/rovcompanion/OperationQueue.java',
    ROOT / 'tests/ControlTests.java')
run(JDK / 'java.exe', '-cp', tests, 'uk.co.rovcompanion.ControlTests')
run(sys.executable, ROOT / 'tests/controller_harness.py')
with zipfile.ZipFile(BUILD / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for path in classes.rglob('*.class'):
        jar.write(path, str(path.relative_to(classes)).replace('\\', '/'))
run(JDK / 'java.exe', '-cp', SDK / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '31',
    '--lib', ANDROID, '--output', dex, BUILD / 'classes.jar')
with zipfile.ZipFile(BUILD / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write(dex / 'classes.dex', 'classes.dex')
run(SDK / 'zipalign.exe', '-f', '4', BUILD / 'unsigned.apk', BUILD / 'aligned.apk')
key = TOOLS / 'preview.keystore'
if not key.exists():
    run(JDK / 'keytool.exe', '-genkeypair', '-noprompt', '-keystore', key, '-storepass', 'android',
        '-keypass', 'android', '-alias', 'preview', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '36500', '-dname', 'CN=ROV Companion Preview')
dist = ROOT / 'dist'
dist.mkdir(exist_ok=True)
apk = dist / f'ROV-Companion-{version}.apk'
run(JDK / 'java.exe', '-jar', SDK / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--ks-key-alias', 'preview', '--v4-signing-enabled', 'false', '--out', apk, BUILD / 'aligned.apk')
verification = subprocess.run([str(JDK / 'java.exe'), '-jar', str(SDK / 'lib/apksigner.jar'), 'verify',
    '--verbose', '--print-certs', str(apk)], check=True, capture_output=True, text=True, cwd=ROOT, env=ENV)
(dist / 'signature-verification.txt').write_text(verification.stdout, encoding='utf-8')
print(verification.stdout)
badging = subprocess.run([str(SDK / 'aapt2.exe'), 'dump', 'badging', str(apk)],
    check=True, capture_output=True, text=True, cwd=ROOT, env=ENV)
(dist / 'apk-badging.txt').write_text(badging.stdout, encoding='utf-8')
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
(dist / 'SHA256SUMS.txt').write_text(f'{sha}  {apk.name}\n', encoding='utf-8')
print(f'Built {apk} ({apk.stat().st_size} bytes)\nSHA-256 {sha}')
