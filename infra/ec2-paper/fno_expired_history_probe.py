#!/usr/bin/env python3
"""Read-only 3-GET Upstox expired NSE_FO source probe; no archive or trades.

Only on exact private PAPER EC2: fetch existing SSM analytics token in memory,
verify Plus expiry access, one expired NIFTY future and seven days of daily OHLCV/OI.
Only bounded status/count markers leave the host, not tokens or candle values.
"""
from __future__ import annotations

import base64
from datetime import date, datetime, timedelta
import gzip
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo

REGION = "ap-south-1"
ACCOUNT = "098859530205"
INSTANCE = "i-09c01b470fb39b85a"
UNDERLYING = "NSE_INDEX|Nifty 50"
SSM_TOKEN = "/minttick/upstox/analytics-token"
BASE = "https://api.upstox.com/v2/expired-instruments"
UPSTOX_USER_AGENT = "MintTick-Upstox-ReadOnly-Diagnostics/1.0"
REQUEST = "infra/ec2-paper/requests/fno-expired-history-probe.json"
MARKER = "MINTTICK_FNO_EXPIRED_HISTORY:"
STATES = {
    "HISTORY_SOURCE_VERIFIED", "PLUS_ENTITLEMENT_MISSING", "TOKEN_UNAVAILABLE",
    "AUTH_REJECTED", "RATE_LIMITED", "PROVIDER_UNAVAILABLE", "PROVIDER_REJECTED",
    "INVALID_PROVIDER_DATA", "NO_EXPIRED_EXPIRIES", "NO_MATCHING_FUTURES",
    "NO_VALID_CANDLES", "HOST_IDENTITY_INVALID", "HOST_ENV_INVALID",
}
STAGES = ("EXPIRIES", "CONTRACTS", "CANDLES")
HTTP_REASON = {
    400: "BAD_REQUEST", 401: "UNAUTHORIZED", 403: "FORBIDDEN",
    404: "NOT_FOUND", 422: "UNPROCESSABLE", 429: "RATE_LIMITED",
    500: "INTERNAL_ERROR", 502: "BAD_GATEWAY",
    503: "SERVICE_UNAVAILABLE", 504: "GATEWAY_TIMEOUT",
}
STATES |= {stage + "_" + reason for stage in STAGES
           for reason in (*HTTP_REASON.values(), "OTHER_HTTP", "TRANSPORT_FAILURE")}


class Blocked(Exception):
    def __init__(self, code):
        self.code = code if code in STATES else "INVALID_PROVIDER_DATA"
        super().__init__(self.code)


def expected_request():
    return {
        "application": "MintTick",
        "operation": "read-only-upstox-expired-nifty-future-history-probe",
        "account": ACCOUNT, "region": REGION, "instance_id": INSTANCE,
        "environment": "PAPER",
        "authorization": "OWNER_APPROVED_FNO_HISTORY_DIAGNOSTIC_ONLY",
        "max_provider_gets": 3, "persist": False,
        "trading_rights_change": False,
    }


def classify_http(status, data, stage):
    """Classify only HTTP code + fixed endpoint stage; never provider text."""
    if stage not in STAGES:
        return "INVALID_PROVIDER_DATA"
    if status == 403 and b"UDAPI1149" in data[:4096]:
        return "PLUS_ENTITLEMENT_MISSING"
    return stage + "_" + HTTP_REASON.get(status, "OTHER_HTTP")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get_json(url, token, stage):
    parts = urllib.parse.urlsplit(url)
    if (parts.scheme != "https" or parts.netloc != "api.upstox.com"
            or not parts.path.startswith("/v2/expired-instruments/")
            or parts.fragment or len(url) > 800):
        raise Blocked("INVALID_PROVIDER_DATA")
    opener = urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(url, method="GET", headers={
        "Authorization": "Bearer " + token, "Accept": "application/json",
        "User-Agent": UPSTOX_USER_AGENT,
    })
    try:
        with opener.open(request, timeout=12) as response:
            if response.status != 200:
                raise Blocked(classify_http(response.status, b"", stage))
            body = response.read(65537)
    except urllib.error.HTTPError as error:
        raise Blocked(classify_http(error.code, error.read(4097), stage)) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise Blocked(stage + "_TRANSPORT_FAILURE") from None
    if len(body) > 65536:
        raise Blocked("INVALID_PROVIDER_DATA")
    try:
        return json.loads(body)
    except (UnicodeError, ValueError):
        raise Blocked("INVALID_PROVIDER_DATA") from None


