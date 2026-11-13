"""Hand off the tested Windows build between jobs without compiling it again."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()


def file_digest(path):
    with path.open('rb') as stream:
        return digest(stream)


def identity(root, number, sha):
    require(type(number) is int and 1 <= number <= 2147483647, 'Invalid CI number')
    require(re.fullmatch('[a-f0-9]{40}', sha) is not None, 'Invalid source commit')
    version = (root/'VERSION').read_text(encoding='utf-8').strip()
    require(re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?', version) is not None, 'Invalid source version')
    return version, f'lw.HomeNAS-{version}-ci{number}-win-x64'


def inspect(archive, stem, root):
    """Validate every path; only the EXE and pinned FFmpeg are restored later."""
    entries = {}
    names = set()
    for item in archive.infolist():
        name = item.filename
        parts = PurePosixPath(name).parts
        require(name and '\\' not in name and ':' not in name and not name.startswith('/')
                and all(part not in ('', '.', '..') for part in name.rstrip('/').split('/'))
                and parts[0] == stem, 'Unsafe server archive path')
        require(name.rstrip('/').casefold() not in names, 'Duplicate server archive path')
        names.add(name.rstrip('/').casefold())
        kind = (item.external_attr >> 16) & 0o170000
        require(kind in (0, 0o100000, 0o040000) and not item.flag_bits & 1, 'Unsupported server archive entry')
        if not item.is_dir():
            entries[name[len(stem)+1:]] = item
    require('NasServer.exe' in entries, 'Missing tested server executable')
    executable = entries['NasServer.exe']
    require(0 < executable.file_size <= 128*1024*1024, 'Invalid server executable size')
    with archive.open(executable) as stream:
        exe_hash = digest(stream)
    pins = json.loads((root/'tools/ffmpeg/provenance.json').read_text(encoding='utf-8'))
    selected = {'NasServer.exe': executable}
    for name, pin in pins.items():
        require(re.fullmatch('[A-Za-z0-9_.-]+', name) is not None, 'Invalid FFmpeg pin name')
        key = 'tools/ffmpeg/'+name
        require(key in entries, 'Missing pinned FFmpeg dependency: '+name)
        item = entries[key]
        require(item.file_size == pin['bytes'], 'Pinned FFmpeg size differs: '+name)
        with archive.open(item) as stream:
            require(digest(stream) == pin['sha256'], 'Pinned FFmpeg hash differs: '+name)
        selected[key] = item
    return selected, exe_hash


def create(archive_path, output, number, sha, root=ROOT):
    version, stem = identity(root, number, sha)
    require(archive_path.name == stem+'.zip', 'Unexpected server archive name')
    require(not output.exists(), 'Server artifact output already exists')
    with zipfile.ZipFile(archive_path) as archive:
        _, exe_hash = inspect(archive, stem, root)
    require(exe_hash == file_digest(root/'build/app/Release/NasServer.exe'), 'Archive does not contain the tested server executable')
    checksum = file_digest(archive_path)
    record = dict(schema=1, commit=sha, ciNumber=number, version=version,
                  archive=archive_path.name, archiveSha256=checksum, executableSha256=exe_hash)
    output.mkdir(parents=True)
    shutil.copyfile(archive_path, output/archive_path.name)
    (output/(archive_path.name+'.sha256')).write_text(checksum+'  '+archive_path.name+'\n', encoding='utf-8')
    (output/'server-build.json').write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')


def restore(artifact, number, sha, root=ROOT):
    version, stem = identity(root, number, sha)
    record = json.loads((artifact/'server-build.json').read_text(encoding='utf-8'))
    require(record.get('schema') == 1 and record.get('commit') == sha and record.get('ciNumber') == number
            and record.get('version') == version, 'Server artifact belongs to another commit, CI run or version')
    name = stem+'.zip'
    require(record.get('archive') == name, 'Unexpected server archive name')
    archive_path = artifact/name
    require(file_digest(archive_path) == record.get('archiveSha256'), 'Server archive hash differs')
    require((artifact/(name+'.sha256')).read_text(encoding='utf-8').strip()
            == record['archiveSha256']+'  '+name, 'Server archive checksum file differs')
    with zipfile.ZipFile(archive_path) as archive:
        selected, exe_hash = inspect(archive, stem, root)
        require(exe_hash == record.get('executableSha256'), 'Tested server executable hash differs')
        # No paths supplied by the ZIP are used as extraction destinations.
        for relative, entry in selected.items():
            target = root/('build/app/Release/NasServer.exe' if relative == 'NasServer.exe' else relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=target.parent, suffix='.part', delete=False) as out:
                    temporary = Path(out.name)
                    with archive.open(entry) as source:
                        shutil.copyfileobj(source, out, 1024*1024)
                os.replace(temporary, target)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
    print('Restored tested Windows executable and pinned FFmpeg from the same CI commit; no rebuild.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('create', 'restore'))
    parser.add_argument('--ci-number', required=True, type=int)
    parser.add_argument('--ci-sha', required=True)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--artifact-directory', type=Path)
    args = parser.parse_args()
    if args.operation == 'create':
        if not args.archive or not args.output:
            parser.error('create requires --archive and --output')
        create(args.archive, args.output, args.ci_number, args.ci_sha)
    else:
        if not args.artifact_directory:
            parser.error('restore requires --artifact-directory')
        restore(args.artifact_directory, args.ci_number, args.ci_sha)
