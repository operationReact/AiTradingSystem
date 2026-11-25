#!/usr/bin/env python3
"""Fixed, read-only HTTP checks on exact private MintTick EC2, via AWS SSM.

No live order submissions, broker tokens, secret output, market quotes,
credentials, customer identities, persistence or permission mutations.
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

INSTANCE = "i-09c01b470fb39b85a"
REGION = "ap-south-1"
ACCOUNT = "098859530205"
REQUEST_FILE = "infra/ec2-paper/requests/fno-v3-http-smoke.json"
ALLOWED_STATE = {"STOPPED", "CONNECTING", "SUBSCRIBING",
    "CONNECTED_AWAITING_TICKS", "CONNECTED", "RECONNECTING", "ERROR",
    "DISCONNECTED", "BACKOFF", "RECONNECT_LIMIT"}
EXPECTED = {
    "application": "MintTick", "region": REGION, "aws_account": ACCOUNT,
    "instance_id": INSTANCE, "operation": "read-only-fno-v3-http-smoke"
}

def http_get(path: str, key: str | None = None) -> tuple[int, object | None]:
    if not path.startswith("/") or path.startswith("//") or "://" in path or "?" in path:
        raise ValueError("INVALID_PATH")
    url = "http://127.0.0.1:8080" + path
    headers = {"Accept": "application/json"}
    if key:
        headers["X-API-Key"] = key
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, headers=headers, method="GET"),
                         timeout=8) as res:
            if res.status != 200:
                return res.status, None
            body = res.read(4097)
            if len(body) > 4096:
                return 0, None
            try:
                return 200, json.loads(body)
            except (ValueError, UnicodeDecodeError):
                return 0, None
    except urllib.error.HTTPError as err:
        return err.code, None
    except (urllib.error.URLError, TimeoutError, OSError):
        return 0, None

def safe_status(state: object) -> str:
    return state if isinstance(state, str) and state in ALLOWED_STATE else "UNAVAILABLE"

def host() -> None:
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        raise SystemExit("FNO_HOST_IDENTITY_INVALID")
    root = Path("/opt/aitradingsystem-paper")
    env, compose = root / "runtime.env", root / ".env"
    if any(p.is_symlink() or not p.is_file() or (p.stat().st_mode & 0o077)
           for p in (env, compose)):
        raise SystemExit("FNO_HOST_ENV_INVALID")
    values = {}
    for line in env.read_text().splitlines():
        name, sep, value = line.partition("=")
        if sep and name in {"APP_API_KEY",
                "MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED",
                "MINTTICK_UPSTOX_FNO_AUTO_MARKET_FEED_ENABLED",
                "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED",
                "MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED",
                "MINTTICK_UPSTOX_FNO_CONTRACT_DISCOVERY_VERIFIED"}:
            values[name] = value
    version = {}
    for line in compose.read_text().splitlines():
        name, sep, value = line.partition("=")
        if sep and name == "RELEASE_SHA":
            version[name] = value
    sha = version.get("RELEASE_SHA", "")
    print("FNO_API_RELEASE: " + (sha if re.fullmatch("[0-9a-f]{40}", sha)
                                 else "UNAVAILABLE"), flush=True)
    for label, flag in (("FEED", "MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED"),
                        ("AUTO", "MINTTICK_UPSTOX_FNO_AUTO_MARKET_FEED_ENABLED"),
                        ("DISPLAY", "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED")):
        print("FNO_API_FLAG_" + label + ": " +
              ("ENABLED" if values.get(flag) == "true" else "NOT_ENABLED"),
              flush=True)
    for label, flag in (("LIVE_ORDER", "MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED"),
                        ("DISCOVERY", "MINTTICK_UPSTOX_FNO_CONTRACT_DISCOVERY_VERIFIED")):
        print("FNO_API_FLAG_" + label + ": " +
              ("ENABLED" if values.get(flag) == "true" else "NOT_ENABLED"), flush=True)
    code, data = http_get("/actuator/health/readiness")
    print("FNO_API_BACKEND: " +
          ("UP" if code == 200 and isinstance(data, dict)
            and data.get("status") == "UP" else "UNAVAILABLE"), flush=True)
    code, _ = http_get("/api/customer/markets/fno/capability")
    print("FNO_API_CUSTOMER_GATE: " +
          ("PROTECTED" if code in (401, 403) else
           "UNAVAILABLE" if code == 0 else "UNEXPECTED"), flush=True)
    key = values.get("APP_API_KEY", "")
    if not re.fullmatch("[0-9a-f]{64}", key):
        print("FNO_API_OPERATOR_ROUTE: KEY_UNAVAILABLE", flush=True)
        return
    code, result = http_get("/api/admin/market-data/upstox-analytics/fno/live/status", key)
    if code == 404:
        print("FNO_API_OPERATOR_ROUTE: NOT_REGISTERED", flush=True)
        return
    if code in (401, 403):
        print("FNO_API_OPERATOR_ROUTE: DENIED", flush=True)
        return
    if code != 200 or not isinstance(result, dict):
        print("FNO_API_OPERATOR_ROUTE: UNAVAILABLE", flush=True)
        return
    state = safe_status(result.get("state"))
    ticks = result.get("freshExchangeTimedQuotes")
    frames = result.get("decodedFrames")
    subs = result.get("subscriptions")
    if not all(type(x) is int and 0 <= x <= 1000000000
               for x in (ticks, frames, subs)) or subs > 64 or ticks > subs:
        print("FNO_API_OPERATOR_ROUTE: INVALID_STATUS", flush=True)
        return
    print("FNO_API_OPERATOR_ROUTE: RESPONDED", flush=True)
    print("FNO_API_STREAM: " + state, flush=True)
    print("FNO_API_SUBSCRIPTIONS: " + str(subs), flush=True)
    print("FNO_API_FRESH_TICKS: " + str(ticks), flush=True)
    print("FNO_API_DECODED_FRAMES: " + str(frames), flush=True)
    research_code, research = http_get("/api/admin/research/fno-futures/status", key)
    rows = research if research_code == 200 and isinstance(research, list) else []
    summary = []
    for row in rows:
        if isinstance(row, dict) and re.fullmatch(r"strategy\.STR-17[0-9]", str(row.get("strategyId", ""))) \
                and re.fullmatch(r"[A-Z_]{4,40}", str(row.get("status", ""))) \
                and re.fullmatch(r"[A-Z0-9_]{4,60}", str(row.get("reasonCode", ""))) \
                and all(type(row.get(k)) is int and 0 <= row.get(k) <= 1000
                        for k in ("contractsResolved", "freshQuotes", "historySessions")):
            summary.append("%s=%s:%s:c%d:q%d:h%d" % (
                row["strategyId"].split(".")[1], row["status"], row["reasonCode"],
                row["contractsResolved"], row["freshQuotes"], row["historySessions"]))
    print("FNO_API_RESEARCH: " + (";".join(summary) if len(rows) == 3 and len(summary) == 3
                                   else "UNAVAILABLE"), flush=True)
    # Deliberately do not print any exchange instrument, option price or raw response.

def runner() -> None:
    request = Path(REQUEST_FILE)
    if not request.is_file() or request.is_symlink() or request.stat().st_size > 1024:
        raise SystemExit("FNO_API_BAD_REQUEST")
    if json.loads(request.read_text()) != EXPECTED:
        raise SystemExit("FNO_API_REQUEST_MISMATCH")
    sys.path.insert(0, "infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    raw = Path(__file__).read_bytes()
    payload = base64.b64encode(gzip.compress(raw, mtime=0)).decode()
    if len(payload) > 13000 or not re.fullmatch("[A-Za-z0-9+/=]+", payload):
        raise SystemExit("FNO_API_SCRIPT_TOO_LARGE")
    script = "set -eu\nprintf '%s' '" + payload + "' | base64 -d | gzip -d | python3 - host\n"

    def aws(*args: str) -> dict:
        cmd = ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"]
        p = subprocess.run(cmd, check=False, capture_output=True, text=True, timeout=50)
        if p.returncode:
            raise SystemExit("FNO_API_SSM_UNAVAILABLE")
        return json.loads(p.stdout)
    data = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
        "--instance-ids", INSTANCE, "--timeout-seconds", "120",
        "--comment", "Read-only MintTick FNO V3 localhost HTTP smoke",
        "--parameters", json.dumps({"commands": [script]}))
    cid = (data.get("Command") or {}).get("CommandId", "")
    if not re.fullmatch("[0-9a-f-]{36}", cid):
        raise SystemExit("FNO_API_SSM_BAD_ID")
    allow = {
        "FNO_API_RELEASE": r"(?:[0-9a-f]{40}|UNAVAILABLE)",
        "FNO_API_FLAG_FEED": r"(?:ENABLED|NOT_ENABLED)",
        "FNO_API_FLAG_AUTO": r"(?:ENABLED|NOT_ENABLED)",
        "FNO_API_FLAG_DISPLAY": r"(?:ENABLED|NOT_ENABLED)",
        "FNO_API_BACKEND": r"(?:UP|UNAVAILABLE)",
        "FNO_API_CUSTOMER_GATE": r"(?:PROTECTED|UNAVAILABLE|UNEXPECTED)",
        "FNO_API_OPERATOR_ROUTE": r"(?:RESPONDED|NOT_REGISTERED|KEY_UNAVAILABLE|DENIED|UNAVAILABLE|INVALID_STATUS)",
        "FNO_API_STREAM": r"[A-Z_]{2,40}",
        "FNO_API_SUBSCRIPTIONS": r"[0-9]{1,2}",
        "FNO_API_FRESH_TICKS": r"[0-9]{1,2}",
        "FNO_API_DECODED_FRAMES": r"[0-9]{1,10}",
        "FNO_API_FLAG_LIVE_ORDER": r"(?:ENABLED|NOT_ENABLED)",
        "FNO_API_FLAG_DISCOVERY": r"(?:ENABLED|NOT_ENABLED)",
        "FNO_API_RESEARCH": r"(?:UNAVAILABLE|[A-Za-z0-9_=:;.-]{1,900})",
    }
    for _ in range(35):
        time.sleep(4)
        status = aws("ssm", "get-command-invocation", "--command-id", cid,
                     "--instance-id", INSTANCE)
        state = status.get("Status")
        if state in ("Success", "Failed", "TimedOut", "Cancelled"):
            seen = {}
            for line in (status.get("StandardOutputContent") or "").splitlines():
                k, sep, v = line.partition(": ")
                if sep and k in allow and re.fullmatch(allow[k], v) and k not in seen:
                    seen[k] = v
            for k, v in sorted(seen.items()):
                print(k + ": " + v)
            needed = {"FNO_API_RELEASE", "FNO_API_FLAG_FEED",
                "FNO_API_FLAG_AUTO", "FNO_API_FLAG_DISPLAY",
                "FNO_API_BACKEND", "FNO_API_CUSTOMER_GATE",
                "FNO_API_OPERATOR_ROUTE"}
            if state != "Success" or not needed.issubset(seen):
                raise SystemExit("FNO_API_PROBE_FAILED")
            return
        if state not in ("Pending", "InProgress", "Delayed"):
            raise SystemExit("FNO_API_UNEXPECTED_SSM_STATE")
    raise SystemExit("FNO_API_PROBE_TIMEOUT")

if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("host", "runner"):
        raise SystemExit("FNO_API_INVALID_MODE")
    host() if sys.argv[1] == "host" else runner()
