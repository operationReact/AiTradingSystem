#!/usr/bin/env python3
"""Owner-scoped PRIVATE research collector for Upstox expired NSE_FO.

Not a general exchange data licence or a redistribution service. This runner
may operate ONLY against the exact private owner-approved request/permit, in
the existing PAPER EC2, for 7 days. Cached candles are kept private, and
expunged when the owner scope expires. No trading endpoints are used.

The existing Upstox adapter and metadata checkpoint engine remain unchanged.
"""
from __future__ import annotations
import base64
from datetime import date, datetime, timedelta, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
from zoneinfo import ZoneInfo

from fno_history_backfill import (
    MetadataQueue, RetentionGrant, SOURCE, Pace, BackfillWorker, GateClosed
)
from upstox_fno_backfill_adapters import (
    SQLiteHistoricalCandleSink, UpstoxExpiredCandles, NoRedirect
)

ROOT = Path("/opt/aitradingsystem-paper")
BASE_DIR = ROOT / "fno-history"
JOURNAL = BASE_DIR / "checkpoints.sqlite"
DATA_DB = BASE_DIR / "private-research-cache.sqlite"
PERMIT = BASE_DIR / "owner-private-scope.json"
STATUS = BASE_DIR / "private-progress.json"
TOKEN_PARAM = "/minttick/upstox/analytics-token"
APPROVAL_ID = "OWNER_APPROVED_PRIVATE_FNO_RESEARCH_2026_11_21"
CUTOFF = date(2026, 11, 28)
UNDERLYING = "NSE_INDEX|Nifty 50"
BASE = "https://api.upstox.com/v2/expired-instruments"
AGENT = "MintTick-Upstox-ReadOnly-Diagnostics/1.0"
MAX_MONTHS = 12
MAX_OPTION_PAIRS = 11
MAX_REQUESTS_PER_RUN = 600
MIN_REQUEST_GAP = 2.5
DAILY_CACHE_TTL_SECONDS = 7 * 86400
SAFE_FAILURES = frozenset({
    "FNO_DATA_RETENTION_NOT_AUTHORIZED", "FNO_DISCOVERY_AUTH_REJECTED",
    "FNO_DISCOVERY_INVALID_JSON", "FNO_DISCOVERY_INVALID_RESPONSE",
    "FNO_DISCOVERY_PROVIDER_REJECTED", "FNO_DISCOVERY_RATE_LIMITED",
    "FNO_DISCOVERY_TOO_LARGE", "FNO_DISCOVERY_TRANSPORT",
    "FNO_PRIVATE_DIR_NOT_SECURE", "FNO_PRIVATE_PERMIT_SYMLINK",
    "FNO_REQUEST_BUDGET_EXHAUSTED", "FNO_STATUS_INVALID_PATH",
    "FNO_STATUS_TOO_LARGE", "INVALID_FNO_DISCOVERY_ROUTE",
    "LOST_FNO_JOB_LEASE", "OWNER_PRIVATE_FNO_SCOPE_MISSING_OR_EXPIRED",
    "UPSTOX_HISTORY_TOKEN_UNAVAILABLE", "UPSTOX_TOKEN_UNAVAILABLE",
    "WRONG_EC2_RUNTIME",
    "FNO_EXPIRIES_INVALID", "FNO_CONTRACT_INVALID",
    "FNO_DISCOVERY_CONTRACTS_INVALID", "FNO_STRIKE_INVALID",
    "INVALID_UPSTOX_EXPIRED_INTERVAL", "INVALID_UPSTOX_HISTORY_DATES",
    "UPSTOX_EXPIRED_HISTORY_NETWORK_ERROR", "INVALID_FNO_SINK_BATCH",
    "INVALID_FNO_SINK_ROWS", "INVALID_HTTP_OBSERVATION",
    "SOURCE_CAPTURE_TIME_INVALID", "ORIGINAL_HTTP_BODY_INVALID",
    "HTTP_CANDLES_DO_NOT_MATCH_SOURCE",
    "UNATTESTED_LEGACY_BATCH_CANNOT_BE_REWRITTEN",
    "HISTORICAL_PROVIDER_REVISION_DETECTED", "CANNOT_RETROFIT_OLD_HTTP_CAPTURE",
    "ORIGINAL_PROVIDER_RESPONSE_REVISION",
    "ORIGINAL_HTTP_CAPTURE_CANNOT_BE_DOWNGRADED",
    "HISTORICAL_CANDLE_IMMUTABILITY_CONFLICT", "INVALID_DATE",
    "INVALID_EXPIRED_INSTRUMENT_KEY", "INVALID_FNO_WINDOW",
    "INVALID_FNO_CANDLES", "INVALID_FNO_TIMESTAMP", "INVALID_FNO_DAILY_CANDLE",
    "INVALID_FNO_OHLCV_OI", "CONTRACT_NOT_EXPIRED_OR_WINDOW_EXCEEDS_EXPIRY",
    "INVALID_FNO_STATE", "INVALID_FNO_RECEIPT", "INVALID_RATE_LIMIT",
    "ORIGINAL_HTTP_RECEIPT_SINK_REQUIRED",
})

