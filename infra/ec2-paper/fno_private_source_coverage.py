#!/usr/bin/env python3
"""Owner-private expired NIFTY source-catalogue and calendar gap diagnostics.

Read-only on-host; 25 Upstox metadata GET maximum. No candle values, instrument
identifiers or credentials are printed or sent to GitHub. Exchange holidays,
illiquid contract days, external price accuracy and durable rights are NOT
certified by this tool.
"""
from collections import Counter,defaultdict
from datetime import date,timedelta,datetime
from pathlib import Path
import json
import re
import sqlite3
import sys
from urllib.parse import quote

ROOT=Path("/opt/aitradingsystem-paper/fno-history")
SOURCE="upstox-expired-nse-fo"
MARKER="MINTTICK_FNO_COVERAGE_V1:"
EXPECTED_MONTHS=tuple(["2025-11","2025-12"]+[f"2026-{m:02}" for m in range(1,11)])
# NSE/FAOP/71777 (12 December 2025) is the published 2026 F&O holiday
# schedule. These closures narrow weekday gap *candidates*; they are NOT proof
# of a complete exchange-session calendar, quote provenance or data rights.
# Ref: https://nsearchives.nseindia.com/content/circulars/FAOP71777.pdf
# NSE/FAOP/65588 (13 December 2024). 2025-10-21 is deliberately NOT an
# all-day closure: the circular explicitly schedules Muhurat trading that day.
# Ref: https://nsearchives.nseindia.com/content/circulars/FAOP65588.pdf
NSE_FO_2025_PUBLISHED_HOLIDAYS = frozenset(date.fromisoformat(day) for day in (
    "2025-02-26", "2025-03-14", "2025-03-31", "2025-04-10",
    "2025-04-14", "2025-04-18", "2025-05-01", "2025-08-15",
    "2025-08-27", "2025-10-02", "2025-10-22", "2025-11-05",
    "2025-12-25",
))
NSE_FO_2026_PUBLISHED_HOLIDAYS = frozenset(date.fromisoformat(day) for day in (
    "2026-01-26", "2026-03-03", "2026-03-26", "2026-03-31",
    "2026-04-03", "2026-04-14", "2026-05-01", "2026-05-28",
    "2026-06-26", "2026-09-14", "2026-10-02", "2026-10-20",
    "2026-11-10", "2026-11-24", "2026-12-25",
))
# Original annual schedule NSE/FAOP/71777 was amended by NSE/FAOP/72262
# dated 2026-01-12. NSE F&O was CLOSED on 2026-01-15 because of the
# Maharashtra municipal corporation election. For historical as-of decisions,
# do not imply the amendment was public before 2026-01-12.
# Ref: https://nsearchives.nseindia.com/content/circulars/FAOP72262.pdf
NSE_FO_2026_AMENDED_HOLIDAYS = frozenset({date(2026, 1, 15)})
NSE_FO_PUBLISHED_CLOSED_DAYS = (NSE_FO_2025_PUBLISHED_HOLIDAYS
                                | NSE_FO_2026_PUBLISHED_HOLIDAYS
                                | NSE_FO_2026_AMENDED_HOLIDAYS)


def ro(path):
    path=Path(path)
    if not path.is_file() or path.is_symlink():
        raise ValueError("COVERAGE_DB_UNAVAILABLE")
    db=sqlite3.connect("file:"+quote(str(path),safe="/")+"?mode=ro",
                       uri=True,timeout=15)
    db.execute("PRAGMA query_only=ON")
    return db


