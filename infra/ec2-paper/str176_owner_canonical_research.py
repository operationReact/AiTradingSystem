#!/usr/bin/env python3
"""STR-176 owner-private actual historical EOD research backtest.

This is a 12-month retrospective RESEARCH simulation, not a trade-execution
receipt or a certified point-in-time independent E2E. The 00:00 daily stamp
is only a session label; final EOD OHLCV and OI are assumed available by
the NEXT session. Entry/exit fills are modeled at subsequent OPEN prices,
never invented broker fills. Test/source data never leave the PAPER instance.

Usage: host (restricted EC2) / runner (approved single-file GitHub OIDC+SSM).
"""
from __future__ import annotations
from collections import defaultdict, Counter
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import subprocess
import sys
import time
from urllib.parse import quote
from zoneinfo import ZoneInfo

ROOT=Path("/opt/aitradingsystem-paper/fno-history")
DB_NAME="personal-canonical-daily.sqlite"
SOURCE_VERSION="minttick.fno.personal.canonical.v1"
OWNER_END=datetime(2026,11,29,tzinfo=ZoneInfo("Asia/Kolkata")).timestamp()
REGION="ap-south-1"
ACCOUNT="098859530205"
INSTANCE="i-09c01b470fb39b85a"
REQUEST=Path("infra/ec2-paper/requests/str176-owner-retrospective-readonly.json")
MARKER="MINTTICK_STR176_OWNER_RETROSPECTIVE:"
EXACT={
    "kind":"minttick-str176-owner-retrospective-readonly-v1",
    "account":ACCOUNT,"region":REGION,"instance_id":INSTANCE,
    "max_provider_gets":12, "no_market_data_export":True,
    "no_db_writes":True,"no_orders":True,"historical_e2e_claim":False
}
KEY_RE=re.compile(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}\Z")
STAMP_RE=re.compile(r"\d{4}-\d{2}-\d{2}T00:00:00\+05:30\Z")
COST_BPS=30
MAX_ROWS=3929
EXPECTED={"UPSTOX_PRIVATE":3608,"NSE_PUBLIC_EOD":321}

def dec(v):
    try: n=Decimal(str(v))
    except (ValueError,TypeError,InvalidOperation):
        raise ValueError("BAD_SOURCE_PRICE") from None
    if not n.is_finite() or n<0: raise ValueError("BAD_SOURCE_PRICE")
    return n

def _private_db(root):
    root=Path(root)
    if (not root.is_absolute() or root.is_symlink() or not root.is_dir()
            or root.stat().st_mode & 0o077):
        raise ValueError("PRIVATE_STORE_UNAVAILABLE")
    dbfile=root/DB_NAME
    if (dbfile.is_symlink() or not dbfile.is_file()
            or not stat.S_ISREG(dbfile.stat().st_mode)
            or dbfile.stat().st_mode & 0o077):
        raise ValueError("PRIVATE_STORE_UNAVAILABLE")
    db=sqlite3.connect("file:"+quote(str(dbfile),safe="/")+"?mode=ro",
        uri=True,timeout=10)
    db.execute("PRAGMA query_only=ON")
    return db

