"""Pinned bytes must survive Windows checkout; altered bytes must still be rejected."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]


def pinned_files():
    expected={r['path']:r['sha256'] for r in json.loads((ROOT/'third_party/dependencies.lock.json').read_text(encoding='utf-8'))['files']}
    tracked=set(subprocess.check_output(['git','ls-files'],cwd=ROOT).decode('utf-8').splitlines())
    for name,pin in json.loads((ROOT/'tools/ffmpeg/provenance.json').read_text(encoding='utf-8')).items():
        path='tools/ffmpeg/'+name
        if path in tracked:expected[path]=pin['sha256']
    return expected


class DependencyCheckoutChecks(unittest.TestCase):
    def test_all_pinned_files_disable_git_text_conversion(self):
        records=pinned_files()
        names=b'\0'.join(name.encode() for name in records)+b'\0'
        result=subprocess.run(['git','check-attr','-z','--stdin','text'],input=names,stdout=subprocess.PIPE,check=True,cwd=ROOT).stdout.split(b'\0')
        self.assertEqual(len(result),3*len(records)+1)
        for index in range(0,len(result)-1,3):
            self.assertEqual(result[index+1:index+3],[b'text',b'unset'],result[index].decode())

    def test_windows_checkout_filter_matches_original_lock(self):
        expected=pinned_files()
        names=['third_party/httplib/httplib.h','third_party/json/json.hpp','third_party/sqlite/sqlite3.c','third_party/mbedtls/library/aes.c']+[name for name in expected if name.startswith('tools/ffmpeg/')]
        for name in names:
            with self.subTest(path=name):
                content=subprocess.check_output(['git','-c','core.autocrlf=true','cat-file','--filters','--path='+name,'HEAD:'+name],cwd=ROOT)
                self.assertEqual(hashlib.sha256(content).hexdigest(),expected[name])

    def test_crlf_or_content_changes_are_not_silently_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'tools').mkdir();(root/'third_party').mkdir()
            script=root/'tools/check-dependencies.py';script.write_bytes((ROOT/'tools/check-dependencies.py').read_bytes())
            pin={'files':[{'path':'third_party/fixture.h','sha256':hashlib.sha256(b'line\n').hexdigest()}]}
            (root/'third_party/dependencies.lock.json').write_text(json.dumps(pin),encoding='utf-8')
            for value,newline_hint in [(b'line\r\n',True),(b'changed\n',False)]:
                with self.subTest(value=value):
                    (root/'third_party/fixture.h').write_bytes(value)
                    result=subprocess.run([sys.executable,str(script)],capture_output=True)
                    self.assertNotEqual(result.returncode,0);self.assertIn(b'Expected SHA256:',result.stderr)
                    self.assertEqual(b'Git CRLF conversion' in result.stderr,newline_hint)


if __name__=='__main__':unittest.main()
