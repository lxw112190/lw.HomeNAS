"""UI ZIP limits shared with host checks; must stay compatible with distributed APKs."""
import re

RESOURCE_PATH_PATTERN = r'[A-Za-z0-9_./-]+\.(html|css|js|png|jpg|jpeg|webp|svg|woff2|json)'
MAX_FILES = 200
MAX_UNPACKED_BYTES = 20 * 1024 * 1024


def validate_ui_entries(entries):
    names = set()
    total = 0
    for name, size in entries:
        if (not re.fullmatch(RESOURCE_PATH_PATTERN, name)
                or name.startswith('/') or '..' in name):
            raise ValueError(f'APK rejects UI resource path or type: {name}')
        if name in names:
            raise ValueError(f'Duplicate UI resource: {name}')
        names.add(name)
        total += size
        if size < 0 or total > MAX_UNPACKED_BYTES or len(names) > MAX_FILES:
            raise ValueError('UI resources exceed APK extraction limits')
    missing = {'index.html', 'app.js', 'style.css'} - names
    if missing:
        raise ValueError('Missing required UI resources: ' + ', '.join(sorted(missing)))