def read_eligible(root=ROOT,now=None):
    """Read ONLY the strict, already cross-checked canonical 3,929-row file."""
    now=time.time() if now is None else float(now)
    db=_private_db(root)
    try:
        if db.execute("PRAGMA quick_check").fetchone()!=("ok",):
            raise ValueError("PRIVATE_STORE_INTEGRITY_FAILURE")
        meta=db.execute("SELECT schema_version,generated_at,expires_at,total FROM meta").fetchall()
        if (len(meta)!=1 or meta[0][0]!=SOURCE_VERSION
                or type(meta[0][3]) is not int or meta[0][3]!=MAX_ROWS
                or not 0<meta[0][1]<meta[0][2]<=OWNER_END
                or meta[0][1]>now+300 or now>=meta[0][2]):
            raise ValueError("PRIVATE_STORE_EXPIRED_OR_UNVERIFIED")
        raw=db.execute("""SELECT contract_key,session_day,candle_ts,
                  open_price,high_price,low_price,close_price,
                  volume,open_interest,source FROM daily
                  ORDER BY contract_key,session_day""").fetchall()
        if len(raw)!=MAX_ROWS: raise ValueError("PRIVATE_STORE_ROW_COUNT_CHANGED")
        groups=defaultdict(list)
        by_source=Counter()
        for key,day,stamp,op,high,low,close,vol,oi,source in raw:
            if (not isinstance(key,str) or not KEY_RE.fullmatch(key)
                    or not isinstance(day,str) or not isinstance(stamp,str)
                    or not STAMP_RE.fullmatch(stamp) or stamp[:10]!=day
                    or source not in EXPECTED or type(vol) is not int
                    or type(oi) is not int or not 0<=vol<=10**12
                    or not 0<=oi<=10**12):
                raise ValueError("PRIVATE_SOURCE_SCHEMA_INVALID")
            session=date.fromisoformat(day)
            expiry=datetime.strptime(key.rsplit("|",1)[1],"%d-%m-%Y").date()
            if session>expiry or session.weekday()>=5:
                raise ValueError("PRIVATE_SOURCE_SESSION_INVALID")
            expected="NSE_PUBLIC_EOD" if expiry.year==2025 and expiry.month==12 else "UPSTOX_PRIVATE"
            if source!=expected: raise ValueError("PRIVATE_SOURCE_PROVENANCE_CHANGED")
            a,h,l,c=(dec(v) for v in (op,high,low,close))
            if (min(a,h,l,c)<=0 or h<max(a,c) or l>min(a,c)):
                raise ValueError("PRIVATE_SOURCE_OHLC_INVALID")
            if groups[key] and groups[key][-1][0]>=session:
                raise ValueError("PRIVATE_SOURCE_DUPLICATE_OR_UNSORTED")
            groups[key].append((session,a,h,l,c,vol,oi,source))
            by_source[source]+=1
        if dict(by_source)!=EXPECTED or len(groups)!=276:
            raise ValueError("PRIVATE_SOURCE_COHORT_CHANGED")
        return groups
    finally:
        db.close()

def metadata_futures(expiries, fetch):
    """Fetch authoritative Upstox FUT contract identities; not guess from keys."""
    if not 1<=len(expiries)<=12: raise ValueError("EXPIRY_SCOPE_INVALID")
    identified={}
    calls=0
    for expiry in sorted(expiries):
        if not isinstance(expiry,date):
            raise ValueError("EXPIRY_SCOPE_INVALID")
        rows=fetch(expiry)
        calls+=1
        if not isinstance(rows,list) or not 1<=len(rows)<=200:
            raise ValueError("ORIGINAL_FUTURES_METADATA_MISSING")
        matches=[]
        for row in rows:
            if not isinstance(row,dict): raise ValueError("ORIGINAL_FUTURES_METADATA_INVALID")
            if (row.get("segment")!="NSE_FO" or row.get("exchange")!="NSE"
                    or row.get("instrument_type")!="FUT"
                    or row.get("underlying_key")!="NSE_INDEX|Nifty 50"
                    or row.get("expiry")!=expiry.isoformat()): continue
            key=row.get("instrument_key")
            lot=row.get("lot_size")
            if (not isinstance(key,str) or not KEY_RE.fullmatch(key)
                    or key.rsplit("|",1)[1]!=expiry.strftime("%d-%m-%Y")
                    or type(lot) is not int or not 1<=lot<=100000):
                raise ValueError("ORIGINAL_FUTURES_METADATA_INVALID")
            matches.append((key,lot))
        if not matches: raise ValueError("ORIGINAL_FUTURES_METADATA_MISSING")
        matches.sort()
        if len(set(matches))!=len(matches):
            raise ValueError("ORIGINAL_FUTURES_METADATA_AMBIGUOUS")
        identified[expiry]=matches[0]  # exact existing owner pilot selected first FUT
    return identified,calls

def _bps(numerator,denominator):
    if denominator<=0:return Decimal(0)
    return numerator*Decimal(10000)/denominator

