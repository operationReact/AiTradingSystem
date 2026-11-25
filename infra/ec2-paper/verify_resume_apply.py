#!/usr/bin/env python3
"""Fail-closed recovery for the exact PAPER EC2 state created by the first partial apply.

No AWS calls. A previous provisioning run created a VPC and four IAM objects,
all in remote Terraform state, before failing on missing ec2:DescribeVpcAttribute.
Only eight remaining CREATE actions can be applied after an explicit new request.
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("paper_locked_gate", ROOT / "verify_locked_apply.py")
assert spec and spec.loader
locked = importlib.util.module_from_spec(spec)
spec.loader.exec_module(locked)

EXISTING = frozenset({
    "aws_vpc.paper[0]",
    "aws_iam_role.instance_ssm[0]",
    "aws_iam_role.daily_stop[0]",
    "aws_iam_instance_profile.paper[0]",
    "aws_iam_role_policy_attachment.ssm_core[0]",
})
EXPECTED_NEW = locked.gate.ADDRESSES - EXISTING
EXPECTED_DATA = frozenset({
    "data.aws_caller_identity.current",
    "data.aws_ssm_parameter.al2023_arm64[0]",
})
REQUEST = {
    **locked.EXPECTED,
    "kind": "ec2-paper-resume",
    "operation": "resume-partial-once",
    "confirmation": "RESUME_PARTIAL_PAPER_EC2_4GB",
}
MAX_PLAN_BYTES = 30 * 1024 * 1024
MAX_REQUEST_BYTES = 2048
MAX_STATE_LIST_BYTES = 4096


def validate_request(obj: object) -> None:
    if not isinstance(obj, dict) or obj != REQUEST:
        raise ValueError("unapproved PAPER partial recovery request")


def validate_state_addresses(text: str) -> None:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(lines) != len(set(lines)):
        raise ValueError("duplicate addresses in remote Terraform state")
    if set(lines) != EXISTING | EXPECTED_DATA:
        raise ValueError("remote state differs from audited partial provisioning state")


def validate_plan(plan: object) -> None:
    if not isinstance(plan, dict) or not isinstance(plan.get("resource_changes"), list):
        raise ValueError("missing Terraform changes")
    created: dict[str, dict] = {}
    observed = set()
    for item in plan["resource_changes"]:
        if not isinstance(item, dict):
            raise ValueError("invalid resource change")
        address, mode = item.get("address"), item.get("mode")
        change = item.get("change")
        actions = change.get("actions") if isinstance(change, dict) else None
        if mode == "data":
            if address not in EXPECTED_DATA or actions not in (["no-op"], ["read"]):
                raise ValueError("unexpected data source change")
            continue
        if mode != "managed" or address not in locked.gate.ADDRESSES or address in observed:
            raise ValueError("unapproved or duplicate managed resource")
        observed.add(address)
        if address in EXISTING:
            if actions != ["no-op"]:
                raise ValueError("previously created resource would change")
        else:
            if actions != ["create"] or not isinstance(change.get("after"), dict):
                raise ValueError("partial recovery must only create remaining resources")
            created[address] = change["after"]
    if set(created) != EXPECTED_NEW:
        raise ValueError("partial recovery must create exactly eight missing resources")

    for address, after in created.items():
        if address in locked.TAGGED_CREATE_RESOURCES:
            tags = after.get("tags")
            if not isinstance(tags, dict) or any(
                tags.get(k) != v for k, v in locked.REQUIRED_TAGS.items()
            ):
                raise ValueError("IAM-required PAPER creation tags absent")

    instance = created["aws_instance.paper[0]"]
    if (
        instance.get("instance_type") != "t4g.medium"
        or instance.get("disable_api_termination") is not True
        or instance.get("instance_initiated_shutdown_behavior") != "stop"
        or instance.get("associate_public_ip_address") is not True
    ):
        raise ValueError("PAPER host shape mismatch")
    meta = instance.get("metadata_options")
    if not isinstance(meta, list) or len(meta) != 1 or meta[0].get("http_tokens") != "required":
        raise ValueError("IMDSv2 required")
    disk = instance.get("root_block_device")
    if not isinstance(disk, list) or len(disk) != 1 or not (
        disk[0].get("volume_size") == 35
        and disk[0].get("volume_type") == "gp3"
        and disk[0].get("encrypted") is True
        and disk[0].get("delete_on_termination") is False
    ):
        raise ValueError("disk type, encryption or size mismatch")
    if created["aws_security_group.paper[0]"].get("ingress") not in (None, []):
        raise ValueError("security group ingress must remain empty")
    stop = created["aws_scheduler_schedule.daily_stop[0]"]
    if (
        stop.get("schedule_expression_timezone") != "Asia/Kolkata"
        or stop.get("schedule_expression") != "cron(0 22 * * ? *)"
        or stop.get("state") != "ENABLED"
    ):
        raise ValueError("mandatory 22 IST stop schedule absent")


def main(argv: list[str]) -> int:
    try:
        if len(argv) != 3 or argv[1] not in ("request", "state", "plan"):
            raise ValueError("unsupported mode")
        mode, file = argv[1], Path(argv[2])
        limit = {"request": MAX_REQUEST_BYTES, "state": MAX_STATE_LIST_BYTES,
                 "plan": MAX_PLAN_BYTES}[mode]
        if file.is_symlink() or not file.is_file() or file.stat().st_size > limit:
            raise ValueError("invalid input")
        data = file.read_text(encoding="utf-8")
        if mode == "state":
            validate_state_addresses(data)
        elif mode == "request":
            validate_request(json.loads(data))
        else:
            validate_plan(json.loads(data))
    except (OSError, ValueError, KeyError, TypeError, StopIteration, AttributeError,
            json.JSONDecodeError):
        print("PAPER EC2 partial resume BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("PAPER exact partial recovery verified; LIVE trading remains disabled")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
