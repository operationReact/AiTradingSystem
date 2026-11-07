#!/usr/bin/env bash
# Read-only broker readiness check on exact existing PAPER EC2 via approved SSM.
# Never prints Parameter Store values, account IDs, Cognito subject, PIN, TOTP or tokens.
set -euo pipefail
umask 077
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
test "$(stat -c %a "$root/runtime.env")" = 600
python3 - "$root/runtime.env" <<'PY'
import re, sys
from pathlib import Path
settings={}
for line in Path(sys.argv[1]).read_text().splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        k, v = line.split("=", 1)
        if k in {"MINTTICK_ANGEL_ONE_OWNER_SUB", "MARKET_DATA_PROVIDER",
                 "MARKET_DATA_LAMBDA_FUNCTION_NAME", "UPSTOX_CLIENT_ID",
                 "UPSTOX_CLIENT_SECRET", "UPSTOX_REDIRECT_URI"}:
            settings[k] = v.strip()
owner=settings.get("MINTTICK_ANGEL_ONE_OWNER_SUB", "")
print("MINTTICK_OWNER_BINDING:", "PRESENT" if re.fullmatch(r"[A-Za-z0-9_-]{10,128}",owner) else "MISSING")
mode=settings.get("MARKET_DATA_PROVIDER") == "ANGEL_ONE_LAMBDA"
fn=settings.get("MARKET_DATA_LAMBDA_FUNCTION_NAME") == "minttick-dev-angelone-feed"
print("MINTTICK_PRIVATE_FEED_MODE:", "ENABLED" if mode and fn else "NOT_ENABLED")
upstox=(bool(settings.get("UPSTOX_CLIENT_ID")) and bool(settings.get("UPSTOX_CLIENT_SECRET"))
    and settings.get("UPSTOX_REDIRECT_URI") ==
        "https://api.minttick.com/api/customer/brokers/upstox/callback")
print("MINTTICK_UPSTOX_SERVER_CONFIG:", "PRESENT" if upstox else "NOT_CONFIGURED")
PY
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
chmod 0700 "$work"
if timeout 20 aws lambda invoke --region ap-south-1 \
  --function-name minttick-dev-angelone-feed \
  --cli-binary-format raw-in-base64-out \
  --payload '{"operation":"status"}' "$work/status.json" \
  --query 'StatusCode' --output text > "$work/code" 2> "$work/invoke.stderr"; then
  python3 - "$work/code" "$work/status.json" <<'PY'
import json, sys
from pathlib import Path
allowed = {
    "CREDENTIALS_NOT_CONFIGURED", "SSM_UNAVAILABLE",
    "INVALID_TOTP_CONFIGURATION", "INTERNAL_FAILURE",
}
try:
    code = Path(sys.argv[1]).read_text().strip()
    result = json.loads(Path(sys.argv[2]).read_text())
    ready = (code == "200" and isinstance(result, dict)
             and result.get("ok") is True and result.get("configured") is True)
    reason = "READY" if ready else (
        result.get("code") if isinstance(result, dict)
        and result.get("code") in allowed else "UNEXPECTED_RESPONSE")
except (OSError, ValueError, TypeError, AttributeError):
    ready = False
    reason = "INVALID_RESPONSE"
print("MINTTICK_PRIVATE_FEED_LAMBDA:", "READY" if ready else "NOT_CONFIRMED")
print("MINTTICK_PRIVATE_FEED_DIAGNOSTIC:", reason)
PY
else
  # Do not print raw AWS CLI stderr, HTTP error bodies, SSM values or tokens.
  reason=INVOCATION_FAILED
  if grep -qiE 'AccessDenied|not authorized' "$work/invoke.stderr"; then
    reason=INVOKE_ACCESS_DENIED
  elif grep -qiE 'ResourceNotFound|Function not found' "$work/invoke.stderr"; then
    reason=FUNCTION_NOT_FOUND
  elif grep -qiE 'TooManyRequests|Throttl' "$work/invoke.stderr"; then
    reason=THROTTLED
  elif grep -qiE 'timed out|timeout' "$work/invoke.stderr"; then
    reason=INVOCATION_TIMEOUT
  fi
  echo "MINTTICK_PRIVATE_FEED_LAMBDA: NOT_CONFIRMED"
  echo "MINTTICK_PRIVATE_FEED_DIAGNOSTIC: $reason"
fi
echo "MINTTICK_READ_ONLY_CHECK: completed; no broker secrets accessed by this script"
