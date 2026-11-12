#!/usr/bin/env bash
# Guarded one-host PAPER backend restart only; no broker orders or secrets logged.
set -Eeuo pipefail
umask 077
root=/opt/aitradingsystem-paper
fail() { printf 'MINTTICK_UPSTOX_RELOAD_RESULT:%s\n' "$1"; exit 4; }
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64 || fail HOST_MISMATCH
test -d "$root" && test ! -L "$root" || fail HOST_MISMATCH
for f in runtime.env .env paper-compose.yml run_paper.sh; do
  test -f "$root/$f" && test ! -L "$root/$f" || fail HOST_MISMATCH
done
test "$(stat -c '%a' "$root/runtime.env")" = 600 || fail RUNTIME_ENV_UNSAFE
sha="$(sed -n 's/^RELEASE_SHA=//p' "$root/.env" | tail -n 1)"
test "$sha" = "567db8beeefe02fd24852462c320fc1c667cc775" || fail DEPLOYED_SHA_CHANGED
test "$(docker image inspect "aitradingsystem-paper-backend-arm64:$sha" --format '{{.Architecture}}' 2>/dev/null)" = arm64 || fail IMAGE_MISSING
grep -Fq 'TRADING_MODE: PAPER' "$root/paper-compose.yml" || fail NOT_PAPER

# Validate the protected SSM identity and actual running container environment
# without printing any response bodies, credentials or container environment.
python3 - "$root" <<'PY' || fail PRECHECK_FAILED
import hashlib,hmac,json,pathlib,subprocess,sys
root=pathlib.Path(sys.argv[1])
expected='22cf6c93ded86ab2a69d567eec9cce77bd3002a473d0aa601ace4fa829d75873'
callback='https://api.minttick.com/api/customer/brokers/upstox/callback'
names=['/minttick/upstox/client-id','/minttick/upstox/client-secret',
       '/minttick/upstox/redirect-uri']
try:
    result=subprocess.run(['aws','ssm','get-parameters','--region','ap-south-1',
        '--names',*names,'--with-decryption','--output','json','--no-cli-pager'],
        capture_output=True,text=True,timeout=25,check=False)
    assert result.returncode==0
    raw=json.loads(result.stdout)
    values={x['Name']:x for x in raw['Parameters']}
    assert len(values)==3 and not raw.get('InvalidParameters')
    assert values[names[0]]['Type']=='SecureString'
    assert values[names[1]]['Type']=='SecureString'
    assert values[names[2]]['Type'] in ('String','SecureString')
    assert hmac.compare_digest(hashlib.sha256(
        values[names[0]]['Value'].strip().encode()).hexdigest(),expected)
    assert values[names[1]]['Value'].strip()
    assert hmac.compare_digest(values[names[2]]['Value'].strip(),callback)
    compose=['docker','compose','--env-file',str(root/'.env'),'-f',str(root/'paper-compose.yml')]
    cfg=subprocess.run(compose+['config','--format','json'],
        capture_output=True,text=True,timeout=15,check=False)
    assert cfg.returncode==0
    configured=json.loads(cfg.stdout)['services']['backend']['environment']
    assert configured['TRADING_MODE']=='PAPER'
    assert configured['MINTTICK_UPSTOX_OAUTH_SSM_ENABLED']=='true'
    cid=subprocess.run(compose+['ps','-q','backend'],
        capture_output=True,text=True,timeout=15,check=False)
    assert cid.returncode==0 and cid.stdout.strip()
    inspection=subprocess.run(['docker','inspect','--format','{{json .Config.Env}}',
        cid.stdout.strip()],capture_output=True,text=True,timeout=15,check=False)
    assert inspection.returncode==0
    actual=json.loads(inspection.stdout)
    assert 'TRADING_MODE=PAPER' in actual
    assert 'MINTTICK_UPSTOX_OAUTH_SSM_ENABLED=true' in actual
except (OSError,ValueError,KeyError,TypeError,AssertionError,
        subprocess.TimeoutExpired,json.JSONDecodeError):
    sys.exit(5)
PY

# The backend has already validated the correct SSM key, callback, and secret.
# Restart ONLY the existing PAPER backend to reload UpstoxProperties at startup.
before="$(date -u +%s)"
work="$(mktemp -d "$root/.upstox-reload.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT
chmod 0700 "$work"
if ! docker compose --env-file "$root/.env" -f "$root/paper-compose.yml" \
       restart backend >"$work/restart.log" 2>&1; then
  fail RESTART_FAILED
fi
healthy=false
for attempt in $(seq 1 45); do
  if curl --silent --fail --connect-timeout 2 --max-time 5 \
       --output /dev/null http://127.0.0.1:8080/api/health 2>/dev/null; then
    healthy=true
    break
  fi
  sleep 2
done
test "$healthy" = true || fail HEALTH_FAILED
python3 - "$root" "$before" <<'PY' || fail START_TIME_NOT_VERIFIED
import datetime as dt,pathlib,subprocess,sys
root=pathlib.Path(sys.argv[1])
compose=['docker','compose','--env-file',str(root/'.env'),'-f',str(root/'paper-compose.yml')]
cid=subprocess.run(compose+['ps','-q','backend'],capture_output=True,text=True,timeout=15)
assert cid.returncode==0 and cid.stdout.strip()
started=subprocess.run(['docker','inspect','--format','{{.State.StartedAt}}',cid.stdout.strip()],
    capture_output=True,text=True,timeout=15)
assert started.returncode==0
start=dt.datetime.fromisoformat(started.stdout.strip().replace('Z','+00:00')).timestamp()
assert start>=float(sys.argv[2])-2
PY
printf 'MINTTICK_UPSTOX_RELOAD_RESULT:BACKEND_RESTARTED_HEALTHY\n'
