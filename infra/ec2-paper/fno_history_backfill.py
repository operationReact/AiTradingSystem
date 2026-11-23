#!/usr/bin/env python3
"""Dormant NSE_FO expired-contract backfill core.

Checkpoint metadata only: NO OHLCV/OI values or credentials are stored here.
Provider access and market-data writes are impossible without a caller-supplied,
time-limited documented retention grant, provider adapter AND idempotent sink.
No command line, schedule, AWS, SSM or broker-order code is provided.

Inspired by the verified read-only Upstox expired F&O probe:
infra/ec2-paper/fno_expired_history_probe.py. This module is NOT the
existing operational downloader and cannot be invoked by existing workflows.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import time
from typing import Callable, Protocol

SCHEMA_VERSION = 1
SOURCE = "upstox-expired-nse-fo"
SUPPORTED = frozenset({"day", "30minute", "15minute", "5minute", "3minute", "1minute"})
MAX_WINDOW_DAYS = {"day": 30, "30minute": 7, "15minute": 7,
                   "5minute": 3, "3minute": 3, "1minute": 1}
MAX_RETRIES = 5
LEASE_SECONDS = 90
DEFAULT_MIN_SECONDS_PER_GET = 1.5  # conservative local pace, not provider entitlement


class GateClosed(RuntimeError):
    """Missing rights, expired approval, or unsafe runner configuration."""


@dataclass(frozen=True)
class RetentionGrant:
    """Explicit approval supplied by an authorized integration, NOT environment."""
    reference: str
    source: str
    allow_storage: bool
    intervals: tuple[str, ...]
    valid_until: date

    def require(self, interval: str, today: date) -> None:
        if (self.source != SOURCE or not self.allow_storage
                or not self.reference.startswith("verified-")
                or len(self.reference) > 100
                or self.valid_until < today or interval not in self.intervals
                or interval not in SUPPORTED):
            raise GateClosed("FNO_DATA_RETENTION_NOT_AUTHORIZED")


@dataclass(frozen=True)
class ProviderReply:
    status: int
    candles: object = None
    retry_after_seconds: int = 0


@dataclass(frozen=True)
class Batch:
    job_id: int
    contract_key: str
    interval: str
    first: date
    last: date
    attempts: int

    @property
    def dedupe_key(self) -> str:
        key = f"{SOURCE}|{self.contract_key}|{self.interval}|{self.first}|{self.last}"
        return hashlib.sha256(key.encode()).hexdigest()


class ExpiredProvider(Protocol):
    def fetch(self, contract_key: str, interval: str,
              first: date, last: date) -> ProviderReply: ...


class IdempotentSink(Protocol):
    def upsert(self, batch: Batch, rows: list[tuple],
               idempotency_key: str) -> bool: ...


def utcnow() -> float:
    return datetime.now(timezone.utc).timestamp()


def _date(d: object) -> date:
    if type(d) is not date:
        raise ValueError("INVALID_DATE")
    return d


def _contract_key(key: str) -> str:
    # No hardcoded expired contract identity. Input originates in provider
    # discovery and is validated at the integration boundary.
    import re
    if not isinstance(key, str) or not re.fullmatch(
        r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}", key
    ):
        raise ValueError("INVALID_EXPIRED_INSTRUMENT_KEY")
    return key


def split_windows(first: date, last: date, interval: str):
    """Inclusive deterministic chunks; no gaps and no overlap."""
    first, last = _date(first), _date(last)
    if interval not in SUPPORTED or first > last:
        raise ValueError("INVALID_FNO_WINDOW")
    width = MAX_WINDOW_DAYS[interval]
    cursor = first
    while cursor <= last:
        end = min(cursor + timedelta(days=width - 1), last)
        yield cursor, end
        cursor = end + timedelta(days=1)


def validate_candles(candles: object, batch: Batch) -> list[tuple]:
    """Upstox expired history: [timestamp,open,high,low,close,volume,OI]."""
    if not isinstance(candles, list) or len(candles) > 20000:
        raise ValueError("INVALID_FNO_CANDLES")
    result = []
    seen = set()
    seen_daily_dates = set()
    for item in candles:
        if not isinstance(item, list) or len(item) != 7:
            raise ValueError("INVALID_FNO_CANDLES")
        stamp, op, high, low, close, vol, oi = item
        try:
            timestamp = datetime.fromisoformat(stamp)
        except (TypeError, ValueError):
            raise ValueError("INVALID_FNO_TIMESTAMP") from None
        if (timestamp.tzinfo is None
                or not batch.first <= timestamp.date() <= batch.last
                or timestamp.isoformat() in seen):
            raise ValueError("INVALID_FNO_TIMESTAMP")
        seen.add(timestamp.isoformat())
        if batch.interval == "day":
            # Official Upstox expired-day samples are midnight IST; a 09:15
            # observation under the same day interval is ambiguous, not a
            # second daily bar. Fail the whole batch, never silently select.
            canonical_offset = timedelta(hours=5, minutes=30)
            if (timestamp.utcoffset() != canonical_offset
                    or timestamp.hour != 0 or timestamp.minute != 0
                    or timestamp.second != 0 or timestamp.microsecond != 0
                    or timestamp.date() in seen_daily_dates):
                raise ValueError("INVALID_FNO_DAILY_CANDLE")
            seen_daily_dates.add(timestamp.date())
        if (any(type(v) not in (int, float) or not math.isfinite(v) or v <= 0
                for v in (op, high, low, close))
                or low > min(op, close) or high < max(op, close)
                or high < low or type(vol) is not int or vol < 0
                or type(oi) is not int or oi < 0):
            raise ValueError("INVALID_FNO_OHLCV_OI")
        result.append((timestamp.isoformat(), op, high, low, close, vol, oi))
    return result


class MetadataQueue:
    """SQLite metadata journal, no market-data payload.

    Jobs are unique by provider contract + interval + inclusive date window.
    A process crash after an idempotent sink write safely repeats its upsert
    when a stale lease expires; never mark complete before sink confirms commit.
    """

    def __init__(self, filename: str | Path):
        self.path = Path(filename)
        self.db = sqlite3.connect(str(self.path), timeout=15, isolation_level=None)
        self.db.execute("PRAGMA busy_timeout=15000")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS fno_backfill_jobs(
            id INTEGER PRIMARY KEY,
            contract_key TEXT NOT NULL,
            interval TEXT NOT NULL,
            first_day TEXT NOT NULL,
            last_day TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN
                ('PENDING','ACTIVE','RETRY','DONE','EMPTY','FAILED','BLOCKED')),
            attempts INTEGER NOT NULL DEFAULT 0,
            next_after REAL NOT NULL DEFAULT 0,
            lease_until REAL NOT NULL DEFAULT 0,
            rows_written INTEGER NOT NULL DEFAULT 0,
            reason TEXT NOT NULL DEFAULT 'NONE',
            updated_at REAL NOT NULL,
            UNIQUE(contract_key,interval,first_day,last_day)
        )""")
        self.db.execute("CREATE INDEX IF NOT EXISTS ix_fno_due ON fno_backfill_jobs(state,next_after,lease_until)")

    def close(self):
        self.db.close()

    def plan(self, contract_key: str, interval: str, first: date,
             last: date, *, rights: RetentionGrant | None = None,
             today: date | None = None) -> int:
        # Metadata planning is also gated, so production cannot accidentally
        # accumulate a queue before source retention rights are verified.
        if rights is None:
            raise GateClosed("FNO_DATA_RETENTION_NOT_AUTHORIZED")
        rights.require(interval, today or datetime.now(timezone.utc).date())
        key = _contract_key(contract_key)
        first, last = _date(first), _date(last)
        contract_expiry = datetime.strptime(key.rsplit('|', 1)[1], '%d-%m-%Y').date()
        if contract_expiry >= (today or datetime.now(timezone.utc).date()) or last > contract_expiry:
            raise ValueError('CONTRACT_NOT_EXPIRED_OR_WINDOW_EXCEEDS_EXPIRY')
        windows = list(split_windows(first, last, interval))
        now = utcnow()
        self.db.execute("BEGIN IMMEDIATE")
        try:
            before = self.db.total_changes
            self.db.executemany("""INSERT OR IGNORE INTO fno_backfill_jobs
                (contract_key,interval,first_day,last_day,state,updated_at)
                VALUES (?,?,?,?,'PENDING',?)""",
                [(key, interval, a.isoformat(), b.isoformat(), now)
                 for a, b in windows])
            added = self.db.total_changes - before
            self.db.execute("COMMIT")
            return added
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def claim(self, now: float | None = None) -> Batch | None:
        now = utcnow() if now is None else now
        self.db.execute("BEGIN IMMEDIATE")
        try:
            entry = self.db.execute("""SELECT id,contract_key,interval,first_day,last_day,attempts
                FROM fno_backfill_jobs
                WHERE (state IN ('PENDING','RETRY') AND next_after<=?)
                   OR (state='ACTIVE' AND lease_until<=?)
                ORDER BY first_day, id LIMIT 1""", (now, now)).fetchone()
            if entry is None:
                self.db.execute("COMMIT")
                return None
            job_id, key, interval, first, last, attempts = entry
            attempts += 1
            if attempts > MAX_RETRIES:
                self.db.execute("""UPDATE fno_backfill_jobs
                    SET state='FAILED',reason='RETRY_EXHAUSTED',lease_until=0,updated_at=?
                    WHERE id=?""", (now, job_id))
                self.db.execute("COMMIT")
                return None
            self.db.execute("""UPDATE fno_backfill_jobs
                SET state='ACTIVE',attempts=?,lease_until=?,updated_at=? WHERE id=?""",
                (attempts, now + LEASE_SECONDS, now, job_id))
            self.db.execute("COMMIT")
            return Batch(job_id, key, interval, date.fromisoformat(first),
                         date.fromisoformat(last), attempts)
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def finish(self, batch: Batch, *, state: str, rows: int = 0,
               reason: str = "NONE", retry_after: int = 0,
               now: float | None = None) -> None:
        if state not in {"DONE", "EMPTY", "RETRY", "FAILED", "BLOCKED"}:
            raise ValueError("INVALID_FNO_STATE")
        allowed_reasons = {"NONE","RATE_LIMIT","PROVIDER_5XX","TRANSPORT",
                           "AUTH_REJECTED","INVALID_DATA","SINK_ERROR","RETRY_EXHAUSTED",
                           "PROVIDER_4XX","SINK_PROVENANCE_REJECTED"}
        if reason not in allowed_reasons or type(rows) is not int or rows < 0:
            raise ValueError("INVALID_FNO_RECEIPT")
        if state != "DONE" and rows:
            raise ValueError("INVALID_FNO_RECEIPT")
        now = utcnow() if now is None else now
        bounded_retry = min(1800, max(2, int(retry_after))) if state == "RETRY" else 0
        self.db.execute("BEGIN IMMEDIATE")
        try:
            count = self.db.execute("""UPDATE fno_backfill_jobs
                SET state=?,reason=?,rows_written=?,next_after=?,lease_until=0,updated_at=?
                WHERE id=? AND state='ACTIVE' AND attempts=?""",
                (state,reason,rows,now+bounded_retry,now,batch.job_id,batch.attempts)).rowcount
            if count != 1:
                raise RuntimeError("LOST_FNO_JOB_LEASE")
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def summary(self) -> dict:
        grouped = dict(self.db.execute(
            "SELECT state,COUNT(*) FROM fno_backfill_jobs GROUP BY state").fetchall())
        counts = {name: int(grouped.get(name, 0)) for name in (
            "PENDING", "ACTIVE", "RETRY", "DONE", "EMPTY", "FAILED", "BLOCKED")}
        total = sum(counts.values())
        completed = counts["DONE"] + counts["EMPTY"]
        row = self.db.execute("""SELECT COALESCE(SUM(rows_written),0),
            MAX(CASE WHEN state IN ('DONE','EMPTY') THEN updated_at END)
            FROM fno_backfill_jobs""").fetchone()
        return {
            "source": SOURCE,
            "state": "PREPARATION_ONLY",
            "retention_rights": "EXTERNAL_APPROVAL_REQUIRED",
            "total_batches": total,
            "completed_batches": completed,
            "completion_pct": round(100.0 * completed / total, 2) if total else 0.0,
            "rows_written": int(row[0]),
            "last_completed_utc_epoch": row[1],
            "counts": counts,
        }

    def status_json(self) -> str:
        return json.dumps(self.summary(), sort_keys=True, separators=(",", ":"))


