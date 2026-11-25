#!/usr/bin/env bash
# Cloud-init for a single EC2 PAPER research/development host.
# NO broker credentials, SSH, static GitHub tokens, ECR push or LIVE trading.
set -euo pipefail
umask 077
exec > >(tee -a /var/log/aitrading-paper-ec2-bootstrap.log) 2>&1

test "$(id -u)" -eq 0
test "$(uname -m)" = "aarch64"
# AL2023 already includes curl-minimal; adding full curl conflicts with it.
dnf install -y --setopt=install_weak_deps=False docker openssl ca-certificates
systemctl enable --now docker
systemctl enable --now amazon-ssm-agent

# AL2023's default docker package does not bundle Compose. Obtain the
# fixed official ARM64 release and checksum from its release assets. A hash
# mismatch, missing asset or failed download aborts bootstrap.
version="v5.6.0"
base="https://github.com/docker/compose/releases/download/${version}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl --fail --location --retry 3 --silent --show-error \
  "$base/docker-compose-linux-aarch64" -o "$work/docker-compose-linux-aarch64"
# GitHub's official v5.6.0 release API publishes this asset SHA256 digest.
# Pin it directly instead of parsing release-specific checksums.txt formatting.
compose_sha256="733ec76717ceb59052a9609b9dadfb523b2df8eab57a54212872d10a58078ea2"
printf '%s  %s\n' "$compose_sha256" "$work/docker-compose-linux-aarch64" | sha256sum -c -
install -d -m 0755 /usr/local/lib/docker/cli-plugins
install -m 0755 "$work/docker-compose-linux-aarch64" \
  /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version >/dev/null

# Reuse the already verified local-only Java/PostgreSQL/Flutter stack path.
install -d -m 0700 -o root -g root /opt/aitradingsystem-paper
if [ ! -e /opt/aitradingsystem-paper/runtime.env ]; then
  printf 'POSTGRES_PASSWORD=%s\nAPP_API_KEY=%s\n' \
    "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" \
    > /opt/aitradingsystem-paper/runtime.env
  chmod 0600 /opt/aitradingsystem-paper/runtime.env
fi
echo 'PAPER EC2 bootstrap prepared with SSM; public app ports closed; no app deployed'
