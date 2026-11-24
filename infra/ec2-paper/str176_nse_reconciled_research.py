#!/usr/bin/env python3
"""Bounded owner-private NSE replacement of one quarantined research cohort.

Original Upstox observations stay quarantined and unchanged. NSE supplies
all accepted OHLC/OI/volume for the target cohort; midnight observations only
cross-check contract, volume units and OI. No complete-calendar, past first-
publication, independent strategy acceptance or trading authority is implied.
"""
from __future__ import annotations
from collections import defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import str176_expanded_upstox_research as core
import fno_personal_nse_december_compare as nse

TARGET=date(2025,12,30)
FIRST=TARGET-timedelta(days=89)
MAX_NSE_GETS=66
MARKER="MINTTICK_STR176_NSE_RECONCILED_RESEARCH:"
REQUEST=Path("infra/ec2-paper/requests/str176-nse-reconciled-readonly.json")
EXACT={"kind":"minttick-str176-nse-reconciled-readonly-v1",
       "account":core.ACCOUNT,"region":core.REGION,"instance_id":core.INSTANCE,
       "target_expiry":TARGET.isoformat(),"max_upstox_gets":49,
       "max_nse_gets":MAX_NSE_GETS,"no_raw_data_export":True,
       "no_db_writes":True,"no_orders":True,"independent_historical_e2e":False}
MODULES=("str176_expanded_upstox_research","fno_personal_nse_december_compare")
ALLOWED_FIELDS=frozenset("""state sourceFuturesBars eligibleFuturesCohorts decisionWindows
windowOutcomesBySplit purgedPreOosWindows evaluatedWindows blockedWindows candidateWindows
 overlappingCandidatesSkipped modeledTrades trainingTrades outOfSampleTrades outOfSampleLosses
 trainingNetPaise outOfSampleNetPaise outOfSampleMaxDrawdownPaise providerGets exchangeGets
 originalEligibleCohorts originalUpstoxQuarantinedCohorts exchangeReconciledCohorts
 exchangeSourceBars midnightOhlcDisagreements parsedUpstoxSourceSha256 exchangeArchiveSetSha256
 frictionBps oosNetAt20BpsPaise oosNetAt50BpsPaise authenticSourceOriginalFirstPublishedAtVerified
 calendarCompletenessVerified independentHistoricalE2ECertified certifiedStrategies orders writes""".split())


def target_observations(payloads):
    """Index immutable original observations, not accepted/deduplicated candles."""
    observed=defaultdict(dict)
    for payload in payloads:
        if not isinstance(payload,dict) or not isinstance(payload.get("candles"),list):
            raise ValueError("NSE_RECONCILE_RAW_SCOPE_INVALID")
        if len(payload["candles"])>120:
            raise ValueError("NSE_RECONCILE_RAW_SCOPE_INVALID")
        for row in payload["candles"]:
            if not isinstance(row,list) or len(row)!=7 or not isinstance(row[0],str):
                raise ValueError("NSE_RECONCILE_RAW_SCOPE_INVALID")
            try:stamp=datetime.fromisoformat(row[0])
            except ValueError:raise ValueError("NSE_RECONCILE_RAW_SCOPE_INVALID") from None
            clock=(stamp.hour,stamp.minute,stamp.second,stamp.microsecond)
            if (stamp.utcoffset()!=timedelta(hours=5,minutes=30)
                    or not FIRST<=stamp.date()<=TARGET
                    or clock not in ((0,0,0,0),(9,15,0,0))
                    or clock in observed[stamp.date()]):
                raise ValueError("NSE_RECONCILE_RAW_SCOPE_INVALID")
            # Preserve both original observations; do not pick one as EOD truth.
            observed[stamp.date()][clock]=tuple(row)
    if not 9<=len(observed)<=MAX_NSE_GETS or any(
            (0,0,0,0) not in clocks for clocks in observed.values()):
        raise ValueError("NSE_RECONCILE_MIDNIGHT_AUDIT_MISSING")
    return observed


