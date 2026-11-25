#!/usr/bin/env python3
"""Owner-only, one-GET Upstox Analytics Token LTP authorization diagnostic.

Runs on the existing exact PAPER EC2 via protected AWS SSM. Tests one approved
NSE_EQ instrument using the current SSM Analytics token. Never prints bearer,
instrument, price, quote, account data or provider body; stores no market data.
Does not change broker orders, rights, deployment, token or EC2 lifecycle.
"""
from __future__ import annotations

import base64
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
import urllib.request

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
PARAMETER = "/minttick/upstox/analytics-token"
REQUEST = "infra/ec2-paper/requests/upstox-quote-access-check.json"
REPO = "operationReact/AiTradingSystem"
ISSUE = 1504
URL = "https://api.upstox.com/v3/market-quote/ltp?instrument_key=NSE_EQ%7CINE848E01016"
INSTRUMENT = "NSE_EQ|INE848E01016"
# Explicit truthful client identifier: Upstox staff report default Python-urllib
# signatures can be rejected by Cloudflare with error 1010.
UPSTOX_USER_AGENT = "MintTick-Upstox-ReadOnly-Diagnostics/1.0"
MARKER = "MINTTICK_UPSTOX_QUOTE_ACCESS:"
STATES = frozenset({
    "QUOTE_VERIFIED", "QUOTE_EMPTY", "QUOTE_HTTP_400", "QUOTE_HTTP_401",
    "QUOTE_HTTP_403", "QUOTE_HTTP_404", "QUOTE_HTTP_422", "QUOTE_HTTP_429",
    "QUOTE_HTTP_5XX", "QUOTE_HTTP_OTHER", "QUOTE_PLUS_REQUIRED",
    "QUOTE_PROVIDER_INVALID", "QUOTE_TRANSPORT_ERROR",
    "QUOTE_CLOUDFLARE_1010", "QUOTE_TOKEN_INVALID",
    "TOKEN_UNAVAILABLE", "HOST_INVALID",
})


def expected_request():
    return {
        "kind": "minttick-owner-upstox-quote-source-check-v1",
        "operation": "one-get-upstox-ltp-read-only",
        "environment": "PAPER",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "authorization": "OWNER_APPROVED_UPSTOX_TOKEN_QUOTE_DIAG_2026_11_21",
        "max_provider_gets": 1,
        "persist": False,
        "trading_rights_change": False,
    }


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def classify_http(code, raw):
    """Allowlisted failure classification only; never log response payload."""
    snippet = raw[:2048]
    if code == 403 and (b"error 1010" in snippet.lower()
                        or b"error code: 1010" in snippet.lower()
                        or b"browser_signature_banned" in snippet.lower()):
        return "QUOTE_CLOUDFLARE_1010"
    if b"UDAPI100050" in snippet:
        return "QUOTE_TOKEN_INVALID"
    if code == 403 and b"UDAPI1149" in snippet:
        return "QUOTE_PLUS_REQUIRED"
    if code in (400, 401, 403, 404, 422, 429):
        return "QUOTE_HTTP_" + str(code)
    if 500 <= code <= 599:
        return "QUOTE_HTTP_5XX"
    return "QUOTE_HTTP_OTHER"


def classify_quote(body):
    if not isinstance(body, dict) or body.get("status") != "success":
        return "QUOTE_PROVIDER_INVALID"
    quotes = body.get("data")
    if not isinstance(quotes, dict) or len(quotes) > 4:
        return "QUOTE_PROVIDER_INVALID"
    if not quotes:
        return "QUOTE_EMPTY"
    # Value/identity are checked in memory only; quote fields never leave EC2.
    matched = [
        v for v in quotes.values()
        if isinstance(v, dict) and v.get("instrument_token") == INSTRUMENT
    ]
    if len(matched) != 1:
        return "QUOTE_PROVIDER_INVALID"
    price = matched[0].get("last_price")
    if type(price) not in (int, float) or not math.isfinite(price) or price <= 0:
        return "QUOTE_PROVIDER_INVALID"
    return "QUOTE_VERIFIED"


def quote(token):
    if not isinstance(token, str) or not 32 <= len(token) <= 8192 or re.search(r"\s", token):
        return "TOKEN_UNAVAILABLE"
    opener = urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(URL, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/json",
        "User-Agent": UPSTOX_USER_AGENT,
    }, method="GET")
    try:
        with opener.open(req, timeout=12) as response:
            if response.status != 200:
                return classify_http(response.status, b"")
            data = response.read(32769)
    except urllib.error.HTTPError as error:
        # Read only the first bounded bytes for known failure codes. No raw
        # HTML/JSON body, bearer token, quote, or Cloudflare Ray ID is logged.
        return classify_http(error.code, error.read(2049))
    except (urllib.error.URLError, TimeoutError, OSError):
        return "QUOTE_TRANSPORT_ERROR"
    if len(data) > 32768:
        return "QUOTE_PROVIDER_INVALID"
    try:
        return classify_quote(json.loads(data))
    except (UnicodeError, ValueError, TypeError):
        return "QUOTE_PROVIDER_INVALID"


def safe_receipt(state):
    if state not in STATES:
        raise ValueError("QUOTE_UNKNOWN_STATUS")
    return f"{MARKER} status={state} stored=false rights=unknown orders=false"


