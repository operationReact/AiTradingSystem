#!/usr/bin/env bash
# Exact one-host, history-timer-only repair. No broker/API calls, trading,
# EC2 lifecycle, AWS scheduler changes, or credentials.
set -Eeuo pipefail
umask 077
test "$#" -eq 1
stage="$1"
root=/opt/aitradingsystem-paper
test "$(id -u)" -eq 0
test "$(uname -m)" = aarch64
test -d "$root" && test ! -L "$root"
test -d "$stage" && test ! -L "$stage"
for name in minttick-owner-history.service minttick-owner-history.timer; do
  test -f "$stage/$name" && test ! -L "$stage/$name"
  test ! -L "/etc/systemd/system/$name"
done
for name in owner_history_scheduler.py owner_history_progress.py; do
  test -f "$root/$name" && test ! -L "$root/$name"
  test "$(stat -c '%u:%a' "$root/$name")" = "0:600"
done
test -f "$root/run_owner_personal_history.sh"
test "$(stat -c '%u:%a' "$root/run_owner_personal_history.sh")" = "0:700"
grep -Fxq 'OnCalendar=*-*-* *:0/2:00 Asia/Kolkata' "$stage/minttick-owner-history.timer"
grep -Fxq 'Unit=minttick-owner-history.service' "$stage/minttick-owner-history.timer"
grep -Fxq 'ExecStart=/usr/bin/python3 /opt/aitradingsystem-paper/owner_history_scheduler.py' "$stage/minttick-owner-history.service"
grep -Fxq 'Type=oneshot' "$stage/minttick-owner-history.service"
grep -Fxq 'User=root' "$stage/minttick-owner-history.service"
install -o root -g root -m 0644 "$stage/minttick-owner-history.service" /etc/systemd/system/minttick-owner-history.service
install -o root -g root -m 0644 "$stage/minttick-owner-history.timer" /etc/systemd/system/minttick-owner-history.timer
systemd-analyze verify /etc/systemd/system/minttick-owner-history.service /etc/systemd/system/minttick-owner-history.timer >/dev/null
systemctl daemon-reload
systemctl enable --now minttick-owner-history.timer >/dev/null
systemctl is-enabled --quiet minttick-owner-history.timer
echo 'OWNER_TIMER_REPAIR: ENABLED'
systemctl is-active --quiet minttick-owner-history.timer
echo 'OWNER_TIMER_REPAIR: ACTIVE'
next="$(systemctl show --value -p NextElapseUSecRealtime minttick-owner-history.timer)"
test -n "$next" && test "$next" != 'n/a'
echo 'OWNER_TIMER_REPAIR: NEXT_TICK_PRESENT'
echo 'OWNER_TIMER_REPAIR: HISTORICAL_ONLY'
