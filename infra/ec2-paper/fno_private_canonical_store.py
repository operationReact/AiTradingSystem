#!/usr/bin/env python3
"""Owner-private, short-lived canonical F&O research cache.

Copies only 3,929 independently cross-checked research rows from the existing
private 12-month pilot into a separate, local SQLite file. Fails closed after
original source receipts expire (7 days) or on 2026-11-29 00:00 IST.
No file/network export, broker order execution or production use.
"""
from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import tempfile
import time
from urllib.parse import quote
from zoneinfo import ZoneInfo

ROOT = Path("/opt/aitradingsystem-paper/fno-history")
TARGET = ROOT / "personal-canonical-daily.sqlite"
VERSION = "minttick.fno.personal.canonical.v1"
END_IST = datetime(2026, 11, 29, tzinfo=ZoneInfo("Asia/Kolkata")).timestamp()
LIFETIME = 7 * 86400
MARKER = "MINTTICK_FNO_PRIVATE_CANONICAL_V1:"
SOURCES = {"UPSTOX_PRIVATE", "NSE_PUBLIC_EOD"}
EXPECTED = {"total": 3929, "upstoxMonths": 3608, "nseDecember": 321,
            "excludedDecember": 1, "uniqueDailyKeys": 3929}
KEY_RE = re.compile(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}")
STAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2}T00:00:00\+05:30")

def _safe_dir(root):
    p = Path(root)
    if not p.is_absolute() or p.is_symlink() or not p.is_dir() or p.stat().st_mode & 0o077:
        raise ValueError("CANONICAL_DIRECTORY_NOT_PRIVATE")
    return p

def _safe_target(root):
    p = _safe_dir(root) / TARGET.name
    if p.is_symlink():
        raise ValueError("CANONICAL_SYMLINK_REFUSED")
    if p.exists() and (not p.is_file() or p.stat().st_mode & 0o077):
        raise ValueError("CANONICAL_FILE_NOT_PRIVATE")
    return p

def _source_expiry(raw_db, now):
    # The source's retention clock is receipt commit time, NOT the timestamp
    # of a copy into the canonical cache. A copy cannot refresh the rights.
    rows = raw_db.execute(
        "SELECT committed_at FROM fno_history_receipts").fetchall()
    if len(rows) != 276:
        raise ValueError("CANONICAL_SOURCE_RECEIPTS_CHANGED")
    stamps = []
    for (t,) in rows:
        if type(t) not in (int,float) or not 0 < t <= now + 300:
            raise ValueError("CANONICAL_SOURCE_RECEIPT_INVALID")
        stamps.append(float(t))
    expires = min(min(stamps) + LIFETIME, END_IST)
    if now >= expires:
        raise ValueError("CANONICAL_SOURCE_EXPIRED")
    return expires

def _decimal(v, *, positive=False):
    try:
        x = Decimal(str(v))
    except (TypeError,InvalidOperation):
        raise ValueError("CANONICAL_INVALID_VALUE") from None
    if not x.is_finite() or (x <= 0 if positive else x < 0):
        raise ValueError("CANONICAL_INVALID_VALUE")
    return x

def _iter_validated(canonical, metrics):
    if metrics != EXPECTED or len(canonical)!=EXPECTED["total"]:
        raise ValueError("CANONICAL_COHORT_NOT_VERIFIED")
    tally = Counter()
    rows = []
    for (key, day), (stamp, values, source) in canonical.items():
        if (not isinstance(key,str) or not KEY_RE.fullmatch(key)
                or source not in SOURCES or not isinstance(stamp,str)
                or not STAMP_RE.fullmatch(stamp) or stamp[:10]!=day.isoformat()):
            raise ValueError("CANONICAL_IDENTITY_INVALID")
        expiry=datetime.strptime(key.rsplit("|",1)[1], "%d-%m-%Y").date()
        if day>expiry:
            raise ValueError("CANONICAL_DAY_AFTER_EXPIRY")
        is_dec=(expiry.year,expiry.month)==(2025,12)
        if (is_dec and source!="NSE_PUBLIC_EOD") or (
                not is_dec and source!="UPSTOX_PRIVATE"):
            raise ValueError("CANONICAL_SOURCE_CLASSIFICATION_WRONG")
        if len(values)!=6:
            raise ValueError("CANONICAL_VALUE_SCHEMA")
        a,b,c,d,vol,oi=(_decimal(x,positive=i<4)
                        for i,x in enumerate(values))
        if c>min(a,d) or b<max(a,d):
            raise ValueError("CANONICAL_OHLC_INCONSISTENT")
        if vol!=vol.to_integral_value() or oi!=oi.to_integral_value():
            raise ValueError("CANONICAL_NON_INTEGRAL_VOLUME_OI")
        if vol>Decimal(10**12) or oi>Decimal(10**12):
            raise ValueError("CANONICAL_OUT_OF_RANGE")
        rows.append((key,day.isoformat(),stamp,*[str(x) for x in (a,b,c,d)],
                     int(vol),int(oi),source))
        tally[source]+=1
    if tally != {"UPSTOX_PRIVATE": 3608, "NSE_PUBLIC_EOD": 321}:
        raise ValueError("CANONICAL_SOURCE_COUNTS_INVALID")
    rows.sort(key=lambda x:(x[0],x[1]))
    return rows

