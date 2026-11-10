#!/usr/bin/env python3
"""One strictly pinned 2,000-day ONE_DAY Angel One depth request; never store OHLCV.

SSM assembles this with the previously reviewed owner authentication helper
in-memory; only bounded, sanitized counts and dates leave the EC2 host.
This is a source AVAILABILITY probe, not Golden Source licensed ingestion.
"""
import datetime as dt
import decimal
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

# Only these four audited windows can ever be selected. Default phase 1.
# The last phase stops at the user's 2026-11-10 target date.
WINDOWS = {
    "1": (dt.date(2006, 11, 10), dt.date(2012, 5, 1)),
    "2": (dt.date(2012, 5, 2), dt.date(2017, 10, 22)),
    "3": (dt.date(2017, 10, 23), dt.date(2023, 4, 14)),
    "4": (dt.date(2023, 4, 15), dt.date(2026, 11, 10)),
}
PHASE = os.environ.get("MINTTICK_DEPTH_PHASE", "1")
if PHASE not in WINDOWS:
    raise ValueError("only four reviewed historical depth phases are permitted")
START, END = WINDOWS[PHASE]
if (END - START).days + 1 > 2000:
    raise AssertionError("historical window exceeds Angel One daily request limit")
MAX_ROWS = 2000
MAX_RESPONSE_BYTES = 262144
NSE_TOKEN = "2885"  # current known RELIANCE-EQ token, historical identity unverified
CANDLE_URL = API_ROOT + "/rest/secure/angelbroking/historical/v1/getCandleData"
STATUS = frozenset({
    "NONEMPTY", "EMPTY_OR_UNSUPPORTED", "INVALID_RESPONSE", "PROVIDER_REJECTED",
    "PROVIDER_RATE_LIMITED", "NETWORK_UNAVAILABLE", "SOURCE_UNAVAILABLE",
    "OWNER_AUTH_UNAVAILABLE", "IP_UNAVAILABLE", "SOURCE_OUT_OF_RANGE",
    "INCONSISTENT_CANDLES",
})


def candle_summary(raw, start=START, end=END):
    """Validate shapes, timestamps and OHLCV in memory; return metadata ONLY."""
    if not isinstance(raw, dict) or raw.get("status") is not True:
        return "PROVIDER_REJECTED", None
    rows = raw.get("data")
    if rows is None or rows == []:
        return "EMPTY_OR_UNSUPPORTED", None
    if not isinstance(rows, list) or len(rows) > MAX_ROWS:
        return "INVALID_RESPONSE", None
    first = last = None
    previous = None
    years = {}
    for item in rows:
        if not isinstance(item, list) or len(item) != 6:
            return "INVALID_RESPONSE", None
        try:
            instant = dt.datetime.fromisoformat(item[0])
            if instant.tzinfo is None:
                return "INVALID_RESPONSE", None
            day = instant.astimezone(ZoneInfo("Asia/Kolkata")).date()
            prices = [decimal.Decimal(str(v)) for v in item[1:5]]
            volume = decimal.Decimal(str(item[5]))
            if (any(not v.is_finite() or v <= 0 for v in prices)
                    or not volume.is_finite() or volume < 0
                    or volume != volume.to_integral_value()):
                return "INCONSISTENT_CANDLES", None
            op, high, low, close = prices
            if high < max(op, close) or low > min(op, close) or low > high:
                return "INCONSISTENT_CANDLES", None
            if day < start or day > end:
                return "SOURCE_OUT_OF_RANGE", None
            if previous is not None and day <= previous:
                return "INCONSISTENT_CANDLES", None
            previous = day
            if first is None:
                first = day
            last = day
            years[day.year] = years.get(day.year, 0) + 1
        except (ValueError, TypeError, decimal.InvalidOperation, OverflowError):
            return "INVALID_RESPONSE", None
    return "NONEMPTY", (len(rows), first.isoformat(), last.isoformat(), years)


