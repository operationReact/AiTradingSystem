#!/usr/bin/env python3
"""Read-only STR-176 actual V102 PostgreSQL source evidence on exact PAPER EC2.

Only aggregate 5-count receipts may escape the owner-private instance.
This audits source prerequisites, never market backtest/OOS certification.
No provider calls, broker orders, source material export, rights or DB writes.
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

ROOT = Path("/opt/aitradingsystem-paper")
INSTANCE = "i-09c01b470fb39b85a"
ACCOUNT = "098859530205"
REGION = "ap-south-1"
REQUEST = Path("infra/ec2-paper/requests/str176-v102-readonly.json")
MARKER = "MINTTICK_STR176_V102_READONLY:"
EXACT = {
    "kind": "minttick-str176-v102-source-eligibility-readonly-v1",
    "account": ACCOUNT,
    "region": REGION,
    "instance_id": INSTANCE,
    "no_data_export": True,
    "no_order_calls": True,
    "no_db_or_rights_writes": True,
}
# 100% static source-eligibility SQL. The transaction rejects any writes.
# Day labels and provider download times are never reported as original first-known.
SQL = """
BEGIN READ ONLY;
SET LOCAL TIME ZONE 'Asia/Kolkata';
WITH eligible AS (
    SELECT s.instrument_key, s.provider_code, s.session_date
    FROM fno_verified_completed_ohlcv_oi_session s
    JOIN golden_data_provider p ON p.provider_code = s.provider_code
        AND p.source_kind = 'EXTERNAL'
    JOIN golden_source_rights_receipt h
        ON h.receipt_id = s.ohlcv_rights_receipt_id
        AND h.provider_id = p.provider_id
        AND h.source_domain = 'nse_fo_completed_ohlcv'
        AND h.permitted_use = 'MODEL_INFERENCE'
        AND h.receipt_status = 'VERIFIED'
    JOIN golden_source_rights_receipt oi
        ON oi.receipt_id = s.oi_rights_receipt_id
        AND oi.provider_id = p.provider_id
        AND oi.source_domain = 'nse_fo_final_oi'
        AND oi.permitted_use = 'MODEL_INFERENCE'
        AND oi.receipt_status = 'VERIFIED'
    JOIN golden_historical_exchange_session_evidence cal
        ON cal.evidence_id = s.calendar_evidence_id
        AND cal.provider_id = p.provider_id
        AND cal.source_domain = 'nse_fo_trading_calendar'
        AND cal.exchange = 'NSE'
        AND cal.session_date = s.session_date
        AND cal.session_status = 'TRADING'
    WHERE s.session_date BETWEEN CURRENT_DATE - 26 AND CURRENT_DATE - 1
      AND s.expiry BETWEEN CURRENT_DATE + 7 AND CURRENT_DATE + 120
      AND s.retain_until >= CURRENT_DATE
      AND s.captured_at <= NOW()
      AND s.ohlcv_first_available_at >= cal.session_close_at
      AND s.oi_first_available_at >= cal.session_close_at
      AND s.ohlcv_first_available_at <= NOW()
      AND s.oi_first_available_at <= NOW()
      AND cal.first_available_at <= NOW()
      AND cal.verified_at <= NOW() AND cal.captured_at <= NOW()
      AND h.valid_from <= NOW()
      AND (h.valid_to IS NULL OR h.valid_to > NOW())
      AND h.verified_at <= NOW() AND h.captured_at <= NOW()
      AND oi.valid_from <= NOW()
      AND (oi.valid_to IS NULL OR oi.valid_to > NOW())
      AND oi.verified_at <= NOW() AND oi.captured_at <= NOW()
), current_rights AS (
    SELECT r.provider_id, r.source_domain, r.permitted_use
    FROM golden_source_rights_receipt r
    WHERE r.receipt_status = 'VERIFIED'
      AND r.verified_at IS NOT NULL AND r.verified_by IS NOT NULL
      AND r.revoked_at IS NULL AND r.captured_at <= NOW()
      AND r.verified_at <= NOW() AND r.valid_from <= NOW()
      AND (r.valid_to IS NULL OR r.valid_to > NOW())
      AND NOT EXISTS (
          SELECT 1 FROM golden_source_rights_receipt revoked
          WHERE revoked.provider_id = r.provider_id
            AND revoked.source_domain = r.source_domain
            AND revoked.permitted_use = r.permitted_use
            AND revoked.receipt_status = 'REVOKED'
            AND revoked.revoked_at <= NOW() AND revoked.captured_at <= NOW()
      )
), required_rights(source_domain,permitted_use) AS (
    VALUES
      ('nse_fo_completed_ohlcv','RAW_RETENTION'),
      ('nse_fo_completed_ohlcv','MODEL_INFERENCE'),
      ('nse_fo_final_oi','RAW_RETENTION'),
      ('nse_fo_final_oi','MODEL_INFERENCE'),
      ('nse_fo_trading_calendar','RAW_RETENTION'),
      ('nse_fo_trading_calendar','MODEL_INFERENCE')
), fully_authorized_provider AS (
    SELECT p.provider_id
    FROM golden_data_provider p
    WHERE p.source_kind = 'EXTERNAL'
      AND p.provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')
      AND NOT EXISTS (
          SELECT 1 FROM required_rights needed
          WHERE NOT EXISTS (
              SELECT 1 FROM current_rights r
              WHERE r.provider_id = p.provider_id
                AND r.source_domain = needed.source_domain
                AND r.permitted_use = needed.permitted_use
          )
      )
)
SELECT (SELECT COUNT(*) FROM fno_verified_completed_ohlcv_oi_session),
       (SELECT COUNT(*) FROM eligible),
       (SELECT COUNT(*) FROM
           (SELECT provider_code, instrument_key FROM eligible
            GROUP BY provider_code, instrument_key
            HAVING COUNT(DISTINCT session_date) >= 7) candidates),
       (SELECT COUNT(*) FROM fully_authorized_provider),
       (SELECT COUNT(*) FROM golden_historical_exchange_session_evidence cal
        JOIN golden_data_provider p ON p.provider_id=cal.provider_id
        WHERE p.provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')
          AND p.source_kind='EXTERNAL'
          AND cal.source_domain='nse_fo_trading_calendar'
          AND cal.exchange='NSE' AND cal.session_status='TRADING'
          AND cal.session_date BETWEEN CURRENT_DATE - 26 AND CURRENT_DATE - 1
          AND cal.first_available_at <= NOW()
          AND cal.verified_at <= NOW() AND cal.captured_at <= NOW());
