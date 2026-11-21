#!/usr/bin/env python3
"""Bounded real Upstox expired NSE_FO research download; volatile memory only.

Exactly 1 expiry request + at most 3 completed monthly expiry samples x:
future contracts GET + option contracts GET + 3 daily candle GETs = 16 GETs.
Validates real futures and CE+PE OHLCV/OI in RAM. Discards all raw candles
before process exit. No persistent archive, SQLite, progress files, trades,
streaming service or scheduled job. Emits a fixed count-only status receipt.
"""
from __future__ import annotations
import base64
from datetime import date, datetime, timedelta
import gzip
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo

REGION="ap-south-1"
ACCOUNT="098859530205"
INSTANCE="i-09c01b470fb39b85a"
ROOT=Path("/opt/aitradingsystem-paper")
TOKEN_PARAM="/minttick/upstox/analytics-token"
BASE="https://api.upstox.com/v2/expired-instruments"
AGENT="MintTick-Upstox-ReadOnly-Diagnostics/1.0"
UNDERLYING="NSE_INDEX|Nifty 50"
REQUEST=Path("infra/ec2-paper/requests/fno-ephemeral-pilot.json")
MARKER="MINTTICK_FNO_EPHEMERAL_V1:"
MAX_GETS=16
MONTHS=3
STATES={"SOURCE_VERIFIED","NO_EXPIRED_MONTH","NO_USABLE_CONTRACT",
        "TOKEN_UNAVAILABLE","AUTH_REJECTED","RATE_LIMITED",
        "PROVIDER_UNAVAILABLE","PROVIDER_REJECTED","INVALID_SOURCE",
        "HOST_REJECTED","SSM_FAILURE"}

class PilotError(Exception):
    def __init__(self,status):
        self.status=status if status in STATES else "INVALID_SOURCE"
        super().__init__(self.status)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        return None

def expected_request():
    return {
        "kind":"minttick-expired-fno-ephemeral-pilot-v1",
        "operation":"download-validate-expired-futures-options-in-memory",
        "account":ACCOUNT,"region":REGION,"instance_id":INSTANCE,
        "environment":"PAPER",
        "authorization":"OWNER_APPROVED_MEMORY_ONLY_FNO_HISTORY_SAMPLE_2026_11_21",
        "max_provider_gets":MAX_GETS,
        "persist_market_data":False,
        "publish_market_data":False,
        "trading_rights_change":False,
    }

def token_from_ssm():
    try:
        p=subprocess.run(["aws","ssm","get-parameter","--name",TOKEN_PARAM,
                          "--with-decryption","--region",REGION,"--output","json",
                          "--no-cli-pager"],capture_output=True,text=True,
                         timeout=20,check=False)
        if p.returncode:
            raise PilotError("TOKEN_UNAVAILABLE")
        value=json.loads(p.stdout)["Parameter"]["Value"]
        if not isinstance(value,str) or not 32<=len(value)<=8192 or re.search(r"\s",value):
            raise PilotError("TOKEN_UNAVAILABLE")
        return value
    except (OSError,ValueError,KeyError,TypeError,subprocess.TimeoutExpired):
        raise PilotError("TOKEN_UNAVAILABLE") from None

def get_json(url,token,budget):
    if budget["count"]>=MAX_GETS:
        raise PilotError("INVALID_SOURCE")
    parts=urllib.parse.urlsplit(url)
    if (parts.scheme!="https" or parts.netloc!="api.upstox.com"
            or not parts.path.startswith("/v2/expired-instruments/")
            or parts.fragment or len(url)>850):
        raise PilotError("INVALID_SOURCE")
    if budget["count"]:
        time.sleep(1.6)
    budget["count"]+=1
    req=urllib.request.Request(url,method="GET",headers={
        "Authorization":"Bearer "+token,
        "Accept":"application/json",
        "User-Agent":AGENT,
    })
    opener=urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(req,timeout=15) as reply:
            if reply.status!=200:
                raise PilotError("PROVIDER_REJECTED")
            data=reply.read(8*1024*1024+1)
    except urllib.error.HTTPError as error:
        if error.code in (401,403):
            raise PilotError("AUTH_REJECTED") from None
        if error.code==429:
            raise PilotError("RATE_LIMITED") from None
        if error.code>=500:
            raise PilotError("PROVIDER_UNAVAILABLE") from None
        raise PilotError("PROVIDER_REJECTED") from None
    except (urllib.error.URLError,TimeoutError,OSError):
        raise PilotError("PROVIDER_UNAVAILABLE") from None
    if len(data)>8*1024*1024:
        raise PilotError("INVALID_SOURCE")
    try:
        parsed=json.loads(data)
    except (ValueError,UnicodeError):
        raise PilotError("INVALID_SOURCE") from None
    if not isinstance(parsed,dict) or parsed.get("status")!="success":
        raise PilotError("INVALID_SOURCE")
    return parsed.get("data")

