#!/usr/bin/env python3
"""One bounded, read-only real Upstox V3 socket handshake on existing MintTick host.

No quotes, order requests, customer data, tokens, JWT or upstream URLs in logs.
Uses next exchange-listed expiry expected 2026-11-23 (Nov 24 is NSE holiday).
No persistent subscription or customer display policy is enabled by this test.
"""
import base64
from datetime import datetime, time as local_time
from zoneinfo import ZoneInfo
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

ROOT = Path("/opt/aitradingsystem-paper")
SHA = "0a1302f264f37ac71f167d0ace4947dbe4f30577"
EXPIRY = "2026-11-23"
INSTANCE = "i-09c01b470fb39b85a"
ACCOUNT = "098859530205"
REGION = "ap-south-1"
REQUEST = "infra/ec2-paper/requests/fno-v3-socket-pilot.json"
EXACT = {
    "operation": "one-shot-private-upstox-fno-v3-socket-evidence",
    "instance_id": INSTANCE, "aws_account": ACCOUNT, "region": REGION,
    "expected_backend_release": SHA, "expiry": EXPIRY,
    "authorization": "OWNER_APPROVED_FNO_V3_READONLY_SOCKET_2026_11_18",
}
PREFIX = "/api/admin/market-data/upstox-analytics/fno/live/"
STATES = {"STOPPED", "CONNECTING", "SUBSCRIBING", "CONNECTED",
          "CONNECTED_AWAITING_TICKS", "RECONNECTING", "FAILED_RECONNECT_LIMIT"}

def api(method, route, key):
    if method not in {"GET","POST"} or not route.startswith(PREFIX) or "?" in route[:-len(EXPIRY)-8]:
        # Allow the exact pinned expiry query on start ONLY.
        raise ValueError("UNAPPROVED_API_ROUTE")
    if route not in (PREFIX+"status", PREFIX+"stop",
                     PREFIX+"start?expiry="+EXPIRY):
        raise ValueError("UNAPPROVED_API_ROUTE")
    req = urllib.request.Request("http://127.0.0.1:8080"+route,
        headers={"X-API-Key":key,"Accept":"application/json"},
        data=b"" if method=="POST" else None, method=method)
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(req, timeout=45 if method=="POST" else 8) as resp:
            if (resp.status != 202 if route.startswith(PREFIX+"start")
                    else resp.status != 200):
                return None
            body=resp.read(4097)
            if len(body)>4096:
                return None
            return json.loads(body)
    except (urllib.error.URLError, ValueError, OSError, TimeoutError):
        return None

def valid_status(payload):
    if not isinstance(payload,dict) or payload.get("state") not in STATES:
        return None
    for name,limit in (("subscriptions",64),("observedInstruments",64),
                       ("decodedFrames",1000000),("discardedFrames",1000000),
                       ("freshExchangeTimedQuotes",64)):
        value=payload.get(name)
        if type(value) is not int or not 0<=value<=limit:
            return None
    if payload.get("displayRightsVerified") is not False or (
        payload.get("liveTradingEnabled") is not False):
        return None
    return payload

# Sanitized failure tokens only; the host's stderr is never echoed verbatim.
VALID_ERRORS = {"WRONG_HOST","WRONG_HOST_ENV","DUPLICATE_FLAG",
    "SOURCE_NOT_READY","UNREVIEWED_RIGHTS_FLAG","NSE_SESSION_NOT_OPEN",
    "OPERATOR_AUTH_UNAVAILABLE","BUSY_OPERATOR_SOCKET",
    "SOURCE_OR_EXPIRY_REJECTED","V3_STATUS_UNVERIFIED",
    "SOCKET_STOP_UNVERIFIED","UNAPPROVED_API_ROUTE",
    "REQUEST_INVALID","REQUEST_CONTRACT_MISMATCH",
    "SCRIPT_TOO_LARGE","SSM_UNAVAILABLE","SSM_BAD_COMMAND_ID",
    "V3_PRIVATE_PROBE_UNVERIFIED","V3_PRIVATE_INVALID_SSM_STATE",
    "V3_PRIVATE_TIMEOUT","INVALID_MODE"}

