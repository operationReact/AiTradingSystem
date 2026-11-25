#!/usr/bin/env python3
"""Exact-host, read-only MintTick expired F&O downloader readiness audit.

A narrow GitHub dev push of a separate owner-approved JSON request performs
ONE AWS SSM shell read on the pre-existing PAPER EC2 host. This script NEVER
calls an Upstox endpoint, reads credentials, creates files/databases, starts
or stops processes, downloads candles, changes retention rights or executes
orders. Host outputs only a fixed allowlisted status receipt.

The GitHub runner requires verify_ssm_release.verify_aws() exact AWS identity,
role, existing EC2, SSM and schedule assertions before remote execution.
"""
from __future__ import annotations

import base64
from datetime import datetime, timezone
import gzip
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
ROOT = Path("/opt/aitradingsystem-paper")
REQUEST = Path("infra/ec2-paper/requests/fno-history-readiness.json")
MARKER = "MINTTICK_FNO_READINESS_V1:"
JOURNAL = ROOT / "fno-history" / "checkpoints.sqlite"
SNAPSHOT = ROOT / "fno-history" / "progress.json"
MODULE = ROOT / "infra" / "ec2-paper" / "fno_history_backfill.py"
BACKUP_MODULE = ROOT / "fno_history_backfill.py"
SERVICE = "minttick-fno-history-backfill.service"

# IMPORTANT: a missing rights attestation is UNKNOWN, never approved by
# the presence of a paid Upstox Plus plan or a successful GET.
IDENTITY = {"PASS", "DENIED"}
CODE = {"PRESENT", "NOT_INSTALLED"}
JOURNAL_STATUS = {"PRESENT", "MISSING"}
SNAPSHOT_STATUS = {"PRESENT_FRESH", "PRESENT_STALE", "INVALID", "MISSING"}
SERVICE_STATUS = {"RUNNING", "STOPPED", "NOT_INSTALLED", "UNKNOWN"}
RELEASE = {"UNVERIFIED"}
OUTCOMES = {"READY_FOR_RIGHTS_REVIEW", "RUNTIME_NOT_INSTALLED", "RUNTIME_ANOMALY"}


def expected_request():
    return {
        "kind": "minttick-fno-history-readiness-v1",
        "operation": "inspect-existing-fno-history-runtime-without-writes",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "environment": "PAPER",
        "authorization": "OWNER_APPROVED_READONLY_FNO_HISTORY_READINESS_2026_11_21",
        "max_provider_gets": 0,
        "persist": False,
        "trading_rights_change": False,
    }


def make_receipt(identity, code, journal, snapshot, service, release, outcome):
    values = (identity, code, journal, snapshot, service, release, outcome)
    groups = (IDENTITY, CODE, JOURNAL_STATUS, SNAPSHOT_STATUS,
              SERVICE_STATUS, RELEASE, OUTCOMES)
    if any(v not in group for v, group in zip(values, groups)):
        raise ValueError("INVALID_READINESS_RECEIPT")
    return MARKER + "|".join(values)


def parse_receipt(raw: str):
    """Reject freeform and multiple receipts; no host text is forwarded."""
    if not isinstance(raw, str) or len(raw) > 3500:
        return None
    matches = [line.strip() for line in raw.splitlines()
               if line.startswith(MARKER)]
    if len(matches) != 1 or len(matches[0]) > 240:
        return None
    fields = matches[0][len(MARKER):].split("|")
    if len(fields) != 7:
        return None
    try:
        if make_receipt(*fields) != matches[0]:
            return None
    except ValueError:
        return None
    return tuple(fields)


def snapshot_status(path: Path, now=None):
    if path.is_symlink() or not path.is_file():
        return "MISSING"
    try:
        if path.stat().st_size > 4096 or path.stat().st_size < 40:
            return "INVALID"
        document = json.loads(path.read_text(encoding="utf-8"))
        if (not isinstance(document, dict)
                or document.get("schema") != "minttick.fno.history.progress.v1"
                or document.get("source") != "upstox-expired-nse-fo"
                or document.get("planScope") != "CURRENT_JOURNAL_BATCHES_ONLY"
                or document.get("retentionRightsVerified") is not False
                or document.get("storageActive") is not False
                or document.get("state") != "PREPARATION_ONLY"):
            return "INVALID"
        timestamp = datetime.fromisoformat(
            document["generatedAt"].replace("Z", "+00:00"))
        if timestamp.tzinfo is None:
            return "INVALID"
        now = now or datetime.now(timezone.utc)
        age = (now - timestamp.astimezone(timezone.utc)).total_seconds()
        return "PRESENT_STALE" if age > 600 else (
            "INVALID" if age < -300 else "PRESENT_FRESH")
    except (OSError, ValueError, TypeError, KeyError, OverflowError):
        return "INVALID"