def momentum(window,expiry,as_of):
    """Independent numerical reference of STR-176 production threshold logic.
    No next-session price enters this function; Java fixture parity is separate.
    """
    if len(window)!=7:return "BLOCKED"
    dates=[r[0] for r in window]
    if len(set(dates))!=7 or dates!=sorted(dates):return "BLOCKED"
    if any(r[6]<=0 or r[5]<0 for r in window):return "BLOCKED"
    if not all(1<=(b-a).days<=5 for a,b in zip(dates,dates[1:])):
        return "BLOCKED"
    if not 7<=(expiry-as_of).days<=120:return "BLOCKED"
    if (as_of-dates[-1]).days<1 or (as_of-dates[-1]).days>5:
        return "BLOCKED"
    for i,r in enumerate(window):
        _,op,high,low,close,volume,oi,_=r
        if _bps(high-low,close)>Decimal(800):return "BLOCKED"
        if i and abs(_bps(close-window[i-1][4],window[i-1][4]))>Decimal(2500):
            return "BLOCKED"
    prior_high=max(r[2] for r in window[:-1])
    prior_low=min(r[3] for r in window[:-1])
    last=window[-1]
    up=_bps(last[4]-prior_high,prior_high)
    down=_bps(prior_low-last[4],prior_low)
    baseline=sum(x[5] for x in window[:3])
    recent=sum(x[5] for x in window[4:])
    if baseline<=0:return "BLOCKED"
    volume_lift=_bps(Decimal(recent),Decimal(baseline))
    participation=_bps(Decimal(last[5]),Decimal(last[6]))
    changes=[(window[i][4]>window[i-1][4],window[i][4]<window[i-1][4])
             for i in range(4,7)]
    rises=sum(a for a,b in changes)
    falls=sum(b for a,b in changes)
    if volume_lift<11000 or participation<2000:return "NO_SIGNAL"
    if up>=100 and rises>=2 and falls==0 and up-20>30:
        return "LONG"
    if down>=100 and falls>=2 and rises==0 and down-20>30:
        return "SHORT"
    return "NO_SIGNAL"

def evaluate(groups, metadata, *, cost_bps=COST_BPS):
    """Chronological contract-group walkforward, one-lot modeled next opens."""
    if not isinstance(groups,dict) or not isinstance(metadata,dict) or cost_bps not in (20,30,50):
        raise ValueError("RESEARCH_INPUT_INVALID")
    all_expiries=sorted(metadata)
    if len(all_expiries)!=12:raise ValueError("NOT_TWELVE_SOURCE_COHORTS")
    # Pre-committed chronological split: oldest 8 expiries train; newest 4 OOS.
    split=all_expiries[8]
    counters=Counter()
    train_net=oos_net=0
    equity=peak=drawdown=0
    for expiry in all_expiries:
        key,lot=metadata[expiry]
        rows=groups.get(key)
        if rows is None or not 9<=len(rows)<=25:
            counters["cohortsMissingFullFuture"]=counters.get("cohortsMissingFullFuture",0)+1
            continue
        if len(set(x[0] for x in rows))!=len(rows):
            raise ValueError("DUPLICATE_FUTURE_SESSION")
        counters["futuresCohorts"]=counters.get("futuresCohorts",0)+1
        counters["sourceFuturesRows"]+=len(rows)
        for index in range(6,len(rows)-2):
            first=rows[index]
            decision_day=rows[index+1][0]
            decision=momentum(rows[index-6:index+1],expiry,decision_day)
            counters["decisionWindows"]+=1
            if decision=="BLOCKED":
                counters["dataBlockedWindows"]+=1
                continue
            counters["researchEvaluatorRuns"]+=1
            if decision=="NO_SIGNAL":
                counters["noSignalWindows"]+=1
                continue
            counters["candidateWindows"]+=1
            # Signal uses ONLY prior 7 completed EOD sessions.
            # Fill entry at NEXT trading day's OPEN, exit one more day later OPEN.
            entry=rows[index+1][1]
            exit_=rows[index+2][1]
            multiplier=1 if decision=="LONG" else -1
            gross=((exit_-entry)*lot*100*multiplier).quantize(Decimal(1),rounding=ROUND_HALF_UP)
            notional=(entry+exit_)/2*lot*100
            fees=(notional*cost_bps/10000).quantize(Decimal(1),rounding=ROUND_HALF_UP)
            net=int(gross)-int(fees)
            counters["simulatedRoundTrips"]+=1
            if expiry<split:
                counters["trainingTrades"]+=1
                train_net+=net
            else:
                counters["outOfSampleTrades"]+=1
                oos_net+=net
                equity+=net
                peak=max(peak,equity)
                drawdown=max(drawdown,peak-equity)
                if net<0:counters["outOfSampleLosses"]+=1
    if counters["futuresCohorts"]!=12:
        return {"state":"SOURCE_FUTURES_COHORTS_INCOMPLETE",
                "reason":"MISSING_FULL_FUTURES_SOURCE_METADATA_OR_ROWS",
                "counts":dict(counters)}
    return {"state":"RESEARCH_SIMULATED_AFTER_COST_NOT_CERTIFIED",
            "reason":"SIMULATED_OPENS_AND_UNVERIFIED_ORIGINAL_PUBLICATION",
            "counts":dict(counters),
            "trainNetPaise":train_net,"oosNetPaise":oos_net,
            "oosMaxDrawdownPaise":drawdown}

