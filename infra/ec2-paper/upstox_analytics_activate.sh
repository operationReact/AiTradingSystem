#!/usr/bin/env bash
# Existing PAPER host only: read-only Upstox Analytics source acceptance.
# Recreate backend only, never full web/DB deployment or any broker order.
set -Eeuo pipefail
umask 077
stage=precheck
accepted=0
work=
failure_code=
rollback_state=not_needed
fail() { failure_code="$1"; exit 5; }
on_exit() {
  local rc=$? reason
  if [ "$rc" -ne 0 ] || [ "$accepted" -ne 1 ]; then
    reason="$failure_code"
    if [ "$rollback_state" = unverified ]; then reason=ROLLBACK_UNVERIFIED; fi
    if [ -z "$reason" ]; then reason=HOST_ACTIVATION_UNEXPECTED; fi
    printf 'MINTTICK_UPSTOX_RESULT:%s\n' "$reason"
    printf 'MINTTICK_UPSTOX_STAGE:%s\n' "$stage" >&2
  fi
  if [ -n "$work" ]; then rm -rf -- "$work" || true; fi
  if [ "$rc" -eq 0 ] && [ "$accepted" -ne 1 ]; then exit 9; fi
}
trap on_exit EXIT
root=/opt/aitradingsystem-paper
parameter=/minttick/upstox/analytics-token
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64 || fail HOST_PRECHECK_FAILED
test -d "$root" && test ! -L "$root" || fail HOST_PRECHECK_FAILED
for f in runtime.env .env paper-compose.yml run_paper.sh; do
  test -f "$root/$f" && test ! -L "$root/$f" || fail HOST_PRECHECK_FAILED
done
test "$(stat -c %a "$root/runtime.env")" = 600 || fail HOST_PRECHECK_FAILED
test "$(stat -c %U "$root/runtime.env")" = root || fail HOST_PRECHECK_FAILED
test "$(stat -c %a "$root/.env")" = 600 || fail HOST_PRECHECK_FAILED

# AWS returns only Parameter.Type, not the token value, to the host process.
stage=ssm_authorization
diagnostic="$(mktemp "$root/.upstox-ssm-check.XXXXXXXX")"
parameter_type="$(aws ssm get-parameter --region ap-south-1 --name "$parameter" \
  --with-decryption --query 'Parameter.Type' --output text \
  --no-cli-pager 2>"$diagnostic" || true)"
if [ "$parameter_type" != SecureString ]; then
  reason=SSM_UNAVAILABLE
  if [ -n "$parameter_type" ]; then
    reason=SSM_NOT_SECURESTRING
  elif grep -Eiq 'ParameterNotFound|parameter.*not.*found' "$diagnostic"; then
    reason=SSM_PARAMETER_MISSING
  elif grep -Eiq 'KMSAccessDenied|kms:Decrypt|KMS.*not authorized' "$diagnostic"; then
    reason=SSM_KMS_DENIED
  elif grep -Eiq 'AccessDenied|not authorized|explicit deny' "$diagnostic"; then
    reason=SSM_ACCESS_DENIED
  elif grep -Eiq 'EndpointConnectionError|Could not connect|timed out|Network' "$diagnostic"; then
    reason=SSM_NETWORK_UNAVAILABLE
  fi
  rm -f -- "$diagnostic"
  fail "$reason"
fi
rm -f -- "$diagnostic"

stage=release_identity
sha="$(sed -n 's/^RELEASE_SHA=//p' "$root/.env" | tail -n 1)"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || fail HOST_RELEASE_IDENTITY_INVALID
test "$(docker image inspect "aitradingsystem-paper-backend-arm64:$sha" \
  --format '{{.Architecture}}' 2>/dev/null)" = arm64 || fail HOST_RELEASE_IDENTITY_INVALID
test "$(docker image inspect "aitradingsystem-paper-backend-arm64:$sha" \
  --format '{{.Config.User}}' 2>/dev/null)" = 10001 || fail HOST_RELEASE_IDENTITY_INVALID
grep -q 'TRADING_MODE: PAPER' "$root/paper-compose.yml" || fail NOT_PAPER
test "$(curl --silent --fail --connect-timeout 3 --max-time 8 \
  https://checkip.amazonaws.com 2>/dev/null || true)" = 3.108.130.17 \
  || fail HOST_EGRESS_IP_DRIFT
stage=backend_precheck
curl --fail --silent --connect-timeout 2 --max-time 6 --output /dev/null \
  http://127.0.0.1:8080/api/health 2>/dev/null || fail BACKEND_PRECHECK_UNHEALTHY
pg="$(docker compose --env-file "$root/.env" -f "$root/paper-compose.yml" \
  ps -q postgres 2>/dev/null || true)"
test -n "$pg" && test "$(docker inspect --format '{{.State.Running}}' "$pg" 2>/dev/null)" = true \
  || fail POSTGRES_NOT_RUNNING

stage=runtime_prepare
work="$(mktemp -d "$root/.upstox-analytics.XXXXXXXX")"
chmod 0700 "$work"
cp -p "$root/runtime.env" "$work/previous.env"
cp -p "$root/.env" "$work/previous.compose.env"
awk '!/^(MINTTICK_UPSTOX_ANALYTICS_(ENABLED|PROBE_ENABLED)|MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED)=/' \
  "$work/previous.env" > "$work/next.env"