class Pace:
    """Per-process max request pace. Multi-worker deployment requires shared limiter."""
    def __init__(self, minimum_gap_seconds: float = DEFAULT_MIN_SECONDS_PER_GET,
                 clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], None] = time.sleep):
        if not 0.5 <= minimum_gap_seconds <= 60:
            raise ValueError("INVALID_RATE_LIMIT")
        self.gap = minimum_gap_seconds
        self.clock = clock
        self.sleep = sleep
        self.next_allowed = 0.0

    def wait(self):
        now = self.clock()
        if now < self.next_allowed:
            self.sleep(self.next_allowed - now)
            now = self.clock()
        self.next_allowed = max(now, self.next_allowed) + self.gap


class BackfillWorker:
    def __init__(self, queue: MetadataQueue, provider: ExpiredProvider,
                 sink: IdempotentSink, rights: RetentionGrant | None,
                 pace: Pace | None = None,
                 today: Callable[[], date] = lambda: datetime.now(timezone.utc).date()):
        self.queue, self.provider, self.sink = queue, provider, sink
        self.rights = rights
        self.pace = pace or Pace()
        self.today = today

    def run_once(self) -> str:
        # Fail before claim and before any provider call. If permission expires
        # mid-job, also recheck immediately before market-data sink write.
        if self.rights is None:
            raise GateClosed("FNO_DATA_RETENTION_NOT_AUTHORIZED")
        # Only checked interval for a claimed job; require source + date first.
        if (self.rights.source != SOURCE or not self.rights.allow_storage
                or not self.rights.reference.startswith("verified-")
                or self.rights.valid_until < self.today()):
            raise GateClosed("FNO_DATA_RETENTION_NOT_AUTHORIZED")
        batch = self.queue.claim()
        if batch is None:
            return "IDLE"
        try:
            self.rights.require(batch.interval, self.today())
            self.pace.wait()
            try:
                reply = self.provider.fetch(batch.contract_key, batch.interval,
                                            batch.first, batch.last)
            except (OSError, TimeoutError):
                self.queue.finish(batch,state="RETRY",reason="TRANSPORT",
                                  retry_after=min(600, 2 ** min(batch.attempts, 9)))
                return "RETRY"
            if not isinstance(reply, ProviderReply):
                self.queue.finish(batch,state="FAILED",reason="INVALID_DATA")
                return "FAILED"
            status = reply.status
            if status in (401, 403):
                self.queue.finish(batch,state="BLOCKED",reason="AUTH_REJECTED")
                return "BLOCKED"
            if status == 429 or 500 <= status <= 599:
                self.queue.finish(batch,state="RETRY",
                    reason="RATE_LIMIT" if status == 429 else "PROVIDER_5XX",
                    retry_after=max(2 ** min(batch.attempts, 9),reply.retry_after_seconds))
                return "RETRY"
            if status != 200:
                self.queue.finish(batch,state="FAILED",reason="PROVIDER_4XX")
                return "FAILED"
            try:
                rows = validate_candles(reply.candles, batch)
            except ValueError:
                self.queue.finish(batch,state="FAILED",reason="INVALID_DATA")
                return "FAILED"
            if not rows:
                self.queue.finish(batch,state="EMPTY",reason="NONE")
                return "EMPTY"
            self.rights.require(batch.interval, self.today())
            try:
                written = self.sink.upsert(batch, rows, batch.dedupe_key)
            except ValueError:
                # Immutable source conflicts are custody failures, not
                # transient transport issues. Do not endlessly retry and
                # never allow a revised old candle to overwrite evidence.
                self.queue.finish(batch,state="BLOCKED",
                                  reason="SINK_PROVENANCE_REJECTED")
                return "BLOCKED"
            except (OSError, TimeoutError, RuntimeError):
                self.queue.finish(batch,state="RETRY",reason="SINK_ERROR",
                                  retry_after=min(600,2 ** min(batch.attempts,9)))
                return "RETRY"
            if written is not True:
                self.queue.finish(batch,state="RETRY",reason="SINK_ERROR",
                                  retry_after=min(600,2 ** min(batch.attempts,9)))
                return "RETRY"
            self.queue.finish(batch,state="DONE",rows=len(rows))
            return "DONE"
        except GateClosed:
            self.queue.finish(batch,state="BLOCKED",reason="AUTH_REJECTED")
            raise


# Deliberately NO CLI entrypoint or auto-executing scheduler. No built-in
# provider HTTP client or market-data sink: source and rights integrations
# require a separate approved review before any bulk retrieval/persistence.
