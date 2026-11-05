#!/usr/bin/env bash
# One-time launch bootstrap for isolated AI Trading PAPER host.
# No AWS keys, GitHub tokens, market-provider credentials or LIVE authority.
set -euo pipefail
exec > >(tee -a /var/log/aitradingsystem-paper-bootstrap.log) 2>&1
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends docker.io docker-compose-v2 openssl ca-certificates
systemctl enable --now docker
test "$(id -u)" -eq 0
docker compose version >/dev/null
install -d -m 0700 -o root -g root /opt/aitradingsystem-paper
if [ ! -e /opt/aitradingsystem-paper/runtime.env ]; then
  umask 077
  printf 'POSTGRES_PASSWORD=%s\nAPP_API_KEY=%s\n' \
    "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" \
    > /opt/aitradingsystem-paper/runtime.env
  chmod 0600 /opt/aitradingsystem-paper/runtime.env
fi
# Do not start the application or expose public web ports during bootstrap.
# Production domain, HTTPS, database backups and model custody are separate gates.
echo 'AI Trading PAPER Lightsail host bootstrap ready (no app deployed)'
