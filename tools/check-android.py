"""Host-side source/manifest checks. Does not claim to compile Android or run device tests."""
from pathlib import Path
from html.parser import HTMLParser
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from android_ui_policy import validate_ui_entries

APP = ROOT / 'android/app'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class PageLayout(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.policies = []
        self.views = []
        self.ids = []
        self.feed(text)

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        if tag == 'meta' and attributes.get('http-equiv', '').lower() == 'content-security-policy':
            self.policies.append(attributes.get('content', ''))
        if 'data-view' in attributes:
            self.views.append(attributes['data-view'])
        if 'id' in attributes:
            self.ids.append(attributes['id'])


def check_local_pages(assets):
    validate_ui_entries((path.relative_to(assets).as_posix(), path.stat().st_size)
                        for path in assets.rglob('*') if path.is_file())
    for name in ('index.html', 'boot.js', 'app.js', 'style.css'):
        assert (assets / name).is_file(), f'Missing Android asset: {name}'
    layout = PageLayout((assets / 'index.html').read_text(encoding='utf-8'))
    assert len(layout.policies) == 1, 'Expected one Android page CSP'
    directives = {}
    for clause in layout.policies[0].split(';'):
        words = clause.split()
        if words:
            assert words[0] not in directives, f'Duplicate CSP directive: {words[0]}'
            directives[words[0]] = words[1:]
    # fetch() reads local HTML fragments through WebViewAssetLoader, never a remote server.
    for name, sources in {
        'default-src': ["'none'"], 'connect-src': ["'self'"],
        'media-src': ["'self'"], 'script-src': ["'self'"], 'style-src': ["'self'"], 'img-src': ["'self'"],
        'frame-src': ["'none'"], 'object-src': ["'none'"],
        'base-uri': ["'none'"], 'form-action': ["'none'"],
    }.items():
        assert directives.get(name) == sources, f'Unexpected Android CSP {name}'
    assert len(layout.views) == len(set(layout.views)), 'Duplicate Android page slot'
    assert {'login', 'backup', 'library', 'settings', 'dialogs'} <= set(layout.views), 'Missing Android page slot'
    for name in layout.views:
        assert re.fullmatch(r'[a-z][a-z0-9-]*', name or ''), 'Invalid Android fragment name'
        assert (assets / 'views' / (name + '.html')).is_file(), f'Missing Android fragment: {name}'
        fragment = PageLayout((assets / 'views' / (name + '.html')).read_text(encoding='utf-8'))
        layout.ids.extend(fragment.ids)
    assert len(layout.ids) == len(set(layout.ids)), 'Duplicate Android control ID'
    bound = set(re.findall(r"\$\('([A-Za-z0-9_-]+)'\)", (assets / 'app.js').read_text(encoding='utf-8')))
    assert not bound - set(layout.ids), 'Missing Android event targets: ' + ', '.join(sorted(bound - set(layout.ids)))


def check_source():
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
    check_local_pages(assets)
    print(json.dumps({'android_source_layout': 'passed', 'local_page_fragments': 'passed', 'java_files': len(sources),
                      'compiled_locally': False, 'device_tests': 'CI only, pending until workflow runs'}, ensure_ascii=False))


if __name__ == '__main__':
    check_source()
