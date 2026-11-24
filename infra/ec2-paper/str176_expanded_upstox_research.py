#!/usr/bin/env python3
"""STR-176 bounded authenticated futures day-history 90-day research pilot.

DATA USE: owner-personal authorized scope only, existing SSM credentials,
read-only Upstox expired-instruments GETs. No raw provider data or keys leave
the owner PAPER EC2. Nothing is persisted, no orders and no rights changed.
A retrospective next-open assumption is NOT historical first-known proof or
independent market E2E. This deliberately does not tune STR-176 thresholds.
"""
from __future__ import annotations

from collections import Counter
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
import base64
import gzip
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.parse

REGION="ap-south-1"
ACCOUNT="098859530205"
INSTANCE="i-09c01b470fb39b85a"
REQUEST=Path("infra/ec2-paper/requests/str176-expanded-upstox-readonly.json")
SOURCE_MARKER="MINTTICK_STR176_EXTENDED_SOURCE_RESEARCH:"
EXACT={
  "kind":"minttick-str176-extended-upstox-readonly-v1",
  "account":ACCOUNT,"region":REGION,"instance_id":INSTANCE,
  "max_provider_gets":49,"source_days":90,
  "no_raw_data_export":True,"no_db_writes":True,"no_orders":True,
  "independent_historical_e2e":False
}
KEY_RE=re.compile(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}\Z")
SOURCE="UPSTOX_AUTHENTICATED_EXPIRED_DAY"
MAX_FUTURES=12
SOURCE_DAYS=90
PER_GET_DAYS=30
MAX_BARS=1080
FIXED_FRICTION_BPS=30
SAFE_STATE=frozenset((
    "RESEARCH_EXTENDED_EOD_NOT_CERTIFIED",
    "SOURCE_FUTURES_COHORTS_INCOMPLETE",
))

def parsed_price(v):
    try: n=Decimal(str(v))
    except (ValueError,InvalidOperation,TypeError):
        raise ValueError("ORIGINAL_SOURCE_PRICE_INVALID") from None
    if not n.is_finite() or n<=0 or n.as_tuple().exponent<-8 or n>Decimal("100000000"):
        raise ValueError("ORIGINAL_SOURCE_PRICE_INVALID")
    return n

def expiries_from_provider(rows, reference_date):
    if not isinstance(rows,list) or not 1<=len(rows)<=250:
        raise ValueError("EXPIRED_UNIVERSE_INVALID")
    months={}
    for v in rows:
        if not isinstance(v,str):raise ValueError("EXPIRED_UNIVERSE_INVALID")
        d=date.fromisoformat(v)
        if d>=reference_date.replace(day=1):continue
        key=(d.year,d.month)
        months[key]=max(d,months.get(key,d))
    dates=sorted(months.values())[-MAX_FUTURES:]
    if len(dates)!=MAX_FUTURES:
        raise ValueError("TWELVE_ACTUAL_MONTHLY_EXPIRIES_REQUIRED")
    return dates

def chosen_future(rows, expiry, underlying):
    if not isinstance(rows,list) or not 1<=len(rows)<=200:
        raise ValueError("UPSTOX_ORIGINAL_FUTURES_MISSING")
    candidates=[]
    for row in rows:
        if not isinstance(row,dict):
            raise ValueError("UPSTOX_ORIGINAL_FUTURES_INVALID")
        if (row.get("segment")!="NSE_FO" or row.get("exchange")!="NSE"
                or row.get("underlying_key")!=underlying
                or row.get("instrument_type")!="FUT"
                or row.get("expiry")!=expiry.isoformat()):
            continue
        key=row.get("instrument_key")
        lot=row.get("lot_size")
        if (not isinstance(key,str) or not KEY_RE.fullmatch(key)
                or key.rsplit("|",1)[1]!=expiry.strftime("%d-%m-%Y")
                or type(lot) is not int or not 1<=lot<=100000):
            raise ValueError("UPSTOX_ORIGINAL_FUTURES_INVALID")
        candidates.append((key,lot))
    candidates.sort()
    if not candidates or len(set(candidates))!=len(candidates):
        raise ValueError("UPSTOX_ORIGINAL_FUTURES_MISSING_OR_AMBIGUOUS")
    return candidates[0]

