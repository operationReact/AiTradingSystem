#!/usr/bin/env python3
"""Explicit auditable dev-branch Git push request; no credentials or AWS calls.

Only an exact changed request file on the protected dev branch may trigger
PAPER instance preflight/provision or internal-only application deployment.
No request file exists in the repo until intentionally committed by a user.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

INSTANCE = "aitradingsystem-paper-dev"
MAX_BYTES = 1024


def validate(payload: dict, kind: str) -> tuple[str, str]:
    if not isinstance(payload, dict) or set(payload) != {
        "kind", "target", "operation", "confirmation",
    } or payload.get("kind") != kind or payload.get("target") != INSTANCE:
        raise ValueError("unreviewed deployment request")
    operation, confirm = payload["operation"], payload["confirmation"]
    if not isinstance(operation, str) or not isinstance(confirm, str):
        raise ValueError("deployment request fields invalid")
    allowed = {
        "bootstrap": {
            "preflight": "",
            "provision": "CREATE_PAPER_4GB",
        },
        "deploy": {
            "deploy": "DEPLOY_PAPER_LOCAL_ONLY",
        },
    }
    if kind not in allowed or allowed[kind].get(operation) != confirm:
        raise ValueError("deployment request approval absent")
    return operation, confirm


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--kind", choices=("bootstrap", "deploy"), required=True)
    p.add_argument("--request", type=Path, required=True)
    p.add_argument("--github-output", type=Path, required=True)
    a = p.parse_args()
    try:
        if a.request.is_symlink() or not a.request.is_file():
            raise ValueError("missing committed request")
        if a.request.stat().st_size > MAX_BYTES:
            raise ValueError("oversized request")
        operation, confirmation = validate(json.loads(a.request.read_text()), a.kind)
        with a.github_output.open("a", encoding="utf-8") as out:
            out.write(f"operation={operation}\nconfirmation={confirmation}\n")
        print(f"Explicit dev-branch {a.kind} request validated for {operation}")
        return 0
    except (ValueError, TypeError, KeyError, OSError, AttributeError,
            json.JSONDecodeError):
        print("Lightsail PAPER request BLOCKED (sanitized)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
