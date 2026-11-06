"""Restore the pinned standalone Windows FFmpeg and source archive for CI packaging."""
import argparse
import hashlib
import http.client
import json
from pathlib import Path
import socket
import ssl
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DOWNLOAD_ATTEMPTS = 5


def transient(error):
    if isinstance(error, urllib.error.HTTPError):
        return error.code in (408, 429, 500, 502, 503, 504)
    if isinstance(error, urllib.error.URLError):
        return transient(error.reason)
    if isinstance(error, ssl.SSLCertVerificationError):
        return False
    return isinstance(error, (ConnectionError, TimeoutError, socket.gaierror,
                              ssl.SSLError, http.client.IncompleteRead))


def retry_network(label, operation):
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        try:
            return operation()
        except Exception as error:
            if not transient(error):
                raise
            if attempt == DOWNLOAD_ATTEMPTS:
                raise RuntimeError(f'{label}: network failed after {attempt} attempts') from error
            delay = 2 ** attempt
            print(f'{label}: transient {type(error).__name__}; '
                  f'attempt {attempt}/{DOWNLOAD_ATTEMPTS}, retry in {delay}s', flush=True)
            time.sleep(delay)


def wheel_metadata():
    def read():
        request = urllib.request.Request('https://pypi.org/pypi/imageio-ffmpeg/0.6.0/json',
                                         headers={'User-Agent': 'lw.HomeNAS-CI'})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    return retry_network('FFmpeg wheel metadata (pypi.org)', read)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verified(path, record):
    if not path.is_file():
        return False
    actual_size=path.stat().st_size
    actual_hash=digest(path)
    if actual_size != record['bytes'] or actual_hash != record['sha256']:
        hint=''
        if actual_size <= 4*1024*1024 and hashlib.sha256(path.read_bytes().replace(b'\r\n',b'\n')).hexdigest()==record['sha256']:
            hint='\nGit CRLF conversion changed pinned bytes. Restore with tools/ffmpeg/** -text; do not change the pin or skip validation.'
        raise ValueError('Pinned FFmpeg dependency differs: '+path.name+'\nExpected bytes/SHA256: '+str(record['bytes'])+' / '+record['sha256']+'\nActual bytes/SHA256:   '+str(actual_size)+' / '+actual_hash+hint)
    return True


def download(url, path, expected_hash, maximum, expected_size=None):
    path = Path(path)
    label = f'FFmpeg dependency {path.name} ({urllib.parse.urlsplit(url).hostname})'
    print(f'{label}: downloading with up to {DOWNLOAD_ATTEMPTS} attempts', flush=True)

    def fetch():
        # Keep an incomplete response separate from both the final file and other attempts.
        checksum = hashlib.sha256()
        total = 0
        temporary = None
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'lw.HomeNAS-CI'})
            with urllib.request.urlopen(request, timeout=120) as response:
                length = response.headers.get('Content-Length')
                length = int(length) if length is not None else None
                if length is not None and (length < 0 or length > maximum):
                    raise ValueError('Download exceeds pinned size limit')
                with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name+'.',
                                                  suffix='.part', delete=False) as out:
                    temporary = Path(out.name)
                    while block := response.read(1024 * 1024):
                        total += len(block)
                        if total > maximum:
                            raise ValueError('Download exceeds pinned size limit')
                        out.write(block)
                        checksum.update(block)
                if (length is not None and total < length) or (expected_size is not None and total < expected_size):
                    raise http.client.IncompleteRead(b'', max(length or 0, expected_size or 0)-total)
            if expected_size is not None and total != expected_size:
                raise ValueError('Download pinned size mismatch')
            if checksum.hexdigest() != expected_hash:
                raise ValueError('Download SHA256 mismatch')
            temporary.replace(path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)

    retry_network(label, fetch)


def restore(folder, verify_only=False):
    records = json.loads((folder / 'provenance.json').read_text(encoding='utf-8'))
    executable = records['ffmpeg.exe']
    if not verified(folder / 'ffmpeg.exe', executable):
        if verify_only:
            raise ValueError('Missing ffmpeg.exe')
        metadata = wheel_metadata()
        wheel = next(item for item in metadata['urls'] if item['filename'] == executable['origin_wheel'])
        if wheel['digests']['sha256'] != executable['origin_wheel_sha256']:
            raise ValueError('PyPI wheel differs from checked-in pin')
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / 'ffmpeg.whl'
            download(wheel['url'], archive, executable['origin_wheel_sha256'], 128 * 1024 * 1024)
            with zipfile.ZipFile(archive) as z:
                names = [n for n in z.namelist() if n.startswith('imageio_ffmpeg/binaries/ffmpeg-win-x86_64-') and n.endswith('.exe')]
                if len(names) != 1 or z.getinfo(names[0]).file_size != executable['bytes']:
                    raise ValueError('Unexpected FFmpeg wheel layout')
                content = z.read(names[0])
            if hashlib.sha256(content).hexdigest() != executable['sha256']:
                raise ValueError('Extracted FFmpeg hash mismatch')
            (folder / 'ffmpeg.exe').write_bytes(content)
    for name, record in records.items():
        if Path(name).name != name:
            raise ValueError('Invalid provenance file name')
        target = folder / name
        if verified(target, record):
            continue
        if verify_only or 'url' not in record:
            raise ValueError('Missing FFmpeg dependency: ' + name)
        download(record['url'], target, record['sha256'], record['bytes'], record['bytes'])
        verified(target, record)
    print('Verified pinned FFmpeg executable, source archive and license files.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify-only', action='store_true')
    restore(ROOT / 'tools/ffmpeg', parser.parse_args().verify_only)