def decode_expired_chunk(payload, first, last):
    # Preserve the API's documented data.candles contract. Never silently
    # reinterpret a missing/unsupported response as an empty candle history.
    if not isinstance(payload,dict):
        raise ValueError("HISTORICAL_DATA_OBJECT_MISSING")
    if not isinstance(payload.get("candles"),list):
        raise ValueError("HISTORICAL_CANDLE_ARRAY_MISSING")
    # The real source exceeded 30 records for a 30-calendar-day query.
    # Count is not itself sufficient to conclude invalid history: inspect
    # individual source dates and duplicate daily sessions before rejection.
    # Bound total parsed records separately against response abuse.
    if len(payload["candles"])>PER_GET_DAYS*4:
        raise ValueError("HISTORICAL_CANDLE_WINDOW_OVERSIZED")
    result=[]
    seen=set()
    for row in payload["candles"]:
        if not isinstance(row,list) or len(row)!=7:
            raise ValueError("INVALID_SOURCE_CANDLE_LENGTH")
        stamp,op,hi,lo,close,vol,oi=row
        if not isinstance(stamp,str):
            raise ValueError("INVALID_SOURCE_SESSION")
        try:dt=datetime.fromisoformat(stamp)
        except ValueError:raise ValueError("INVALID_SOURCE_SESSION") from None
        if dt.utcoffset()!=timedelta(hours=5,minutes=30):
            raise ValueError("INVALID_SOURCE_SESSION")
        if not first<=dt.date()<=last:
            raise ValueError("HISTORICAL_BAR_OUTSIDE_REQUEST")
        if dt.hour or dt.minute or dt.second or dt.microsecond:
            raise ValueError("HISTORICAL_NONCANONICAL_DAILY_TIME")
        if dt.date() in seen:
            raise ValueError("HISTORICAL_DUPLICATE_SESSION_DAY")
        if dt.date().weekday()>=5:
            raise ValueError("INVALID_SOURCE_SESSION")
        seen.add(dt.date())
        if (type(vol) is not int or vol<0 or vol>10**12
                or type(oi) is not int or oi<0 or oi>10**12):
            raise ValueError("INVALID_SOURCE_QUANTITY")
        o,h,l,c=(parsed_price(x) for x in (op,hi,lo,close))
        if l>min(o,c) or h<max(o,c) or l>h:
            raise ValueError("ORIGINAL_SOURCE_OHLC_INVALID")
        result.append((dt.date(),o,h,l,c,vol,oi,SOURCE))
    return result

def retrieve_futures(expiries, underlying, fetch, *, max_requests=49):
    """fetch: trusted Upstox Discovery.get, gives parsed provider original data.

    The GET request construction is bounded by 90 calendar days (3 x 30)
    and comes from externally authenticated expired-contract metadata only.
    """
    if len(expiries)!=MAX_FUTURES or type(max_requests) is not int or max_requests!=49:
        raise ValueError("SOURCE_REQUEST_SCOPE_INVALID")
    if not isinstance(underlying,str) or underlying!="NSE_INDEX|Nifty 50":
        raise ValueError("SOURCE_UNDERLYING_SCOPE_INVALID")
    universe={}
    by_expiry={}
    metadata_requests=0
    history_requests=0
    for expiry in expiries:
        path="/future/contract?instrument_key="+urllib.parse.quote(underlying,safe="")
        path+="&expiry_date="+expiry.isoformat()
        key,lot=chosen_future(fetch(path),expiry,underlying)
        metadata_requests+=1
        rows=[]
        seen=set()
        first=expiry-timedelta(days=SOURCE_DAYS-1)
        for k in range(3):
            start=first+timedelta(days=k*PER_GET_DAYS)
            end=min(start+timedelta(days=PER_GET_DAYS-1),expiry)
            request=("/historical-candle/"
                     +urllib.parse.quote(key,safe="")
                     +"/day/"+end.isoformat()+"/"+start.isoformat())
            payload=fetch(request)
            history_requests+=1
            decoded=decode_expired_chunk(payload,start,end)
            for r in decoded:
                if r[0] in seen:
                    raise ValueError("DUPLICATE_SOURCE_SESSION_ACROSS_REQUESTS")
                seen.add(r[0])
                rows.append(r)
        rows.sort(key=lambda item:item[0])
        if len(rows)>SOURCE_DAYS or sum(1 for r in rows if r[-1]!=SOURCE):
            raise ValueError("UNVERIFIED_SOURCE_SCOPE")
        universe[key]=rows
        by_expiry[expiry]=(key,lot)
    if (metadata_requests!=12 or history_requests!=36
            or 1+metadata_requests+history_requests!=max_requests
            or len(universe)!=MAX_FUTURES):
        raise ValueError("UPSTOX_SOURCE_REQUEST_COUNT_CHANGED")
    return universe,by_expiry,metadata_requests,history_requests

