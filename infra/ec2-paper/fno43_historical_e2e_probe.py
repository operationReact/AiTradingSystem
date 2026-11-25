#!/usr/bin/env python3
"""Read-only, owner-private evidence inventory for STR-170..212.

No market payloads, instrument identifiers, broker data, credentials or rows
leave the host. A VALID 3,929-row daily cache is not strategy E2E: it lacks
original-first-known time, independently attested research rights, index/option
chains, L1 depth, IV/Greeks, evaluator replay and after-cost OOS proof.
This script cannot authorize a backtest, PAPER order or LIVE order.
"""
from __future__ import annotations

from collections import Counter
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
import base64
import gzip
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import subprocess
import sys
import time
from urllib.parse import quote
from zoneinfo import ZoneInfo

ROOT = Path("/opt/aitradingsystem-paper/fno-history")
FILE = "personal-canonical-daily.sqlite"
VERSION = "minttick.fno.personal.canonical.v1"
END_IST = datetime(2026, 11, 29, tzinfo=ZoneInfo("Asia/Kolkata")).timestamp()
MARKER = "MINTTICK_FNO43_HISTORY_E2E_V1:"
REQUEST = Path("infra/ec2-paper/requests/fno43-historical-e2e-probe.json")
REGION = "ap-south-1"
ACCOUNT = "098859530205"
INSTANCE = "i-09c01b470fb39b85a"
EXACT = {
    "kind": "minttick-fno43-readonly-historical-evidence-v1",
    "operation": "inspect-private-canonical-and-43-strategy-e2e-prerequisites",
    "account": ACCOUNT, "region": REGION, "instance_id": INSTANCE,
    "no_provider_calls": True, "no_market_data_export": True,
    "no_paper_or_live_orders": True, "no_rights_change": True,
}
KEY = re.compile(r"NSE_FO\|[A-Za-z0-9_-]+\|\d{2}-\d{2}-\d{4}\Z")
STAMP = re.compile(r"\d{4}-\d{2}-\d{2}T00:00:00\+05:30\Z")
SOURCES = {"UPSTOX_PRIVATE": 3608, "NSE_PUBLIC_EOD": 321}
MISSING_BY_FAMILY = (
    (range(170, 181), "FUTURES", "FUTURES_L1_SPOT_BID_ASK_AND_ORIGINAL_TIME"),
    (range(181, 192), "OPTIONS_DIRECTIONAL", "INDEX_EQUITY_HISTORY_AND_OPTION_L1"),
    (range(192, 204), "OPTIONS_VOLATILITY", "INDEX_HISTORY_OPTION_IV_GREEKS_AND_DEPTH"),
    (range(204, 213), "OPTIONS_HEDGING", "BROKER_POSITIONS_OPTION_GREEKS_AND_DEPTH"),
)

def _strategy_rows():
    rows = []
    for number in range(170, 213):
        family, missing = next((family, reason) for group, family, reason
                               in MISSING_BY_FAMILY if number in group)
        rows.append({"strategyId": "strategy.STR-" + str(number),
                     "family": family, "state": "BLOCKED_NOT_HISTORICAL_E2E",
                     "missing": [missing, "ORIGINAL_FIRST_KNOWN_AT_MISSING",
                                 "SOURCE_RESEARCH_RIGHTS_NOT_ATTESTED",
                                 "EVALUATOR_REPLAY_AND_AFTER_COST_OOS_MISSING"]})
    return rows

def _private_file(root):
    root = Path(root)
    if (not root.is_absolute() or not root.is_dir() or root.is_symlink()
            or root.stat().st_mode & 0o077):
        raise ValueError("PRIVATE_DIRECTORY_REQUIRED")
    target = root / FILE
    if (not target.is_file() or target.is_symlink()
            or not stat.S_ISREG(target.stat().st_mode)
            or target.stat().st_mode & 0o077):
        raise ValueError("PRIVATE_CACHE_UNAVAILABLE")
    return target

def _decimal(value, positive=False):
    try:
        v = Decimal(str(value))
    except (ValueError, TypeError, InvalidOperation):
        raise ValueError("INVALID_CANDLE_VALUE") from None
    if not v.is_finite() or (v <= 0 if positive else v < 0):
        raise ValueError("INVALID_CANDLE_VALUE")
    return v