def run_research(root=ROOT,now=None,fetch=None):
    groups=read_eligible(root,now)
    expiries={datetime.strptime(key.rsplit("|",1)[1],"%d-%m-%Y").date()
              for key in groups}
    if fetch is None:raise ValueError("EXPLICIT_PROVIDER_METADATA_FETCH_REQUIRED")
    metadata,calls=metadata_futures(expiries,fetch)
    result=evaluate(groups,metadata)
    counts=result["counts"]
    return {
        "state":result["state"],"reason":result["reason"],
        "sourceRows":sum(len(rows) for rows in groups.values()),
        "sourceFuturesRows":counts.get("sourceFuturesRows",0),
        "sourceFuturesContracts":counts.get("futuresCohorts",0),
        "decisionWindows":counts.get("decisionWindows",0),
        "evaluatorWindows":counts.get("researchEvaluatorRuns",0),
        "candidateWindows":counts.get("candidateWindows",0),
        "simulatedRoundTrips":counts.get("simulatedRoundTrips",0),
        "trainingTrades":counts.get("trainingTrades",0),
        "outOfSampleTrades":counts.get("outOfSampleTrades",0),
        "outOfSampleLosses":counts.get("outOfSampleLosses",0),
        "trainNetPaise":result.get("trainNetPaise"),
        "oosNetPaise":result.get("oosNetPaise"),
        "oosMaxDrawdownPaise":result.get("oosMaxDrawdownPaise"),
        "providerGets":calls,
        "simulatedCostBps":COST_BPS,
        "assumedEodKnownBeforeNextOpen":True,
        "providerFirstPublishedAtVerified":False,
        "historicalStrategyE2ECertified":False,"certifiedStrategies":0,
        "orders":False,"writes":False
    }