def expiry_samples(data,today):
    if not isinstance(data,list) or len(data)>200:
        raise PilotError("INVALID_SOURCE")
    complete_month=today.replace(day=1)
    months={}
    for item in data:
        try:
            expiry=date.fromisoformat(item)
        except (ValueError,TypeError):
            raise PilotError("INVALID_SOURCE") from None
        if expiry>=complete_month:
            continue
        key=(expiry.year,expiry.month)
        if key not in months or expiry>months[key]:
            months[key]=expiry
    return sorted(months.values(),reverse=True)[:MONTHS]

def contract_key(item,expiry,kind):
    if not isinstance(item,dict) or item.get("segment")!="NSE_FO" or (
            item.get("underlying_key")!=UNDERLYING) or (
            item.get("expiry")!=expiry.isoformat()) or (
            item.get("instrument_type")!=kind):
        return None
    key=item.get("instrument_key")
    if not isinstance(key,str) or not re.fullmatch(
            r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}",key):
        raise PilotError("INVALID_SOURCE")
    if key.rsplit("|",1)[-1]!=expiry.strftime("%d-%m-%Y"):
        raise PilotError("INVALID_SOURCE")
    return key

def select_keys(data,expiry,kind):
    if not isinstance(data,list) or len(data)>10000:
        raise PilotError("INVALID_SOURCE")
    if kind=="FUT":
        found=[k for row in data if (k:=contract_key(row,expiry,"FUT"))]
        return sorted(set(found))[:1]
    found={}
    for row in data:
        if not isinstance(row,dict):
            raise PilotError("INVALID_SOURCE")
        for side in ("CE","PE"):
            key=contract_key(row,expiry,side)
            if key is not None:
                strike=row.get("strike_price")
                if (type(strike) not in (int,float) or
                        not math.isfinite(strike) or strike<=0):
                    raise PilotError("INVALID_SOURCE")
                found[(strike,side)]=key
    both=sorted({strike for strike,side in found if
                 (strike,"CE") in found and (strike,"PE") in found})
    if not both:
        return []
    strike=both[len(both)//2]
    return [found[(strike,"CE")],found[(strike,"PE")]]

def count_bars(body,first,last):
    if not isinstance(body,dict) or not isinstance(body.get("candles"),list):
        raise PilotError("INVALID_SOURCE")
    rows=body["candles"]
    if len(rows)>100:
        raise PilotError("INVALID_SOURCE")
    seen=set()
    for row in rows:
        if not isinstance(row,list) or len(row)!=7:
            raise PilotError("INVALID_SOURCE")
        ts,op,hi,lo,close,vol,oi=row
        try:
            stamp=datetime.fromisoformat(ts)
        except (ValueError,TypeError):
            raise PilotError("INVALID_SOURCE") from None
        if (stamp.tzinfo is None or not first<=stamp.date()<=last
                or ts in seen or any(type(p) not in (int,float)
                or not math.isfinite(p) or p<=0 for p in (op,hi,lo,close))
                or hi<max(op,close) or lo>min(op,close)
                or type(vol) is not int or vol<0 or type(oi) is not int or oi<0):
            raise PilotError("INVALID_SOURCE")
        seen.add(ts)
    return len(rows)

def pilot(token,today=None,fetch=get_json):
    today=today or datetime.now(ZoneInfo("Asia/Kolkata")).date()
    budget={"count":0}
    base_query=urllib.parse.quote(UNDERLYING,safe="")
    expiries=expiry_samples(fetch(BASE+"/expiries?instrument_key="+base_query,
                                  token,budget),today)
    if not expiries:
        return "NO_EXPIRED_MONTH",budget["count"],0,0,0,0
    used=0;future_count=0;option_count=0;bars=0
    for expiry in expiries:
        query=("?instrument_key="+base_query+"&expiry_date="+expiry.isoformat())
        fut=select_keys(fetch(BASE+"/future/contract"+query,token,budget),expiry,"FUT")
        opts=select_keys(fetch(BASE+"/option/contract"+query,token,budget),expiry,"OPT")
        chosen=fut+opts
        if not chosen:
            continue
        used+=1
        future_count+=len(fut)
        option_count+=len(opts)
        first=expiry-timedelta(days=7)
        for key in chosen:
            url=(BASE+"/historical-candle/"+
                 urllib.parse.quote(key,safe="")+"/day/"+
                 expiry.isoformat()+"/"+first.isoformat())
            # Raw payload never escapes this expression; only row counts kept.
            bars+=count_bars(fetch(url,token,budget),first,expiry)
    state=("SOURCE_VERIFIED" if bars>0 else "NO_USABLE_CONTRACT")
    return state,budget["count"],used,future_count,option_count,bars

def receipt(state,gets,months,futs,opts,bars):
    if (state not in STATES or any(type(n) is not int or n<0
            for n in (gets,months,futs,opts,bars))
            or gets>MAX_GETS or months>MONTHS or futs>MONTHS
            or opts>2*MONTHS or bars>100*3*MONTHS):
        raise ValueError("BAD_FNO_PILOT_RECEIPT")
    return (f"{MARKER}{state}|{gets}|{months}|{futs}|{opts}|{bars}")

def parse_receipt(raw):
    if not isinstance(raw,str) or len(raw)>5000:
        return None
    found=[x.strip() for x in raw.splitlines() if x.startswith(MARKER)]
    if len(found)!=1 or len(found[0])>110:
        return None
    values=found[0][len(MARKER):].split("|")
    if len(values)!=6 or any(not re.fullmatch(r"\d{1,4}",v) for v in values[1:]):
        return None
    try:
        numbers=tuple(int(v) for v in values[1:])
        if receipt(values[0],*numbers)!=found[0]:
            return None
    except ValueError:
        return None
    return values[0],*numbers

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        print(receipt("HOST_REJECTED",0,0,0,0,0),flush=True)
        return 2
    for name in (".env","runtime.env"):
        p=ROOT/name
        if p.is_symlink() or not p.is_file() or p.stat().st_mode&0o077:
            print(receipt("HOST_REJECTED",0,0,0,0,0),flush=True)
            return 2
    try:
        response=pilot(token_from_ssm())
    except PilotError as error:
        print(receipt(error.status,0,0,0,0,0),flush=True)
        return 2
    print(receipt(*response),flush=True)
    return 0 if response[0]=="SOURCE_VERIFIED" else 2

def _aws(*args):
    try:
        p=subprocess.run(["aws",*args,"--region",REGION,"--output","json",
                         "--no-cli-pager"],capture_output=True,text=True,
                         timeout=45,check=False)
    except (OSError,subprocess.TimeoutExpired):
        raise SystemExit("FNO_EPHEMERAL_SSM_UNAVAILABLE") from None
    if p.returncode:
        raise SystemExit("FNO_EPHEMERAL_SSM_UNAVAILABLE")
    try:
        return json.loads(p.stdout)
    except ValueError:
        raise SystemExit("FNO_EPHEMERAL_SSM_UNAVAILABLE") from None

def runner():
    if (not REQUEST.is_file() or REQUEST.is_symlink() or
            REQUEST.stat().st_size>1024 or
            json.loads(REQUEST.read_text())!=expected_request()):
        raise SystemExit("FNO_EPHEMERAL_REQUEST_NOT_APPROVED")
    sys.path.insert(0,"infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    package=base64.b64encode(
        gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if len(package)>18000 or not re.fullmatch(r"[A-Za-z0-9+/=]+",package):
        raise SystemExit("FNO_EPHEMERAL_OVERSIZED")
    shell=("set -eu\nprintf '%s' '"+package+
           "' | base64 -d | gzip -dc | python3 - host\n")
    data=_aws("ssm","send-command","--document-name",
              "AWS-RunShellScript","--instance-ids",INSTANCE,
              "--timeout-seconds","200","--comment",
              "MintTick up to 16 GET ephemeral historical FNO bars research only",
              "--parameters",json.dumps({"commands":[shell]}))
    command=(data.get("Command") or {}).get("CommandId")
    if not isinstance(command,str) or not re.fullmatch(r"[0-9a-f-]{36}",command):
        raise SystemExit("FNO_EPHEMERAL_SSM_ID_INVALID")
    for _ in range(48):
        time.sleep(4)
        data=_aws("ssm","get-command-invocation",
                  "--command-id",command,"--instance-id",INSTANCE)
        stage=data.get("Status")
        if stage in {"Success","Failed","TimedOut","Cancelled"}:
            result=parse_receipt(data.get("StandardOutputContent") or "")
            if result is None:
                raise SystemExit("FNO_EPHEMERAL_NO_SAFE_RECEIPT")
            state,gets,months,futs,opts,bars=result
            print(f"FNO EPHEMERAL DOWNLOAD: {state}; providerGets={gets}; "
                  f"expiryMonths={months}; futures={futs}; options={opts}; "
                  f"validDailyBars={bars}; persisted=FALSE; orders=FALSE",
                  flush=True)
            if stage!="Success" or state!="SOURCE_VERIFIED":
                raise SystemExit("FNO_EPHEMERAL_NOT_VERIFIED")
            return
        if stage not in {"Pending","InProgress","Delayed"}:
            raise SystemExit("FNO_EPHEMERAL_SSM_STATE_INVALID")
    raise SystemExit("FNO_EPHEMERAL_SSM_TIMEOUT")

if __name__=="__main__":
    if len(sys.argv)!=2 or sys.argv[1] not in {"host","runner"}:
        raise SystemExit("USAGE: fno_ephemeral_pilot.py host|runner")
    if sys.argv[1]=="host":
        raise SystemExit(host())
    runner()
