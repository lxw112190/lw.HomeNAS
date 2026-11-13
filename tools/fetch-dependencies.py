"""Fetch pinned public source dependencies; run once before building."""
import concurrent.futures
import hashlib
import io
import json
import tarfile
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ITEMS = [
    ('qrcodegen-cpp','https://raw.githubusercontent.com/nayuki/QR-Code-generator/v1.8.0/cpp/qrcodegen.cpp','third_party/qrcodegen/qrcodegen.cpp'),
    ('qrcodegen-hpp','https://raw.githubusercontent.com/nayuki/QR-Code-generator/v1.8.0/cpp/qrcodegen.hpp','third_party/qrcodegen/qrcodegen.hpp'),
    ('httplib', 'https://raw.githubusercontent.com/yhirose/cpp-httplib/v0.64.0/httplib.h', 'third_party/httplib/httplib.h'),
    ('httplib-license', 'https://raw.githubusercontent.com/yhirose/cpp-httplib/v0.64.0/LICENSE', 'third_party/httplib/LICENSE'),
    ('json', 'https://raw.githubusercontent.com/nlohmann/json/v3.12.0/single_include/nlohmann/json.hpp', 'third_party/json/json.hpp'),
    ('json-license', 'https://raw.githubusercontent.com/nlohmann/json/v3.12.0/LICENSE.MIT', 'third_party/json/LICENSE.MIT'),
    ('sqlite', 'https://www.sqlite.org/2026/sqlite-amalgamation-3540100.zip', None),
    ('mbedtls', 'https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.7/mbedtls-3.6.7.tar.bz2', None),
]


def fetch(item):
    name, url, destination = item
    request = urllib.request.Request(url, headers={'User-Agent': 'lw.HomeNAS-development'})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read()
    if name == 'mbedtls':
        sums_url = url.replace('.tar.bz2', '-sha256sum.txt')
        with urllib.request.urlopen(sums_url, timeout=90) as response:
            sums = response.read().decode('utf-8')
        expected = next(line.split()[0] for line in sums.splitlines() if line.strip().endswith('mbedtls-3.6.7.tar.bz2'))
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Mbed TLS archive SHA256 mismatch')
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:bz2') as archive:
            for member in archive.getmembers():
                parts = Path(member.name).parts
                if not member.isfile() or len(parts) < 2:
                    continue
                relative = Path(*parts[1:])
                if '..' in relative.parts or relative.is_absolute():
                    raise ValueError('Invalid archive path')
                if relative.parts[0] not in ('include', 'library') and relative.name not in ('LICENSE', 'LICENSES'):
                    continue
                if member.size > 8 * 1024 * 1024:
                    raise ValueError('Oversized dependency file')
                target = ROOT / 'third_party/mbedtls' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(member).read())
    elif name == 'sqlite':
        expected = '92281c86e2957f9b9305890f9ac04204caa7b677cc868dc326d942689df28e30'
        if hashlib.sha3_256(data).hexdigest() != expected:
            raise ValueError('SQLite archive SHA3-256 mismatch')
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for filename in ['sqlite3.c', 'sqlite3.h']:
                target = ROOT / 'third_party/sqlite' / filename
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read('sqlite-amalgamation-3540100/' + filename))
    else:
        target = ROOT / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return {'name': name, 'url': url, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}


if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        records = list(pool.map(fetch, ITEMS))
    header=(ROOT/'third_party/qrcodegen/qrcodegen.hpp').read_text(encoding='utf-8').split('*/',1)[0]
    license='\n'.join(line[3:] if line.startswith(' * ') else '' for line in header.splitlines()[1:]).strip()+'\n'
    (ROOT/'third_party/qrcodegen/LICENSE').write_bytes(license.encode('utf-8'))
    files = []
    for folder in ('httplib','json','sqlite','mbedtls','qrcodegen'):
        for file in sorted((ROOT/'third_party'/folder).rglob('*')):
            if file.is_file():
                files.append({'path': file.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
    (ROOT / 'third_party/dependencies.lock.json').write_bytes(
        (json.dumps({'dependencies': records,'files':files}, indent=2) + '\n').encode('utf-8'))
    for record in records:
        print(record['name'], record['bytes'], record['sha256'])
