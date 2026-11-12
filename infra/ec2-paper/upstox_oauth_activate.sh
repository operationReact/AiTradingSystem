#!/usr/bin/env bash
# Read-only customer Upstox OAuth activation on exact existing PAPER EC2.
# Credentials never leave SSM -> backend SDK; no token/trade in workflow logs.
set -Eeuo pipefail
umask 077
root=/opt/aitradingsystem-paper
expected_sha="$1"
fail() { printf 'MINTTICK_UPSTOX_OAUTH_RESULT:%s\n' "$1"; exit 4; }
[[ "$expected_sha" =~ ^[0-9a-f]{40}$ ]] || fail BAD_RELEASE_SHA
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64 || fail HOST_MISMATCH
test -d "$root" && test ! -L "$root" || fail HOST_MISMATCH
for f in runtime.env .env paper-compose.yml run_paper.sh; do
  test -f "$root/$f" && test ! -L "$root/$f" || fail HOST_MISMATCH
done
test "$(stat -c '%a' "$root/runtime.env")" = 600 || fail RUNTIME_ENV_UNSAFE
test "$(stat -c '%U' "$root/runtime.env")" = root || fail RUNTIME_ENV_UNSAFE
sha="$(sed -n 's/^RELEASE_SHA=//p' "$root/.env" | tail -n 1)"
test "$sha" = "$expected_sha" || fail DEPLOYED_SHA_MISMATCH
test "$(docker image inspect "aitradingsystem-paper-backend-arm64:$sha" --format '{{.Architecture}}' 2>/dev/null)" = arm64 || fail IMAGE_NOT_FOUND
grep -q 'MINTTICK_UPSTOX_OAUTH_SSM_ENABLED:' "$root/paper-compose.yml" || fail BRIDGE_NOT_DEPLOYED
grep -q 'TRADING_MODE: PAPER' "$root/paper-compose.yml" || fail NOT_PAPER

# Preflight exact three parameter values in memory only, never output values.
# Decryption permission and callback match are proven before changing the host.
python3 - <<'PY' || fail SSM_PARAMETERS_UNAVAILABLE
import json, subprocess, sys
names = ['/minttick/upstox/client-id', '/minttick/upstox/client-secret',
         '/minttick/upstox/redirect-uri']
try:
    cmd = ['aws', 'ssm', 'get-parameters', '--region', 'ap-south-1',
           '--names', *names, '--with-decryption', '--output', 'json',
           '--no-cli-pager']
    response = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
    if response.returncode:
        raise ValueError('parameter access')
    data = json.loads(response.stdout)
    values = {p['Name']: p for p in data.get('Parameters', [])}
    if len(values) != 3 or data.get('InvalidParameters'):
        raise ValueError('missing')
    for name in names[:2]:
        if values[name]['Type'] != 'SecureString' or not values[name]['Value'].strip():
            raise ValueError('credential type')
    if (values[names[2]]['Type'] not in ('String', 'SecureString')
            or values[names[2]]['Value'] !=
            'https://api.minttick.com/api/customer/brokers/upstox/callback'):
        raise ValueError('callback mismatch')
except Exception:
    sys.exit(5)
PY

work="$(mktemp -d "$root/.upstox-oauth.XXXXXXXX")"
chmod 0700 "$work"
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT
cp -p "$root/runtime.env" "$work/previous.env"
awk '!/^MINTTICK_UPSTOX_OAUTH_SSM_ENABLED=/' "$work/previous.env" > "$work/next.env"
printf 'MINTTICK_UPSTOX_OAUTH_SSM_ENABLED=true\n' >> "$work/next.env"
install -o root -g root -m 0600 "$work/next.env" "$root/runtime.env"
if ! bash "$root/run_paper.sh" "$sha" > "$work/activation.log" 2>&1; then
  install -o root -g root -m 0600 "$work/previous.env" "$root/runtime.env"
  if bash "$root/run_paper.sh" "$sha" > "$work/rollback.log" 2>&1; then
    fail RESTART_FAILED_ROLLED_BACK
  fi
  fail RESTART_FAILED_ROLLBACK_UNVERIFIED
fi
# Compose up -d does NOT restart an unchanged container just because an
# AWS SSM SecureString changed. UpstoxProperties is read only at Spring startup.
# Force a backend process restart after the validated host release has started,
# then confirm the read-only API is healthy. Never log any secret or URL.
if ! docker compose --env-file "$root/.env" -f "$root/paper-compose.yml" \
    restart backend > "$work/backend-restart.log" 2>&1; then
  install -o root -g root -m 0600 "$work/previous.env" "$root/runtime.env"
  if bash "$root/run_paper.sh" "$sha" > "$work/rollback.log" 2>&1; then
    fail RESTART_FAILED_ROLLED_BACK
  fi
  fail RESTART_FAILED_ROLLBACK_UNVERIFIED
fi
healthy=false
for attempt in $(seq 1 40); do
  if curl --fail --silent --connect-timeout 2 --max-time 4 \
      --output /dev/null http://127.0.0.1:8080/api/health 2>/dev/null; then
    healthy=true
    break
  fi
  sleep 2
done
if [ "$healthy" != true ]; then
  install -o root -g root -m 0600 "$work/previous.env" "$root/runtime.env"
  if bash "$root/run_paper.sh" "$sha" > "$work/rollback.log" 2>&1; then
    fail RESTART_FAILED_ROLLED_BACK
  fi
  fail RESTART_FAILED_ROLLBACK_UNVERIFIED
fi
# The process has reloaded SSM. A successful broker OAuth response must still
# be independently verified; health alone cannot confirm the app key.

# Do not expose compose config: it may contain other runtime credentials.
if ! docker compose --env-file "$root/.env" -f "$root/paper-compose.yml" config --format json 2>/dev/null |
  python3 -c 'import json,sys; env=json.load(sys.stdin)["services"]["backend"]["environment"]; assert env["TRADING_MODE"]=="PAPER" and env["MINTTICK_UPSTOX_OAUTH_SSM_ENABLED"]=="true"'; then
  install -o root -g root -m 0600 "$work/previous.env" "$root/runtime.env"
  if bash "$root/run_paper.sh" "$sha" > "$work/rollback.log" 2>&1; then
    fail FLAG_NOT_ACTIVE_ROLLED_BACK
  fi
  fail FLAG_NOT_ACTIVE_ROLLBACK_UNVERIFIED
fi
# Configured backend still requires customer Cognito JWT and browser OAuth
# before connected=true. This host validation intentionally makes no trades.
printf 'MINTTICK_UPSTOX_OAUTH_RESULT:HOST_ACTIVATED_READ_ONLY\n'