def inside_monday_nse_window(at):
    """Bound this exact one-shot proof to the next verified NSE F&O session.

    A regular 09:15-15:30 IST window does not authorize broker trading;
    24 Nov 2026 is an exchange holiday and must not be used as fallback.
    """
    if at is None or at.tzinfo is None or at.utcoffset() is None:
        return False
    india = at.astimezone(ZoneInfo("Asia/Kolkata"))
    return (india.date().isoformat() == EXPIRY
            and local_time(9, 15) <= india.time().replace(tzinfo=None)
                < local_time(15, 30))


def host_error_token(stderr):
    """Return the whitelisted host failure token from SSM stderr, else None."""
    found=None
    for line in (stderr or "").splitlines():
        k,sep,v=line.partition(":")
        if sep and k=="V3_PRIVATE_ERROR" and v in VALID_ERRORS:
            found=v
    return found

def host():
    if not inside_monday_nse_window(datetime.now(ZoneInfo("Asia/Kolkata"))):
        raise ValueError("NSE_SESSION_NOT_OPEN")
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        raise ValueError("WRONG_HOST")
    for name in ("runtime.env",".env"):
        path=ROOT/name
        if not path.is_file() or path.is_symlink() or path.stat().st_mode&0o077:
            raise ValueError("WRONG_HOST_ENV")
    def read_env(path):
        out={}
        for line in path.read_text().splitlines():
            k,sep,v=line.partition("=")
            if sep:
                if k in out:
                    raise ValueError("DUPLICATE_FLAG")
                out[k]=v
        return out
    env=read_env(ROOT/"runtime.env")
    compose=read_env(ROOT/".env")
    if compose.get("RELEASE_SHA")!=SHA or (
        env.get("MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED")!="true"):
        raise ValueError("SOURCE_NOT_READY")
    # A pre-existing display-rights config may be true, but this proof
    # never changes, validates or relies upon that entitlement. Only an
    # already-stopped source socket is eligible for the bounded handshake.
    for flag in ("MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED",
                 "MINTTICK_UPSTOX_FNO_AUTO_MARKET_FEED_ENABLED"):
        if env.get(flag,"false")!="false":
            raise ValueError("UNREVIEWED_RIGHTS_FLAG")
    key=env.get("APP_API_KEY","")
    if not re.fullmatch(r"[0-9a-f]{64}",key):
        raise ValueError("OPERATOR_AUTH_UNAVAILABLE")
    initial=valid_status(api("GET",PREFIX+"status",key))
    if initial is None or initial["state"]!="STOPPED":
        raise ValueError("BUSY_OPERATOR_SOCKET")
    started=False
    try:
        result=valid_status(api("POST",PREFIX+"start?expiry="+EXPIRY,key))
        if result is None:
            raise ValueError("SOURCE_OR_EXPIRY_REJECTED")
        started=True
        # Finite 12-second feed handshake; no burst loop or outside-session replay.
        time.sleep(12)
        status=valid_status(api("GET",PREFIX+"status",key))
        if status is None:
            raise ValueError("V3_STATUS_UNVERIFIED")
        print("V3_PRIVATE_SOCKET:"+status["state"], flush=True)
        print("V3_PRIVATE_SUBSCRIPTIONS:"+str(status["subscriptions"]),flush=True)
        print("V3_PRIVATE_DECODED_FRAMES:"+str(status["decodedFrames"]),flush=True)
        print("V3_PRIVATE_FRESH_TICKS:"+str(status["freshExchangeTimedQuotes"]),flush=True)
        print("V3_PRIVATE_EXPIRY:"+EXPIRY,flush=True)
    finally:
        if started:
            stop=valid_status(api("POST",PREFIX+"stop",key))
            if stop is None or stop["state"]!="STOPPED":
                raise ValueError("SOCKET_STOP_UNVERIFIED")
            print("V3_PRIVATE_STOP:CONFIRMED",flush=True)

