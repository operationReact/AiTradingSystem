"""Read-only market-data diagnostic for the Mind PAPER cycle. Never creates, changes or orders anything.

Prints the classified blocker, provider, instrument identity, timeframe, latest candle time and
expected freshness. It never prints tokens or account data.

Usage: market_data_diagnostic.py <base_url> <token_file> <symbol> <exchange> <room_id_or_-> <summary_file>
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

INTERVAL = "FIVE_MINUTE"
MAX_CANDLE_AGE_MINUTES = 20
PERMITTED_SOURCES = {"ANGEL_ONE_SMARTAPI"}
PAPER_CYCLE_DATA_CODES = {"DATA_BLOCKED"}


def parse_time(value):
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def classify_candles(items, now, max_age_minutes=MAX_CANDLE_AGE_MINUTES):
    """Classify stored candles for the cycle. Returns a dict with a single verdict.

    Verdicts: MISSING (no candles), DISALLOWED (source not permitted for PAPER),
    STALE (latest candle older than the freshness window, or in the future), VALID.
    """
    if not items:
        return {"verdict": "MISSING", "count": 0, "latestCandleTime": None, "ageMinutes": None,
                "source": None, "maxAgeMinutes": max_age_minutes}
    ordered = sorted(items, key=lambda c: parse_time(c.get("candleTime")) or datetime.min.replace(tzinfo=timezone.utc))
    latest = ordered[-1]
    latest_time = parse_time(latest.get("candleTime"))
    if latest_time is None:
        return {"verdict": "MISSING", "count": len(items), "latestCandleTime": None, "ageMinutes": None,
                "source": latest.get("source"), "maxAgeMinutes": max_age_minutes}
    age = (now - latest_time).total_seconds() / 60.0
    source = latest.get("source")
    base = {"count": len(items), "latestCandleTime": latest_time.isoformat(),
            "ageMinutes": round(age, 2), "source": source, "maxAgeMinutes": max_age_minutes}
    if source not in PERMITTED_SOURCES:
        return {**base, "verdict": "DISALLOWED"}
    if age > max_age_minutes or age < -1:
        return {**base, "verdict": "STALE"}
    return {**base, "verdict": "VALID"}


def classify_cycle(cycle):
    """Summarise the last cycle's blocker. Returns reasons and the blocking stage detail."""
    if not isinstance(cycle, dict):
        return {"outcome": None, "blocker": "NO_CYCLE_RECORDED", "reasons": [], "stages": []}
    stages = [{"code": s.get("code"), "result": s.get("result"), "detail": s.get("detail")}
              for s in (cycle.get("stages") or [])]
    blocking = [s for s in stages if s["result"] in ("BLOCKED", "REJECTED", "UNKNOWN")]
    return {
        "outcome": cycle.get("outcome"),
        "blocker": blocking[0]["code"] + ":" + (blocking[0]["detail"] or "") if blocking else None,
        "reasons": list(cycle.get("reasons") or []),
        "stages": stages,
    }


def _get(base, path, token):
    req = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as err:
        raise RuntimeError(f"GET {path.split('?')[0]} returned {err.code}")


def run(base, token, symbol, exchange, room_id):
    now = datetime.now(timezone.utc)
    instruments = _get(base, "/api/customer/market-data/instruments?" + urllib.parse.urlencode(
        {"query": symbol, "exchange": exchange}), token)
    matches = [i for i in instruments.get("content", []) if i.get("tradingSymbol") == symbol and i.get("exchange") == exchange]
    if len(matches) != 1:
        return {"blocker": "INSTRUMENT_IDENTITY_NOT_UNIQUE", "matches": len(matches)}
    inst = matches[0]
    instrument_id = int(inst["id"])
    since = (now - timedelta(days=10)).date().isoformat()
    until = now.date().isoformat()
    candles = _get(base, "/api/customer/market-data/candles?" + urllib.parse.urlencode(
        {"instrumentId": instrument_id, "interval": INTERVAL, "fromDate": since, "toDate": until,
         "page": 0, "size": 200}), token)
    candle_result = classify_candles(candles.get("items", []), now)

    quote = None
    try:
        detail = _get(base, "/api/customer/market-data/quotes/detail?" + urllib.parse.urlencode(
            {"instrumentId": instrument_id}), token)
        exchange_at = parse_time(detail.get("exchangeAt"))
        quote = {"ltpPresent": detail.get("ltp") is not None, "source": detail.get("source"),
                 "exchangeAt": exchange_at.isoformat() if exchange_at else None,
                 "ageSeconds": round((now - exchange_at).total_seconds(), 1) if exchange_at else None}
    except RuntimeError as err:
        quote = {"error": str(err)}

    cycle = None
    if room_id == "auto":
        rooms = _get(base, "/api/customer/trading-rooms", token)
        paper = [r for r in rooms if str(r.get("mode", "")).upper() == "PAPER"]
        room_id = paper[0]["id"] if len(paper) == 1 else None
    if room_id:
        cycles = _get(base, f"/api/customer/trading-rooms/{room_id}/mind/paper-cycles", token)
        rows = cycles if isinstance(cycles, list) else cycles.get("content", [])
        cycle = next((c for c in rows if c.get("instrumentId") == instrument_id), None)
    identity = []
    for other in instruments.get("content", [])[:10]:
        if not str(other.get("tradingSymbol", "")).upper().startswith(symbol.split("-")[0].upper()):
            continue
        for interval in (INTERVAL, "ONE_DAY"):
            page = _get(base, "/api/customer/market-data/candles?" + urllib.parse.urlencode(
                {"instrumentId": int(other["id"]), "interval": interval, "fromDate": since, "toDate": until,
                 "page": 0, "size": 200}), token)
            rows = page.get("items", [])
            latest = max((parse_time(r.get("candleTime")) for r in rows if parse_time(r.get("candleTime"))),
                         default=None)
            identity.append({"instrumentId": int(other["id"]), "tradingSymbol": other.get("tradingSymbol"),
                             "exchange": other.get("exchange"), "interval": interval, "count": len(rows),
                             "latestCandleTime": latest.isoformat() if latest else None})
    return {
        "provider": "ANGEL_ONE_SMARTAPI",
        "identityCandidates": identity,
        "instrument": {"id": instrument_id, "tradingSymbol": symbol, "exchange": exchange},
        "interval": INTERVAL,
        "expectedFreshnessMinutes": MAX_CANDLE_AGE_MINUTES,
        "candles": candle_result,
        "quote": quote,
        "lastCycle": classify_cycle(cycle),
        "checkedAt": now.isoformat(),
    }


def main(argv):
    if len(argv) != 7:
        print("DIAGNOSTIC_FAILED: BAD_ARGUMENTS", file=sys.stderr)
        return 2
    base, token_file, symbol, exchange, room_id, summary_file = argv[1:7]
    with open(token_file) as fh:
        token = fh.read().strip()
    try:
        summary = run(base.rstrip("/"), token, symbol, exchange, room_id)
    except Exception:
        print("DIAGNOSTIC_FAILED: TRANSPORT_OR_PARSE_ERROR", file=sys.stderr)
        return 1
    with open(summary_file, "w") as fh:
        json.dump(summary, fh, indent=2)
    print(json.dumps({k: v for k, v in summary.items() if k != "checkedAt"}, indent=2))
    print("DIAGNOSTIC_DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
