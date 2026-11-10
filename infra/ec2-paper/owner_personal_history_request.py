#!/usr/bin/env python3
"""Fixed single-file approval for owner-private history audit or download."""
import json
import os
from pathlib import Path
import sys

DOWNLOAD = "infra/ec2-paper/requests/owner-personal-history-download.json"
AUDIT = "infra/ec2-paper/requests/owner-personal-history-audit.json"
COMMON = {
    "application": "MintTick",
    "account": "098859530205",
    "region": "ap-south-1",
    "instance_id": "i-09c01b470fb39b85a",
    "private_ec2_only": True,
    "no_redistribution": True,
    "enable_trading": False,
}
APPROVALS = {
    DOWNLOAD: ("download", {
        **COMMON,
        "operation": "owner-personal-angelone-daily-history-pilot",
        "from_date": "2006-11-10",
        "through_date": "2026-11-10",
        "max_equities": 5,
        "max_provider_calls": 20,
        "min_spacing_seconds": 10,
    }),
    AUDIT: ("audit-only", {
        **COMMON,
        "operation": "owner-personal-angelone-daily-history-audit",
        "audit_only": True,
        "max_provider_calls": 0,
    }),
}


def strict_pairs(pairs):
    obj = {}
    for k, v in pairs:
        if k in obj:
            raise ValueError("Duplicate owner request field")
        obj[k] = v
    return obj


def approved_mode(path, payload):
    if path not in APPROVALS:
        raise ValueError("Not one fixed approved request path")
    mode, expected = APPROVALS[path]
    if type(payload) is not dict or payload.keys() != expected.keys():
        raise ValueError("Owner request has unexpected fields")
    if any(type(payload[k]) is not type(v) or payload[k] != v for k, v in expected.items()):
        raise ValueError("Owner request fields differ from reviewed approval")
    return mode


def validate_file(changed_path):
    if changed_path not in APPROVALS:
        raise ValueError("Refuse unknown, multiple or arbitrary changed paths")
    path = Path(changed_path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 2048:
        raise ValueError("Invalid fixed owner request file")
    payload = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_pairs)
    return approved_mode(changed_path, payload)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Exactly one fixed owner request file required")
    mode = validate_file(sys.argv[1])
    environment = os.environ.get("GITHUB_ENV")
    if not environment:
        raise SystemExit("GitHub runner environment missing")
    with Path(environment).open("a", encoding="utf-8") as fh:
        fh.write("OWNER_HISTORY_MODE=" + mode + "\n")
    print("Approved owner historical mode: " + mode)


if __name__ == "__main__":
    main()
