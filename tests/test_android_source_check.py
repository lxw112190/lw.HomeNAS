"""Host CI regressions for local fragments and the Android page network boundary."""
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('android_source_check', ROOT / 'tools/check-android.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class AndroidSourceCheckTest(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
