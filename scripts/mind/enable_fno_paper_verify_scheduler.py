#!/usr/bin/env python3
"""Opt in ONLY the dedicated virtual F&O PAPER verification room.

Requires existing externally verified source rights, clean virtual ledger,
fresh customer JWT and protected after-hours test account. Does not enable
LIVE orders, touch other rooms, or claim independent strategy backtesting.
After enabling, the existing backend scheduler may submit *virtual PAPER*
orders during future NSE sessions using its bounded one-lot risk guard.
"""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request

import bootstrap_fno_paper_verify_room as room

MARK="MIND_FNO_PAPER_AUTOMATION:"
ROOT=room.ROOT


class Blocked(ValueError):
    pass


def arm(token, read=room.request, post=None, now=None):
    t=(now or dt.datetime.now(room.IST)).astimezone(room.IST)
    if 9<=t.hour<16:
        raise Blocked("SCHEDULER_OPTIN_MARKET_HOURS_DENIED")
    # Opt-in must be read-only until the explicit automation control POST.
    # ensure_room() creates a room when missing, so it is unsafe here.
    inventory=read(ROOT,token)
    if (not isinstance(inventory,list) or len(inventory)>100 or
            any(not isinstance(item,dict) for item in inventory)):
        raise Blocked("VERIFICATION_ROOM_INVENTORY_INVALID")
    matches=[item for item in inventory if item.get("name")==room.ROOM_NAME]
    if len(matches)!=1:
        raise Blocked("VERIFICATION_ROOM_MISSING_OR_AMBIGUOUS")
    if not room._valid_room(matches[0]):
        raise Blocked("VERIFICATION_ROOM_SCOPE_MISMATCH")
    identifier=matches[0]["id"]
    prefix=ROOT+"/"+identifier
    capability=read(prefix+"/paper/fno/capability",token)
    if (not isinstance(capability,dict) or
            capability.get("paperConfigured") is not True or
            capability.get("liveExecutionAuthorized") is not False):
        raise Blocked("PAPER_USE_OR_RIGHTS_NOT_READY")
    books=read(prefix+"/paper/fno/reconciliation",token)
    wallet=read(prefix+"/paper/fno/wallet-reconciliation",token)
    if (not isinstance(books,dict) or books.get("status")!="NO_FILLS" or
            books.get("recordedFills")!=0 or
            not isinstance(wallet,dict) or
            wallet.get("cashAndRealizedPnlReconciled") is not True or
            wallet.get("optionFills")!=0):
        raise Blocked("PAPER_LEDGER_NOT_EMPTY_OR_NOT_RECONCILED")
    state=read(prefix+"/mind/fno/paper-automation",token)
    if not isinstance(state,dict) or state.get("liveMoney") is not False:
        raise Blocked("VIRTUAL_AUTOMATION_CONTRACT_INVALID")
    if state.get("enabled") is True:
        print(MARK+"ALREADY_ENABLED_IN_VERIFICATION_ROOM;liveMoney=false")
        return 0
    if state.get("enabled") is not False:
        raise Blocked("VIRTUAL_AUTOMATION_STATE_INVALID")
    if post is None:
        post=post_control
    outcome=post(prefix+"/mind/fno/paper-automation/control",token,{"enabled":True})
    if not isinstance(outcome,dict) or outcome.get("enabled") is not True or (
            outcome.get("liveMoney") is not False):
        # Attempt to deactivate the same verification-only room, but never
        # claim a successful rollback without a new authenticated GET.
        try:
            post(prefix+"/mind/fno/paper-automation/control",token,{"enabled":False})
        except Exception:
            pass
        raise Blocked("OPTIN_ACK_INVALID")
    state=read(prefix+"/mind/fno/paper-automation",token)
    if not isinstance(state,dict) or state.get("enabled") is not True or (
            state.get("liveMoney") is not False):
        try:
            post(prefix+"/mind/fno/paper-automation/control",token,{"enabled":False})
        except Exception:
            pass
        raise Blocked("OPTIN_READBACK_FAILED")
    print(MARK+"VIRTUAL_PAPER_VERIFICATION_WORKER_ENABLED")
    print(MARK+"REAL_MONEY_ORDERS:ZERO")
    print(MARK+"PROSPECTIVE_STRATEGY_E2E:NOT_YET_PROVEN")
    return 0


def post_control(path,token,payload):
    suffix="/mind/fno/paper-automation/control"
    prefix=ROOT+"/"
    if (not isinstance(path,str) or not path.startswith(prefix) or
            not path.endswith(suffix) or
            not room.UUID.fullmatch(path[len(prefix):-len(suffix)])):
        raise Blocked("UNAPPROVED_WRITE_ROUTE")
    if payload not in ({"enabled":True},{"enabled":False}):
        raise Blocked("UNAPPROVED_CONTROL_PAYLOAD")
    if not token or "\r" in token or "\n" in token:
        raise Blocked("AUTH_TOKEN_INVALID")
    req=urllib.request.Request(room.HOST+path,method="POST",
        data=json.dumps(payload).encode(),
        headers={"Authorization":"Bearer "+token,
                 "Content-Type":"application/json",
                 "Accept":"application/json",
                 "Cache-Control":"no-store",
                 "User-Agent":"MintTick-Mind-PAPER-Verification/1.0"})
    opener=urllib.request.build_opener(room.NoRedirect())
    try:
        with opener.open(req,timeout=12) as res:
            if res.status!=200:
                raise Blocked("CONTROL_HTTP_INVALID")
            raw=res.read(8193)
    except urllib.error.HTTPError:
        raise Blocked("CONTROL_HTTP_REJECTED") from None
    except (OSError,urllib.error.URLError,TimeoutError):
        raise Blocked("CONTROL_TRANSPORT_UNAVAILABLE") from None
    if len(raw)>8192:
        raise Blocked("CONTROL_RESPONSE_TOO_LARGE")
    try:
        return json.loads(raw)
    except (ValueError,UnicodeDecodeError):
        raise Blocked("CONTROL_RESPONSE_INVALID") from None


def main(argv=None):
    args=sys.argv[1:] if argv is None else argv
    if len(args)!=1:
        print(MARK+"INVALID_ARGUMENTS")
        return 2
    try:
        p=Path(args[0])
        if not p.is_file() or p.is_symlink() or p.stat().st_size>18000:
            raise Blocked("TOKEN_FILE_INVALID")
        return arm(p.read_text().strip())
    except (Blocked,room.VerifyBlocked) as e:
        print(MARK+"BLOCKED:"+str(e)+";liveMoney=false")
        return 1
    except (OSError,TypeError,ValueError):
        print(MARK+"UNAVAILABLE;liveMoney=false")
        return 1


if __name__=="__main__":
    sys.exit(main())
