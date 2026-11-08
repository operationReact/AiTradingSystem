#!/usr/bin/env python3
"""Read-only analysis of public Angel One daily master; prints aggregated counts only.

No credentials, customer records, raw symbols, token IDs or market prices.
A diagnostic only: intentionally does not import or alter DB state.
"""
import collections
import datetime
import decimal
import json
import re
import urllib.request

URL = "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json"
MAX_BYTES = 60 * 1024 * 1024
SEGMENTS = {
    "NSE", "NSE_CM", "BSE", "BSE_CM", "NFO", "NSE_FO",
    "BFO", "BSE_FO", "MCX", "MCX_FO", "CDS", "BCD",
}
SYMBOL = re.compile(r"[A-Za-z0-9&._/-]{1,100}\Z")
TOKEN = re.compile(r"[0-9]{1,32}\Z")


def text(row, key, maximum):
    value = row.get(key)
    if value is None:
        return None
    # Jackson JsonNode.asText() represents non-string scalars as string.
    value = str(value).strip()
    if not value:
        return None
    if len(value) > maximum:
        raise ValueError(key + "_TOO_LONG")
    return value


def required(row, key, maximum):
    value = text(row, key, maximum)
    if value is None:
        raise ValueError(key + "_MISSING")
    return value


def number(value, key, positive=False, allow_minus_one=False, max_scale=6):
    try:
        n = decimal.Decimal(value)
    except (decimal.InvalidOperation, ValueError):
        raise ValueError(key + "_NON_NUMERIC") from None
    if not n.is_finite():
        raise ValueError(key + "_NON_FINITE")
    if allow_minus_one and n == -1:
        return n
    if positive and n <= 0:
        raise ValueError(key + "_NON_POSITIVE")
    if not positive and not allow_minus_one and n < 0:
        raise ValueError(key + "_NEGATIVE")
    if allow_minus_one and n < 0:
        raise ValueError(key + "_NEGATIVE")
    if -n.as_tuple().exponent > max_scale:
        raise ValueError(key + "_SCALE")
    if len(n.as_tuple().digits) > 20:
        raise ValueError(key + "_PRECISION")
    return n


def check(row):
    if not isinstance(row, dict):
        raise ValueError("NOT_OBJECT")
    token = required(row, "token", 32)
    symbol = required(row, "symbol", 100)
    text(row, "name", 200)
    segment = required(row, "exch_seg", 20).upper()
    if segment not in SEGMENTS:
        raise ValueError("SEGMENT_UNSUPPORTED")
    if not TOKEN.fullmatch(token):
        raise ValueError("TOKEN_INVALID")
    if not SYMBOL.fullmatch(symbol):
        raise ValueError("SYMBOL_INVALID")
    lot = required(row, "lotsize", 20)
    try:
        if not re.fullmatch(r"[+]?[0-9]+", lot) or not (0 < int(lot) <= 2147483647):
            raise ValueError()
    except ValueError:
        raise ValueError("LOTSIZE_INVALID") from None
    number(required(row, "tick_size", 50), "TICK", positive=True, max_scale=8)
    text(row, "instrumenttype", 30)
    expiry = text(row, "expiry", 20)
    if expiry:
        valid = False
        for fmt in ("%d%b%Y", "%Y-%m-%d"):
            try:
                datetime.datetime.strptime(expiry.title(), fmt)
                valid = True
                break
            except ValueError:
                pass
        if not valid:
            raise ValueError("EXPIRY_INVALID")
    strike = text(row, "strike", 50)
    if strike is not None:
        number(strike, "STRIKE", allow_minus_one=True, max_scale=6)
    return segment, symbol.endswith("-EQ")


def main():
    request = urllib.request.Request(URL, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=100) as response:
        if response.status != 200:
            raise SystemExit("SOURCE_HTTP_NON_200")
        data = response.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise SystemExit("SOURCE_TOO_LARGE")
    rows = json.loads(data)
    if not isinstance(rows, list):
        raise SystemExit("INVALID_ROOT")
    reasons, segments, accepted_segments = collections.Counter(), collections.Counter(), collections.Counter()
    accepted = equities = 0
    for row in rows:
        if isinstance(row, dict):
            segment = str(row.get("exch_seg", "<missing>")).upper()
            segments[segment] += 1
        try:
            segment, is_eq = check(row)
            accepted_segments[segment] += 1
            accepted += 1
            if segment in {"NSE", "NSE_CM", "BSE", "BSE_CM"} and is_eq:
                equities += 1
        except (ValueError, TypeError) as e:
            reasons[str(e)] += 1
    print("MINTTICK_AUDIT_ROWS:", len(rows))
    print("MINTTICK_AUDIT_BYTES:", len(data))
    print("MINTTICK_AUDIT_ACCEPTED:", accepted)
    print("MINTTICK_AUDIT_REJECTED:", sum(reasons.values()))
    print("MINTTICK_AUDIT_ACCEPTED_CASH_EQ:", equities)
    print("MINTTICK_AUDIT_REJECTION_PERCENT:", round(100 * sum(reasons.values()) / max(1, len(rows)), 2))
    for key, count in reasons.most_common(20):
        print("MINTTICK_REJECT_REASON:", key, count)
    for key, count in segments.most_common(20):
        print("MINTTICK_SOURCE_SEGMENT:", key, count)
    for key, count in accepted_segments.most_common(12):
        print("MINTTICK_ACCEPTED_SEGMENT:", key, count)


if __name__ == "__main__":
    main()