def safe_failure_reason(exc):
    """Expose a fixed reason or exception class, never messages or paths."""
    reason = str(exc)
    if reason in SAFE_FAILURES:
        return reason
    if isinstance(exc, sqlite3.Error):
        return "PRIVATE_DB_ERROR"
    if isinstance(exc, OSError):
        return "PRIVATE_STORAGE_ERROR"
    exception_classes = {
        GateClosed: "WORKER_EXCEPTION_GATE_CLOSED",
        RuntimeError: "WORKER_EXCEPTION_RUNTIME_ERROR",
        ValueError: "WORKER_EXCEPTION_VALIDATION_ERROR",
        KeyError: "WORKER_EXCEPTION_KEY_ERROR",
        TypeError: "WORKER_EXCEPTION_TYPE_ERROR",
        AttributeError: "WORKER_EXCEPTION_ATTRIBUTE_ERROR",
    }
    failure = exception_classes.get(type(exc))
    return failure or "UNCLASSIFIED_SAFE_FAILURE"

def today():
    return datetime.now(ZoneInfo("Asia/Kolkata")).date()

def private_scope():
    return {
        "approval": APPROVAL_ID,
        "user_scope": "single-owner-internal-research-and-backtesting-only",
        "data_source": SOURCE,
        "underlying": UNDERLYING,
        "intervals": ["day"],
        "expires_on": CUTOFF.isoformat(),
        "max_months": MAX_MONTHS,
        "max_option_pairs_per_month": MAX_OPTION_PAIRS,
        "max_provider_gets": MAX_REQUESTS_PER_RUN,
        "no_redistribution": True,
        "no_live_orders": True,
        "provider_licence_verified": False,
    }

def verify_scope(grant):
    if grant.reference != "verified-owner-private-research-20261121":
        return False
    if not PERMIT.is_file() or PERMIT.is_symlink() or PERMIT.stat().st_size > 1024:
        return False
    if today() > CUTOFF:
        return False
    try:
        return json.loads(PERMIT.read_text()) == private_scope()
    except (OSError, ValueError):
        return False

def grant():
    g = RetentionGrant("verified-owner-private-research-20261121",
        SOURCE, True, ("day",), CUTOFF)
    if not verify_scope(g):
        raise GateClosed("OWNER_PRIVATE_FNO_SCOPE_MISSING_OR_EXPIRED")
    return g

def ssm_token():
    try:
        p = subprocess.run([
            "aws", "ssm", "get-parameter", "--name", TOKEN_PARAM,
            "--with-decryption", "--region", "ap-south-1",
            "--output", "json", "--no-cli-pager"
        ], capture_output=True, text=True, timeout=20, check=False)
        if p.returncode:
            raise GateClosed("UPSTOX_TOKEN_UNAVAILABLE")
        value = json.loads(p.stdout)["Parameter"]["Value"]
        if not isinstance(value,str) or not 32<=len(value)<=8192 or re.search(r"\s",value):
            raise GateClosed("UPSTOX_TOKEN_UNAVAILABLE")
        return value
    except (OSError,ValueError,TypeError,KeyError,subprocess.TimeoutExpired):
        raise GateClosed("UPSTOX_TOKEN_UNAVAILABLE") from None

