#!/usr/bin/env python3
"""Unwired, rights-gated Upstox expired NSE_FO candle provider + SQLite sink.

Integration adapters for fno_history_backfill.py. No imports execute requests
or create databases. A real application must supply an independently reviewed
retention-verification callback (default DENY), approved token getter, and
operator-initiated runner. This module has no CLI, SSM, scheduler, AWS or orders.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import sqlite3
from typing import Callable
import urllib.error
import urllib.parse
import urllib.request

from fno_history_backfill import (
    Batch, GateClosed, ProviderReply, RetentionGrant, SOURCE,
    _contract_key, utcnow,
)

BASE = "https://api.upstox.com/v2/expired-instruments/historical-candle/"
USER_AGENT = "MintTick-Upstox-ReadOnly-History/1.0"
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
ALLOWLISTED_INTERVALS = frozenset(("1minute", "3minute", "5minute",
                                   "15minute", "30minute", "day"))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify_approval(grant: RetentionGrant | None,
                    verifier: Callable[[RetentionGrant], bool] | None,
                    interval: str, today) -> None:
    """Fail closed unless an external trusted checker attests this exact grant."""
    if grant is None or verifier is None:
        raise GateClosed("FNO_DATA_RETENTION_NOT_AUTHORIZED")
    grant.require(interval, today)
    try:
        allowed = verifier(grant)
    except Exception:
        raise GateClosed("FNO_DATA_RETENTION_NOT_AUTHORIZED") from None
    if allowed is not True:
        raise GateClosed("FNO_DATA_RETENTION_NOT_AUTHORIZED")


def build_url(contract_key: str, interval: str, first, last) -> str:
    if interval not in ALLOWLISTED_INTERVALS:
        raise ValueError("INVALID_UPSTOX_EXPIRED_INTERVAL")
    _contract_key(contract_key)
    from datetime import date, datetime
    if (type(first) is not date or type(last) is not date or first > last):
        raise ValueError("INVALID_UPSTOX_HISTORY_DATES")
    expiry = datetime.strptime(contract_key.rsplit("|", 1)[1], "%d-%m-%Y").date()
    if last > expiry:
        raise ValueError("INVALID_UPSTOX_HISTORY_DATES")
    return (BASE + urllib.parse.quote(contract_key, safe="")
            + "/" + interval + "/" + last.isoformat()
            + "/" + first.isoformat())


def _retry_after(value) -> int:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{1,4}", value):
        return 0
    return min(int(value), 1800)


class UpstoxExpiredCandles:
    """Calls one official GET only after independent grant verification.

    Does not log provider body, instrument, token, headers or response metadata.
    A returned 200 means parsed body, not persistence or license approval.
    """
    def __init__(self, token_getter: Callable[[], str],
                 grant: RetentionGrant | None = None,
                 verifier: Callable[[RetentionGrant], bool] | None = None,
                 today=None, opener=None):
        from datetime import datetime, timezone
        self.token_getter = token_getter
        self.grant, self.verifier = grant, verifier
        self.today = today or (lambda: datetime.now(timezone.utc).date())
        self.opener = opener or urllib.request.build_opener(NoRedirect())

    def fetch(self, contract_key: str, interval: str, first, last) -> ProviderReply:
        verify_approval(self.grant, self.verifier, interval, self.today())
        url = build_url(contract_key, interval, first, last)
        token = self.token_getter()
        if (not isinstance(token, str) or not 32 <= len(token) <= 8192
                or re.search(r"\s", token)):
            raise GateClosed("UPSTOX_HISTORY_TOKEN_UNAVAILABLE")
        request = urllib.request.Request(url, method="GET", headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/json",
            "User-Agent": USER_AGENT,
        })
        try:
            with self.opener.open(request, timeout=15) as response:
                if response.status != 200:
                    return ProviderReply(response.status)
                payload = response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            # No error body is ever logged, stored, or propagated.
            return ProviderReply(error.code, retry_after_seconds=_retry_after(
                error.headers.get("Retry-After", "") if error.headers else ""))
        except (urllib.error.URLError, TimeoutError, OSError):
            raise OSError("UPSTOX_EXPIRED_HISTORY_NETWORK_ERROR") from None
        if len(payload) > MAX_RESPONSE_BYTES:
            return ProviderReply(502)
        try:
            body = json.loads(payload)
        except (ValueError, UnicodeError, TypeError):
            return ProviderReply(502)
        if (not isinstance(body, dict) or body.get("status") != "success"
                or not isinstance(body.get("data"), dict)
                or not isinstance(body["data"].get("candles"), list)):
            return ProviderReply(502)
        return ProviderReply(200, body["data"]["candles"])


class SQLiteHistoricalCandleSink:
    """Append-only original-download candle custody, with explicit rights gate.

    A completed provider candle is NOT an as-of-trading-day observation.
    'first_captured_at' is the actual time our process first stored it, never
    backdated to the old candle timestamp. An external rights verifier remains
    mandatory; a mere receipt reference does not independently prove rights.
    This module never starts a scheduler or makes a broker order.
    """
    def __init__(self, path: str | Path,
                 grant: RetentionGrant | None = None,
                 verifier: Callable[[RetentionGrant], bool] | None = None,
                 today=None):
        from datetime import datetime, timezone
        self.grant, self.verifier = grant, verifier
        self.today = today or (lambda: datetime.now(timezone.utc).date())
        if grant is None or not grant.intervals:
            raise GateClosed("FNO_DATA_RETENTION_NOT_AUTHORIZED")
        verify_approval(grant, verifier, grant.intervals[0], self.today())
        self.conn = sqlite3.connect(str(path), timeout=15, isolation_level=None)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA busy_timeout=15000")
        self.conn.execute("""CREATE TABLE IF NOT EXISTS fno_historical_candles (
            source TEXT NOT NULL,
            contract_key TEXT NOT NULL,
            interval TEXT NOT NULL,
            candle_ts TEXT NOT NULL,
            open_price REAL NOT NULL,
            high_price REAL NOT NULL,
            low_price REAL NOT NULL,
            close_price REAL NOT NULL,
            volume INTEGER NOT NULL,
            open_interest INTEGER NOT NULL,
            batch_hash TEXT NOT NULL,
            PRIMARY KEY(source, contract_key, interval, candle_ts)
        )""")
        self.conn.execute("""CREATE TABLE IF NOT EXISTS fno_history_receipts (
            batch_hash TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            contract_key TEXT NOT NULL,
            interval TEXT NOT NULL,
            row_count INTEGER NOT NULL,
            committed_at REAL NOT NULL
        )""")
        # The original legacy receipts have no content digest and CANNOT be
        # promoted retroactively. Only new downloads can create this proof.
        self.conn.execute("""CREATE TABLE IF NOT EXISTS fno_immutable_candle_receipt (
            batch_hash TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            contract_key TEXT NOT NULL,
            interval TEXT NOT NULL,
            first_day TEXT NOT NULL,
            last_day TEXT NOT NULL,
            row_count INTEGER NOT NULL CHECK(row_count>0),
            normalized_sha256 TEXT NOT NULL,
            first_captured_at REAL NOT NULL,
            permission_reference TEXT NOT NULL
        )""")

    def close(self):
        self.conn.close()

    def upsert(self, batch: Batch, rows: list[tuple],
               idempotency_key: str) -> bool:
        import hashlib
        verify_approval(self.grant, self.verifier, batch.interval, self.today())
        if (idempotency_key != batch.dedupe_key or not rows
                or not re.fullmatch(r"[a-f0-9]{64}", idempotency_key)
                or not isinstance(rows, list) or len(rows) > 20000):
            raise ValueError("INVALID_FNO_SINK_BATCH")
        from fno_history_backfill import validate_candles
        verified = validate_candles([list(x) for x in rows], batch)
        if len(verified) != len(rows):
            raise ValueError("INVALID_FNO_SINK_ROWS")
        # Stable normalized OHLCV+OI order. This proves what was actually
        # downloaded and persisted, NOT a cryptographic provider signature.
        canonical = sorted(verified, key=lambda x:x[0])
        normalized = hashlib.sha256(json.dumps(
            canonical, separators=(",",":"), ensure_ascii=True,
            allow_nan=False).encode("utf-8")).hexdigest()
        self.conn.execute("BEGIN IMMEDIATE")
        try:
            previous = self.conn.execute("""
                SELECT normalized_sha256,row_count,first_captured_at
                FROM fno_immutable_candle_receipt WHERE batch_hash=?
                """,(idempotency_key,)).fetchone()
            legacy = self.conn.execute("""
                SELECT COUNT(*) FROM fno_history_receipts WHERE batch_hash=?
                """,(idempotency_key,)).fetchone()[0]
            if (previous is None and legacy):
                raise ValueError("UNATTESTED_LEGACY_BATCH_CANNOT_BE_REWRITTEN")
            if previous is not None and (
                    previous[0]!=normalized or previous[1]!=len(canonical)):
                raise ValueError("HISTORICAL_PROVIDER_REVISION_DETECTED")
            # Check *every* already persisted row, including overlapping
            # windows: a new batch may never rewrite earlier market history.
            for item in canonical:
                prior = self.conn.execute("""
                    SELECT open_price,high_price,low_price,close_price,
                           volume,open_interest,batch_hash
                    FROM fno_historical_candles
                    WHERE source=? AND contract_key=? AND interval=? AND candle_ts=?
                    """,(SOURCE,batch.contract_key,batch.interval,item[0])).fetchone()
                if prior is not None and (prior!=tuple(item[1:])+(idempotency_key,)):
                    raise ValueError("HISTORICAL_CANDLE_IMMUTABILITY_CONFLICT")
            self.conn.executemany("""INSERT INTO fno_historical_candles(
                source,contract_key,interval,candle_ts,open_price,
                high_price,low_price,close_price,volume,open_interest,batch_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(source,contract_key,interval,candle_ts) DO NOTHING""",
                [(SOURCE,batch.contract_key,batch.interval,*item,idempotency_key)
                 for item in canonical])
            if previous is None:
                captured=utcnow()
                self.conn.execute("""INSERT INTO fno_immutable_candle_receipt(
                    batch_hash,source,contract_key,interval,first_day,last_day,
                    row_count,normalized_sha256,first_captured_at,
                    permission_reference) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    (idempotency_key,SOURCE,batch.contract_key,batch.interval,
                     batch.first.isoformat(),batch.last.isoformat(),len(canonical),
                     normalized,captured,self.grant.reference))
                self.conn.execute("""INSERT INTO fno_history_receipts(
                    batch_hash,source,contract_key,interval,row_count,committed_at)
                    VALUES (?,?,?,?,?,?)""",
                    (idempotency_key,SOURCE,batch.contract_key,batch.interval,
                     len(canonical),captured))
            self.conn.execute("COMMIT")
            return True
        except BaseException:
            self.conn.execute("ROLLBACK")
            raise

    def count(self) -> int:
        return int(self.conn.execute(
            "SELECT COUNT(*) FROM fno_historical_candles").fetchone()[0])
