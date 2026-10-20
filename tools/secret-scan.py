#!/usr/bin/env python3
"""Check tracked files without printing secret values."""
from pathlib import Path
import re,subprocess,sys
root=Path(__file__).resolve().parent.parent
names=subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
key=''
config=root/'config.properties'
if config.exists():
 for line in config.read_text().splitlines():
  if line.partition('=')[0].strip()=='tmdb_key': key=line.partition('=')[2].strip()
problems=[]
for name in names:
 if not name:continue
 p=root/name
 if name=='config.properties' or p.suffix in ('.keystore','.p12','.pfx','.apk'):
  problems.append((name,'private build file'));continue
 if not p.is_file():continue
 data=p.read_bytes()
 if key and len(key)>12 and key.encode() in data:problems.append((name,'local API credential'))
 if any(b'-----BEGIN '+kind+b'-----' in data for kind in (b'PRIVATE KEY',b'RSA PRIVATE KEY')):
  problems.append((name,'private key material'))
 if p.suffix.lower() in ('.md','.html','.txt'):
  text=data.decode('utf-8',errors='replace')
  if re.search(r'/Users/[^/\s]+/',text):problems.append((name,'personal filesystem path'))
  if re.search(r'\b(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})\b',text):
   problems.append((name,'private network address'))
if problems:
 for name,reason in problems:print(f'{name}: {reason}')
 raise SystemExit(1)
print(f'Checked {sum(bool(n) for n in names)} tracked paths. No configured credential or private build file was found.')