def service_status(run=subprocess.run):
    """Read service state; never start or create systemd units."""
    try:
        result = run(["systemctl", "is-active", "--", SERVICE],
                     capture_output=True, text=True, timeout=8,
                     check=False)
        value = result.stdout.strip()
        if len(value) > 30:
            return "UNKNOWN"
        if value == "active" and result.returncode == 0:
            return "RUNNING"
        if value in {"inactive", "failed", "deactivating", "activating"}:
            return "STOPPED"
        if value == "unknown":
            return "NOT_INSTALLED"
        return "UNKNOWN"
    except (OSError, subprocess.TimeoutExpired):
        return "UNKNOWN"


def inspect_host(root=ROOT, journal=JOURNAL, snapshot=SNAPSHOT,
                 module=MODULE, backup=BACKUP_MODULE,
                 svc=service_status, enforce_host_identity=True):
    identity = "PASS"
    if enforce_host_identity:
        try:
            if os.geteuid() != 0 or os.uname().machine != "aarch64":
                identity = "DENIED"
            for name in (".env", "runtime.env"):
                path = root / name
                if path.is_symlink() or not path.is_file() or (
                        path.stat().st_mode & 0o077):
                    identity = "DENIED"
        except (OSError, ValueError):
            identity = "DENIED"
    code = "PRESENT" if any(p.is_file() and not p.is_symlink()
                            for p in (module, backup)) else "NOT_INSTALLED"
    journal_state = ("PRESENT" if journal.is_file() and not journal.is_symlink()
                     else "MISSING")
    snap = snapshot_status(snapshot)
    service = svc()
    # Never derive rights from environment variables, Plus, an SSM token,
    # or successfully downloaded sample candles.
    rights = "UNVERIFIED"
    if identity != "PASS" or service == "RUNNING" and code == "NOT_INSTALLED":
        state = "RUNTIME_ANOMALY"
    elif code == "NOT_INSTALLED" or journal_state == "MISSING":
        state = "RUNTIME_NOT_INSTALLED"
    else:
        state = "READY_FOR_RIGHTS_REVIEW"
    return (identity, code, journal_state, snap, service, rights, state)


def host():
    receipt = inspect_host()
    print(make_receipt(*receipt), flush=True)
    return 0 if receipt[0] == "PASS" else 2


def _aws(*args):
    try:
        r = subprocess.run(
            ["aws", *args, "--region", REGION, "--output", "json",
             "--no-cli-pager"], capture_output=True, text=True,
            timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise SystemExit("FNO_READINESS_SSM_UNAVAILABLE") from None
    if r.returncode:
        raise SystemExit("FNO_READINESS_SSM_UNAVAILABLE")
    try:
        return json.loads(r.stdout)
    except ValueError:
        raise SystemExit("FNO_READINESS_SSM_INVALID") from None


def runner():
    request = REQUEST
    if (not request.is_file() or request.is_symlink()
            or request.stat().st_size > 1024
            or json.loads(request.read_text()) != expected_request()):
        raise SystemExit("FNO_READINESS_REQUEST_NOT_APPROVED")
    sys.path.insert(0, "infra/ec2-paper")
    from verify_ssm_release import verify_aws
    verify_aws()
    payload = base64.b64encode(
        gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(payload) > 15000 or not re.fullmatch(r"[A-Za-z0-9+/=]+", payload):
        raise SystemExit("FNO_READINESS_OVERSIZED")
    shell = ("set -eu\nprintf '%s' '" + payload
             + "' | base64 -d | gzip -dc | python3 - host\n")
    result = _aws("ssm", "send-command", "--document-name",
                  "AWS-RunShellScript", "--instance-ids", INSTANCE,
                  "--timeout-seconds", "150", "--comment",
                  "MintTick historical FNO zero-provider-request runtime readiness",
                  "--parameters", json.dumps({"commands": [shell]}))
    command_id = (result.get("Command") or {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(
            r"[0-9a-f-]{36}", command_id):
        raise SystemExit("FNO_READINESS_SSM_COMMAND_INVALID")
    for _ in range(30):
        time.sleep(4)
        reply = _aws("ssm", "get-command-invocation",
                     "--command-id", command_id, "--instance-id", INSTANCE)
        state = reply.get("Status")
        if state in {"Success", "Failed", "TimedOut", "Cancelled"}:
            fields = parse_receipt(reply.get("StandardOutputContent") or "")
            if fields is None:
                raise SystemExit("FNO_READINESS_NO_SAFE_RECEIPT")
            identity, code, journal, snap, service, rights, outcome = fields
            print(f"FNO READINESS: identity={identity}, code={code}, "
                  f"journal={journal}, snapshot={snap}, service={service}, "
                  f"rights={rights}, outcome={outcome}; "
                  "providerGets=0; persisted=false; orders=false", flush=True)
            if state != "Success" or identity != "PASS":
                raise SystemExit("FNO_READINESS_HOST_CHECK_FAILED")
            return
        if state not in {"Pending", "InProgress", "Delayed"}:
            raise SystemExit("FNO_READINESS_SSM_STATE_INVALID")
    raise SystemExit("FNO_READINESS_SSM_TIMEOUT")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in {"host", "runner"}:
        raise SystemExit("USAGE: fno_history_readiness.py host|runner")
    if sys.argv[1] == "host":
        raise SystemExit(host())
    runner()
