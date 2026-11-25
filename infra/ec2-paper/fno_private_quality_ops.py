#!/usr/bin/env python3
"""Controlled Phase 2 read-only F&O audit runner.

Only reviewed source code at the exact Git commit, fetched via TLS and
SHA-256 verified on the existing host. Private market data never leaves EC2.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time

from fno_private_bulk_ops import _aws, INSTANCE
from verify_ssm_release import verify_aws

REQUEST_PATH = Path("infra/ec2-paper/requests/fno-private-quality-audit.json")
SCRIPT = Path("infra/ec2-paper/fno_private_history_audit.py")
MARKER = "MINTTICK_FNO_AUDIT_V1:"
ALLOWED_FINDINGS = {
    "invalidJobMetadata", "duplicateJobs", "duplicateJobHashes",
    "doneWithZeroWritten", "nonDoneWithWrittenRows", "duplicateReceipts",
    "orphanReceipts", "receiptCheckpointMismatch", "doneMissingReceipt",
    "duplicateCandles", "orphanCandleRows", "candleCheckpointMismatch",
    "outsideBatchWindow", "invalidCandleTimestamp",
    "invalidOrMissingOHLC", "inconsistentOHLC",
    "invalidOrMissingVolume", "invalidOrMissingOI",
    "receiptVsCachedRowsMismatch", "duplicateDailyDates", "unexpectedPilotWindow",
}

def expected_request():
    return {
        "kind": "minttick-owner-private-fno-quality-audit-v1",
        "audit_revision": 4,
        "operation": "read-only-historical-candle-and-checkpoint-audit",
        "account": "098859530205",
        "region": "ap-south-1",
        "instance_id": "i-09c01b470fb39b85a",
        "scope": "12-completed-nifty-expiry-months-daily",
        "authorization": "OWNER_APPROVED_FNO_PHASE2_AUDIT_2026_11_21",
        "no_provider_requests": True,
        "no_orders": True,
        "no_redistribution": True,
    }

def build_host_command(commit, source_digest):
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(r"[0-9a-f]{64}", source_digest):
        raise ValueError("UNPINNED_AUDIT_SOURCE")
    url = ("https://raw.githubusercontent.com/operationReact/AiTradingSystem/"
           + commit + "/infra/ec2-paper/fno_private_history_audit.py")
    # Executable is readable source, never compressed or hidden. No DB writes,
    # broker calls, account changes, or persistence of the temporary script.
    return (
        "set -eu\numask 077\n"
        "tmp=$(mktemp /tmp/minttick-fno-audit.XXXXXX)\n"
        "trap 'rm -f \"$tmp\"' EXIT\n"
        "curl --fail --silent --show-error --max-time 25 --proto '=https' "
        "--tlsv1.2 -o \"$tmp\" '" + url + "'\n"
        "test \"$(wc -c < \"$tmp\")\" -lt 24000\n"
        "printf '%s  %s\\n' '" + source_digest + "' \"$tmp\" | sha256sum -c - >/dev/null\n"
        "PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 \"$tmp\"\n"
    )

def parse_receipt(output):
    if not isinstance(output, str) or len(output) > 6500:
        raise ValueError("INVALID_AUDIT_RECEIPT")
    lines = [line[len(MARKER):] for line in output.splitlines()
             if line.startswith(MARKER)]
    if len(lines) != 1 or len(lines[0]) > 5000:
        raise ValueError("INVALID_AUDIT_RECEIPT")
    report = json.loads(lines[0])
    if report.get("audit") != "OWNER_PRIVATE_NIFTY_12M_DAILY_V1" or report.get("result") not in ("PASS","REVIEW"):
        raise ValueError("INVALID_AUDIT_RECEIPT")
    jobs, candles, cov, sql = (report[k] for k in ("jobs","candles","coverage","sqlite"))
    metrics = [
        jobs["total"], jobs["completed"], candles["stored"],
        candles["receipts"], candles["zeroVolumeValid"], candles["zeroOIValid"],
        cov["expiryMonths"], cov["uniqueContracts"], report["findingCount"],
    ]
    if any(type(n) is not int or not 0 <= n <= 1000000 for n in metrics):
        raise ValueError("INVALID_AUDIT_METRIC")
    if any(type(sql[k]) is not bool for k in ("journalQuickCheck", "cacheQuickCheck")):
        raise ValueError("INVALID_SQLITE_RECEIPT")
    states = jobs["states"]
    if (set(states) != {"PENDING","ACTIVE","RETRY","DONE","EMPTY","FAILED","BLOCKED"}
            or any(type(v) is not int or not 0 <= v <= 20000 for v in states.values())):
        raise ValueError("INVALID_CHECKPOINT_RECEIPT")
    findings = report["findings"]
    if (not isinstance(findings, dict) or not set(findings) <= ALLOWED_FINDINGS
            or any(type(n) is not int or not 0 <= n <= 1000000 for n in findings.values())):
        raise ValueError("INVALID_FINDINGS_RECEIPT")
    if not isinstance(cov["months"], list) or len(cov["months"]) > 12:
        raise ValueError("INVALID_COVERAGE_RECEIPT")
    for row in cov["months"]:
        if not re.fullmatch(r"20\d{2}-(0[1-9]|1[0-2])", row["month"]):
            raise ValueError("INVALID_MONTH_RECEIPT")
        if any(type(row[k]) is not int or not 0 <= row[k] <= 1000000
               for k in ("jobs","contracts","candles","duplicateDailyDates",
                         "contractsOver21Candles","maxCandlesPerContract",
                         "duplicateIdenticalPayload","duplicateConflictingPayload",
                         "duplicateSameInstant")):
            raise ValueError("INVALID_MONTH_RECEIPT")
        patterns = row.get("timestampPatterns")
        if not isinstance(patterns,list) or len(patterns)>12:
            raise ValueError("INVALID_TIME_PATTERN_RECEIPT")
        for entry in patterns:
            if (not isinstance(entry,dict)
                    or not re.fullmatch(r"[0-2][0-9]:[0-5][0-9][+-][0-2][0-9][0-5][0-9]", entry.get("time",""))
                    or type(entry.get("count")) is not int
                    or not 0 <= entry["count"] <= 1000000):
                raise ValueError("INVALID_TIME_PATTERN_RECEIPT")
    isolation=report.get("quarantine")
    if not isinstance(isolation,dict):
        raise ValueError("INVALID_QUARANTINE_REPORT")
    fields=("excludedExpiryMonth","eligibleExpiryMonths","eligibleContracts",
            "eligibleCandles","excludedContracts","excludedCandles","status","boundary")
    if (set(isolation)!=set(fields)
            or isolation["status"] not in ("ISOLATED","BLOCKED")
            or isolation["excludedExpiryMonth"]!="2025-12"
            or isolation["boundary"]!="STRUCTURAL_RESEARCH_ONLY_NO_BACKTEST_APPROVAL"
            or any(type(isolation[name]) is not int or not 0<=isolation[name]<=1000000
                   for name in ("eligibleExpiryMonths","eligibleContracts",
                                "eligibleCandles","excludedContracts","excludedCandles"))):
        raise ValueError("INVALID_QUARANTINE_REPORT")
    return report

def print_safe_report(report):
    j,c,cov,q=(report[k] for k in ("jobs","candles","coverage","sqlite"))
    print(f"FNO AUDIT RESULT: {report['result']}; "
          f"journalOK={q['journalQuickCheck']}; cacheOK={q['cacheQuickCheck']}; "
          f"jobs={j['completed']}/{j['total']}; storedCandles={c['stored']}; "
          f"receipts={c['receipts']}; expiryMonths={cov['expiryMonths']}; "
          f"contracts={cov['uniqueContracts']}; "
          f"zeroVolumeValid={c['zeroVolumeValid']}; zeroOIValid={c['zeroOIValid']}; "
          f"criticalFindings={report['findingCount']}", flush=True)
    for month in cov["months"]:
        print(f"FNO AUDIT MONTH: month={month['month']}; jobs={month['jobs']}; "
              f"contracts={month['contracts']}; candles={month['candles']}; "
              f"dailyDuplicates={month['duplicateDailyDates']}; "
              f"contractsOver21Candles={month['contractsOver21Candles']}; "
              f"maxCandlesPerContract={month['maxCandlesPerContract']}; "
              f"identicalDailyDuplicates={month['duplicateIdenticalPayload']}; "
              f"conflictingDailyDuplicates={month['duplicateConflictingPayload']}; "
              f"duplicateSameInstant={month['duplicateSameInstant']}", flush=True)
        if month['duplicateDailyDates']:
            for pattern in month["timestampPatterns"]:
                print(f"FNO AUDIT TIME: month={month['month']}; "
                      f"clockOffset={pattern['time']}; candles={pattern['count']}", flush=True)
    for name,value in sorted(report["findings"].items()):
        print(f"FNO AUDIT FINDING: {name}={value}", flush=True)
    q=report["quarantine"]
    print(f"FNO AUDIT QUARANTINE: status={q['status']}; "
          f"excludedMonth={q['excludedExpiryMonth']}; "
          f"eligibleMonths={q['eligibleExpiryMonths']}; "
          f"eligibleContracts={q['eligibleContracts']}; "
          f"eligibleCandles={q['eligibleCandles']}; "
          f"excludedContracts={q['excludedContracts']}; "
          f"excludedCandles={q['excludedCandles']}; "
          "researchOnly=true; backtestingApproved=false",flush=True)
    print("FNO AUDIT LIMITS: no contract type mapping; no provider source catalogue; "
          "no exchange holiday calendar; seven-day private cache", flush=True)

def runner():
    if (not REQUEST_PATH.is_file() or REQUEST_PATH.is_symlink()
            or REQUEST_PATH.stat().st_size > 1024
            or json.loads(REQUEST_PATH.read_text()) != expected_request()):
        raise SystemExit("FNO_AUDIT_APPROVAL_REJECTED")
    verify_aws()
    src=SCRIPT.read_bytes()
    if not 5000 < len(src) < 24000:
        raise SystemExit("FNO_AUDIT_SOURCE_INVALID")
    digest=hashlib.sha256(src).hexdigest()
    command=build_host_command(os.environ.get("GITHUB_SHA",""),digest)
    response=_aws("ssm","send-command","--document-name","AWS-RunShellScript",
                  "--instance-ids",INSTANCE,"--timeout-seconds","180",
                  "--comment","MintTick owner private FNO Phase 2 read-only quality audit",
                  "--parameters",json.dumps({"commands":[command]}))
    command_id=(response.get("Command") or {}).get("CommandId")
    if not isinstance(command_id,str) or not re.fullmatch(r"[0-9a-f-]{36}",command_id):
        raise SystemExit("FNO_AUDIT_SSM_COMMAND_INVALID")
    for _ in range(36):
        time.sleep(5)
        status=_aws("ssm","get-command-invocation","--command-id",command_id,
                    "--instance-id",INSTANCE)
        state=status.get("Status")
        if state in ("Success","Failed","Cancelled","TimedOut"):
            if state!="Success":
                raise SystemExit("FNO_AUDIT_SSM_FAILED")
            try:
                report=parse_receipt(status.get("StandardOutputContent") or "")
            except (ValueError,TypeError,KeyError):
                raise SystemExit("FNO_AUDIT_INVALID_SAFE_REPORT") from None
            print_safe_report(report)
            if report["result"]!="PASS":
                if report["quarantine"]["status"]!="ISOLATED":
                    raise SystemExit("FNO_AUDIT_REVIEW_REQUIRED")
                print("FNO AUDIT QUALITY GATE: raw=REVIEW; elevenMonthResearchCohort=ISOLATED; "
                      "decemberBacktestingBlocked=true",flush=True)
            return
        if state not in ("Pending","InProgress","Delayed"):
            raise SystemExit("FNO_AUDIT_SSM_INVALID_STATUS")
    raise SystemExit("FNO_AUDIT_SSM_TIMEOUT")

if __name__=="__main__":
    if len(sys.argv)!=2 or sys.argv[1]!="runner":
        raise SystemExit("FNO_AUDIT_INVALID_COMMAND")
    runner()
