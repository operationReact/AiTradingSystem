#!/usr/bin/env python3
"""Publish only sanitized owner-private Angel One download *metadata* to EC2.

No OHLCV, symbols' tokens, credentials, source pages or issuer secrets leave the
root-only archive. Missing/unverified sessions are never marked complete.
"""
from __future__ import annotations

import collections
import datetime as dt
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile
from zoneinfo import ZoneInfo

ROOT = Path("/var/lib/minttick/owner-personal-angelone-history")
OUTPUT_DIR = Path("/opt/aitradingsystem-paper/monitor")
OUTPUT = OUTPUT_DIR / "progress.json"
TZ = ZoneInfo("Asia/Kolkata")
START_YEAR = 2006
END_YEAR = 2026
SCHEMA = "minttick.owner.history.progress.v1"
MAX_PAGES = 50000
MAX_BLOB_BYTES = 786432
MAX_SNAPSHOT_BYTES = 80000


class ProgressUnavailable(Exception):
    pass


def _private_archive(root: Path) -> None:
    if (root.is_symlink() or not root.is_dir()
            or root.stat().st_uid != 0 or root.stat().st_mode & 0o077):
        raise ProgressUnavailable("PRIVATE_ARCHIVE_UNAVAILABLE")
    for path in (root / "manifest.sqlite3", root / "pages"):
        if path.is_symlink() or not path.exists() or path.stat().st_uid != 0:
            raise ProgressUnavailable("PRIVATE_ARCHIVE_UNAVAILABLE")
        if path.stat().st_mode & 0o077:
            raise ProgressUnavailable("PRIVATE_ARCHIVE_UNAVAILABLE")


def _yearly(conn: sqlite3.Connection, root: Path):
    pages = conn.execute("""SELECT symbol,token,source_sha256,archive_relative_path,
                                   row_count,earliest,latest
                            FROM page WHERE exchange='NSE' ORDER BY token,from_date""").fetchall()
    if len(pages) > MAX_PAGES:
        raise ProgressUnavailable("TOO_MANY_PAGES")
    by_year = collections.defaultdict(lambda: {
        "candles": 0, "symbols": set(), "earliest": None, "latest": None})
    real_rows = 0
    tokens_with_rows = set()
    covered_sessions = set()  # Exact (token, session) deduplication.
    for symbol, token, digest, rel, row_count, earliest, latest in pages:
        if not isinstance(symbol, str) or not re.fullmatch(r"[A-Z0-9_.&-]{1,40}-EQ", symbol):
            raise ProgressUnavailable("INVALID_ARCHIVE_IDENTITY")
        if (not isinstance(token, str) or not re.fullmatch(r"[0-9]{1,12}", token)
                or not isinstance(digest, str) or not re.fullmatch("[0-9a-f]{64}", digest)
                or rel != "pages/" + digest + ".json.gz"
                or not isinstance(row_count, int) or row_count < 0 or row_count > 2000):
            raise ProgressUnavailable("INVALID_ARCHIVE_MANIFEST")
        file = root / rel
        if (file.is_symlink() or not file.is_file() or file.stat().st_uid != os.geteuid()
                or file.stat().st_mode & 0o077 or file.stat().st_size > 1024 * 1024):
            raise ProgressUnavailable("INVALID_ARCHIVE_FILE")
        try:
            with gzip.open(file, "rb") as stream:
                blob = stream.read(MAX_BLOB_BYTES + 1)
            if len(blob) > MAX_BLOB_BYTES or hashlib.sha256(blob).hexdigest() != digest:
                raise ProgressUnavailable("ARCHIVE_CHECKSUM_MISMATCH")
            value = json.loads(blob)
        except (OSError, EOFError, UnicodeError, ValueError):
            raise ProgressUnavailable("ARCHIVE_CHECKSUM_MISMATCH") from None
        if (not isinstance(value, dict)
                or value.get("format") != "minttick.owner.personal.angelone.daily.v1"
                or value.get("symbol") != symbol or value.get("token") != token
                or value.get("interval") != "ONE_DAY"
                or not isinstance(value.get("rows"), list)
                or len(value["rows"]) != row_count):
            raise ProgressUnavailable("ARCHIVE_METADATA_MISMATCH")
        # Exchange test securities are retained in the private audit but
        # are excluded from usable equity counts and year-by-year totals.
        if symbol.endswith("NSETEST-EQ"):
            continue
        for row in value["rows"]:
            try:
                stamp = dt.datetime.fromisoformat(row[0]).astimezone(TZ).date()
            except (ValueError, TypeError, AttributeError, IndexError):
                raise ProgressUnavailable("INVALID_SESSION_DATE") from None
            if not START_YEAR <= stamp.year <= END_YEAR:
                raise ProgressUnavailable("OUTSIDE_REQUESTED_RANGE")
            session = (token, stamp.isoformat())
            if session in covered_sessions:
                raise ProgressUnavailable("DUPLICATE_ARCHIVE_SESSION")
            covered_sessions.add(session)
            y = by_year[stamp.year]
            y["candles"] += 1
            y["symbols"].add(token)
            y["earliest"] = min(y["earliest"], stamp) if y["earliest"] else stamp
            y["latest"] = max(y["latest"], stamp) if y["latest"] else stamp
            real_rows += 1
            tokens_with_rows.add(token)
    years = []
    for year in range(START_YEAR, END_YEAR + 1):
        item = by_year[year]
        years.append({
            "year": year, "candles": item["candles"],
            "stocks": len(item["symbols"]),
            "firstDate": item["earliest"].isoformat() if item["earliest"] else None,
            "lastDate": item["latest"].isoformat() if item["latest"] else None,
            "completeness": "UNVERIFIED" if item["candles"] else "NO_DATA",
        })
    return real_rows, len(tokens_with_rows), years, len(pages)


