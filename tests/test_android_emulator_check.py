import importlib.util
import json
from pathlib import Path
from subprocess import CompletedProcess
import tempfile
import unittest
import contextlib
import io

SPEC = importlib.util.spec_from_file_location('emulator_check', Path(__file__).resolve().parents[1] / 'tools/android-emulator-check.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EvidenceTests(unittest.TestCase):
    def run_case(self, mode):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'android/app/build/outputs/apk/debug'
            apk.mkdir(parents=True)
            (apk / 'app-debug.apk').write_bytes(b'test')
            (apk / 'output-metadata.json').write_text(json.dumps({'applicationId': 'com.lxw.homenas', 'elements': [{'outputFile': 'app-debug.apk'}]}))
            calls = []

            def runner(argv, binary=False):
                calls.append(argv)
                if argv[0] == 'gradle':
                    return CompletedProcess(argv, 7 if mode == 'test-failure' else 0, 'test report', '')
                if 'resolve-activity' in argv:
                    text = 'com.lxw.homenas/com.changed.RuntimeActivity\n'
                elif 'start' in argv:
                    text = 'Error type 3\nError: Activity class does not exist' if mode == 'launch-failure' else 'Status: ok'
                elif binary:
                    return CompletedProcess(argv, 0, b'\x89PNG\r\n\x1a\n' if 'screencap' in argv else b'tar', b'')
                else:
                    text = 'Success'
                return CompletedProcess(argv, 0, text, '')

            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                result = MODULE.execute(root, runner)
            self.assertTrue((root / 'android/app/build/outputs/device-evidence/logcat.txt').is_file())
            return result, calls

    def test_reinstalls_and_resolves_launcher_after_test_cleanup(self):
        result, calls = self.run_case('success')
        self.assertEqual(result, 0)
        installed = next(i for i, c in enumerate(calls) if c[:2] == ['adb', 'install'])
        launched = next(i for i, c in enumerate(calls) if 'start' in c)
        self.assertLess(installed, launched)
        self.assertIn('com.lxw.homenas/com.changed.RuntimeActivity', calls[launched])

    def test_preserves_test_failure_and_collects_diagnostics(self):
        result, calls = self.run_case('test-failure')
        self.assertEqual(result, 7)
        self.assertFalse(any('start' in c for c in calls))

    def test_detects_adb_launch_error_even_with_zero_exit_code(self):
        result, _ = self.run_case('launch-failure')
        self.assertEqual(result, 1)


if __name__ == '__main__':
    unittest.main()
