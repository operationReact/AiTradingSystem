#!/usr/bin/env python3
"""One-shot owner-only Upstox Analytics Token comparison: 2 GETs, no storage.

Tests an ordinary NSE_EQ historical V3 window and NIFTY expired-F&O expiries
with the SAME SSM token from the existing PAPER host. Only enumerated statuses
and bounded counts are emitted. No credential, OHLCV, contract or provider body
is logged. No LIVE/PAPER orders, feature flags, or dataset persistence.
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

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
PARAMETER = "/minttick/upstox/analytics-token"
REQUEST = "infra/ec2-paper/requests/upstox-history-access-compare.json"
ISSUE = 1504
REPO = "operationReact/AiTradingSystem"
PREFIX = "MINTTICK_UPSTOX_HISTORY_COMPARE:"
CODES = frozenset({
    "OK_DATA", "OK_EMPTY", "HTTP_400", "HTTP_401", "HTTP_403",
    "HTTP_404", "HTTP_422", "HTTP_429", "HTTP_5XX", "HTTP_OTHER",
    "PLUS_REQUIRED", "PROVIDER_INVALID", "TRANSPORT_ERROR",
    "CLOUDFLARE_1010", "TOKEN_INVALID",
    "TOKEN_UNAVAILABLE", "HOST_INVALID", "NOT_RUN",
})
HTTP_CODES = {400, 401, 403, 404, 422, 429}
EQUITY_KEY = "NSE_EQ|INE848E01016"  # Official Upstox V3 docs NSE_EQ key
EXPIRED_KEY = "NSE_INDEX|Nifty 50"
UPSTOX_USER_AGENT = "MintTick-Upstox-ReadOnly-Diagnostics/1.0"


def expected_request():
    return {
        "kind": "minttick-owner-upstox-history-access-comparison-v1",
        "operation": "compare-standard-v3-and-expired-expiries-read-only",
        "environment": "PAPER",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "authorization": "OWNER_APPROVED_DIAGNOSE_FNO_SOURCE_2026_11_21",
        "max_provider_gets": 2,
        "persist": False,
        "trading_rights_change": False,
    }


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def status_http(code, data):
    """Categorize known provider/WAF errors without printing the response."""
    fragment = data[:2048].lower()
    if code == 403 and (b"error 1010" in fragment
                        or b"error code: 1010" in fragment
                        or b"browser_signature_banned" in fragment):
        return "CLOUDFLARE_1010"
    if b"udapi100050" in fragment:
        return "TOKEN_INVALID"
    if code == 403 and b"udapi1149" in fragment:
        return "PLUS_REQUIRED"
    if code in HTTP_CODES:
        return f"HTTP_{code}"
    if 500 <= code <= 599:
        return "HTTP_5XX"
    return "HTTP_OTHER"


def get_endpoint(url, token):
    """Only two predefined Upstox endpoints, no arbitrary URL or redirects."""
    parts = urllib.parse.urlsplit(url)
    allowed = (
        parts.scheme == "https" and parts.netloc == "api.upstox.com"
        and not parts.fragment and len(url) < 320
        and (parts.path.startswith("/v3/historical-candle/")
             or parts.path == "/v2/expired-instruments/expiries")
    )
    if not allowed:
        return "PROVIDER_INVALID", None
    request = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token, "Accept": "application/json",
        "User-Agent": UPSTOX_USER_AGENT,
    }, method="GET")
    try:
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=12) as result:
            if result.status != 200:
                return status_http(result.status, b""), None
            payload = result.read(32769)
    except urllib.error.HTTPError as error:
        return status_http(error.code, error.read(2049)), None
    except (urllib.error.URLError, TimeoutError, OSError):
        return "TRANSPORT_ERROR", None
    if len(payload) > 32768:
        return "PROVIDER_INVALID", None
    try:
        return "OK_DATA", json.loads(payload)
    except (UnicodeError, ValueError):
        return "PROVIDER_INVALID", None


def count_equity(body, first, last):
    if not isinstance(body, dict) or body.get("status") != "success":
        return "PROVIDER_INVALID", 0
    dataset = body.get("data")
    if not isinstance(dataset, dict) or not isinstance(dataset.get("candles"), list):
        return "PROVIDER_INVALID", 0
    rows = dataset["candles"]
    if len(rows) > 64:
        return "PROVIDER_INVALID", 0
    seen = set()
    for bar in rows:
        if not isinstance(bar, list) or len(bar) != 7:
            return "PROVIDER_INVALID", 0
        stamp, op, high, low, close, volume, oi = bar
        try:
            when = datetime.fromisoformat(stamp)
        except (TypeError, ValueError):
            return "PROVIDER_INVALID", 0
        if (when.tzinfo is None or not first <= when.date() <= last
                or when in seen):
            return "PROVIDER_INVALID", 0
        seen.add(when)
        if (any(type(n) not in (int, float) or not math.isfinite(n) or n <= 0
                for n in (op, high, low, close))
                or low > min(op, close) or high < max(op, close)
                or type(volume) is not int or volume < 0
                or type(oi) is not int or oi < 0):
            return "PROVIDER_INVALID", 0
    return ("OK_DATA" if rows else "OK_EMPTY"), len(rows)


def count_expiries(body, today):
    if not isinstance(body, dict) or body.get("status") != "success":
        return "PROVIDER_INVALID", 0
    values = body.get("data")
    if not isinstance(values, list) or len(values) > 128:
        return "PROVIDER_INVALID", 0
    seen = set()
    for item in values:
        if not isinstance(item, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", item):
            return "PROVIDER_INVALID", 0
        try:
            expiry = date.fromisoformat(item)
        except ValueError:
            return "PROVIDER_INVALID", 0
        if expiry < today:
            seen.add(expiry)
    return ("OK_DATA" if seen else "OK_EMPTY"), len(seen)


def compare(token, today=None):
    today = today or datetime.now(ZoneInfo("Asia/Kolkata")).date()
    last = today - timedelta(days=1)
    first = last - timedelta(days=7)
    normal_url = ("https://api.upstox.com/v3/historical-candle/"
                  + urllib.parse.quote(EQUITY_KEY, safe="")
                  + "/days/1/" + last.isoformat() + "/" + first.isoformat())
    expired_url = ("https://api.upstox.com/v2/expired-instruments/expiries"
                   "?instrument_key=" + urllib.parse.quote(EXPIRED_KEY, safe=""))
    normal_status, normal_body = get_endpoint(normal_url, token)
    normal_count = 0
    if normal_status == "OK_DATA":
        normal_status, normal_count = count_equity(normal_body, first, last)
    expired_status, expired_body = get_endpoint(expired_url, token)
    expired_count = 0
    if expired_status == "OK_DATA":
        expired_status, expired_count = count_expiries(expired_body, today)
    return normal_status, normal_count, expired_status, expired_count


def safe_line(ordinary, bars, expired, expiries):
    if ordinary not in CODES or expired not in CODES:
        raise ValueError("INVALID_COMPARE_STATUS")
    if any(type(n) is not int or n < 0 or n > 128 for n in (bars, expiries)):
        raise ValueError("INVALID_COMPARE_COUNT")
    if (ordinary != "OK_DATA" and bars != 0) or (expired != "OK_DATA" and expiries != 0):
        raise ValueError("INVALID_COMPARE_COUNT")
    return (f"{PREFIX} standard={ordinary} bars={bars} expired={expired} "
            f"expiries={expiries} stored=false orders=false")


def parse_line(text):
    if not isinstance(text, str) or len(text) > 12000:
        return None
    matches = re.findall(
        r"^MINTTICK_UPSTOX_HISTORY_COMPARE: standard=([A-Z0-9_]+) bars=(\d{1,3}) "
        r"expired=([A-Z0-9_]+) expiries=(\d{1,3}) stored=false orders=false$",
        text, re.MULTILINE)
    if len(matches) != 1:
        return None
    a, bars, b, expiries = matches[0]
    try:
        safe_line(a, int(bars), b, int(expiries))
        return a, int(bars), b, int(expiries)
    except ValueError:
        return None


def host_token():
    cmd = ["aws", "ssm", "get-parameter", "--name", PARAMETER,
           "--with-decryption", "--region", REGION,
           "--output", "json", "--no-cli-pager"]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True,
                                timeout=15, check=False)
        if result.returncode:
            return None
        value = json.loads(result.stdout)["Parameter"]["Value"]
        if not isinstance(value, str) or not 32 <= len(value) <= 8192 or re.search(r"\s", value):
            return None
        return value
    except (OSError, ValueError, TypeError, KeyError, subprocess.TimeoutExpired):
        return None


def host():
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        print(safe_line("HOST_INVALID", 0, "HOST_INVALID", 0), flush=True)
        return
    root = Path("/opt/aitradingsystem-paper")
    paths = (root / "runtime.env", root / ".env")
    if any(p.is_symlink() or not p.is_file() or p.stat().st_mode & 0o077 for p in paths):
        print(safe_line("HOST_INVALID", 0, "HOST_INVALID", 0), flush=True)
        return
    token = host_token()
    if token is None:
        print(safe_line("TOKEN_UNAVAILABLE", 0, "NOT_RUN", 0), flush=True)
        return
    result = compare(token)
    print(safe_line(*result), flush=True)


def runner():
    file = Path(REQUEST)
    if (file.is_symlink() or not file.is_file() or file.stat().st_size >= 1024
            or json.loads(file.read_text()) != expected_request()):
        raise SystemExit("HISTORY_COMPARE_REQUEST_UNAPPROVED")
    sys.path.insert(0, "infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    payload = base64.b64encode(gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(payload) > 14000 or not re.fullmatch("[A-Za-z0-9+/=]+", payload):
        raise SystemExit("HISTORY_COMPARE_PAYLOAD_INVALID")
    script = "set -eu\nprintf '%s' '" + payload + "' | base64 -d | gzip -dc | python3 - host\n"

    def aws(*argv):
        result = subprocess.run(
            ["aws", *argv, "--region", REGION, "--output", "json", "--no-cli-pager"],
            capture_output=True, text=True, timeout=50, check=False)
        if result.returncode:
            raise SystemExit("HISTORY_COMPARE_SSM_UNAVAILABLE")
        return json.loads(result.stdout)

    sent = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
               "--instance-ids", INSTANCE, "--timeout-seconds", "150",
               "--comment", "MintTick owner-only no-storage Upstox source comparison",
               "--parameters", json.dumps({"commands": [script]}))
    command_id = (sent.get("Command") or {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch("[0-9a-f-]{36}", command_id):
        raise SystemExit("HISTORY_COMPARE_COMMAND_INVALID")
    for _ in range(40):
        time.sleep(4)
        result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                     "--instance-id", INSTANCE)
        state = result.get("Status")
        if state in ("Success", "Failed", "TimedOut", "Cancelled"):
            receipt = parse_line(result.get("StandardOutputContent") or "")
            if state != "Success" or receipt is None:
                raise SystemExit("HISTORY_COMPARE_NO_VERIFIED_RECEIPT")
            print(safe_line(*receipt), flush=True)
            return
        if state not in ("Pending", "InProgress", "Delayed"):
            raise SystemExit("HISTORY_COMPARE_UNEXPECTED_SSM_STATE")
    raise SystemExit("HISTORY_COMPARE_TIMEOUT")


def issue(log: Path):
    if os.environ.get("GH_REPOSITORY") != REPO:
        raise SystemExit("HISTORY_COMPARE_WRONG_REPO")
    run_id = os.environ.get("GH_RUN_ID", "")
    token = os.environ.get("GH_TOKEN", "")
    if not re.fullmatch(r"\d{1,20}", run_id) or not token or any(c.isspace() for c in token):
        raise SystemExit("HISTORY_COMPARE_BAD_ISSUE_CONTEXT")
    text = ""
    if log.is_file() and not log.is_symlink() and log.stat().st_size <= 12000:
        text = log.read_text(encoding="utf-8", errors="replace")
    receipt = parse_line(text)
    if receipt:
        ordinary, bars, expired, expiries = receipt
        result = (f"Standard historical V3: **{ordinary}** ({bars} daily candles); "
                  f"expired F&O expiries: **{expired}** ({expiries} dates).")
    else:
        result = "**NO_SANITIZED_RECEIPT** — source access not verified."
    message = (
        "### Upstox token vs expired-F&O access (read-only, 2 GETs)\n\n"
        + result + "\n\n"
        "No raw prices, bearer token, instrument identifiers, SSM secrets, "
        "LIVE/PAPER orders, stored history or data-use rights were changed. "
        "A normal V3 success does not authorize expired-F&O ingestion.\n\n"
        f"Protected execution: https://github.com/{REPO}/actions/runs/{run_id}"
    )
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/issues/{ISSUE}/comments",
        method="POST", data=json.dumps({"body": message}).encode(),
        headers={"Authorization": "Bearer " + token,
                 "Accept": "application/vnd.github+json",
                 "Content-Type": "application/json",
                 "User-Agent": "MintTick-ReadOnlyHistoryCompare/1",
                 "X-GitHub-Api-Version": "2022-11-28"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.status != 201:
                raise SystemExit("HISTORY_COMPARE_ISSUE_FAILED")
    except (OSError, urllib.error.URLError, urllib.error.HTTPError):
        raise SystemExit("HISTORY_COMPARE_ISSUE_FAILED") from None
    print("HISTORY_COMPARE_ISSUE_RECEIPT_POSTED", flush=True)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("HISTORY_COMPARE_INVALID_MODE")
    if sys.argv[1] == "host" and len(sys.argv) == 2:
        host()
    elif sys.argv[1] == "runner" and len(sys.argv) == 2:
        runner()
    elif sys.argv[1] == "issue" and len(sys.argv) == 3:
        issue(Path(sys.argv[2]))
    else:
        raise SystemExit("HISTORY_COMPARE_INVALID_MODE")