def _audit_cache(root, now):
    target = _private_file(root)
    conn = sqlite3.connect("file:" + quote(str(target), safe="/") + "?mode=ro",
                           uri=True, timeout=10)
    try:
        conn.execute("PRAGMA query_only=ON")
        meta_cols = {x[1] for x in conn.execute("PRAGMA table_info(meta)")}
        data_cols = {x[1] for x in conn.execute("PRAGMA table_info(daily)")}
        if (not {"schema_version", "generated_at", "expires_at", "total"} <= meta_cols
                or not {"contract_key", "session_day", "candle_ts",
                        "open_price", "high_price", "low_price", "close_price",
                        "volume", "open_interest", "source"} <= data_cols):
            raise ValueError("CACHE_SCHEMA_UNVERIFIED")
        meta = conn.execute("SELECT schema_version,generated_at,expires_at,total "
                            "FROM meta").fetchall()
        if len(meta) != 1:
            raise ValueError("CACHE_META_UNVERIFIED")
        version, generated, expires, expected = meta[0]
        if (version != VERSION or type(generated) is not int
                or type(expires) is not int or type(expected) is not int
                or expected != 3929 or generated > now + 300
                or not 0 < generated < expires <= END_IST or now >= expires):
            raise ValueError("CACHE_EXPIRED_OR_UNVERIFIED")
        if conn.execute("PRAGMA quick_check").fetchone() != ("ok",):
            raise ValueError("CACHE_INTEGRITY_FAILED")
        counts = Counter()
        seen = set()
        contracts = set()
        earliest, latest = None, None
        for record in conn.execute(
            "SELECT contract_key,session_day,candle_ts,open_price,high_price,"
            "low_price,close_price,volume,open_interest,source FROM daily"
        ):
            key, day, stamp, op, high, low, close, volume, oi, source = record
            if (type(key) is not str or not KEY.fullmatch(key)
                    or type(day) is not str or type(stamp) is not str
                    or not STAMP.fullmatch(stamp) or stamp[:10] != day
                    or source not in SOURCES
                    or type(volume) is not int or type(oi) is not int
                    or not 0 <= volume <= 10**12 or not 0 <= oi <= 10**12):
                raise ValueError("CANDLE_PROVENANCE_OR_SCHEMA_INVALID")
            try:
                session = date.fromisoformat(day)
                expiry = datetime.strptime(key.rsplit("|", 1)[1], "%d-%m-%Y").date()
            except ValueError:
                raise ValueError("CANDLE_DATE_INVALID") from None
            if session > expiry:
                raise ValueError("CANDLE_AFTER_EXPIRY")
            expected_source = ("NSE_PUBLIC_EOD" if (expiry.year, expiry.month)
                               == (2025, 12) else "UPSTOX_PRIVATE")
            if source != expected_source:
                raise ValueError("CANDLE_SOURCE_CLASS_INVALID")
            prices = [_decimal(v, positive=True) for v in (op, high, low, close)]
            if prices[2] > min(prices[0], prices[3]) or prices[1] < max(prices[0], prices[3]):
                raise ValueError("CANDLE_OHLC_INVALID")
            ident = (key, day)
            if ident in seen:
                raise ValueError("CANDLE_DUPLICATE_SESSION")
            seen.add(ident)
            contracts.add(key)
            counts[source] += 1
            earliest = day if earliest is None or day < earliest else earliest
            latest = day if latest is None or day > latest else latest
            if len(seen) > 3929:
                raise ValueError("CACHE_ROW_LIMIT")
        if counts != SOURCES or len(seen) != expected or len(contracts) != 276:
            raise ValueError("CACHE_COHORT_MISMATCH")
        return {"cacheIntegrity": "VALIDATED_PRIVATE_DAILY_ONLY",
                "canonicalDailyRows": len(seen),
                "uniqueContractKeys": len(contracts),
                "sourceCounts": dict(sorted(counts.items())),
                "firstSession": earliest, "lastSession": latest,
                "cacheExpiresEpoch": expires}
    finally:
        conn.close()

def inspect(root=ROOT, now=None):
    now = time.time() if now is None else float(now)
    result = {"contractVersion": "minttick.fno43.historical-e2e-probe.v1",
              "strategiesExpected": 43, "historicalE2EValidated": 0,
              "paperE2EValidated": 0, "liveE2EValidated": 0,
              "independentSourceRightsVerified": False,
              "originalFirstKnownObserved": False,
              "backtestingApproved": False, "orderRoutesCalled": False,
              "strategies": _strategy_rows()}
    try:
        result.update(_audit_cache(root, now))
        result["status"] = "PRIVATE_DAILY_DATA_AUDITED_E2E_BLOCKED"
    except (ValueError, OSError, sqlite3.Error, TypeError):
        result["status"] = "PRIVATE_CACHE_UNAVAILABLE_OR_UNVERIFIED"
        result["cacheIntegrity"] = "NOT_VERIFIED"
        result["canonicalDailyRows"] = None
        result["uniqueContractKeys"] = None
        result["sourceCounts"] = {}
        result["firstSession"] = None
        result["lastSession"] = None
        result["cacheExpiresEpoch"] = None
    return result

