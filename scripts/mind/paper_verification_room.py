"""One-shot creation of the isolated PAPER verification room for the dedicated test account.

Run only from the trusted dev workflow. Prints counts, identifiers and pass/fail only. It never prints
tokens, passwords or response bodies.

Usage: paper_verification_room.py <base_url> <token_file> <request_file> <summary_file>

Gates, all fail closed:
- the account has zero trading rooms before creation (a fresh dedicated test account)
- exactly one room is created with the reviewed PAPER-only request
- global trading mode is PAPER with automated paper orders OFF, read via the room's readiness
- the room is PAPER, with no LIVE broker selected or connected
- the PAPER wallet is activated once and reports exactly the reviewed initial cash
"""
import json
import sys

import paper_verification_cycle as pv

EXPECTED_CASH_RUPEES = 100_000
EXPECTED_MODE = "PAPER"


class RoomError(Exception):
    pass


def assert_fresh_account(rooms):
    if not isinstance(rooms, list):
        raise RoomError("room list not reported")
    if rooms:
        raise RoomError(f"account already has {len(rooms)} room(s); refusing to create another")


def assert_room_request(request):
    if request.get("mode") != EXPECTED_MODE:
        raise RoomError("room request is not PAPER")
    if request.get("plannedBudgetInr") != EXPECTED_CASH_RUPEES:
        raise RoomError("room request budget is not the reviewed PAPER cash")
    if request.get("marketScope") != "EQUITY":
        raise RoomError("room request scope is not EQUITY")


def assert_wallet(account, expected_rupees):
    expected_paise = expected_rupees * 100
    if int(account.get("initialCashPaise", -1)) != expected_paise:
        raise RoomError("activated wallet initial cash does not match the reviewed amount")
    if int(account.get("availableCashPaise", -1)) != expected_paise:
        raise RoomError("activated wallet available cash does not match the reviewed amount")
    if account.get("mode") not in (None, EXPECTED_MODE):
        raise RoomError("wallet mode is not PAPER")


def run(base, token, request):
    assert_room_request(request)
    _, rooms = pv._request(base, "/api/customer/trading-rooms", token)
    assert_fresh_account(rooms)
    print("account: fresh dedicated test account (zero existing rooms)")

    body = {
        "name": request["name"],
        "focus": request["focus"],
        "plannedBudgetInr": request["plannedBudgetInr"],
        "riskPreference": request["riskPreference"],
        "mode": request["mode"],
        "marketScope": request["marketScope"],
    }
    _, created = pv._request(base, "/api/customer/trading-rooms", token, "POST", body)
    room_id = created["id"]
    if str(created.get("mode", "")).upper() != EXPECTED_MODE:
        raise RoomError("created room is not PAPER")
    print("room: created exactly one PAPER room")

    room_path = f"/api/customer/trading-rooms/{room_id}"
    _, readiness = pv._request(base, room_path + "/execution/readiness", token)
    pv.assert_isolation(readiness)
    print("isolation: PAPER room, no LIVE broker, global PAPER, automated paper orders OFF")

    _, wallet = pv._request(base, room_path + "/paper/activate", token, "POST")
    _, account = pv._request(base, room_path + "/paper/account", token)
    assert_wallet(account, EXPECTED_CASH_RUPEES)
    print(f"wallet: activated with exactly Rs {EXPECTED_CASH_RUPEES} simulated cash")
    return {"roomId": room_id, "mode": EXPECTED_MODE, "initialCashRupees": EXPECTED_CASH_RUPEES}


def main(argv):
    if len(argv) != 5:
        print("ROOM_FAILED: BAD_ARGUMENTS", file=sys.stderr)
        return 2
    base, token_file, request_file, summary_file = argv[1:5]
    with open(token_file) as fh:
        token = fh.read().strip()
    with open(request_file) as fh:
        request = json.load(fh)
    try:
        summary = run(base.rstrip("/"), token, request)
    except (RoomError, pv.VerificationError) as err:
        print("ROOM_FAILED: " + str(err), file=sys.stderr)
        return 1
    except Exception:
        print("ROOM_FAILED: TRANSPORT_OR_PARSE_ERROR", file=sys.stderr)
        return 1
    with open(summary_file, "w") as fh:
        json.dump(summary, fh, indent=2)
    print("ROOM_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