def runner():
    if not inside_monday_nse_window(datetime.now(ZoneInfo("Asia/Kolkata"))):
        raise ValueError("NSE_SESSION_NOT_OPEN")
    p=Path(REQUEST)
    if p.is_symlink() or not p.is_file() or p.stat().st_size>1300:
        raise ValueError("REQUEST_INVALID")
    if json.loads(p.read_text())!=EXACT:
        raise ValueError("REQUEST_CONTRACT_MISMATCH")
    sys.path.insert(0,"infra/ec2-paper")
    import verify_ssm_release
    verify_ssm_release.verify_aws()
    raw=Path(__file__).read_bytes()
    payload=base64.b64encode(gzip.compress(raw,mtime=0)).decode()
    if len(payload)>12500 or not re.fullmatch("[A-Za-z0-9+/=]+",payload):
        raise ValueError("SCRIPT_TOO_LARGE")
    script="set -eu\nprintf '%s' '"+payload+"' | base64 -d | gzip -d | python3 - host\n"
    def aws(*args):
        cmd=["aws",*args,"--region",REGION,"--output","json","--no-cli-pager"]
        p=subprocess.run(cmd,capture_output=True,text=True,check=False,timeout=50)
        if p.returncode:
            raise ValueError("SSM_UNAVAILABLE")
        return json.loads(p.stdout)
    result=aws("ssm","send-command","--document-name","AWS-RunShellScript",
        "--instance-ids",INSTANCE,"--timeout-seconds","125",
        "--comment","Private 12-second Upstox V3 read-only socket proof",
        "--parameters",json.dumps({"commands":[script]}))
    cid=(result.get("Command") or {}).get("CommandId","")
    if not re.fullmatch("[0-9a-f-]{36}",cid):
        raise ValueError("SSM_BAD_COMMAND_ID")
    for _ in range(40):
        time.sleep(4)
        response=aws("ssm","get-command-invocation","--command-id",cid,
                     "--instance-id",INSTANCE)
        state=response.get("Status")
        if state in ("Success","Failed","TimedOut","Cancelled"):
            stdout=response.get("StandardOutputContent") or ""
            approved={
                "V3_PRIVATE_SOCKET":r"[A-Z_]{2,32}",
                "V3_PRIVATE_SUBSCRIPTIONS":r"[0-9]{1,2}",
                "V3_PRIVATE_DECODED_FRAMES":r"[0-9]{1,7}",
                "V3_PRIVATE_FRESH_TICKS":r"[0-9]{1,2}",
                "V3_PRIVATE_EXPIRY":r"2026-11-23",
                "V3_PRIVATE_STOP":r"CONFIRMED",
            }
            seen={}
            for line in stdout.splitlines():
                k,sep,v=line.partition(":")
                if sep and k in approved and re.fullmatch(approved[k],v):
                    seen[k]=v
            for k,v in sorted(seen.items()):
                print(k+":"+v,flush=True)
            if state=="Success" and len(seen)==len(approved):
                print("V3_PRIVATE_RESULT:PROBE_COMPLETE_NO_ORDERS",flush=True)
                return
            token=host_error_token(response.get("StandardErrorContent") or "")
            if token:
                print("V3_PRIVATE_HOST_ERROR:"+token,flush=True)
            raise ValueError("V3_PRIVATE_PROBE_UNVERIFIED")
        if state not in ("Pending","InProgress","Delayed"):
            raise ValueError("V3_PRIVATE_INVALID_SSM_STATE")
    raise ValueError("V3_PRIVATE_TIMEOUT")

if __name__=="__main__":
    try:
        if len(sys.argv)!=2 or sys.argv[1] not in ("host","runner"):
            raise ValueError("INVALID_MODE")
        host() if sys.argv[1]=="host" else runner()
    except (ValueError,OSError,subprocess.TimeoutExpired) as err:
        valid=VALID_ERRORS
        msg=str(err)
        print("V3_PRIVATE_ERROR:"+(msg if msg in valid else "UNAVAILABLE"),
              file=sys.stderr)
        sys.exit(2)
