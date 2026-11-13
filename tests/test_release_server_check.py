"""Exercise the actual cross-job archive restore with isolated synthetic payloads."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('server_artifact', ROOT/'tools/server-artifact.py')
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
SHA = 'a'*40


class ServerHandoff(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root/'VERSION').write_text('1.2.0\n', encoding='utf-8')
        self.ffmpeg = self.root/'tools/ffmpeg'
        self.ffmpeg.mkdir(parents=True)
        pin = dict(bytes=6, sha256=hashlib.sha256(b'ffmpeg').hexdigest())
        (self.ffmpeg/'provenance.json').write_text(json.dumps({'ffmpeg.exe':pin}), encoding='utf-8')
        self.stem = 'lw.HomeNAS-1.2.0-ci13-win-x64'
        self.archive = self.root/(self.stem+'.zip')
        self.artifact = self.root/'artifact'
        self.exe = self.root/'build/app/Release/NasServer.exe'

    def create(self, extra=None, ffmpeg=b'ffmpeg', executable=b'tested executable'):
        self.exe.parent.mkdir(parents=True, exist_ok=True)
        self.exe.write_bytes(b'tested executable')
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr(self.stem+'/NasServer.exe', executable)
            archive.writestr(self.stem+'/tools/ffmpeg/ffmpeg.exe', ffmpeg)
            archive.writestr(self.stem+'/config/server.json', 'do not import source config')
            if extra:archive.writestr(extra, b'unexpected')
        server.create(self.archive, self.artifact, 13, SHA, self.root)
        # Model the final assembly job's clean checkout, with no old executable.
        self.exe.unlink()

    def test_round_trip_restores_exact_bytes_and_only_allowed_runtime_files(self):
        self.create()
        server.restore(self.artifact, 13, SHA, self.root)
        self.assertEqual(self.exe.read_bytes(), b'tested executable')
        self.assertEqual((self.ffmpeg/'ffmpeg.exe').read_bytes(), b'ffmpeg')
        self.assertFalse((self.root/'config/server.json').exists())
        self.assertFalse(list(self.root.rglob('*.part')))

    def test_wrong_commit_run_and_version_fail_before_any_extraction(self):
        self.create()
        for number, sha in [(14,SHA), (13,'b'*40)]:
            with self.subTest(number=number,sha=sha), self.assertRaisesRegex(ValueError,'another commit'):
                server.restore(self.artifact, number, sha, self.root)
        (self.root/'VERSION').write_text('1.3.0', encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'another commit'):
            server.restore(self.artifact, 13, SHA, self.root)
        self.assertFalse(self.exe.exists())

    def test_tampered_archive_and_checksum_are_rejected(self):
        self.create()
        checksum = self.artifact/(self.archive.name+'.sha256')
        original = checksum.read_bytes()
        checksum.write_text('bad checksum', encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'checksum file'):
            server.restore(self.artifact, 13, SHA, self.root)
        checksum.write_bytes(original)
        with (self.artifact/self.archive.name).open('ab') as stream:stream.write(b'tampered')
        with self.assertRaisesRegex(ValueError,'archive hash'):
            server.restore(self.artifact, 13, SHA, self.root)
        self.assertFalse(self.exe.exists())

    def test_executable_hash_and_pinned_ffmpeg_are_checked(self):
        self.create()
        stamp = self.artifact/'server-build.json'
        data = json.loads(stamp.read_text(encoding='utf-8'))
        data['executableSha256'] = '0'*64
        stamp.write_text(json.dumps(data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'executable hash'):
            server.restore(self.artifact, 13, SHA, self.root)
        self.assertFalse(self.exe.exists())
        # Even a self-consistent archive manifest cannot override source dependency pins.
        data['executableSha256'] = hashlib.sha256(b'tested executable').hexdigest()
        with zipfile.ZipFile(self.artifact/self.archive.name,'w') as archive:
            archive.writestr(self.stem+'/NasServer.exe', b'tested executable')
            archive.writestr(self.stem+'/tools/ffmpeg/ffmpeg.exe', b'BADBAD')
        data['archiveSha256'] = server.file_digest(self.artifact/self.archive.name)
        stamp.write_text(json.dumps(data), encoding='utf-8')
        (self.artifact/(self.archive.name+'.sha256')).write_text(data['archiveSha256']+'  '+self.archive.name+'\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'FFmpeg hash'):
            server.restore(self.artifact, 13, SHA, self.root)
        self.assertFalse(self.exe.exists())

    def test_path_traversal_duplicate_and_foreign_roots_are_rejected(self):
        for name in [self.stem+'/../escape', self.stem+'/C:/escape', '/absolute', 'other/NasServer.exe', self.stem+'/nasserver.exe']:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError,'archive path'):
                self.create(extra=name)
            self.assertFalse(self.artifact.exists())

    def test_wrong_executable_in_server_zip_cannot_be_stamped_as_tested(self):
        with self.assertRaisesRegex(ValueError,'tested server executable'):
            self.create(executable=b'other executable')
        self.assertFalse(self.artifact.exists())


if __name__ == '__main__':unittest.main()