def _next_scheduled_at(now: dt.datetime) -> str:
    """Next authorized two-minute timer check; never promises an API request."""
    here = now.astimezone(TZ)
    for step in range(8):
        day = (here + dt.timedelta(days=step)).date()
        first_hour = 0
        for hour in range(first_hour, 21):
            for minute in range(0, 60, 2):
                candidate = dt.datetime(
                    day.year, day.month, day.day, hour, minute, tzinfo=TZ)
                if candidate > here:
                    return candidate.isoformat()
    raise ProgressUnavailable("SCHEDULER_CALENDAR_UNAVAILABLE")

def _previous_runs(path: Path) -> list[dict]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_SNAPSHOT_BYTES:
        return []
    try:
        previous = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    if previous.get("schema") != SCHEMA or not isinstance(previous.get("recentRuns"), list):
        return []
    return [x for x in previous["recentRuns"][:7]
            if isinstance(x, dict) and set(x) == {
                "startedAt", "finishedAt", "windowsQueried", "newCandles", "state"}
            and isinstance(x["newCandles"], int)
            and isinstance(x["windowsQueried"], int)
            and x["state"] in ("COMPLETED", "FAILED")]


def build_snapshot(root: Path, now: dt.datetime, status: str,
                   scheduler_state: dict, last_run: dict | None = None,
                   previous_runs: list[dict] | None = None):
    if status not in ("RUNNING", "IDLE", "PAUSED", "ERROR"):
        raise ProgressUnavailable("INVALID_STATUS")
    _private_archive(root)
    database = root / "manifest.sqlite3"
    conn = sqlite3.connect("file:" + str(database) + "?mode=ro", uri=True, timeout=5)
    try:
        candles, stock_count, years, total_pages = _yearly(conn, root)
        failed = conn.execute("SELECT count(*) FROM page_failure").fetchone()[0]
        pending = conn.execute("SELECT count(*) FROM recovery_scan WHERE next_through IS NOT NULL").fetchone()[0]
        entries = conn.execute(
            """SELECT symbol,from_date,through_date,outcome,returned_rows
                 FROM candle_request ORDER BY id DESC LIMIT 12""").fetchall()
    finally:
        conn.close()
    requests = []
    for symbol, first, through, outcome, count in entries:
        # Deliberately omit tokens, raw prices, request headers and raw messages.
        if not re.fullmatch(r"[A-Z0-9_.&-]{1,40}-EQ", symbol):
            continue
        requests.append({"symbol": symbol, "fromDate": first,
                         "throughDate": through,
                         "outcome": outcome if outcome in (
                             "CANDLES_RETURNED", "EMPTY_CAUSE_UNKNOWN",
                             "PROVIDER_RATE_LIMITED", "PROVIDER_REJECTED",
                             "COVERAGE_UNVERIFIED", "OUTCOME_UNKNOWN") else "FAILED",
                         "returnedCandles": count if isinstance(count, int) and count >= 0 else None})
    recent = list(previous_runs or [])
    if last_run is not None:
        recent.insert(0, last_run)
    data = {
        "schema": SCHEMA,
        "generatedAt": now.astimezone(dt.timezone.utc).isoformat(),
        "status": status,
        "source": "ANGEL_ONE",
        "task": {
            "name": "NSE cash equities · Historical candles",
            "interval": "ONE_DAY",
            "requestedFrom": "2006-11-10",
            "requestedThrough": "2026-11-10",
            "candles": candles,
            "stocksWithCandles": stock_count,
            "archivePages": total_pages,
            "failedWindows": failed,
            "pendingRecoveryScans": pending,
            "yearCoverageVerified": False,
            "listingHistoryVerified": False,
            "years": years,
        },
        "scheduler": {
            "cadence": "BOUNDED_CONTINUOUS_OFF_HOURS",
            "weekdayHoursIST": "00:00–20:58",
            "weekendHoursIST": "00:00–20:58",
            "nextScheduledAt": _next_scheduled_at(now),
            "zeroCandleStreak": int(scheduler_state.get("zero_streak", 0)),
            "pausedUntil": scheduler_state.get("paused_until"),
        },
        "recentRuns": recent[:8],
        "recentRequests": requests,
        "note": "Year counts are verified stored rows, NOT proof of complete historical sessions.",
    }
    if len(json.dumps(data, separators=(",", ":")).encode()) > MAX_SNAPSHOT_BYTES:
        raise ProgressUnavailable("SNAPSHOT_TOO_LARGE")
    return data


