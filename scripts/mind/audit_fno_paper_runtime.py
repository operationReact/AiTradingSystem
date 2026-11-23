#!/usr/bin/env python3
"""Authenticated GET-only MintTick Mind F&O PAPER runtime evidence.

This observes a *verification customer's* own PAPER rooms. It does not create
rooms, enable workers, submit virtual or LIVE orders, or prove another owner's
account is ready. Raw user data, room ids, balances, instruments and token are
never logged. Results are fixed categories, aggregate counts and explicit
non-E2E statements.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

HOST = "https://api.minttick.com"
ROOT = "/api/customer/trading-rooms"
MARKER = "MIND_FNO_PAPER_RUNTIME:"
MAX_RESPONSE = 131072
MAX_ROOMS = 100
MAX_PROBES = 2
UUID = re.compile(r"[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}\Z")
SAFE_CODE = re.compile(r"[A-Z][A-Z0-9_]{0,79}\Z")


class ReadOnlyProbeFailure(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get_json(path: str, token: str, opener=None):
    if not path.startswith(ROOT) or "?" in path or "#" in path or "//" in path:
        raise ReadOnlyProbeFailure("REQUEST_PATH_INVALID")
    if not token or "\r" in token or "\n" in token:
        raise ReadOnlyProbeFailure("TOKEN_INVALID")
    opener = opener or urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(
        HOST + path, method="GET",
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/json",
            "Cache-Control": "no-store",
            "User-Agent": "MintTick-Mind-PAPER-Audit/1.0",
        })
    try:
        with opener.open(request, timeout=12) as response:
            if response.status != 200:
                raise ReadOnlyProbeFailure("GET_UNEXPECTED_STATUS")
            content = response.read(MAX_RESPONSE + 1)
    except urllib.error.HTTPError as error:
        code = error.code
        if code in (401, 403):
            raise ReadOnlyProbeFailure("CUSTOMER_AUTH_DENIED") from None
        if code == 404:
            raise ReadOnlyProbeFailure("GET_ROUTE_OR_ROOM_NOT_FOUND") from None
        raise ReadOnlyProbeFailure("GET_HTTP_UNAVAILABLE") from None
    except (OSError, TimeoutError, urllib.error.URLError):
        raise ReadOnlyProbeFailure("GET_TRANSPORT_UNAVAILABLE") from None
    if len(content) > MAX_RESPONSE:
        raise ReadOnlyProbeFailure("GET_RESPONSE_TOO_LARGE")
    try:
        return json.loads(content)
    except (ValueError, UnicodeDecodeError):
        raise ReadOnlyProbeFailure("GET_RESPONSE_NOT_JSON") from None


def bounded_int(v, maximum=5000):
    return type(v) is int and 0 <= v <= maximum


def find_funded_options(rooms):
    if not isinstance(rooms, list) or len(rooms) > MAX_ROOMS:
        raise ReadOnlyProbeFailure("ROOM_INVENTORY_INVALID")
    result = []
    for room in rooms:
        if not isinstance(room, dict):
            raise ReadOnlyProbeFailure("ROOM_INVENTORY_INVALID")
        if (room.get("mode") == "PAPER" and room.get("marketScope") == "FNO"
                and room.get("status") == "PAPER_FUNDED"
                and room.get("focus") in ("INTRADAY", "SWING")):
            identifier = room.get("id")
            if not isinstance(identifier, str) or not UUID.fullmatch(identifier):
                raise ReadOnlyProbeFailure("ROOM_IDENTIFIER_INVALID")
            result.append(identifier)
    return result


def _valid_reason(v):
    return isinstance(v, str) and SAFE_CODE.fullmatch(v)


def summarize_room(capability, book, wallet, state, history):
    if not all(isinstance(v, dict) for v in (capability, book, wallet, state)):
        raise ReadOnlyProbeFailure("PAPER_CONTRACT_INVALID")
    if not isinstance(history, list) or len(history) > 40:
        raise ReadOnlyProbeFailure("PAPER_HISTORY_INVALID")
    if type(capability.get("readOnlyConfigured")) is not bool or (
            type(capability.get("paperConfigured")) is not bool or
            capability.get("liveExecutionAuthorized") is not False):
        raise ReadOnlyProbeFailure("PAPER_CAPABILITY_INVALID")
    reasons = capability.get("blockerCodes")
    if not isinstance(reasons, list) or len(reasons) > 20 or any(not _valid_reason(x) for x in reasons):
        raise ReadOnlyProbeFailure("PAPER_BLOCKERS_INVALID")
    book_status = book.get("status")
    if book_status not in ("NO_FILLS", "BOOKKEEPING_MATCHED", "INCONSISTENT", "INCOMPLETE"):
        raise ReadOnlyProbeFailure("PAPER_BOOK_STATUS_INVALID")
    if any(not bounded_int(book.get(k)) for k in ("recordedFills", "buyFills", "sellFills", "openContracts")):
        raise ReadOnlyProbeFailure("PAPER_BOOK_COUNTS_INVALID")
    if (type(wallet.get("cashAndRealizedPnlReconciled")) is not bool or
            not bounded_int(wallet.get("optionFills")) or
            wallet.get("tradingApproved") is not False or
            book.get("tradingApproved") is not False):
        raise ReadOnlyProbeFailure("PAPER_WALLET_CONTRACT_INVALID")
    if (type(state.get("enabled")) is not bool or
            state.get("liveMoney") is not False):
        raise ReadOnlyProbeFailure("PAPER_AUTOMATION_CONTRACT_INVALID")
    paper_events = 0
    for event in history:
        if not isinstance(event, dict) or event.get("liveMoney") is not False:
            raise ReadOnlyProbeFailure("PAPER_EVENT_CONTRACT_INVALID")
        if event.get("status") in ("FILLED_RECONCILED", "FILLED_AUDIT_MISMATCH", "FILLED_AUDIT_UNKNOWN"):
            paper_events += 1
    book_match = book_status in ("NO_FILLS", "BOOKKEEPING_MATCHED")
    cash_match = wallet["cashAndRealizedPnlReconciled"]
    if state["enabled"] and (not capability["paperConfigured"] or not cash_match or not book_match):
        state_class = "ENABLED_BUT_UNSAFE_RUNTIME"
    elif state["enabled"]:
        state_class = "ENABLED_PENDING_EXTERNAL_E2E"
    elif not capability["paperConfigured"]:
        state_class = "PAPER_CONFIGURATION_BLOCKED"
    elif not cash_match or not book_match:
        state_class = "RECONCILIATION_BLOCKED"
    else:
        state_class = "DISABLED_BUT_LOCALLY_READY"
    return (MARKER + "state=" + state_class +
            ";configured=" + str(capability["paperConfigured"]).lower() +
            ";automationEnabled=" + str(state["enabled"]).lower() +
            ";bookStatus=" + book_status +
            ";cashMatched=" + str(cash_match).lower() +
            ";fills=" + str(book["recordedFills"]) +
            ";optionWalletFills=" + str(wallet["optionFills"]) +
            ";paperHistoryEvents=" + str(paper_events) +
            ";liveMoney=false;independentStrategyE2E=false")


def run(token: str, get=get_json):
    rooms = find_funded_options(get(ROOT, token))
    print(MARKER + "eligibleVerificationRooms=" + str(len(rooms)) +
          ";realUserRoomsNotInspected=true;orders=zero")
    if not rooms:
        print(MARKER + "state=NO_FUNDED_FNO_PAPER_ROOM_IN_VERIFICATION_ACCOUNT" +
              ";independentStrategyE2E=false")
        return 0
    errors = 0
    for identifier in rooms[:MAX_PROBES]:
        prefix = ROOT + "/" + identifier
        try:
            cap = get(prefix + "/paper/fno/capability", token)
            book = get(prefix + "/paper/fno/reconciliation", token)
            wallet = get(prefix + "/paper/fno/wallet-reconciliation", token)
            if (not isinstance(cap, dict) or type(cap.get("paperConfigured")) is not bool
                    or cap.get("liveExecutionAuthorized") is not False):
                raise ReadOnlyProbeFailure("PAPER_CAPABILITY_INVALID")
            if not cap["paperConfigured"]:
                # Both scheduler and PAPER routes can be conditionally absent
                # when a feature/usage gate is disabled. Report the precise
                # allowlisted blocker without requesting those absent routes.
                safe = {"FNO_PAPER_DISABLED", "FNO_V3_FEED_DISABLED",
                        "DISPLAY_RIGHTS_NOT_VERIFIED",
                        "PAPER_USE_RIGHTS_NOT_VERIFIED", "ROOM_HORIZON_UNSUPPORTED"}
                codes = cap.get("blockerCodes")
                if not isinstance(codes, list) or len(codes) > 20:
                    raise ReadOnlyProbeFailure("PAPER_BLOCKERS_INVALID")
                reason = ",".join(sorted(set(x for x in codes if isinstance(x,str) and x in safe))) or "UNCLASSIFIED"
                if not isinstance(book, dict) or book.get("status") not in (
                        "NO_FILLS","BOOKKEEPING_MATCHED","INCONSISTENT","INCOMPLETE"):
                    raise ReadOnlyProbeFailure("PAPER_BOOK_STATUS_INVALID")
                if (not isinstance(wallet, dict) or
                        type(wallet.get("cashAndRealizedPnlReconciled")) is not bool):
                    raise ReadOnlyProbeFailure("PAPER_WALLET_CONTRACT_INVALID")
                print(MARKER + "state=PAPER_CONFIGURATION_BLOCKED;codes=" + reason +
                      ";bookStatus=" + book["status"] +
                      ";cashMatched=" +
                      str(wallet["cashAndRealizedPnlReconciled"]).lower() +
                      ";scheduler=NOT_TESTED_DISABLED_GATE;orders=zero" +
                      ";independentStrategyE2E=false")
                continue
            state = get(prefix + "/mind/fno/paper-automation", token)
            history = get(prefix + "/mind/fno/paper-automation/history", token)
            print(summarize_room(cap, book, wallet, state, history))
        except ReadOnlyProbeFailure as error:
            print(MARKER + "state=READ_ONLY_PROBE_BLOCKED;reason=" + str(error) +
                  ";orders=zero;independentStrategyE2E=false")
            errors += 1
    return 1 if errors else 0


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print(MARKER + "state=INVALID_ARGUMENTS;orders=zero")
        return 2
    try:
        token_file = Path(args[0])
        if not token_file.is_file() or token_file.is_symlink() or token_file.stat().st_size > 18000:
            raise ReadOnlyProbeFailure("TOKEN_FILE_INVALID")
        token = token_file.read_text(encoding="utf-8").strip()
        return run(token)
    except ReadOnlyProbeFailure as error:
        print(MARKER + "state=BLOCKED;reason=" + str(error) +
              ";orders=zero;independentStrategyE2E=false")
        return 1
    except (OSError, ValueError, TypeError):
        print(MARKER + "state=UNAVAILABLE;orders=zero;independentStrategyE2E=false")
        return 1


if __name__ == "__main__":
    sys.exit(main())
