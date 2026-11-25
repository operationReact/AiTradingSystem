#!/usr/bin/env python3
"""Two fixed, GET-only owner diagnostics for a listed NIFTY option on exact EC2.

Reports field presence and bounded failure states only. Never prints provider
responses, prices, credentials, tokens or owner data. No persistence/orders.
"""
from __future__ import annotations
import base64
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
import urllib.request

ACCOUNT="098859530205"
REGION="ap-south-1"
INSTANCE="i-09c01b470fb39b85a"
PARAMETER="/minttick/upstox/analytics-token"
INSTRUMENT="NSE_FO|47821"
QUOTE="https://api.upstox.com/v3/market-quote/quotes?instrument_key=NSE_FO%7C47821"
CHAIN=("https://api.upstox.com/v2/option/chain?"
       "instrument_key=NSE_INDEX%7CNifty%2050&expiry_date=2026-12-01")
REQUEST="infra/ec2-paper/requests/fno-contract-47821-quote-check.json"
MARKER="MINTTICK_FNO_47821_PROBE:"
FIELDS=("ltp","oi","trade","previous","chain_ltp","chain_oi",
        "iv","delta","gamma","theta","vega")
STATES={"VERIFIED","EMPTY","NOT_FOUND","INVALID","HTTP_400","HTTP_401",
        "HTTP_403","HTTP_404","HTTP_422","HTTP_429","HTTP_5XX",
        "HTTP_OTHER","TRANSPORT_ERROR","TOKEN_UNAVAILABLE","HOST_INVALID"}
ALLOWED_RE=re.compile(r"^[A-Z0-9_]{2,26}$")
MAX_RESPONSE=1_000_000

def expected_request():
    return {"application":"MintTick","aws_account":ACCOUNT,"region":REGION,
            "instance_id":INSTANCE,"operation":"two-read-only-upstox-fno-quote-gets",
            "instrument":"NSE_FO|47821","expiry":"2026-12-01",
            "max_provider_gets":2,"orders":False,"persist":False}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def present(value, positive=False):
    return (type(value) in (int,float) and math.isfinite(value)
            and (value>0 if positive else value>=0))

def read_quote(body):
    result={k:"NO" for k in FIELDS}
    if not isinstance(body,dict) or body.get("status")!="success":
        return "INVALID",result
    data=body.get("data")
    if not isinstance(data,dict) or len(data)>1:
        return "INVALID",result
    if not data: return "EMPTY",result
    rows=[v for v in data.values() if isinstance(v,dict)
          and v.get("instrument_token")==INSTRUMENT]
    if len(rows)!=1: return "NOT_FOUND",result
    row=rows[0]
    result["ltp"]="YES" if present(row.get("last_price"),True) else "NO"
    result["oi"]="YES" if present(row.get("oi")) else "NO"
    result["previous"]="YES" if present(row.get("prev_close_price"),True) else "NO"
    value=row.get("last_trade_time")
    result["trade"]="YES" if (str(value).isdigit() and
           10<=len(str(value))<=16) else "NO"
    return "VERIFIED",result

def read_chain(body):
    result={k:"NO" for k in FIELDS}
    if not isinstance(body,dict) or body.get("status")!="success":
        return "INVALID",result
    data=body.get("data")
    if not isinstance(data,list) or len(data)>512:
        return "INVALID",result
    matches=[]
    for row in data:
        if not isinstance(row,dict):continue
        for side in ("call_options","put_options"):
            leg=row.get(side)
            if isinstance(leg,dict) and leg.get("instrument_key")==INSTRUMENT:
                matches.append(leg)
    if not matches:return "NOT_FOUND",result
    if len(matches)!=1:return "INVALID",result
    leg=matches[0]
    market=leg.get("market_data")
    greek=leg.get("option_greeks")
    market=market if isinstance(market,dict) else {}
    greek=greek if isinstance(greek,dict) else {}
    result["chain_ltp"]="YES" if present(market.get("ltp"),True) else "NO"
    result["chain_oi"]="YES" if present(market.get("oi")) else "NO"
    for name in ("iv","delta","gamma","theta","vega"):
        v=greek.get(name)
        result[name]="YES" if (type(v) in (int,float) and math.isfinite(v)
                               and (v>=0 if name=="iv" else True)) else "NO"
    return "VERIFIED",result

def get(url, token, parser):
    req=urllib.request.Request(url,headers={
        "Authorization":"Bearer "+token,
        "Accept":"application/json",
        "User-Agent":"MintTick-FNO-Owner-ReadOnly-Probe/1.0"},method="GET")
    try:
        with urllib.request.build_opener(NoRedirect()).open(req,timeout=15) as r:
            if r.status!=200: return "HTTP_OTHER",{k:"NO" for k in FIELDS}
            raw=r.read(MAX_RESPONSE+1)
    except urllib.error.HTTPError as e:
        code=e.code
        state="HTTP_"+str(code) if code in (400,401,403,404,422,429) else (
            "HTTP_5XX" if 500<=code<600 else "HTTP_OTHER")
        return state,{k:"NO" for k in FIELDS}
    except (urllib.error.URLError, TimeoutError, OSError):
        return "TRANSPORT_ERROR",{k:"NO" for k in FIELDS}
    if len(raw)>MAX_RESPONSE:return "INVALID",{k:"NO" for k in FIELDS}
    try:return parser(json.loads(raw))
    except (ValueError,TypeError,UnicodeError):
        return "INVALID",{k:"NO" for k in FIELDS}

