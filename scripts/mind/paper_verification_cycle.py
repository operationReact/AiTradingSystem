"""Controlled PAPER-only Mind verification cycle, run only from the trusted dev workflow.

Prints counts, outcomes and pass/fail only. It never prints tokens, passwords or response bodies.

Usage: paper_verification_cycle.py <base_url> <token_file> <request_file> <summary_file>

Safety rails (all fail closed):
- exactly one PAPER room for the verification account
- room is PAPER with no selected or connected LIVE broker
- global trading control is PAPER with automated paper orders OFF (read via execution readiness)
- one cycle POST, then one identical replay with the same Idempotency-Key that must create no order
- at most `max_new_orders` new paper orders, and a filled order must respect the 20% exposure cap
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

EXPOSURE_CAP_FRACTION = 0.20
ALLOWED_INTERVALS = {"FIVE_MINUTE", "FIFTEEN_MINUTE", "ONE_HOUR", "ONE_DAY"}


class VerificationError(Exception):
    pass


def select_paper_room(rooms):
    paper = [r for r in rooms if str(r.get("mode", "")).upper() == "PAPER"]
    if len(paper) != 1:
        raise VerificationError(f"expected exactly one PAPER room, found {len(paper)}")
    return paper[0]


def assert_isolation(readiness):
    """LIVE isolation and global automation state. Automation must be OFF before any cycle runs."""
    if str(readiness.get("mode", "")).upper() != "PAPER":
        raise VerificationError("room is not PAPER")
    if readiness.get("selectedBroker") is not None:
        raise VerificationError("a LIVE broker is selected for this room")
    if readiness.get("brokerConnected") is not False:
        raise VerificationError("a broker is connected for this room")
    automation = readiness.get("globalAutomation")
    if not isinstance(automation, dict):
        raise VerificationError("global automation state not reported; isolation not demonstrated")
    if automation.get("tradingMode") != "PAPER":
        raise VerificationError("global trading mode is not PAPER")
    if automation.get("automatedPaper") is not False:
        raise VerificationError("automated PAPER orders are ON; a single cycle would not be isolated")


def find_instrument(content, symbol, exchange):
    matches = [i for i in content if i.get("tradingSymbol") == symbol and i.get("exchange") == exchange]
    if len(matches) != 1:
        raise VerificationError(f"expected exactly one {exchange} instrument for {symbol}, found {len(matches)}")
    return int(matches[0]["id"])


def assert_budget(cycle, initial_cash_paise, orders_before, orders_after, max_new_orders):
    new_orders = orders_after - orders_before
    if new_orders > max_new_orders:
        raise VerificationError(f"{new_orders} new orders exceeds the limit of {max_new_orders}")
    if cycle.get("outcome") == "FILLED":
        quantity = int(cycle.get("quantity") or 0)
        price = float(cycle.get("limitPrice") or 0)
        if quantity < 1 or price <= 0:
            raise VerificationError("filled cycle has no valid quantity or price")
        notional_paise = quantity * price * 100
        if notional_paise > initial_cash_paise * EXPOSURE_CAP_FRACTION:
            raise VerificationError("filled order breaches the 20% exposure cap")


def assert_idempotent(first, second):
    if first.get("id") != second.get("id"):
        raise VerificationError("replay with the same Idempotency-Key created a different cycle")


def _request(base, path, token, method="GET", body=None, key=None):
    headers = {"Authorization": "Bearer " + token}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if key is not None:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as err:
        raise VerificationError(f"{method} {path.split('?')[0]} returned {err.code}")


def run(base, token, request):
    if request.get("interval") not in ALLOWED_INTERVALS:
        raise VerificationError("interval not allowed")
    _, rooms = _request(base, "/api/customer/trading-rooms", token)
    room = select_paper_room(rooms if isinstance(rooms, list) else rooms.get("content", []))
    room_id = room["id"]
    room_path = f"/api/customer/trading-rooms/{room_id}"

    _, readiness = _request(base, room_path + "/execution/readiness", token)
    assert_isolation(readiness)
    print("isolation: PAPER room, no LIVE broker, global PAPER, automated paper orders OFF")

    _, instruments = _request(base, "/api/customer/market-data/instruments?" + urllib.parse.urlencode(
        {"query": request["instrument_symbol"], "exchange": request["exchange"]}), token)
    instrument_id = find_instrument(instruments.get("content", []), request["instrument_symbol"], request["exchange"])

    _, account_before = _request(base, room_path + "/paper/account", token)
    initial_cash = int(account_before.get("initialCashPaise") or account_before.get("initialCash") or 0)
    if initial_cash <= 0:
        raise VerificationError("paper account initial cash not reported")
    _, orders_before_body = _request(base, room_path + "/paper/orders", token)
    orders_before = len(orders_before_body.get("content", orders_before_body) if isinstance(orders_before_body, dict)
                        else orders_before_body)

    body = {"instrumentId": instrument_id, "interval": request["interval"]}
    _, first_result = _request(base, room_path + "/mind/paper-cycles", token, "POST", body, request["idempotency_key"])
    first = first_result.get("cycle", first_result)
    if first.get("liveOrder") is not False:
        raise VerificationError("cycle does not assert liveOrder=false; LIVE isolation not demonstrated")
    print(f"cycle outcome={first.get('outcome')} side={first.get('side')} quantity={first.get('quantity')} liveOrder=false")

    _, second_result = _request(base, room_path + "/mind/paper-cycles", token, "POST", body, request["idempotency_key"])
    second = second_result.get("cycle", second_result)
    assert_idempotent(first, second)
    print("idempotency: replay with the same key returned the same cycle")

    _, orders_after_body = _request(base, room_path + "/paper/orders", token)
    orders_after = len(orders_after_body.get("content", orders_after_body) if isinstance(orders_after_body, dict)
                       else orders_after_body)
    assert_budget(first, initial_cash, orders_before, orders_after, request["max_new_orders"])
    print(f"orders: before={orders_before} after={orders_after} new={orders_after - orders_before} "
          f"(limit {request['max_new_orders']})")
    stages = [s.get("code") for s in (first.get("stages") or [])]
    return {"roomId": room_id, "instrumentId": instrument_id, "outcome": first.get("outcome"),
            "stages": stages, "newOrders": orders_after - orders_before}


def main(argv):
    if len(argv) != 5:
        print("VERIFY_FAILED: BAD_ARGUMENTS", file=sys.stderr)
        return 2
    base, token_file, request_file, summary_file = argv[1:5]
    with open(token_file) as fh:
        token = fh.read().strip()
    with open(request_file) as fh:
        request = json.load(fh)
    try:
        summary = run(base.rstrip("/"), token, request)
    except VerificationError as err:
        print("VERIFY_FAILED: " + str(err), file=sys.stderr)
        return 1
    except Exception:
        print("VERIFY_FAILED: TRANSPORT_OR_PARSE_ERROR", file=sys.stderr)
        return 1
    with open(summary_file, "w") as fh:
        json.dump(summary, fh, indent=2)
    print("VERIFY_PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