def history_request(headers):
    data = json.dumps({
        "exchange": "NSE", "symboltoken": NSE_TOKEN, "interval": "ONE_DAY",
        "fromdate": START.isoformat() + " 00:00",
        "todate": END.isoformat() + " 15:30",
    }, separators=(",", ":")).encode("utf-8")
    request = Request(CANDLE_URL, data=data, headers=headers, method="POST")
    try:
        with urlopen(request, timeout=25) as response:
            if response.status != 200:
                return "SOURCE_UNAVAILABLE", None
            content = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as error:
        if error.code == 429:
            return "PROVIDER_RATE_LIMITED", None
        if error.code in (401, 403, 400, 422):
            return "PROVIDER_REJECTED", None
        return "SOURCE_UNAVAILABLE", None
    except (OSError, URLError, TimeoutError):
        return "NETWORK_UNAVAILABLE", None
    if len(content) > MAX_RESPONSE_BYTES:
        return "INVALID_RESPONSE", None
    try:
        return candle_summary(json.loads(content))
    except (ValueError, TypeError, UnicodeError):
        return "INVALID_RESPONSE", None


def run_depth():
    # Helpers come from the already reviewed, in-memory authentication probe.
    egress()
    network = verified_client_network_headers()
    params = ssm_params()
    if (not re.fullmatch(r"[A-Za-z0-9._-]{4,20}", params["client_code"])
            or not re.fullmatch(r"[0-9]{4,8}", params["pin"])):
        return "OWNER_AUTH_UNAVAILABLE", None
    headers = {
        "Content-Type": "application/json", "Accept": "application/json",
        "X-PrivateKey": params["api_key"], "X-UserType": "USER",
        "X-SourceID": "WEB", **network,
    }
    body = json.dumps({
        "clientcode": params["client_code"], "password": params["pin"],
        "totp": totp(params["totp_secret"]),
    }, separators=(",", ":")).encode("utf-8")
    auth = request_json(API_ROOT + "/rest/auth/angelbroking/user/v1/loginByPassword",
                        "POST", headers, body)
    jwt, feed = auth.get("jwtToken"), auth.get("feedToken")
    if not isinstance(jwt, str) or not jwt or not isinstance(feed, str) or not feed:
        return "OWNER_AUTH_UNAVAILABLE", None
    profile = request_json(API_ROOT + "/rest/secure/angelbroking/user/v1/getProfile",
                           "GET", {**headers, "Authorization": "Bearer " + jwt})
    verified_code = profile.get("clientcode", profile.get("clientCode"))
    if not isinstance(verified_code, str) or not hmac.compare_digest(
            verified_code.strip().upper(), params["client_code"].strip().upper()):
        return "OWNER_AUTH_UNAVAILABLE", None
    return history_request({**headers, "Authorization": "Bearer " + jwt})


def main():
    print("DEPTH_PHASE: " + PHASE, flush=True)
    print("DEPTH_WINDOW_START: " + START.isoformat(), flush=True)
    print("DEPTH_WINDOW_END: " + END.isoformat(), flush=True)
    print("DEPTH_INSTRUMENT: RELIANCE_EQ_PROBE", flush=True)
    state, report = "SOURCE_UNAVAILABLE", None
    try:
        state, report = run_depth()
    except ProbeFailure as failure:
        # Do not surface any auth/SSM error strings or provider responses.
        state = "IP_UNAVAILABLE" if str(failure).startswith(("EC2_EGRESS:", "INSTANCE_NETWORK_HEADERS:")) else "OWNER_AUTH_UNAVAILABLE"
    except Exception:
        state = "SOURCE_UNAVAILABLE"
    if state not in STATUS:
        state, report = "INVALID_RESPONSE", None
    print("DEPTH_STATUS: " + state, flush=True)
    if state == "NONEMPTY" and report is not None:
        count, first, last, years = report
        print("DEPTH_VALIDATED_ROWS: " + str(count), flush=True)
        print("DEPTH_FIRST_CANDLE: " + first, flush=True)
        print("DEPTH_LAST_CANDLE: " + last, flush=True)
        for year in range(START.year, END.year + 1):
            print("DEPTH_YEAR_" + str(year) + ": " + str(years.get(year, 0)), flush=True)
    print("DEPTH_STORED: FALSE", flush=True)
    print("DEPTH_LICENCE_VERIFIED: FALSE", flush=True)
    return 0 if state == "NONEMPTY" else 2


if __name__ == "__main__":
    sys.exit(main())
