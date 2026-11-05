#!/usr/bin/env python3
"""Strict recovery from a partially created, Terraform-tainted PAPER security group.

The user-approved eight-create apply left an existing tagged, no-inbound SG in
AWS, plus nine other managed resources. It failed while attempting to revoke
AWS's implicit default egress rule. Before reconciling the state taint, the
deployment must independently verify the real SG. It may only add the last
three resources, and must not delete or replace any existing AWS resources.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ACCOUNT = "098859530205"
STATE_MANAGED = frozenset({
    "aws_vpc.paper[0]",
    "aws_internet_gateway.paper[0]",
    "aws_subnet.paper[0]",
    "aws_route_table.paper[0]",
    "aws_route_table_association.paper[0]",
    "aws_security_group.paper[0]",
    "aws_iam_role.instance_ssm[0]",
    "aws_iam_role.daily_stop[0]",
    "aws_iam_instance_profile.paper[0]",
    "aws_iam_role_policy_attachment.ssm_core[0]",
})
CREATE_ONLY = frozenset({
    "aws_instance.paper[0]",
    "aws_iam_role_policy.daily_stop[0]",
    "aws_scheduler_schedule.daily_stop[0]",
})
DATA = frozenset({
    "data.aws_caller_identity.current",
    "data.aws_ssm_parameter.al2023_arm64[0]",
})
SG = "aws_security_group.paper[0]"
REQUEST = {
    "kind": "ec2-paper-sg-taint-reconciliation",
    "account": ACCOUNT,
    "region": "ap-south-1",
    "target": "aitradingsystem-paper-ec2",
    "instance_type": "t4g.medium",
    "root_volume_gib": 35,
    "daily_stop_hour_ist": 22,
    "daily_stop_enabled": True,
    "operation": "untaint-sg-and-resume-once",
    "confirmation": "RECONCILE_AUDITED_SG_TAINT_AND_RESUME_PAPER_4GB",
}
REQUIRED_TAGS = {"Application": "ai-trading-system", "Environment": "paper"}
MAX_PLAN_BYTES = 30 * 1024 * 1024


def validate_request(obj: object) -> None:
    if not isinstance(obj, dict) or obj != REQUEST:
        raise ValueError("unapproved SG taint reconciliation request")


def validate_state_addresses(value: str) -> None:
    lines = [x.strip() for x in value.splitlines() if x.strip()]
    if len(lines) != len(set(lines)) or set(lines) != STATE_MANAGED | DATA:
        raise ValueError("S3 state no longer contains exact ten known managed resources")


def validate_plan(plan: object, *, pre_taint: bool) -> None:
    if not isinstance(plan, dict) or not isinstance(plan.get("resource_changes"), list):
        raise ValueError("invalid Terraform plan")
    seen: set[str] = set()
    new: dict[str, dict] = {}
    for item in plan["resource_changes"]:
        if not isinstance(item, dict) or not isinstance(item.get("change"), dict):
            raise ValueError("malformed Terraform change")
        addr, mode, change = item.get("address"), item.get("mode"), item["change"]
        actions = change.get("actions")
        if mode == "data":
            if addr not in DATA or actions not in (["no-op"], ["read"]):
                raise ValueError("unapproved data source")
            continue
        if mode != "managed" or addr not in STATE_MANAGED | CREATE_ONLY or addr in seen:
            raise ValueError("unexpected or duplicate managed resource")
        seen.add(addr)
        if addr == SG and pre_taint:
            if (actions != ["delete", "create"]
                or item.get("action_reason") != "replace_because_tainted"):
                raise ValueError("SG replacement is not solely Terraform taint")
        elif addr in STATE_MANAGED:
            if actions != ["no-op"]:
                raise ValueError("existing PAPER resources must never be mutated")
        else:
            if actions != ["create"] or not isinstance(change.get("after"), dict):
                raise ValueError("only three exact missing resources may be created")
            new[addr] = change["after"]

    if seen != STATE_MANAGED | CREATE_ONLY or set(new) != CREATE_ONLY:
        raise ValueError("unexpected number of resource changes")

    inst = new["aws_instance.paper[0]"]
    if (
        inst.get("instance_type") != "t4g.medium"
        or inst.get("disable_api_termination") is not True
        or inst.get("instance_initiated_shutdown_behavior") != "stop"
        or inst.get("associate_public_ip_address") is not True
    ):
        raise ValueError("invalid PAPER EC2 host configuration")
    metadata = inst.get("metadata_options")
    if not isinstance(metadata, list) or len(metadata) != 1 or metadata[0].get("http_tokens") != "required":
        raise ValueError("IMDSv2 required")
    disk = inst.get("root_block_device")
    if not isinstance(disk, list) or len(disk) != 1 or not (
        disk[0].get("encrypted") is True and disk[0].get("volume_type") == "gp3"
        and disk[0].get("volume_size") == 35
        and disk[0].get("delete_on_termination") is False
    ):
        raise ValueError("disk/storage isolation mismatch")
    tags = inst.get("tags")
    if not isinstance(tags, dict) or any(tags.get(k) != v for k,v in REQUIRED_TAGS.items()):
        raise ValueError("missing required PAPER EC2 tags")
    stop = new["aws_scheduler_schedule.daily_stop[0]"]
    if (
        stop.get("schedule_expression_timezone") != "Asia/Kolkata"
        or stop.get("schedule_expression") != "cron(0 22 * * ? *)"
        or stop.get("state") != "ENABLED"
    ):
        raise ValueError("mandatory nightly stop scheduler mismatch")


def main(argv: list[str]) -> int:
    try:
        if len(argv) != 3 or argv[1] not in ("request", "state", "tainted", "final"):
            raise ValueError("invalid verification mode")
        mode, path = argv[1], Path(argv[2])
        limit = MAX_PLAN_BYTES if mode in ("tainted", "final") else 4096
        if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
            raise ValueError("invalid input")
        raw = path.read_text(encoding="utf-8")
        if mode == "state":
            validate_state_addresses(raw)
        elif mode == "request":
            validate_request(json.loads(raw))
        else:
            validate_plan(json.loads(raw), pre_taint=(mode == "tainted"))
    except (OSError, ValueError, KeyError, TypeError, AttributeError,
            json.JSONDecodeError):
        print("PAPER SG-taint recovery blocked (sanitized)", file=sys.stderr)
        return 2
    print("PAPER SG reconciliation gate PASS; no LIVE orders authorized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
