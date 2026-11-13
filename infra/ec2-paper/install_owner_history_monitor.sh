#!/usr/bin/env bash
# Reviewed EC2-only owner history scheduler install/activation.
# Called only from the immutable, SHA256-verified PAPER SSM release.
# Never fetches broker data, changes AWS infrastructure, or handles secrets.
set -Eeuo pipefail
umask 077
mode="${1:-}"
stage="${2:-}"
root=/opt/aitradingsystem-paper
[[ "$mode" == prepare || "$mode" == activate ]] || exit 2
test "$#" -eq 2
test "$(id -u)" -eq 0 && test "$(uname -m)" = aarch64
test -d "$root" && test ! -L "$root"
test -d "$stage" && test ! -L "$stage"
for name in owner_history_scheduler.py owner_history_progress.py minttick-owner-history.service minttick-owner-history.timer; do
  test -f "$stage/$name" && test ! -L "$stage/$name"
done
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
# Root-only personal collector, already installed by reviewed Angel One pilot.
for name in probe_owner_angelone_auth.py owner_personal_angelone_history.py; do
  test -f "$root/$name" && test ! -L "$root/$name"
  test "$(stat -c '%u:%a' "$root/$name")" = "0:600"
done
test -f "$root/run_owner_personal_history.sh" && test ! -L "$root/run_owner_personal_history.sh"
test "$(stat -c '%u:%a' "$root/run_owner_personal_history.sh")" = "0:700"
test -d /var/lib/minttick/owner-personal-angelone-history
test ! -L /var/lib/minttick/owner-personal-angelone-history

# Only metadata can leave the private archive. The app container receives no
# candle pages, SQLite database, broker profile or SSM credentials.
if [[ "$mode" == prepare ]]; then
  python3 -m py_compile "$stage/owner_history_scheduler.py" "$stage/owner_history_progress.py"
  bash -n "$root/run_owner_personal_history.sh"
  python3 - "$stage" <<'PY'
import sys
sys.path.insert(0, sys.argv[1])
from owner_history_scheduler import verify_instance_identity, ROOT, STATE, load_state
from owner_history_progress import _private_archive
verify_instance_identity()
_private_archive(ROOT)
load_state(STATE)
PY
  install -o root -g root -m 0600 "$stage/owner_history_scheduler.py" "$root/owner_history_scheduler.py"
  install -o root -g root -m 0600 "$stage/owner_history_progress.py" "$root/owner_history_progress.py"
  test ! -L "$root/monitor"
  install -d -o root -g root -m 0755 "$root/monitor"
  python3 - "$root" <<'PY'
import sys
sys.path.insert(0,sys.argv[1])
from owner_history_scheduler import STATE, load_state
from owner_history_progress import publish
publish(status="IDLE", scheduler_state=load_state(STATE))
PY
  test -f "$root/monitor/progress.json" && test ! -L "$root/monitor/progress.json"
  test "$(stat -c '%u:%a' "$root/monitor/progress.json")" = "0:644"
  install -o root -g root -m 0644 "$stage/minttick-owner-history.service" /etc/systemd/system/minttick-owner-history.service
  install -o root -g root -m 0644 "$stage/minttick-owner-history.timer" /etc/systemd/system/minttick-owner-history.timer
  systemd-analyze verify /etc/systemd/system/minttick-owner-history.service /etc/systemd/system/minttick-owner-history.timer
  echo 'OWNER_HISTORY_MONITOR_SNAPSHOT: VERIFIED'
else
  # Only activate after the backend and proxy have passed connected health.
  test -s "$root/monitor/progress.json"
  test "$(stat -c '%u:%a' "$root/monitor/progress.json")" = "0:644"
  systemctl daemon-reload
  systemctl enable --now minttick-owner-history.timer >/dev/null
  systemctl is-enabled --quiet minttick-owner-history.timer
  systemctl is-active --quiet minttick-owner-history.timer
  echo 'OWNER_HISTORY_TIMER: ENABLED'
fi