COMMIT;
"""

def expected_receipt(state: str, counts=None) -> dict:
    if state not in ("ELIGIBILITY_MEASURED_NOT_CERTIFIED", "SOURCE_READ_UNAVAILABLE"):
        raise ValueError("UNKNOWN_SOURCE_STATE")
    if counts is not None:
        if (not isinstance(counts, tuple) or len(counts) != 5
                or any(type(n) is not int or n < 0 or n > 1000000000
                       for n in counts)
                or counts[1] > counts[0] or counts[2] * 7 > counts[1]
                or counts[3] > 2):
            raise ValueError("INVALID_SOURCE_COUNTS")
    if (state == "ELIGIBILITY_MEASURED_NOT_CERTIFIED") != (counts is not None):
        raise ValueError("SOURCE_RECEIPT_INCOMPLETE")
    return {
        "state": state,
        "v102Rows": None if counts is None else counts[0],
        "rightsJoinedRows": None if counts is None else counts[1],
        "possibleSevenSessionWindows": None if counts is None else counts[2],
        "fullyAuthorizedProviders": None if counts is None else counts[3],
        "verifiedCalendarRows": None if counts is None else counts[4],
        "independentHistoricalCertified": 0,
        "brokerOrders": False,
        "writes": False,
    }

def query_counts(run=subprocess.run, root=ROOT):
    root=Path(root)
    if (not root.is_absolute() or root.is_symlink()
            or not root.is_dir()
            or not (root / ".env").is_file()
            or (root / ".env").is_symlink()
            or (root / ".env").stat().st_mode & 0o077
            or not (root / "paper-compose.yml").is_file()
            or (root / "paper-compose.yml").is_symlink()):
        raise ValueError("EXACT_PRIVATE_RUNTIME_MISSING")
    args=["docker", "compose", "--env-file", str(root / ".env"),
          "-f", str(root / "paper-compose.yml"),
          "exec", "-T", "postgres", "psql", "-X", "-q", "-t", "-A",
          "-F", ":", "-v", "ON_ERROR_STOP=1", "-U", "aitrading",
          "-d", "aitrading", "-c", SQL]
    output=run(args, capture_output=True, text=True, timeout=30, check=False)
    if output.returncode != 0 or len(output.stdout) > 256:
        raise ValueError("READ_ONLY_SOURCE_QUERY_FAILED")
    lines=[s.strip() for s in output.stdout.splitlines() if s.strip()]
    if len(lines)!=1 or not re.fullmatch(r"[0-9]{1,10}(?::[0-9]{1,10}){4}",lines[0]):
        raise ValueError("SOURCE_COUNT_RECEIPT_INVALID")
    counts=tuple(int(x) for x in lines[0].split(":"))
    expected_receipt("ELIGIBILITY_MEASURED_NOT_CERTIFIED",counts)
    return counts

def host():
    if os.geteuid()!=0 or os.uname().machine!="aarch64":
        report=expected_receipt("SOURCE_READ_UNAVAILABLE")
    else:
        try:
            report=expected_receipt("ELIGIBILITY_MEASURED_NOT_CERTIFIED",query_counts())
        except (ValueError,OSError,subprocess.SubprocessError):
            report=expected_receipt("SOURCE_READ_UNAVAILABLE")
    print(MARKER+json.dumps(report,separators=(",",":"),sort_keys=True),flush=True)

def parse_host_receipt(output: str) -> dict:
    if not isinstance(output,str) or len(output)>2048:
        raise ValueError("UNBOUNDED_PRIVATE_RECEIPT")
    rows=[line[len(MARKER):] for line in output.splitlines()
          if line.startswith(MARKER)]
    if len(rows)!=1:
        raise ValueError("MISSING_SOURCE_RECEIPT")
    try:
        data=json.loads(rows[0])
        state=data["state"]
        counts= (data["v102Rows"],data["rightsJoinedRows"],
                 data["possibleSevenSessionWindows"],data["fullyAuthorizedProviders"],
                 data["verifiedCalendarRows"]) if state=="ELIGIBILITY_MEASURED_NOT_CERTIFIED" else None
        expected=expected_receipt(state,counts)
    except (ValueError,KeyError,TypeError):
        raise ValueError("SOURCE_RECEIPT_UNTRUSTED") from None
    if data!=expected:
        raise ValueError("SOURCE_RECEIPT_UNTRUSTED")
    return expected

def runner():
    if (REQUEST.is_symlink() or not REQUEST.is_file()
            or REQUEST.stat().st_size>=1024
            or json.loads(REQUEST.read_text())!=EXACT):
        raise ValueError("REQUEST_NOT_APPROVED")
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    verify_aws()
    payload=base64.b64encode(gzip.compress(Path(__file__).read_bytes(),mtime=0)).decode()
    if len(payload)>=20000 or not re.fullmatch(r"[A-Za-z0-9+/=]+",payload):
        raise ValueError("HOST_SCRIPT_UNBOUNDED")
    shell=("set -eu\nprintf '%s' '"+payload+
           "' | base64 -d | gzip -d | PYTHONDONTWRITEBYTECODE=1 python3 - host\n")
    sent=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
              "--instance-ids",INSTANCE,"--timeout-seconds","120",
              "--comment","STR176 V102 historical original source READ ONLY",
              "--parameters",json.dumps({"commands":[shell]}))
    cid=(sent.get("Command") or {}).get("CommandId")
    if not isinstance(cid,str) or not re.fullmatch(r"[0-9a-f-]{36}",cid):
        raise ValueError("INVALID_SSM_COMMAND")
    for _ in range(24):
        time.sleep(5)
        status=_aws("ssm","get-command-invocation",
                    "--command-id",cid,"--instance-id",INSTANCE)
        state=status.get("Status")
        if state=="Success":
            result=parse_host_receipt(status.get("StandardOutputContent",""))
            print("STR176_V102_HISTORICAL_AUDIT:state="+result["state"]
                  +";v102Rows="+str(result["v102Rows"])
                  +";rightsJoinedRows="+str(result["rightsJoinedRows"])
                  +";possibleSevenSessionWindows="+str(result["possibleSevenSessionWindows"])
                  +";fullyAuthorizedProviders="+str(result["fullyAuthorizedProviders"])
                  +";verifiedCalendarRows="+str(result["verifiedCalendarRows"])
                  +";independentHistoricalCertified=0/43;orders=false;writes=false")
            return
        if state in ("Failed","TimedOut","Cancelled"):
            raise ValueError("SSM_SOURCE_AUDIT_FAILED")
        if state not in ("Pending","InProgress","Delayed"):
            raise ValueError("SSM_SOURCE_STATUS_INVALID")
    raise ValueError("SSM_SOURCE_TIMEOUT")

if __name__=="__main__":
    try:
        if sys.argv[1:]==["host"]:
            host()
        elif sys.argv[1:]==["runner"]:
            runner()
        else:
            raise ValueError("INVALID_SOURCE_MODE")
    except (ValueError,OSError,subprocess.SubprocessError,KeyError):
        print("STR176_V102_HISTORICAL_AUDIT:BLOCKED_NO_VERIFIED_RECEIPT",
              file=sys.stderr)
        raise SystemExit(2)
