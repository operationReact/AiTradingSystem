#!/usr/bin/env python3
"""Read-only owner-private re-fetch of exactly three isolated weekday gaps.

Not independent EOD verification. No price/contract key/timestamp/token export.
No writes. Existing 7-day cache and 2026-11-28 scope remain unchanged.
"""
from collections import defaultdict,Counter
from datetime import date,datetime,timedelta
from pathlib import Path
import json
import sqlite3
import sys
import time
from urllib.parse import quote

ROOT=Path("/opt/aitradingsystem-paper/fno-history")
MARKER="MINTTICK_FNO_PARTIAL_GAPS_V1:"
MAX_CALLS=3
SOURCE="upstox-expired-nse-fo"


def read_only(path):
    path=Path(path)
    if not path.is_file() or path.is_symlink():
        raise ValueError("PRIVATE_DB_NOT_AVAILABLE")
    db=sqlite3.connect("file:"+quote(str(path),safe="/")+"?mode=ro",
                       uri=True,timeout=15)
    db.execute("PRAGMA query_only=ON")
    return db


def identify_partial_gaps(job_rows, candle_rows):
    by_month=defaultdict(list)
    expected=defaultdict(set)
    observed=defaultdict(set)
    for key,first,last,state,interval in job_rows:
        if interval!="day" or state not in ("DONE","EMPTY"):
            raise ValueError("UNEXPECTED_JOB")
        expiry=datetime.strptime(key.rsplit("|",1)[-1],"%d-%m-%Y").date()
        if date.fromisoformat(last)!=expiry or expiry-date.fromisoformat(first)!=timedelta(days=20):
            raise ValueError("INVALID_JOB_WINDOW")
        month=expiry.strftime("%Y-%m")
        by_month[month].append((key,first,last))
        for i in range(21):
            day=date.fromisoformat(first)+timedelta(days=i)
            if day.weekday()<5:
                expected[key].add(day)
    for key,stamp in candle_rows:
        dt=datetime.fromisoformat(stamp)
        if dt.tzinfo is None or dt.utcoffset()!=timedelta(hours=5,minutes=30):
            raise ValueError("INVALID_CANDLE_TIME")
        observed[key].add(dt.date())
    result=[]
    for month,contracts in by_month.items():
        if month=="2025-12":
            continue  # the entire December 2025 cohort remains quarantined
        for key,first,last in contracts:
            for day in expected[key]-observed[key]:
                missing=sum(1 for c,_,__ in contracts if day in expected[c] and day not in observed[c])
                relevant=sum(1 for c,_,__ in contracts if day in expected[c])
                if 0<missing<relevant:
                    result.append((month,key,first,last,day))
    if len(result)!=3 or sorted(m for m,*_ in result)!=["2026-05","2026-10","2026-10"]:
        raise ValueError("ISOLATED_GAP_COHORT_CHANGED")
    return sorted(result)


def compare_gaps(gaps,fetch):
    counts=Counter()
    for idx,(month,key,first,last,day) in enumerate(gaps):
        if idx:
            time.sleep(2.5)
        reply=fetch(key,"day",date.fromisoformat(first),date.fromisoformat(last))
        counts["requests"]+=1
        if reply.status!=200 or not isinstance(reply.candles,list):
            counts["providerErrors"]+=1
            break
        found=[]
        for item in reply.candles:
            if not isinstance(item,list) or len(item)!=7:
                counts["invalidSourceRecords"]+=1
                continue
            try:
                dt=datetime.fromisoformat(item[0])
                if dt.tzinfo is None or dt.utcoffset()!=timedelta(hours=5,minutes=30):
                    raise ValueError()
            except (TypeError,ValueError):
                counts["invalidSourceRecords"]+=1
                continue
            if dt.date()==day:
                found.append(item)
        if found:
            counts["providerReturnedMissingDay"]+=1
            counts["missingDayWithMultipleProviderBars"]+=len(found)>1
        else:
            counts["providerAlsoMissingDay"]+=1
        if month=="2026-05":
            counts["mayGapChecked"]+=1
        elif month=="2026-10":
            counts["octGapChecked"]+=1
    return {k:counts[k] for k in (
        "requests","providerErrors","invalidSourceRecords",
        "providerReturnedMissingDay","providerAlsoMissingDay",
        "missingDayWithMultipleProviderBars","mayGapChecked","octGapChecked")}


def main():
    sys.path.insert(0,"/opt/aitradingsystem-paper/infra/ec2-paper")
    from fno_private_bulk_worker import grant,ssm_token,today
    from upstox_fno_backfill_adapters import UpstoxExpiredCandles
    rights=grant()
    rights.require("day",today())
    journal=read_only(ROOT/"checkpoints.sqlite")
    cache=read_only(ROOT/"private-research-cache.sqlite")
    try:
        jobs=list(journal.execute(
            "SELECT contract_key,first_day,last_day,state,interval FROM fno_backfill_jobs"))
        candles=list(cache.execute(
            "SELECT contract_key,candle_ts FROM fno_historical_candles "
            "WHERE source=? AND interval='day'",(SOURCE,)))
        if len(jobs)!=276 or len(candles)>10000:
            raise ValueError("PRIVATE_COHORT_UNEXPECTED")
        gaps=identify_partial_gaps(jobs,candles)
        provider=UpstoxExpiredCandles(
            ssm_token,grant=rights,
            verifier=lambda g:g==rights and grant()==g,today=today)
        outcome=compare_gaps(gaps,provider.fetch)
        if outcome["requests"]>MAX_CALLS:
            raise ValueError("EXCEEDED_PRIVATE_REQUEST_BUDGET")
        encoded=json.dumps(outcome,sort_keys=True,separators=(",",":"))
        if len(encoded)>500:
            raise ValueError("RESULT_TOO_LARGE")
        print(MARKER+encoded,flush=True)
    finally:
        journal.close()
        cache.close()


if __name__=="__main__":
    try:main()
    except (ValueError,OSError,sqlite3.Error,RuntimeError):
        print("FNO_PARTIAL_GAPS_ERROR:VERIFICATION_UNAVAILABLE",flush=True)
        raise SystemExit(2)
