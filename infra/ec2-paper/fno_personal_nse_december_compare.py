#!/usr/bin/env python3
"""Private in-memory personal comparison: official NSE daily F&O vs Upstox cache.

No price, token, instrument key, file or raw NSE row is emitted or written.
Only one December 2025 pilot expiry; bounded source GETs and strict parsing.
"""
from __future__ import annotations
from collections import Counter,defaultdict
from datetime import date,datetime,timedelta
from decimal import Decimal,InvalidOperation
import csv
import io
import json
import re
from pathlib import Path
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT=Path("/opt/aitradingsystem-paper")
CACHE=ROOT/"fno-history/private-research-cache.sqlite"
JOURNAL=ROOT/"fno-history/checkpoints.sqlite"
EXPIRY=date(2025,12,30)
START=date(2025,12,10)
END=EXPIRY
SOURCE="upstox-expired-nse-fo"
HOST="nsearchives.nseindia.com"
MARKER="MINTTICK_FNO_NSE_DEC_COMPARE_V1:"
MAX_ARCHIVE=8*1024*1024
MAX_CSV=45*1024*1024

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        return None

def dec(value):
    try:
        x=Decimal(str(value).strip())
        if not x.is_finite() or x<0:
            raise ValueError("BAD_NUMBER")
        return x
    except (InvalidOperation,TypeError):
        raise ValueError("BAD_NUMBER") from None

def source_url(day):
    if not START<=day<=END or day.weekday()>4:
        raise ValueError("INVALID_PROBE_DAY")
    return "https://"+HOST+"/content/fo/BhavCopy_NSE_FO_0_0_0_"+day.strftime("%Y%m%d")+"_F_0000.csv.zip"

def parse_udiff(blob,day,include_identity=False):
    if not blob.startswith(b"PK\x03\x04") or len(blob)>MAX_ARCHIVE:
        raise ValueError("INVALID_NSE_ZIP")
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        members=z.infolist()
        if (len(members)!=1 or members[0].is_dir()
          or members[0].filename!="BhavCopy_NSE_FO_0_0_0_"+day.strftime("%Y%m%d")+"_F_0000.csv"
          or members[0].file_size>MAX_CSV):
            raise ValueError("INVALID_NSE_ZIP_MEMBER")
        raw=z.read(members[0])
    if len(raw)>MAX_CSV:
        raise ValueError("NSE_CSV_TOO_LARGE")
    data=csv.DictReader(io.StringIO(raw.decode("utf-8-sig"),newline=""))
    required={"BizDt","TckrSymb","FinInstrmTp","XpryDt","StrkPric","OptnTp",
              "OpnPric","HghPric","LwPric","ClsPric","TtlTradgVol","OpnIntrst","NewBrdLotQty"}
    if include_identity:
        required.update(("FinInstrmId","ChngInOpnIntrst"))
    if data.fieldnames is None or not required.issubset(data.fieldnames):
        raise ValueError("NSE_SCHEMA_MISMATCH")
    rows={}
    for n,row in enumerate(data):
        if n>=180000:
            raise ValueError("NSE_TOO_MANY_RECORDS")
        if row["BizDt"]!=day.isoformat():
            raise ValueError("NSE_INCORRECT_DAY")
        if row["TckrSymb"].strip()!="NIFTY" or row["FinInstrmTp"] not in ("IDF","IDO"):
            continue
        if date.fromisoformat(row["XpryDt"])!=EXPIRY:
            continue
        kind="FUT" if row["FinInstrmTp"]=="IDF" else row["OptnTp"].strip()
        if include_identity and kind!="FUT":
            continue
        if kind not in ("FUT","CE","PE"):
            raise ValueError("NSE_UNSUPPORTED_KIND")
        strike=Decimal(0) if kind=="FUT" else dec(row["StrkPric"])
        identity=(kind,strike,day)
        values=tuple(dec(row[k]) for k in (
            "OpnPric","HghPric","LwPric","ClsPric","TtlTradgVol","OpnIntrst"))
        lot=dec(row["NewBrdLotQty"])
        if lot<=0 or lot>10000:
            raise ValueError("NSE_BAD_LOT_SIZE")
        values=values+(lot,)
        if include_identity:
            instrument=row["FinInstrmId"].strip()
            change=Decimal(row["ChngInOpnIntrst"].strip())
            if not instrument.isdecimal() or not change.is_finite() or change!=change.to_integral_value():
                raise ValueError("NSE_INVALID_CONTRACT_OR_OI_CHANGE")
            values=values+(instrument,change)
        if identity in rows:
            raise ValueError("NSE_DUPLICATE_CONTRACT_ROW")
        rows[identity]=values
    return rows

