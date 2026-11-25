#!/usr/bin/env bash
# Invoke exclusively by the GitHub dev-only SSM workflow on the exact PAPER EC2.
# One SHA256-verified, immutable commit archive. No SSH, public listeners,
# Terraform modifications, broker credentials or LIVE trading.
set -Eeuo pipefail
umask 077
# Only fixed stage labels are emitted on failure; never expose host secrets or command output.
stage=host_precheck
trap 'printf "MINTTICK_DEPLOY_FAILED_STAGE:%s\n" "$stage" >&2' ERR

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
stage=artifact_download
# Inspect S3 errors locally and reveal only a fixed, non-secret category.
# Never forward raw AWS CLI output or temporary credentials to GitHub logs.
if ! aws s3 cp "s3://$bucket/releases/$sha/$expected_hash/release.tar" "$bundle" \
  --region ap-south-1 --no-progress --only-show-errors 2>"$work/s3-download.err"; then
  reason=unknown
  if grep -Eiq 'AccessDenied|Forbidden|403' "$work/s3-download.err"; then
    reason=access_denied
  elif grep -Eiq 'NoSuchKey|Not Found|404' "$work/s3-download.err"; then
    reason=object_missing
  elif grep -Eiq 'No space left|Errno 28' "$work/s3-download.err"; then
    reason=disk_full
  elif grep -Eiq 'Unable to locate credentials|NoCredentials|credential' "$work/s3-download.err"; then
    reason=credentials_unavailable
  elif grep -Eiq 'Could not connect|Name or service not known|timed out|EndpointConnectionError' "$work/s3-download.err"; then
    reason=network_unavailable
  fi
  printf 'MINTTICK_S3_DOWNLOAD_FAILURE:%s\n' "$reason" >&2
  exit 2
fi
stage=artifact_integrity
printf '%s  %s\n' "$expected_hash" "$bundle" | sha256sum -c - >/dev/null || {
  echo "Release SHA256 mismatch; no Docker image will be loaded" >&2
  exit 2
}

# Expected archive produced on the GitHub runner. Deny extra paths.
stage=archive_validation
list="$(tar -tf "$bundle" | LC_ALL=C sort)"
expected="$(printf 'images.tar.gz\npaper-compose.yml\nrun_paper.sh\ninstall_owner_history_monitor.sh\nowner_history_scheduler.py\nowner_history_progress.py\nminttick-owner-history.service\nminttick-owner-history.timer' | LC_ALL=C sort)"
test "$list" = "$expected" || { echo "Unexpected archive members" >&2; exit 2; }
tar -xf "$bundle" -C "$work"
for f in images.tar.gz paper-compose.yml run_paper.sh install_owner_history_monitor.sh owner_history_scheduler.py owner_history_progress.py minttick-owner-history.service minttick-owner-history.timer; do
  test -f "$work/$f" && test ! -L "$work/$f" || exit 2
done
test "$(du -b "$work/images.tar.gz" | cut -f1)" -gt 1000000 || exit 2

# Validate scripts and image names before replacing active release.
bash -n "$work/run_paper.sh"
bash -n "$work/install_owner_history_monitor.sh"
grep -q 'TRADING_MODE: PAPER' "$work/paper-compose.yml"
grep -q '/opt/aitradingsystem-paper/monitor:/run/minttick-owner-history:ro' "$work/paper-compose.yml"
# Fail closed on missing existing private owner archive before any app rollout.
stage=history_precheck
bash "$work/install_owner_history_monitor.sh" prepare "$work"
stage=image_load
docker load --input "$work/images.tar.gz" >/dev/null

# Host-local runtime.env (0600) is never overwritten or printed.
stage=host_activation
install -o root -g root -m 0600 "$work/paper-compose.yml" "$root/paper-compose.yml"
install -o root -g root -m 0700 "$work/run_paper.sh" "$root/run_paper.sh"
bash "$root/run_paper.sh" "$sha"
stage=history_activation
bash "$work/install_owner_history_monitor.sh" activate "$work"
echo "SSM ARM64 PAPER application + private owner history monitor acceptance passed for exact release $sha"
