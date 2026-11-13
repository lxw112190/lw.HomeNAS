import datetime
import hashlib
import importlib.util
import io
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
import yaml
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding,rsa
from cryptography.x509.oid import NameOID

ROOT=Path(__file__).resolve().parents[1]
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'tools'/file);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
import sys
sys.path.insert(0,str(ROOT/'tools'))
apk=module('release_apk','prepare-android-release.py');ffmpeg=module('release_ffmpeg','fetch-ffmpeg.py')
workflows=module('release_workflows','check-workflows.py')


def signed_fixture():
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w') as z:z.writestr('payload.txt','fixture')
    base=stream.getvalue();eocd=base.rfind(b'PK\x05\x06');central=struct.unpack_from('<I',base,eocd+16)[0]
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048);name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'fixture')]);now=datetime.datetime.now(datetime.timezone.utc)
    cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(1).not_valid_before(now-datetime.timedelta(days=1)).not_valid_after(now+datetime.timedelta(days=1)).sign(key,hashes.SHA256()).public_bytes(serialization.Encoding.DER)
    public=key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
    lp=lambda value:struct.pack('<I',len(value))+value
    chunks=[hashlib.sha256(b'\xa5'+struct.pack('<I',len(section))+section).digest() for section in (base[:central],base[central:eocd],base[eocd:])]
    digest=hashlib.sha256(b'\x5a'+struct.pack('<I',len(chunks))+b''.join(chunks)).digest()
    signed=lp(lp(struct.pack('<I',0x103)+lp(digest)))+lp(lp(cert))+lp(b'')
    signature=key.sign(signed,padding.PKCS1v15(),hashes.SHA256());signer=lp(signed)+lp(lp(struct.pack('<I',0x103)+lp(signature)))+lp(public)
    v2=lp(lp(signer));pair=struct.pack('<Q',len(v2)+4)+struct.pack('<I',0x7109871a)+v2;size=len(pair)+24;block=struct.pack('<Q',size)+pair+struct.pack('<Q',size)+b'APK Sig Block 42'
    trailer=bytearray(base[eocd:]);struct.pack_into('<I',trailer,16,central+len(block));return base[:central]+block+base[central:eocd]+bytes(trailer),hashlib.sha256(cert).hexdigest()


