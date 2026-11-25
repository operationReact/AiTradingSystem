#!/usr/bin/env python3
"""Fail-closed private reconciliation with independently *licensed* NSE F&O EOD.

This module NEVER downloads market data, changes the private SQLite cache,
certifies rights, exports contract identifiers/prices, or approves backtests.
The operator must provide an authorized, on-host reference CSV, provider-to-
exchange catalogue CSV, and a separately reviewed rights attestation.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
from urllib.parse import quote

SOURCE = "upstox-expired-nse-fo"
REFERENCE_HEADER = {
    "BizDt","TckrSymb","FinInstrmTp","XpryDt","StrkPric","OptnTp",
    "OpnPric","HghPric","LwPric","ClsPric","TtlTradgVol","OpnIntrst",
}
CATALOGUE_HEADER = {"contract_key","underlying","expiry","kind","strike","lot_size"}
RIGHTS_HEADER = {
    "licence_reference","permitted_private_reconciliation",
    "may_read_eod_on_host","may_retain_reference_data",
    "may_redistribute","expires_on","source",
}
ROOT = Path("/opt/aitradingsystem-paper/fno-history")
LIMIT = 100000
DECEMBER = (2025,12)
OUTPUT_MARKER = "MINTTICK_FNO_INDEPENDENT_RECONCILE_V1:"


def decimal(value, *, zero_ok=True):
    try:
        n=Decimal(str(value).strip())
        if not n.is_finite() or n<0 or (not zero_ok and n<=0):
            raise ValueError("INVALID_EOD_NUMBER")
        return n
    except (InvalidOperation,TypeError):
        raise ValueError("INVALID_EOD_NUMBER") from None


def validated_lot(value):
    """Contract lots must be independently matched to the historic instrument."""
    n=decimal(value,zero_ok=False)
    if n!=n.to_integral_value() or n>10000:
        raise ValueError("PRIVATE_CONTRACT_LOT_SIZE_INVALID")
    return n


def safe_file(path, max_bytes):
    p=Path(path)
    if (not p.is_absolute() or p.is_symlink() or not p.is_file()
            or not 0<p.stat().st_size<=max_bytes):
        raise ValueError("PRIVATE_EOD_FILE_INVALID")
    return p


def load_catalogue(path):
    p=safe_file(path,32768)
    mapping={}
    identities=set()
    with p.open(newline="",encoding="utf-8") as handle:
        reader=csv.DictReader(handle)
        if reader.fieldnames is None or set(reader.fieldnames)!=CATALOGUE_HEADER:
            raise ValueError("PRIVATE_CONTRACT_CATALOGUE_SCHEMA")
        for row in reader:
            key=row["contract_key"]
            if not re.fullmatch(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-12-2025",key):
                raise ValueError("PRIVATE_CONTRACT_CATALOGUE_KEY")
            expiry=date.fromisoformat(row["expiry"])
            if (expiry.year,expiry.month)!=DECEMBER or expiry.strftime("%d-%m-%Y")!=key.rsplit("|",1)[1]:
                raise ValueError("PRIVATE_CONTRACT_CATALOGUE_EXPIRY")
            if row["underlying"]!="NIFTY" or row["kind"] not in ("FUT","CE","PE"):
                raise ValueError("PRIVATE_CONTRACT_CATALOGUE_TYPE")
            strike=Decimal(0) if row["kind"]=="FUT" else decimal(row["strike"],zero_ok=False)
            if row["kind"]=="FUT" and row["strike"] not in ("","0","0.0"):
                raise ValueError("PRIVATE_CONTRACT_CATALOGUE_STRIKE")
            identity=(expiry,row["kind"],strike)
            lot=validated_lot(row["lot_size"])
            if key in mapping or identity in identities:
                raise ValueError("PRIVATE_CONTRACT_CATALOGUE_AMBIGUOUS")
            mapping[key]=(expiry,row["kind"],strike,lot)
            identities.add(identity)
            if len(mapping)>23:
                raise ValueError("PRIVATE_CONTRACT_CATALOGUE_BOUNDED")
    if len(mapping)!=23:
        raise ValueError("PRIVATE_CONTRACT_CATALOGUE_INCOMPLETE")
    return mapping


def load_reference(path):
    """Accept a separately licensed, locally provided NSE F&O UDiFF EOD CSV."""
    p=safe_file(path,32*1024*1024)
    reference={}
    scanned=0
    with p.open(newline="",encoding="utf-8-sig") as handle:
        reader=csv.DictReader(handle)
        if reader.fieldnames is None or not REFERENCE_HEADER.issubset(set(reader.fieldnames)):
            raise ValueError("PRIVATE_EOD_SCHEMA_INVALID")
        for row in reader:
            scanned+=1
            if scanned>LIMIT:
                raise ValueError("PRIVATE_EOD_LIMIT_EXCEEDED")
            if row["TckrSymb"].strip()!="NIFTY":
                continue
            instrument=row["FinInstrmTp"].strip()
            if instrument not in ("IDF","IDO"):
                continue
            day=date.fromisoformat(row["BizDt"].strip())
            expiry=date.fromisoformat(row["XpryDt"].strip())
            if (expiry.year,expiry.month)!=DECEMBER:
                continue
            option=row["OptnTp"].strip()
            if instrument=="IDF":
                kind="FUT"
                if option not in ("","-"):
                    raise ValueError("PRIVATE_EOD_TYPE_AMBIGUOUS")
                strike=Decimal(0)
            else:
                if option not in ("CE","PE"):
                    raise ValueError("PRIVATE_EOD_TYPE_AMBIGUOUS")
                kind=option
                strike=decimal(row["StrkPric"],zero_ok=False)
            identity=(expiry,kind,strike,day)
            values=tuple(decimal(row[field],zero_ok=(field not in
                         ("OpnPric","HghPric","LwPric","ClsPric"))) for field in
                         ("OpnPric","HghPric","LwPric","ClsPric",
                          "TtlTradgVol","OpnIntrst"))
            if identity in reference:
                raise ValueError("PRIVATE_EOD_DUPLICATE_EXCHANGE_KEY")
            reference[identity]=values
    return reference,scanned


def ro_cache(path):
    p=safe_file(path,256*1024*1024)
    db=sqlite3.connect("file:"+quote(str(p),safe="/")+"?mode=ro",
                       uri=True,timeout=12)
    db.execute("PRAGMA query_only=ON")
    return db


def reconcile(cache, catalogue, reference):
    """Aggregate only. No individual source datum or instrument ID escapes."""
    if (len(catalogue)!=23
            or any(not isinstance(item,tuple) or len(item)!=4
                   for item in catalogue.values())
            or len({item[:3] for item in catalogue.values()})!=23):
        raise ValueError("PRIVATE_CATALOGUE_INCOMPLETE")
    # Revalidate a direct programmatic catalogue; file validation is not the
    # sole trust boundary. Never normalize by an invented lot size.
    for item in catalogue.values():
        validated_lot(item[3])
    found=defaultdict(list)
    rows=cache.execute("""SELECT contract_key,candle_ts,open_price,high_price,
                     low_price,close_price,volume,open_interest
                     FROM fno_historical_candles
                     WHERE source=? AND interval='day' AND contract_key LIKE ?""",
                     (SOURCE,"%-12-2025"))
    count=0
    for key,stamp,op,high,low,close,volume,oi in rows:
        count+=1
        if count>1000:
            raise ValueError("PRIVATE_EOD_EXCESS_ROWS")
        if key not in catalogue:
            raise ValueError("PRIVATE_EOD_UNMAPPED_CONTRACT")
        dt=datetime.fromisoformat(stamp)
        if dt.tzinfo is None or dt.utcoffset()!=timedelta(hours=5,minutes=30):
            raise ValueError("PRIVATE_EOD_UNEXPECTED_TIMEZONE")
        if dt.date().month!=12 or dt.date().year!=2025:
            raise ValueError("PRIVATE_EOD_OUTSIDE_WINDOW")
        values=tuple(decimal(v,zero_ok=i>=4) for i,v in
                     enumerate((op,high,low,close,volume,oi)))
        found[(key,dt.date())].append((dt.strftime("%H:%M"),values))
    if len(found)!=322 or count!=643:
        raise ValueError("PRIVATE_EOD_COHORT_CHANGED")

    totals=Counter()
    for (key,day),candles in found.items():
        expiry,kind,strike,lot=catalogue[key]
        eod=reference.get((expiry,kind,strike,day))
        if len(candles)==2:
            totals["duplicateDays"]+=1
        elif len(candles)!=1:
            raise ValueError("PRIVATE_EOD_MORE_THAN_TWO")
        if eod is None:
            totals["missingIndependentEod"]+=1
            continue
        totals["independentRowsFound"]+=1
        # NSE F&O UDiFF TtlTradgVol is contract count. The Upstox daily
        # historical volume is units of underlying in the verified December
        # pilot (NSE contracts × authenticated instrument lot size).
        # Keep NSE raw reference and original Upstox cache untouched.
        eod_normalized=(*eod[:4],eod[4]*lot,eod[5])
        midnight=[values for stamp,values in candles if stamp=="00:00"]
        opening=[values for stamp,values in candles if stamp=="09:15"]
        if len(midnight)!=1 or len(opening)>1:
            raise ValueError("PRIVATE_EOD_UNEXPECTED_TIMESTAMP")
        a=midnight[0]==eod_normalized
        b=len(opening)==1 and opening[0]==eod_normalized
        if midnight[0][4]==eod[4]:
            totals["midnightUnscaledVolumeMatches"]+=1
        if midnight[0][4]==eod_normalized[4]:
            totals["midnightScaledVolumeMatches"]+=1
        if opening and opening[0][4]==eod[4]:
            totals["openingUnscaledVolumeMatches"]+=1
        if opening and opening[0][4]==eod_normalized[4]:
            totals["openingScaledVolumeMatches"]+=1
        if a:
            totals["midnightExactMatches"]+=1
        if b:
            totals["openingExactMatches"]+=1
        if len(opening):
            if midnight[0]!=opening[0]:
                totals["providerConflictingDays"]+=1
            else:
                totals["providerIdenticalDays"]+=1
            if a and b:
                totals["bothExactMatches"]+=1
            elif a and not b:
                totals["midnightOnlyExactMatches"]+=1
            elif b and not a:
                totals["openingOnlyExactMatches"]+=1
            else:
                totals["neitherExactMatch"]+=1
        elif not a:
            totals["singleCandleDisagrees"]+=1
    keys=[
        "totalCandles","contractDates","duplicateDays","independentRowsFound",
        "missingIndependentEod","providerConflictingDays","providerIdenticalDays",
        "midnightExactMatches","openingExactMatches","bothExactMatches",
        "midnightOnlyExactMatches","openingOnlyExactMatches","neitherExactMatch",
        "singleCandleDisagrees",
        "midnightUnscaledVolumeMatches","openingUnscaledVolumeMatches",
        "midnightScaledVolumeMatches","openingScaledVolumeMatches",
    ]
    totals["totalCandles"]=count
    totals["contractDates"]=len(found)
    return {"schema":"minttick.fno.independent.eod.review.v1",
            "result":"REVIEW", "backtestingApproved":False,
            "commercialRightsVerified":False,
            "mutatedRawCache":False,
            "referenceFormat":"NSE_FO_UDIFF_LOCAL_FILE",
            "volumeNormalization":"NSE_CONTRACTS_TIMES_AUTHENTICATED_HISTORICAL_LOT",
            "counts":{k:totals[k] for k in keys}}


def rights_attestation(path):
    """Approval receipt cannot itself prove legal rights: require an externally
    reviewed, out-of-band SHA-256 allowlist; never self-certify from JSON alone."""
    p=safe_file(path,4096)
    expected=os.environ.get("MINTTICK_REVIEWED_EOD_RIGHTS_SHA256","")
    if not re.fullmatch(r"[0-9a-f]{64}",expected):
        raise ValueError("INDEPENDENT_EOD_RIGHTS_NOT_APPROVED")
    raw=p.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=expected:
        raise ValueError("INDEPENDENT_EOD_RIGHTS_NOT_APPROVED")
    record=json.loads(raw)
    if (not isinstance(record,dict) or set(record)!=RIGHTS_HEADER
        or record["source"]!="NSE_DATA_AND_ANALYTICS_LICENSED_FO_EOD"
        or not record["permitted_private_reconciliation"]
        or not record["may_read_eod_on_host"]
        or record["may_retain_reference_data"] or record["may_redistribute"]
        or not isinstance(record["licence_reference"],str)
        or not re.fullmatch(r"[A-Za-z0-9_.-]{8,100}",record["licence_reference"])
        or date.fromisoformat(record["expires_on"])<date.today()):
        raise ValueError("INDEPENDENT_EOD_RIGHTS_NOT_APPROVED")
    # The external administrator must review actual executed licensing terms.
    # This file/digest check prevents accidental invocation; is NOT a licence.
    return True


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--cache",default=str(ROOT/"private-research-cache.sqlite"))
    parser.add_argument("--catalogue",required=True)
    parser.add_argument("--reference",required=True)
    parser.add_argument("--rights-attestation",required=True)
    args=parser.parse_args()
    try:
        rights_attestation(args.rights_attestation)
        catalogue=load_catalogue(args.catalogue)
        reference,_=load_reference(args.reference)
        db=ro_cache(args.cache)
        try:
            db.execute("BEGIN")
            report=reconcile(db,catalogue,reference)
        finally:
            db.close()
        print(OUTPUT_MARKER+json.dumps(report,sort_keys=True,separators=(",",":")))
        return 0
    except (ValueError,OSError,sqlite3.Error,TypeError,KeyError):
        # Do not write arbitrary provider paths, source rows, market values,
        # contract identities or entitlement details to actions logs.
        print("FNO_INDEPENDENT_RECONCILE:BLOCKED_OR_INVALID_INPUT")
        return 2


if __name__=="__main__":
    raise SystemExit(main())
