#!/usr/bin/env bash
# One-time active NSE/BSE cash-equity catalogue activation on the reviewed PAPER EC2.
# Uses only its existing localhost operator endpoint and host-local API key.
# Never prints secrets, raw responses, market prices or broker account data.
set -Eeuo pipefail
umask 077
# Redacted stage-only failure marker. The workflow never displays raw host stderr.
stage=HOST_PREFLIGHT
work=''
report_exit() {
  local result=$?
  if [[ -n "$work" && -d "$work" ]]; then rm -rf -- "$work"; fi
  if (( result != 0 )); then
    printf 'MINTTICK_CATALOGUE_BLOCKER: %s\n' "$stage" >&2
  fi
}
trap report_exit EXIT
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root"
for f in runtime.env .env paper-compose.yml; do
  test -f "$root/$f" && test ! -L "$root/$f"
done
test "$(stat -c %a "$root/runtime.env")" = 600
test "$(stat -c %a "$root/.env")" = 600
grep -Fq 'TRADING_MODE: PAPER' "$root/paper-compose.yml"
command -v docker >/dev/null 2>&1
command -v curl >/dev/null 2>&1

work="$(mktemp -d "$root/.instrument-import.XXXXXXXX")"
chmod 0700 "$work"
cash_count() {
  timeout 12 docker compose --env-file "$root/.env" -f "$root/paper-compose.yml" \
    exec -T postgres psql -X -v ON_ERROR_STOP=1 -U aitrading -d aitrading -Atq \
    -c "SELECT count(*) FROM instrument_master i JOIN instrument_catalogue_state c ON c.active_version=i.catalogue_version WHERE c.singleton_id=1 AND i.active=TRUE AND i.exchange_segment IN ('NSE_CM','BSE_CM') AND i.trading_symbol LIKE '%-EQ';" \
    2>/dev/null
}

stage=PREIMPORT_DB
before="$(cash_count)" || { echo 'BLOCKED: cannot read existing instrument catalogue' >&2; exit 2; }
[[ "$before" =~ ^[0-9]{1,6}$ ]] || { echo 'BLOCKED: invalid catalogue count' >&2; exit 2; }
if (( 10#$before > 0 )); then
  echo "MINTTICK_CATALOGUE_IMPORT: ALREADY_ACTIVE"
  echo "MINTTICK_CASH_EQUITY_INSTRUMENTS: $before"
  exit 0
fi

stage=OPERATOR_KEY
# Generate an ephemeral 0600 request-header file from the protected host env.
# No secret is passed via SSM, command-line arguments, GitHub, or output.
python3 - "$root/runtime.env" "$work/header" <<'PY'
import os,re,sys
from pathlib import Path
source=Path(sys.argv[1]); target=Path(sys.argv[2])
matches=[]
for row in source.read_text().splitlines():
    if row.startswith("APP_API_KEY="):
        matches.append(row.split("=",1)[1])
if len(matches)!=1 or not re.fullmatch(r"[!-~]{16,256}",matches[0]) or "\r" in matches[0]:
    raise SystemExit("BLOCKED: protected operator key unavailable")
target.write_text("X-API-Key: "+matches[0]+"\n")
os.chmod(target,0o600)
PY

# Only the local operator endpoint can import the signed-off public instrument file.
# Keep response in a protected temp file and never display it.
stage=BACKEND_HTTP
code="$(timeout 210 curl --silent --connect-timeout 5 --max-time 190 \
  --output "$work/result.json" --write-out '%{http_code}' \
  --header "@$work/header" --request POST \
  http://127.0.0.1:8080/api/instruments/import 2>/dev/null)" || {
  echo 'BLOCKED: local official catalogue import transport failed' >&2
  exit 2
}
[[ "$code" == 200 ]] || { echo 'BLOCKED: local catalogue import did not return HTTP 200' >&2; exit 2; }
stage=BACKEND_RESPONSE
python3 - "$work/result.json" <<'PY'
import json,sys
from pathlib import Path
try:
    result=json.loads(Path(sys.argv[1]).read_text())
    assert isinstance(result,dict)
    assert result.get("status")=="SUCCEEDED"
    assert isinstance(result.get("activeCount"),int) and result["activeCount"] > 0
except (ValueError,TypeError,AssertionError,OSError):
    raise SystemExit("BLOCKED: official instrument catalogue import was not accepted")
PY

stage=POSTIMPORT_DB
after="$(cash_count)" || { echo 'BLOCKED: cannot verify activated cash equities' >&2; exit 2; }
[[ "$after" =~ ^[0-9]{1,6}$ ]] || { echo 'BLOCKED: invalid activated count' >&2; exit 2; }
(( 10#$after > 0 )) || { echo 'BLOCKED: import yielded zero active cash equities' >&2; exit 2; }
echo 'MINTTICK_CATALOGUE_IMPORT: SUCCESS'
echo "MINTTICK_CASH_EQUITY_INSTRUMENTS: $after"
