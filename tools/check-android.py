"""Host-side source/manifest checks. Does not claim to compile Android or run device tests."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'android/app'
ANDROID = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(APP / 'src/main/AndroidManifest.xml').getroot()
application = manifest.find('application')
assert application.get(ANDROID + 'allowBackup') == 'false'
services = application.findall('service')
assert any(s.get(ANDROID + 'name') == '.BackupService' and s.get(ANDROID + 'exported') == 'false'
           and s.get(ANDROID + 'foregroundServiceType') == 'dataSync' for s in services)
permissions = {p.get(ANDROID + 'name') for p in manifest.findall('uses-permission')}
assert 'android.permission.MANAGE_EXTERNAL_STORAGE' not in permissions
assert 'android.permission.READ_MEDIA_VISUAL_USER_SELECTED' in permissions
for path in APP.glob('src/**/*.xml'):
    ET.parse(path)
sources = list(APP.glob('src/**/*.java'))
assert len(sources) >= 15
for path in sources:
    text = path.read_text(encoding='utf-8')
    assert not re.search(r'(?:sk-[a-z0-9]{24,}|BEGIN PRIVATE KEY)', text)
    assert 'handler.proceed()' not in text
    assert 'addJavascriptInterface(' not in text
    assert 'setHostnameVerifier(' not in text
assets = APP / 'src/main/assets/www'
assert all((assets / name).is_file() for name in ('index.html', 'app.js', 'style.css'))
assert "connect-src 'none'" in (assets / 'index.html').read_text(encoding='utf-8')
print(json.dumps({'android_source_layout': 'passed', 'java_files': len(sources),
                  'compiled_locally': False, 'device_tests': 'CI only, pending until workflow runs'}, ensure_ascii=False))