awk '!/^(MINTTICK_UPSTOX_ANALYTICS_(ENABLED|PROBE_ENABLED)|MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED)=/' \
  "$work/previous.compose.env" > "$work/next.compose.env"
grep -Eq '^POSTGRES_PASSWORD=[0-9a-f]{64}$' "$work/next.env" || fail HOST_RUNTIME_PASSWORD_INVALID
grep -Eq '^APP_API_KEY=[0-9a-f]{64}$' "$work/next.env" || fail HOST_RUNTIME_OPERATOR_KEY_INVALID
grep -Eq "^RELEASE_SHA=$sha$" "$work/next.compose.env" || fail HOST_RELEASE_IDENTITY_INVALID
for file in "$work/next.env" "$work/next.compose.env"; do
  printf 'MINTTICK_UPSTOX_ANALYTICS_ENABLED=true\n' >> "$file"
  printf 'MINTTICK_UPSTOX_ANALYTICS_PROBE_ENABLED=true\n' >> "$file"
  printf 'MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED=true\n' >> "$file"
  chmod 0600 "$file"
done

# Check exact PAPER mode, all source flags, and localhost-only backend binding.
stage=config_check
if ! docker compose --env-file "$work/next.compose.env" \
  -f "$root/paper-compose.yml" config --format json 2>/dev/null |
  python3 -c 'import json,sys; d=json.load(sys.stdin)["services"]; e=d["backend"]["environment"]; assert e["TRADING_MODE"]=="PAPER"; assert all(e[k]=="true" for k in ("MINTTICK_UPSTOX_ANALYTICS_ENABLED","MINTTICK_UPSTOX_ANALYTICS_PROBE_ENABLED","MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED")); assert {p["host_ip"] for p in d["backend"]["ports"]}=={"127.0.0.1"}'; then
  fail BACKEND_CONFIG_INVALID
fi

recreate_backend() {
  docker compose --env-file "$root/.env" -f "$root/paper-compose.yml" \
    up -d --no-deps --no-build --force-recreate backend > "$work/recreate.log" 2>&1
}
wait_health() {
  local attempt
  for attempt in $(seq 1 40); do
    if curl --fail --silent --connect-timeout 2 --max-time 4 \
      --output /dev/null http://127.0.0.1:8080/api/health 2>/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}
rollback() {
  local ok=true
  install -o root -g root -m 0600 "$work/previous.env" "$root/runtime.env" || ok=false
  install -o root -g root -m 0600 "$work/previous.compose.env" "$root/.env" || ok=false
  if [ "$ok" != true ] || ! recreate_backend || ! wait_health; then
    rollback_state=unverified
  else
    rollback_state=healthy
  fi
}
stage=runtime_publish
if ! install -o root -g root -m 0600 "$work/next.env" "$root/runtime.env" ||
   ! install -o root -g root -m 0600 "$work/next.compose.env" "$root/.env"; then
  rollback
  fail HOST_RUNTIME_INSTALL_FAILED
fi

# Recreate ONLY backend to load effective env; no pg_dump, S3, or web restart.
stage=backend_recreate
if ! recreate_backend; then
  rollback
  fail BACKEND_RECREATE_FAILED
fi
stage=backend_health
if ! wait_health; then
  rollback
  fail BACKEND_RESTART_UNHEALTHY
fi
stage=read_only_probe
operator_key="$(sed -n 's/^APP_API_KEY=//p' "$root/runtime.env" | head -n 1)"
if [[ ! "$operator_key" =~ ^[0-9a-f]{64}$ ]]; then
  rollback
  fail OPERATOR_AUTH_UNAVAILABLE
fi
http_code="$(curl --silent --connect-timeout 3 --max-time 35 \
  -X POST -H "X-API-Key: $operator_key" -H 'Accept: application/json' \
  -o "$work/response.json" -w '%{http_code}' \
  http://127.0.0.1:8080/api/admin/market-data/upstox-analytics/probe \
  2>/dev/null || true)"
unset operator_key
if [ "$http_code" != 200 ]; then
  rollback
  fail PROBE_HTTP_UNAVAILABLE
fi

stage=result_validation
if ! python3 - "$work/response.json" <<'PY'
import json,sys
try:
    with open(sys.argv[1],encoding="utf-8") as f: d=json.load(f)
    assert d["status"]=="READS_VERIFIED"
    assert d["stored"] is False and d["modelTrainingRightsVerified"] is False
    assert d["liveTradingEnabled"] is False
    n,r=int(d["validatedDailyCandles"]),int(d["currentRatios"])
    assert 0<n<=40 and 0<r<=32
    print(f"MINTTICK_UPSTOX_RESULT:READS_VERIFIED candles={n} ratios={r}")
except (KeyError,AssertionError,TypeError,ValueError,OSError):
    sys.exit(1)
PY
then
  rollback
  fail PROVIDER_OR_RESPONSE_UNAVAILABLE
fi
accepted=1
echo 'MINTTICK_UPSTOX_RESULT:ACTIVATED_READ_ONLY'
