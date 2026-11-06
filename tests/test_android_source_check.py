"""Host CI regressions for local fragments and the Android page network boundary."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('android_source_check', ROOT / 'tools/check-android.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class AndroidSourceCheckTest(unittest.TestCase):
    def test_production_preview_audio_and_native_playback_flow(self):
        subprocess.run(['node', str(ROOT / 'tests/media-preview-ui.cjs')], cwd=ROOT, check=True)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.assets = Path(temporary.name) / 'www'
        shutil.copytree(ROOT / 'android/app/src/main/assets/www', self.assets)

    def edit_index(self, old, new):
        index = self.assets / 'index.html'
        text = index.read_text(encoding='utf-8')
        self.assertIn(old, text)
        index.write_text(text.replace(old, new), encoding='utf-8')

    def test_shipped_local_fragment_layout_passes(self):
        checker.check_local_pages(self.assets)

    def test_android_files_string_shortcuts_are_rejected(self):
        for source in ['Files.readString(path)', 'java.nio.file.Files.writeString(path, text)',
                       'Files . readString (path)']:
            with self.subTest(source=source), self.assertRaisesRegex(AssertionError, 'Android API unavailable'):
                checker.check_android_file_api(source)

    def test_static_import_cannot_bypass_android_file_api_check(self):
        for method in ['readString', 'writeString', '*']:
            with self.subTest(method=method), self.assertRaisesRegex(AssertionError, 'static import'):
                checker.check_android_file_api('import static java.nio.file.Files.' + method + ';')

    def test_explicit_utf8_file_bytes_remain_supported(self):
        checker.check_android_file_api('new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8)')

    def test_remote_connection_permission_is_rejected(self):
        self.edit_index("connect-src 'self'", "connect-src 'self' https:")
        with self.assertRaisesRegex(AssertionError, 'connect-src'):
            checker.check_local_pages(self.assets)

    def test_old_policy_cannot_silently_break_fragment_loading(self):
        self.edit_index("connect-src 'self'", "connect-src 'none'")
        with self.assertRaisesRegex(AssertionError, 'connect-src'):
            checker.check_local_pages(self.assets)

    def test_missing_fragment_is_rejected(self):
        (self.assets / 'views/login.html').unlink()
        with self.assertRaisesRegex(AssertionError, 'Missing Android fragment: login'):
            checker.check_local_pages(self.assets)

    def test_missing_boot_script_is_rejected(self):
        (self.assets / 'boot.js').unlink()
        with self.assertRaisesRegex(AssertionError, 'boot.js'):
            checker.check_local_pages(self.assets)

    def test_fragment_path_traversal_is_rejected(self):
        self.edit_index('data-view="dialogs"', 'data-view="../dialogs"')
        with self.assertRaises(AssertionError):
            checker.check_local_pages(self.assets)

    def test_duplicate_csp_directive_is_rejected(self):
        self.edit_index("connect-src 'self'", "connect-src 'self'; connect-src *")
        with self.assertRaisesRegex(AssertionError, 'Duplicate CSP directive'):
            checker.check_local_pages(self.assets)

    def test_distributed_apk_policy_matches_host_policy(self):
        from android_ui_policy import RESOURCE_PATH_PATTERN
        native = (ROOT / 'android/app/src/main/java/com/lw/homenas/ClientMaintenance.java').read_text(encoding='utf-8')
        self.assertIn('path.matches(' + json.dumps(RESOURCE_PATH_PATTERN) + ')', native)

    def test_missing_bound_control_is_rejected(self):
        fragment = self.assets / 'views/library.html'
        fragment.write_text(fragment.read_text(encoding='utf-8').replace('id="library-order"', 'id="unused-order"'), encoding='utf-8')
        with self.assertRaisesRegex(AssertionError, 'Missing Android event targets: library-order'):
            checker.check_local_pages(self.assets)

    def test_license_text_cannot_be_shipped_as_unsupported_txt(self):
        (self.assets / 'licenses/old-NOTICE.txt').write_text('fixture notice', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, r'APK rejects.*old-NOTICE.txt'):
            checker.check_local_pages(self.assets)

    def test_license_pages_keep_local_links_and_full_text(self):
        from html.parser import HTMLParser

        class Links(HTMLParser):
            def __init__(self):
                super().__init__()
                self.hrefs = []
            def handle_starttag(self, tag, attrs):
                if tag == 'a':
                    self.hrefs.append(dict(attrs).get('href', ''))

        parser = Links()
        parser.feed((self.assets / 'licenses.html').read_text(encoding='utf-8'))
        license_links = [link for link in parser.hrefs if link.startswith('licenses/')]
        self.assertEqual(len(license_links), 3)
        for link in license_links:
            self.assertTrue(link.endswith('.html'))
            page = (self.assets / link).read_text(encoding='utf-8')
            self.assertIn('<pre class="license-text">', page)
        self.assertIn('END OF TERMS AND CONDITIONS', (self.assets / 'licenses/APACHE-2.0.html').read_text(encoding='utf-8'))

    def test_zip_entry_limits_and_paths_remain_enforced(self):
        from android_ui_policy import validate_ui_entries
        base = [('index.html', 1), ('app.js', 1), ('style.css', 1)]
        for extra in [[('../escape.html', 1)], [('/root.html', 1)], [('index.html', 1)],
                      [('huge.js', 20 * 1024 * 1024)], [(f'file{i}.js', 1) for i in range(200)]]:
            with self.subTest(extra=extra[0][0]), self.assertRaises(ValueError):
                validate_ui_entries(base + extra)


if __name__ == '__main__':
    unittest.main()