def expiries(body, today):
    if not isinstance(body, dict) or body.get("status") != "success":
        raise Blocked("INVALID_PROVIDER_DATA")
    data = body.get("data")
    if not isinstance(data, list) or len(data) > 128:
        raise Blocked("INVALID_PROVIDER_DATA")
    found = set()
    for value in data:
        if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            raise Blocked("INVALID_PROVIDER_DATA")
        try:
            expiry = date.fromisoformat(value)
        except ValueError:
            raise Blocked("INVALID_PROVIDER_DATA") from None
        if expiry < today:
            found.add(expiry)
    return sorted(found, reverse=True)


def future_key(body, expiry):
    if not isinstance(body, dict) or body.get("status") != "success":
        raise Blocked("INVALID_PROVIDER_DATA")
    data = body.get("data")
    if not isinstance(data, list) or len(data) > 128:
        raise Blocked("INVALID_PROVIDER_DATA")
    found = []
    for row in data:
        if not isinstance(row, dict):
            raise Blocked("INVALID_PROVIDER_DATA")
        if (row.get("segment") != "NSE_FO" or row.get("exchange") != "NSE"
                or row.get("instrument_type") != "FUT"
                or row.get("underlying_key") != UNDERLYING
                or row.get("expiry") != expiry.isoformat()):
            continue
        key = row.get("instrument_key")
        lot = row.get("lot_size")
        if (not isinstance(key, str)
                or not re.fullmatch(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}", key)
                or type(lot) is not int or not 1 <= lot <= 100000):
            raise Blocked("INVALID_PROVIDER_DATA")
        found.append(key)
    return (min(found), len(found)) if found else (None, 0)


def count_candles(body, first, last):
    if not isinstance(body, dict) or body.get("status") != "success":
        raise Blocked("INVALID_PROVIDER_DATA")
    payload = body.get("data")
    if not isinstance(payload, dict) or not isinstance(payload.get("candles"), list):
        raise Blocked("INVALID_PROVIDER_DATA")
    rows = payload["candles"]
    if len(rows) > 64:
        raise Blocked("INVALID_PROVIDER_DATA")
    seen = set()
    for bar in rows:
        if not isinstance(bar, list) or len(bar) != 7:
            raise Blocked("INVALID_PROVIDER_DATA")
        stamp, op, hi, lo, close, volume, oi = bar
        try:
            timestamp = datetime.fromisoformat(stamp)
        except (ValueError, TypeError):
            raise Blocked("INVALID_PROVIDER_DATA") from None
        if (timestamp.tzinfo is None or not first <= timestamp.date() <= last
                or timestamp in seen):
            raise Blocked("INVALID_PROVIDER_DATA")
        seen.add(timestamp)
        if (any(type(x) not in (int, float) or not math.isfinite(x) or x <= 0
                for x in (op, hi, lo, close))
                or lo > min(op, close) or hi < max(op, close)
                or type(volume) is not int or volume < 0
                or type(oi) is not int or oi < 0):
            raise Blocked("INVALID_PROVIDER_DATA")
    return len(rows)


def probe(token, today=None):
    today = today or datetime.now(ZoneInfo("Asia/Kolkata")).date()
    underlying = urllib.parse.quote(UNDERLYING, safe="")
    listed = expiries(get_json(BASE + "/expiries?instrument_key=" + underlying, token, "EXPIRIES"), today)
    if not listed:
        return "NO_EXPIRED_EXPIRIES", 0, 0, 0
    # Futures typically have monthly expiries; avoid selecting a recent weekly-only expiry.
    # Choose the latest provider-listed expiry in a fully completed month if available.
    completed_month_start = today.replace(day=1)
    last = next((d for d in listed if d < completed_month_start), listed[0])
    url = BASE + "/future/contract?instrument_key=" + underlying + "&expiry_date=" + last.isoformat()
    key, contracts = future_key(get_json(url, token, "CONTRACTS"), last)
    if not key:
        return "NO_MATCHING_FUTURES", len(listed), 0, 0
    # STR-176 requires seven COMPLETED trading sessions; seven calendar
    # days can yield only five bars. Read 21 calendar days with the same
    # single bounded Upstox GET, without storing candles or orders.
    first = last - timedelta(days=21)
    url = (BASE + "/historical-candle/" + urllib.parse.quote(key, safe="")
           + "/day/" + last.isoformat() + "/" + first.isoformat())
    candles = count_candles(get_json(url, token, "CANDLES"), first, last)
    return ("HISTORY_SOURCE_VERIFIED" if candles else "NO_VALID_CANDLES",
            len(listed), contracts, candles)


def host_token():
    command = ["aws", "ssm", "get-parameter", "--name", SSM_TOKEN,
               "--with-decryption", "--region", REGION, "--output", "json", "--no-cli-pager"]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=15, check=False)
        if result.returncode:
            raise Blocked("TOKEN_UNAVAILABLE")
        token = json.loads(result.stdout)["Parameter"]["Value"]
    except (OSError, ValueError, TypeError, KeyError, subprocess.TimeoutExpired):
        raise Blocked("TOKEN_UNAVAILABLE") from None
    if not isinstance(token, str) or not 32 <= len(token) <= 8192 or re.search(r"\s", token):
        raise Blocked("TOKEN_UNAVAILABLE")
    return token