def bps(num,denom):
    return (num/denom*Decimal(10000)) if denom>0 else Decimal(0)

def signal(window,expiry,as_of):
    """Explicitly pinned independent STR-176 EOD reference signal.
    Uses 7 completed prior sessions; exit/entry price never enters here.
    """
    if len(window)!=7:return "BLOCKED"
    days=[r[0] for r in window]
    if days!=sorted(set(days)) or any(r[6]<=0 or r[5]<0 for r in window):
        return "BLOCKED"
    if not all(1<=(b-a).days<=5 for a,b in zip(days,days[1:])):
        return "BLOCKED"
    if not 7<=(expiry-as_of).days<=120:
        return "BLOCKED"
    if not 1<=(as_of-days[-1]).days<=5:
        return "BLOCKED"
    for i,r in enumerate(window):
        if bps(r[2]-r[3],r[4])>800:return "BLOCKED"
        if i and abs(bps(r[4]-window[i-1][4],window[i-1][4]))>2500:
            return "BLOCKED"
    h=max(r[2] for r in window[:6])
    l=min(r[3] for r in window[:6])
    last=window[-1]
    up=bps(last[4]-h,h)
    down=bps(l-last[4],l)
    baseline=sum(r[5] for r in window[:3])
    recent=sum(r[5] for r in window[4:])
    if baseline<=0:return "BLOCKED"
    lift=bps(Decimal(recent),Decimal(baseline))
    participation=bps(Decimal(last[5]),Decimal(last[6]))
    rises=sum(window[i][4]>window[i-1][4] for i in range(4,7))
    falls=sum(window[i][4]<window[i-1][4] for i in range(4,7))
    if lift<11000 or participation<2000:return "NO_SIGNAL"
    if up>=100 and rises>=2 and falls==0 and up-20>30:return "LONG"
    if down>=100 and falls>=2 and rises==0 and down-20>30:return "SHORT"
    return "NO_SIGNAL"

def evaluate(universe,metadata,cost_bps):
    if cost_bps not in (20,30,50) or len(metadata)!=12:
        raise ValueError("RESEARCH_SCENARIO_INVALID")
    split=sorted(metadata)[8]
    counts=Counter()
    train_net=oos_net=equity=peak=drawdown=0
    for expiry in sorted(metadata):
        key,lot=metadata[expiry]
        rows=universe.get(key)
        if rows is None or not 9<=len(rows)<=90:
            counts["insufficientFuturesCohorts"]+=1
            continue
        counts["eligibleFuturesCohorts"]+=1
        counts["sourceFuturesRows"]+=len(rows)
        latest_exit=None
        for i in range(6,len(rows)-2):
            decision_day=rows[i+1][0]
            outcome=signal(rows[i-6:i+1],expiry,decision_day)
            counts["decisionWindows"]+=1
            if outcome=="BLOCKED":
                counts["blockedWindows"]+=1
                continue
            counts["evaluatedWindows"]+=1
            if outcome=="NO_SIGNAL":
                counts["noSignalWindows"]+=1
                continue
            counts["candidateWindows"]+=1
            entry_date=rows[i+1][0]
            exit_date=rows[i+2][0]
            if latest_exit is not None and entry_date<=latest_exit:
                counts["overlappingCandidatesSkipped"]+=1
                continue
            latest_exit=exit_date
            entry_price=rows[i+1][1]
            exit_price=rows[i+2][1]
            direction=1 if outcome=="LONG" else -1
            gross=((exit_price-entry_price)*lot*100*direction).quantize(
                Decimal(1),rounding=ROUND_HALF_UP)
            notional=(entry_price+exit_price)/2*lot*100
            costs=(notional*cost_bps/10000).quantize(
                Decimal(1),rounding=ROUND_HALF_UP)
            net=int(gross)-int(costs)
            counts["modeledTrades"]+=1
            if expiry<split:
                counts["trainTrades"]+=1
                train_net+=net
            else:
                counts["oosTrades"]+=1
                oos_net+=net
                equity+=net
                peak=max(peak,equity)
                drawdown=max(drawdown,peak-equity)
                if net<0:counts["oosLosses"]+=1
    state=("SOURCE_FUTURES_COHORTS_INCOMPLETE"
           if counts["eligibleFuturesCohorts"]!=12 else
           "RESEARCH_EXTENDED_EOD_NOT_CERTIFIED")
    return {
        "state":state,"sourceFuturesBars":counts["sourceFuturesRows"],
        "eligibleFuturesCohorts":counts["eligibleFuturesCohorts"],
        "decisionWindows":counts["decisionWindows"],
        "evaluatedWindows":counts["evaluatedWindows"],
        "blockedWindows":counts["blockedWindows"],
        "candidateWindows":counts["candidateWindows"],
        "overlappingCandidatesSkipped":counts["overlappingCandidatesSkipped"],
        "modeledTrades":counts["modeledTrades"],
        "trainingTrades":counts["trainTrades"],
        "outOfSampleTrades":counts["oosTrades"],
        "outOfSampleLosses":counts["oosLosses"],
        "trainingNetPaise":train_net,"outOfSampleNetPaise":oos_net,
        "outOfSampleMaxDrawdownPaise":drawdown,
    }

