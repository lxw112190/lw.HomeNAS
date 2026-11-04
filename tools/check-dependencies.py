"""Verify vendored source files against the checked-in lock, without networking."""
import hashlib
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
lock=json.loads((root/'third_party/dependencies.lock.json').read_text(encoding='utf-8'))
for record in lock['files']:
    path=root/record['path']
    if hashlib.sha256(path.read_bytes()).hexdigest()!=record['sha256']:
        raise SystemExit('Dependency mismatch: '+record['path'])
print('Verified',len(lock['files']),'vendored dependency files.')
