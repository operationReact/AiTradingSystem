#!/usr/bin/env bash
# Single-host, read-only Upstox Analytics Token activation. Called ONLY from
# reviewed dev-branch OIDC -> SSM workflow, never from customer APIs.
# No order permissions, public ports, broker secrets in logs, AWS IAM mutations
# or Angel One collector/scheduler configuration changes.
set -Eeuo pipefail
umask 077
stage=precheck
accepted=0
work=
# Report unexpected early termination using fixed, non-sensitive markers.
# Deliberate failures retain their more specific marker, emitted first.
on_exit() {
  code=$?
  if [ -n "$work" ]; then
    rm -rf -- "$work" || true
  fi
  if [ "$code" -ne 0 ] || [ "$accepted" -ne 1 ]; then
    case "$stage" in
      ssm_authorization) reason=SSM_UNAVAILABLE ;;
      read_only_probe|result_validation) reason=PROVIDER_OR_RESPONSE_UNAVAILABLE ;;
      *) reason=LOCAL_RELEASE_UNCLASSIFIED ;;
    esac
    printf 'MINTTICK_UPSTOX_RESULT:%s\n' "$reason"
    printf 'MINTTICK_UPSTOX_STAGE:%s\n' "$stage" >&2
    if [ "$code" -eq 0 ]; then
      exit 9
    fi
  fi
}
trap on_exit EXIT

root=/opt/aitradingsystem-paper
parameter=/minttick/upstox/analytics-token
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64
test -d "$root" && test ! -L "$root"
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
test -f "$root/run_paper.sh" && test ! -L "$root/run_paper.sh"
test -f "$root/.env" && test ! -L "$root/.env"
test "$(stat -c %a "$root/runtime.env")" = 600
test "$(stat -c %U "$root/runtime.env")" = root

# Do not download, echo, save or parse the credential value. AWS returns only
# the Parameter.Type field to this root-only local process; stderr discarded.
stage=ssm_authorization
# Capture *only* CLI error category, never the token or raw IAM/KMS message.
diagnostic="$(mktemp "$root/.upstox-ssm-check.XXXXXXXX")"
parameter_type="$(aws ssm get-parameter --region ap-south-1 --name "$parameter" \
  --with-decryption --query 'Parameter.Type' --output text \
  --no-cli-pager 2>"$diagnostic" || true)"
if [ "$parameter_type" != SecureString ]; then
  failure=SSM_UNAVAILABLE
  if [ "$parameter_type" != "" ]; then
    failure=SSM_NOT_SECURESTRING
  elif grep -Eiq 'ParameterNotFound|parameter.*not.*found' "$diagnostic"; then
    failure=SSM_PARAMETER_MISSING
  elif grep -Eiq 'KMSAccessDenied|kms:Decrypt|KMS.*not authorized' "$diagnostic"; then
    failure=SSM_KMS_DENIED
  elif grep -Eiq 'AccessDenied|not authorized|explicit deny' "$diagnostic"; then
    failure=SSM_ACCESS_DENIED
  elif grep -Eiq 'EndpointConnectionError|Could not connect|timed out|Network' "$diagnostic"; then
    failure=SSM_NETWORK_UNAVAILABLE
  fi
  rm -f -- "$diagnostic"
  printf 'MINTTICK_UPSTOX_RESULT:%s\n' "$failure"
  exit 3
fi
rm -f -- "$diagnostic"

# Read only the SHA currently installed on this host, never from user input.
stage=release_identity
sha="$(sed -n 's/^RELEASE_SHA=//p' "$root/.env" | tail -n 1)"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || exit 4
test "$(docker image inspect "aitradingsystem-paper-backend-arm64:$sha" \
  --format '{{.Architecture}}')" = arm64

