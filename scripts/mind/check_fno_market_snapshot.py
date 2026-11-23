#!/usr/bin/env python3
"""Read-only audit of Options market responses with real last-trade evidence.

No quoted prices, customer data, contract keys or tokens are logged. Explicitly
separates exchange-timed V3 retained LTP (leg.ltp + exchangeAt) from V2
chainSnapshotIndicators which have no independently verified exchange time.
These counts are display diagnostics, NEVER order or strategy E2E approval.
"""
from __future__ import annotations

import datetime as dt
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")
MARKER = "MIND_FNO_MARKET_SNAPSHOT:"
MAX_BYTES = 2_000_000
KEY = re.compile(r"NSE_FO\|[A-Za-z0-9._-]{1,64}\Z")
STATUS = {"MARKET_CLOSED", "FRESH_QUOTES", "NO_FRESH_QUOTES"}


def _json(raw):
    if not isinstance(raw, bytes) or len(raw) > MAX_BYTES:
        raise ValueError("OVERSIZED_RESPONSE")
    try:
        v = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise ValueError("INVALID_JSON") from None
    if not isinstance(v, dict):
        raise ValueError("NOT_OBJECT")
    return v


def _price(value):
    if isinstance(value, bool) or value is None:
        return False
    try:
        n = Decimal(str(value))
    except (ValueError, TypeError, InvalidOperation):
        return False
    return n.is_finite() and n > 0


def select_expiry(raw, now=None):
    """Read first future/recent listed expiry; only prints date, never identities."""
    data = _json(raw)
    if data.get("status") != "VERIFIED" or data.get("underlyingKey") != "NSE_INDEX|Nifty 50":
        return None
    dates = data.get("expiries")
    if not isinstance(dates, list) or len(dates) > 120:
        return None
    today = (now or dt.datetime.now(IST)).astimezone(IST).date()
    parsed = []
    for value in dates:
        if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            return None
        try:
            day = dt.date.fromisoformat(value)
        except ValueError:
            return None
        if today <= day <= today + dt.timedelta(days=90):
            parsed.append(day)
    if len(parsed) != len(dates) or len(set(parsed)) != len(parsed):
        return None
    return min(parsed).isoformat() if parsed else None