class ReleaseChecks(unittest.TestCase):
    def test_all_workflows_have_unique_keys_and_valid_dependencies(self):
        for path in (ROOT/'.github/workflows').glob('*.y*ml'):
            with self.subTest(path=path.name):workflows.validate(path.read_text(encoding='utf-8'))

    def test_duplicate_checkout_ref_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'Duplicate YAML key.*ref'):
            workflows.validate('jobs:\n  package:\n    steps:\n      - with:\n          ref: first\n          ref: second\n')

    def test_select_cannot_reference_its_own_future_output(self):
        with self.assertRaisesRegex(ValueError,'undeclared needs reference.*select'):
            workflows.validate('jobs:\n  select:\n    steps:\n      - with:\n          ref: ${{ needs.select.outputs.sha }}\n')

    def test_job_cycles_and_missing_dependencies_are_rejected(self):
        for source in ['jobs:\n  a:\n    needs: b\n  b:\n    needs: a\n',
                       'jobs:\n  a:\n    needs: missing\n']:
            with self.subTest(source=source),self.assertRaises(ValueError):workflows.validate(source)

    def test_actual_signature_and_content_digest(self):
        content,certificate=signed_fixture();self.assertEqual(apk.signing_certificate(content),certificate)
        tampered=bytearray(content);tampered[0]^=1
        with self.assertRaisesRegex(ValueError,'content digest'):apk.signing_certificate(bytes(tampered))

    def test_unsigned_apk_is_rejected(self):
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,'w') as z:z.writestr('file','test')
        with self.assertRaisesRegex(ValueError,'signing block'):apk.signing_certificate(stream.getvalue())

    def test_pinned_download_cache_refuses_changed_file(self):
        with tempfile.TemporaryDirectory() as d:
            file=Path(d)/'dependency';pin={'bytes':3,'sha256':hashlib.sha256(b'abc').hexdigest()}
            self.assertFalse(ffmpeg.verified(file,pin));file.write_bytes(b'abc');self.assertTrue(ffmpeg.verified(file,pin));file.write_bytes(b'abd')
            with self.assertRaisesRegex(ValueError,'differs'):ffmpeg.verified(file,pin)

    def test_workflow_keeps_manual_artifact_only_mode(self):
        workflow=workflows.validate((ROOT/'.github/workflows/android.yml').read_text(encoding='utf-8'))
        step=next(s for s in workflow['jobs']['select']['steps'] if s.get('id')=='source')
        self.assertEqual(step['env']['INPUT_PUBLISH'],'${{ inputs.publish }}')
        self.assertEqual(workflow['permissions']['contents'],'read');self.assertEqual(workflow['jobs']['publish']['permissions']['contents'],'write')
        self.assertIn("needs.select.outputs.publish == 'true'",workflow['jobs']['publish']['if'])

    def test_parallel_builds_join_only_after_emulator_and_use_current_run_artifacts(self):
        workflow=workflows.validate((ROOT/'.github/workflows/android.yml').read_text(encoding='utf-8'))
        jobs=workflow['jobs']
        self.assertFalse(jobs['apk'].get('needs'))
        self.assertFalse(jobs['server'].get('needs'))
        self.assertEqual(jobs['emulator']['needs'],'apk')
        self.assertEqual(set(jobs['package']['needs']),{'select','apk','server','emulator'})
        self.assertEqual(set(jobs['publish']['needs']),{'select','package'})
        # Neither a skipped emulator nor a failed build can be bypassed by always().
        self.assertNotIn('if',jobs['package'])
        self.assertNotIn('always()',jobs['publish']['if'])
        for name in ('apk','server','emulator','select'):
            checkout=next(step for step in jobs[name]['steps'] if step.get('uses')=='actions/checkout@v4')
            self.assertEqual(checkout['with']['ref'],'${{ github.sha }}')
        for name in ('package','publish'):
            checkout=next(step for step in jobs[name]['steps'] if step.get('uses')=='actions/checkout@v4')
            self.assertEqual(checkout['with']['ref'],'${{ needs.select.outputs.sha }}')
        downloads=[step['with'] for step in jobs['package']['steps'] if step.get('uses')=='actions/download-artifact@v4']
        self.assertEqual({step['path'] for step in downloads},{'incoming-android','incoming-server'})
        for step in downloads:self.assertNotIn('run-id',step)
        # Keep only one automatic entry point; a workflow_run chain would serialize again.
        for path in (ROOT/'.github/workflows').glob('*.yml'):
            events=workflows.validate(path.read_text(encoding='utf-8')).get(True,{})
            self.assertNotIn('workflow_run',events)
            if 'push' in events:self.assertEqual(path.name,'android.yml')
        self.assertEqual(workflow.get(True)['push']['branches'],['main'])
        self.assertIn("github.event_name != 'pull_request'",jobs['select']['if'])
        self.assertEqual(workflow['concurrency']['cancel-in-progress'],"${{ github.event_name == 'pull_request' }}")

    def test_package_reuses_server_build_and_does_not_download_ffmpeg_again(self):
        build=(ROOT/'tools/build-server-release.ps1').read_text(encoding='utf-8')
        assembly=(ROOT/'tools/build-release.ps1').read_text(encoding='utf-8')
        self.assertIn("'build.ps1'",build)
        self.assertIn("'tests/smoke.py'",build)
        self.assertIn("'tests/deployment-scripts.ps1'",build)
        self.assertIn("'tools/server-artifact.py','create'",build)
        self.assertNotIn('ArtifactDirectory',build)
        self.assertNotIn("'build.ps1'",assembly)
        self.assertIn("'tools/server-artifact.py','restore'",assembly)
        self.assertIn("'tools/fetch-ffmpeg.py','--verify-only'",assembly)
        self.assertIn("'tools/prepare-android-release.py'",assembly)
        self.assertIn("'tests/package_release.py'",assembly)

    def test_ffmpeg_crlf_pin_remains_strict_with_diagnostic(self):
        with tempfile.TemporaryDirectory() as d:
            file=Path(d)/'COPYING.txt';pin={'bytes':5,'sha256':hashlib.sha256(b'line\n').hexdigest()}
            file.write_bytes(b'line\r\n')
            with self.assertRaisesRegex(ValueError,'Git CRLF conversion'):ffmpeg.verified(file,pin)
            file.write_bytes(b'other\n')
            with self.assertRaisesRegex(ValueError,'differs'):ffmpeg.verified(file,pin)

    def test_version_and_native_protocol_sources_agree(self):
        subprocess.run([sys.executable,str(ROOT/'tools/check-version.py')],check=True,cwd=ROOT)

    def test_selection_and_draft_publishing_with_fake_api(self):
        node=shutil.which('node');self.assertIsNotNone(node,'Node is required for release-workflow checks')
        subprocess.run([node,str(ROOT/'tests/release_workflow.test.cjs')],check=True,cwd=ROOT)


if __name__=='__main__':unittest.main()