def _checked_report(result):
    if (type(result) is not dict or result.get("strategiesExpected") != 43
            or result.get("historicalE2EValidated") != 0
            or result.get("paperE2EValidated") != 0
            or result.get("liveE2EValidated") != 0
            or result.get("backtestingApproved") is not False
            or result.get("orderRoutesCalled") is not False
            or len(result.get("strategies", [])) != 43):
        raise ValueError("RECEIPT_NOT_TRUSTED")
    if [row.get("strategyId") for row in result["strategies"]] != [
            f"strategy.STR-{n}" for n in range(170, 213)]:
        raise ValueError("RECEIPT_STRATEGIES_NOT_43")
    if any(row.get("state") != "BLOCKED_NOT_HISTORICAL_E2E"
           for row in result["strategies"]):
        raise ValueError("UNVERIFIED_E2E_PROMOTION")
    if result.get("status") not in {
        "PRIVATE_DAILY_DATA_AUDITED_E2E_BLOCKED", "PRIVATE_CACHE_UNAVAILABLE_OR_UNVERIFIED"
    }:
        raise ValueError("RECEIPT_STATUS_NOT_ALLOWED")
    return result

def host():
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        raise ValueError("EXACT_PRIVATE_HOST_REQUIRED")
    report = _checked_report(inspect())
    print(MARKER + json.dumps(report, separators=(",", ":"), sort_keys=True), flush=True)

def runner():
    if (not REQUEST.is_file() or REQUEST.is_symlink()
            or REQUEST.stat().st_size >= 1024
            or json.loads(REQUEST.read_text()) != EXACT):
        raise ValueError("REQUEST_NOT_APPROVED")
    from verify_ssm_release import verify_aws
    from fno_private_bulk_ops import _aws
    verify_aws()
    packed = base64.b64encode(gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(packed) >= 18000 or not re.fullmatch("[A-Za-z0-9+/=]+", packed):
        raise ValueError("UNBOUNDED_HOST_SCRIPT")
    command = ("set -eu\nPYTHONDONTWRITEBYTECODE=1 "
               "printf '%s' '" + packed + "' | base64 -d | gzip -d | "
               "PYTHONDONTWRITEBYTECODE=1 python3 - host\n")
    sent = _aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
                "--instance-ids", INSTANCE, "--timeout-seconds", "120",
                "--comment", "MintTick read-only 43-strategy historical eligibility",
                "--parameters", json.dumps({"commands": [command]}))
    cid = (sent.get("Command") or {}).get("CommandId")
    if not isinstance(cid, str) or not re.fullmatch("[0-9a-f-]{36}", cid):
        raise ValueError("INVALID_SSM_COMMAND")
    for _ in range(24):
        time.sleep(5)
        result = _aws("ssm", "get-command-invocation",
                      "--command-id", cid, "--instance-id", INSTANCE)
        state = result.get("Status")
        if state == "Success":
            output = result.get("StandardOutputContent") or ""
            if len(output) > 15000:
                raise ValueError("UNEXPECTED_HOST_OUTPUT")
            hits = [line[len(MARKER):] for line in output.splitlines()
                    if line.startswith(MARKER)]
            if len(hits) != 1:
                raise ValueError("MISSING_PRIVATE_RECEIPT")
            attestation = _checked_report(json.loads(hits[0]))
            # Only bounded aggregate counts and missing-domain IDs leave GitHub.
            print("FNO43_HISTORY_E2E_AUDIT:status=" + attestation["status"]
                  + ";cacheRows=" + str(attestation["canonicalDailyRows"])
                  + ";contracts=" + str(attestation["uniqueContractKeys"])
                  + ";historicalVerified=0/43;paperVerified=0/43;liveVerified=0/43"
                  + ";sourceRights=UNVERIFIED;sourceFirstKnown=MISSING;"
                  + "backtestingApproved=false;orders=false")
            for row in attestation["strategies"]:
                print("FNO43_BLOCKED:" + row["strategyId"] + "|"
                      + row["missing"][0])
            return
        if state in ("Failed", "TimedOut", "Cancelled"):
            raise ValueError("SSM_PROBE_FAILED")
        if state not in ("Pending", "InProgress", "Delayed"):
            raise ValueError("INVALID_SSM_STATE")
    raise ValueError("SSM_PROBE_TIMEOUT")

if __name__ == "__main__":
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ("runner", "host"):
            raise ValueError("UNSUPPORTED_MODE")
        host() if sys.argv[1] == "host" else runner()
    except (ValueError, OSError, sqlite3.Error, TypeError, KeyError):
        print("FNO43_HISTORY_E2E_AUDIT:BLOCKED_NO_VERIFIED_RECEIPT",
              file=sys.stderr)
        raise SystemExit(2)