def research(fetch, reference_date):
    expiry_info=fetch("/expiries?instrument_key="
                       +urllib.parse.quote("NSE_INDEX|Nifty 50",safe=""))
    expiry_dates=expiries_from_provider(expiry_info,reference_date)
    source,metadata,meta_gets,hist_gets=retrieve_futures(
        expiry_dates,"NSE_INDEX|Nifty 50",fetch)
    primary=evaluate(source,metadata,FIXED_FRICTION_BPS)
    cheaper=evaluate(source,metadata,20)
    stress=evaluate(source,metadata,50)
    if (any(v["decisionWindows"]!=primary["decisionWindows"] or
            v["candidateWindows"]!=primary["candidateWindows"] for v in
            (cheaper,stress)) or primary["sourceFuturesBars"]>MAX_BARS):
        raise ValueError("RESEARCH_SIGNAL_COHORT_DRIFT")
    return {
        **primary, "providerGets":1+meta_gets+hist_gets,
        "metadataGets":1+meta_gets,"historyGets":hist_gets,
        "sourceDayRange":SOURCE_DAYS,"frictionBps":FIXED_FRICTION_BPS,
        "oosNetAt20BpsPaise":cheaper["outOfSampleNetPaise"],
        "oosNetAt50BpsPaise":stress["outOfSampleNetPaise"],
        "authenticSourceOriginalFirstPublishedAtVerified":False,
        "sourceCaptureType":"AUTHENTICATED_RECEIVED_NOW_NOT_HISTORICAL_FIRST_KNOWN",
        "independentHistoricalE2ECertified":False,
        "certifiedStrategies":0,"orders":False,"writes":False,
    }

def safe_receipt(r):
    if (type(r) is not dict or r.get("state") not in SAFE_STATE
            or r.get("providerGets")!=49
            or r.get("metadataGets")!=13 or r.get("historyGets")!=36
            or r.get("frictionBps")!=30
            or r.get("sourceDayRange")!=90
            or r.get("sourceCaptureType")!=
              "AUTHENTICATED_RECEIVED_NOW_NOT_HISTORICAL_FIRST_KNOWN"
            or r.get("independentHistoricalE2ECertified") is not False
            or r.get("authenticSourceOriginalFirstPublishedAtVerified") is not False
            or r.get("certifiedStrategies")!=0
            or r.get("orders") is not False or r.get("writes") is not False):
        raise ValueError("UNVERIFIED_EXTENDED_RESEARCH_RECEIPT")
    for k in ("sourceFuturesBars","eligibleFuturesCohorts",
              "decisionWindows","evaluatedWindows","blockedWindows",
              "candidateWindows","overlappingCandidatesSkipped",
              "modeledTrades","trainingTrades","outOfSampleTrades",
              "outOfSampleLosses"):
        if type(r.get(k)) is not int or not 0<=r[k]<=MAX_BARS:
            raise ValueError("UNVERIFIED_EXTENDED_RESEARCH_RECEIPT")
    if (r["evaluatedWindows"]+r["blockedWindows"]!=r["decisionWindows"]
            or r["trainingTrades"]+r["outOfSampleTrades"]!=r["modeledTrades"]
            or r["modeledTrades"]>r["candidateWindows"]
            or r["eligibleFuturesCohorts"]>MAX_FUTURES):
        raise ValueError("UNVERIFIED_EXTENDED_RESEARCH_RECEIPT")
    for k in ("trainingNetPaise","outOfSampleNetPaise",
              "outOfSampleMaxDrawdownPaise","oosNetAt20BpsPaise",
              "oosNetAt50BpsPaise"):
        if type(r.get(k)) is not int or abs(r[k])>10**14:
            raise ValueError("UNVERIFIED_EXTENDED_RESEARCH_RECEIPT")
    return r

