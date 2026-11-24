#!/usr/bin/env python3
"""Publish only sanitized owner-private Angel One download *metadata* to EC2.

The bounded owner-only 1-year chart cache contains OHLCV but no archive pages,
broker credentials or account secrets. Only owner JWT may read chart rows.
The raw root-private archive and SQLite stay outside the backend container.
"""
from __future__ import annotations

import collections
from contextlib import contextmanager
import datetime as dt
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import stat
import subprocess
import tempfile
import time
from zoneinfo import ZoneInfo

ROOT = Path("/var/lib/minttick/owner-personal-angelone-history")
OUTPUT_DIR = Path("/opt/aitradingsystem-paper/monitor")
OUTPUT = OUTPUT_DIR / "progress.json"
TZ = ZoneInfo("Asia/Kolkata")
START_YEAR = 2006
END_YEAR = max(2026, dt.datetime.now(TZ).year)
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


def _yearly(conn: sqlite3.Connection, root: Path, *, chart_sink=None,
            monthly_sink=None, chart_start=None, chart_end=None):
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
            if monthly_sink is not None:
                monthly_sink(token, symbol, row)
            if chart_sink is not None and chart_start <= stamp <= chart_end:
                chart_sink(token, symbol, row)
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
    """Next two-minute timer check (not a guarantee of a provider request)."""
    if now.tzinfo is None or now.utcoffset() is None:
        raise ProgressUnavailable("INVALID_SCHEDULER_CLOCK")
    here = now.astimezone(TZ)
    tick = here.replace(second=0, microsecond=0)
    return (tick + dt.timedelta(minutes=2 - here.minute % 2)).isoformat()

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
                   previous_runs: list[dict] | None = None,
                   chart_sink=None, monthly_sink=None):
    if status not in ("RUNNING", "IDLE", "PAUSED", "ERROR"):
        raise ProgressUnavailable("INVALID_STATUS")
    _private_archive(root)
    database = root / "manifest.sqlite3"
    conn = sqlite3.connect("file:" + str(database) + "?mode=ro", uri=True, timeout=5)
    try:
        as_of = now.astimezone(TZ).date()
        candles, stock_count, years, total_pages = _yearly(
            conn, root, chart_sink=chart_sink, monthly_sink=monthly_sink,
            chart_start=as_of - dt.timedelta(days=365), chart_end=as_of)
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
            "requestedThrough": as_of.isoformat(),
            "latestRecordedSession": max((year["lastDate"] or "" for year in years), default="") or None,
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
            "cadence": "BOUNDED_CONTINUOUS_DAILY",
            "weekdayHoursIST": "00:00–23:58",
            "weekendHoursIST": "00:00–23:58",
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



class _OwnerChartSink:
    """Stage bounded, owner-only chart rows from fully SHA-checked archive pages."""

    def __init__(self, output_dir: Path, now: dt.datetime):
        self.parent = output_dir
        self.stage = Path(tempfile.mkdtemp(prefix=".owner-chart-staging-", dir=str(output_dir)))
        os.chmod(self.stage, 0o755)
        self.generated = now.astimezone(dt.timezone.utc).isoformat()
        self.token = None
        self.symbol = None
        self.rows = []

    def __call__(self, token: str, symbol: str, row: list):
        if self.token != token:
            self._flush()
            self.token, self.symbol, self.rows = token, symbol, []
        if self.symbol != symbol or len(self.rows) >= 400:
            raise ProgressUnavailable("INVALID_CHART_IDENTITY_OR_LIMIT")
        if not isinstance(row, list) or len(row) != 6:
            raise ProgressUnavailable("INVALID_CHART_ROW")
        self.rows.append(row)

    def _flush(self):
        if not self.rows:
            return
        if not re.fullmatch(r"[0-9]{1,12}", self.token):
            raise ProgressUnavailable("INVALID_CHART_TOKEN")
        payload = {
            "schema": "minttick.owner.chart.v1",
            "generatedAt": self.generated,
            "exchange": "NSE",
            "symbol": self.symbol,
            "token": self.token,
            "interval": "ONE_DAY",
            "rows": self.rows,
        }
        data = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
        if len(data) > 90000:
            raise ProgressUnavailable("OVERSIZE_CHART")
        target = self.stage / (self.token + ".json")
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            os.fchmod(stream.fileno(), 0o644)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())

    def complete(self):
        self._flush()
        active = self.parent / "charts"
        prior = self.parent / ".owner-charts-previous"
        for path in (active, prior):
            if path.is_symlink():
                raise ProgressUnavailable("UNTRUSTED_CHART_PATH")
        if prior.exists():
            if not prior.is_dir() or prior.stat().st_uid != 0:
                raise ProgressUnavailable("UNTRUSTED_CHART_BACKUP")
            shutil.rmtree(prior)
        if active.exists():
            if not active.is_dir() or active.stat().st_uid != 0:
                raise ProgressUnavailable("UNTRUSTED_CHART_PATH")
            os.replace(active, prior)
        try:
            os.replace(self.stage, active)
        except OSError:
            if prior.exists() and not active.exists():
                os.replace(prior, active)
            raise
        if prior.exists():
            shutil.rmtree(prior)

    def discard(self):
        if self.stage.exists() and not self.stage.is_symlink():
            shutil.rmtree(self.stage)