def calendar_candidates(job_rows,candle_rows):
    """Count weekday absence as a candidate, never as a verified missing trade."""
    expected=defaultdict(set)
    months={}
    observed=defaultdict(set)
    duplicates=Counter()
    weekend=Counter()
    irregular=Counter()
    for key,first,last in job_rows:
        expiry=datetime.strptime(key.rsplit("|",1)[-1],"%d-%m-%Y").date()
        m=expiry.strftime("%Y-%m")
        months[key]=m
        first,last=date.fromisoformat(first),date.fromisoformat(last)
        if last-first!=timedelta(days=20) or expiry!=last:
            raise ValueError("COVERAGE_UNEXPECTED_WINDOW")
        for i in range(21):
            day=first+timedelta(days=i)
            if day.weekday()<5:
                expected[key].add(day)
    for key,stamp in candle_rows:
        if key not in months:
            raise ValueError("COVERAGE_ORPHAN_CANDLE")
        dt=datetime.fromisoformat(stamp)
        if dt.tzinfo is None or dt.utcoffset()!=timedelta(hours=5,minutes=30):
            raise ValueError("COVERAGE_INVALID_TIMESTAMP")
        m=months[key]
        if dt.date() in observed[key]:
            duplicates[m]+=1
        observed[key].add(dt.date())
        if dt.date().weekday()>=5:
            weekend[m]+=1
        if dt.hour!=0 or dt.minute!=0 or dt.second!=0:
            irregular[m]+=1
    by_month=[]
    for m in EXPECTED_MONTHS:
        keys=[k for k,v in months.items() if v==m]
        slots=sum(len(expected[k]) for k in keys)
        missing=sum(len(expected[k]-observed[k]) for k in keys)
        # Count a published NSE F&O closure separately from an unexplained
        # weekday gap. Do not erase existing candidate counts or invent bars.
        official_closed_slots=sum(
            len(expected[k] & NSE_FO_PUBLISHED_CLOSED_DAYS) for k in keys)
        official_closed_missing=sum(
            len((expected[k]-observed[k]) & NSE_FO_PUBLISHED_CLOSED_DAYS)
            for k in keys)
        possible_days=set().union(*(expected[k] for k in keys))
        common_empty=0
        partial_missing=0
        for day in possible_days:
            applicable=[k for k in keys if day in expected[k]]
            absent=sum(1 for k in applicable if day not in observed[k])
            if absent and absent==len(applicable):
                common_empty+=1
            elif absent:
                partial_missing+=absent
        if missing!=common_empty*len(keys)+partial_missing:
            raise ValueError("COVERAGE_SESSION_COUNTS_INCONSISTENT")
        by_month.append({
            "month":m,"contracts":len(keys),"expectedWeekdaySlots":slots,
            "missingWeekdayCandidates":missing,
            "nsePublishedHolidaySlots":official_closed_slots,
            "nseHolidayMissingSlots":official_closed_missing,
            "remainingWeekdayGapCandidates":missing-official_closed_missing,
            "commonEmptyWeekdays":common_empty,
            "partialWeekdayGapSlots":partial_missing,
            "observedUniqueDays":sum(len(observed[k]) for k in keys),
            "weekendRows":weekend[m],"dailyDateDuplicates":duplicates[m],
            "nonMidnightRows":irregular[m],
        })
    return by_month


def metadata_match(jobs,expiries,contract_lookup):
    """Only the selected 23-contract pilot. Not all listed NSE F&O contracts."""
    from fno_private_bulk_worker import _key,contracts
    by_month=defaultdict(set)
    for key,first,last,state in jobs:
        expiry=datetime.strptime(key.rsplit("|",1)[-1],"%d-%m-%Y").date()
        by_month[expiry].add(key)
        if state not in ("DONE","EMPTY"):
            raise ValueError("COVERAGE_CHECKPOINT_INCOMPLETE")
    expected_months={exp.strftime("%Y-%m") for exp in expiries}
    if expected_months!=set(EXPECTED_MONTHS) or len(expiries)!=12:
        raise ValueError("COVERAGE_EXPIRY_CATALOGUE_MISMATCH")
    counts=Counter()
    per_month=[]
    for expiry in expiries:
        fut,opts=contract_lookup[expiry]
        selected=contracts(fut,opts,expiry)
        if len(selected)!=23 or len(set(selected))!=23:
            counts["invalidSourcePlanMonths"]+=1
        ref=by_month.get(expiry,set())
        expected=set(selected)
        counts["selectedMatches"]+=len(ref&expected)
        counts["selectedMissing"]+=len(expected-ref)
        counts["selectedUnexpected"]+=len(ref-expected)
        expected_futures={key for item in fut if (key:=_key(item,expiry,"FUT"))}
        expected_ce={key for item in opts if (key:=_key(item,expiry,"CE"))}
        expected_pe={key for item in opts if (key:=_key(item,expiry,"PE"))}
        future=len(ref&expected_futures)
        ce=len(ref&expected_ce)
        pe=len(ref&expected_pe)
        counts["futures"]+=future
        counts["calls"]+=1
        counts["calls"]+=1
        counts["CE"]+=ce
        counts["PE"]+=pe
        counts["invalidTypes"]+=len(ref-(expected_futures|expected_ce|expected_pe))
        per_month.append({
            "month":expiry.strftime("%Y-%m"),
            "futures":future,"calls":ce+pe,"CE":ce,"PE":pe,
            "selectedContractMatches":len(ref&expected),
        })
    ok=(counts["selectedMatches"]==276 and not counts["selectedMissing"]
        and not counts["selectedUnexpected"] and not counts["invalidTypes"]
        and not counts["invalidSourcePlanMonths"] and counts["futures"]==12
        and counts["CE"]==132 and counts["PE"]==132)
    return ok,dict(counts),per_month


