#!/usr/bin/env bash
# Invoke exclusively by the GitHub dev-only SSM workflow on the exact PAPER EC2.
# One SHA256-verified, immutable commit archive. No SSH, public listeners,
# Terraform modifications, broker credentials or LIVE trading.
set -euo pipefail
umask 077

sha="${1:-}"
expected_hash="${2:-}"
bucket="aitrading-paper-releases-098859530205"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid immutable release commit" >&2; exit 2; }
[[ "$expected_hash" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid expected artifact SHA256" >&2; exit 2; }
test "$(id -u)" -eq 0 && test "$(uname -m)" = "aarch64"

root="/opt/aitradingsystem-paper"
test -d "$root" && test ! -L "$root"
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
# Temporary staging stays on the existing encrypted EBS and is erased after
# successful or unsuccessful deployment. Enforce no symlinks or special files.
work="$(mktemp -d "$root/.ssm-release.XXXXXXXX")"
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT
chmod 0700 "$work"
bundle="$work/release.tar"
aws s3 cp "s3://$bucket/releases/$sha/release.tar" "$bundle" \
  --region ap-south-1 --no-progress --only-show-errors
printf '%s  %s\n' "$expected_hash" "$bundle" | sha256sum -c - >/dev/null || {
  echo "Release SHA256 mismatch; no Docker image will be loaded" >&2
  exit 2
}

# Expected archive produced on the GitHub runner. Deny extra paths.
list="$(tar -tf "$bundle" | LC_ALL=C sort)"
expected="$(printf 'images.tar.gz\npaper-compose.yml\nrun_paper.sh' | LC_ALL=C sort)"
test "$list" = "$expected" || { echo "Unexpected archive members" >&2; exit 2; }
tar -xf "$bundle" -C "$work"
for f in images.tar.gz paper-compose.yml run_paper.sh; do
  test -f "$work/$f" && test ! -L "$work/$f" || exit 2
done
test "$(du -b "$work/images.tar.gz" | cut -f1)" -gt 1000000 || exit 2

# Validate scripts and image names before replacing active release.
bash -n "$work/run_paper.sh"
grep -q 'TRADING_MODE: PAPER' "$work/paper-compose.yml"
docker load --input "$work/images.tar.gz" >/dev/null

# Host-local runtime.env (0600) is never overwritten or printed.
install -o root -g root -m 0600 "$work/paper-compose.yml" "$root/paper-compose.yml"
install -o root -g root -m 0700 "$work/run_paper.sh" "$root/run_paper.sh"
bash "$root/run_paper.sh" "$sha"
echo "SSM ARM64 PAPER application acceptance passed for exact release $sha"