def fetch_day(day,opener):
    url=source_url(day)
    req=urllib.request.Request(url,method="GET",headers={
        "Accept":"application/zip","User-Agent":"MintTick-Personal-Research/1.0"})
    try:
        with opener.open(req,timeout=30) as res:
            if res.status!=200 or res.url!=url:
                raise ValueError("NSE_UNEXPECTED_RESPONSE")
            length=res.headers.get("Content-Length")
            if length and (not length.isdecimal() or int(length)>MAX_ARCHIVE):
                raise ValueError("NSE_ZIP_TOO_LARGE")
            content=res.read(MAX_ARCHIVE+1)
    except urllib.error.HTTPError as e:
        if e.code==404:
            return None
        raise ValueError("NSE_SOURCE_REJECTED") from None
    except (urllib.error.URLError,TimeoutError,OSError):
        raise ValueError("NSE_TRANSPORT_UNAVAILABLE") from None
    return parse_udiff(content,day)

def metadata_map(futures,options):
    """Use explicit provider contract metadata, never decode identity from key."""
    from fno_private_bulk_worker import _key,contracts
    selected=set(contracts(futures,options,EXPIRY))
    if len(selected)!=23:
        raise ValueError("INVALID_UPSTOX_SOURCE_SET")
    results={}
    for kind,rows in (("FUT",futures),("CE",options),("PE",options)):
        for row in rows:
            key=_key(row,EXPIRY,kind)
            if key not in selected:
                continue
            strike=Decimal(0) if kind=="FUT" else dec(row["strike_price"])
            if key in results:
                raise ValueError("AMBIGUOUS_CONTRACT_KEY")
            results[key]=(kind,strike)
    if len(results)!=23 or len(set(results.values()))!=23:
        raise ValueError("INVALID_CONTRACT_MAPPING")
    return results

def cache_rows(journal,cache,mapping):
    jobs=list(journal.execute("""SELECT contract_key,first_day,last_day,state
           FROM fno_backfill_jobs WHERE interval='day'
             AND last_day='2025-12-30'"""))
    if (len(jobs)!=23 or set(x[0] for x in jobs)!=set(mapping)
        or any((first,last,state)!=("2025-12-10","2025-12-30","DONE")
               for key,first,last,state in jobs)):
        raise ValueError("CHECKPOINT_MAPPING_CHANGED")
    rows=list(cache.execute("""SELECT contract_key,candle_ts,
           open_price,high_price,low_price,close_price,volume,open_interest
           FROM fno_historical_candles
           WHERE source=? AND interval='day'
             AND contract_key LIKE '%-12-2025'""",(SOURCE,)))
    if len(rows)!=643:
        raise ValueError("DECEMBER_CACHE_COHORT_CHANGED")
    by_day=defaultdict(list)
    for key,stamp,*raw_values in rows:
        if key not in mapping:
            raise ValueError("EXTRA_CONTRACT")
        dt=datetime.fromisoformat(stamp)
        if (dt.tzinfo is None or dt.utcoffset()!=timedelta(hours=5,minutes=30)
            or not START<=dt.date()<=END):
            raise ValueError("UNEXPECTED_CANDLE_TIMESTAMP")
        if dt.strftime("%H:%M") not in ("00:00","09:15"):
            raise ValueError("UNEXPECTED_CANDLE_TIME")
        values=tuple(dec(x) for x in raw_values)
        by_day[(key,dt.date())].append((dt.strftime("%H:%M"),values))
    if len(by_day)!=322:
        raise ValueError("DECEMBER_COHORT_CHANGED")
    return by_day

