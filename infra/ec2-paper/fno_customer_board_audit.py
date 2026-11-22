#!/usr/bin/env python3
"""Fixed operator-only read of actual customer F&O board assembly via EC2 SSM.

No customer credentials, provider quote or token leaves the host. No orders,
user account mutations, app configuration writes or market data persistence.
"""
import base64
import gzip
import json
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
REQUEST="infra/ec2-paper/requests/fno-customer-board-audit.json"
ROUTE="http://127.0.0.1:8080/api/admin/market-data/upstox-analytics/fno/customer-board-audit"
MARKER="MINTTICK_FNO_CUSTOMER_BOARD_AUDIT:"
FIELDS=("displayRightsVerified","discoveryVerified","sourceReady","marketOpen",
        "liveOrderEnabled","listedContractFound","verifiedLastLtpPresent",
        "verifiedPreviousClosePresent","verifiedLastOiPresent",
        "verifiedLastTradeTimePresent","chainSnapshotLtpPresent",
        "chainSnapshotOiPresent","chainSnapshotGreeksComplete",
        "chainSnapshotRecent","safeNoOrders")
STATES={"CONTRACT_MATCHED","CONTRACT_NOT_IN_BOARD","SOURCE_OR_RIGHTS_UNAVAILABLE",
        "COOLDOWN","MARKET_HTTP_400","MARKET_HTTP_403","MARKET_HTTP_409",
        "MARKET_HTTP_503","MARKET_UNAVAILABLE","UNEXPECTED_ORDER_AUTHORITY"}
MARKET={"MARKET_CLOSED","FRESH_QUOTES","NO_FRESH_QUOTES","UNAVAILABLE","OTHER"}
ERR={"UNAVAILABLE","HTTP_401","HTTP_403","HTTP_404","HTTP_5XX",
     "INVALID_RESPONSE","NO_API_KEY","INVALID_HOST"}

def expected_request():
    return {"application":"MintTick","aws_account":ACCOUNT,"region":REGION,
        "instance_id":INSTANCE,
        "operation":"read-only-minttick-fno-customer-assembly-audit",
        "endpoint":"/api/admin/market-data/upstox-analytics/fno/customer-board-audit",
        "instrument":"NSE_FO|47821","expiry":"2026-12-01",
        "orders":False,"persist":False}

def classify(payload):
    if not isinstance(payload,dict) or len(payload)>32:
        return "INVALID_RESPONSE", None
    state=payload.get("status"); market=payload.get("boardStatus")
    if state not in STATES or market not in MARKET:
        return "INVALID_RESPONSE",None
    if type(payload.get("validatedStrikes")) is not int or not 0<=payload["validatedStrikes"]<=1024:
        return "INVALID_RESPONSE",None
    if any(type(payload.get(k)) is not bool for k in FIELDS):
        return "INVALID_RESPONSE",None
    if payload["liveOrderEnabled"] or not payload["safeNoOrders"]:
        return "INVALID_RESPONSE",None
    return state, {"market":market,"strikes":payload["validatedStrikes"],
        **{k:payload[k] for k in FIELDS}}

def safe_receipt(state, metrics=None):
    if state in ERR and metrics is None:
        return MARKER+" status="+state+" safe=true orders=false"
    if state not in STATES or metrics is None: raise ValueError("INVALID_AUDIT")
    if metrics.get("market") not in MARKET or type(metrics.get("strikes")) is not int:
        raise ValueError("INVALID_METRICS")
    vals=[]
    for key in FIELDS:
        v=metrics.get(key)
        if type(v) is not bool:raise ValueError("INVALID_FIELD")
        vals.append(key+"="+("true" if v else "false"))
    return (MARKER+" status="+state+" market="+metrics["market"]+
        " strikes="+str(metrics["strikes"])+" "+" ".join(vals)+
        " safe=true orders=false")