def publish(root: Path = ROOT, output: Path = OUTPUT, *,
            now: dt.datetime | None = None, status: str = "IDLE",
            scheduler_state: dict | None = None, last_run: dict | None = None) -> None:
    if os.geteuid() != 0:
        raise ProgressUnavailable("ROOT_REQUIRED")
    now = now or dt.datetime.now(dt.timezone.utc)
    target_dir = output.parent
    if target_dir.is_symlink():
        raise ProgressUnavailable("UNTRUSTED_MONITOR_PATH")
    target_dir.mkdir(parents=True, exist_ok=True)
    if target_dir.stat().st_uid != 0:
        raise ProgressUnavailable("UNTRUSTED_MONITOR_PATH")
    os.chmod(target_dir, 0o755)  # Metadata only: readable by backend container.
    if output.is_symlink():
        raise ProgressUnavailable("UNTRUSTED_MONITOR_FILE")
    previous = _previous_runs(output)
    result = build_snapshot(root, now, status, scheduler_state or {},
                            last_run, previous)
    data = (json.dumps(result, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
    fd, tmp = tempfile.mkstemp(prefix=".progress-", dir=str(target_dir))
    try:
        os.fchmod(fd, 0o644)
        with os.fdopen(fd, "wb") as writer:
            writer.write(data)
            writer.flush()
            os.fsync(writer.fileno())
        os.replace(tmp, output)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
