#!/usr/bin/env python3
"""Bounded owner-only historical source smoke check on existing private EC2.

Print only fixed categorical statuses and a bounded validated candle count.
Never print the operator API key, raw HTTP response, symbol token, or OHLCV.
"""
import hmac
import json
import pathlib
import re
import stat
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = pathlib.Path("/opt/aitradingsystem-paper")
ALLOWED = {
    "VALID_CANDLES", "COOLDOWN", "OWNER_FEED_UNAVAILABLE",
    "SAMPLE_INSTRUMENT_MISSING", "NO_COMPLETED_SESSION",
    "INVALID_PROVIDER_RESPONSE", "NO_PROVIDER_CANDLES",
    "PROVIDER_RATE_LIMITED", "PROVIDER_REJECTED", "PROVIDER_UNAVAILABLE",
}


def operator_key(path):
    try:
        if not path.is_file() or path.is_symlink() or stat.S_IMODE(path.stat().st_mode) != 0o600:
            return ""
        matches = [line[len("APP_API_KEY="):] for line in path.read_text().splitlines()
                   if line.startswith("APP_API_KEY=")]
        if len(matches) != 1 or not re.fullmatch(r"[!-~]{16,256}", matches[0]):
            return ""
        return matches[0]
    except (OSError, ValueError):
        return ""


def main():
    if not ROOT.is_dir() or ROOT.is_symlink():
        print("OWNER_HISTORY_SOURCE: HOST_UNAVAILABLE")
        return 2
    a = operator_key(ROOT / "runtime.env")
    b = operator_key(ROOT / ".env")
    if not a or not b or not hmac.compare_digest(a, b):
        print("OWNER_HISTORY_SOURCE: OPERATOR_UNAVAILABLE")
        return 2
    request = Request(
        "http://127.0.0.1:8080/api/market-data/candles/owner-source-probe",
        headers={"X-API-Key": a, "Accept": "application/json",
                 "Content-Type": "application/json"},
        data=b"{}", method="POST",
    )
    try:
        with urlopen(request, timeout=40) as response:
            if response.status != 200:
                raise ValueError("unapproved response")
            raw = response.read(2049)
    except (HTTPError, URLError, OSError, TimeoutError, ValueError):
        print("OWNER_HISTORY_SOURCE: HTTP_UNAVAILABLE")
        return 2
    if len(raw) > 2048:
        print("OWNER_HISTORY_SOURCE: INVALID_RESPONSE")
        return 2
    try:
        result = json.loads(raw)
        state = result.get("status")
        n = result.get("validatedRows")
        if (not isinstance(result, dict) or state not in ALLOWED
            or type(n) is not int or not 0 <= n <= 100
            or result.get("stored") is not False
            or result.get("redistributionRightsVerified") is not False
            or result.get("tradingEnabled") is not False
            or (state == "VALID_CANDLES" and n == 0)
            or (state != "VALID_CANDLES" and n != 0)):
            raise ValueError("unapproved response")
    except (ValueError, TypeError, AttributeError):
        print("OWNER_HISTORY_SOURCE: INVALID_RESPONSE")
        return 2
    print("OWNER_HISTORY_SOURCE: " + state)
    print("OWNER_HISTORY_VALIDATED_ROWS: " + str(n))
    return 0 if state == "VALID_CANDLES" else 2


if __name__ == "__main__":
    raise SystemExit(main())