def parse_receipt(raw):
    if not isinstance(raw,str) or len(raw)>12000:return None
    lines=[x for x in raw.splitlines() if x.startswith(MARKER)]
    if len(lines)!=1:return None
    row=lines[0]
    if any(row==safe_receipt(s) for s in ERR):return row
    pattern=(re.escape(MARKER)+r" status=([A-Z0-9_]+) market=([A-Z_]+) "
             r"strikes=(\d{1,4}) "+r" ".join(re.escape(k)+r"=(true|false)"
                  for k in FIELDS)+r" safe=true orders=false")
    m=re.fullmatch(pattern,row)
    if not m:return None
    state,market,count=m.group(1),m.group(2),int(m.group(3))
    if state not in STATES or market not in MARKET or count>1024:return None
    vals={k:m.group(4+i)=="true" for i,k in enumerate(FIELDS)}
    try:return row if row==safe_receipt(state,{"market":market,"strikes":count,**vals}) else None
    except ValueError:return None

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        print(safe_receipt("INVALID_HOST"),flush=True);return
    env=Path("/opt/aitradingsystem-paper/runtime.env")
    if env.is_symlink() or not env.is_file() or env.stat().st_mode & 0o077:
        print(safe_receipt("INVALID_HOST"),flush=True);return
    keys=[s.split("=",1)[1] for s in env.read_text().splitlines()
          if s.startswith("APP_API_KEY=")]
    if len(keys)!=1 or not re.fullmatch("[0-9a-f]{64}",keys[0]):
        print(safe_receipt("NO_API_KEY"),flush=True);return
    req=urllib.request.Request(ROUTE,method="GET",headers={
        "X-API-Key":keys[0],"Accept":"application/json"})
    try:
        with urllib.request.build_opener(NoRedirect()).open(req,timeout=60) as res:
            if res.status!=200:
                print(safe_receipt("UNAVAILABLE"),flush=True);return
            raw=res.read(8193)
    except urllib.error.HTTPError as e:
        state="HTTP_"+str(e.code) if e.code in (401,403,404) else (
            "HTTP_5XX" if 500<=e.code<600 else "UNAVAILABLE")
        print(safe_receipt(state),flush=True);return
    except (urllib.error.URLError,OSError,TimeoutError):
        print(safe_receipt("UNAVAILABLE"),flush=True);return
    if len(raw)>8192:
        print(safe_receipt("INVALID_RESPONSE"),flush=True);return
    try:state,metrics=classify(json.loads(raw))
    except (ValueError,TypeError):state,metrics="INVALID_RESPONSE",None
    print(safe_receipt(state,metrics),flush=True)

def runner():
    path=Path(REQUEST)
    if path.is_symlink() or not path.is_file() or path.stat().st_size>1024 or json.loads(
            path.read_text())!=expected_request():
        raise SystemExit("FNO_AUDIT_UNAPPROVED")
    sys.path.insert(0,"infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    encoded=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if len(encoded)>22000 or not re.fullmatch("[A-Za-z0-9+/=]+",encoded):
        raise SystemExit("FNO_AUDIT_PAYLOAD_INVALID")
    shell="set -eu\nprintf '%s' '"+encoded+"' | base64 -d | gzip -dc | python3 - host\n"
    def aws(*args):
        p=subprocess.run(["aws",*args,"--region",REGION,"--output","json","--no-cli-pager"],
            capture_output=True,text=True,timeout=50,check=False)
        if p.returncode:raise RuntimeError("FNO_AUDIT_SSM_UNAVAILABLE")
        return json.loads(p.stdout)
    sent=aws("ssm","send-command","--document-name","AWS-RunShellScript",
        "--instance-ids",INSTANCE,"--timeout-seconds","180",
        "--comment","MintTick owner-only sanitized customer FNO assembly GET",
        "--parameters",json.dumps({"commands":[shell]}))
    cid=(sent.get("Command") or {}).get("CommandId","")
    if not isinstance(cid,str) or not re.fullmatch("[0-9a-f-]{36}",cid):
        raise RuntimeError("FNO_AUDIT_BAD_SSM_ID")
    for _ in range(45):
        time.sleep(4)
        data=aws("ssm","get-command-invocation","--command-id",cid,"--instance-id",INSTANCE)
        status=data.get("Status")
        if status in ("Success","Failed","TimedOut","Cancelled"):
            receipt=parse_receipt(data.get("StandardOutputContent",""))
            if status!="Success" or receipt is None:
                raise RuntimeError("FNO_AUDIT_NO_SAFE_RECEIPT")
            print(receipt,flush=True)
            return
        if status not in ("Pending","InProgress","Delayed"):
            raise RuntimeError("FNO_AUDIT_UNKNOWN_SSM_STATE")
    raise RuntimeError("FNO_AUDIT_TIMEOUT")

if __name__=="__main__":
    if sys.argv[1:]==["host"]:host()
    elif sys.argv[1:]==["runner"]:runner()
    else:raise SystemExit("FNO_AUDIT_INVALID_MODE")
