#!/usr/bin/env python3
"""Create one dedicated, virtual-only F&O PAPER *verification* room, once.

Uses the existing ephemeral Cognito validation user. Never touches the
owner's real account, AWS, broker credentials or LIVE order endpoints.
Only /api/customer/trading-rooms POST and customer-scoped read checks.
Off-market manual PAPER cycle may run only when explicitly requested.
No raw body/account/room identifiers, money, JWT or instrument data is logged.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

HOST = "https://api.minttick.com"
ROOT = "/api/customer/trading-rooms"
MARK = "MIND_FNO_PAPER_BOOTSTRAP:"
ROOM_NAME = "MintTick FNO PAPER Acceptance"
ROOM_BUDGET_INR = 100000  # virtual balance only, not a real fund transfer
IST = ZoneInfo("Asia/Kolkata")
UUID = re.compile(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}\Z")
MAX_RESPONSE=131072


class VerifyBlocked(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, url):
        return None


def request(path, token, method="GET", data=None, opener=None):
    if not path.startswith(ROOT) or "//" in path or "?" in path or "#" in path:
        raise VerifyBlocked("INVALID_ENDPOINT")
    if method not in ("GET", "POST") or (method=="POST" and (path!=ROOT and not path.endswith("/mind/fno/paper-cycles"))):
        raise VerifyBlocked("WRITE_NOT_ALLOWLISTED")
    if method=="POST" and path.endswith("/mind/fno/paper-cycles") and data is not None:
        raise VerifyBlocked("PAPER_CYCLE_CANNOT_SPECIFY_ORDER")
    if not token or "\r" in token or "\n" in token:
        raise VerifyBlocked("INVALID_TOKEN")
    opener=opener or urllib.request.build_opener(NoRedirect())
    if method=="POST":
        body = json.dumps(data, separators=(",", ":")).encode() if data is not None else b""
    else:
        body = None
    req=urllib.request.Request(HOST+path, data=body, method=method,
        headers={"Authorization":"Bearer "+token,
                 "Accept":"application/json",
                 "Cache-Control":"no-store",
                 "Content-Type":"application/json",
                 "User-Agent":"MintTick-FNO-PAPER-Verification/1.0"})
    try:
        with opener.open(req,timeout=15) as response:
            status=response.status
            raw=response.read(MAX_RESPONSE+1)
    except urllib.error.HTTPError as e:
        if e.code in (401,403):
            raise VerifyBlocked("AUTH_DENIED") from None
        if e.code==404:
            raise VerifyBlocked("PAPER_ROUTE_NOT_DEPLOYED") from None
        if e.code==409:
            raise VerifyBlocked("PAPER_CONFIGURATION_BLOCKED") from None
        raise VerifyBlocked("PAPER_HTTP_ERROR") from None
    except (OSError,urllib.error.URLError,TimeoutError):
        raise VerifyBlocked("PAPER_TRANSPORT_UNAVAILABLE") from None
    if len(raw)>MAX_RESPONSE or status not in (200,201):
        raise VerifyBlocked("BAD_HTTP_OR_BODY")
    try:
        return json.loads(raw)
    except (ValueError,UnicodeDecodeError):
        raise VerifyBlocked("INVALID_JSON_RESPONSE") from None


def _valid_room(room):
    return (isinstance(room,dict)
            and isinstance(room.get("id"),str)
            and UUID.fullmatch(room["id"].lower())
            and room.get("name")==ROOM_NAME
            and room.get("mode")=="PAPER"
            and room.get("marketScope")=="FNO"
            and room.get("focus")=="INTRADAY"
            and room.get("status")=="PAPER_FUNDED")


def ensure_room(token, getter=request):
    existing=getter(ROOT,token)
    if not isinstance(existing,list) or len(existing)>100:
        raise VerifyBlocked("ROOM_LIST_INVALID")
    matches=[x for x in existing if isinstance(x,dict) and x.get("name")==ROOM_NAME]
    if len(matches)>1:
        raise VerifyBlocked("VERIFICATION_ROOM_AMBIGUOUS")
    if matches:
        room=matches[0]
        if not _valid_room(room):
            raise VerifyBlocked("VERIFICATION_ROOM_IDENTITY_MISMATCH")
        return room["id"],False
    payload={"name":ROOM_NAME,"focus":"INTRADAY",
             "plannedBudgetInr":ROOM_BUDGET_INR,
             "riskPreference":"BALANCED",
             "mode":"PAPER","marketScope":"FNO"}
    room=getter(ROOT,token,"POST",payload)
    if not _valid_room(room):
        raise VerifyBlocked("CREATED_ROOM_CONTRACT_INVALID")
    return room["id"],True


def observe(token, getter=request, allow_cycle=False,
            now=None):
    identifier,created=ensure_room(token,getter)
    base=ROOT+"/"+identifier
    print(MARK+"verificationRoom="+("CREATED_VIRTUAL_ONLY" if created else "REUSED_VIRTUAL_ONLY")+
          ";brokerOrders=zero")
    capability=getter(base+"/paper/fno/capability",token)
    if (not isinstance(capability,dict) or
            type(capability.get("paperConfigured")) is not bool or
            capability.get("liveExecutionAuthorized") is not False):
        raise VerifyBlocked("PAPER_CAPABILITY_CONTRACT_INVALID")
    print(MARK+"paperConfigured="+str(capability["paperConfigured"]).lower()+
          ";liveExecutionAuthorized=false")
    recon=getter(base+"/paper/fno/reconciliation",token)
    wallet=getter(base+"/paper/fno/wallet-reconciliation",token)
    if (not isinstance(recon,dict) or
            recon.get("status") not in ("NO_FILLS","BOOKKEEPING_MATCHED","INCONSISTENT","INCOMPLETE") or
            not isinstance(wallet,dict) or
            type(wallet.get("cashAndRealizedPnlReconciled")) is not bool):
        raise VerifyBlocked("PAPER_LEDGER_CONTRACT_INVALID")
    print(MARK+"bookStatus="+recon["status"]+
          ";walletReconciled="+str(wallet["cashAndRealizedPnlReconciled"]).lower()+
          ";strategyE2E=false")
    try:
        worker=getter(base+"/mind/fno/paper-automation",token)
    except VerifyBlocked as e:
        if str(e)=="PAPER_ROUTE_NOT_DEPLOYED":
            print(MARK+"scheduler=ROUTE_NOT_DEPLOYED;orders=zero")
            return 0
        raise
    if (not isinstance(worker,dict) or type(worker.get("enabled")) is not bool
            or worker.get("liveMoney") is not False):
        raise VerifyBlocked("AUTOMATION_CONTRACT_INVALID")
    if worker["enabled"]:
        raise VerifyBlocked("UNEXPECTED_VERIFICATION_SCHEDULER_ENABLED")
    print(MARK+"scheduler=DISABLED_SAFE;orders=zero")
    current=(now or dt.datetime.now(IST)).astimezone(IST)
    if allow_cycle:
        if not (current.hour>=16 or current.hour<9):
            raise VerifyBlocked("PAPER_CYCLE_ONLY_AFTER_MARKET_CLOSE")
        if not capability["paperConfigured"]:
            print(MARK+"cycle=SKIPPED_CAPABILITY_BLOCKED;orders=zero")
            return 0
        result=getter(base+"/mind/fno/paper-cycles",token,"POST",None)
        if (not isinstance(result,dict) or result.get("liveMoney") is not False
                or result.get("action")!="NONE"
                or result.get("status") not in ("NO_TRADE","BLOCKED","HOLD")):
            raise VerifyBlocked("CYCLE_DID_NOT_ABSTAIN_AFTER_CLOSE")
        print(MARK+"cycle=NO_ORDER_AFTER_CLOSE;reason="+
              ("ENTRY_CUTOFF" if result.get("reasonCode")=="PAPER_ENTRY_CUTOFF"
               else "OTHER_BLOCKED")+
              ";independentE2E=false")
    return 0


def main(argv=None):
    args=sys.argv[1:] if argv is None else argv
    if len(args) not in (1,2) or (len(args)==2 and args[1]!="--closed-market-paper-cycle"):
        print(MARK+"status=INVALID_ARGUMENTS")
        return 2
    try:
        file=Path(args[0])
        if not file.is_file() or file.is_symlink() or file.stat().st_size>18000:
            raise VerifyBlocked("ACCESS_TOKEN_FILE_INVALID")
        token=file.read_text(encoding="utf-8").strip()
        return observe(token, allow_cycle=len(args)==2)
    except VerifyBlocked as e:
        print(MARK+"status=BLOCKED;reason="+str(e)+";liveMoney=false")
        return 1
    except (OSError, ValueError, TypeError):
        print(MARK+"status=UNAVAILABLE;liveMoney=false")
        return 1


if __name__=="__main__":
    sys.exit(main())
