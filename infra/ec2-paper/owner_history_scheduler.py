#!/usr/bin/env python3
"""Private, fail-closed EC2 timer driver for the existing Angel One daily collector.

This is not a new market-data source and never publishes candles to customers.
The reviewed root-owned downloader remains the only broker/manifest writer.
"""
from __future__ import annotations

import datetime as dt
import json
import os
from pathlib import Path
import platform
import re
import sqlite3
import subprocess
import sys
import tempfile
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

TZ = ZoneInfo("Asia/Kolkata")
ROOT = Path("/var/lib/minttick/owner-personal-angelone-history")
STATE = ROOT / ".scheduler-state.json"
DOWNLOADER = Path("/opt/aitradingsystem-paper/run_owner_personal_history.sh")
MAX_RUN_SECONDS = 660  # Existing collector limits requests to 540 seconds.
INSTANCE_ID = "i-09c01b470fb39b85a"
IMDS = "http://169.254.169.254/latest"
PAUSE_AFTER_ZERO_BATCHES = 2
PAUSE_HOURS = 24
METRIC_KEYS = frozenset({
    "PERSONAL_REAL_EQUITY_ROWS_STORED_TOTAL",
    "PERSONAL_ROWS_STORED_TOTAL",
    "PERSONAL_PAGES_STORED_TOTAL",
    "PERSONAL_PAGES_DOWNLOADED_THIS_RUN",
    "PERSONAL_ROWS_DOWNLOADED_THIS_RUN",
    "PERSONAL_RECOVERY_SCANS_PENDING",
    "PERSONAL_FAILED_WINDOWS_TOTAL",
})


class SchedulerBlocked(Exception):
    pass


def allowed_time(now: dt.datetime) -> bool:
    local = now.astimezone(TZ)
    minute = local.hour * 60 + local.minute
    lower = 16 * 60 if local.weekday() < 5 else 9 * 60
    return lower <= minute < 21 * 60  # 22:00 IST EC2 stop; no near-stop runs.


def metrics_from_output(stdout: str) -> dict[str, int]:
    metrics = {}
    for line in stdout.splitlines():
        match = re.fullmatch(r"([A-Z][A-Z0-9_]{4,90}): ([0-9]{1,12})", line)
        if match and match.group(1) in METRIC_KEYS:
            if match.group(1) in metrics:
                raise SchedulerBlocked("DUPLICATE_COUNTER")
            metrics[match.group(1)] = int(match.group(2))
    return metrics


def load_state(path: Path) -> dict:
    if path.is_symlink():
        raise SchedulerBlocked("UNTRUSTED_STATE")
    if not path.exists():
        return {"zero_streak": 0, "paused_until": None}
    st = path.stat()
    if st.st_uid != 0 or (st.st_mode & 0o077):
        raise SchedulerBlocked("UNTRUSTED_STATE")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise SchedulerBlocked("INVALID_STATE") from None
    if (not isinstance(value, dict) or set(value) != {"zero_streak", "paused_until"}
            or type(value["zero_streak"]) is not int
            or not 0 <= value["zero_streak"] <= PAUSE_AFTER_ZERO_BATCHES
            or (value["paused_until"] is not None and
                not isinstance(value["paused_until"], str))):
        raise SchedulerBlocked("INVALID_STATE")
    return value