class Discovery:
    def __init__(self, token, clock=time.monotonic, sleep=time.sleep):
        self.token=token
        self.calls=0
        self.next_at=0.0
        self.clock, self.sleep=clock,sleep
        self.opener=urllib.request.build_opener(NoRedirect())

    def get(self, route):
        if self.calls >= MAX_REQUESTS_PER_RUN:
            raise RuntimeError("FNO_REQUEST_BUDGET_EXHAUSTED")
        url=BASE+route
        parts=urllib.parse.urlsplit(url)
        if parts.scheme!="https" or parts.netloc!="api.upstox.com" or (
                not parts.path.startswith("/v2/expired-instruments/")):
            raise RuntimeError("INVALID_FNO_DISCOVERY_ROUTE")
        now=self.clock()
        if now<self.next_at:
            self.sleep(self.next_at-now)
        self.next_at=self.clock()+MIN_REQUEST_GAP
        self.calls+=1
        req=urllib.request.Request(url,method="GET",headers={
            "Authorization":"Bearer "+self.token,
            "Accept":"application/json",
            "User-Agent":AGENT,
        })
        try:
            with self.opener.open(req,timeout=15) as response:
                if response.status!=200:
                    raise RuntimeError("FNO_DISCOVERY_PROVIDER_REJECTED")
                raw=response.read(4*1024*1024+1)
        except urllib.error.HTTPError as e:
            if e.code in (401,403):
                raise RuntimeError("FNO_DISCOVERY_AUTH_REJECTED") from None
            if e.code==429:
                raise RuntimeError("FNO_DISCOVERY_RATE_LIMITED") from None
            raise RuntimeError("FNO_DISCOVERY_PROVIDER_REJECTED") from None
        except (urllib.error.URLError,OSError,TimeoutError):
            raise RuntimeError("FNO_DISCOVERY_TRANSPORT") from None
        if len(raw)>4*1024*1024:
            raise RuntimeError("FNO_DISCOVERY_TOO_LARGE")
        try:
            obj=json.loads(raw)
        except (ValueError,TypeError):
            raise RuntimeError("FNO_DISCOVERY_INVALID_JSON") from None
        if not isinstance(obj,dict) or obj.get("status")!="success":
            raise RuntimeError("FNO_DISCOVERY_INVALID_RESPONSE")
        return obj.get("data")

def monthly_expiries(rows, current):
    if not isinstance(rows,list) or len(rows)>250:
        raise ValueError("FNO_EXPIRIES_INVALID")
    monthly={}
    before=current.replace(day=1)
    for value in rows:
        d=date.fromisoformat(value)
        if d>=before: continue
        k=(d.year,d.month)
        if k not in monthly or monthly[k]<d:
            monthly[k]=d
    return sorted(monthly.values(),reverse=True)[:MAX_MONTHS]

def _key(row,expiry,kind):
    if not isinstance(row,dict):
        raise ValueError("FNO_CONTRACT_INVALID")
    if (row.get("segment")!="NSE_FO" or
            row.get("exchange")!="NSE" or
            row.get("underlying_key")!=UNDERLYING or
            row.get("instrument_type")!=kind or
            row.get("expiry")!=expiry.isoformat()):
        return None
    key=row.get("instrument_key")
    if not isinstance(key,str) or not re.fullmatch(
        r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}",key):
        raise ValueError("FNO_CONTRACT_INVALID")
    if key.split("|")[-1]!=expiry.strftime("%d-%m-%Y"):
        raise ValueError("FNO_CONTRACT_INVALID")
    return key

def contracts(futures,options,expiry):
    if not isinstance(futures,list) or not isinstance(options,list):
        raise ValueError("FNO_DISCOVERY_CONTRACTS_INVALID")
    if len(futures)>200 or len(options)>15000:
        raise ValueError("FNO_DISCOVERY_CONTRACTS_INVALID")
    fs=sorted({k for row in futures if (k:=_key(row,expiry,"FUT"))})
    by_strike={}
    for row in options:
        for side in ("CE","PE"):
            key=_key(row,expiry,side)
            if not key: continue
            strike=row.get("strike_price")
            if type(strike) not in (int,float) or not 0<strike<1e9:
                raise ValueError("FNO_STRIKE_INVALID")
            by_strike[(strike,side)]=key
    strikes=sorted({s for s,side in by_strike if
                    (s,"CE") in by_strike and (s,"PE") in by_strike})
    # Median of available strikes, not a claim of actual historical ATM.
    center=len(strikes)//2
    half=MAX_OPTION_PAIRS//2
    chosen=strikes[max(0,center-half):
                   min(len(strikes),center+half+1)]
    # 1 future + max 11 matched CE/PE pairs per monthly expiry.
    return fs[:1]+[by_strike[(s,side)] for s in chosen for side in ("CE","PE")]

def source_plan(queue, discovery, approval, month_limit=MAX_MONTHS):
    grant().require("day",today())
    key=urllib.parse.quote(UNDERLYING,safe="")
    dates=monthly_expiries(discovery.get("/expiries?instrument_key="+key),today())
    added=0
    for expiry in dates[:month_limit]:
        qry="?instrument_key="+key+"&expiry_date="+expiry.isoformat()
        fs=discovery.get("/future/contract"+qry)
        opts=discovery.get("/option/contract"+qry)
        for contract_key in contracts(fs,opts,expiry):
            start=expiry-timedelta(days=20)
            added+=queue.plan(contract_key,"day",start,expiry,
                              rights=approval,today=today())
    return added

