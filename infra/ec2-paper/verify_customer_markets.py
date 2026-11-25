#!/usr/bin/env python3
"""Read-only authenticated MintTick Markets acceptance probe.

No broker or Cognito credentials are printed or stored. A customer Cognito access
token is supplied through MINTTICK_TEST_ACCESS_TOKEN at invocation time.
This probes the deployed HTTP API, not the Android UI or a live exchange stream.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


class ContractFailure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ContractFailure(message)


def stamp(value, name):
    require(isinstance(value, str), name + " is missing")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ContractFailure(name + " is invalid") from exc
    require(parsed.tzinfo is not None, name + " lacks timezone")
    return parsed.astimezone(timezone.utc)


def validated_snapshot(payload, instrument_ids, *, closed, now):
    """Return source-backed and missing coverage counts, never price values."""
    require(isinstance(payload, dict), "snapshot is not a JSON object")
    stamp(payload.get("generatedAt"), "snapshot.generatedAt")
    rows = payload.get("rows")
    require(isinstance(rows, list) and len(rows) == len(instrument_ids),
            "snapshot does not contain the requested instrument rows")
    priced = live = stored = unavailable = 0
    for expected_id, row in zip(instrument_ids, rows):
        require(isinstance(row, dict) and row.get("instrumentId") == expected_id,
                "snapshot instrument identity/order mismatch")
        require(row.get("exchange") in ("NSE", "BSE")
                and isinstance(row.get("tradingSymbol"), str),
                "snapshot instrument venue/symbol missing")
        status = row.get("status")
        require(status in ("LIVE", "LAST_CLOSE", "MARKET_CLOSED", "UNAVAILABLE",
                           "CONNECTING", "RECONNECTING", "STALE"),
                "unknown quote status")
        price = row.get("ltp")
        source = row.get("source")
        exchanged = row.get("exchangeAt")
        if price is not None:
            require(isinstance(price, (int, float)) and not isinstance(price, bool)
                    and 0 < price < float("inf"), "invalid quote amount")
            tick_at = stamp(exchanged, "priced row exchangeAt")
            require(tick_at <= now + timedelta(seconds=5),
                    "quote exchangeAt is in the future")
            if status == "LIVE":
                require(not closed, "holiday/closed session falsely labelled LIVE")
                require(source == "ANGEL_ONE_SMARTAPI_WS_V2",
                        "LIVE row has unverified source")
                require(now - tick_at <= timedelta(minutes=2),
                        "old exchange tick falsely labelled LIVE")
                live += 1
            elif status == "LAST_CLOSE":
                require(source in ("STORED_LAST_LIVE_TICK", "STORED_LAST_DETAIL"),
                        "saved quote has an unrecognized source")
                stored += 1
            else:
                raise ContractFailure("unverified quote status includes a price")
            priced += 1
        else:
            require(exchanged is None and source is None,
                    "unpriced row carries misleading provenance")
            require(status != "LIVE" and status != "LAST_CLOSE",
                    "price-bearing status lacks a price")
            unavailable += 1
    return {"requested": len(rows), "priced": priced, "live": live,
            "stored": stored, "unavailable": unavailable}


def validated_overview(payload, *, closed, now):
    require(isinstance(payload, dict), "overview is not a JSON object")
    stamp(payload.get("generatedAt"), "overview.generatedAt")
    source = payload.get("source")
    require(source in ("ANGEL_ONE_SMARTAPI_FULL_BULK", "STORED_LAST_OVERVIEW"),
            "overview source is unrecognized")
    indices = payload.get("indices")
    require(isinstance(indices, list), "overview indices is not an array")
    as_of = payload.get("exchangeAsOf")
    if as_of is not None:
        require(stamp(as_of, "overview.exchangeAsOf") <= now + timedelta(seconds=5),
                "overview exchange timestamp is in the future")
    for index in indices:
        require(isinstance(index, dict), "malformed overview index")
        require(index.get("freshness") in ("LIVE", "STALE", "LAST_CLOSE"),
                "index freshness is missing")
        stamp(index.get("exchangeAt"), "index.exchangeAt")
        if closed:
            require(index["freshness"] != "LIVE",
                    "closed session index falsely labelled LIVE")
        if source == "STORED_LAST_OVERVIEW":
            require(index["freshness"] == "LAST_CLOSE",
                    "saved index falsely labelled current")
    if source == "STORED_LAST_OVERVIEW":
        require(payload.get("ideas") == [], "saved overview replays stale market ideas")
    universe = payload.get("universe")
    require(isinstance(universe, dict), "overview universe is missing")
    returned = universe.get("returned")
    require(isinstance(returned, int) and returned >= 0, "invalid equity coverage")
    return {"source": source, "indices": len(indices), "equities": returned}


def get_json(base_url, token, path):
    req = Request(base_url.rstrip("/") + path, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/json",
    })
    try:
        with urlopen(req, timeout=15) as response:
            require(response.status == 200, "Markets API returned a non-200 status")
            data = response.read(2_000_001)
            require(len(data) <= 2_000_000, "Markets API response is too large")
            return json.loads(data)
    except HTTPError as exc:
        raise ContractFailure("Markets API HTTP " + str(exc.code)) from None
    except (URLError, TimeoutError, ValueError) as exc:
        raise ContractFailure("Markets API is unreachable or returned invalid JSON") from None


def self_test():
    now = datetime(2026, 11, 10, 5, 30, tzinfo=timezone.utc)
    base = {"generatedAt": "2026-11-10T05:30:00Z", "rows": [{
        "instrumentId": 42, "exchange": "NSE", "tradingSymbol": "TEST-EQ",
        "ltp": 123.45, "exchangeAt": "2026-11-09T09:50:00Z",
        "status": "LAST_CLOSE", "source": "STORED_LAST_DETAIL",
    }]}
    require(validated_snapshot(base, [42], closed=True, now=now)["stored"] == 1,
            "saved quote contract self-test failed")
    spoof = json.loads(json.dumps(base))
    spoof["rows"][0]["status"] = "LIVE"
    try:
        validated_snapshot(spoof, [42], closed=True, now=now)
    except ContractFailure:
        pass
    else:
        raise ContractFailure("LIVE holiday spoof was accepted")
    missing = json.loads(json.dumps(base))
    missing["rows"][0]["exchangeAt"] = None
    try:
        validated_snapshot(missing, [42], closed=True, now=now)
    except ContractFailure:
        pass
    else:
        raise ContractFailure("missing exchange timestamp was accepted")
    overview = {"source": "STORED_LAST_OVERVIEW",
                "generatedAt": "2026-11-10T05:30:00Z",
                "exchangeAsOf": "2026-11-09T09:50:00Z",
                "universe": {"returned": 10}, "ideas": [],
                "indices": [{"freshness": "LAST_CLOSE",
                             "exchangeAt": "2026-11-09T09:50:00Z"}]}
    require(validated_overview(overview, closed=True, now=now)["indices"] == 1,
            "overview contract self-test failed")
    print("VALIDATOR_SELF_TEST: PASS (fixtures only, not production)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("MINTTICK_API_BASE_URL"))
    parser.add_argument("--instrument-ids", help="Comma-separated known cash-equity IDs")
    parser.add_argument("--expect-closed", action="store_true",
                        help="Reject any LIVE market-data classification")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    token = os.environ.get("MINTTICK_TEST_ACCESS_TOKEN", "")
    require(bool(token), "MINTTICK_TEST_ACCESS_TOKEN is required for a real authenticated test")
    require(bool(args.base_url) and args.base_url.startswith("https://"),
            "An HTTPS --base-url is required")
    now = datetime.now(timezone.utc)
    if args.instrument_ids:
        try:
            ids = [int(item) for item in args.instrument_ids.split(",")]
        except ValueError as exc:
            raise ContractFailure("Invalid instrument IDs") from exc
    else:
        page = get_json(args.base_url, token, "/api/customer/market-data/instruments?size=30&page=0")
        rows = page.get("content") if isinstance(page, dict) else None
        require(isinstance(rows, list), "authenticated catalogue response is malformed")
        ids = [row["id"] for row in rows if isinstance(row, dict) and
               isinstance(row.get("id"), int)]
    require(0 < len(ids) <= 30 and len(set(ids)) == len(ids) and min(ids) > 0,
            "Choose 1-30 unique positive cash-equity instrument IDs")
    print("AUTHENTICATED_CATALOGUE: PASS")
    params = urlencode({"instrumentIds": ",".join(str(item) for item in ids)})
    snapshot = get_json(args.base_url, token, "/api/customer/market-data/snapshot?" + params)
    result = validated_snapshot(snapshot, ids, closed=args.expect_closed, now=now)
    print("SNAPSHOT_PROVENANCE: PASS")
    print("SNAPSHOT_COVERAGE: requested={requested} priced={priced} stored={stored} live={live} missing={unavailable}".format(**result))
    overview = get_json(args.base_url, token, "/api/customer/market-data/overview")
    evidence = validated_overview(overview, closed=args.expect_closed, now=now)
    print("OVERVIEW_PROVENANCE: PASS")
    print("OVERVIEW_COVERAGE: source={source} indices={indices} equities={equities}".format(**evidence))
    require(result["priced"] > 0, "No source-backed price was served for sampled equities")
    require(evidence["indices"] > 0, "No source-backed index quote was served")
    print("CLOSED_MARKET_ACCEPTANCE: PASS" if args.expect_closed else "MARKETS_API_ACCEPTANCE: PASS")


if __name__ == "__main__":
    try:
        main()
    except ContractFailure as error:
        print("MARKETS_ACCEPTANCE: FAIL: " + str(error), file=sys.stderr)
        sys.exit(1)
