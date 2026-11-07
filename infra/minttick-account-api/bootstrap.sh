#!/usr/bin/env bash
# Dedicated MintTick ACCOUNT API host, not the PAPER trading host.
set -euo pipefail
umask 077
dnf install -y docker amazon-ssm-agent openssl
systemctl enable --now docker amazon-ssm-agent
install -d -m 0700 /opt/minttick-account
install -d -m 0700 /opt/minttick-account/postgres
install -d -m 0700 /opt/minttick-account/caddy-data
install -d -m 0700 /opt/minttick-account/caddy-config
if [ ! -e /opt/minttick-account/runtime.env ]; then
  printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)" > /opt/minttick-account/runtime.env
  chmod 0600 /opt/minttick-account/runtime.env
fi
echo 'Account host bootstrap complete; no public application is started until reviewed release.'
