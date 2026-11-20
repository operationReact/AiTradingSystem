#!/usr/bin/env bash
# Exact-host, private PAPER-only activation of source-backed 5m/1H ingestion.
# Changes no broker credentials, order rights, external services or public routes.
set -Eeuo pipefail
umask 077
stage=HOST_IDENTITY
# Print only fixed stage enums, never command output or private values.
trap 'echo "INTRADAY_ACTIVATION_BLOCKED: ${stage}"' ERR
echo 'INTRADAY_ACTIVATION_STAGE: STARTED'
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root"
cd "$root"
stage=PRIVATE_FILES
echo 'INTRADAY_ACTIVATION_STAGE: PRIVATE_FILES'
for name in runtime.env .env paper-compose.yml; do
  test -f "$name" && test ! -L "$name"
done
test "$(stat -c '%u:%a' runtime.env)" = '0:600'
test "$(stat -c '%u:%a' .env)" = '0:600'
grep -Fq 'TRADING_MODE: PAPER' paper-compose.yml
grep -Fq 'OWNER_PRIVATE_INTRADAY_ENABLED:' paper-compose.yml
grep -Fq 'OWNER_FEED_ENABLED:' paper-compose.yml
stage=OWNER_FEED
echo 'INTRADAY_ACTIVATION_STAGE: OWNER_FEED'
for file in runtime.env .env; do
  grep -qx 'OWNER_FEED_ENABLED=true' "$file" || {
    echo 'INTRADAY_ACTIVATION_BLOCKED: OWNER_FEED_NOT_ACTIVE'; exit 2;
  }
  grep -Eq '^MINTTICK_ANGEL_ONE_OWNER_SUB=[0-9a-fA-F-]{36}$' "$file" || {
    echo 'INTRADAY_ACTIVATION_BLOCKED: OWNER_SUB_NOT_CONFIGURED'; exit 2;
  }
done
stage=BACKEND_CONTAINER
echo 'INTRADAY_ACTIVATION_STAGE: BACKEND_CONTAINER'
backend="$(docker compose --env-file .env -f paper-compose.yml ps -q backend)"
test -n "$backend"
test "$(docker compose --env-file .env -f paper-compose.yml ps -q backend | wc -l)" -eq 1
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$backend" |
  grep -qx 'OWNER_FEED_ENABLED=true' || {
    echo 'INTRADAY_ACTIVATION_BLOCKED: BACKEND_FEED_NOT_ACTIVE'; exit 2;
  }
# The original 5m/1H collector targets shared market_candle and must
# remain OFF. This activation enables a different owner-private table writer.
stage=OWNER_PRIVATE_STORAGE
echo 'INTRADAY_ACTIVATION_STAGE: OWNER_PRIVATE_STORAGE'
for file in runtime.env .env; do
  if grep -qx 'OWNER_HISTORY_BACKFILL_ENABLED=true' "$file"; then
    echo 'INTRADAY_ACTIVATION_BLOCKED: SHARED_COLLECTOR_ACTIVE'
    exit 2
  fi
done
if docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$backend" |
  grep -qx 'OWNER_HISTORY_BACKFILL_ENABLED=true'; then
  echo 'INTRADAY_ACTIVATION_BLOCKED: SHARED_COLLECTOR_ACTIVE'
  exit 2
fi
# SSM streams this script into bash; Docker exec must not consume its stdin.
private_tables="$(timeout 20 docker compose --env-file .env -f paper-compose.yml exec -T postgres \
  psql -U aitrading -d aitrading -qtAX -v ON_ERROR_STOP=1 \
  -c "SELECT CASE WHEN to_regclass('public.owner_private_intraday_candle') IS NOT NULL AND to_regclass('public.owner_private_intraday_cursor') IS NOT NULL THEN 'READY' ELSE 'MISSING' END" \
  </dev/null 2>/dev/null)" || {
    echo 'INTRADAY_ACTIVATION_BLOCKED: PRIVATE_STORAGE_UNAVAILABLE'
    exit 2
  }
if [[ "$private_tables" != READY ]]; then
  echo 'INTRADAY_ACTIVATION_BLOCKED: PRIVATE_STORAGE_MISSING'
  exit 2
fi
echo 'INTRADAY_PRIVATE_STORAGE: VERIFIED'
stage=HOST_STAGING
echo 'INTRADAY_ACTIVATION_STAGE: HOST_STAGING'
scratch="$(mktemp -d "$root/.intraday-activation.XXXXXXXX")"
chmod 0700 "$scratch"
cleanup() { rm -rf -- "$scratch"; }
trap cleanup EXIT
cp -p runtime.env "$scratch/runtime.env"
cp -p .env "$scratch/compose.env"
rollback() {
  cp -p "$scratch/runtime.env" runtime.env
  cp -p "$scratch/compose.env" .env
  docker compose --env-file .env -f paper-compose.yml up -d --no-build --no-deps --force-recreate backend >/dev/null 2>&1 || true
}
stage=RUNTIME_UPDATE
echo 'INTRADAY_ACTIVATION_STAGE: RUNTIME_UPDATE'
for file in runtime.env .env; do
  tmp="$(mktemp "$root/.intraday-env.XXXXXXXX")"
  grep -v '^OWNER_PRIVATE_INTRADAY_ENABLED=' "$file" > "$tmp" || true
  printf 'OWNER_PRIVATE_INTRADAY_ENABLED=true\n' >> "$tmp"
  install -o root -g root -m 0600 "$tmp" "$file"
  rm -f "$tmp"
done
stage=BACKEND_RECREATE
echo 'INTRADAY_ACTIVATION_STAGE: BACKEND_RECREATE'
if ! docker compose --env-file .env -f paper-compose.yml config --quiet ||
   ! docker compose --env-file .env -f paper-compose.yml up -d --no-build --no-deps --force-recreate backend >/dev/null; then
  rollback
  echo 'INTRADAY_ACTIVATION_BLOCKED: CONTAINER_START_FAILED'
  exit 2
fi
stage=BACKEND_HEALTH
echo 'INTRADAY_ACTIVATION_STAGE: BACKEND_HEALTH'
for attempt in $(seq 1 45); do
  backend="$(docker compose --env-file .env -f paper-compose.yml ps -q backend)"
  if [[ -n "$backend" ]] &&
      curl --silent --fail --max-time 3 http://127.0.0.1:8080/api/health >/dev/null 2>&1 &&
      docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$backend" |
        grep -qx 'OWNER_PRIVATE_INTRADAY_ENABLED=true'; then
    echo 'INTRADAY_ACTIVATION: ENABLED'
    echo 'INTRADAY_BACKEND_HEALTH: PASS'
    echo 'INTRADAY_SOURCE: ANGEL_ONE_OWNER_SESSION'
    echo 'INTRADAY_ORDER_RIGHTS_CHANGED: FALSE'
    exit 0
  fi
  sleep 3
done
rollback
echo 'INTRADAY_ACTIVATION_BLOCKED: BACKEND_NOT_READY'
exit 2
