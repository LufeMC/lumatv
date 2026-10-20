#!/usr/bin/env python3
"""Download only the reviewed player artifacts with matching SHA-256 hashes."""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

root = Path(__file__).resolve().parent
cache = root / '.player-libs'
cache.mkdir(exist_ok=True)
for entry in json.loads((root / 'player-dependencies.lock').read_text()):
    target = cache / entry['file']
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == entry['sha256']:
        continue
    if not entry['url'].startswith(('https://dl.google.com/dl/android/maven2/', 'https://repo.maven.apache.org/maven2/')):
        raise RuntimeError('Invalid dependency host')
    with urlopen(entry['url'], timeout=30) as response:
        data = response.read(20 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != entry['sha256']:
        raise RuntimeError('Dependency checksum failed: ' + entry['file'])
    target.write_bytes(data)
    print('Downloaded ' + entry['file'])
