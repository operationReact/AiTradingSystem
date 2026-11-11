#!/usr/bin/env bash
# Owner-only Upstox individual-app TOTP login activation on existing PAPER EC2.
# SSM values are inspected in process memory, NEVER printed or exported.
# No Upstox token request, broker order, SSH, IAM mutation or EC2 lifecycle change.
set -Eeuo pipefail
umask 077
root=/opt/aitradingsystem-paper
expected_sha="${1:-}"
fail() { printf 'MINTTICK_UPSTOX_TOTP_RESULT:%s\n' "$1"; exit 4; }

[[ "$expected_sha" =~ ^[0-9a-f]{40}$ ]] || fail BAD_RELEASE_SHA
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64 || fail HOST_MISMATCH
test -d "$root" && test ! -L "$root" || fail HOST_MISMATCH
for file in runtime.env .env paper-compose.yml run_paper.sh; do
  test -f "$root/$file" && test ! -L "$root/$file" || fail HOST_MISMATCH
done
test "$(stat -c '%a' "$root/runtime.env")" = 600 &&
  test "$(stat -c '%U' "$root/runtime.env")" = root &&
  test "$(stat -c '%a' "$root/.env")" = 600 || fail RUNTIME_ENV_UNSAFE
sha="$(sed -n 's/^RELEASE_SHA=//p' "$root/.env" | tail -n 1)"
test "$sha" = "$expected_sha" || fail DEPLOYED_SHA_MISMATCH
test "$(docker image inspect "aitradingsystem-paper-backend-arm64:$sha" --format '{{.Architecture}}' 2>/dev/null)" = arm64 || fail IMAGE_NOT_FOUND
grep -Fq 'TRADING_MODE: PAPER' "$root/paper-compose.yml" || fail NOT_PAPER
grep -Fq 'MINTTICK_UPSTOX_OWNER_TOTP_ENABLED:' "$root/paper-compose.yml" &&
  grep -Fq 'MINTTICK_UPSTOX_OAUTH_SSM_ENABLED:' "$root/paper-compose.yml" || fail BRIDGE_NOT_DEPLOYED

# No user-supplied URL, PIN, seed, account ID, or OAuth token is forwarded.
# The existing Cognito owner binding and OAuth SSM bridge must already be active.
python3 - "$root/runtime.env" <<'PY' || fail OWNER_OR_OAUTH_NOT_READY
import re, sys
from pathlib import Path
try:
    values = {}
    for line in Path(sys.argv[1]).read_text().splitlines():
        if line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        if key in {'MINTTICK_ANGEL_ONE_OWNER_SUB',
                   'MINTTICK_UPSTOX_OAUTH_SSM_ENABLED'}:
            if key in values: raise ValueError('duplicate')
            values[key] = value.strip()
    assert re.fullmatch(r'[A-Za-z0-9_-]{10,128}',
                        values.get('MINTTICK_ANGEL_ONE_OWNER_SUB', ''))
    assert values.get('MINTTICK_UPSTOX_OAUTH_SSM_ENABLED') == 'true'
except Exception:
    sys.exit(4)
PY

# Must prove instance-role permission to decrypt the exact new SecureStrings.
# Returned values remain only in the private Python process and are not logged.
python3 - <<'PY' || fail SSM_OWNER_SECRETS_UNAVAILABLE
import base64, json, re, subprocess, sys
names = ('/minttick/upstox/login-pin', '/minttick/upstox/totp-secret')
try:
    result = subprocess.run(
        ['aws','ssm','get-parameters','--region','ap-south-1',
         '--names',*names,'--with-decryption','--no-cli-pager',
         '--output','json'],
        capture_output=True, text=True, timeout=25, check=False)
    if result.returncode: raise ValueError('SSM role or KMS unavailable')
    data = json.loads(result.stdout)
    found = {v['Name']: v for v in data.get('Parameters', [])}
    if set(found) != set(names) or data.get('InvalidParameters'):
        raise ValueError('missing secret')
    if any(found[n]['Type'] != 'SecureString' for n in names):
        raise ValueError('wrong type')
    pin = found[names[0]]['Value']
    if not re.fullmatch('[0-9]{6}', pin):
        raise ValueError('invalid PIN')
    seed = found[names[1]]['Value'].replace(' ', '').replace('-', '').upper().rstrip('=')
    if not re.fullmatch('[A-Z2-7]{16,256}', seed):
        raise ValueError('invalid seed alphabet or length')
    if len(base64.b32decode(seed+'='*((-len(seed))%8), casefold=True)) < 10:
        raise ValueError('short seed')
except Exception:
    sys.exit(4)
PY

# Idempotent: avoid unnecessarily rotating a valid Upstox session.
if grep -Fxq 'MINTTICK_UPSTOX_OWNER_TOTP_ENABLED=true' "$root/runtime.env"; then
  if docker compose --env-file "$root/.env" -f "$root/paper-compose.yml" config --format json 2>/dev/null |
    python3 -c 'import json,sys; d=json.load(sys.stdin)["services"]["backend"]["environment"]; assert d["TRADING_MODE"]=="PAPER" and d["MINTTICK_UPSTOX_OWNER_TOTP_ENABLED"]=="true"'; then
    printf 'MINTTICK_UPSTOX_TOTP_RESULT:HOST_ALREADY_ACTIVE_READ_ONLY\n'
    exit 0
  fi
fi

work="$(mktemp -d "$root/.upstox-totp.XXXXXXXX")"
chmod 0700 "$work"
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT
cp -p "$root/runtime.env" "$work/previous.env"
awk '!/^MINTTICK_UPSTOX_OWNER_TOTP_ENABLED=/' "$work/previous.env" > "$work/next.env"
printf 'MINTTICK_UPSTOX_OWNER_TOTP_ENABLED=true\n' >> "$work/next.env"
install -o root -g root -m 0600 "$work/next.env" "$root/runtime.env"

if ! bash "$root/run_paper.sh" "$sha" > "$work/activation.log" 2>&1; then
  install -o root -g root -m 0600 "$work/previous.env" "$root/runtime.env"
  if bash "$root/run_paper.sh" "$sha" > "$work/rollback.log" 2>&1; then
    fail RESTART_FAILED_ROLLED_BACK
  fi
  fail RESTART_FAILED_ROLLBACK_UNVERIFIED
fi
if ! docker compose --env-file "$root/.env" -f "$root/paper-compose.yml" config --format json 2>/dev/null |
  python3 -c 'import json,sys; d=json.load(sys.stdin)["services"]["backend"]["environment"]; assert d["TRADING_MODE"]=="PAPER" and d["MINTTICK_UPSTOX_OAUTH_SSM_ENABLED"]=="true" and d["MINTTICK_UPSTOX_OWNER_TOTP_ENABLED"]=="true"'; then
  install -o root -g root -m 0600 "$work/previous.env" "$root/runtime.env"
  if bash "$root/run_paper.sh" "$sha" > "$work/rollback.log" 2>&1; then
    fail FLAG_NOT_ACTIVE_ROLLED_BACK
  fi
  fail FLAG_NOT_ACTIVE_ROLLBACK_UNVERIFIED
fi
# Never call Upstox automatically: provider eligibility and customer session
# must be confirmed via authenticated owner-only API / in-app login.
printf 'MINTTICK_UPSTOX_TOTP_RESULT:HOST_ACTIVATED_READ_ONLY\n'
