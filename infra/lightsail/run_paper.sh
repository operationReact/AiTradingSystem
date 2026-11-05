#!/usr/bin/env bash
# Runs as root on the one tagged PAPER Lightsail VM. Never executes broker orders.
# All application listener ports remain bound to VM loopback.
set -euo pipefail
umask 077
sha="${1:?exact release SHA required}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || exit 2
test "$(id -u)" -eq 0 || exit 2
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root" || exit 2
cd "$root"
test -f runtime.env && test ! -L runtime.env || exit 2
test -f paper-compose.yml && test ! -L paper-compose.yml || exit 2
chmod 0600 runtime.env
# Do not emit secrets into CI logs. Preserve the bootstrapped credentials and
# existing persistent postgres volume across deployments.
cp -- runtime.env .env
printf 'RELEASE_SHA=%s\n' "$sha" >> .env
chmod 0600 .env

# Validate local images; never pull a similarly named image from a registry.
docker image inspect "aitradingsystem-paper-backend:$sha" >/dev/null
docker image inspect "aitradingsystem-paper-web:$sha" >/dev/null
docker compose --env-file .env -f paper-compose.yml config --quiet

# Preserve an on-host rollback copy when an existing DB is running.
# Disaster recovery still REQUIRES independent external snapshots.
mkdir -p -m 0700 backups
if docker compose --env-file .env -f paper-compose.yml ps -q postgres |
    grep -q .; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  docker compose --env-file .env -f paper-compose.yml exec -T postgres \
    pg_dump -U aitrading aitrading | gzip -c > "backups/paper-$stamp.sql.gz"
  test -s "backups/paper-$stamp.sql.gz"
  chmod 0600 "backups/paper-$stamp.sql.gz"
fi

docker compose --env-file .env -f paper-compose.yml up -d --no-build --remove-orphans

# A deployed Nginx static health alone is NOT connected backend acceptance.
# Verify Postgres-backed Java API both directly and through the Flutter proxy.
curl -fSs --retry 20 --retry-delay 5 --retry-connrefused \
  http://127.0.0.1:8080/api/health >/dev/null
curl -fSs --retry 10 --retry-delay 3 --retry-connrefused \
  http://127.0.0.1:8088/health >/dev/null
curl -fSs --retry 10 --retry-delay 3 --retry-connrefused \
  http://127.0.0.1:8088/api/health >/dev/null
echo 'PAPER Java/PostgreSQL/Flutter proxy LOCAL-only smoke passed; PUBLIC not deployed'
