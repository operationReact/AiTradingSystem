#!/usr/bin/env bash
# Read-only diagnostics through approved SSM on the existing ARM64 PAPER EC2.
# Never output credentials, run a broker order or publish network ports.
set -euo pipefail
umask 077

test "$(id -u)" -eq 0 || exit 2
test "$(uname -m)" = "aarch64" || exit 2
timeout 120 cloud-init status --wait >/dev/null
test "$(systemctl is-active docker)" = "active"
test "$(systemctl is-active amazon-ssm-agent)" = "active"
docker info --format '{{.ServerVersion}}' >/dev/null
docker compose version >/dev/null
aws --version >/dev/null 2>&1 || { echo "Missing AWS CLI for S3 release download" >&2; exit 2; }
root="/opt/aitradingsystem-paper"
test -d "$root" && test ! -L "$root"
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
test "$(stat -c '%a' "$root/runtime.env")" = "600"
grep -Eq '^POSTGRES_PASSWORD=[0-9a-f]{64}$' "$root/runtime.env"
grep -Eq '^APP_API_KEY=[0-9a-f]{64}$' "$root/runtime.env"
test "$(df -Pk / | awk 'NR==2 {print $4}')" -gt 6291456 || {
    echo "Insufficient free root filesystem space for release" >&2
    exit 2
}
echo 'ARM64 host SSM preflight PASS: cloud-init, Docker, Compose, AWS CLI, private credentials and disk'
