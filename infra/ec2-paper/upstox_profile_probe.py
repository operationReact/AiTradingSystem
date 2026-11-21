#!/usr/bin/env python3
"""Owner-only, one-GET Upstox account-profile segment check (read-only).

Runs on the exact PAPER EC2 through protected AWS SSM, using the stored Analytics token.
Reports only whether the account lists the NFO and NSE exchange segments, and any sanitized
Upstox error code. Never prints bearer tokens, names, emails, user ids, products, provider
bodies or quote values. Stores nothing, places no orders, changes no rights or trading mode.
"""
from __future__ import annotations

import base64
import gzip
import json
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
REQUEST = "infra/ec2-paper/requests/upstox-profile-check.json"
REPO = "operationReact/AiTradingSystem"
ISSUE = 1504
URL = "https://api.upstox.com/v2/user/profile"
MARKER = "MINTTICK_UPSTOX_PROFILE_CHECK:"
STATES = frozenset({
    "PROFILE_NFO_PRESENT", "PROFILE_NFO_ABSENT", "PROFILE_PROVIDER_INVALID",
    "PROFILE_HTTP_400", "PROFILE_HTTP_401", "PROFILE_HTTP_403", "PROFILE_HTTP_404",
    "PROFILE_HTTP_422", "PROFILE_HTTP_429", "PROFILE_HTTP_5XX", "PROFILE_HTTP_OTHER",
    "PROFILE_PLUS_REQUIRED", "PROFILE_TRANSPORT_ERROR",
    "TOKEN_UNAVAILABLE", "HOST_INVALID",
})
ERROR_CODE = re.compile(rb'"errorCode"\s*:\s*"(UDAPI[0-9]{4,5})"')
RECEIPT = re.compile(
    r"^MINTTICK_UPSTOX_PROFILE_CHECK: status=([A-Z0-9_]+) "
    r"providerCode=(UDAPI[0-9]{4,5}|NONE) stored=false rights=unknown orders=false$",
    re.MULTILINE)


def expected_request():
    return {
        "kind": "minttick-owner-upstox-profile-check-v1",
        "operation": "one-get-upstox-user-profile-read-only",
        "environment": "PAPER",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "authorization": "OWNER_APPROVED_UPSTOX_PROFILE_CHECK_2026_11_21",
        "max_provider_gets": 1,
        "persist": False,
        "trading_rights_change": False,
    }


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def provider_code(raw):
    match = ERROR_CODE.search(raw[:2048])
    return match.group(1).decode("ascii") if match else "NONE"


def classify_http(code, raw):
    if code == 403 and b"UDAPI1149" in raw[:2048]:
        return "PROFILE_PLUS_REQUIRED"
    if code in (400, 401, 403, 404, 422, 429):
        return "PROFILE_HTTP_" + str(code)
    if 500 <= code <= 599:
        return "PROFILE_HTTP_5XX"
    return "PROFILE_HTTP_OTHER"


def classify_profile(body):
    if not isinstance(body, dict) or body.get("status") != "success":
        return "PROFILE_PROVIDER_INVALID"
    data = body.get("data")
    if not isinstance(data, dict):
        return "PROFILE_PROVIDER_INVALID"
    exchanges = data.get("exchanges")
    if not isinstance(exchanges, list) or not all(isinstance(e, str) for e in exchanges):
        return "PROFILE_PROVIDER_INVALID"
    # Only membership is evaluated. The list, names and identifiers are never printed.
    return "PROFILE_NFO_PRESENT" if "NFO" in exchanges else "PROFILE_NFO_ABSENT"


def profile(token):
    """Returns (state, providerCode)."""
    if not isinstance(token, str) or not 32 <= len(token) <= 8192 or re.search(r"\s", token):
        return "TOKEN_UNAVAILABLE", "NONE"
    opener = urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(URL, headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/json",
    }, method="GET")
    try:
        with opener.open(req, timeout=12) as response:
            if response.status != 200:
                return classify_http(response.status, b""), "NONE"
            data = response.read(32769)
    except urllib.error.HTTPError as error:
        raw = error.read(2049)
        return classify_http(error.code, raw), provider_code(raw)
    except (urllib.error.URLError, TimeoutError, OSError):
        return "PROFILE_TRANSPORT_ERROR", "NONE"
    if len(data) > 32768:
        return "PROFILE_PROVIDER_INVALID", "NONE"
    try:
        return classify_profile(json.loads(data)), "NONE"
    except (UnicodeError, ValueError, TypeError):
        return "PROFILE_PROVIDER_INVALID", "NONE"