def compare(by_day,mapping,nse):
    counts=Counter()
    for (key,day),candles in by_day.items():
        kind,strike=mapping[key]
        source=nse.get((kind,strike,day))
        if len(candles)==2: counts["duplicates"]+=1
        if source is None:
            counts["missingNSEContractDays"]+=1
            continue
        if len(source)!=7:
            raise ValueError("NSE_MISSING_LOT_SIZE")
        eod=source[:6]
        lot=source[6]
        counts["matchedNSEContractDays"]+=1
        for clock,values in candles:
            prefix="midnight" if clock=="00:00" else "opening"
            if values==eod:
                counts[prefix+"AllSixMatch"]+=1
            if values[:4]==eod[:4]:
                counts[prefix+"OHLCMatch"]+=1
            if values[3]==eod[3]:
                counts[prefix+"CloseMatch"]+=1
            if values[4]==eod[4]:
                counts[prefix+"VolumeMatch"]+=1
            if eod[4]>0 and values[4]==eod[4]*lot:
                counts[prefix+"NormalizedVolumeMatch"]+=1
            if prefix=="midnight":
                for i,field in enumerate(("Open","High","Low")):
                    if values[i]==eod[i]: counts["midnight"+field+"Match"]+=1
            if values[5]==eod[5]:
                counts[prefix+"OIMatch"]+=1
        if len(candles)==2:
            a=[v for t,v in candles if t=="00:00"]
            b=[v for t,v in candles if t=="09:15"]
            if len(a)!=1 or len(b)!=1:
                raise ValueError("DUPLICATE_TIME_OR_MISSING_MIDNIGHT")
            if a[0]!=b[0]:
                counts["providerConflicts"]+=1
            else:
                counts["providerIdenticalPairs"]+=1
            if a[0]==eod and b[0]!=eod: counts["midnightOnlyFullMatch"]+=1
            elif a[0]!=eod and b[0]==eod: counts["openingOnlyFullMatch"]+=1
            elif a[0]==eod and b[0]==eod: counts["bothFullMatch"]+=1
            else: counts["neitherFullMatch"]+=1
    expected={"duplicates":321,"providerConflicts":316,"providerIdenticalPairs":5}
    if any(counts[k]!=v for k,v in expected.items()):
        raise ValueError("DUPLICATE_RECONCILIATION_CHANGED")
    keys=[
        "duplicates","providerConflicts","providerIdenticalPairs",
        "matchedNSEContractDays","missingNSEContractDays",
        "midnightAllSixMatch","openingAllSixMatch",
        "midnightOHLCMatch","openingOHLCMatch",
        "midnightCloseMatch","openingCloseMatch",
        "midnightVolumeMatch","openingVolumeMatch",
        "midnightNormalizedVolumeMatch","openingNormalizedVolumeMatch",
        "midnightOpenMatch","midnightHighMatch","midnightLowMatch",
        "midnightOIMatch","openingOIMatch",
        "midnightOnlyFullMatch","openingOnlyFullMatch",
        "bothFullMatch","neitherFullMatch",
    ]
    return {k:counts[k] for k in keys}


def canonical_personal_rows(non_dec_rows, by_day, mapping, nse):
    """Build source-attributed unique daily candles in memory, never export them."""
    merged={}
    for key,stamp,op,high,low,close,volume,oi in non_dec_rows:
        if not re.fullmatch(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}",key):
            raise ValueError("INVALID_RESEARCH_KEY")
        dt=datetime.fromisoformat(stamp)
        expiry=datetime.strptime(key.rsplit("|",1)[1],"%d-%m-%Y").date()
        if (expiry.year,expiry.month)==(2025,12):
            raise ValueError("DECEMBER_WRONG_PROVENANCE")
        if (dt.tzinfo is None or dt.utcoffset()!=timedelta(hours=5,minutes=30)
            or dt.time().hour!=0 or dt.time().minute!=0 or dt.time().second!=0
            or dt.date()>expiry):
            raise ValueError("INVALID_RESEARCH_DAY")
        identity=(key,dt.date())
        if identity in merged:raise ValueError("DUPLICATE_NON_DECEMBER_DAY")
        vals=tuple(dec(x) for x in (op,high,low,close,volume,oi))
        if min(vals[:4])<=0 or vals[2]>min(vals[0],vals[3]) or vals[1]<max(vals[0],vals[3]):
            raise ValueError("INVALID_NON_DECEMBER_OHLC")
        merged[identity]=(stamp,vals,"UPSTOX_PRIVATE")
    other=len(merged)
    excluded=0
    for (key,day),original in by_day.items():
        if key not in mapping or (key,day) in merged:
            raise ValueError("MISMATCHED_RESEARCH_CONTRACT")
        kind,strike=mapping[key]
        source=nse.get((kind,strike,day))
        if source is None or len(source)!=7:
            raise ValueError("MISSING_EXCHANGE_REFERENCE_DAY")
        prices=source[:4]
        units=source[4]*source[6]  # NSE contracts converted to lot-sized quantity
        oi=source[5]
        if (min(prices)<=0 or prices[2]>min(prices[0],prices[3])
            or prices[1]<max(prices[0],prices[3])
            or units!=units.to_integral_value() or units>Decimal(1_000_000_000_000)):
            # Never synthesize OHLC or silently replace the source day.
            excluded+=1
            continue
        merged[(key,day)]=(day.isoformat()+"T00:00:00+05:30",
                           prices+(units,oi),"NSE_PUBLIC_EOD")
    return merged,{"total":len(merged),"upstoxMonths":other,
                   "nseDecember":len(by_day)-excluded,"excludedDecember":excluded,
                   "uniqueDailyKeys":len(merged)}

