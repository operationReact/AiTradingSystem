#!/usr/bin/env bash
# Read-only Upstox identity check, on the existing guarded PAPER EC2 only.
set -Eeuo pipefail
umask 077
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
test -f /opt/aitradingsystem-paper/.env
python3 - <<'PY'
import datetime as dt
import hashlib
import hmac
import json
from pathlib import Path
import subprocess

root=Path('/opt/aitradingsystem-paper')
expected_digest='22cf6c93ded86ab2a69d567eec9cce77bd3002a473d0aa601ace4fa829d75873'
expected_redirect='https://api.minttick.com/api/customer/brokers/upstox/callback'
names=['/minttick/upstox/client-id','/minttick/upstox/client-secret','/minttick/upstox/redirect-uri']
def emit(label,value):
    print('MINTTICK_UPSTOX_IDENTITY_'+label+': '+value,flush=True)
def utc(raw):
    try:
        if isinstance(raw,(int,float)):
            return dt.datetime.fromtimestamp(raw,dt.timezone.utc)
        if isinstance(raw,str):
            return dt.datetime.fromisoformat(raw.replace('Z','+00:00')).astimezone(dt.timezone.utc)
    except (TypeError,ValueError,OverflowError):
        pass
    return None
try:
    result=subprocess.run(['aws','ssm','get-parameters','--region','ap-south-1',
        '--names',*names,'--with-decryption','--output','json','--no-cli-pager'],
        capture_output=True,text=True,timeout=25,check=False)
    if result.returncode!=0: raise ValueError('SSM_UNAVAILABLE')
    response=json.loads(result.stdout)
    found={p['Name']:p for p in response.get('Parameters',[]) if isinstance(p,dict)}
    if len(found)!=3 or response.get('InvalidParameters'): raise ValueError('SSM_INCOMPLETE')
    app=found[names[0]]
    secret=found[names[1]]
    callback=found[names[2]]
    if (app.get('Type')!='SecureString' or secret.get('Type')!='SecureString'
            or callback.get('Type') not in ('String','SecureString')):
        raise ValueError('SSM_TYPES_INVALID')
    key=app.get('Value','').strip()
    token=secret.get('Value','')
    redirect=callback.get('Value','').strip()
    emit('KEY_MATCH','YES' if hmac.compare_digest(hashlib.sha256(key.encode()).hexdigest(),
        expected_digest) else 'NO')
    emit('SECRET_PRESENT','YES' if bool(token.strip()) else 'NO')
    emit('CALLBACK_MATCH','YES' if hmac.compare_digest(redirect,expected_redirect) else 'NO')
    try:
        effective=(root/'.env').read_text().splitlines()
        runtime=(root/'runtime.env').read_text().splitlines()
        def enabled(lines):
            return [v.split('=',1)[1].strip() for v in lines
                if v.startswith('MINTTICK_UPSTOX_OAUTH_SSM_ENABLED=')]==['true']
        emit('OAUTH_FLAG','ENABLED' if enabled(effective) and enabled(runtime) else 'NOT_CONFIRMED')
    except (OSError,ValueError):
        emit('OAUTH_FLAG','NOT_CONFIRMED')
    try:
        base=['docker','compose','--env-file',str(root/'.env'),
            '-f',str(root/'paper-compose.yml')]
        cid=subprocess.run(base+['ps','-q','backend'],
            capture_output=True,text=True,timeout=15,check=False)
        if cid.returncode!=0 or not cid.stdout.strip(): raise ValueError('NO_CONTAINER')
        info=subprocess.run(['docker','inspect','--format',
            '{{.State.StartedAt}}',cid.stdout.strip()],
            capture_output=True,text=True,timeout=15,check=False)
        if info.returncode: raise ValueError('NO_START_TIME')
        started=utc(info.stdout.strip())
        modified=[utc(p.get('LastModifiedDate')) for p in found.values()]
        if started is None or any(x is None for x in modified):
            emit('BACKEND_RELOADED','UNKNOWN')
        else:
            emit('BACKEND_RELOADED','YES' if started>max(modified) else 'NO')
    except (OSError,ValueError,subprocess.TimeoutExpired):
        emit('BACKEND_RELOADED','UNKNOWN')
except (OSError,ValueError,KeyError,TypeError,json.JSONDecodeError,subprocess.TimeoutExpired):
    emit('KEY_MATCH','UNKNOWN')
    emit('SECRET_PRESENT','UNKNOWN')
    emit('CALLBACK_MATCH','UNKNOWN')
    emit('OAUTH_FLAG','UNKNOWN')
    emit('BACKEND_RELOADED','UNKNOWN')
PY