# Only these literal reason IDs may leave the private host. Unrecognized
# exception text (including URLs, keys, raw source JSON or credentials) never
# leaves the SSM boundary. Blocked/error reports cannot certify E2E.
SAFE_BLOCK_CODES=frozenset((
  "EXPIRED_UNIVERSE_INVALID",
  "TWELVE_ACTUAL_MONTHLY_EXPIRIES_REQUIRED",
  "UPSTOX_ORIGINAL_FUTURES_INVALID",
  "UPSTOX_ORIGINAL_FUTURES_MISSING",
  "UPSTOX_ORIGINAL_FUTURES_MISSING_OR_AMBIGUOUS",
  "INVALID_SOURCE_CANDLE_LENGTH",
  "INVALID_SOURCE_SESSION",
  "INVALID_SOURCE_QUANTITY",
  "UNVERIFIED_AUTHENTICATED_CANDLES",
  "HISTORICAL_DATA_OBJECT_MISSING",
  "HISTORICAL_CANDLE_ARRAY_MISSING",
  "HISTORICAL_CANDLE_WINDOW_OVERSIZED",
  "HISTORICAL_BAR_OUTSIDE_REQUEST",
  "HISTORICAL_NONCANONICAL_DAILY_TIME",
  "HISTORICAL_DUPLICATE_SESSION_DAY",
  "ORIGINAL_SOURCE_PRICE_INVALID",
  "ORIGINAL_SOURCE_OHLC_INVALID",
  "DUPLICATE_SOURCE_SESSION_ACROSS_REQUESTS",
  "UNVERIFIED_SOURCE_SCOPE",
  "UPSTOX_SOURCE_REQUEST_COUNT_CHANGED",
  "SOURCE_REQUEST_SCOPE_INVALID",
  "SOURCE_UNDERLYING_SCOPE_INVALID",
  "OWNER_UNDERLYING_INVALID",
  "RESEARCH_SIGNAL_COHORT_DRIFT",
  "UNVERIFIED_EXTENDED_RESEARCH_RECEIPT",
  "PROVIDER_GET_COUNT_INVALID",
  "OWNER_PRIVATE_FNO_SCOPE_MISSING_OR_EXPIRED",
  "FNO_DATA_RETENTION_NOT_AUTHORIZED",
  "UPSTOX_TOKEN_UNAVAILABLE",
  "FNO_DISCOVERY_AUTH_REJECTED",
  "FNO_DISCOVERY_RATE_LIMITED",
  "FNO_DISCOVERY_PROVIDER_REJECTED",
  "FNO_DISCOVERY_INVALID_RESPONSE",
  "FNO_DISCOVERY_TRANSPORT",
  "FNO_DISCOVERY_TOO_LARGE",
  "FNO_DISCOVERY_INVALID_JSON",
  "FNO_REQUEST_BUDGET_EXHAUSTED",
  "INVALID_FNO_DISCOVERY_ROUTE",
  "OWNER_SOURCE_RESEARCH_FAILED",
  "OWNER_SOURCE_RESEARCH_TIMEOUT",
  "OWNER_SSM_STATE_UNKNOWN",
  "EXACT_PRIVATE_HOST_REQUIRED",
  "UNCLASSIFIED_SOURCE_FAILURE"
))

def safe_block_code(exc):
    if (isinstance(exc,(ValueError,RuntimeError,OSError,KeyError,TypeError))
            and len(exc.args)==1 and type(exc.args[0]) is str
            and exc.args[0] in SAFE_BLOCK_CODES):
        return exc.args[0]
    return "UNCLASSIFIED_SOURCE_FAILURE"

