#!/usr/bin/env python3
"""Read-only audit of the owner-private expired-NIFTY F&O cache.

No provider calls, orders, credentials, instrument keys, candles, or raw prices
are sent outside the host. GitHub receives bounded aggregate counters only.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import sys
from urllib.parse import quote

SOURCE = "upstox-expired-nse-fo"
ROOT = Path("/opt/aitradingsystem-paper/fno-history")
JOBS = ROOT / "checkpoints.sqlite"
CANDLES = ROOT / "private-research-cache.sqlite"
MARKER = "MINTTICK_FNO_AUDIT_V1:"
MAX_ROWS = 1000000
JOB_STATES = ("PENDING", "ACTIVE", "RETRY", "DONE", "EMPTY", "FAILED", "BLOCKED")


def read_only(path):
    path = Path(path)
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ValueError("DATABASE_NOT_AVAILABLE")
    uri = "file:" + quote(str(path), safe="/") + "?mode=ro"
    db = sqlite3.connect(uri, uri=True, timeout=10)
    db.execute("PRAGMA query_only=ON")
    db.execute("PRAGMA busy_timeout=10000")
    return db


def validate_schema(db, table, expected):
    found = {row[1] for row in db.execute("PRAGMA table_info(" + table + ")")}
    if not expected.issubset(found):
        raise ValueError("AUDIT_SCHEMA_UNSUPPORTED")


def audit(journal, cache):
    job_columns = {"contract_key", "interval", "first_day", "last_day", "state",
                   "rows_written", "attempts", "reason"}
    candle_columns = {"source", "contract_key", "interval", "candle_ts", "open_price",
                      "high_price", "low_price", "close_price", "volume",
                      "open_interest", "batch_hash"}
    receipt_columns = {"batch_hash", "source", "contract_key", "interval",
                       "row_count", "committed_at"}
    validate_schema(journal, "fno_backfill_jobs", job_columns)
    validate_schema(cache, "fno_historical_candles", candle_columns)
    validate_schema(cache, "fno_history_receipts", receipt_columns)

    journal_ok = list(journal.execute("PRAGMA quick_check")) == [("ok",)]
    cache_ok = list(cache.execute("PRAGMA quick_check")) == [("ok",)]
    jobs = list(journal.execute("""SELECT contract_key,interval,first_day,last_day,
                                  state,rows_written,attempts,reason
                                  FROM fno_backfill_jobs"""))
    receipts = list(cache.execute("""SELECT batch_hash,source,contract_key,interval,
                                    row_count FROM fno_history_receipts"""))
    if len(jobs) > 20000 or len(receipts) > 20000:
        raise ValueError("AUDIT_BOUNDED_LIMIT")
    invalid = Counter()
    state_count = Counter()
    by_hash = {}
    month_jobs = Counter()
    month_contracts = defaultdict(set)
    seen_jobs = set()
    for key, interval, first, last, state, rows_written, attempts, reason in jobs:
        state_count[state] += 1
        try:
            expiry = datetime.strptime(key.rsplit("|", 1)[1], "%d-%m-%Y").date()
            first_date, last_date = date.fromisoformat(first), date.fromisoformat(last)
            assert (re.fullmatch(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}", key)
                    and interval == "day" and first_date <= last_date <= expiry
                    and state in JOB_STATES and isinstance(rows_written, int)
                    and rows_written >= 0 and isinstance(attempts, int) and attempts >= 0)
        except (ValueError, TypeError, AssertionError, IndexError):
            invalid["invalidJobMetadata"] += 1
            continue
        identity = (key, interval, first, last)
        if identity in seen_jobs:
            invalid["duplicateJobs"] += 1
        seen_jobs.add(identity)
        digest = hashlib.sha256((f"{SOURCE}|{key}|{interval}|{first}|{last}").encode()).hexdigest()
        if digest in by_hash:
            invalid["duplicateJobHashes"] += 1
        by_hash[digest] = (key, interval, first_date, last_date, state, rows_written)
        month = expiry.strftime("%Y-%m")
        month_jobs[month] += 1
        month_contracts[month].add(key)
        if first_date != expiry - timedelta(days=20) or last_date != expiry:
            invalid["unexpectedPilotWindow"] += 1
        if state == "DONE" and rows_written == 0:
            invalid["doneWithZeroWritten"] += 1
        if state != "DONE" and rows_written > 0:
            invalid["nonDoneWithWrittenRows"] += 1

    receipt_map = {}
    for digest, source, key, interval, row_count in receipts:
        if digest in receipt_map:
            invalid["duplicateReceipts"] += 1
        receipt_map[digest] = (source, key, interval, row_count)
        job = by_hash.get(digest)
        if not job:
            invalid["orphanReceipts"] += 1
        elif (source != SOURCE or key != job[0] or interval != job[1]
              or row_count != job[5] or job[4] != "DONE"):
            invalid["receiptCheckpointMismatch"] += 1

    for digest, job in by_hash.items():
        if job[4] == "DONE" and digest not in receipt_map:
            invalid["doneMissingReceipt"] += 1

    rows_per_hash = Counter()
    candle_months = Counter()
    duplicates = set()
    seen_daily_dates = set()
    seen_daily_payload = {}
    monthly_daily_duplicates = Counter()
    identical_daily_repeats = Counter()
    divergent_daily_repeats = Counter()
    same_instant_repeats = Counter()
    daily_timestamp_patterns = defaultdict(Counter)
    monthly_contract_candles = Counter()
    count = 0
    zero_volume = zero_oi = 0
    for row in cache.execute("""SELECT source,contract_key,interval,candle_ts,
                               open_price,high_price,low_price,close_price,
                               volume,open_interest,batch_hash
                               FROM fno_historical_candles"""):
        count += 1
        if count > MAX_ROWS:
            raise ValueError("AUDIT_BOUNDED_LIMIT")
        (source, key, interval, stamp, op, high, low, close,
         volume, oi, digest) = row
        identity = (source, key, interval, stamp)
        if identity in duplicates:
            invalid["duplicateCandles"] += 1
        duplicates.add(identity)
        rows_per_hash[digest] += 1
        job = by_hash.get(digest)
        if not job:
            invalid["orphanCandleRows"] += 1
        elif source != SOURCE or key != job[0] or interval != job[1] or job[4] != "DONE":
            invalid["candleCheckpointMismatch"] += 1
        try:
            dt = datetime.fromisoformat(stamp)
            if dt.tzinfo is None or dt.utcoffset() is None:
                raise ValueError("TZ")
            if job and not job[2] <= dt.date() <= job[3]:
                invalid["outsideBatchWindow"] += 1
            if job:
                expiry = datetime.strptime(key.rsplit("|", 1)[1], "%d-%m-%Y").date()
                month = expiry.strftime("%Y-%m")
                candle_months[month] += 1
                monthly_contract_candles[(month, key)] += 1
                daily_key = (source, key, interval, dt.date())
                # Compare only within the same contract and exchange calendar day.
                # Never emit the instrument key, raw price, or candle timestamp.
                candle_payload = (op, high, low, close, volume, oi)
                if daily_key in seen_daily_dates:
                    invalid["duplicateDailyDates"] += 1
                    monthly_daily_duplicates[month] += 1
                    previous = seen_daily_payload[daily_key]
                    if previous[0] == candle_payload:
                        identical_daily_repeats[month] += 1
                    else:
                        divergent_daily_repeats[month] += 1
                    if previous[1].astimezone(timezone.utc) == dt.astimezone(timezone.utc):
                        same_instant_repeats[month] += 1
                else:
                    seen_daily_payload[daily_key] = (candle_payload, dt)
                seen_daily_dates.add(daily_key)
                # Time-of-day and UTC offset only; never retain original date.
                pattern = dt.strftime("%H:%M%z")
                daily_timestamp_patterns[month][pattern] += 1
        except (ValueError, TypeError, AttributeError):
            invalid["invalidCandleTimestamp"] += 1
        if any(type(x) not in (int, float) or not math.isfinite(x) or x <= 0
               for x in (op, high, low, close)):
            invalid["invalidOrMissingOHLC"] += 1
        elif low > min(op, close) or high < max(op, close) or high < low:
            invalid["inconsistentOHLC"] += 1
        if type(volume) is not int or volume < 0:
            invalid["invalidOrMissingVolume"] += 1
        elif volume == 0:
            zero_volume += 1
        if type(oi) is not int or oi < 0:
            invalid["invalidOrMissingOI"] += 1
        elif oi == 0:
            zero_oi += 1

    for digest, (source, key, interval, row_count) in receipt_map.items():
        if rows_per_hash[digest] != row_count:
            invalid["receiptVsCachedRowsMismatch"] += 1

    total_jobs = len(jobs)
    completed = state_count["DONE"] + state_count["EMPTY"]
    critical = sum(invalid.values())
    complete = total_jobs == 276 and completed == total_jobs
    coverage = [
        {"month": month, "jobs": month_jobs[month],
         "contracts": len(month_contracts[month]), "candles": candle_months[month],
         "duplicateDailyDates": monthly_daily_duplicates[month],
         "duplicateIdenticalPayload": identical_daily_repeats[month],
         "duplicateConflictingPayload": divergent_daily_repeats[month],
         "duplicateSameInstant": same_instant_repeats[month],
         "timestampPatterns": [
             {"time": clock, "count": n}
             for clock, n in sorted(daily_timestamp_patterns[month].items())
         ][:12] if monthly_daily_duplicates[month] else [],
         "contractsOver21Candles": sum(1 for key in month_contracts[month]
                                       if monthly_contract_candles[(month,key)] > 21),
         "maxCandlesPerContract": max((monthly_contract_candles[(month,key)]
                                       for key in month_contracts[month]), default=0)}
        for month in sorted(month_jobs)
    ]
    # Structural quarantine: never claim the raw 12-month dataset is clean.
    # The December 2025 cohort is isolated because Upstox currently returns
    # both midnight and 09:15 bars for the same daily contract/date. This
    # produces a potential 11-month research candidate, NOT a trading license
    # or full source/calendar/price reconciliation.
    expected_months = [
        "2025-11", "2025-12", "2026-01", "2026-02", "2026-03",
        "2026-04", "2026-05", "2026-06", "2026-07", "2026-08",
        "2026-09", "2026-10",
    ]
    quarantine_month = "2025-12"
    month_lookup = {entry["month"]: entry for entry in coverage}
    cohort_isolated = (
        journal_ok and cache_ok and total_jobs == 276
        and completed == 276 and len(receipts) == 276
        and sorted(month_lookup) == expected_months
        and dict(invalid) == {"duplicateDailyDates": 321}
        and len(jobs) == 276
        and all(entry["jobs"] == entry["contracts"] == 23 for entry in coverage)
        and month_lookup[quarantine_month]["duplicateDailyDates"] == 321
        and all(entry["duplicateDailyDates"] == 0
                and entry["contractsOver21Candles"] == 0
                for entry in coverage if entry["month"] != quarantine_month)
    )
    eligible = [entry for entry in coverage if entry["month"] != quarantine_month]
    quarantine = {
        "status": "ISOLATED" if cohort_isolated else "BLOCKED",
        "excludedExpiryMonth": quarantine_month,
        "eligibleExpiryMonths": len(eligible) if cohort_isolated else 0,
        "eligibleContracts": sum(x["contracts"] for x in eligible)
                              if cohort_isolated else 0,
        "eligibleCandles": sum(x["candles"] for x in eligible)
                            if cohort_isolated else 0,
        "excludedContracts": month_lookup.get(quarantine_month,{}).get("contracts",0),
        "excludedCandles": month_lookup.get(quarantine_month,{}).get("candles",0),
        "boundary": "STRUCTURAL_RESEARCH_ONLY_NO_BACKTEST_APPROVAL",
    }
    # Cannot certify CE vs PE vs FUT or trading-day completeness from the
    # persisted contract keys alone. Metadata was not stored in the sink.
    return {
        "audit": "OWNER_PRIVATE_NIFTY_12M_DAILY_V1",
        "result": "PASS" if journal_ok and cache_ok and critical == 0 and
                           complete and len(month_jobs) == 12 and count > 0 else "REVIEW",
        "sqlite": {"journalQuickCheck": journal_ok, "cacheQuickCheck": cache_ok},
        "jobs": {"total": total_jobs, "completed": completed,
                 "states": {k: state_count[k] for k in JOB_STATES}},
        "coverage": {"expiryMonths": len(month_jobs),
                     "uniqueContracts": len({(v[0], v[1]) for v in by_hash.values()}),
                     "months": coverage},
        "candles": {"stored": count, "zeroVolumeValid": zero_volume,
                    "zeroOIValid": zero_oi, "receipts": len(receipts)},
        "findings": {k: invalid[k] for k in sorted(invalid)},
        "findingCount": critical,
        "quarantine": quarantine,
        "limits": ["contractTypeNotPersisted", "holidayCalendarNotAvailable",
                   "sourceCatalogNotReconciled", "privateCacheExpiresAfterSevenDays"]
    }


def main():
    try:
        journal = read_only(JOBS)
        cache = read_only(CANDLES)
        try:
            journal.execute("BEGIN")
            cache.execute("BEGIN")
            result = audit(journal, cache)
        finally:
            cache.close()
            journal.close()
        payload = json.dumps(result, sort_keys=True, separators=(",", ":"))
        if len(payload) > 5000:
            raise ValueError("AUDIT_REPORT_TOO_LARGE")
        print(MARKER + payload)
    except (ValueError, OSError, sqlite3.Error):
        print("FNO_AUDIT_ERROR:READ_ONLY_AUDIT_UNAVAILABLE")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
