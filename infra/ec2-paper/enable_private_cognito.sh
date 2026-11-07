#!/usr/bin/env bash
# Idempotently enable existing dev Cognito on the existing private PAPER backend.
# Invoke only via the audited SSM workflow. No inbound access, no new instances.
set -euo pipefail
umask 077
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root"
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
test "$(stat -c %a "$root/runtime.env")" = 600
test -f "$root/paper-compose.yml" && test ! -L "$root/paper-compose.yml"
# Verify the already-deployed compose has the exact expected dev pool/client.
grep -Fq 'ap-south-1_jz2T8qbiY' "$root/paper-compose.yml"
grep -Fq '6jm18v2mdpcfraip92hrjhqjnd' "$root/paper-compose.yml"
grep -Fq '127.0.0.1:8080:8080' "$root/paper-compose.yml"
grep -Fq '127.0.0.1:8088:8080' "$root/paper-compose.yml"
# Preserve other host-local settings, including any credentials. Do not print.
temp="$(mktemp "$root/.cognito-env.XXXXXXXX")"
trap 'rm -f "$temp"' EXIT
grep -Ev '^MINTTICK_COGNITO_(ENABLED|REGION|ISSUER|CLIENT_ID)=' "$root/runtime.env" > "$temp"
cat >> "$temp" <<'EOF'
MINTTICK_COGNITO_ENABLED=true
MINTTICK_COGNITO_REGION=ap-south-1
MINTTICK_COGNITO_ISSUER=https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_jz2T8qbiY
MINTTICK_COGNITO_CLIENT_ID=6jm18v2mdpcfraip92hrjhqjnd
EOF
chmod 0600 "$temp"
install -o root -g root -m 0600 "$temp" "$root/runtime.env"
# Container already created from the reviewed SSM release; never pull arbitrary images.
cd "$root"
test -f .env
# The active Compose environment must include the new values; recreate backend only.
# Compose --env-file overrides the old file, not other container credentials.
docker compose --env-file runtime.env -f paper-compose.yml config --quiet
docker compose --env-file runtime.env -f paper-compose.yml up -d --no-build --no-deps --force-recreate backend
for i in $(seq 1 45); do
  if curl --silent --fail --max-time 3 http://127.0.0.1:8080/api/health >/dev/null; then
    code="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 http://127.0.0.1:8080/api/auth/cognito/me)"
    if [[ "$code" == 401 || "$code" == 403 ]]; then
      echo 'Private Cognito account API configured; still localhost-only'
      exit 0
    fi
  fi
  sleep 3
done
echo 'Private Cognito enablement failed readiness' >&2
exit 1