def exchange_cohort(payloads, lot, fetch):
    observed=target_observations(payloads)
    result=[]
    archive_hashes=[]
    mismatch=0
    positive_unit_anchors=0
    for day,clocks in sorted(observed.items()):
        blob=fetch(day)
        if type(blob) is not bytes:
            raise ValueError("NSE_RECONCILE_SOURCE_UNAVAILABLE")
        parsed=nse.parse_udiff(blob,day)
        values=parsed.get(("FUT",Decimal(0),day))
        if values is None or len(values)!=7:
            raise ValueError("NSE_RECONCILE_EXACT_FUTURE_MISSING")
        op,hi,lo,close,volume,oi,source_lot=values
        if (type(lot) is not int or source_lot!=lot
                or any(v!=v.to_integral_value() for v in (volume,oi))
                or not 0<=volume*lot<=10**12 or not 0<oi<=10**12
                or min(op,hi,lo,close)<=0 or lo>min(op,close)
                or hi<max(op,close)):
            raise ValueError("NSE_RECONCILE_VALUE_OR_LOT_INVALID")
        mid=clocks[(0,0,0,0)]
        if (type(mid[5]) is not int or type(mid[6]) is not int
                or mid[5]!=volume*lot or mid[6]!=oi
                or core.parsed_price(mid[4])!=close):
            raise ValueError("NSE_RECONCILE_VOLUME_OI_OR_CLOSE_DISAGREES")
        positive_unit_anchors+=int(volume>0)
        mismatch+=int(tuple(core.parsed_price(v) for v in mid[1:5])!=values[:4])
        result.append((day,op,hi,lo,close,int(volume*lot),int(oi),"NSE_PUBLIC_EOD_RECONCILED"))
        archive_hashes.append([day.isoformat(),hashlib.sha256(blob).hexdigest()])
    if not positive_unit_anchors:
        raise ValueError("NSE_RECONCILE_POSITIVE_UNIT_ANCHOR_MISSING")
    digest=hashlib.sha256(json.dumps(archive_hashes,separators=(",",":")).encode()).hexdigest()
    return result,digest,mismatch


class ExchangeArchives:
    def __init__(self):
        self.opener=urllib.request.build_opener(nse.NoRedirect())
        self.calls=0
        self.next_at=0.0
        self.started=time.monotonic()

    def get(self,day):
        if (not isinstance(day,date) or not FIRST<=day<=TARGET
                or self.calls>=MAX_NSE_GETS or time.monotonic()-self.started>600):
            raise ValueError("NSE_RECONCILE_REQUEST_BOUND")
        wait=self.next_at-time.monotonic()
        if wait>0:time.sleep(wait)
        self.next_at=time.monotonic()+1.0
        self.calls+=1
        url=("https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_"
             +day.strftime("%Y%m%d")+"_F_0000.csv.zip")
        req=urllib.request.Request(url,method="GET",headers={
            "Accept":"application/zip","User-Agent":"MintTick-Personal-Research/1.0"})
        try:
            with self.opener.open(req,timeout=12) as response:
                if response.status!=200 or response.url!=url:
                    raise ValueError("NSE_RECONCILE_SOURCE_UNAVAILABLE")
                length=response.headers.get("Content-Length")
                if length and (not length.isdecimal() or int(length)>nse.MAX_ARCHIVE):
                    raise ValueError("NSE_RECONCILE_ARCHIVE_BOUND")
                blob=response.read(nse.MAX_ARCHIVE+1)
        except (urllib.error.HTTPError,urllib.error.URLError,OSError,TimeoutError):
            # 404 is unavailable source, NOT an inferred holiday/non-session.
            raise ValueError("NSE_RECONCILE_SOURCE_UNAVAILABLE") from None
        if len(blob)>nse.MAX_ARCHIVE:
            raise ValueError("NSE_RECONCILE_ARCHIVE_BOUND")
        return blob