def save_state(path: Path, state: dict) -> None:
    # Atomic root-only JSON state. Do not touch the collector's own SQLite DB.
    if path.is_symlink():
        raise SchedulerBlocked("UNTRUSTED_STATE")
    fd, name = tempfile.mkstemp(prefix=".scheduler-", dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(state, stream, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def verify_instance_identity() -> None:
    # IMDSv2 required: never trust an arbitrary ARM64 host with this wrapper.
    try:
        request = Request(IMDS + "/api/token", data=b"", method="PUT", headers={
            "X-aws-ec2-metadata-token-ttl-seconds": "60"})
        with urlopen(request, timeout=3) as response:
            token = response.read(512).decode("ascii")
        if not token or len(token) > 512:
            raise ValueError()
        request = Request(IMDS + "/meta-data/instance-id", headers={
            "X-aws-ec2-metadata-token": token})
        with urlopen(request, timeout=3) as response:
            instance = response.read(64).decode("ascii")
    except (OSError, ValueError, UnicodeError):
        raise SchedulerBlocked("INSTANCE_IDENTITY_UNAVAILABLE") from None
    if instance != INSTANCE_ID:
        raise SchedulerBlocked("WRONG_EC2_INSTANCE")


def verify_host(now: dt.datetime) -> None:
    if os.geteuid() != 0 or platform.machine() != "aarch64":
        raise SchedulerBlocked("WRONG_HOST_IDENTITY")
    if not allowed_time(now):
        raise SchedulerBlocked("OUTSIDE_APPROVED_TIME")
    verify_instance_identity()
    if not ROOT.is_dir() or ROOT.is_symlink() or ROOT.stat().st_uid != 0:
        raise SchedulerBlocked("PRIVATE_ARCHIVE_MISSING")
    if not DOWNLOADER.is_file() or DOWNLOADER.is_symlink():
        raise SchedulerBlocked("REVIEWED_COLLECTOR_MISSING")
    if DOWNLOADER.stat().st_uid != 0 or (DOWNLOADER.stat().st_mode & 0o077):
        raise SchedulerBlocked("UNTRUSTED_COLLECTOR")


def new_state(state: dict, metrics: dict[str, int], now: dt.datetime) -> dict:
    calls = metrics.get("PERSONAL_PAGES_DOWNLOADED_THIS_RUN", 0)
    rows = metrics.get("PERSONAL_ROWS_DOWNLOADED_THIS_RUN", 0)
    if calls <= 0 or "PERSONAL_ROWS_DOWNLOADED_THIS_RUN" not in metrics:
        return state  # No progress proof, including provider cooldown; do not reset.
    if rows > 0:
        return {"zero_streak": 0, "paused_until": None}
    streak = min(PAUSE_AFTER_ZERO_BATCHES, state["zero_streak"] + 1)
    return {"zero_streak": streak,
            "paused_until": (now + dt.timedelta(hours=PAUSE_HOURS)).isoformat()
            if streak >= PAUSE_AFTER_ZERO_BATCHES else None}


def publish_progress(status: str, state: dict, last_run: dict | None = None) -> None:
    # Imported at execution time; import and snapshot failures fail closed
    # before issuing provider calls. No secrets are included in the summary.
    from owner_history_progress import ProgressUnavailable, publish
    try:
        publish(status=status, scheduler_state=state, last_run=last_run)
    except (OSError, ValueError, sqlite3.Error, ProgressUnavailable) as exc:
        raise SchedulerBlocked("PROGRESS_PUBLICATION_UNAVAILABLE") from None


def execute(now: dt.datetime | None = None) -> int:
    now = now or dt.datetime.now(dt.timezone.utc)
    verify_host(now)
    state = load_state(STATE)
    if state["paused_until"]:
        try:
            pause_end = dt.datetime.fromisoformat(state["paused_until"])
            if pause_end.tzinfo is None:
                raise ValueError()
        except ValueError:
            raise SchedulerBlocked("INVALID_PAUSE_TIMESTAMP") from None
        if now < pause_end:
            publish_progress("PAUSED", state)
            print("OWNER_SCHEDULER_PAUSED: ZERO_CANDLE_COOLDOWN", flush=True)
            return 0
        # After the 24-hour pause permit ONE new batch before another pause.
        state = {"zero_streak": PAUSE_AFTER_ZERO_BATCHES - 1, "paused_until": None}
    publish_progress("RUNNING", state)
    started = dt.datetime.now(dt.timezone.utc)
    try:
        result = subprocess.run([str(DOWNLOADER), "download"], capture_output=True,
                                text=True, timeout=MAX_RUN_SECONDS, check=False)
    except (OSError, subprocess.TimeoutExpired):
        publish_progress("ERROR", state, {
            "startedAt": started.isoformat(),
            "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "windowsQueried": 0, "newCandles": 0, "state": "FAILED"})
        raise SchedulerBlocked("COLLECTOR_UNAVAILABLE_OR_TIMEOUT") from None
    # Only allow numeric, pre-reviewed counters to reach journald. Never log
    # provider responses, authentication headers, stderr or raw OHLCV.
    metrics = metrics_from_output(result.stdout)
    for key in sorted(metrics):
        print(f"{key}: {metrics[key]}", flush=True)
    if result.returncode != 0 or "PERSONAL_GOLDEN_SOURCE_PUBLISHED: FALSE" not in result.stdout:
        publish_progress("ERROR", state, {
            "startedAt": started.isoformat(),
            "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "windowsQueried": metrics.get("PERSONAL_PAGES_DOWNLOADED_THIS_RUN", 0),
            "newCandles": metrics.get("PERSONAL_ROWS_DOWNLOADED_THIS_RUN", 0),
            "state": "FAILED"})
        raise SchedulerBlocked("COLLECTOR_FAILED_OR_UNVERIFIED")
    updated = new_state(state, metrics, dt.datetime.now(dt.timezone.utc))
    save_state(STATE, updated)
    publish_progress("PAUSED" if updated["paused_until"] else "IDLE", updated, {
        "startedAt": started.isoformat(),
        "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "windowsQueried": metrics.get("PERSONAL_PAGES_DOWNLOADED_THIS_RUN", 0),
        "newCandles": metrics.get("PERSONAL_ROWS_DOWNLOADED_THIS_RUN", 0),
        "state": "COMPLETED"})
    if updated["paused_until"]:
        print("OWNER_SCHEDULER_PAUSED: ZERO_CANDLE_COOLDOWN", flush=True)
    print("OWNER_SCHEDULER_RUN: COMPLETED", flush=True)
    return 0


if __name__ == "__main__":
    try:
        if sys.argv[1:]:
            raise SchedulerBlocked("NO_ARGUMENTS_ALLOWED")
        sys.exit(execute())
    except SchedulerBlocked as error:
        print("OWNER_SCHEDULER_BLOCKED: " + str(error), flush=True)
        sys.exit(2)