def store(canonical, metrics, raw_db, *, root=ROOT, now=None):
    """Atomically create/replace a private research cache; return safe counters."""
    now = time.time() if now is None else float(now)
    dest = _safe_target(root)
    if now >= END_IST:
        raise ValueError("CANONICAL_OWNER_SCOPE_EXPIRED")
    expires = _source_expiry(raw_db, now)
    rows = _iter_validated(canonical, metrics)
    os.umask(0o077)
    fd, name = tempfile.mkstemp(prefix=".fno-canonical-",suffix=".sqlite",dir=root)
    tmp = Path(name)
    try:
        os.fchmod(fd,stat.S_IRUSR|stat.S_IWUSR)
        os.close(fd)
        fd = -1
        db=sqlite3.connect(str(tmp),timeout=20)
        try:
            db.execute("PRAGMA journal_mode=DELETE")
            db.execute("PRAGMA synchronous=FULL")
            db.execute("""CREATE TABLE meta(
                schema_version TEXT NOT NULL, generated_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL, total INTEGER NOT NULL,
                CHECK(total>0))""")
            db.execute("""CREATE TABLE daily(
                contract_key TEXT NOT NULL, session_day TEXT NOT NULL,
                candle_ts TEXT NOT NULL,
                open_price TEXT NOT NULL, high_price TEXT NOT NULL,
                low_price TEXT NOT NULL, close_price TEXT NOT NULL,
                volume INTEGER NOT NULL, open_interest INTEGER NOT NULL,
                source TEXT NOT NULL CHECK(source IN ('UPSTOX_PRIVATE','NSE_PUBLIC_EOD')),
                PRIMARY KEY(contract_key,session_day))""")
            db.executemany("INSERT INTO daily VALUES (?,?,?,?,?,?,?,?,?,?)", rows)
            db.execute("INSERT INTO meta VALUES (?,?,?,?)",
                       (VERSION,int(now),int(expires),len(rows)))
            db.commit()
            if (db.execute("PRAGMA quick_check").fetchone() != ("ok",)
                    or db.execute("SELECT COUNT(*) FROM daily").fetchone()[0]!=3929):
                raise ValueError("CANONICAL_PERSISTENCE_VALIDATION_FAILED")
        finally:
            db.close()
        if dest.is_symlink():
            raise ValueError("CANONICAL_SYMLINK_REFUSED")
        os.replace(tmp,dest)
        # Fail closed on expiry even if a scheduled purge is delayed:
        # read_canonical verifies expiry on every access.
        return {"status":"PRIVATE_CANONICAL_STORED","total":3929,
                "upstox":3608,"nse":321,"expiresEpoch":int(expires),
                "backtestingApproved":False,"customerApiEnabled":False}
    finally:
        if fd>=0:os.close(fd)
        if tmp.exists(): tmp.unlink()

def read_canonical(*, root=ROOT, now=None, key=None, limit=100, as_of=None):
    """Owner-only, provisional DAILY research read with a mandatory replay as-of.

    Midnight candle_ts is a SESSION LABEL, not proof the final day's OHLC was
    available then. A decision made on session day D must not see D's final
    OHLCV/OI, even if a broker encoded it as 00:00+05:30. Only strictly older
    session days can be read. This day-boundary rule is necessary but NOT
    sufficient for independently verified exchange publication/rights or E2E.
    The private dataset remains blocked for validated backtests and orders.
    """
    now=time.time() if now is None else float(now)
    if not isinstance(as_of,datetime) or as_of.tzinfo is None or as_of.utcoffset() is None:
        raise ValueError("CANONICAL_ASOF_REQUIRED")
    if as_of.timestamp()>now+5:
        raise ValueError("CANONICAL_ASOF_IN_FUTURE")
    # The exchange session is local to India, not the runner's UTC timezone.
    before_session_day=as_of.astimezone(ZoneInfo("Asia/Kolkata")).date().isoformat()
    p=_safe_target(root)
    if not p.is_file() or now>=END_IST:
        raise ValueError("CANONICAL_NOT_AVAILABLE")
    if not isinstance(key,str) or not KEY_RE.fullmatch(key) or not 1<=limit<=200:
        raise ValueError("CANONICAL_INVALID_QUERY")
    db=sqlite3.connect("file:"+quote(str(p),safe="/")+"?mode=ro",
                       uri=True,timeout=10)
    try:
        db.execute("PRAGMA query_only=ON")
        meta=db.execute("SELECT schema_version,expires_at,total FROM meta").fetchone()
        if (meta is None or meta[0]!=VERSION or now>=meta[1] or meta[2]!=3929):
            raise ValueError("CANONICAL_READ_EXPIRED_OR_INVALID")
        data=db.execute("""SELECT session_day,candle_ts,open_price,high_price,
                           low_price,close_price,volume,open_interest,source
                           FROM daily WHERE contract_key=? AND session_day<?
                           ORDER BY session_day DESC LIMIT ?""",
                        (key,before_session_day,limit)).fetchall()
        return data
    finally:
        db.close()

def purge(*, root=ROOT, now=None):
    now=time.time() if now is None else float(now)
    target=_safe_target(root)
    if not target.is_file():
        return "CANONICAL_ABSENT"
    db=sqlite3.connect("file:"+quote(str(target),safe="/")+"?mode=ro",
                       uri=True,timeout=10)
    try:
        db.execute("PRAGMA query_only=ON")
        row=db.execute("SELECT schema_version,expires_at FROM meta").fetchone()
    except sqlite3.Error:
        row=None
    finally:
        db.close()
    if row is None or row[0]!=VERSION or now>=row[1] or now>=END_IST:
        target.unlink()
        return "CANONICAL_EXPIRED_REMOVED"
    return "CANONICAL_NOT_YET_DUE"

if __name__=="__main__":
    import sys
    if len(sys.argv)!=2 or sys.argv[1]!="purge":
        raise SystemExit("CANONICAL_PURGE_ONLY")
    try:
        print(MARKER+json.dumps({"state":purge()},sort_keys=True))
    except (ValueError,OSError,sqlite3.Error):
        print("FNO_CANONICAL_PURGE:FAILED")
        raise SystemExit(2)