def research(fetch,reference_date,exchange_fetch):
    captured=[]
    source_hash=hashlib.sha256()
    def original(route):
        payload=fetch(route)
        source_hash.update(json.dumps([route,payload],sort_keys=True,
                                     separators=(",",":"),allow_nan=False).encode())
        parts=urllib.parse.unquote(urllib.parse.urlsplit(route).path).split("/")
        if len(parts)==6 and parts[1]=="historical-candle" and parts[2].endswith("|30-12-2025"):
            captured.append(payload)
        return payload
    expiries=core.expiries_from_provider(original(
        "/expiries?instrument_key="+urllib.parse.quote("NSE_INDEX|Nifty 50",safe="")),reference_date)
    faults={}
    universe,metadata,meta_gets,hist_gets=core.retrieve_futures(
        expiries,"NSE_INDEX|Nifty 50",original,diagnostics=faults)
    if (TARGET not in metadata or len(captured)!=3
            or faults["quarantinedFuturesCohorts"]!=1
            or faults["sourceFaultsByReason"]!={"HISTORICAL_NONCANONICAL_DAILY_TIME":3}):
        raise ValueError("NSE_RECONCILE_ORIGINAL_COHORT_CHANGED")
    key,lot=metadata[TARGET]
    if universe[key]:raise ValueError("NSE_RECONCILE_TARGET_NOT_QUARANTINED")
    before=core.evaluate(universe,metadata,30)
    rows,digest,mismatch=exchange_cohort(captured,lot,exchange_fetch)
    # Separate in-memory exchange-source view; original raw observations untouched.
    rebuilt={**universe,key:rows}
    scenarios={cost:core.evaluate(rebuilt,metadata,cost) for cost in (20,30,50)}
    primary=scenarios[30]
    if any((r["decisionWindows"],r["candidateWindows"],r["windowOutcomesBySplit"])!=
           (primary["decisionWindows"],primary["candidateWindows"],primary["windowOutcomesBySplit"])
           for r in scenarios.values()):
        raise ValueError("RESEARCH_SIGNAL_COHORT_DRIFT")
    result={**primary,"state":"NSE_RECONCILED_RESEARCH_NOT_CERTIFIED",
            "providerGets":1+meta_gets+hist_gets,"exchangeGets":len(rows),
            "originalEligibleCohorts":before["eligibleFuturesCohorts"],
            "originalUpstoxQuarantinedCohorts":1,"exchangeReconciledCohorts":1,
            "exchangeSourceBars":len(rows),"midnightOhlcDisagreements":mismatch,
            "parsedUpstoxSourceSha256":source_hash.hexdigest(),
            "exchangeArchiveSetSha256":digest,"frictionBps":30,
            "oosNetAt20BpsPaise":scenarios[20]["outOfSampleNetPaise"],
            "oosNetAt50BpsPaise":scenarios[50]["outOfSampleNetPaise"],
            "authenticSourceOriginalFirstPublishedAtVerified":False,
            "calendarCompletenessVerified":False,"independentHistoricalE2ECertified":False,
            "certifiedStrategies":0,"orders":False,"writes":False}
    return safe_receipt(result)


def safe_receipt(r):
    if (type(r) is not dict or set(r)!=ALLOWED_FIELDS
            or any(type(r.get(k)) is not int for k in (
                "providerGets","originalEligibleCohorts","originalUpstoxQuarantinedCohorts",
                "exchangeReconciledCohorts","exchangeSourceBars","certifiedStrategies"))
            or r.get("state")!="NSE_RECONCILED_RESEARCH_NOT_CERTIFIED"
            or r.get("providerGets")!=49 or r.get("originalEligibleCohorts")!=11
            or r.get("originalUpstoxQuarantinedCohorts")!=1
            or r.get("exchangeReconciledCohorts")!=1
            or type(r.get("exchangeGets")) is not int or not 9<=r["exchangeGets"]<=MAX_NSE_GETS
            or r.get("exchangeSourceBars")!=r["exchangeGets"]
            or type(r.get("midnightOhlcDisagreements")) is not int
            or not 0<=r["midnightOhlcDisagreements"]<=r["exchangeGets"]
            or any(r.get(k) is not False for k in (
                "authenticSourceOriginalFirstPublishedAtVerified","calendarCompletenessVerified",
                "independentHistoricalE2ECertified","orders","writes"))
            or r.get("certifiedStrategies")!=0
            or any(not isinstance(r.get(k),str) or not re.fullmatch(r"[0-9a-f]{64}",r[k])
                   for k in ("parsedUpstoxSourceSha256","exchangeArchiveSetSha256"))):
        raise ValueError("NSE_RECONCILE_RECEIPT_INVALID")
    # Reuse all existing numerical, partition, OOS and cost validation contracts.
    # The core receipt now rejects unknown keys; the exchange-specific
    # receipt has its own strict allowlist above. Project only common fields.
    exchange_only={"exchangeGets","originalEligibleCohorts",
                   "originalUpstoxQuarantinedCohorts","exchangeReconciledCohorts",
                   "exchangeSourceBars","midnightOhlcDisagreements",
                   "parsedUpstoxSourceSha256","exchangeArchiveSetSha256",
                   "calendarCompletenessVerified"}
    compatible={k:v for k,v in r.items() if k not in exchange_only}
    compatible.update(state="RESEARCH_EXTENDED_EOD_NOT_CERTIFIED",
                metadataGets=13,historyGets=36,sourceDayRange=90,
                sourceCaptureType="AUTHENTICATED_RECEIVED_NOW_NOT_HISTORICAL_FIRST_KNOWN",
                quarantinedFuturesCohorts=0,rejectedHistoryChunks=0,sourceFaultsByReason={},
                rejectedTimeShapes={},rejectedDayShapes={})
    core.safe_receipt(compatible)
    return r


