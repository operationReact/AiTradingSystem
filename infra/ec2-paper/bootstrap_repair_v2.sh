#!/usr/bin/env bash
# One-time SSM repair on the existing protected PAPER EC2 only.
# Every failure emits a fixed STEP_<NAME>:FAIL marker; no secret values,
# remote logs or environment variables are emitted.
set -euo pipefail
umask 077

test "$(id -u)" -eq 0 || { echo "STEP_ROOT:FAIL"; exit 2; }
test "$(uname -m)" = "aarch64" || { echo "STEP_ARM64:FAIL"; exit 2; }
echo "STEP_IDENTITY:PASS"

if ! rpm -q docker >/dev/null 2>&1; then
  # AL2023 already includes curl-minimal; installing full curl conflicts with it.
  if dnf install -y --setopt=install_weak_deps=False docker openssl ca-certificates >/dev/null 2>&1; then
    echo "STEP_DOCKER_PACKAGE:PASS"
  else
    echo "STEP_DOCKER_PACKAGE:FAIL"
    if curl -sSI --max-time 8 https://cdn.amazonlinux.com >/dev/null 2>&1; then
      echo "STEP_PACKAGE_NETWORK:PASS"
    else
      echo "STEP_PACKAGE_NETWORK:FAIL"
    fi
    exit 1
  fi
else
  echo "STEP_DOCKER_PACKAGE:PASS"
fi

if systemctl enable --now docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo "STEP_DOCKER_SERVICE:PASS"
else
  echo "STEP_DOCKER_SERVICE:FAIL"
  exit 1
fi

# Retain pinned official version and official sha256 checksum.
version="v5.6.0"
base="https://github.com/docker/compose/releases/download/$version"
directory="$(mktemp -d)"
cleanup() { rm -rf -- "$directory"; }
trap cleanup EXIT

if curl --fail --location --retry 3 --max-time 180 --silent --show-error \
  "$base/docker-compose-linux-aarch64" -o "$directory/docker-compose-linux-aarch64" >/dev/null 2>&1; then
  echo "STEP_COMPOSE_DOWNLOAD:PASS"
else
  echo "STEP_COMPOSE_DOWNLOAD:FAIL"
  exit 1
fi

# Official v5.6.0 docker-compose-linux-aarch64 asset digest from GitHub release API.
compose_sha256="733ec76717ceb59052a9609b9dadfb523b2df8eab57a54212872d10a58078ea2"
if printf '%s  %s\n' "$compose_sha256" "$directory/docker-compose-linux-aarch64" | sha256sum -c - >/dev/null 2>&1; then
  echo "STEP_COMPOSE_CHECKSUM:PASS"
else
  echo "STEP_COMPOSE_CHECKSUM:FAIL"
  exit 1
fi

install -d -m 0755 /usr/local/lib/docker/cli-plugins
install -m 0755 "$directory/docker-compose-linux-aarch64" /usr/local/lib/docker/cli-plugins/docker-compose
if docker compose version >/dev/null 2>&1; then
  echo "STEP_COMPOSE_INSTALLED:PASS"
else
  echo "STEP_COMPOSE_INSTALLED:FAIL"
  exit 1
fi

root="/opt/aitradingsystem-paper"
test ! -L "$root" || { echo "STEP_RUNTIME_DIRECTORY:FAIL"; exit 1; }
install -d -m 0700 -o root -g root "$root"
test -d "$root" && test ! -L "$root" || { echo "STEP_RUNTIME_DIRECTORY:FAIL"; exit 1; }
echo "STEP_RUNTIME_DIRECTORY:PASS"

if [ ! -e "$root/runtime.env" ]; then
  generated="$(mktemp "$root/.runtime.XXXXXXXX")"
  trap 'rm -f -- "${generated:-}"; cleanup' EXIT
  printf 'POSTGRES_PASSWORD=%s\nAPP_API_KEY=%s\n' \
    "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" > "$generated"
  chmod 0600 "$generated"
  mv -f -- "$generated" "$root/runtime.env"
fi
if test -f "$root/runtime.env" && test ! -L "$root/runtime.env" && \
  test "$(stat -c '%a' "$root/runtime.env")" = "600" && \
  grep -Eq '^POSTGRES_PASSWORD=[0-9a-f]{64}$' "$root/runtime.env" && \
  grep -Eq '^APP_API_KEY=[0-9a-f]{64}$' "$root/runtime.env"; then
  echo "STEP_RUNTIME_SECRETS:PASS"
else
  echo "STEP_RUNTIME_SECRETS:FAIL"
  exit 1
fi
echo "STEP_BOOTSTRAP_COMPLETE:PASS"
