"""Verify the CI Release APK against source and the fixed public certificate before packaging."""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import struct
import zipfile
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from android_ui_policy import validate_ui_entries

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def u32(blob, offset):
    return struct.unpack_from('<I', blob, offset)[0]


def lp(blob, offset=0):
    length = u32(blob, offset)
    end = offset + 4 + length
    require(end <= len(blob), 'Truncated APK signing field')
    return blob[offset + 4:end], end


def sequence(blob):
    offset = 0
    while offset < len(blob):
        value, offset = lp(blob, offset)
        yield value


def signing_certificate(data):
    eocd = data.rfind(b'PK\x05\x06')
    require(eocd >= 0 and eocd + 22 + struct.unpack_from('<H', data, eocd + 20)[0] == len(data), 'Invalid APK ZIP trailer')
    central = u32(data, eocd + 16)
    require(central + u32(data, eocd + 12) == eocd and data[central - 16:central] == b'APK Sig Block 42', 'Missing APK signing block')
    length = struct.unpack_from('<Q', data, central - 24)[0]
    start = central - length - 8
    require(start >= 0 and struct.unpack_from('<Q', data, start)[0] == length, 'Invalid APK signing block size')
    offset = start + 8
    v2 = None
    while offset < central - 24:
        count = struct.unpack_from('<Q', data, offset)[0]
        require(count >= 4 and offset + 8 + count <= central - 24, 'Invalid APK signing pair')
        if u32(data, offset + 8) == 0x7109871a:
            require(v2 is None, 'Duplicate APK v2 block')
            v2 = data[offset + 12:offset + 8 + count]
        offset += 8 + count
    require(offset == central - 24 and v2 is not None, 'APK v2 signature required')
    signers, end = lp(v2)
    require(end == len(v2), 'Invalid APK v2 block')
    certificates = []
    for signer in sequence(signers):
        signed, offset = lp(signer)
        signatures, offset = lp(signer, offset)
        public, offset = lp(signer, offset)
        require(offset == len(signer), 'Invalid APK signer')
        digests, at = lp(signed)
        certs, at = lp(signed, at)
        _, at = lp(signed, at)
        require(signed[at:] in (b'', bytes(4)), 'Unexpected APK signer padding')
        signatures, digests = list(sequence(signatures)), list(sequence(digests))
        require([u32(x, 0) for x in signatures] == [u32(x, 0) for x in digests], 'APK signing algorithms differ')
        signature, end = lp(next(x for x in signatures if u32(x, 0) == 0x103), 4)
        require(end == len(next(x for x in signatures if u32(x, 0) == 0x103)), 'Invalid RSA signature record')
        serialization.load_der_public_key(public).verify(signature, signed, padding.PKCS1v15(), hashes.SHA256())
        certificate = next(sequence(certs))
        key = x509.load_der_x509_certificate(certificate).public_key()
        require(key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) == public, 'APK public key mismatch')
        trailer = bytearray(data[eocd:])
        struct.pack_into('<I', trailer, 16, start)
        chunks = []
        for section in (data[:start], data[central:eocd], bytes(trailer)):
            for position in range(0, len(section), 1024 * 1024):
                chunk = section[position:position + 1024 * 1024]
                chunks.append(hashlib.sha256(b'\xa5' + struct.pack('<I', len(chunk)) + chunk).digest())
        actual = hashlib.sha256(b'\x5a' + struct.pack('<I', len(chunks)) + b''.join(chunks)).digest()
        record = next(x for x in digests if u32(x, 0) == 0x103)
        expected, end = lp(record, 4)
        require(end == len(record) and actual == expected, 'APK content digest mismatch')
        certificates.append(hashlib.sha256(certificate).hexdigest())
    require(len(certificates) == 1, 'Exactly one APK signer required')
    return certificates[0]


def manifest_attributes(blob):
    strings = []
    position = 8
    while position < len(blob):
        kind, header, size = struct.unpack_from('<HHI', blob, position)
        require(size >= header and size > 0 and position + size <= len(blob), 'Invalid binary manifest chunk')
        if kind == 1:
            count, flags, base = u32(blob, position + 8), u32(blob, position + 16), position + u32(blob, position + 20)
            def read_length(at, utf8):
                value = blob[at] if utf8 else struct.unpack_from('<H', blob, at)[0]
                step = 1 if utf8 else 2
                if value & (0x80 if utf8 else 0x8000):
                    following = blob[at + 1] if utf8 else struct.unpack_from('<H', blob, at + 2)[0]
                    return ((value & (0x7f if utf8 else 0x7fff)) << (8 if utf8 else 16)) + following, at + 2 * step
                return value, at + step
            for index in range(count):
                at = base + u32(blob, position + header + 4 * index)
                length, at = read_length(at, bool(flags & 0x100))
                if flags & 0x100:
                    length, at = read_length(at, True)
                    strings.append(blob[at:at + length].decode('utf-8'))
                else:
                    strings.append(blob[at:at + 2 * length].decode('utf-16le'))
        if kind == 0x102 and strings[u32(blob, position + 20)] == 'manifest':
            start, stride, count = struct.unpack_from('<HHH', blob, position + 24)
            values = {}
            for index in range(count):
                at = position + 16 + start + index * stride
                name, raw, datatype, value = strings[u32(blob, at + 4)], u32(blob, at + 8), blob[at + 15], u32(blob, at + 16)
                values[name] = strings[raw] if raw != 0xffffffff else strings[value] if datatype == 3 else value
            return values
        position += size
    raise ValueError('Missing binary manifest')