def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise ValueError("EXACT_PRIVATE_HOST_REQUIRED")
    sys.path.insert(0,"/opt/aitradingsystem-paper/infra/ec2-paper")
    from fno_private_bulk_worker import Discovery,grant,ssm_token,today
    grant().require("day",today())
    provider=Discovery(ssm_token())
    archives=ExchangeArchives()
    report=research(provider.get,today(),archives.get)
    if provider.calls!=49 or archives.calls!=report["exchangeGets"]:
        raise ValueError("PROVIDER_GET_COUNT_INVALID")
    print(MARKER+json.dumps(report,sort_keys=True,separators=(",",":")),flush=True)


def bundle():
    root=Path(__file__).resolve().parent
    prefix="import sys,types\n"
    for name in MODULES:
        source=(root/(name+".py")).read_text()
        prefix+=f"m=types.ModuleType({name!r});sys.modules[{name!r}]=m;exec({source!r},m.__dict__)\n"
    return (prefix+Path(__file__).read_text()).replace(
        "from __future__ import annotations\n","").encode()


def runner():
    if (not REQUEST.is_file() or REQUEST.is_symlink() or REQUEST.stat().st_size>=1024
            or json.loads(REQUEST.read_text())!=EXACT):
        raise ValueError("PROTECTED_OWNER_REQUEST_NOT_REVIEWED")
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    verify_aws()
    blob=base64.b64encode(gzip.compress(bundle(),mtime=0)).decode()
    if len(blob)>24000:raise ValueError("RUNNER_BOUND_INVALID")
    command=("set -eu\numask 077\nprintf '%s' '"+blob+"' | base64 -d | gzip -d | "
             "PYTHONDONTWRITEBYTECODE=1 python3 - host\n")
    call=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
              "--instance-ids",core.INSTANCE,"--timeout-seconds","900",
              "--comment","MintTick STR176 independent NSE EOD research no orders",
              "--parameters",json.dumps({"commands":[command]}))
    cid=(call.get("Command") or {}).get("CommandId")
    if not isinstance(cid,str) or not re.fullmatch(r"[0-9a-f-]{36}",cid):
        raise ValueError("SSM_COMMAND_INVALID")
    for _ in range(225):
        time.sleep(4)
        report=_aws("ssm","get-command-invocation","--command-id",cid,
                    "--instance-id",core.INSTANCE)
        if report.get("Status")=="Success":
            output=report.get("StandardOutputContent") or ""
            hits=[s[len(MARKER):] for s in output.splitlines() if s.startswith(MARKER)]
            if len(output)>4000 or len(hits)!=1:raise ValueError("NSE_RECONCILE_RECEIPT_INVALID")
            print(MARKER+json.dumps(safe_receipt(json.loads(hits[0])),sort_keys=True),flush=True)
            return
        if report.get("Status") in ("Failed","TimedOut","Cancelled"):
            lines=(report.get("StandardErrorContent") or "").splitlines()
            codes=[s[len(MARKER+"BLOCKED:"):] for s in lines if s.startswith(MARKER+"BLOCKED:")]
            raise ValueError(codes[0] if len(codes)==1 and codes[0] in SAFE_CODES
                             else "NSE_RECONCILE_HOST_FAILED")
    raise ValueError("OWNER_SOURCE_RESEARCH_TIMEOUT")


SAFE_CODES=core.SAFE_BLOCK_CODES|frozenset((
    "NSE_RECONCILE_RAW_SCOPE_INVALID","NSE_RECONCILE_MIDNIGHT_AUDIT_MISSING",
    "NSE_RECONCILE_SOURCE_UNAVAILABLE","NSE_RECONCILE_EXACT_FUTURE_MISSING",
    "NSE_RECONCILE_VALUE_OR_LOT_INVALID","NSE_RECONCILE_VOLUME_OI_OR_CLOSE_DISAGREES",
    "NSE_RECONCILE_POSITIVE_UNIT_ANCHOR_MISSING","NSE_RECONCILE_REQUEST_BOUND",
    "NSE_RECONCILE_ARCHIVE_BOUND","NSE_RECONCILE_ORIGINAL_COHORT_CHANGED",
    "NSE_RECONCILE_TARGET_NOT_QUARANTINED","NSE_RECONCILE_RECEIPT_INVALID",
    "NSE_RECONCILE_HOST_FAILED"))
if __name__=="__main__":
    try:
        if sys.argv[1:]==["host"]:host()
        elif sys.argv[1:]==["runner"]:runner()
        else:raise ValueError("RUNNER_MODE_INVALID")
    except Exception as exc:
        code=exc.args[0] if len(exc.args)==1 and type(exc.args[0]) is str else ""
        print(MARKER+"BLOCKED:"+(code if code in SAFE_CODES else "UNCLASSIFIED_SOURCE_FAILURE"),
              file=sys.stderr,flush=True)
        raise SystemExit(2)
