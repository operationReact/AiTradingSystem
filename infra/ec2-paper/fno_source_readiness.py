#!/usr/bin/env python3
"""Single-use private, non-persisting Upstox F&O readiness evidence over existing EC2 SSM.

No market quotes, bearer, AWS token, operator key, response body, or private
account data are printed. This never enables LIVE/PAPER orders, V3 streaming
or any data use rights. Its only provider call is the already reviewed backend
private ADMIN F&O REST source probe, scoped to an exchange-listed expiry.
"""
from __future__ import annotations

import base64
import gzip
import json
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
EXPECTED_RELEASE = "97e6cc183deb6c76e4c29b618a02dc971dd4c40d"
EXPIRY = "2026-11-17"
STATUS = {"READS_VERIFIED", "PARTIAL_DATA", "INVALID_PROVENANCE",
          "PROVIDER_UNAVAILABLE", "DISABLED", "INVALID_EXPIRY", "COOLDOWN"}
# Only the server-owned, sanitized F&O failure enum is printable; never errors
# sourced from provider text, URLs, token material or JSON body fields.
SOURCE_FAILURES = {
    "UNAUTHORIZED", "FORBIDDEN", "RATE_LIMITED", "NOT_FOUND",
    "UPSTREAM_UNAVAILABLE", "UPSTREAM_REJECTED", "TRANSPORT_FAILURE",
    "CREDENTIAL_UNAVAILABLE", "INVALID_PAYLOAD",
    "SCHEMA_INVALID", "ENVELOPE_INVALID"
}
STATUS |= {stage + "_" + failure
           for stage in ("CONTRACTS", "CHAIN")
           for failure in SOURCE_FAILURES}
REQUEST_PATH = "infra/ec2-paper/requests/fno-source-readiness.json"


def expected_request() -> dict:
    return {
        "kind": "minttick-private-fno-source-readiness-v1",
        "operation": "probe-upstox-nifty-options-read-only",
        "environment": "PAPER",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "backend_release": EXPECTED_RELEASE,
        "underlying": "NSE_INDEX|Nifty 50",
        "expiry": EXPIRY,
        "authorization": "OWNER_APPROVED_READ_ONLY_FNO_CHECK_2026_11_14",
    }


def validate_response(body: object) -> tuple[str, int, int, int, int, int]:
    """Strict output contract; counts are safe telemetry, not a quote entitlement."""
    if not isinstance(body, dict) or body.get("underlyingKey") != "NSE_INDEX|Nifty 50" \
            or body.get("expiry") != EXPIRY:
        raise ValueError("FNO_INVALID_PROVIDER_IDENTITY")
    state = body.get("status")
    if state not in STATUS:
        raise ValueError("FNO_INVALID_PROVIDER_STATUS")
    names = ["validatedContracts", "validatedStrikes", "callsWithContract",
             "putsWithContract", "rowsWithCompleteGreeks"]
    values = [body.get(k) for k in names]
    if any(type(n) is not int or n < 0 or n > 1024 for n in values):
        raise ValueError("FNO_INVALID_COUNTS")
    contracts, strikes, calls, puts, greeks = values
    if calls > strikes or puts > strikes or greeks > strikes:
        raise ValueError("FNO_INVALID_COUNTS")
    for key in ("stored", "exchangeQuoteTimeVerified", "displayRightsVerified",
                "modelTrainingRightsVerified", "liveTradingEnabled"):
        if body.get(key) is not False:
            raise ValueError("FNO_UNSUPPORTED_PROVENANCE_OR_RIGHTS")
    if state == "READS_VERIFIED" and not (
        contracts >= 2 and strikes >= 1 and calls == strikes and puts == strikes
    ):
        raise ValueError("FNO_FALSE_VERIFIED_STATUS")
    return state, contracts, strikes, calls, puts, greeks


