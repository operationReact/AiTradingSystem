#!/usr/bin/env bash
# PAPER-only idempotent on-instance deployment, intended for future SSM runner.
# Precondition: exact ARM64 images already transferred by approved workflow.
# Never starts an EC2 instance, opens ports or executes LIVE broker orders.
set -euo pipefail
umask 077

# Fixed, non-sensitive failure stage; no environment, provider payload or tokens.
stage=HOST_PRECHECK
paper_exit() {
  local rc=$?
  # Keep the single EXIT trap active even after temporary runtime.env creation.
  if [ -n "${temp:-}" ]; then rm -f -- "$temp" || true; fi
  if [ "$rc" -ne 0 ]; then
    printf 'MINTTICK_PAPER_FAILED_STAGE:%s\n' "$stage" >&2
  fi
}
trap paper_exit EXIT

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

stage=IMAGE_CHECK
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

# Fail closed if this backend would authenticate outside the registered
# broker static IP. Keep the local AWS network identity derived, not invented.
stage=EGRESS_CHECK
observed_egress="$(curl --silent --fail --connect-timeout 3 --max-time 8 https://checkip.amazonaws.com 2>/dev/null || true)"
test "$observed_egress" = "3.108.130.17" || {
  echo "Angel One registered static egress IP mismatch; release refused" >&2
  exit 2
}
stage=NETWORK_IDENTITY
client_iface="$(ip -o -4 addr show | awk '$4 ~ /^10[.]87[.]0[.]10[/]/ {print $2}')"
client_iface="${client_iface%%@*}"
[[ "$client_iface" =~ ^[a-zA-Z0-9_.-]{2,32}$ ]] || {
  echo "Exact EC2 client network interface unavailable" >&2; exit 2
}
test -f "/sys/class/net/$client_iface/address" || exit 2
client_mac="$(tr '[:upper:]' '[:lower:]' < "/sys/class/net/$client_iface/address" | tr -d '[:space:]')"
[[ "$client_mac" =~ ^([0-9a-f]{2}:){5}[0-9a-f]{2}$ ]] && test "$client_mac" != "00:00:00:00:00:00" || {
  echo "Exact EC2 client network MAC unavailable" >&2; exit 2
}

# Merge host-local credentials (never exported to Actions or console logs)
# with an immutable release digest and nonsecret network headers, atomically
# and with 0600 permissions.
stage=COMPOSE_CONFIG
temp="$(mktemp "$root/.env.XXXXXXXX")"
# Cleanup is handled by paper_exit without replacing the stage-reporting trap.
cat runtime.env > "$temp"
printf 'RELEASE_SHA=%s\n' "$sha" >> "$temp"
printf 'ANGELONE_CLIENT_MAC_ADDRESS=%s\n' "$client_mac" >> "$temp"
chmod 0600 "$temp"
docker compose --env-file "$temp" -f paper-compose.yml config --quiet

# Backup existing running database before rolling the backend forward.
# Backups on the same EBS are *not* disaster recovery; separate S3 backups
# are required before the service contains important retained user data.
stage=DATABASE_BACKUP
mkdir -p -m 0700 backups
if docker compose --env-file "$temp" -f paper-compose.yml ps -q postgres | grep -q .; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  docker compose --env-file "$temp" -f paper-compose.yml exec -T postgres \
    pg_dump -U aitrading aitrading | gzip -c > "backups/paper-$stamp.sql.gz"
  test -s "backups/paper-$stamp.sql.gz" || { echo "Backup failed; abort" >&2; exit 1; }
  chmod 0600 "backups/paper-$stamp.sql.gz"
fi

stage=COMPOSE_START
mv -f -- "$temp" .env
temp=
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
stage=API_HEALTH
wait_health "Postgres-backed Java API" "http://127.0.0.1:8080/api/health"
stage=WEB_HEALTH
wait_health "Flutter Web static" "http://127.0.0.1:8088/health"
stage=PROXY_HEALTH
wait_health "Flutter Web backend proxy" "http://127.0.0.1:8088/api/health"
stage=BRIDGE_HEALTH
wait_health "Lambda-only private API bridge" "http://10.87.0.10:8081/api/health"

# Backend is localhost-only. The web reverse proxy has exactly one additional
# VPC-private bind, protected at the EC2 security group by a Lambda-SG-only rule.
# Fail closed on any drift to 0.0.0.0, public IP, extra port, or non-PAPER mode.
stage=PORT_CONTRACT
docker compose --env-file .env -f paper-compose.yml config --format json |
  python3 -c 'import json,sys; d=json.load(sys.stdin); s=d["services"]; ports=lambda n:{(p["host_ip"],int(p["published"]),int(p["target"])) for p in s[n].get("ports",[])}; assert ports("backend")=={("127.0.0.1",8080,8080)}; assert ports("web")=={("127.0.0.1",8088,8080),("10.87.0.10",8081,8080)}; assert not s["postgres"].get("ports"); assert s["backend"]["environment"]["TRADING_MODE"]=="PAPER"; print("PAPER deployment verified: backend loopback; VPC bridge Lambda SG only; no LIVE orders")'

stage=COMPLETE
echo "PAPER PostgreSQL, Spring Boot and Flutter Web connected local acceptance passed"
