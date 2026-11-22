#!/usr/bin/env python3
"""One-day NSE F&O independent December duplicate diagnostic (research only).

Exactly 2025-12-15, max one public official NSE UDiFF ZIP in memory, two
Upstox catalogue GETs, owner-private read-only cache. No raw prices, names,
strikes, keys, EOD rows, source tokens or contract trade dates exported.
Never chooses a canonical candle, certifies licensing or approves backtests.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
import csv
import io
import json
import re
import sqlite3
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

DAY=date(2025,12,15)
EXPIRY=date(2025,12,30)
SOURCE="upstox-expired-nse-fo"
ROOT="/opt/aitradingsystem-paper/fno-history"
MARKER="MINTTICK_FNO_DEC_NSE_ONE_DAY_V1:"
MAX_CONTRACTS=23
MAX_ARCHIVE_BYTES=8*1024*1024
MAX_CSV_BYTES=45*1024*1024
MAX_ROWS=180000
PRICE_KEYS=("OpnPric","HghPric","LwPric","ClsPric")
DATA_KEYS=PRICE_KEYS+("TtlTradgVol","OpnIntrst")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        return None

def decimal(raw):
    try:
        value=Decimal(str(raw).strip())
    except (InvalidOperation,TypeError,ValueError):
        raise ValueError("EOD_NUMBER_INVALID") from None
    if not value.is_finite() or value<0:
        raise ValueError("EOD_NUMBER_INVALID")
    return value

def fetch_nse_archive(opener=None):
    url=("https://nsearchives.nseindia.com/content/fo/"
         "BhavCopy_NSE_FO_0_0_0_20251215_F_0000.csv.zip")
    opener=opener or urllib.request.build_opener(NoRedirect())
    request=urllib.request.Request(url,method="GET",headers={
        "Accept":"application/zip",
        "User-Agent":"MintTick-Owner-ReadOnly-December-Diagnostics/1.0",
    })
    try:
        with opener.open(request,timeout=25) as response:
            if response.status!=200 or response.url!=url:
                return "SOURCE_REJECTED",None
            size=response.headers.get("Content-Length")
            if size and (not size.isdecimal() or int(size)>MAX_ARCHIVE_BYTES):
                return "INVALID_SOURCE_RESPONSE",None
            data=response.read(MAX_ARCHIVE_BYTES+1)
    except urllib.error.HTTPError:
        return "SOURCE_REJECTED",None
    except (urllib.error.URLError,OSError,TimeoutError):
        return "TRANSPORT_UNAVAILABLE",None
    if len(data)>MAX_ARCHIVE_BYTES or not data.startswith(b"PK"+bytes((3,4))):
        return "INVALID_SOURCE_RESPONSE",None
    return "FETCHED",data

def parse_eod(blob):
    if not blob.startswith(b"PK"+bytes((3,4))) or len(blob)>MAX_ARCHIVE_BYTES:
        raise ValueError("NSE_ARCHIVE_INVALID")
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        entries=z.infolist()
        if (len(entries)!=1 or entries[0].filename!="BhavCopy_NSE_FO_0_0_0_20251215_F_0000.csv"
                or entries[0].is_dir() or entries[0].file_size>MAX_CSV_BYTES):
            raise ValueError("NSE_ARCHIVE_MEMBER_INVALID")
        raw=z.read(entries[0])
        if len(raw)>MAX_CSV_BYTES:
            raise ValueError("NSE_ARCHIVE_TOO_LARGE")
    reader=csv.DictReader(io.StringIO(raw.decode("utf-8-sig"),newline=""))
    required={"BizDt","TckrSymb","FinInstrmTp","XpryDt","StrkPric","OptnTp",*DATA_KEYS}
    if reader.fieldnames is None or not required.issubset(reader.fieldnames):
        raise ValueError("NSE_ARCHIVE_SCHEMA_INVALID")
    found={}
    for i,row in enumerate(reader):
        if i>=MAX_ROWS or row["BizDt"].strip()!=DAY.isoformat():
            raise ValueError("NSE_ARCHIVE_DATE_OR_SIZE_INVALID")
        if row["TckrSymb"].strip()!="NIFTY" or row["XpryDt"].strip()!=EXPIRY.isoformat():
            continue
        kind=row["FinInstrmTp"].strip()
        if kind=="IDF":
            identity=(EXPIRY,"FUT",Decimal(0))
        elif kind=="IDO":
            side=row["OptnTp"].strip()
            if side not in ("CE","PE"):
                continue
            strike=decimal(row["StrkPric"])
            if strike<=0:
                raise ValueError("NSE_ARCHIVE_STRIKE_INVALID")
            identity=(EXPIRY,side,strike)
        else:
            continue
        if identity in found:
            raise ValueError("NSE_DUPLICATE_IDENTITY")
        found[identity]=tuple(decimal(row[k]) for k in DATA_KEYS)
    return found

def map_contracts(keys,futures,options,*,include_lots=False):
    """Use authenticated Upstox contract identities; never parse strike from key."""
    from fno_private_bulk_worker import _key
    if len(keys)!=MAX_CONTRACTS or len(set(keys))!=MAX_CONTRACTS:
        raise ValueError("DECEMBER_CONTRACT_COHORT_INVALID")
    if not isinstance(futures,list) or not isinstance(options,list):
        raise ValueError("DECEMBER_METADATA_MALFORMED")
    found={}
    lots={}
    def source_lot(item):
        size=item.get("lot_size")
        if type(size) is not int or not 1<=size<=10000:
            raise ValueError("DECEMBER_AUTHENTICATED_LOT_SIZE_INVALID")
        return Decimal(size)
    for item in futures:
        key=_key(item,EXPIRY,"FUT")
        if key in keys:
            if key in found:
                raise ValueError("DECEMBER_METADATA_DUPLICATE")
            found[key]=(EXPIRY,"FUT",Decimal(0))
            if include_lots:
                lots[key]=source_lot(item)
    for item in options:
        for side in ("CE","PE"):
            key=_key(item,EXPIRY,side)
            if key not in keys:
                continue
            try:
                strike=decimal(item["strike_price"])
            except (KeyError,TypeError):
                raise ValueError("DECEMBER_STRIKE_MISSING") from None
            if strike<=0 or key in found:
                raise ValueError("DECEMBER_STRIKE_OR_KEY_INVALID")
            found[key]=(EXPIRY,side,strike)
            if include_lots:
                lots[key]=source_lot(item)
    if len(found)!=MAX_CONTRACTS or len(set(found.values()))!=MAX_CONTRACTS:
        raise ValueError("DECEMBER_CATALOGUE_CROSSWALK_INCOMPLETE")
    if include_lots and len(lots)!=MAX_CONTRACTS:
        raise ValueError("DECEMBER_LOT_SIZE_CROSSWALK_INCOMPLETE")
    return (found,lots) if include_lots else found


def volume_unit_evidence(rows,catalogue,reference,lots):
    """Counts volume-unit hypotheses without exporting any raw observation."""
    if set(catalogue)!=set(lots) or len(catalogue)!=MAX_CONTRACTS:
        raise ValueError("DECEMBER_LOT_COHORT_INVALID")
    counts=Counter()
    for key,stamp,*values in rows:
        if key not in catalogue or len(values)!=6:
            raise ValueError("DECEMBER_UNIT_ROW_INVALID")
        dt=datetime.fromisoformat(stamp)
        if dt.date()!=DAY or (dt.hour,dt.minute) not in ((0,0),(9,15)):
            raise ValueError("DECEMBER_UNIT_TIMESTAMP_INVALID")
        official=reference.get(catalogue[key])
        if official is None:
            continue
        source_volume=decimal(values[4])
        nse_contract_volume=official[4]
        lot=lots[key]
        family="midnight" if dt.hour==0 else "opening"
        counts[family+"LotMetadataChecked"]+=1
        if nse_contract_volume*lot==source_volume:
            counts[family+"NseVolumeTimesLotMatches"]+=1
        if nse_contract_volume==source_volume:
            counts[family+"UnscaledVolumeMatches"]+=1
        if source_volume*lot==nse_contract_volume:
            counts[family+"InverseLotMatches"]+=1
    return {k:counts[k] for k in (
        "midnightLotMetadataChecked","openingLotMetadataChecked",
        "midnightNseVolumeTimesLotMatches","openingNseVolumeTimesLotMatches",
        "midnightUnscaledVolumeMatches","openingUnscaledVolumeMatches",
        "midnightInverseLotMatches","openingInverseLotMatches",
    )}

def compare_one_day(rows,catalogue,reference):
    """Counts only, 23 selected identities, no choice of original candidate."""
    if len(catalogue)!=23 or len(set(catalogue.values()))!=23:
        raise ValueError("DECEMBER_CATALOGUE_INCOMPLETE")
    saved=defaultdict(dict)
    count=Counter()
    for key,stamp,*values in rows:
        if key not in catalogue:
            raise ValueError("DECEMBER_UNEXPECTED_KEY")
        try:
            dt=datetime.fromisoformat(stamp)
        except (TypeError,ValueError):
            raise ValueError("DECEMBER_TIMESTAMP_INVALID") from None
        if (dt.tzinfo is None or dt.utcoffset()!=timedelta(hours=5,minutes=30)
                or dt.date()!=DAY or dt.second!=0 or dt.microsecond!=0
                or (dt.hour,dt.minute) not in ((0,0),(9,15))):
            raise ValueError("DECEMBER_TIMESTAMP_INVALID")
        mark=dt.strftime("%H:%M")
        if mark in saved[key] or len(values)!=6:
            raise ValueError("DECEMBER_DUPLICATE_STAMP_OR_COLUMNS")
        saved[key][mark]=tuple(decimal(x) for x in values)
        count["savedBars"]+=1
    if set(saved)!=set(catalogue):
        raise ValueError("DECEMBER_SAVED_COHORT_INCOMPLETE")
    for key,identity in catalogue.items():
        values=saved[key]
        if "00:00" not in values:
            raise ValueError("DECEMBER_MIDNIGHT_CANDIDATE_MISSING")
        if "09:15" in values:
            count["duplicatePairs"]+=1
            count["conflictingPairs" if values["00:00"]!=values["09:15"]
                  else "identicalPairs"]+=1
        eod=reference.get(identity)
        if eod is None:
            count["noNseContractRow"]+=1
            continue
        count["matchedNseIdentities"]+=1
        exact_mid=values["00:00"]==eod
        exact_open="09:15" in values and values["09:15"]==eod
        if exact_mid:
            count["midnightExactAllSix"]+=1
        if exact_open:
            count["openingExactAllSix"]+=1
        for k,field in enumerate(("O","H","L","C","V","OI")):
            for label,mark in (("midnight","00:00"),("opening","09:15")):
                if mark in values and values[mark][k]==eod[k]:
                    count[label+"Exact"+field]+=1
        if "09:15" not in values:
            count["singleCandidate"]+=1
        elif exact_mid and exact_open:
            count["bothExact"]+=1
        elif exact_mid:
            count["midnightOnlyExact"]+=1
        elif exact_open:
            count["openingOnlyExact"]+=1
        else:
            count["neitherExact"]+=1
    result={name:count[name] for name in (
        "savedBars","duplicatePairs","conflictingPairs","identicalPairs",
        "matchedNseIdentities","noNseContractRow","singleCandidate",
        "midnightExactAllSix","openingExactAllSix","bothExact",
        "midnightOnlyExact","openingOnlyExact","neitherExact",
        *(label+"Exact"+field for label in ("midnight","opening")
          for field in ("O","H","L","C","V","OI")),
    )}
    if (result["matchedNseIdentities"]+result["noNseContractRow"]!=23
            or result["duplicatePairs"]+23>result["savedBars"]):
        raise ValueError("DECEMBER_EOD_COUNTER_INCONSISTENT")
    return result

def main():
    from pathlib import Path
    # Pin the reviewed read-only helper downloaded alongside this script
    # before importing host-installed provider code.
    from fno_private_partial_gaps import read_only
    sys.path.insert(0,"/opt/aitradingsystem-paper/infra/ec2-paper")
    from fno_private_bulk_worker import Discovery,UNDERLYING,grant,ssm_token,today
    grant().require("day",today())
    journal=read_only(Path(ROOT)/"checkpoints.sqlite")
    cache=read_only(Path(ROOT)/"private-research-cache.sqlite")
    try:
        jobs=list(journal.execute(
            "SELECT contract_key,first_day,last_day,state,interval "
            "FROM fno_backfill_jobs WHERE last_day='2025-12-30'"))
        if (len(jobs)!=MAX_CONTRACTS or any(
                state!="DONE" or interval!="day" or first!="2025-12-10"
                for key,first,last,state,interval in jobs)):
            raise ValueError("DECEMBER_PRIVATE_SOURCE_UNEXPECTED")
        keys={row[0] for row in jobs}
        if len(keys)!=MAX_CONTRACTS:
            raise ValueError("DECEMBER_PRIVATE_KEYS_NONUNIQUE")
        rows=list(cache.execute(
            "SELECT contract_key,candle_ts,open_price,high_price,low_price,"
            "close_price,volume,open_interest FROM fno_historical_candles "
            "WHERE source=? AND interval='day' AND substr(candle_ts,1,10)=?"
            " AND contract_key LIKE ?",(SOURCE,DAY.isoformat(),"%-12-2025")))
        if not 23<=len(rows)<=46:
            raise ValueError("DECEMBER_SPOTCHECK_ROW_COHORT_UNEXPECTED")
        token=ssm_token()
        api=Discovery(token)
        query="?instrument_key="+urllib.parse.quote(UNDERLYING,safe="")+"&expiry_date="+EXPIRY.isoformat()
        futures=api.get("/future/contract"+query)
        options=api.get("/option/contract"+query)
        if api.calls!=2:
            raise ValueError("DECEMBER_METADATA_BUDGET_MISMATCH")
        catalogue,lots=map_contracts(keys,futures,options,include_lots=True)
        state,blob=fetch_nse_archive()
        if state!="FETCHED":
            report={"state":state,"metadataGets":2,"archivesFetched":0,
                    "backtestingApproved":False,"dataRightsVerified":False}
        else:
            reference=parse_eod(blob)
            counts=compare_one_day(rows,catalogue,reference)
            counts.update(volume_unit_evidence(rows,catalogue,reference,lots))
            report={"state":"COMPARED","metadataGets":2,"archivesFetched":1,
                    "sampleDays":1,"selectedContracts":23,**counts,
                    "backtestingApproved":False,"dataRightsVerified":False}
        encoded=json.dumps(report,sort_keys=True,separators=(",",":"))
        if len(encoded)>1600:
            raise ValueError("DECEMBER_RECEIPT_TOO_LARGE")
        print(MARKER+encoded,flush=True)
    finally:
        journal.close()
        cache.close()

if __name__=="__main__":
    try:main()
    except (ValueError,OSError,sqlite3.Error,RuntimeError,KeyError,TypeError,
            zipfile.BadZipFile,UnicodeError):
        print("FNO_DEC_NSE_ONE_DAY:BLOCKED_OR_INVALID",flush=True)
        raise SystemExit(2)