def parse_receipt(source):
    if not isinstance(source, str) or len(source) > 12000:
        return None
    matches = re.findall(
        r"^MINTTICK_UPSTOX_QUOTE_ACCESS: status=([A-Z0-9_]+) "
        r"stored=false rights=unknown orders=false$",
        source, re.MULTILINE)
    return matches[0] if len(matches) == 1 and matches[0] in STATES else None


def host_token():
    cmd = ["aws", "ssm", "get-parameter", "--name", PARAMETER,
           "--with-decryption", "--region", REGION,
           "--output", "json", "--no-cli-pager"]
    try:
        process = subprocess.run(cmd, check=False, capture_output=True, text=True, timeout=15)
        if process.returncode:
            return None
        value = json.loads(process.stdout)["Parameter"]["Value"]
    except (OSError, ValueError, TypeError, KeyError, subprocess.TimeoutExpired):
        return None
    if not isinstance(value, str) or not 32 <= len(value) <= 8192 or re.search(r"\s", value):
        return None
    return value


def host():
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        print(safe_receipt("HOST_INVALID"), flush=True)
        return
    root = Path("/opt/aitradingsystem-paper")
    files = (root / "runtime.env", root / ".env")
    if any(p.is_symlink() or not p.is_file() or p.stat().st_mode & 0o077 for p in files):
        print(safe_receipt("HOST_INVALID"), flush=True)
        return
    token = host_token()
    state = quote(token) if token else "TOKEN_UNAVAILABLE"
    print(safe_receipt(state), flush=True)


def runner():
    path = Path(REQUEST)
    if (path.is_symlink() or not path.is_file() or path.stat().st_size > 1024
            or json.loads(path.read_text()) != expected_request()):
        raise SystemExit("QUOTE_UNAPPROVED_REQUEST")
    sys.path.insert(0, "infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    payload = base64.b64encode(gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(payload) > 14000 or not re.fullmatch("[A-Za-z0-9+/=]+", payload):
        raise SystemExit("QUOTE_PAYLOAD_UNEXPECTED")
    shell = "set -eu\nprintf '%s' '" + payload + "' | base64 -d | gzip -dc | python3 - host\n"

    def aws(*args):
        result = subprocess.run(
            ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"],
            capture_output=True, text=True, timeout=50, check=False)
        if result.returncode:
            raise SystemExit("QUOTE_SSM_UNAVAILABLE")
        return json.loads(result.stdout)

    sent = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
               "--instance-ids", INSTANCE, "--timeout-seconds", "150",
               "--comment", "MintTick one-GET Upstox quote token authorization check",
               "--parameters", json.dumps({"commands": [shell]}))
    command_id = (sent.get("Command") or {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch("[0-9a-f-]{36}", command_id):
        raise SystemExit("QUOTE_BAD_SSM_ID")
    for _ in range(40):
        time.sleep(4)
        result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                     "--instance-id", INSTANCE)
        state = result.get("Status")
        if state in ("Success", "Failed", "TimedOut", "Cancelled"):
            receipt = parse_receipt(result.get("StandardOutputContent") or "")
            if state != "Success" or receipt is None:
                raise SystemExit("QUOTE_NO_SAFE_SSM_RECEIPT")
            print(safe_receipt(receipt), flush=True)
            return
        if state not in ("Pending", "InProgress", "Delayed"):
            raise SystemExit("QUOTE_SSM_UNEXPECTED_STATE")
    raise SystemExit("QUOTE_SSM_TIMEOUT")


def publish_issue(path):
    run_id = os.environ.get("GH_RUN_ID", "")
    token = os.environ.get("GH_TOKEN", "")
    if (os.environ.get("GH_REPOSITORY") != REPO
            or not re.fullmatch(r"\d{1,20}", run_id)
            or not token or any(c.isspace() for c in token)):
        raise SystemExit("QUOTE_BAD_GITHUB_CONTEXT")
    raw = ""
    if path.is_file() and not path.is_symlink() and path.stat().st_size < 12000:
        raw = path.read_text(encoding="utf-8", errors="replace")
    status = parse_receipt(raw)
    outcome = f"**{status}**" if status is not None else "**NO_SANITIZED_RECEIPT**"
    text = ("### Upstox Analytics Token LTP V3 read-only authorization\n\n"
            "Single fixed NSE_EQ market quote endpoint: " + outcome + ".\n\n"
            "This test does not inspect token claims or modify history/downloaders. "
            "It publishes no token, quote values, owner account information or provider "
            "response body. No trading or persistence rights changed.\n\n"
            f"Protected workflow: https://github.com/{REPO}/actions/runs/{run_id}")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/issues/{ISSUE}/comments",
        method="POST", data=json.dumps({"body": text}).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "MintTick-Upstox-Quote-ReadOnly/1",
        })
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            if response.status != 201:
                raise SystemExit("QUOTE_ISSUE_NOT_ACCEPTED")
    except (urllib.error.URLError, OSError):
        raise SystemExit("QUOTE_ISSUE_UNAVAILABLE") from None
    print("QUOTE_ISSUE_SAFE_RECEIPT_POSTED", flush=True)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("QUOTE_INVALID_MODE")
    if len(sys.argv) == 2 and sys.argv[1] == "host":
        host()
    elif len(sys.argv) == 2 and sys.argv[1] == "runner":
        runner()
    elif len(sys.argv) == 3 and sys.argv[1] == "issue":
        publish_issue(Path(sys.argv[2]))
    else:
        raise SystemExit("QUOTE_INVALID_MODE")