def ro(path):
    if not path.is_file() or path.is_symlink():
        raise ValueError("PRIVATE_CACHE_NOT_AVAILABLE")
    db=sqlite3.connect("file:"+urllib.parse.quote(str(path),safe="/")+"?mode=ro",
                       uri=True,timeout=15)
    db.execute("PRAGMA query_only=ON")
    return db

def run(on_canonical=None):
    sys.path.insert(0,str(ROOT/"infra/ec2-paper"))
    from fno_private_bulk_worker import Discovery,grant,ssm_token,today,UNDERLYING
    grant().require("day",today())
    journal=ro(JOURNAL);cache=ro(CACHE)
    try:
        provider=Discovery(ssm_token())
        underlying=urllib.parse.quote(UNDERLYING,safe="")
        suffix="?instrument_key="+underlying+"&expiry_date="+EXPIRY.isoformat()
        futures=provider.get("/future/contract"+suffix)
        options=provider.get("/option/contract"+suffix)
        mapping=metadata_map(futures,options)
        by_day=cache_rows(journal,cache,mapping)
        nse={}
        scanned=0;retrieved=0;missing=0
        opener=urllib.request.build_opener(NoRedirect())
        d=START
        while d<=END:
            if d.weekday()<5:
                if scanned:
                    time.sleep(0.75)
                scanned+=1
                rows=fetch_day(d,opener)
                if rows is None:
                    missing+=1
                else:
                    retrieved+=1
                    for (kind,strike,day),vals in rows.items():
                        if (kind,strike) in mapping.values():
                            identity=(kind,strike,day)
                            if identity in nse:
                                raise ValueError("DUPLICATE_NSE_SOURCE_ID")
                            nse[identity]=vals
            d+=timedelta(days=1)
        if scanned!=15 or provider.calls!=2:
            raise ValueError("SOURCE_REQUEST_COUNT_CHANGED")
        metrics=compare(by_day,mapping,nse)
        other=list(cache.execute("""SELECT contract_key,candle_ts,open_price,
                high_price,low_price,close_price,volume,open_interest
                FROM fno_historical_candles
                WHERE source=? AND interval='day'
                  AND contract_key NOT LIKE '%-12-2025'""",(SOURCE,)))
        if len(other)!=3608:
            raise ValueError("NON_DECEMBER_COHORT_CHANGED")
        canonical,research=canonical_personal_rows(other,by_day,mapping,nse)
        if (research["upstoxMonths"]!=3608
            or research["nseDecember"]+research["excludedDecember"]!=322
            or research["excludedDecember"]>1
            or research["total"]!=3930-research["excludedDecember"]):
            raise ValueError("UNEXPECTED_RESEARCH_RESULT")
        saved = on_canonical(canonical,research,cache) if on_canonical else None
        del canonical
        report={
            "status":"CROSSCHECK_COMPLETE" if metrics["missingNSEContractDays"]==0 else "CROSSCHECK_PARTIAL",
            "scope":"DEC_2025_NIFTY_SELECTED_23_CONTRACTS",
            "nseArchivesRequested":scanned,"nseArchivesFetched":retrieved,
            "nseArchiveDaysNotFound":missing,
            "providerMetadataGets":provider.calls,
            "privateUpstoxCandleRows":643,"privateContractDays":322,
            "priceRowsExported":False,"privateDataPersisted":False,
            "backtestingApproved":False,"metrics":metrics,
            "personalResearchDaily":research,"researchDataPersisted":saved is not None,
        }
        text=json.dumps(report,sort_keys=True,separators=(",",":"))
        if len(text)>2000:
            raise ValueError("AGGREGATE_REPORT_TOO_LARGE")
        print(MARKER+text,flush=True)
        return saved
    finally:
        journal.close();cache.close()

if __name__=="__main__":
    try:run()
    except (ValueError,TypeError,KeyError,sqlite3.Error,OSError,zipfile.BadZipFile,UnicodeError):
        print("FNO_NSE_DEC_COMPARE:SOURCE_UNAVAILABLE_OR_MISMATCH",flush=True)
        raise SystemExit(2)
