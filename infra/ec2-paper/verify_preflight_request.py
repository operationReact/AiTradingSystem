#!/usr/bin/env python3
"""Fail-closed read-only AWS EC2-paper preflight request on dev.

This module never calls AWS. The request file is submitted only through an
audited PR merged to dev, where a strictly read-only GitHub OIDC role is used.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

EXPECTED = {
    "kind": "ec2-paper",
    "account": "098859530205",
    "region": "ap-south-1",
    "target": "aitradingsystem-paper-ec2",
    "operation": "inspect",
    "confirmation": "READ_ONLY_AWS_PREFLIGHT",
}
MAX_BYTES = 1024

def validate(data: object) -> None:
    if not isinstance(data, dict) or data != EXPECTED:
        raise ValueError("AWS PAPER request mismatched approved read-only fields")

def main(args: list[str]) -> int:
    if len(args) != 2:
        print("EC2 PAPER preflight BLOCKED", file=sys.stderr)
        return 2
    try:
        p = Path(args[1])
        if p.is_symlink() or not p.is_file() or p.stat().st_size > MAX_BYTES:
            raise ValueError("missing or oversized request")
        validate(json.loads(p.read_text(encoding="utf-8")))
    except (ValueError, TypeError, OSError, json.JSONDecodeError):
        print("EC2 PAPER preflight BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("Approved EC2 PAPER read-only preflight, account/region/dev fixed")
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
