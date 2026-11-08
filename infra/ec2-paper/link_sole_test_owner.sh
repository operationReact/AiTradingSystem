#!/usr/bin/env bash
# One-time private DEV/PAPER owner-link repair. Never prints a Cognito
# subject, customer data, AWS parameter, PIN, API key, token or TOTP seed.
set -Eeuo pipefail
umask 077
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
python3 - <<'PY'
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.request import urlopen

root=Path('/opt/aitradingsystem-paper')
issuer='https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_jz2T8qbiY'
subject_ok=re.compile(r'[A-Za-z0-9_-]{10,128}\Z')
paths=[root/'runtime.env',root/'.env']
compose=root/'paper-compose.yml'
if not root.is_dir() or root.is_symlink() or not compose.is_file() or compose.is_symlink():
    raise SystemExit('BLOCKED: exact PAPER host files are not available')
old_files={}
for path in paths:
    if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
        raise SystemExit('BLOCKED: protected host environment permissions')
    old_files[path]=path.read_text()
def configured_subject(data):
    values=[line.split('=',1)[1].strip() for line in data.splitlines()
            if line.startswith('MINTTICK_ANGEL_ONE_OWNER_SUB=')]
    if len(values)!=1 or not subject_ok.fullmatch(values[0]):
        raise SystemExit('BLOCKED: existing owner binding not exact')
    return values[0]
previous=configured_subject(old_files[paths[0]])
if configured_subject(old_files[paths[1]]) != previous:
    raise SystemExit('BLOCKED: host and compose owner settings disagree')
compose_text=compose.read_text()
if 'MINTTICK_ANGEL_ONE_SSM_STREAM_ENABLED: "true"' not in compose_text:
    raise SystemExit('BLOCKED: protected SSM broker mode is not enabled')

psql=['docker','compose','--env-file',str(root/'.env'),'-f',str(compose),
      'exec','-T','postgres','psql','-X','-Atq','-v','ON_ERROR_STOP=1',
      '-U','aitrading','-d','aitrading','-c']
sql=("SELECT ai.subject FROM app_auth_identity ai "
     "JOIN app_user u ON u.id=ai.user_id "
     "WHERE ai.provider='cognito' AND ai.issuer='"+issuer+"' "
     "AND u.role='CUSTOMER' AND u.status='ACTIVE' "
     "ORDER BY ai.subject;")
query=subprocess.run(psql+[sql],capture_output=True,text=True,timeout=22,check=True)
subjects=query.stdout.splitlines()
if len(subjects)!=1 or not subject_ok.fullmatch(subjects[0]):
    raise SystemExit('BLOCKED: exactly one active Cognito CUSTOMER is required')
subject=subjects[0]
if subject == previous:
    print('OWNER_LINK: ALREADY_VALID')
    raise SystemExit(0)

# Never rebind a legitimate existing owner to another account.
check_sql=("SELECT count(*) FROM app_auth_identity ai JOIN app_user u ON "
           "u.id=ai.user_id WHERE ai.provider='cognito' "
           "AND ai.issuer='"+issuer+"' AND ai.subject='"+previous+"' "
           "AND u.role='CUSTOMER' AND u.status='ACTIVE';")
linked=subprocess.run(psql+[check_sql],capture_output=True,text=True,
                      timeout=22,check=True)
if linked.stdout.strip()!='0':
    raise SystemExit('BLOCKED: existing owner is already linked; refuse reassignment')

def replace_owner(content):
    lines=content.splitlines(keepends=True)
    found=0
    for i,line in enumerate(lines):
        if line.startswith('MINTTICK_ANGEL_ONE_OWNER_SUB='):
            lines[i]='MINTTICK_ANGEL_ONE_OWNER_SUB='+subject+'\n'
            found+=1
    if found!=1: raise RuntimeError('owner key not unique')
    return ''.join(lines)
def atomic_write(path,content):
    fd,tmp=tempfile.mkstemp(prefix='.minttick-owner-',dir=root)
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,'w') as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

updated=False
try:
    for path in paths:
        atomic_write(path,replace_owner(old_files[path]))
    updated=True
    subprocess.run(['docker','compose','--env-file',str(root/'.env'),
        '-f',str(compose),'config','--quiet'],check=True,
        capture_output=True,timeout=22)
    subprocess.run(['docker','compose','--env-file',str(root/'.env'),
        '-f',str(compose),'up','-d','--no-deps','--force-recreate','backend'],
        check=True,capture_output=True,timeout=65)
    healthy=False
    for _ in range(24):
        try:
            with urlopen('http://127.0.0.1:8080/api/health',timeout=3) as response:
                healthy=response.status==200
        except Exception: pass
        if healthy: break
        time.sleep(3)
    if not healthy: raise RuntimeError('backend failed readiness')
except Exception:
    if updated:
        for path in paths:
            atomic_write(path,old_files[path])
        subprocess.run(['docker','compose','--env-file',str(root/'.env'),
            '-f',str(compose),'up','-d','--no-deps','--force-recreate','backend'],
            check=False,capture_output=True,timeout=65)
    raise SystemExit('BLOCKED: owner link could not be activated; previous config restored')
print('OWNER_LINK: REPAIRED_AND_BACKEND_READY')
PY