class _OwnerMonthlySink:
    """Aggregate only SHA-checked daily source rows into real calendar months.

    Monthly bars preserve first open, max high, min low, last close and sum
    of provider volume. Missing daily sessions are never filled or invented.
    """

    def __init__(self, output_dir: Path, now: dt.datetime):
        self.parent = output_dir
        self.stage = Path(tempfile.mkdtemp(prefix=".owner-month-staging-",
                                           dir=str(output_dir)))
        os.chmod(self.stage, 0o755)
        self.generated = now.astimezone(dt.timezone.utc).isoformat()
        self.token = None
        self.symbol = None
        self.rows = []
        self.last_session = None

    def __call__(self, token: str, symbol: str, row: list):
        if self.token != token:
            self._flush()
            self.token, self.symbol, self.rows, self.last_session = token, symbol, [], None
        if self.symbol != symbol or not isinstance(row, list) or len(row) != 6:
            raise ProgressUnavailable("INVALID_MONTHLY_CHART_IDENTITY")
        try:
            instant = dt.datetime.fromisoformat(row[0])
            if instant.tzinfo is None:
                raise ValueError("Naive timestamp")
            session = instant.astimezone(TZ).date()
            vals = row[1:5]
            if not all(type(v) in (int, float) and 0 < v < 1e9 for v in vals):
                raise ValueError("Invalid OHLC")
            op, hi, lo, close = vals
            if hi < max(op, close) or lo > min(op, close) or lo > hi:
                raise ValueError("Invalid OHLC bounds")
            volume = row[5]
            # The collector accepts finite integral provider volume such as
            # 12345.0 without rewriting the SHA-checked original payload.
            # Normalize only this exact integer quantity for monthly export.
            if (type(volume) not in (int, float) or not 0 <= volume <= 10**12
                    or volume != int(volume)):
                raise ValueError("Invalid volume")
            volume = int(volume)
        except (TypeError, ValueError, IndexError, OverflowError):
            raise ProgressUnavailable("INVALID_MONTHLY_SOURCE_ROW") from None
        if self.last_session is not None and session <= self.last_session:
            raise ProgressUnavailable("OUT_OF_ORDER_MONTHLY_SOURCE")
        self.last_session = session
        month = (session.year, session.month)
        if not self.rows or self.rows[-1][0][:7] != session.isoformat()[:7]:
            if len(self.rows) >= 400:
                raise ProgressUnavailable("MONTHLY_EXPORT_LIMIT")
            self.rows.append([row[0], op, hi, lo, close, volume])
        else:
            monthly = self.rows[-1]
            monthly[2] = max(monthly[2], hi)
            monthly[3] = min(monthly[3], lo)
            monthly[4] = close
            monthly[5] += volume

    def _flush(self):
        if not self.rows:
            return
        if not re.fullmatch(r"[0-9]{1,12}", self.token):
            raise ProgressUnavailable("INVALID_MONTHLY_TOKEN")
        payload = {
            "schema": "minttick.owner.chart.monthly.v1",
            "generatedAt": self.generated,
            "exchange": "NSE", "symbol": self.symbol, "token": self.token,
            "interval": "ONE_MONTH", "sourceInterval": "ONE_DAY",
            "aggregation": "FIRST_OPEN_MAX_HIGH_MIN_LOW_LAST_CLOSE_SUM_VOLUME",
            "sessionCompleteness": "UNVERIFIED",
            "rows": self.rows,
        }
        data = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
        if len(data) > 90000:
            raise ProgressUnavailable("OVERSIZE_MONTHLY_CHART")
        dest = self.stage / (self.token + ".json")
        fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            os.fchmod(stream.fileno(), 0o644)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())

    def complete(self):
        self._flush()
        active = self.parent / "monthly"
        prior = self.parent / ".owner-monthly-previous"
        for path in (active, prior):
            if path.is_symlink():
                raise ProgressUnavailable("UNTRUSTED_MONTHLY_CHART_PATH")
        if prior.exists():
            if not prior.is_dir() or prior.stat().st_uid != 0:
                raise ProgressUnavailable("UNTRUSTED_MONTHLY_CHART_BACKUP")
            shutil.rmtree(prior)
        if active.exists():
            if not active.is_dir() or active.stat().st_uid != 0:
                raise ProgressUnavailable("UNTRUSTED_MONTHLY_CHART_PATH")
            os.replace(active, prior)
        try:
            os.replace(self.stage, active)
        except OSError:
            if prior.exists() and not active.exists():
                os.replace(prior, active)
            raise
        if prior.exists():
            shutil.rmtree(prior)

    def discard(self):
        if self.stage.exists() and not self.stage.is_symlink():
            shutil.rmtree(self.stage)


