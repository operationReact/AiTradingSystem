#!/usr/bin/env python3
"""Read-only diagnostics for MintTick PAPER F&O HTTP 500s on existing EC2.

Reads only PostgreSQL schema metadata, fixed runtime configuration flags and
bounded, sanitized log categories. Never returns customer rows, positions,
cash, prices, broker payloads, bearer tokens, parameters or raw log text.
Does not modify database, containers, AWS, broker account or order privileges.
"""
from __future__ import annotations

import os
from pathlib import Path
import re
import subprocess

ROOT = Path("/opt/aitradingsystem-paper")
SERVICES = ("postgres", "backend")
FLAG_KEYS = (
    "MINTTICK_UPSTOX_FNO_PAPER_ENABLED",
    "MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED",
    "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED",
    "MINTTICK_UPSTOX_FNO_PAPER_USAGE_RIGHTS_VERIFIED",
    "MINTTICK_UPSTOX_ANALYTICS_ENABLED",
)


def run(cmd, timeout=8):
    try:
        return subprocess.run(cmd, text=True, capture_output=True,
                              check=False, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None


def container(service):
    if service not in SERVICES:
        return None
    proc = run(["docker", "ps", "-q", "--filter",
                "label=com.docker.compose.project=aitradingsystem-paper",
                "--filter", "label=com.docker.compose.service=" + service], 5)
    if proc is None or proc.returncode:
        return None
    ident = proc.stdout.strip()
    return ident if re.fullmatch(r"[a-f0-9]{12,64}", ident) else None


def sql_state(pg, statement, allowed):
    if not pg:
        return "UNAVAILABLE"
    proc = run(["docker", "exec", pg, "psql", "-U", "aitrading",
                "-d", "aitrading", "-tA", "-c", statement], 8)
    if proc is None or proc.returncode:
        return "UNAVAILABLE"
    value = proc.stdout.strip()
    return value if value in allowed else "UNAVAILABLE"


def table_state(pg, relation):
    if relation not in (
        "customer_paper_fno_position", "customer_paper_fno_order",
        "customer_paper_account", "customer_trading_room",
        "flyway_schema_history"
    ):
        return "UNAVAILABLE"
    result = sql_state(pg, "SELECT CASE WHEN to_regclass('public." + relation
                       + "') IS NOT NULL THEN 'PRESENT' ELSE 'MISSING' END",
                       {"PRESENT", "MISSING"})
    return result


def migration_state(pg, version):
    if version not in ("92", "94"):
        return "UNAVAILABLE"
    return sql_state(pg, """
        SELECT CASE WHEN EXISTS (
           SELECT 1 FROM flyway_schema_history
            WHERE version = '""" + version + """' AND success = true
        ) THEN 'APPLIED' ELSE 'NOT_APPLIED' END
    """, {"APPLIED", "NOT_APPLIED"})


def release_state():
    path = ROOT / ".env"
    if path.is_symlink() or not path.is_file():
        return "UNAVAILABLE"
    try:
        for line in path.read_text().splitlines():
            if line.startswith("RELEASE_SHA="):
                value = line.removeprefix("RELEASE_SHA=").strip()
                return value if re.fullmatch("[a-f0-9]{40}", value) else "UNAVAILABLE"
    except OSError:
        pass
    return "UNAVAILABLE"


def config_state():
    path = ROOT / ".env"
    if path.is_symlink() or not path.is_file():
        return {k: "UNKNOWN" for k in FLAG_KEYS}
    result = {k: "UNKNOWN" for k in FLAG_KEYS}
    try:
        for line in path.read_text().splitlines():
            key, sep, val = line.partition("=")
            if sep and key in result:
                result[key] = "ENABLED" if val.strip().lower() == "true" else (
                    "DISABLED" if val.strip().lower() == "false" else "UNKNOWN")
    except OSError:
        pass
    return result


def effective_flags(backend):
    """Inspect only allowlisted boolean flags from running container env.

    Docker inspect may contain sensitive configuration; parse in process memory
    and emit no environment values, unknown names or raw JSON.
    """
    result = {k: "UNKNOWN" for k in FLAG_KEYS}
    if not backend:
        return result
    proc = run(["docker", "inspect", "--format", "{{json .Config.Env}}", backend], 6)
    if proc is None or proc.returncode:
        return result
    try:
        import json
        rows = json.loads(proc.stdout)
        if not isinstance(rows, list):
            return result
        for value in rows:
            if not isinstance(value, str):
                continue
            key, sep, val = value.partition("=")
            if sep and key in result:
                result[key] = "ENABLED" if val.lower() == "true" else (
                    "DISABLED" if val.lower() == "false" else "UNKNOWN")
    except (TypeError, ValueError):
        return {k: "UNKNOWN" for k in FLAG_KEYS}
    return result


def backend_errors(backend):
    """Classify limited recent logs, discard every original line."""
    if not backend:
        return "UNAVAILABLE"
    proc = run(["docker", "logs", "--since", "30m", "--tail", "500", backend], 10)
    if proc is None or proc.returncode:
        return "UNAVAILABLE"
    body = (proc.stdout + proc.stderr)[-131072:].lower()
    if "customer_paper_fno_" in body and (
        "does not exist" in body or "undefined_table" in body):
        return "FNO_TABLE_MISSING"
    if "customer_paper_fno_" in body and (
        "permission denied" in body or "insufficient_privilege" in body):
        return "FNO_TABLE_PERMISSION"
    if "customer_paper_fno_" in body and (
        "column" in body and "does not exist" in body):
        return "FNO_COLUMN_MISMATCH"
    if "customer_paper_fno_" in body and (
        "bad sql grammar" in body or "psqlexception" in body):
        return "FNO_SQL_EXCEPTION"
    if "upstox" in body and ("exception" in body or "error" in body):
        return "UPSTOX_RUNTIME_ERROR"
    if "customerpaperfno" in body and ("exception" in body or "error" in body):
        return "FNO_RUNTIME_ERROR"
    return "NO_CLASSIFIED_ERROR"


def main():
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        raise SystemExit("FNO_DIAGNOSTIC_HOST_INVALID")
    if ROOT.is_symlink() or not ROOT.is_dir():
        raise SystemExit("FNO_DIAGNOSTIC_PATH_INVALID")
    pg, backend = (container(n) for n in SERVICES)
    print("FNO_POSTGRES: " + ("RUNNING" if pg else "UNAVAILABLE"))
    print("FNO_BACKEND: " + ("RUNNING" if backend else "UNAVAILABLE"))
    print("FNO_RELEASE_SHA: " + release_state())
    for relation in (
        "customer_paper_fno_position", "customer_paper_fno_order",
        "customer_paper_account", "customer_trading_room",
        "flyway_schema_history"
    ):
        print("FNO_TABLE_" + relation.upper() + ": " + table_state(pg, relation))
    for version in ("92", "94"):
        print("FNO_MIGRATION_" + version + ": " + migration_state(pg, version))
    for key, state in sorted(config_state().items()):
        print("FNO_CONFIG_" + key + ": " + state)
    for key, state in sorted(effective_flags(backend).items()):
        print("FNO_EFFECTIVE_" + key + ": " + state)
    print("FNO_ERROR_CLASS: " + backend_errors(backend))


if __name__ == "__main__":
    main()
