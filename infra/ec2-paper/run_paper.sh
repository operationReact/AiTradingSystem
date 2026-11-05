#!/usr/bin/env bash
# PAPER-only idempotent on-instance deployment, intended for future SSM runner.
# Precondition: exact ARM64 images already transferred by approved workflow.
# Never starts an EC2 instance, opens ports or executes LIVE broker orders.
set -euo pipefail
umask 077

sha="${1:?40-character immutable GitHub commit SHA required}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "Bad release SHA" >&2; exit 2; }
test "$(id -u)" -eq 0 || { echo "Root required" >&2; exit 2; }
test "$(uname -m)" = "aarch64" || { echo "Wrong host architecture" >&2; exit 2; }

root="/opt/aitradingsystem-paper"
test -d "$root" && test ! -L "$root" || { echo "Missing safe deployment directory" >&2; exit 2; }
cd "$root"
test -f runtime.env && test ! -L runtime.env || { echo "Missing safe host-local runtime secrets" >&2; exit 2; }
test -f paper-compose.yml && test ! -L paper-compose.yml || { echo "Missing safe Compose file" >&2; exit 2; }
chmod 0600 runtime.env

# Images are built on GitHub ARM64 and transferred to this host.
# Do not build with the small 4GiB host. Do not silently pull app images.
api="aitradingsystem-paper-backend-arm64:$sha"
web="aitradingsystem-paper-web-arm64:$sha"
for img in "$api" "$web"; do
  test "$(docker image inspect "$img" --format '{{.Architecture}}')" = "arm64" || {
    echo "Missing reviewed ARM64 application image" >&2; exit 2;
  }
done
test "$(docker image inspect "$api" --format '{{.Config.User}}')" = "10001" || {
  echo "Backend must run as unprivileged container user" >&2; exit 2;
}

# Merge host-local credentials (never exported to Actions or console logs)
# with an immutable release digest, atomically and with 0600 permissions.
temp="$(mktemp "$root/.env.XXXXXXXX")"
trap 'rm -f -- "$temp"' EXIT
cat runtime.env > "$temp"
printf 'RELEASE_SHA=%s\n' "$sha" >> "$temp"
chmod 0600 "$temp"
docker compose --env-file "$temp" -f paper-compose.yml config --quiet

# Backup existing running database before rolling the backend forward.
# Backups on the same EBS are *not* disaster recovery; separate S3 backups
# are required before the service contains important retained user data.
mkdir -p -m 0700 backups
if docker compose --env-file "$temp" -f paper-compose.yml ps -q postgres | grep -q .; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  docker compose --env-file "$temp" -f paper-compose.yml exec -T postgres \
    pg_dump -U aitrading aitrading | gzip -c > "backups/paper-$stamp.sql.gz"
  test -s "backups/paper-$stamp.sql.gz" || { echo "Backup failed; abort" >&2; exit 1; }
  chmod 0600 "backups/paper-$stamp.sql.gz"
fi

mv -f -- "$temp" .env
trap - EXIT
docker compose --env-file .env -f paper-compose.yml up -d --no-build --remove-orphans

# Actual connected Postgres-backed backend and Flutter reverse-proxy readiness.
# Endpoint response bodies are discarded rather than logged.
wait_health() {
  local label="$1" url="$2" attempt
  for attempt in $(seq 1 50); do
    if curl --fail --silent --show-error --connect-timeout 2 --max-time 6 \
      --output /dev/null "$url" 2>/dev/null; then
      echo "PAPER $label local health passed"
      return 0
    fi
    sleep 3
  done
  echo "PAPER $label local health failed; application not accepted" >&2
  return 1
}
wait_health "Postgres-backed Java API" "http://127.0.0.1:8080/api/health"
wait_health "Flutter Web static" "http://127.0.0.1:8088/health"
wait_health "Flutter Web backend proxy" "http://127.0.0.1:8088/api/health"

# Fail if a listener escapes loopback via any compose port map.
docker compose --env-file .env -f paper-compose.yml config --format json |
  python3 -c 'import json,sys; d=json.load(sys.stdin); s=d["services"]; assert all(p.get("host_ip")=="127.0.0.1" for n in ("backend","web") for p in s[n].get("ports",[])); assert not s["postgres"].get("ports"); assert s["backend"]["environment"]["TRADING_MODE"]=="PAPER"; print("PAPER deployment verified: only localhost listeners; no LIVE orders")'

echo "PAPER PostgreSQL, Spring Boot and Flutter Web connected local acceptance passed"
