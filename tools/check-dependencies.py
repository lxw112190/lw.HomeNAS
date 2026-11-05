"""Verify vendored source files against the checked-in lock, without networking."""
import hashlib
import json
from pathlib import Path

root=Path(__file__).resolve().parents[1]
lock=json.loads((root/'third_party/dependencies.lock.json').read_text(encoding='utf-8'))
for record in lock['files']:
    path=root/record['path']
    content=path.read_bytes()
    actual=hashlib.sha256(content).hexdigest()
    if actual!=record['sha256']:
        hint=''
        if hashlib.sha256(content.replace(b'\r\n',b'\n')).hexdigest()==record['sha256']:
            hint='\nGit CRLF conversion changed pinned bytes. Restore the dependency with third_party/** -text; do not change the lock or skip this check.'
        raise SystemExit('Dependency mismatch: '+record['path']+'\nExpected SHA256: '+record['sha256']+'\nActual SHA256:   '+actual+hint)
print('Verified',len(lock['files']),'vendored dependency files.')
