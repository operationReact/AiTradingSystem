#!/usr/bin/env python3
"""Private, read-only December 2025 Upstox daily source replay diagnostics.

Never prints instrument keys, candles, tokens, raw prices, or timestamps.
Uses at most 23 permitted provider GETs on the existing owner-only EC2 host.
"""
from __future__ import annotations
from collections import Counter
from datetime import date, datetime
from pathlib import Path
import json
import re
import sqlite3
import sys
import time
from urllib.parse import quote

SOURCE = "upstox-expired-nse-fo"

ROOT=Path("/opt/aitradingsystem-paper/fno-history")
JOURNAL=ROOT/"checkpoints.sqlite"
CACHE=ROOT/"private-research-cache.sqlite"
MARKER="MINTTICK_FNO_REPLAY_V1:"
MAX_CONTRACTS=23


def db_read(path):
    if not path.is_file() or path.is_symlink():
        raise RuntimeError("SOURCE_REPLAY_DB_UNAVAILABLE")
    uri="file:"+quote(str(path),safe="/")+"?mode=ro"
    db=sqlite3.connect(uri,uri=True,timeout=15)
    db.execute("PRAGMA query_only=ON")
    return db


def normalize_row(record):
    if not isinstance(record,(list,tuple)) or len(record)!=7:
        return None
    ts=record[0]
    try:
        dt=datetime.fromisoformat(ts)
        if dt.tzinfo is None or dt.utcoffset() is None:
            return None
        return dt.isoformat(),tuple(record[1:])
    except (TypeError,ValueError):
        return None


def compare_one(persisted, fresh):
    counts=Counter()
    fresh_by_day={}
    for row in fresh:
        validated=normalize_row(row)
        if validated is None:
            counts["badSourceRows"]+=1
            continue
        stamp,values=validated
        dt=datetime.fromisoformat(stamp)
        fresh_by_day.setdefault(dt.date(),[]).append((stamp,values))
    saved_by_day={}
    for row in persisted:
        validated=normalize_row(row)
        if validated is None:
            counts["badSavedRows"]+=1
            continue
        stamp,values=validated
        dt=datetime.fromisoformat(stamp)
        saved_by_day.setdefault(dt.date(),[]).append((stamp,values))
    for day, saved in saved_by_day.items():
        observed=fresh_by_day.get(day,[])
        counters=Counter(datetime.fromisoformat(x[0]).strftime("%H:%M%z") for x in observed)
        if len(observed)>1:
            counts["sourceMultiRowDays"]+=1
        if not observed:
            counts["sourceMissingSavedDays"]+=1
        if len(observed)==1 and counters.get("00:00+0530")==1:
            counts["providerMidnightOnlyDays"]+=1
        if len(observed)==1 and counters.get("09:15+0530")==1:
            counts["provider0915OnlyDays"]+=1
        for stamp,values in saved:
            clock=datetime.fromisoformat(stamp).strftime("%H:%M%z")
            if clock=="00:00+0530":
                counts["savedMidnightRows"]+=1
                if (stamp,values) in observed:
                    counts["matchedMidnightValues"]+=1
            elif clock=="09:15+0530":
                counts["saved0915Rows"]+=1
                if (stamp,values) in observed:
                    counts["matched0915Values"]+=1
    return counts


def run():
    sys.path.insert(0, "/opt/aitradingsystem-paper/infra/ec2-paper")
    from fno_private_bulk_worker import grant, ssm_token, today
    from upstox_fno_backfill_adapters import UpstoxExpiredCandles
    approval=grant()  # exact owner-private permit through 2026-11-28
    if today()>date(2026,11,28):
        raise RuntimeError("REPLAY_OWNER_SCOPE_EXPIRED")
    journal=db_read(JOURNAL)
    cache=db_read(CACHE)
    try:
        jobs=list(journal.execute("""SELECT contract_key,first_day,last_day
                         FROM fno_backfill_jobs WHERE interval='day'
                         AND last_day>='2025-12-01' AND last_day<='2025-12-31'
                         AND state='DONE' ORDER BY contract_key"""))
        if len(jobs)!=MAX_CONTRACTS:
            raise RuntimeError("SOURCE_REPLAY_COHORT_INVALID")
        token=ssm_token()
        provider=UpstoxExpiredCandles(lambda:token,grant=approval,
                                     verifier=lambda g: g==approval and grant()==g,
                                     today=today)
        counts=Counter()
        for index,(key,first,last) in enumerate(jobs):
            if not re.fullmatch(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-2025",key):
                raise RuntimeError("SOURCE_REPLAY_KEY_INVALID")
            if index:
                time.sleep(2.5)
            reply=provider.fetch(key,"day",date.fromisoformat(first),date.fromisoformat(last))
            counts["providerRequests"]+=1
            if reply.status!=200 or not isinstance(reply.candles,list):
                counts["providerErrors"]+=1
                break
            counts["sourceContractsChecked"]+=1
            existing=list(cache.execute("""SELECT candle_ts,open_price,high_price,low_price,
                               close_price,volume,open_interest
                               FROM fno_historical_candles
                               WHERE source=? AND interval='day' AND contract_key=?""",
                               (SOURCE,key)))
            counts.update(compare_one(existing,reply.candles))
        # This is evidence, not authorization to delete a conflicting price.
        counts["candidateMatchesProvider"]=int(
            counts["providerErrors"]==0
            and counts["sourceContractsChecked"]==MAX_CONTRACTS
            and counts["savedMidnightRows"]==counts["matchedMidnightValues"]==322
            and counts["sourceMultiRowDays"]==0
            and counts["sourceMissingSavedDays"]==0
            and counts["provider0915OnlyDays"]==0
            and counts["providerMidnightOnlyDays"]==322
        )
        permitted={"providerRequests","providerErrors","sourceContractsChecked",
                   "savedMidnightRows","saved0915Rows","matchedMidnightValues",
                   "matched0915Values","sourceMultiRowDays","sourceMissingSavedDays",
                   "providerMidnightOnlyDays","provider0915OnlyDays",
                   "badSourceRows","badSavedRows","candidateMatchesProvider"}
        safe={key:counts[key] for key in sorted(permitted)}
        if any(type(value) is not int or value<0 or value>10000 for value in safe.values()):
            raise RuntimeError("SOURCE_REPLAY_METRIC_INVALID")
        print(MARKER+json.dumps(safe,sort_keys=True,separators=(",",":")),flush=True)
    finally:
        journal.close()
        cache.close()


if __name__=="__main__":
    try: run()
    except (RuntimeError,ValueError,OSError,sqlite3.Error):
        print("FNO_REPLAY_ERROR:CHECK_FAILED",flush=True)
        raise SystemExit(2)
