#!/usr/bin/env bash
# Owner-authorized, nonsecret opt-in to the private SSM-backed Angel One
# feed on the exact existing PAPER EC2. No live orders, no public ports.
set -Eeuo pipefail
umask 077
owner="${1:-}"
[[ "$owner" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || {
  echo 'BLOCKED: expected exact Cognito sub UUID' >&2; exit 2;
}
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root"
for file in runtime.env .env paper-compose.yml; do
  test -f "$root/$file" && test ! -L "$root/$file"
done
test "$(stat -c %a "$root/runtime.env")" = 600
test "$(stat -c %a "$root/.env")" = 600
grep -Fq 'MINTTICK_ANGEL_ONE_OWNER_SUB:' "$root/paper-compose.yml"
grep -Fq 'MARKET_DATA_LAMBDA_FUNCTION_NAME:' "$root/paper-compose.yml"
grep -Fq 'TRADING_MODE: PAPER' "$root/paper-compose.yml"
grep -Fq 'OWNER_FEED_ENABLED:' "$root/paper-compose.yml"

# Invoke the *private IAM-only* Lambda with no broker input or credentials.
# Stop before changing configuration if the SSM feed or IAM is unavailable.
work="$(mktemp -d "$root/.feed-enable.XXXXXXXX")"
chmod 0700 "$work"
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT
if ! timeout 25 aws lambda invoke --region ap-south-1   --function-name minttick-dev-angelone-feed   --cli-binary-format raw-in-base64-out   --payload '{"operation":"status"}' "$work/status.json"   --query StatusCode --output text > "$work/code" 2>/dev/null; then
  echo 'BLOCKED: private Angel One Lambda is unavailable or invoke is denied' >&2
  exit 2
fi
python3 - "$work/code" "$work/status.json" <<'PY'
import json,sys
from pathlib import Path
try:
    code=Path(sys.argv[1]).read_text().strip()
    result=json.loads(Path(sys.argv[2]).read_text())
    assert code=="200" and result.get("ok") is True and result.get("configured") is True
except (OSError,ValueError,AssertionError,TypeError,AttributeError):
    raise SystemExit("BLOCKED: read-only Angel One feed did not confirm readiness")
PY

# Stage exact old copies for rollback; never print file contents.
cp -p "$root/runtime.env" "$work/old-runtime"
cp -p "$root/.env" "$work/old-compose-env"
for file in runtime.env .env; do
  old="$root/$file"
  temp="$(mktemp "$root/.owner-feed.XXXXXXXX")"
  grep -Ev '^(MARKET_DATA_PROVIDER|MARKET_DATA_LAMBDA_FUNCTION_NAME|MINTTICK_ANGEL_ONE_OWNER_SUB|OWNER_FEED_ENABLED|OWNER_FEED_UNIVERSE_LIMIT|OWNER_FEED_WS_TOKEN_CAP)=' "$old" > "$temp" || true
  {
    printf 'MARKET_DATA_PROVIDER=ANGEL_ONE_LAMBDA\n'
    printf 'MARKET_DATA_LAMBDA_FUNCTION_NAME=minttick-dev-angelone-feed\n'
    printf 'MINTTICK_ANGEL_ONE_OWNER_SUB=%s\n' "$owner"
    # Only the privately authorized PAPER EC2 opts into an independent
    # collector. Never change the default for production/customer releases.
    printf 'OWNER_FEED_ENABLED=true\n'
    # Distinct from REST's 50-token batch limit: V2 permits 1000 LTP tokens
    # per socket and three sockets per broker account.
    printf 'OWNER_FEED_UNIVERSE_LIMIT=3000\n'
    printf 'OWNER_FEED_WS_TOKEN_CAP=1000\n'
  } >> "$temp"
  chmod 0600 "$temp"
  install -o root -g root -m 0600 "$temp" "$old"
  rm -f "$temp"
done

cd "$root"
if ! docker compose --env-file .env -f paper-compose.yml config --quiet ||
   ! docker compose --env-file .env -f paper-compose.yml up -d --no-build --no-deps --force-recreate backend >/dev/null; then
  cp -p "$work/old-runtime" "$root/runtime.env"
  cp -p "$work/old-compose-env" "$root/.env"
  docker compose --env-file .env -f paper-compose.yml up -d --no-build --no-deps --force-recreate backend >/dev/null 2>&1 || true
  echo 'BLOCKED: activation rejected; restored previous host configuration' >&2
  exit 2
fi

for i in $(seq 1 35); do
  if curl --silent --fail --max-time 4 http://127.0.0.1:8080/api/health >/dev/null 2>&1; then
    auth="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 4 'http://127.0.0.1:8080/api/customer/market-data/quotes?instrumentId=1' || true)"
    if [[ "$auth" == 401 || "$auth" == 403 ]]; then
      # Confirm real backend runtime settings, not just an edited host file.
      backend="$(docker compose --env-file .env -f paper-compose.yml ps -q backend)"
      if [[ -n "$backend" ]] &&
         docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$backend" |
           grep -qx 'OWNER_FEED_ENABLED=true' &&
         docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$backend" |
           grep -qx 'OWNER_FEED_UNIVERSE_LIMIT=3000' &&
         docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$backend" |
           grep -qx 'OWNER_FEED_WS_TOKEN_CAP=1000'; then
        echo 'PASS: private owner feed, autonomous session warmer and JWT gate verified; no live orders'
        exit 0
      fi
    fi
  fi
  sleep 3
done
cp -p "$work/old-runtime" "$root/runtime.env"
cp -p "$work/old-compose-env" "$root/.env"
docker compose --env-file .env -f paper-compose.yml up -d --no-build --no-deps --force-recreate backend >/dev/null 2>&1 || true
echo 'BLOCKED: backend health not accepted; restored previous host configuration' >&2
exit 2
