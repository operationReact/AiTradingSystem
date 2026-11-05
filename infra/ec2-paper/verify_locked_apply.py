#!/usr/bin/env python3
"""Fail-closed one-host PAPER provisioning: exact request and fresh Terraform JSON plan.

Only validates local data. It never calls AWS or authorizes a LIVE trade.
The same *saved* Terraform binary plan must be applied after this check passes.
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODULE = ROOT / "verify_readonly_plan.py"
spec = importlib.util.spec_from_file_location("ec2_paper_readonly_plan", MODULE)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

EXPECTED = {
    "kind": "ec2-paper-provision",
    "account": "098859530205",
    "region": "ap-south-1",
    "target": "aitradingsystem-paper-ec2",
    "instance_type": "t4g.medium",
    "root_volume_gib": 35,
    "daily_stop_hour_ist": 22,
    "daily_stop_enabled": True,
    "operation": "provision-once",
    "confirmation": "PROVISION_PAPER_EC2_4GB",
}
MAX_REQUEST_BYTES = 2048
MAX_PLAN_BYTES = 30 * 1024 * 1024


def validate_request(request: object) -> None:
    if not isinstance(request, dict) or request != EXPECTED:
        raise ValueError("exact paid PAPER provisioning confirmation mismatch")


def validate_fresh_plan(plan: object) -> None:
    gate.verify_plan(plan)
    # The narrow plan gate insists all 13 expected managed resources are
    # CREATE-only: no state replacement, resize, import, delete or updates.
    changes = plan["resource_changes"]
    if len(changes) != 13 or any(
        item.get("change", {}).get("actions") != ["create"] for item in changes
    ):
        raise ValueError("only thirteen new PAPER resources may be created")
    # The plan captures a configured 35 GiB disk exactly, not a 30-50 GiB range.
    inst = next(x["change"]["after"] for x in changes
                if x["address"] == "aws_instance.paper[0]")
    disk = inst["root_block_device"][0]
    if disk.get("volume_size") != 35:
        raise ValueError("not the approved 35 GiB encrypted root disk")
    # No unknown action fields, additional managed objects or other projects.
    # All secrets stay in Terraform/runner memory, never emitted in the result.


def read_json(path: str, limit: int) -> object:
    p = Path(path)
    if p.is_symlink() or not p.is_file() or p.stat().st_size > limit:
        raise ValueError("missing, symlinked, or oversized local input")
    return json.loads(p.read_text(encoding="utf-8"))


def main(args: list[str]) -> int:
    try:
        if len(args) != 3 or args[1] not in ("request", "plan"):
            raise ValueError("invalid gate mode")
        path, max_bytes = (
            (args[2], MAX_REQUEST_BYTES) if args[1] == "request"
            else (args[2], MAX_PLAN_BYTES)
        )
        obj = read_json(path, max_bytes)
        if args[1] == "request":
            validate_request(obj)
        else:
            validate_fresh_plan(obj)
    except (OSError, ValueError, KeyError, TypeError, AttributeError,
            StopIteration, json.JSONDecodeError):
        print("PAPER EC2 approved locked apply BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("Exact paid PAPER EC2 request/plan verified; LIVE trading remains disabled")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