# The caller intentionally accepts one controlled backend recreation, as for
# a normal PAPER release. Preserve the original root-only credential file.
stage=activation
work="$(mktemp -d "$root/.upstox-analytics.XXXXXXXX")"
chmod 0700 "$work"
cp -p "$root/runtime.env" "$work/previous.env"
restore() {
  install -o root -g root -m 0600 "$work/previous.env" "$root/runtime.env" || return 1
  bash "$root/run_paper.sh" "$sha" >/dev/null 2>&1
}
rollback() {
  if restore && curl --fail --silent --max-time 8 \
      --output /dev/null 'http://127.0.0.1:8080/api/health'; then
    echo 'MINTTICK_UPSTOX_RESULT:ROLLBACK_HEALTHY'
  else
    echo 'MINTTICK_UPSTOX_RESULT:ROLLBACK_UNVERIFIED'
  fi
}
classify_failure() {
  # Never echo the private file or raw Docker/host errors.
  local logfile="$work/release.log"
  if grep -Fq 'Angel One registered static egress IP mismatch' "$logfile"; then
    echo 'LOCAL_RELEASE_EGRESS_IP_DRIFT'
  elif grep -Fq 'PAPER Postgres-backed Java API local health failed' "$logfile"; then
    echo 'LOCAL_RELEASE_BACKEND_HEALTH'
  elif grep -Fq 'PAPER Flutter Web static local health failed' "$logfile"; then
    echo 'LOCAL_RELEASE_WEB_HEALTH'
  elif grep -Fq 'PAPER Flutter Web backend proxy local health failed' "$logfile"; then
    echo 'LOCAL_RELEASE_PROXY_HEALTH'
  elif grep -Fq 'PAPER Lambda-only private API bridge local health failed' "$logfile"; then
    echo 'LOCAL_RELEASE_LAMBDA_BRIDGE'
  elif grep -Fq 'Backup failed' "$logfile"; then
    echo 'LOCAL_RELEASE_DB_BACKUP'
  elif grep -Eiq 'no space left on device|disk full' "$logfile"; then
    echo 'LOCAL_RELEASE_DISK_FULL'
  elif grep -Eiq 'no such image|missing reviewed ARM64' "$logfile"; then
    echo 'LOCAL_RELEASE_MISSING_IMAGE'
  elif grep -Eiq 'docker.*error|error response from daemon' "$logfile"; then
    echo 'LOCAL_RELEASE_DOCKER_ERROR'
  else
    echo 'LOCAL_RELEASE_UNCLASSIFIED'
  fi
}
awk '!/^MINTTICK_UPSTOX_ANALYTICS_(ENABLED|PROBE_ENABLED)=/' \
  "$work/previous.env" > "$work/next.env"
grep -Eq '^POSTGRES_PASSWORD=[0-9a-f]{64}$' "$work/next.env"
grep -Eq '^APP_API_KEY=[0-9a-f]{64}$' "$work/next.env"
printf 'MINTTICK_UPSTOX_ANALYTICS_ENABLED=true\n' >> "$work/next.env"
printf 'MINTTICK_UPSTOX_ANALYTICS_PROBE_ENABLED=true\n' >> "$work/next.env"
install -o root -g root -m 0600 "$work/next.env" "$root/runtime.env"
if ! bash "$root/run_paper.sh" "$sha" > "$work/release.log" 2>&1; then
  code="$(classify_failure)"
  rollback
  printf 'MINTTICK_UPSTOX_RESULT:%s\n' "$code"
  exit 5
fi

stage=read_only_probe
operator_key="$(sed -n 's/^APP_API_KEY=//p' "$root/runtime.env" | head -n 1)"
if [[ ! "$operator_key" =~ ^[0-9a-f]{64}$ ]]; then
  rollback
  echo 'MINTTICK_UPSTOX_RESULT:OPERATOR_AUTH_UNAVAILABLE'
  exit 6
fi

http_code="$(curl --silent --show-error --connect-timeout 3 --max-time 35 \
  -X POST -H "X-API-Key: $operator_key" -H 'Accept: application/json' \
  -o "$work/response.json" -w '%{http_code}' \
  'http://127.0.0.1:8080/api/admin/market-data/upstox-analytics/probe' \
  2>/dev/null || true)"
unset operator_key
if [ "$http_code" != 200 ]; then
  rollback
  echo 'MINTTICK_UPSTOX_RESULT:PROBE_HTTP_UNAVAILABLE'
  exit 7
fi

# Only boolean security guarantees, status and validated counts leave the
# private host. No raw provider payload or access tokens are printed.
stage=result_validation
if ! python3 - "$work/response.json" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding='utf-8') as f:
        obj = json.load(f)
    assert obj["status"] == "READS_VERIFIED"
    assert obj["stored"] is False
    assert obj["modelTrainingRightsVerified"] is False
    assert obj["liveTradingEnabled"] is False
    bars, ratios = int(obj["validatedDailyCandles"]), int(obj["currentRatios"])
    assert 0 < bars <= 40 and 0 < ratios <= 32
    print(f"MINTTICK_UPSTOX_RESULT:READS_VERIFIED candles={bars} ratios={ratios}")
except (KeyError, AssertionError, TypeError, ValueError, OSError):
    sys.exit(1)
PY
then
  rollback
  echo 'MINTTICK_UPSTOX_RESULT:PROVIDER_OR_RESPONSE_UNAVAILABLE'
  exit 8
fi

accepted=1
echo 'MINTTICK_UPSTOX_RESULT:ACTIVATED_READ_ONLY'