def run():
    sys.path.insert(0,"/opt/aitradingsystem-paper/infra/ec2-paper")
    from fno_private_bulk_worker import Discovery,grant,ssm_token,monthly_expiries,today,UNDERLYING
    import urllib.parse
    approval=grant()
    approval.require("day",today())
    journal=ro(ROOT/"checkpoints.sqlite")
    cache=ro(ROOT/"private-research-cache.sqlite")
    try:
        jobs=list(journal.execute(
            "SELECT contract_key,first_day,last_day,state FROM fno_backfill_jobs"))
        if len(jobs)!=276 or len({x[0] for x in jobs})!=276:
            raise ValueError("COVERAGE_PILOT_MISMATCH")
        rows=list(cache.execute(
            "SELECT contract_key,candle_ts FROM fno_historical_candles "
            "WHERE source=? AND interval='day'",(SOURCE,)))
        if len(rows)>10000:
            raise ValueError("COVERAGE_BOUNDED_ROWS")
        cal=calendar_candidates([(a,b,c) for a,b,c,_ in jobs],rows)
        token=ssm_token()
        provider=Discovery(token)
        key=urllib.parse.quote(UNDERLYING,safe="")
        expiry_list=monthly_expiries(provider.get("/expiries?instrument_key="+key),today())
        selected={}
        for expiry in expiry_list:
            suffix="?instrument_key="+key+"&expiry_date="+expiry.isoformat()
            future=provider.get("/future/contract"+suffix)
            options=provider.get("/option/contract"+suffix)
            selected[expiry]=(future,options)
        ok,totals,per_month=metadata_match(jobs,expiry_list,selected)
        if provider.calls>25:
            raise ValueError("COVERAGE_REQUEST_LIMIT_EXCEEDED")
        report={
            "source":"UPSTOX_PRIVATE_EXPIRED_NIFTY_CATALOGUE",
            "catalogue": "MATCHED" if ok else "MISMATCH",
            "metadataGets":provider.calls,
            "matchedSelectedContracts":totals.get("selectedMatches",0),
            "missingSelectedContracts":totals.get("selectedMissing",0),
            "unexpectedSelectedContracts":totals.get("selectedUnexpected",0),
            "untypedContracts":totals.get("invalidTypes",0),
            "futures":totals.get("futures",0),
            "CE":totals.get("CE",0),"PE":totals.get("PE",0),
            "priceIndependentVerified":False,
            "exchangeSessionCalendarVerified":False,
            "dataRetentionRightsVerified":False,
            "calendar":cal,
            "expirySelected":per_month,
        }
        encoded=json.dumps(report,sort_keys=True,separators=(",",":"))
        if len(encoded)>6500:
            raise ValueError("COVERAGE_REPORT_TOO_LARGE")
        print(MARKER+encoded,flush=True)
    finally:
        journal.close()
        cache.close()


if __name__=="__main__":
    try:
        run()
    except (ValueError,OSError,RuntimeError,sqlite3.Error,TypeError):
        print("FNO_COVERAGE_ERROR:SOURCE_METADATA_VERIFICATION_UNAVAILABLE",flush=True)
        raise SystemExit(2)