def _publish_snapshot(root: Path = ROOT, output: Path = OUTPUT, *,
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
    chart_sink = _OwnerChartSink(target_dir, now)
    month_sink = _OwnerMonthlySink(target_dir, now)
    try:
        result = build_snapshot(root, now, status, scheduler_state or {},
                                last_run, previous, chart_sink=chart_sink,
                                monthly_sink=month_sink)
        month_sink.complete()
        chart_sink.complete()
    finally:
        chart_sink.discard()
        month_sink.discard()
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


@contextmanager
def publication_lock(directory: Path, *, timeout: float = 120):
    """Serialize timer and deployment publishers without locking/downloading candles."""
    path = directory / '.publication.lock'
    fd = None
    try:
        fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                or info.st_nlink != 1 or info.st_mode & 0o077):
            raise ProgressUnavailable('UNTRUSTED_PUBLICATION_LOCK')
        deadline = time.monotonic() + timeout
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise ProgressUnavailable('PUBLICATION_BUSY') from None
                time.sleep(0.1)
        yield
    finally:
        # Closing releases the lock even if building/committing the cache fails.
        # Never unlink the lock inode: a waiting process may already reference it.
        if fd is not None:
            os.close(fd)


def wait_for_previous_publisher(*, timeout: float = 780) -> None:
    """Drain the existing oneshot after quiescing its timer.

    Never stop the service or interrupt provider work.
    """
    deadline = time.monotonic() + timeout
    while True:
        try:
            process = subprocess.run(['systemctl', 'show', '--value', '-p', 'ActiveState',
                                      'minttick-owner-history.service'],
                                     capture_output=True, text=True, timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            raise ProgressUnavailable('PUBLICATION_HANDOVER_UNAVAILABLE') from None
        state = process.stdout.strip()
        if process.returncode or state not in {'inactive', 'failed', 'active', 'activating', 'deactivating', 'reloading'}:
            raise ProgressUnavailable('PUBLICATION_HANDOVER_UNAVAILABLE')
        if state in {'inactive', 'failed'}:
            return
        if time.monotonic() >= deadline:
            raise ProgressUnavailable('PUBLICATION_HANDOVER_BUSY')
        time.sleep(2)


_HISTORY_TIMER = 'minttick-owner-history.timer'


def _history_timer_command(*args: str, failure: str) -> str:
    try:
        result = subprocess.run(['systemctl', *args], capture_output=True,
                                text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        raise ProgressUnavailable(failure) from None
    if result.returncode:
        raise ProgressUnavailable(failure)
    return result.stdout.strip()


@contextmanager
def quiesce_previous_publisher():
    """Pause only future timer activations; restore the prior active state.

    Keep the timer's enabled state unchanged. Never stop the running oneshot.
    Drain before replacing legacy modules, and restore the timer on failures.
    An initially inactive (including not-yet-installed) timer stays inactive.
    """
    stop_error = 'PUBLICATION_TIMER_QUIESCE_FAILED'
    restore_error = 'PUBLICATION_TIMER_RESTORE_FAILED'
    state = _history_timer_command('show', '--value', '-p', 'ActiveState',
                                   _HISTORY_TIMER, failure=stop_error)
    if state not in {'active', 'inactive'}:
        raise ProgressUnavailable(stop_error)
    restore = state == 'active'
    try:
        if restore:
            _history_timer_command('stop', _HISTORY_TIMER, failure=stop_error)
        if _history_timer_command('show', '--value', '-p', 'ActiveState',
                                  _HISTORY_TIMER, failure=stop_error) != 'inactive':
            raise ProgressUnavailable(stop_error)
        wait_for_previous_publisher()
        yield
    finally:
        if restore:
            _history_timer_command('start', _HISTORY_TIMER, failure=restore_error)
            if _history_timer_command('show', '--value', '-p', 'ActiveState',
                                      _HISTORY_TIMER, failure=restore_error) != 'active':
                raise ProgressUnavailable(restore_error)


def publish(root: Path = ROOT, output: Path = OUTPUT, *,
            now: dt.datetime | None = None, status: str = 'IDLE',
            scheduler_state: dict | None = None, last_run: dict | None = None) -> None:
    if os.geteuid() != 0:
        raise ProgressUnavailable('ROOT_REQUIRED')
    directory = output.parent
    if directory.is_symlink():
        raise ProgressUnavailable('UNTRUSTED_MONITOR_PATH')
    directory.mkdir(parents=True, exist_ok=True)
    if directory.stat().st_uid != 0:
        raise ProgressUnavailable('UNTRUSTED_MONITOR_PATH')
    with publication_lock(directory):
        _publish_snapshot(root, output, now=now, status=status,
                          scheduler_state=scheduler_state, last_run=last_run)