def host() -> None:
    """Execute only on the exact existing PAPER EC2 via role-protected SSM."""
    import os
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        raise SystemExit("FNO_HOST_IDENTITY_INVALID")
    root = Path("/opt/aitradingsystem-paper")
    env = root / "runtime.env"
    compose_env = root / ".env"
    if any(p.is_symlink() or not p.is_file() for p in (env, compose_env)):
        raise SystemExit("FNO_RUNTIME_IDENTITY_INVALID")
    if env.stat().st_mode & 0o077 or compose_env.stat().st_mode & 0o077:
        raise SystemExit("FNO_RUNTIME_PERMISSIONS_INVALID")
    vals = {}
    for item in env.read_text().splitlines():
        if "=" in item:
            k, v = item.split("=", 1)
            if k in {"APP_API_KEY", "MINTTICK_UPSTOX_ANALYTICS_ENABLED",
                     "MINTTICK_UPSTOX_ANALYTICS_PROBE_ENABLED"}:
                vals[k] = v
    version = {}
    for item in compose_env.read_text().splitlines():
        if "=" in item:
            k, v = item.split("=", 1)
            if k in {"RELEASE_SHA", "TRADING_MODE"}:
                version[k] = v
    if version.get("RELEASE_SHA") != EXPECTED_RELEASE:
        # A release SHA is not a credential. Report the strictly validated
        # value to reconcile deployment/probe skew without exposing env.
        actual = version.get("RELEASE_SHA", "")
        if re.fullmatch(r"[0-9a-f]{40}", actual):
            print("MINTTICK_FNO_HOST_RELEASE:sha=" + actual, flush=True)
        raise SystemExit("FNO_BACKEND_REVISION_MISMATCH")
    if vals.get("MINTTICK_UPSTOX_ANALYTICS_ENABLED") != "true" or \
            vals.get("MINTTICK_UPSTOX_ANALYTICS_PROBE_ENABLED") != "true":
        raise SystemExit("FNO_ANALYTICS_NOT_ACTIVATED")
    operator = vals.get("APP_API_KEY", "")
    if not re.fullmatch("[0-9a-f]{64}", operator):
        raise SystemExit("FNO_OPERATOR_KEY_UNAVAILABLE")
    url = ("http://127.0.0.1:8080"
           "/api/admin/market-data/upstox-analytics/fno/probe?expiry=" + EXPIRY)
    req = urllib.request.Request(url, data=b"", method="POST",
                                 headers={"X-API-Key": operator, "Accept": "application/json"})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(req, timeout=50) as res:
            if res.status != 200:
                raise ValueError("FNO_PROBE_HTTP_NOT_200")
            raw = res.read(8192)
            if len(raw) > 4096:
                raise ValueError("FNO_PROBE_RESPONSE_TOO_LARGE")
            received = json.loads(raw)
        state, contracts, strikes, calls, puts, greeks = validate_response(received)
        print(f"MINTTICK_FNO_SOURCE:status={state} contracts={contracts} "
              f"strikes={strikes} calls={calls} puts={puts} greeks={greeks} "
              f"expiry={EXPIRY} stored=false rights=false", flush=True)
        if state != "READS_VERIFIED":
            raise SystemExit("FNO_PRIVATE_SOURCE_NOT_VERIFIED")
    except (urllib.error.URLError, TimeoutError):
        raise SystemExit("FNO_PRIVATE_PROBE_UNAVAILABLE")
    except (ValueError, TypeError, json.JSONDecodeError) as exc:
        # Only the fixed local code, never raw provider or secret data.
        safe = str(exc) if str(exc).startswith("FNO_") else "FNO_INVALID_RESPONSE"
        raise SystemExit(safe)



def sanitized_source_receipt(output: str) -> str | None:
    """Only permit exact, bounded, nonsecret status counters into CI."""
    if not isinstance(output, str) or len(output) > 20000:
        return None
    found = re.findall(
        r"MINTTICK_FNO_SOURCE:status=([A-Z_]+) contracts=(\d+) "
        r"strikes=(\d+) calls=(\d+) puts=(\d+) greeks=(\d+) "
        r"expiry=2026-11-17 stored=false rights=false", output)
    if len(found) != 1 or found[0][0] not in STATUS:
        return None
    state, *values = found[0]
    counts = [int(n) for n in values]
    if any(n > 1024 for n in counts):
        return None
    contracts, strikes, calls, puts, greeks = counts
    if calls > strikes or puts > strikes or greeks > strikes:
        return None
    return (f"status={state} contracts={contracts} strikes={strikes} "
            f"calls={calls} puts={puts} greeks={greeks} expiry={EXPIRY} "
            "stored=false rights=false")