def classify(status, raw, now=None):
    if status in (401,403):
        return MARKER + "status=AUTH_DENIED;orders=false", False
    if status == 404:
        return MARKER + "status=ROUTE_NOT_DEPLOYED;orders=false", False
    if status == 409:
        return MARKER + "status=CONFIGURATION_BLOCKED;orders=false", False
    if status >= 500:
        return MARKER + "status=PROVIDER_OR_BACKEND_UNAVAILABLE;orders=false", False
    if status != 200:
        return MARKER + "status=UNEXPECTED_HTTP;orders=false", False
    try:
        data = _json(raw)
        if (data.get("underlyingKey") != "NSE_INDEX|Nifty 50" or
                data.get("status") not in STATUS or
                data.get("source") != "UPSTOX_ANALYTICS" or
                data.get("displayRightsVerified") is not True or
                data.get("liveOrderEnabled") is not False):
            raise ValueError("WRONG_MARKET_CONTRACT")
        strikes = data.get("strikes")
        if not isinstance(strikes, list) or not 0 < len(strikes) <= 1024:
            raise ValueError("NO_VALID_STRIKES")
        seen = set()
        trades = 0
        fresh = 0
        has_reference = 0
        last_closes = 0
        for row in strikes:
            if not isinstance(row, dict) or not _price(row.get("strikePrice")):
                raise ValueError("INVALID_STRIKE")
            for field, side in (("call","CE"),("put","PE")):
                leg = row.get(field)
                if not isinstance(leg, dict) or leg.get("side") != side:
                    raise ValueError("NO_LISTED_LEG")
                key = leg.get("instrumentKey")
                if not isinstance(key, str) or not KEY.fullmatch(key) or key in seen:
                    raise ValueError("INVALID_OR_DUPLICATE_LEG")
                seen.add(key)
                if type(leg.get("lotSize")) is not int or leg["lotSize"] <= 0:
                    raise ValueError("INVALID_LOT")
                is_fresh = leg.get("quoteFresh")
                if type(is_fresh) is not bool:
                    raise ValueError("INVALID_FRESH_FLAG")
                if is_fresh:
                    fresh += 1
                if _price(leg.get("ltp")) and isinstance(leg.get("exchangeAt"), str):
                    trades += 1
                if _price(leg.get("previousClose")):
                    last_closes += 1
        indicators = data.get("chainSnapshotIndicators")
        if not isinstance(indicators, dict) or len(indicators) > 2048:
            raise ValueError("INVALID_INDICATOR_MAP")
        v2_reference = 0
        for instrument, quote in indicators.items():
            if instrument not in seen or not isinstance(quote, dict):
                raise ValueError("INDICATOR_NOT_LISTED")
            if _price(quote.get("ltp")):
                v2_reference += 1
        # The Flutter UI intentionally hides V2 reference values if the
        # server's provider-snapshot timestamp is >5 minutes old. Report this
        # as a separate count; it may explain blank fields even when the
        # server delivers authentic provider snapshots for nearly every leg.
        age_label = "MISSING_OR_INVALID"
        flutter_visible = 0
        timestamp = data.get("snapshotAt")
        current = now or dt.datetime.now(dt.timezone.utc)
        if current.tzinfo is None:
            raise ValueError("NAIVE_CLOCK")
        if isinstance(timestamp, str) and len(timestamp) <= 64:
            try:
                stamp = dt.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
                if stamp.tzinfo is not None:
                    age = (current.astimezone(dt.timezone.utc) -
                           stamp.astimezone(dt.timezone.utc)).total_seconds()
                    if age < 0:
                        age_label = "FUTURE"
                    elif age <= 300:
                        age_label = "WITHIN_5_MIN"
                        flutter_visible = v2_reference
                    elif age <= 86400:
                        age_label = "OLDER_5_MIN_UNDER_24H"
                    else:
                        age_label = "OLDER_24H"
            except ValueError:
                pass
        status_label = data["status"]
        if status_label == "MARKET_CLOSED" and fresh:
            raise ValueError("FRESH_TICKS_OUTSIDE_SESSION")
        if status_label == "FRESH_QUOTES" and fresh == 0:
            raise ValueError("FALSE_FRESH_QUOTES_STATUS")
        if status_label == "NO_FRESH_QUOTES" and fresh:
            raise ValueError("FALSE_MISSING_QUOTES_STATUS")
        return (
            MARKER + "status=" + status_label +
            ";strikes=" + str(len(strikes)) +
            ";contractLegs=" + str(len(seen)) +
            ";exchangeTimedLastTrades=" + str(trades) +
            ";previousCloseLegs=" + str(last_closes) +
            ";v2ChainLtpIndicators=" + str(v2_reference) +
            ";flutterVisibleV2Indicators=" + str(flutter_visible) +
            ";snapshotAge=" + age_label +
            ";freshV3Legs=" + str(fresh) +
            ";orders=false;execution=false;independentE2E=false"
        ), True
    except (ValueError, TypeError):
        return MARKER + "status=INVALID_MARKET_RESPONSE;orders=false", False


def main(args=None):
    args = sys.argv[1:] if args is None else args
    if not args or args[0] not in ("expiry", "market"):
        print(MARKER + "status=INVALID_ARGUMENTS;orders=false")
        return 2
    try:
        if args[0] == "expiry" and len(args) == 2:
            path = Path(args[1])
            if not path.is_file() or path.is_symlink() or path.stat().st_size > 8192:
                raise ValueError("INVALID_EXPIRY_BODY")
            exp = select_expiry(path.read_bytes())
            if not exp:
                return 1
            print(exp)
            return 0
        if args[0] == "market" and len(args) == 3:
            path = Path(args[2])
            if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_BYTES:
                raise ValueError("INVALID_MARKET_BODY")
            result, valid = classify(int(args[1]), path.read_bytes())
            print(result)
            return 0 if valid else 1
    except (OSError, ValueError, TypeError):
        print(MARKER + "status=INVALID_OR_UNAVAILABLE;orders=false")
        return 1
    print(MARKER + "status=INVALID_ARGUMENTS;orders=false")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