def ssm_block_code(stderr):
    if not isinstance(stderr,str) or len(stderr)>8192:
        return "UNCLASSIFIED_SOURCE_FAILURE"
    prefix=SOURCE_MARKER+"BLOCKED:"
    hits=[line[len(prefix):] for line in stderr.splitlines()
          if line.startswith(prefix)]
    if len(hits)==1 and hits[0] in SAFE_BLOCK_CODES:
        return hits[0]
    return "UNCLASSIFIED_SOURCE_FAILURE"

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise ValueError("EXACT_PRIVATE_HOST_REQUIRED")
    sys.path.insert(0,"/opt/aitradingsystem-paper/infra/ec2-paper")
    from fno_private_bulk_worker import (
        Discovery,grant,ssm_token,today,UNDERLYING)
    if UNDERLYING!="NSE_INDEX|Nifty 50":
        raise ValueError("OWNER_UNDERLYING_INVALID")
    grant().require("day",today())
    provider=Discovery(ssm_token())
    output=safe_receipt(research(provider.get,today()))
    if provider.calls!=49:
        raise ValueError("PROVIDER_GET_COUNT_INVALID")
    print(SOURCE_MARKER+json.dumps(output,sort_keys=True,
        separators=(",",":")),flush=True)

def runner():
    if (not REQUEST.is_file() or REQUEST.is_symlink()
            or REQUEST.stat().st_size>=1024
            or json.loads(REQUEST.read_text())!=EXACT):
        raise ValueError("PROTECTED_OWNER_REQUEST_NOT_REVIEWED")
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    verify_aws()
    blob=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if len(blob)>24000 or not re.fullmatch(r"[A-Za-z0-9+/=]+",blob):
        raise ValueError("RUNNER_BOUND_INVALID")
    command=("set -eu\numask 077\nprintf '%s' '"+blob
             +"' | base64 -d | gzip -d | "
             +"PYTHONDONTWRITEBYTECODE=1 python3 - host\n")
    call=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
        "--instance-ids",INSTANCE,"--timeout-seconds","420",
        "--comment","MintTick STR176 90d expired Upstox source read-only research",
        "--parameters",json.dumps({"commands":[command]}))
    cid=(call.get("Command") or {}).get("CommandId")
    if not isinstance(cid,str) or not re.fullmatch(r"[0-9a-f-]{36}",cid):
        raise ValueError("SSM_COMMAND_INVALID")
    for _ in range(110):
        time.sleep(4)
        result=_aws("ssm","get-command-invocation",
            "--command-id",cid,"--instance-id",INSTANCE)
        state=result.get("Status")
        if state=="Success":
            stdout=result.get("StandardOutputContent") or ""
            if len(stdout)>3200:
                raise ValueError("PRIVATE_RESULT_OUTPUT_TOO_LARGE")
            hits=[x[len(SOURCE_MARKER):] for x in stdout.splitlines()
                  if x.startswith(SOURCE_MARKER)]
            if len(hits)!=1:raise ValueError("SOURCE_RESEARCH_RECEIPT_MISSING")
            report=safe_receipt(json.loads(hits[0]))
            print(SOURCE_MARKER+json.dumps(report,sort_keys=True,
                separators=(",",":")),flush=True)
            return
        if state in ("Failed","TimedOut","Cancelled"):
            # AWS SSM retains the private command stderr; export ONLY a
            # reviewed symbolic reason, never the raw provider exception.
            detail=ssm_block_code(result.get("StandardErrorContent") or "")
            raise ValueError(detail if state=="Failed"
                             else "OWNER_SOURCE_RESEARCH_FAILED")
        if state not in ("Pending","InProgress","Delayed"):
            raise ValueError("OWNER_SSM_STATE_UNKNOWN")
    raise ValueError("OWNER_SOURCE_RESEARCH_TIMEOUT")

if __name__=="__main__":
    try:
        if len(sys.argv)!=2 or sys.argv[1] not in ("host","runner"):
            raise ValueError("RUNNER_MODE_INVALID")
        host() if sys.argv[1]=="host" else runner()
    except (ValueError,RuntimeError,TypeError,KeyError,OSError) as exc:
        print(SOURCE_MARKER+"BLOCKED:"+safe_block_code(exc),
              file=sys.stderr,flush=True)
        raise SystemExit(2)
