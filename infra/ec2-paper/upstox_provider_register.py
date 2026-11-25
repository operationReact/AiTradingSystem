#!/usr/bin/env python3
"""Owner-approved one-time Upstox *identity* registration, never a data-rights grant.

This operation may add only the existing well-identified upstream broker as
golden_data_provider(EXTERNAL). No rights receipts, data jobs, candles,
downloads, schedules, order permissions, or client credentials are created.
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

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
EXPECTED_RELEASE = "4084238ffac0206a8acfbdaa24f9ad06d0a67554"
LEGAL_NAME = "Upstox Securities Private Limited"
MARKER = "MINTTICK_UPSTOX_PROVIDER:"
REQUEST = "infra/ec2-paper/requests/upstox-register-provider.json"

# This single static CTE can INSERT exactly one provider identity. It grants no
# source rights and cannot create a backfill job. The final SELECT is limited
# to this exact provider record and aggregate metadata.
SQL = """
WITH registered AS (
    INSERT INTO golden_data_provider
      (provider_id, provider_code, legal_name, source_kind)
    VALUES (gen_random_uuid(), 'UPSTOX',
            'Upstox Securities Private Limited', 'EXTERNAL')
    ON CONFLICT (provider_code) DO NOTHING
    RETURNING provider_id, provider_code, legal_name, source_kind
), selected AS (
    SELECT provider_id, provider_code, legal_name, source_kind
      FROM registered
    UNION ALL
    SELECT provider_id, provider_code, legal_name, source_kind
      FROM golden_data_provider
     WHERE provider_code = 'UPSTOX'
)
SELECT json_build_object(
    'providerCode', p.provider_code, 'legalName', p.legal_name,
    'sourceKind', p.source_kind,
    'rightsRows',
       (SELECT count(*) FROM golden_source_rights_receipt r
         WHERE r.provider_id = p.provider_id),
    'jobs',
       (SELECT count(*) FROM golden_dataset_backfill_job j
         WHERE j.provider_id = p.provider_id)
)::text FROM selected p LIMIT 1;
"""

def expected_request() -> dict:
    return {
        "kind": "minttick-upstox-external-provider-identity-v1",
        "operation": "register-upstox-metadata-only-no-rights",
        "environment": "PAPER",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "backend_release": EXPECTED_RELEASE,
        "provider_code": "UPSTOX",
        "legal_name": LEGAL_NAME,
        "authorization": "OWNER_REQUESTED_UPSTOX_METADATA_REGISTRATION_2026_11_14",
    }


def sanitize(record: object) -> dict:
    if not isinstance(record, dict) or set(record) != {
        "providerCode", "legalName", "sourceKind", "rightsRows", "jobs"
    }:
        raise ValueError("UPSTOX_PROVIDER_CONTRACT_INVALID")
    if (record["providerCode"] != "UPSTOX"
            or record["legalName"] != LEGAL_NAME
            or record["sourceKind"] != "EXTERNAL"):
        raise ValueError("UPSTOX_PROVIDER_IDENTITY_CONFLICT")
    for key in ("rightsRows", "jobs"):
        if type(record[key]) is not int or not 0 <= record[key] <= 100_000_000:
            raise ValueError("UPSTOX_PROVIDER_COUNTS_INVALID")
    # The registration does not claim these rows are source-rights approvals;
    # it must NEVER create rights or jobs, and the observed counts may reflect
    # independently recorded historical rows on an already registered source.
    return dict(record)


def extract_receipt(raw: str) -> dict:
    if not isinstance(raw, str) or len(raw) > 10_000:
        raise ValueError("UPSTOX_PROVIDER_OUTPUT_INVALID")
    lines = re.findall(r"^MINTTICK_UPSTOX_PROVIDER:(\{[^\n]{1,500}\})$",
                       raw, re.MULTILINE)
    if len(lines) != 1:
        raise ValueError("UPSTOX_PROVIDER_RECEIPT_MISSING")
    return sanitize(json.loads(lines[0]))


def host() -> None:
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        raise SystemExit("UPSTOX_PROVIDER_HOST_INVALID")
    root = Path("/opt/aitradingsystem-paper")
    env = root / ".env"
    runtime = root / "runtime.env"
    if any(path.is_symlink() or not path.is_file()
           for path in (env, runtime)):
        raise SystemExit("UPSTOX_PROVIDER_RUNTIME_INVALID")
    if any(path.stat().st_mode & 0o077 for path in (env, runtime)):
        raise SystemExit("UPSTOX_PROVIDER_PERMISSIONS_INVALID")
    release = ""
    for line in env.read_text().splitlines():
        if line.startswith("RELEASE_SHA="):
            release = line.split("=", 1)[1]
    if release != EXPECTED_RELEASE:
        raise SystemExit("UPSTOX_PROVIDER_RELEASE_MISMATCH")
    try:
        result = subprocess.run(
            ["docker", "exec", "aitradingsystem-paper-postgres-1",
             "sh", "-c",
             'PGPASSWORD="$POSTGRES_PASSWORD" exec psql '
             '--no-psqlrc --quiet --tuples-only --no-align '
             '--set=ON_ERROR_STOP=on -U aitrading -d aitrading -c "$1"',
             "minttick-register-upstox-metadata-only", SQL],
            capture_output=True, text=True, timeout=15, check=False)
        if result.returncode != 0 or len(result.stdout) > 1200:
            raise ValueError("UPSTOX_PROVIDER_DB_FAILED")
        verified = sanitize(json.loads(result.stdout.strip()))
        print(MARKER + json.dumps(verified, separators=(",", ":"),
                                  sort_keys=True), flush=True)
    except (OSError, subprocess.TimeoutExpired, ValueError, TypeError):
        raise SystemExit("UPSTOX_PROVIDER_DB_FAILED")


def runner() -> None:
    path = Path(REQUEST)
    if (not path.is_file() or path.is_symlink()
            or path.stat().st_size > 2048
            or json.loads(path.read_text()) != expected_request()):
        raise SystemExit("UPSTOX_PROVIDER_REQUEST_UNAPPROVED")
    sys.path.insert(0, "infra/ec2-paper")
    import verify_ssm_release
    verify_ssm_release.verify_aws()
    payload = base64.b64encode(
        gzip.compress(Path(__file__).read_bytes(), compresslevel=9, mtime=0)).decode()
    if len(payload) > 12_000 or not re.fullmatch("[A-Za-z0-9+/=]+", payload):
        raise SystemExit("UPSTOX_PROVIDER_PAYLOAD_OVERSIZED")
    command = f"set -eu\nprintf '%s' '{payload}' | base64 -d | gzip -d | python3 - host\n"
    def aws(*args: str) -> dict:
        proc = subprocess.run(
            ["aws", *args, "--region", REGION, "--output", "json",
             "--no-cli-pager"],
            capture_output=True, text=True, timeout=55, check=False)
        if proc.returncode:
            raise SystemExit("UPSTOX_PROVIDER_SSM_UNAVAILABLE")
        return json.loads(proc.stdout)
    response = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
                   "--instance-ids", INSTANCE, "--timeout-seconds", "180",
                   "--comment", "Owner-reviewed Upstox EXTERNAL identity only, no rights",
                   "--parameters", json.dumps({"commands": [command]}))
    cid = (response.get("Command") or {}).get("CommandId")
    if not isinstance(cid, str) or not re.fullmatch("[0-9a-f-]{36}", cid):
        raise SystemExit("UPSTOX_PROVIDER_SSM_COMMAND_INVALID")
    for attempt in range(36):
        time.sleep(4)
        try:
            report = aws("ssm", "get-command-invocation", "--command-id", cid,
                         "--instance-id", INSTANCE)
        except SystemExit:
            if attempt < 4:
                continue
            raise
        state = report.get("Status")
        if state == "Success":
            receipt = extract_receipt(report.get("StandardOutputContent") or "")
            print("UPSTOX PROVIDER METADATA REGISTERED, verified readback: "
                  "provider=UPSTOX source_kind=EXTERNAL "
                  f"existing_rights_metadata_rows={receipt['rightsRows']} "
                  f"existing_jobs={receipt['jobs']} "
                  "permission_granted=false ingestion_started=false", flush=True)
            return
        if state in {"Failed", "Cancelled", "TimedOut", "Cancelling"}:
            raise SystemExit("UPSTOX_PROVIDER_REGISTRATION_FAILED")
        if state not in {"Pending", "InProgress", "Delayed"}:
            raise SystemExit("UPSTOX_PROVIDER_SSM_STATE_INVALID")
    raise SystemExit("UPSTOX_PROVIDER_SSM_TIMEOUT")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in {"host", "runner"}:
        raise SystemExit("UPSTOX_PROVIDER_INVALID_MODE")
    if sys.argv[1] == "host":
        host()
    else:
        runner()
