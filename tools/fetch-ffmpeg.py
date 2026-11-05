"""Restore the pinned standalone Windows FFmpeg and source archive for CI packaging."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verified(path, record):
    if not path.is_file():
        return False
    if path.stat().st_size != record['bytes'] or digest(path) != record['sha256']:
        raise ValueError('Pinned FFmpeg dependency differs: ' + path.name)
    return True


def download(url, path, expected_hash, maximum):
    request = urllib.request.Request(url, headers={'User-Agent': 'lw.HomeNAS-CI'})
    checksum = hashlib.sha256()
    total = 0
    try:
        with urllib.request.urlopen(request, timeout=120) as response, path.open('wb') as out:
            while block := response.read(1024 * 1024):
                total += len(block)
                if total > maximum:
                    raise ValueError('Download exceeds pinned size limit')
                out.write(block)
                checksum.update(block)
        if checksum.hexdigest() != expected_hash:
            raise ValueError('Download SHA256 mismatch')
    except Exception:
        path.unlink(missing_ok=True)
        raise


def restore(folder, verify_only=False):
    records = json.loads((folder / 'provenance.json').read_text(encoding='utf-8'))
    executable = records['ffmpeg.exe']
    if not verified(folder / 'ffmpeg.exe', executable):
        if verify_only:
            raise ValueError('Missing ffmpeg.exe')
        with urllib.request.urlopen('https://pypi.org/pypi/imageio-ffmpeg/0.6.0/json', timeout=60) as response:
            metadata = json.load(response)
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
        download(record['url'], target, record['sha256'], record['bytes'])
        verified(target, record)
    print('Verified pinned FFmpeg executable, source archive and license files.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify-only', action='store_true')
    restore(ROOT / 'tools/ffmpeg', parser.parse_args().verify_only)