def safe_receipt(state, code="NONE"):
    if state not in STATES:
        raise ValueError("PROFILE_UNKNOWN_STATUS")
    if code != "NONE" and not re.fullmatch(r"UDAPI[0-9]{4,5}", code):
        raise ValueError("PROFILE_UNKNOWN_PROVIDER_CODE")
    return f"{MARKER} status={state} providerCode={code} stored=false rights=unknown orders=false"


def parse_receipt(source):
    if not isinstance(source, str) or len(source) > 12000:
        return None
    matches = RECEIPT.findall(source)
    if len(matches) != 1 or matches[0][0] not in STATES:
        return None
    return matches[0]


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
    if not token:
        print(safe_receipt("TOKEN_UNAVAILABLE"), flush=True)
        return
    state, code = profile(token)
    print(safe_receipt(state, code), flush=True)


def runner():
    path = Path(REQUEST)
    if (path.is_symlink() or not path.is_file() or path.stat().st_size > 1024
            or json.loads(path.read_text()) != expected_request()):
        raise SystemExit("PROFILE_UNAPPROVED_REQUEST")
    sys.path.insert(0, "infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    payload = base64.b64encode(gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(payload) > 14000 or not re.fullmatch("[A-Za-z0-9+/=]+", payload):
        raise SystemExit("PROFILE_PAYLOAD_UNEXPECTED")
    shell = "set -eu\nprintf '%s' '" + payload + "' | base64 -d | gzip -dc | python3 - host\n"

    def aws(*args):
        result = subprocess.run(
            ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"],
            capture_output=True, text=True, timeout=50, check=False)
        if result.returncode:
            raise SystemExit("PROFILE_SSM_UNAVAILABLE")
        return json.loads(result.stdout)

    sent = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
               "--instance-ids", INSTANCE, "--timeout-seconds", "150",
               "--comment", "MintTick one-GET Upstox account-profile segment check",
               "--parameters", json.dumps({"commands": [shell]}))
    command_id = (sent.get("Command") or {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch("[0-9a-f-]{36}", command_id):
        raise SystemExit("PROFILE_BAD_SSM_ID")
    for _ in range(40):
        time.sleep(4)
        result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                     "--instance-id", INSTANCE)
        state = result.get("Status")
        if state in ("Success", "Failed", "TimedOut", "Cancelled"):
            parsed = parse_receipt(result.get("StandardOutputContent") or "")
            if state != "Success" or parsed is None:
                raise SystemExit("PROFILE_NO_SAFE_SSM_RECEIPT")
            print(safe_receipt(parsed[0], parsed[1]), flush=True)
            return
        if state not in ("Pending", "InProgress", "Delayed"):
            raise SystemExit("PROFILE_SSM_UNEXPECTED_STATE")
    raise SystemExit("PROFILE_SSM_TIMEOUT")


def publish_issue(path):
    run_id = os.environ.get("GH_RUN_ID", "")
    token = os.environ.get("GH_TOKEN", "")
    if (os.environ.get("GH_REPOSITORY") != REPO
            or not re.fullmatch(r"\d{1,20}", run_id)
            or not token or any(c.isspace() for c in token)):
        raise SystemExit("PROFILE_BAD_GITHUB_CONTEXT")
    raw = ""
    if path.is_file() and not path.is_symlink() and path.stat().st_size < 12000:
        raw = path.read_text(encoding="utf-8", errors="replace")
    parsed = parse_receipt(raw)
    outcome = f"**{parsed[0]}** (providerCode={parsed[1]})" if parsed else "**NO_SANITIZED_RECEIPT**"
    text = ("### Upstox account-profile segment check (read-only, one GET)\n\n"
            "Result: " + outcome + ".\n\n"
            "Publishes no name, email, user id, product list, token, or provider body. "
            "No trading, persistence or rights change.\n\n"
            f"Protected workflow: https://github.com/{REPO}/actions/runs/{run_id}")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/issues/{ISSUE}/comments",
        method="POST", data=json.dumps({"body": text}).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "MintTick-Upstox-Profile-ReadOnly/1",
        })
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            if response.status != 201:
                raise SystemExit("PROFILE_ISSUE_NOT_ACCEPTED")
    except (urllib.error.URLError, OSError):
        raise SystemExit("PROFILE_ISSUE_UNAVAILABLE") from None
    print("PROFILE_ISSUE_SAFE_RECEIPT_POSTED", flush=True)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("PROFILE_INVALID_MODE")
    if len(sys.argv) == 2 and sys.argv[1] == "host":
        host()
    elif len(sys.argv) == 2 and sys.argv[1] == "runner":
        runner()
    elif len(sys.argv) == 3 and sys.argv[1] == "issue":
        publish_issue(Path(sys.argv[2]))
    else:
        raise SystemExit("PROFILE_INVALID_MODE")