def persist_summary(queue,sink,status="RUNNING"):
    summary=queue.summary()
    snapshot={
        "kind":"minttick-private-fno-backfill-v1",
        "status":status,
        "scope":"single-owner-private-research",
        "licenseConfirmed":False,
        "generatedAt":datetime.now(timezone.utc).isoformat(),
        "totalJobs":summary["total_batches"],
        "completedJobs":summary["completed_batches"],
        "percent":summary["completion_pct"] if summary["total_batches"] else None,
        "candleRows":sink.count(),
        "counts":summary["counts"],
        "expiresOn":CUTOFF.isoformat(),
    }
    encoded=json.dumps(snapshot,sort_keys=True).encode()
    if len(encoded)>3000:raise RuntimeError("FNO_STATUS_TOO_LARGE")
    path=STATUS.with_suffix(".tmp")
    if path.is_symlink() or STATUS.is_symlink():
        raise RuntimeError("FNO_STATUS_INVALID_PATH")
    path.write_bytes(encoded)
    os.chmod(path,0o600)
    os.replace(path,STATUS)
    return snapshot

def cleanup_expired_cache(sink):
    # Cache retention is bounded by time since *download*, not candle date.
    oldest=time.time()-DAILY_CACHE_TTL_SECONDS
    sink.conn.execute("BEGIN IMMEDIATE")
    try:
        old=[r[0] for r in sink.conn.execute(
            "SELECT batch_hash FROM fno_history_receipts WHERE committed_at<?",
            (oldest,))]
        has_immutable=bool(sink.conn.execute("""
            SELECT 1 FROM sqlite_master
            WHERE type='table' AND name='fno_immutable_candle_receipt'
            """).fetchone())
        for hashvalue in old:
            sink.conn.execute("DELETE FROM fno_historical_candles WHERE batch_hash=?",(hashvalue,))
            sink.conn.execute("DELETE FROM fno_history_receipts WHERE batch_hash=?",(hashvalue,))
            if has_immutable:
                sink.conn.execute("DELETE FROM fno_immutable_candle_receipt WHERE batch_hash=?",(hashvalue,))
        sink.conn.execute("COMMIT")
    except BaseException:
        sink.conn.execute("ROLLBACK")
        raise

def run(start_limit=0, max_requests=MAX_REQUESTS_PER_RUN, wait_for_more=False):
    os.umask(0o077)
    if not BASE_DIR.is_dir() or BASE_DIR.is_symlink() or BASE_DIR.stat().st_mode&0o077:
        raise GateClosed("FNO_PRIVATE_DIR_NOT_SECURE")
    approval=grant()
    token=ssm_token()
    queue=MetadataQueue(JOURNAL)
    sink=SQLiteHistoricalCandleSink(DATA_DB,grant=approval,
        verifier=verify_scope,today=today)
    requests=0
    try:
        discovery=Discovery(token)
        if start_limit:
            if not queue.summary()["total_batches"]:
                source_plan(queue,discovery,approval,month_limit=3)
        else:
            # Extend the smoke-plan idempotently across all selected months.
            source_plan(queue,discovery,approval,month_limit=MAX_MONTHS)
        requests=discovery.calls
        provider=UpstoxExpiredCandles(lambda:token,grant=approval,
            verifier=verify_scope,today=today)
        worker=BackfillWorker(queue,provider,sink,approval,
                              pace=Pace(MIN_REQUEST_GAP),today=today)
        completed=0
        consecutive_errors=0
        while requests<max_requests:
            grant()
            result=worker.run_once()
            if result=="IDLE":
                # Extend from initial 3 months to a full 12-month NIFTY pilot.
                if start_limit: break
                statuses=queue.summary()["counts"]
                if (not wait_for_more and
                        statuses["PENDING"]==0 and statuses["ACTIVE"]==0
                        and statuses["RETRY"]==0):
                    break
                time.sleep(60)
                continue
            requests+=1
            if result=="BLOCKED":
                break
            if result in ("RETRY","FAILED"):
                consecutive_errors+=1
                if consecutive_errors>=8: break
            else:
                consecutive_errors=0
            completed+=1
            if completed%5==0 or completed==1:
                cleanup_expired_cache(sink)
                persist_summary(queue,sink)
            if start_limit and completed>=start_limit:
                break
        cleanup_expired_cache(sink)
        data=persist_summary(queue,sink,"PAUSED" if start_limit else "RUNNING")
        return {
            "completed":data["completedJobs"],
            "total":data["totalJobs"],
            "rows":data["candleRows"],
            "requests":requests,
            "outcome":"FIRST_BATCH_COMPLETE" if start_limit and data["candleRows"]>0
                       else "BULK_SESSION_STARTED" if not start_limit
                       else "NO_VALID_ROWS",
        }
    finally:
        sink.close()
        queue.close()