def prepare(artifact, output, ui=None, ci_number=0, ci_sha=''):
    require(not output.exists(), 'Release output already exists')
    path = artifact / 'release/app-release.apk'
    require(path.stat().st_size <= 128 * 1024 * 1024, 'APK too large')
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    expected = {line.split()[1].replace('\\', '/'):line.split()[0] for line in (artifact / 'SHA256SUMS.txt').read_text(encoding='utf-8').splitlines() if line.strip()}
    require(expected.get('android/app/build/outputs/apk/release/app-release.apk') == digest, 'CI APK checksum mismatch')
    baseline = json.loads((ROOT / 'android/signing-certificate.json').read_text(encoding='utf-8'))
    certificate = signing_certificate(data)
    require(certificate == baseline['certificate_sha256'], 'APK differs from fixed signing certificate')
    metadata = json.loads((artifact / 'release/output-metadata.json').read_text(encoding='utf-8'))
    gradle = (ROOT / 'android/app/build.gradle').read_text(encoding='utf-8')
    code, version = int(re.search(r'versionCode\s+(\d+)', gradle).group(1)), re.search(r"versionName\s+'([^']+)'", gradle).group(1)
    require(re.fullmatch(r'[0-9A-Za-z.-]+', version), 'Invalid APK version name')
    with zipfile.ZipFile(io.BytesIO(data)) as apk:
        require(all(i.file_size <= 128*1024*1024 for i in apk.infolist()) and sum(i.file_size for i in apk.infolist()) <= 256*1024*1024, 'APK expanded size limit exceeded')
        require(len(apk.namelist()) == len(set(apk.namelist())) and apk.testzip() is None, 'Invalid APK ZIP entries')
        attributes = manifest_attributes(apk.read('AndroidManifest.xml'))
        require(attributes['package'] == metadata['applicationId'] == baseline['package'], 'APK package differs from baseline')
        require(attributes['versionCode'] == metadata['elements'][0]['versionCode'] == code and attributes['versionName'] == metadata['elements'][0]['versionName'] == version, 'APK version differs from source')
        source = ROOT / 'android/app/src/main/assets/www'
        pages = {n[len('assets/www/'):]:apk.read(n) for n in apk.namelist() if n.startswith('assets/www/') and not n.endswith('/')}
        require(set(pages) == {f.relative_to(source).as_posix() for f in source.rglob('*') if f.is_file()}, 'APK page inventory differs from source')
        require(all(content.replace(b'\r\n', b'\n') == (source / name).read_bytes().replace(b'\r\n', b'\n') for name, content in pages.items()), 'APK pages differ from source')
        public = re.search(r'KEY="([^"]+)"', (ROOT / 'android/app/src/main/java/com/lw/homenas/ResourceTrust.java').read_text(encoding='utf-8')).group(1)
        dex = [apk.read(n) for n in apk.namelist() if re.fullmatch(r'classes[0-9]*\.dex', n)]
        require(any(public.encode() in part for part in dex), 'APK resource public key differs from source')
        if ci_sha:
            require(re.fullmatch(r'[a-f0-9]{40}', ci_sha) and any(ci_sha[:12].encode() in part for part in dex), 'APK build commit differs from selected CI commit')
    if ui:
        envelope = json.loads((ui / 'release.json').read_text(encoding='utf-8'))
        payload = base64.b64decode(envelope['payload'])
        serialization.load_der_public_key(base64.b64decode(public)).verify(base64.b64decode(envelope['signature']), payload, padding.PKCS1v15(), hashes.SHA256())
        manifest = json.loads(payload)
        archive = ui / ('ui-r' + str(manifest['revision']) + '.zip')
        require(archive.stat().st_size == manifest['size'] and hashlib.sha256(archive.read_bytes()).hexdigest() == manifest['sha256'], 'Signed UI checksum differs')
        with zipfile.ZipFile(archive) as z:
            validate_ui_entries((i.filename, i.file_size) for i in z.infolist())
            require(set(z.namelist()) == set(pages), 'Signed UI page inventory differs from APK')
            require(all(z.read(name).replace(b'\r\n', b'\n') == pages[name].replace(b'\r\n', b'\n') for name in pages), 'Signed UI differs from APK')
    output.mkdir(parents=True)
    (output / (digest + '.apk')).write_bytes(data)
    (output / 'apk.json').write_text(json.dumps({'versionCode':code,'versionName':version,'sha256':digest,'size':str(len(data)),'notes':f'Fixed-signature Release APK from Android CI {ci_number}.'}, indent=2)+'\n', encoding='utf-8')
    report = {'package':baseline['package'],'versionCode':code,'versionName':version,'variant':'release','sha256':digest,'bytes':len(data),'certificate_sha256':certificate,'apk_v2_signature':'verified','apk_content_digest':'verified','embedded_page_files':len(pages),'source_pages_match':True,'source_commit':ci_sha,'ci_number':ci_number,'physical_device_tested':False,'ui_source':'signed_bundle' if ui else 'apk_builtin'}
    (output / 'verification.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    if ui:
        shutil.copy2(ui / 'release.json', output / 'ui.json')
        shutil.copy2(archive, output / (manifest['sha256'] + '.zip'))
    print(json.dumps(report))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifact-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ui-directory', type=Path)
    parser.add_argument('--ci-number', type=int, default=0)
    parser.add_argument('--ci-sha', default='')
    args = parser.parse_args()
    prepare(args.artifact_directory, args.output, args.ui_directory, args.ci_number, args.ci_sha)
