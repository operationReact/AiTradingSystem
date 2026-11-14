#!/usr/bin/env python3
"""Single-use, source-scoped, metadata-only Upstox PostgreSQL audit via reviewed EC2 SSM.

No source API calls, broker credentials, candle/quote/position payloads, database
writes, entitlement grants, or PAPER/LIVE orders. The query counts persisted
checkpoint *metadata*, not independent object readback or full NSE coverage.
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
DEPLOYED_SHA = "4084238ffac0206a8acfbdaa24f9ad06d0a67554"
REQUEST_PATH = "infra/ec2-paper/requests/upstox-persisted-audit.json"
MARKER = "MINTTICK_UPSTOX_PERSISTED:"
COUNTS = ("jobs", "chunks", "archivedVerifiedChunks", "retentionReceiptChunks",
          "retryChunks", "failedChunks", "rawRetentionRightsMetadata")
MAX_COUNT = 100_000_000

# One static, read-only SQL expression; no user-provided identifiers, dates,
# data ranges or clause interpolation.
SQL = """
WITH source AS (
  SELECT provider_id, source_kind
    FROM golden_data_provider WHERE provider_code = 'UPSTOX'
), jobs AS (
  SELECT j.job_id, j.provider_id, j.source_domain
    FROM golden_dataset_backfill_job j
    JOIN source p ON p.provider_id = j.provider_id
), counted AS (
  SELECT c.chunk_id, c.status,
         EXISTS (
           SELECT 1 FROM golden_source_rights_receipt r
            WHERE r.provider_id = j.provider_id
              AND r.source_domain = j.source_domain
              AND r.permitted_use = 'RAW_RETENTION'
              AND r.receipt_status = 'VERIFIED'
              AND r.verified_at IS NOT NULL
              AND r.verified_at <= now()
              AND r.captured_at <= now()
              AND r.revoked_at IS NULL
              AND r.valid_from <= now()
              AND (r.valid_to IS NULL OR r.valid_to > now())
         ) AS rights_metadata
    FROM golden_dataset_backfill_chunk c JOIN jobs j ON j.job_id = c.job_id
)
SELECT json_build_object(
  'providerState', coalesce((SELECT source_kind FROM source), 'MISSING'),
  'jobs', (SELECT count(*) FROM jobs),
  'chunks', (SELECT count(*) FROM counted),
  'archivedVerifiedChunks',
    (SELECT count(*) FROM counted WHERE status = 'VERIFIED'),
  'retentionReceiptChunks',
    (SELECT count(*) FROM counted WHERE status = 'VERIFIED' AND rights_metadata),
  'retryChunks', (SELECT count(*) FROM counted WHERE status = 'RETRY'),
  'failedChunks', (SELECT count(*) FROM counted WHERE status = 'FAILED'),
  'rawRetentionRightsMetadata',
    (SELECT count(*) FROM golden_source_rights_receipt r JOIN source p
      ON r.provider_id = p.provider_id WHERE r.permitted_use = 'RAW_RETENTION'
        AND r.receipt_status = 'VERIFIED' AND r.verified_at IS NOT NULL
        AND r.verified_at <= now() AND r.captured_at <= now()
        AND r.revoked_at IS NULL AND r.valid_from <= now()
        AND (r.valid_to IS NULL OR r.valid_to > now()))
)::text;
"""

def expected_request() -> dict:
    return {
        "kind": "minttick-owner-upstox-persisted-count-audit-v1",
        "operation": "audit-upstox-checkpoints-metadata-only",
        "environment": "PAPER",
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "backend_release": DEPLOYED_SHA,
        "authorization": "OWNER_APPROVED_READ_ONLY_UPSTOX_RECEIPT_AUDIT_2026_11_14",
    }


def safe_counts(value: object) -> dict:
    if not isinstance(value, dict) or set(value) != set(COUNTS) | {"providerState"}:
        raise ValueError("UPSTOX_AUDIT_INVALID_CONTRACT")
    provider = value["providerState"]
    if provider not in {"EXTERNAL", "SYNTHETIC_TEST", "MISSING"}:
        raise ValueError("UPSTOX_AUDIT_INVALID_PROVIDER")
    if any(type(value[key]) is not int or not 0 <= value[key] <= MAX_COUNT
           for key in COUNTS):
        raise ValueError("UPSTOX_AUDIT_INVALID_COUNTS")
    if (value["retentionReceiptChunks"] > value["archivedVerifiedChunks"]
            or value["archivedVerifiedChunks"] > value["chunks"]
            or value["retryChunks"] > value["chunks"]
            or value["failedChunks"] > value["chunks"]):
        raise ValueError("UPSTOX_AUDIT_INCONSISTENT_COUNTS")
    if provider == "MISSING" and any(value[key] != 0 for key in COUNTS):
        raise ValueError("UPSTOX_AUDIT_MISSING_PROVIDER_WITH_DATA")
    # A missing provider is unknown, not proof of 0% progress. Synthetic-test
    # sources are not eligible production evidence even if rows are present.
    return {key: value[key] for key in ["providerState", *COUNTS]}


def sanitized_receipt(raw: str) -> dict:
    if not isinstance(raw, str) or len(raw) > 16_000:
        raise ValueError("UPSTOX_AUDIT_OUTPUT_REJECTED")
    matches = re.findall(r"^MINTTICK_UPSTOX_PERSISTED:(\{[^\n]{1,600}\})$",
                         raw, flags=re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("UPSTOX_AUDIT_RECEIPT_MISSING_OR_DUPLICATE")
    return safe_counts(json.loads(matches[0]))


def host() -> None:
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        raise SystemExit("UPSTOX_AUDIT_HOST_IDENTITY_INVALID")
    root = Path("/opt/aitradingsystem-paper")
    compose_env = root / ".env"
    runtime = root / "runtime.env"
    for path in (root, compose_env, runtime):
        if path.is_symlink() or not path.exists():
            raise SystemExit("UPSTOX_AUDIT_RUNTIME_IDENTITY_INVALID")
    if (compose_env.stat().st_mode & 0o077) or (runtime.stat().st_mode & 0o077):
        raise SystemExit("UPSTOX_AUDIT_RUNTIME_PERMISSIONS_INVALID")
    # Only inspect the exact nonsecret deployed SHA; never print env contents.
    release = ""
    for line in compose_env.read_text().splitlines():
        if line.startswith("RELEASE_SHA="):
            release = line.split("=", 1)[1]
    if release != DEPLOYED_SHA:
        raise SystemExit("UPSTOX_AUDIT_DEPLOYMENT_SHA_MISMATCH")
    try:
        p = subprocess.run(
            ["docker", "exec", "aitradingsystem-paper-postgres-1", "psql",
             "--no-psqlrc", "--quiet", "--tuples-only", "--no-align",
             "--set=ON_ERROR_STOP=on", "-U", "aitrading", "-d", "aitrading",
             "-c", SQL],
            capture_output=True, text=True, timeout=15, check=False)
        if p.returncode != 0 or len(p.stdout) > 1200:
            raise ValueError("UPSTOX_AUDIT_DATABASE_UNAVAILABLE")
        parsed = safe_counts(json.loads(p.stdout.strip()))
        # Never print raw psql stdout/stderr or errors.
        print(MARKER + json.dumps(parsed, sort_keys=True, separators=(",", ":")),
              flush=True)
    except (OSError, subprocess.TimeoutExpired, ValueError, TypeError):
        raise SystemExit("UPSTOX_AUDIT_DATABASE_UNAVAILABLE")


def runner() -> None:
    request = Path(REQUEST_PATH)
    if (request.is_symlink() or not request.is_file()
            or request.stat().st_size > 2048
            or json.loads(request.read_text()) != expected_request()):
        raise SystemExit("UPSTOX_AUDIT_REQUEST_NOT_APPROVED")
    sys.path.insert(0, "infra/ec2-paper")
    import verify_ssm_release
    verify_ssm_release.verify_aws()
    payload = base64.b64encode(
        gzip.compress(Path(__file__).read_bytes(), compresslevel=9, mtime=0)).decode()
    if len(payload) > 12_000 or not re.fullmatch(r"[A-Za-z0-9+/=]+", payload):
        raise SystemExit("UPSTOX_AUDIT_OVERSIZED_PAYLOAD")
    shell = f"set -eu\nprintf '%s' '{payload}' | base64 -d | gzip -d | python3 - host\n"
    def aws(*args: str) -> dict:
        response = subprocess.run(
            ["aws", *args, "--region", REGION, "--output", "json",
             "--no-cli-pager"],
            capture_output=True, text=True, timeout=55, check=False)
        if response.returncode != 0:
            raise SystemExit("UPSTOX_AUDIT_SSM_UNAVAILABLE")
        return json.loads(response.stdout)
    result = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
                 "--instance-ids", INSTANCE, "--timeout-seconds", "180",
                 "--comment", "MintTick approved private Upstox checkpoint metadata audit",
                 "--parameters", json.dumps({"commands": [shell]}))
    command_id = (result.get("Command") or {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", command_id):
        raise SystemExit("UPSTOX_AUDIT_SSM_COMMAND_INVALID")
    for i in range(38):
        time.sleep(4)
        try:
            result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                         "--instance-id", INSTANCE)
        except SystemExit:
            if i < 4:
                continue
            raise
        status = result.get("Status")
        if status == "Success":
            counts = sanitized_receipt(result.get("StandardOutputContent") or "")
            if counts["providerState"] == "EXTERNAL":
                print("UPSTOX PERSISTED METADATA (not market-data or legal acceptance): "
                      + " ".join(f"{name}={counts[name]}" for name in
                                 ("jobs", "chunks", "archivedVerifiedChunks",
                                  "retentionReceiptChunks", "retryChunks",
                                  "failedChunks", "rawRetentionRightsMetadata")),
                      flush=True)
            else:
                print("UPSTOX PERSISTED METADATA: provider="
                      + counts["providerState"] + " counts=UNKNOWN", flush=True)
            return
        if status in {"Failed", "Cancelled", "TimedOut", "Cancelling"}:
            # This failure code is fixed locally; never reflect remote output.
            raise SystemExit("UPSTOX_AUDIT_HOST_CHECK_FAILED")
        if status not in {"Pending", "InProgress", "Delayed"}:
            raise SystemExit("UPSTOX_AUDIT_SSM_UNEXPECTED_STATE")
    raise SystemExit("UPSTOX_AUDIT_TIMEOUT")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in {"host", "runner"}:
        raise SystemExit("UPSTOX_AUDIT_INVALID_MODE")
    if sys.argv[1] == "host":
        host()
    else:
        runner()