def init_private():
    os.umask(0o077)
    if not ROOT.is_dir() or ROOT.is_symlink():
        raise GateClosed("WRONG_EC2_RUNTIME")
    BASE_DIR.mkdir(mode=0o700,exist_ok=True)
    if BASE_DIR.is_symlink() or BASE_DIR.stat().st_mode&0o077:
        raise GateClosed("FNO_PRIVATE_DIR_NOT_SECURE")
    if PERMIT.is_symlink():
        raise GateClosed("FNO_PRIVATE_PERMIT_SYMLINK")
    PERMIT.write_text(json.dumps(private_scope(),sort_keys=True))
    os.chmod(PERMIT,0o600)

def expire_private_cache():
    """Daily cleanup, including after owner approval expires; no token required."""
    if BASE_DIR.is_symlink() or not BASE_DIR.is_dir():
        return "NO_PRIVATE_CACHE"
    if not DATA_DB.exists() or DATA_DB.is_symlink():
        return "NO_PRIVATE_CACHE"
    os.umask(0o077)
    db=sqlite3.connect(str(DATA_DB),timeout=15,isolation_level=None)
    try:
        if today()>CUTOFF:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM fno_historical_candles")
            db.execute("DELETE FROM fno_history_receipts")
            # Legacy cache databases may predate the immutable receipt schema.
            if db.execute("""
                SELECT 1 FROM sqlite_master
                WHERE type='table' AND name='fno_immutable_candle_receipt'
                """).fetchone():
                db.execute("DELETE FROM fno_immutable_candle_receipt")
            db.execute("COMMIT")
            return "OWNER_SCOPE_EXPIRED_CACHE_CLEARED"
        old=time.time()-DAILY_CACHE_TTL_SECONDS
        db.execute("BEGIN IMMEDIATE")
        expired=[x[0] for x in db.execute(
            "SELECT batch_hash FROM fno_history_receipts WHERE committed_at<?",(old,))]
        has_immutable=bool(db.execute("""
            SELECT 1 FROM sqlite_master
            WHERE type='table' AND name='fno_immutable_candle_receipt'
            """).fetchone())
        for h in expired:
            db.execute("DELETE FROM fno_historical_candles WHERE batch_hash=?",(h,))
            db.execute("DELETE FROM fno_history_receipts WHERE batch_hash=?",(h,))
            if has_immutable:
                db.execute("DELETE FROM fno_immutable_candle_receipt WHERE batch_hash=?",(h,))
        db.execute("COMMIT")
        return "PRIVATE_CACHE_TTL_ENFORCED"
    finally:
        db.close()

def summary():
    if not JOURNAL.is_file() or not DATA_DB.is_file():
        return "NOT_INITIALIZED"
    q=MetadataQueue(JOURNAL)
    db=sqlite3.connect(str(DATA_DB))
    try:
        s=q.summary()
        count=db.execute("SELECT COUNT(*) FROM fno_historical_candles").fetchone()[0]
        return f"jobs={s['total_batches']};complete={s['completed_batches']};rows={int(count)}"
    finally:
        q.close()
        db.close()

if __name__=="__main__":
    mode=sys.argv[1] if len(sys.argv)==2 else ""
    try:
        if mode=="init":
            init_private()
            print("FNO_OWNER_SCOPE_CREATED")
        elif mode=="smoke":
            value=run(start_limit=3,max_requests=12)
            print("FNO_BULK_SMOKE:"+value["outcome"]+
                  f";complete={value['completed']};total={value['total']};rows={value['rows']};calls={value['requests']}")
        elif mode=="run":
            if today()>CUTOFF:
                print("FNO_BULK_WORKER:"+expire_private_cache())
            else:
                val=run(max_requests=MAX_REQUESTS_PER_RUN)
                print("FNO_BULK_WORKER:"+val["outcome"]+
                    f";complete={val['completed']};total={val['total']};rows={val['rows']}")
        elif mode=="purge":
            print("FNO_BULK_PURGE:"+expire_private_cache())
        elif mode=="status":
            print("FNO_BULK_STATUS:"+summary())
        else:
            raise SystemExit("INVALID_FNO_COMMAND")
    except (GateClosed,RuntimeError,ValueError,OSError,sqlite3.Error,
            KeyError,TypeError,AttributeError) as exc:
        # Only fixed, safe error category; never provider payload or bearer.
        print("FNO_BULK_ERROR:"+safe_failure_reason(exc))
        raise SystemExit(2)