def receipt(status, exp=0, futures=0, candles=0):
    if status not in STATES or any(type(n) is not int or not 0 <= n <= 128
                                  for n in (exp, futures, candles)):
        raise Blocked("INVALID_PROVIDER_DATA")
    return (f"{MARKER} status={status} expiries={exp} futures={futures} "
            f"candles={candles} stored=false rights=unverified orders=false")


def parse_receipt(data):
    if not isinstance(data, str) or len(data) > 12000:
        return None
    found = re.findall(
        r"^MINTTICK_FNO_EXPIRED_HISTORY: status=([A-Z_]+) expiries=(\d{1,3}) "
        r"futures=(\d{1,3}) candles=(\d{1,3}) stored=false rights=unverified orders=false$",
        data, re.MULTILINE)
    if len(found) != 1 or found[0][0] not in STATES:
        return None
    status, *values = found[0]
    counts = tuple(int(v) for v in values)
    if any(v > 128 for v in counts):
        return None
    if status == "HISTORY_SOURCE_VERIFIED" and not all(v > 0 for v in counts):
        return None
    return status, *counts


def host():
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        print(receipt("HOST_IDENTITY_INVALID"), flush=True)
        raise SystemExit(2)
    root = Path("/opt/aitradingsystem-paper")
    files = (root / ".env", root / "runtime.env")
    if any(p.is_symlink() or not p.is_file() or p.stat().st_mode & 0o077 for p in files):
        print(receipt("HOST_ENV_INVALID"), flush=True)
        raise SystemExit(2)
    try:
        result = probe(host_token())
        print(receipt(*result), flush=True)
        if result[0] != "HISTORY_SOURCE_VERIFIED":
            raise SystemExit(2)
    except Blocked as error:
        print(receipt(error.code), flush=True)
        raise SystemExit(2) from None


def runner():
    request = Path(REQUEST)
    if (request.is_symlink() or not request.is_file() or request.stat().st_size > 1024
            or json.loads(request.read_text()) != expected_request()):
        raise SystemExit("FNO_HISTORY_REQUEST_NOT_APPROVED")
    sys.path.insert(0, "infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    payload = base64.b64encode(gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(payload) > 12000 or not re.fullmatch(r"[A-Za-z0-9+/=]+", payload):
        raise SystemExit("FNO_HISTORY_PROBE_OVERSIZED")
    shell = "set -eu\nprintf '%s' '" + payload + "' | base64 -d | gzip -dc | python3 - host\n"

    def aws(*args):
        response = subprocess.run(
            ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"],
            capture_output=True, text=True, timeout=45, check=False)
        if response.returncode:
            raise SystemExit("FNO_HISTORY_SSM_UNAVAILABLE")
        return json.loads(response.stdout)

    send = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
               "--instance-ids", INSTANCE, "--timeout-seconds", "160",
               "--comment", "MintTick read-only Upstox expired FNO entitlement and source validation",
               "--parameters", json.dumps({"commands": [shell]}))
    command_id = (send.get("Command") or {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", command_id):
        raise SystemExit("FNO_HISTORY_SSM_ID_INVALID")
    for _ in range(35):
        time.sleep(4)
        state = aws("ssm", "get-command-invocation",
                    "--command-id", command_id, "--instance-id", INSTANCE)
        status = state.get("Status")
        if status in ("Success", "Failed", "TimedOut", "Cancelled"):
            parsed = parse_receipt(state.get("StandardOutputContent") or "")
            if parsed is None:
                raise SystemExit("FNO_HISTORY_UNVERIFIED_NO_SAFE_RECEIPT")
            result, expiries_count, futures_count, bars = parsed
            print(f"FNO EXPIRED SOURCE: {result}, expiries={expiries_count}, "
                  f"futures={futures_count}, dailyBars={bars}; "
                  "rights=UNKNOWN; persisted=FALSE; orders=FALSE", flush=True)
            if status != "Success" or result != "HISTORY_SOURCE_VERIFIED":
                raise SystemExit("FNO_HISTORY_SOURCE_BLOCKED")
            return
        if status not in ("Pending", "InProgress", "Delayed"):
            raise SystemExit("FNO_HISTORY_SSM_STATE_INVALID")
    raise SystemExit("FNO_HISTORY_SSM_TIMEOUT")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("host", "runner"):
        raise SystemExit("FNO_HISTORY_INVALID_COMMAND")
    host() if sys.argv[1] == "host" else runner()