def receipt(q,c,metrics):
    if q not in STATES or c not in STATES or any(
            metrics.get(k) not in ("YES","NO") for k in FIELDS):
        raise ValueError("INVALID_RECEIPT")
    part=" ".join(k+"="+metrics[k] for k in FIELDS)
    return f"{MARKER} quote={q} chain={c} {part} orders=FALSE stored=FALSE"

def parse_receipt(raw):
    if not isinstance(raw,str) or len(raw)>10000:return None
    rows=[x.strip() for x in raw.splitlines() if x.startswith(MARKER)]
    if len(rows)!=1:return None
    line=rows[0]
    m=re.fullmatch(r"MINTTICK_FNO_47821_PROBE: quote=([A-Z0-9_]+) "
                   r"chain=([A-Z0-9_]+) (.*?) orders=FALSE stored=FALSE",line)
    if not m or m.group(1) not in STATES or m.group(2) not in STATES:return None
    parts=m.group(3).split(" ")
    if parts!=[k+"="+v for k,v in zip(FIELDS,[
            next((p.split("=",1)[1] for p in parts if p.startswith(k+"=")),"")
            for k in FIELDS])]:return None
    if any(p not in (k+"=YES",k+"=NO") for p,k in zip(parts,FIELDS)) or len(parts)!=len(FIELDS):
        return None
    return line

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        print(receipt("HOST_INVALID","HOST_INVALID",{k:"NO" for k in FIELDS}))
        return
    cmd=["aws","ssm","get-parameter","--name",PARAMETER,"--with-decryption",
         "--region",REGION,"--output","json","--no-cli-pager"]
    try:
        p=subprocess.run(cmd,capture_output=True,text=True,timeout=15,check=False)
        token=json.loads(p.stdout)["Parameter"]["Value"] if p.returncode==0 else None
    except (OSError,ValueError,KeyError,TypeError,subprocess.TimeoutExpired):
        token=None
    if not isinstance(token,str) or not 32<=len(token)<=8192 or re.search(r"\s",token):
        print(receipt("TOKEN_UNAVAILABLE","TOKEN_UNAVAILABLE",{k:"NO" for k in FIELDS}))
        return
    q,qp=get(QUOTE,token,read_quote)
    c,cp=get(CHAIN,token,read_chain)
    result={k:(cp[k] if k in ("chain_ltp","chain_oi","iv","delta","gamma","theta","vega")
               else qp[k]) for k in FIELDS}
    print(receipt(q,c,result),flush=True)

def runner():
    path=Path(REQUEST)
    if path.is_symlink() or not path.is_file() or path.stat().st_size>1024 or json.loads(
            path.read_text())!=expected_request():
        raise SystemExit("FNO_QUOTE_REQUEST_NOT_APPROVED")
    sys.path.insert(0,"infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    source=Path(__file__).read_bytes()
    encoded=base64.b64encode(gzip.compress(source,mtime=0)).decode()
    if len(encoded)>24000 or not re.fullmatch("[A-Za-z0-9+/=]+",encoded):
        raise SystemExit("FNO_QUOTE_PAYLOAD_INVALID")
    shell="set -eu\nprintf '%s' '"+encoded+"' | base64 -d | gzip -dc | python3 - host\n"
    def aws(*args):
        p=subprocess.run(["aws",*args,"--region",REGION,"--output","json","--no-cli-pager"],
            capture_output=True,text=True,timeout=50,check=False)
        if p.returncode:raise RuntimeError("FNO_QUOTE_SSM_UNAVAILABLE")
        return json.loads(p.stdout)
    sent=aws("ssm","send-command","--document-name","AWS-RunShellScript",
        "--instance-ids",INSTANCE,"--timeout-seconds","160",
        "--comment","MintTick fixed read-only exact option quote presence probe",
        "--parameters",json.dumps({"commands":[shell]}))
    cid=(sent.get("Command") or {}).get("CommandId","")
    if not isinstance(cid,str) or not re.fullmatch("[0-9a-f-]{36}",cid):
        raise RuntimeError("FNO_QUOTE_BAD_SSM_COMMAND")
    for _ in range(40):
        time.sleep(4)
        data=aws("ssm","get-command-invocation","--command-id",cid,"--instance-id",INSTANCE)
        status=data.get("Status")
        if status in ("Success","Failed","TimedOut","Cancelled"):
            line=parse_receipt(data.get("StandardOutputContent",""))
            if status!="Success" or not line:raise RuntimeError("FNO_QUOTE_NO_SAFE_RECEIPT")
            print(line,flush=True)
            return
        if status not in ("Pending","InProgress","Delayed"):
            raise RuntimeError("FNO_QUOTE_BAD_SSM_STATE")
    raise RuntimeError("FNO_QUOTE_SSM_TIMEOUT")

if __name__=="__main__":
    if sys.argv[1:]==["host"]:host()
    elif sys.argv[1:]==["runner"]:runner()
    else:raise SystemExit("FNO_QUOTE_MODE_INVALID")
