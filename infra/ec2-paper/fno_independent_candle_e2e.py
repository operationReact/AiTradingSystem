#!/usr/bin/env python3
"""Independent, read-only integrity audit of *actual persisted* F&O candles.

This is an independent source-to-storage round-trip audit, NOT a strategy
backtest: the record's first_captured_at is download time, not an original
historical quote first-known timestamp. No provider/SSM/order access.
Legacy receipts lacking original normalized SHA are never promoted.
Only aggregate counts and fixed blocker codes should leave a private host.
"""
from __future__ import annotations
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import hashlib
import json
import re
import sqlite3
from urllib.parse import quote

KEY=re.compile(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}\Z")
HEX=re.compile(r"[0-9a-f]{64}\Z")
SOURCE="upstox-expired-nse-fo"
FIELDS=("candle_ts","open_price","high_price","low_price",
        "close_price","volume","open_interest")
ISO_IST=timezone(timedelta(hours=5,minutes=30))

def _fail(code):
    raise ValueError(code)

def audit(database: str | Path, now: datetime | None=None) -> dict:
    """Require complete receipt coverage; refuse silent legacy or edited rows."""
    path=Path(database)
    now=now or datetime.now(timezone.utc)
    if (now.tzinfo is None or not path.is_absolute() or path.is_symlink()
            or not path.is_file()):
        _fail("HISTORICAL_STORE_UNAVAILABLE")
    conn=sqlite3.connect("file:"+quote(str(path),safe="/")+"?mode=ro",
                         uri=True,timeout=10)
    try:
        conn.execute("PRAGMA query_only=ON")
        if conn.execute("PRAGMA quick_check").fetchone()!=("ok",):
            _fail("SQLITE_INTEGRITY_FAILED")
        tables={r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        required={"fno_immutable_candle_receipt","fno_history_receipts",
                  "fno_historical_candles"}
        if not required.issubset(tables):
            _fail("IMMUTABLE_RECEIPT_SCHEMA_MISSING")
        evidence=conn.execute("""
            SELECT batch_hash,source,contract_key,interval,first_day,last_day,
                   row_count,normalized_sha256,first_captured_at,permission_reference
            FROM fno_immutable_candle_receipt ORDER BY batch_hash
            """).fetchall()
        if not 1<=len(evidence)<=50000:
            _fail("NO_ELIGIBLE_IMMUTABLE_SOURCE_RECEIPTS")
        count=0
        http_count=0
        seen=set()
        last_capture=None
        has_original_http="fno_original_http_capture" in tables
        for (batch_hash,source,key,interval,first,last,expected,digest,
             captured,ref) in evidence:
            if (not isinstance(batch_hash,str) or not HEX.fullmatch(batch_hash)
                    or source!=SOURCE or not isinstance(key,str)
                    or not KEY.fullmatch(key) or interval not in
                    {"day","1minute","3minute","5minute","15minute","30minute"}
                    or type(expected) is not int or not 0<expected<=20000
                    or not isinstance(digest,str) or not HEX.fullmatch(digest)
                    or not isinstance(captured,(int,float)) or captured<=0
                    or not isinstance(ref,str) or not ref.startswith("verified-")
                    or len(ref)>100):
                _fail("INVALID_IMMUTABLE_SOURCE_RECEIPT")
            try:
                start,end=date.fromisoformat(first),date.fromisoformat(last)
                expiry=datetime.strptime(key.rsplit("|",1)[1],"%d-%m-%Y").date()
                capture_at=datetime.fromtimestamp(captured,timezone.utc)
            except (ValueError,OverflowError,TypeError):
                _fail("INVALID_SOURCE_CUSTODY_TIME")
            if (start>end or end>expiry
                    or capture_at>now.astimezone(timezone.utc)+timedelta(minutes=5)
                    or capture_at<datetime.combine(
                        end,datetime.min.time(),tzinfo=ISO_IST)):
                _fail("SOURCE_CAPTURE_TIME_OR_EXPIRY_INVALID")
            receipt=conn.execute("""
                SELECT source,contract_key,interval,row_count,committed_at
                FROM fno_history_receipts WHERE batch_hash=?
                """,(batch_hash,)).fetchone()
            if (receipt is None or receipt[:4]!=(source,key,interval,expected)
                    or receipt[4]!=captured):
                _fail("ORIGINAL_RECEIPT_JOIN_MISMATCH")
            rows=conn.execute("""
                SELECT candle_ts,open_price,high_price,low_price,close_price,
                       volume,open_interest
                FROM fno_historical_candles
                WHERE batch_hash=? AND source=? AND contract_key=? AND interval=?
                ORDER BY candle_ts
                """,(batch_hash,source,key,interval)).fetchall()
            if len(rows)!=expected:
                _fail("MISSING_OR_EXTRA_CANDLES")
            for row in rows:
                stamp,op,hi,lo,cl,volume,oi=row
                try:
                    t=datetime.fromisoformat(stamp)
                except (TypeError,ValueError):
                    _fail("INVALID_PROVIDER_CANDLE_TIMESTAMP")
                if (t.tzinfo is None or t.utcoffset()!=timedelta(hours=5,minutes=30)
                        or not start<=t.date()<=end or t.date()>expiry
                        or interval=="day" and (t.hour,t.minute,t.second,t.microsecond)
                            !=(0,0,0,0)):
                    _fail("INVALID_PROVIDER_CANDLE_TIMESTAMP")
                if (not all(type(n) in (int,float) and 0<n<float("inf")
                            for n in (op,hi,lo,cl))
                        or not lo<=min(op,cl)<=max(op,cl)<=hi
                        or type(volume) is not int or not 0<=volume<=10**12
                        or type(oi) is not int or not 0<=oi<=10**12):
                    _fail("INVALID_PROVIDER_CANDLE_OHLCV_OI")
                ident=(source,key,interval,stamp)
                if ident in seen:
                    _fail("DUPLICATE_SOURCE_CANDLE")
                seen.add(ident)
            if has_original_http:
                captured_http=conn.execute("""
                    SELECT source,original_body,response_sha256,byte_count,received_at
                    FROM fno_original_http_capture WHERE batch_hash=?
                    """,(batch_hash,)).fetchone()
                if captured_http is not None:
                    original_source,body,original_digest,byte_count,received_at=captured_http
                    if (original_source!=SOURCE
                            or type(body) is not bytes
                            or type(byte_count) is not int
                            or not 0<len(body)==byte_count<=4*1024*1024
                            or not isinstance(original_digest,str)
                            or not HEX.fullmatch(original_digest)
                            or hashlib.sha256(body).hexdigest()!=original_digest
                            or not isinstance(received_at,(int,float))
                            or not 0<received_at<=captured+1):
                        _fail("ORIGINAL_HTTP_CAPTURE_MISMATCH")
                    try:
                        provider=json.loads(body)
                        source_rows=provider["data"]["candles"]
                        if (not isinstance(provider,dict)
                                or provider.get("status")!="success"
                                or not isinstance(source_rows,list)
                                or len(source_rows)!=expected):
                            _fail("ORIGINAL_HTTP_CANDLE_MISMATCH")
                        normalized_source=sorted(
                            ((r[0],float(r[1]),float(r[2]),float(r[3]),
                              float(r[4]),int(r[5]),int(r[6]))
                             for r in source_rows),key=lambda r:r[0])
                    except (KeyError,ValueError,TypeError,IndexError):
                        _fail("ORIGINAL_HTTP_CANDLE_MISMATCH")
                    if normalized_source!=rows:
                        _fail("ORIGINAL_HTTP_CANDLE_MISMATCH")
                    http_count+=1
            normalized=hashlib.sha256(json.dumps(
                rows,separators=(",",":"),ensure_ascii=True,
                allow_nan=False).encode("utf-8")).hexdigest()
            if normalized!=digest:
                _fail("ORIGINAL_CANDLE_SHA_MISMATCH")
            count+=len(rows)
            last_capture=max(last_capture or captured,captured)
        all_rows=conn.execute(
            "SELECT COUNT(*) FROM fno_historical_candles").fetchone()[0]
        all_receipts=conn.execute(
            "SELECT COUNT(*) FROM fno_history_receipts").fetchone()[0]
        if all_rows!=count or all_receipts!=len(evidence):
            _fail("UNATTESTED_OR_ORPHAN_HISTORICAL_RECORDS")
        if has_original_http and conn.execute(
                "SELECT COUNT(*) FROM fno_original_http_capture").fetchone()[0]!=http_count:
            _fail("ORPHAN_PROVIDER_HTTP_RECEIPT")
        return {
            "state":"STORAGE_ROUND_TRIP_VERIFIED_NOT_STRATEGY_E2E",
            "verifiedCandleRows":count,
            "verifiedImmutableBatches":len(evidence),
            "replayableOriginalHttpResponseBatches":http_count,
            "originalProviderPublicationTimeVerified":False,
            "legacyOrUnattributedRows":0,
            "sourceFirstCapturedAtVerified":True,
            "historicalFirstKnownAtReconstructed":False,
            "independentSourceRightsVerified":False,
            "historicalStrategyE2EValidated":0,
            "brokerOrders":False,
        }
    finally:
        conn.close()