def runner() -> None:
    """AWS actions runner: no arbitrary commands, only verified read-only probe."""
    path = Path(REQUEST_PATH)
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 2048:
        raise SystemExit("FNO_UNEXPECTED_REQUEST")
    if json.loads(path.read_text()) != expected_request():
        raise SystemExit("FNO_REQUEST_CONTRACT_MISMATCH")
    sys.path.insert(0, "infra/ec2-paper")
    import verify_ssm_release
    verify_ssm_release.verify_aws()
    script = Path(__file__).read_bytes()
    payload = base64.b64encode(gzip.compress(script, compresslevel=9, mtime=0)).decode()
    if len(payload) > 8000 or not re.fullmatch("[A-Za-z0-9+/=]+", payload):
        raise SystemExit("FNO_SSM_PAYLOAD_OVERSIZED")
    command = f"set -eu\nprintf '%s' '{payload}' | base64 -d | gzip -d | python3 - host\n"
    def aws(*args: str) -> dict:
        process = subprocess.run(
            ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"],
            check=False, capture_output=True, text=True, timeout=55)
        if process.returncode:
            raise RuntimeError("FNO_SSM_OPERATION_UNAVAILABLE")
        return json.loads(process.stdout)
    send = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
               "--instance-ids", INSTANCE, "--timeout-seconds", "210",
               "--comment", "Private read-only MintTick F&O option-chain source readiness",
               "--parameters", json.dumps({"commands": [command]}))
    cid = (send.get("Command") or {}).get("CommandId", "")
    if not re.fullmatch("[0-9a-f-]{36}", cid):
        raise SystemExit("FNO_SSM_ID_INVALID")
    for attempt in range(45):
        time.sleep(4)
        try:
            got = aws("ssm", "get-command-invocation", "--command-id", cid,
                      "--instance-id", INSTANCE)
        except RuntimeError:
            if attempt < 5:
                continue
            raise
        state = got.get("Status")
        if state == "Success":
            stdout = got.get("StandardOutputContent") or ""
            matches = re.findall(
                r"MINTTICK_FNO_SOURCE:status=([A-Z_]+) contracts=(\d+) "
                r"strikes=(\d+) calls=(\d+) puts=(\d+) greeks=(\d+) "
                r"expiry=2026-11-17 stored=false rights=false", stdout)
            if len(matches) != 1 or matches[0][0] != "READS_VERIFIED":
                raise SystemExit("FNO_SOURCE_MISSING_VERIFIED_RECEIPT")
            match = matches[0]
            print("FNO SOURCE READ VERIFIED via protected SSM: "
                  f"expiry={EXPIRY} contracts={match[1]} strikes={match[2]} "
                  f"calls={match[3]} puts={match[4]} greeks={match[5]} "
                  "rights=NOT_VERIFIED; no orders", flush=True)
            return
        if state in {"Failed", "Cancelled", "TimedOut", "Cancelling"}:
            out = (got.get("StandardOutputContent") or "") + "\n" + \
                  (got.get("StandardErrorContent") or "")
            receipt = sanitized_source_receipt(out)
            if receipt is not None:
                # Non-verifying codes such as DISABLED/PROVIDER_UNAVAILABLE remain blocked.
                print("FNO SOURCE OBSERVED (not verified): " + receipt, flush=True)
            known = re.findall(r"\b(FNO_[A-Z_]+)\b", out)
            code = known[-1] if known else "FNO_HOST_PROBE_FAILED"
            releases = re.findall(r"MINTTICK_FNO_HOST_RELEASE:sha=([0-9a-f]{40})", out)
            if len(releases) == 1:
                print("FNO HOST RELEASE observed=" + releases[0]
                      + " pinned=" + EXPECTED_RELEASE, flush=True)
            print("FNO PRIVATE SOURCE BLOCKED: " + code, flush=True)
            raise SystemExit(1)
        if state not in {"Pending", "InProgress", "Delayed"}:
            raise SystemExit("FNO_UNEXPECTED_SSM_STATE")
    raise SystemExit("FNO_SSM_PROBE_TIMEOUT")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in {"host", "runner"}:
        raise SystemExit("FNO_INVALID_COMMAND")
    if sys.argv[1] == "host":
        host()
    else:
        runner()