def safe_receipt(r):
    if (not isinstance(r,dict)
            or r.get("state") not in ("RESEARCH_SIMULATED_AFTER_COST_NOT_CERTIFIED",
                                      "SOURCE_FUTURES_COHORTS_INCOMPLETE")
            or r.get("sourceRows")!=MAX_ROWS
            or r.get("providerGets")!=12
            or r.get("simulatedCostBps")!=30
            or r.get("historicalStrategyE2ECertified") is not False
            or r.get("providerFirstPublishedAtVerified") is not False
            or r.get("certifiedStrategies")!=0
            or r.get("orders") is not False or r.get("writes") is not False):
        raise ValueError("STR176_PRIVATE_RECEIPT_INVALID")
    for k in ("sourceFuturesRows","sourceFuturesContracts","decisionWindows",
              "evaluatorWindows","candidateWindows","simulatedRoundTrips",
              "trainingTrades","outOfSampleTrades","outOfSampleLosses"):
        if type(r.get(k)) is not int or not 0<=r[k]<=MAX_ROWS:
            raise ValueError("STR176_PRIVATE_RECEIPT_INVALID")
    for k in ("trainNetPaise","oosNetPaise","oosMaxDrawdownPaise"):
        if r.get(k) is not None and (type(r[k]) is not int or abs(r[k])>10**13):
            raise ValueError("STR176_PRIVATE_RECEIPT_INVALID")
    return r

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise ValueError("OWNER_HOST_NOT_VERIFIED")
    sys.path.insert(0,"/opt/aitradingsystem-paper/infra/ec2-paper")
    from fno_private_bulk_worker import Discovery,grant,ssm_token,today,UNDERLYING
    import urllib.parse
    grant().require("day",today())
    provider=Discovery(ssm_token())
    def fetch(expiry):
        q="?instrument_key="+urllib.parse.quote(UNDERLYING,safe="")+"&expiry_date="+expiry.isoformat()
        return provider.get("/future/contract"+q)
    result=safe_receipt(run_research(fetch=fetch))
    if provider.calls!=result["providerGets"]:
        raise ValueError("UNEXPECTED_UPSTOX_SOURCE_CALLS")
    print(MARKER+json.dumps(result,sort_keys=True,separators=(",",":")),flush=True)

def runner():
    if (not REQUEST.is_file() or REQUEST.is_symlink()
            or REQUEST.stat().st_size>=1024
            or json.loads(REQUEST.read_text())!=EXACT):
        raise ValueError("APPROVED_SINGLE_FILE_REQUEST_MISSING")
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    verify_aws()
    packed=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if not re.fullmatch("[A-Za-z0-9+/=]+",packed) or len(packed)>24000:
        raise ValueError("SOURCE_RUNNER_TOO_LARGE")
    command=("set -eu\numask 077\n"
             "printf '%s' '"+packed+"' | base64 -d | gzip -d | "
             "PYTHONDONTWRITEBYTECODE=1 python3 - host\n")
    call=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
              "--instance-ids",INSTANCE,"--timeout-seconds","240",
              "--comment","MintTick STR-176 one-lot source-based EOD research only",
              "--parameters",json.dumps({"commands":[command]}))
    cid=(call.get("Command") or {}).get("CommandId")
    if not isinstance(cid,str) or not re.fullmatch("[0-9a-f-]{36}",cid):
        raise ValueError("SSM_CALL_NOT_VERIFIED")
    for _ in range(65):
        time.sleep(4)
        response=_aws("ssm","get-command-invocation",
            "--command-id",cid,"--instance-id",INSTANCE)
        status=response.get("Status")
        if status=="Success":
            raw=response.get("StandardOutputContent") or ""
            if len(raw)>3000:raise ValueError("UNEXPECTED_PRIVATE_HOST_OUTPUT")
            receipt=[x[len(MARKER):] for x in raw.splitlines() if x.startswith(MARKER)]
            if len(receipt)!=1:raise ValueError("MISSING_PRIVATE_SOURCE_RECEIPT")
            out=safe_receipt(json.loads(receipt[0]))
            print(MARKER+json.dumps(out,sort_keys=True,separators=(",",":")))
            return
        if status in ("Failed","TimedOut","Cancelled"):
            raise ValueError("SSM_RESEARCH_UNAVAILABLE")
        if status not in ("Pending","InProgress","Delayed"):
            raise ValueError("SSM_STATE_UNRECOGNIZED")
    raise ValueError("SSM_RESEARCH_TIMEOUT")

if __name__=="__main__":
    try:
        if len(sys.argv)!=2 or sys.argv[1] not in ("host","runner"):
            raise ValueError("MODE_INVALID")
        host() if sys.argv[1]=="host" else runner()
    except (ValueError,TypeError,OSError,sqlite3.Error,KeyError,RuntimeError):
        print("MINTTICK_STR176_OWNER_RETROSPECTIVE:BLOCKED_NO_VERIFIED_RECEIPT",
              file=sys.stderr)
        raise SystemExit(2)
