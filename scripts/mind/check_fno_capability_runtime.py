#!/usr/bin/env python3
"""Read-only, privacy-preserving F&O availability/expiry triage for MintTick.

Checks two existing customer GET responses even when the NSE session is closed.
A passing observation is not proof of market-data entitlement or trade readiness.
No personal values, quotes, instrument IDs, tokens or raw response are emitted.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

CAPABILITY_MARKER = "MIND_FNO_CAPABILITY_RUNTIME:"
EXPIRIES_MARKER = "MIND_FNO_EXPIRIES_RUNTIME:"
STATES = {
    "CONTRACTS_LISTED_PRICES_NOT_AUTHORIZED",
    "DISPLAY_RIGHTS_NOT_VERIFIED",
    "FNO_CONTRACT_SOURCE_UNAVAILABLE",
    "MARKET_CLOSED",
    "FNO_V3_FEED_UNAVAILABLE",
    "LIVE_QUOTES",
    "WAITING_FOR_QUOTES",
    "FEED_DISCONNECTED",
}
EXPIRY_STATES = {"VERIFIED", "NO_LISTED_EXPIRIES", "BLOCKED", "UNAVAILABLE",
                 "PROVIDER_UNAVAILABLE", "STALE", "SOURCE_UNAVAILABLE"}
SAFE_CODE = re.compile(r"[A-Z][A-Z0-9_]{0,80}\Z")
BOOLEANS = ("sourceReady", "feedConfigured", "marketOpen",
            "displayRightsVerified", "contractDiscoveryVerified")


def _body(raw):
    if not isinstance(raw, bytes) or len(raw) > 8192:
        raise ValueError("INVALID_BOUNDED_RESPONSE")
    try:
        value = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise ValueError("INVALID_JSON") from None
    if not isinstance(value, dict):
        raise ValueError("INVALID_RESPONSE_TYPE")
    return value


def _status(code, marker):
    if code in (401, 403):
        return marker + "status=AUTH_DENIED;orders=false", False
    if code == 404:
        return marker + "status=ROUTE_NOT_DEPLOYED;orders=false", False
    if code == 409:
        return marker + "status=CONFIGURATION_BLOCKED;orders=false", True
    if code >= 500:
        return marker + "status=BACKEND_ERROR;orders=false", False
    return marker + "status=HTTP_UNEXPECTED;orders=false", False


def capability(code, raw):
    if code != 200:
        return _status(code, CAPABILITY_MARKER)
    try:
        data = _body(raw)
        if any(type(data.get(k)) is not bool for k in BOOLEANS):
            raise ValueError("INVALID_BOOLEAN_FIELDS")
        status = data.get("status")
        if status not in STATES:
            raise ValueError("INVALID_STATUS")
        state = data.get("streamState")
        if not isinstance(state, str) or not SAFE_CODE.fullmatch(state):
            raise ValueError("INVALID_STREAM_STATE")
        metrics = ("freshExchangeTimedQuotes", "freshDisplayQuotes", "subscribedInstruments")
        values = [data.get(key) for key in metrics]
        if any(type(v) is not int or v < 0 or v > 200000 for v in values):
            raise ValueError("INVALID_COUNTS")
        fields = ";".join(k + "=" + str(data[k]).lower() for k in BOOLEANS)
        return (CAPABILITY_MARKER + "status=" + status + ";" + fields +
                ";freshDepthQuotes=" + str(values[0]) +
                ";freshDisplayQuotes=" + str(values[1]) +
                ";subscriptions=" + str(values[2]) +
                ";orders=false;independentE2E=false"), True
    except (ValueError, TypeError):
        return CAPABILITY_MARKER + "status=INVALID_RESPONSE;orders=false", False


def expiries(code, raw):
    if code != 200:
        return _status(code, EXPIRIES_MARKER)
    try:
        data = _body(raw)
        if data.get("underlyingKey") != "NSE_INDEX|Nifty 50":
            raise ValueError("WRONG_UNDERLYING")
        status = data.get("status")
        if status not in EXPIRY_STATES:
            raise ValueError("INVALID_STATUS")
        dates = data.get("expiries")
        if not isinstance(dates, list) or len(dates) > 120:
            raise ValueError("INVALID_EXPIRIES")
        # Never log the provider-listed instruments or expiry details.
        if any(not isinstance(v, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", v) for v in dates):
            raise ValueError("INVALID_EXPIRY_DATE")
        if len(set(dates)) != len(dates):
            raise ValueError("DUPLICATE_EXPIRIES")
        if status == "VERIFIED" and not dates:
            raise ValueError("FALSE_VERIFIED")
        return (EXPIRIES_MARKER + "status=" + status +
                ";listed=" + str(len(dates)) +
                ";orders=false;independentE2E=false"), True
    except (ValueError, TypeError):
        return EXPIRIES_MARKER + "status=INVALID_RESPONSE;orders=false", False


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 3 or args[0] not in ("capability", "expiries"):
        print("MIND_FNO_DIAG:INVALID_ARGUMENTS")
        return 2
    try:
        code = int(args[1])
        response = Path(args[2])
        if not response.is_file() or response.is_symlink() or response.stat().st_size > 8192:
            raise ValueError("BAD_FILE")
        content = response.read_bytes()
    except (ValueError, TypeError, OSError):
        print("MIND_FNO_DIAG:UNAVAILABLE_RESPONSE")
        return 1
    label, ok = (capability if args[0] == "capability" else expiries)(code, content)
    print(label)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
