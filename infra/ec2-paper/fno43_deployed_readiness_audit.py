#!/usr/bin/env python3
"""Read-only deployed F&O-43 evidence from exact PAPER EC2, via SSM.

Never accesses a customer bearer token, broker or quote API, nor prints private
market data. X-API-Key is used only on the local host and never returned.
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
REQUEST = Path("infra/ec2-paper/requests/fno43-deployed-readiness.json")
MARKER = "MINTTICK_FNO43_DEPLOYED_READINESS:"
BASE = "http://127.0.0.1:8080/api/admin/research/fno-43/"
ROUTES = ("readiness", "source-evidence")
ALLOWED_STATUS = {
    "OBSERVED", "AUTH_REJECTED", "ROUTE_NOT_DEPLOYED",
    "BACKEND_ERROR", "UNAVAILABLE", "BAD_API_KEY",
    "INVALID_HOST", "INVALID_RESPONSE",
}
MAX_RESPONSE = 210000
COUNTS = (
    "distinct", "sourceRights", "historical", "paper", "live",
    "originalEnvelopes", "sourceMarket", "sourcePaper", "sourceLive",
)
RECEIPT = re.compile(
    r"MINTTICK_FNO43_DEPLOYED_READINESS:status=([A-Z_]+);"
    r"distinct=(\d{1,2});sourceRights=(\d{1,2});"
    r"historical=(\d{1,2});paper=(\d{1,2});live=(\d{1,2});"
    r"originalEnvelopes=(\d{1,3});sourceMarket=(\d{1,2});"
    r"sourcePaper=(\d{1,2});sourceLive=(\d{1,2});"
    r"orders=false;customerAuth=false;privateData=false"
)

def expected_request():
    return {
        "kind": "minttick-fno43-deployed-operator-readonly-v1",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "operations": ["GET /api/admin/research/fno-43/readiness",
                       "GET /api/admin/research/fno-43/source-evidence"],
        "no_order_calls": True,
        "no_customer_auth": True,
        "no_market_data_export": True,
        "no_rights_or_deployment_change": True,
    }


def sanitize(status, metrics=None):
    if status not in ALLOWED_STATUS:
        raise ValueError("STATUS_NOT_ALLOWLISTED")
    metrics = metrics if metrics is not None else {k: 0 for k in COUNTS}
    if set(metrics) != set(COUNTS) or any(type(metrics[k]) is not int or
          not 0 <= metrics[k] <= (100 if k == "originalEnvelopes" else 43)
          for k in COUNTS):
        raise ValueError("INVALID_METRICS")
    return (MARKER + "status=" + status + ";" +
            ";".join(k + "=" + str(metrics[k]) for k in COUNTS) +
            ";orders=false;customerAuth=false;privateData=false")


def receipt(raw: str):
    if not isinstance(raw, str) or len(raw) > 2500:
        return None
    lines = [line for line in raw.splitlines() if line.startswith(MARKER)]
    if len(lines) != 1:
        return None
    line = lines[0]
    match = RECEIPT.fullmatch(line)
    if not match or match.group(1) not in ALLOWED_STATUS:
        return None
    vals = dict(zip(COUNTS, (int(x) for x in match.groups()[1:])))
    try:
        return line if line == sanitize(match.group(1), vals) else None
    except ValueError:
        return None


def _ids(rows):
    return (isinstance(rows, list) and len(rows) == 43 and
            [row.get("strategyId") if isinstance(row, dict) else None
             for row in rows] ==
            ["strategy.STR-" + str(n) for n in range(170, 213)])


def _count(d, key, high=43):
    v = d.get(key)
    if type(v) is not int or not 0 <= v <= high:
        raise ValueError("INVALID_AGGREGATE")
    return v


def check_payload(readiness, evidence):
    """Only strict aggregate metrics escape the instance."""
    if not isinstance(readiness, dict) or not isinstance(evidence, dict):
        raise ValueError("INVALID_RESPONSE")
    if not _ids(readiness.get("strategies")) or not _ids(evidence.get("strategies")):
        raise ValueError("MISSING_OR_DUPLICATE_43")
    if readiness.get("contractVersion") != "minttick.fno43.runtime-source-readiness.v1":
        raise ValueError("UNRECOGNIZED_READINESS")
    if evidence.get("contractVersion") != "minttick.fno43.runtime-source-audit.v1":
        raise ValueError("UNRECOGNIZED_EVIDENCE")
    if _count(readiness, "manifestStrategies") != 43 or _count(evidence, "expectedStrategies") != 43:
        raise ValueError("INCORRECT_MANIFEST_COUNT")
    if evidence.get("brokerOrdersSubmitted") is not False:
        raise ValueError("UNEXPECTED_BROKER_EXECUTION_FLAG")
    numbers = {
        "distinct": _count(readiness, "distinctEvaluatorsObserved"),
        "sourceRights": _count(readiness, "independentlyVerifiedSourceStrategies"),
        "historical": _count(readiness, "historicalDataE2EValidated"),
        "paper": _count(readiness, "paperExecutionValidated"),
        "live": _count(readiness, "liveExecutionValidated"),
        "originalEnvelopes": _count(evidence, "eligibleRightsAttestedEnvelopes", 100),
        "sourceMarket": _count(evidence, "independentMarketE2EValidated"),
        "sourcePaper": _count(evidence, "paperLedgerE2EValidated"),
        "sourceLive": _count(evidence, "liveBrokerE2EValidated"),
    }
    return numbers


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def _get(path, key, opener):
    req = urllib.request.Request(BASE + path, method="GET", headers={
        "X-API-Key": key,
        "User-Agent": "MintTick-Fno43-OperatorAudit/1.0",
        "Accept": "application/json",
        "Cache-Control": "no-store",
    })
    try:
        with opener.open(req, timeout=50) as response:
            if response.status != 200:
                return "UNAVAILABLE", None
            raw = response.read(MAX_RESPONSE + 1)
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            return "AUTH_REJECTED", None
        if error.code == 404:
            return "ROUTE_NOT_DEPLOYED", None
        if 500 <= error.code < 600:
            return "BACKEND_ERROR", None
        return "UNAVAILABLE", None
    except (urllib.error.URLError, OSError, TimeoutError):
        return "UNAVAILABLE", None
    if len(raw) > MAX_RESPONSE:
        return "INVALID_RESPONSE", None
    try:
        return "OBSERVED", json.loads(raw)
    except (ValueError, TypeError, UnicodeError):
        return "INVALID_RESPONSE", None


def host():
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        print(sanitize("INVALID_HOST"), flush=True)
        return
    env = Path("/opt/aitradingsystem-paper/runtime.env")
    if env.is_symlink() or not env.is_file() or env.stat().st_mode & 0o077:
        print(sanitize("INVALID_HOST"), flush=True)
        return
    keys = [line.split("=", 1)[1] for line in env.read_text().splitlines()
            if line.startswith("APP_API_KEY=")]
    if len(keys) != 1 or not re.fullmatch(r"[0-9a-f]{64}", keys[0]):
        print(sanitize("BAD_API_KEY"), flush=True)
        return
    opener = urllib.request.build_opener(NoRedirect())
    rows = []
    for path in ROUTES:
        status, obj = _get(path, keys[0], opener)
        if status != "OBSERVED":
            print(sanitize(status), flush=True)
            return
        rows.append(obj)
    try:
        print(sanitize("OBSERVED", check_payload(*rows)), flush=True)
    except (ValueError, TypeError):
        print(sanitize("INVALID_RESPONSE"), flush=True)


def runner():
    if (REQUEST.is_symlink() or not REQUEST.is_file() or REQUEST.stat().st_size > 1500
            or json.loads(REQUEST.read_text()) != expected_request()):
        raise RuntimeError("FNO43_REQUEST_NOT_APPROVED")
    sys.path.insert(0, "infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    encoded = base64.b64encode(gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(encoded) > 23000 or not re.fullmatch(r"[A-Za-z0-9+/=]+", encoded):
        raise RuntimeError("FNO43_AUDIT_PAYLOAD_INVALID")
    shell = ("set -eu\nprintf '%s' '" + encoded +
             "' | base64 -d | gzip -dc | PYTHONDONTWRITEBYTECODE=1 python3 - host\n")

    def aws(*args):
        p = subprocess.run(["aws", *args, "--region", REGION,
                            "--output", "json", "--no-cli-pager"],
                           capture_output=True, text=True, timeout=45, check=False)
        if p.returncode:
            raise RuntimeError("FNO43_AUDIT_SSM_UNAVAILABLE")
        return json.loads(p.stdout)

    sent = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
               "--instance-ids", INSTANCE, "--timeout-seconds", "160",
               "--comment", "MintTick read-only FNO43 runtime readiness",
               "--parameters", json.dumps({"commands": [shell]}))
    command_id = (sent.get("Command") or {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", command_id):
        raise RuntimeError("FNO43_AUDIT_INVALID_COMMAND")
    for _ in range(36):
        time.sleep(4)
        result = aws("ssm", "get-command-invocation",
                     "--command-id", command_id, "--instance-id", INSTANCE)
        if result.get("Status") in ("Success", "Failed", "TimedOut", "Cancelled"):
            parsed = receipt(result.get("StandardOutputContent", ""))
            if result.get("Status") != "Success" or parsed is None:
                raise RuntimeError("FNO43_AUDIT_MISSING_SAFE_RECEIPT")
            print(parsed, flush=True)
            return
        if result.get("Status") not in ("Pending", "InProgress", "Delayed"):
            raise RuntimeError("FNO43_AUDIT_UNKNOWN_STATUS")
    raise RuntimeError("FNO43_AUDIT_TIMEOUT")


if __name__ == "__main__":
    if sys.argv[1:] == ["host"]:
        host()
    elif sys.argv[1:] == ["runner"]:
        runner()
    else:
        raise SystemExit("FNO43_AUDIT_INVALID_MODE")
