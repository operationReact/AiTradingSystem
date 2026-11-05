#!/usr/bin/env python3
"""Fail-closed Terraform state recovery of ONE existing, stopped PAPER EC2.

The partial EC2 create reached AWS but failed when Terraform attempted an
instance attribute write. The same exact instance was stopped on failure.
Only the *taint marker* of that exact, independently audited instance may be
cleared. No server start, replacement, broad network edits or LIVE trading.
Only the missing stop-role IAM inline policy and nightly schedule are created.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ACCOUNT = "098859530205"
INSTANCE_ID = "i-09c01b470fb39b85a"
INSTANCE_ARN = f"arn:aws:ec2:ap-south-1:{ACCOUNT}:instance/{INSTANCE_ID}"
INSTANCE_ADDR = "aws_instance.paper[0]"
EXISTING = frozenset({
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
    INSTANCE_ADDR,
})
REMAINING = frozenset({
    "aws_iam_role_policy.daily_stop[0]",
    "aws_scheduler_schedule.daily_stop[0]",
})
DATA = frozenset({
    "data.aws_caller_identity.current",
    "data.aws_ssm_parameter.al2023_arm64[0]",
})
REQUEST = {
    "kind": "ec2-paper-existing-host-recovery",
    "account": ACCOUNT,
    "region": "ap-south-1",
    "target": "aitradingsystem-paper-ec2",
    "instance_id": INSTANCE_ID,
    "instance_type": "t4g.medium",
    "root_volume_gib": 35,
    "daily_stop_hour_ist": 22,
    "daily_stop_enabled": True,
    "operation": "untaint-stopped-host-and-create-stop-schedule",
    "confirmation": "FINISH_EXISTING_STOPPED_PAPER_EC2_ONLY",
}


def validate_request(value: object) -> None:
    if not isinstance(value, dict) or value != REQUEST:
        raise ValueError("unapproved exact stopped PAPER EC2 finish request")


def validate_state_addresses(value: str) -> None:
    addresses = [line.strip() for line in value.splitlines() if line.strip()]
    if len(addresses) != len(set(addresses)) or set(addresses) != EXISTING | DATA:
        raise ValueError("remote Terraform state differs from audited eleven existing resources")



def validate_stopped_host_state(value: object) -> None:
    # This state file is read only inside the ephemeral GitHub runner and
    # must never be logged, uploaded or returned in a final message.
    if not isinstance(value, dict) or not isinstance(value.get("resources"), list):
        raise ValueError("invalid protected remote Terraform state")
    instances = [
        resource for resource in value["resources"]
        if isinstance(resource, dict)
        and resource.get("mode") == "managed"
        and resource.get("type") == "aws_instance"
        and resource.get("name") == "paper"
    ]
    if len(instances) != 1 or len(instances[0].get("instances", [])) != 1:
        raise ValueError("unexpected number of PAPER hosts in Terraform state")
    instance = instances[0]["instances"][0]
    if (instance.get("index_key") != 0
        or instance.get("status") != "tainted"
        or not isinstance(instance.get("attributes"), dict)
        or instance["attributes"].get("id") != INSTANCE_ID
        or instance["attributes"].get("instance_type") != "t4g.medium"):
        raise ValueError("unexpected state; no taint removal permitted")


def _parse_policy_json(raw: object) -> None:
    # IAM policy may be computed during planning; if known, verify exact
    # stop-only policy. Final post-apply verification is mandatory regardless.
    if raw is None:
        return
    if not isinstance(raw, str):
        raise ValueError("stop policy must be JSON or unknown")
    parsed = json.loads(raw)
    statements = parsed.get("Statement", []) if isinstance(parsed, dict) else []
    if len(statements) != 1 or not isinstance(statements[0], dict):
        raise ValueError("stop policy has unexpected statements")
    s = statements[0]
    if (s.get("Effect") != "Allow"
        or s.get("Action") != "ec2:StopInstances"
        or s.get("Resource") != INSTANCE_ARN):
        raise ValueError("stop policy must target exact existing PAPER instance")
    c = s.get("Condition", {}).get("StringEquals", {})
    if c != {
        "ec2:ResourceTag/Application": "ai-trading-system",
        "ec2:ResourceTag/Environment": "paper",
    }:
        raise ValueError("stop policy conditions unexpectedly broad")


def validate_plan(plan: object, *, tainted: bool) -> None:
    if not isinstance(plan, dict) or not isinstance(plan.get("resource_changes"), list):
        raise ValueError("invalid Terraform plan")
    seen: set[str] = set()
    created: dict[str, dict] = {}
    for item in plan["resource_changes"]:
        if not isinstance(item, dict) or not isinstance(item.get("change"), dict):
            raise ValueError("malformed Terraform resource change")
        address, mode, change = item.get("address"), item.get("mode"), item["change"]
        actions = change.get("actions")
        if mode == "data":
            if address not in DATA or actions not in (["read"], ["no-op"]):
                raise ValueError("unexpected data source change")
            continue
        if mode != "managed" or address not in EXISTING | REMAINING or address in seen:
            raise ValueError("unapproved or duplicate managed resource")
        seen.add(address)
        if address == INSTANCE_ADDR and tainted:
            if (actions != ["delete", "create"] or
                item.get("action_reason") != "replace_because_tainted"):
                raise ValueError("only an existing host taint marker may be reconciled")
        elif address in EXISTING:
            if actions != ["no-op"]:
                raise ValueError("existing resources may not be altered or replaced")
        else:
            if actions != ["create"] or not isinstance(change.get("after"), dict):
                raise ValueError("only missing stop IAM policy and schedule may be created")
            created[address] = change["after"]

    if seen != EXISTING | REMAINING or set(created) != REMAINING:
        raise ValueError("Terraform plan is not exactly two new resources")

    iam = created["aws_iam_role_policy.daily_stop[0]"]
    if (iam.get("name") != "only-stop-aitrading-paper"
        or iam.get("role") != "aitrading-paper-daily-stop"):
        raise ValueError("stop IAM role policy name/target mismatch")
    _parse_policy_json(iam.get("policy"))
    schedule = created["aws_scheduler_schedule.daily_stop[0]"]
    if (
        schedule.get("name") != "aitrading-paper-nightly-stop"
        or schedule.get("schedule_expression") != "cron(0 22 * * ? *)"
        or schedule.get("schedule_expression_timezone") != "Asia/Kolkata"
        or schedule.get("state") != "ENABLED"
    ):
        raise ValueError("stop schedule name/time/enablement mismatch")
    ftw = schedule.get("flexible_time_window")
    if not isinstance(ftw, list) or len(ftw) != 1 or ftw[0].get("mode") != "OFF":
        raise ValueError("nightly stop must not use flexible time window")
    targets = schedule.get("target")
    if not isinstance(targets, list) or len(targets) != 1:
        raise ValueError("missing single exact schedule target")
    target = targets[0]
    if (target.get("arn") != "arn:aws:scheduler:::aws-sdk:ec2:stopInstances"
        or target.get("role_arn") != f"arn:aws:iam::{ACCOUNT}:role/aitrading-paper-daily-stop"):
        raise ValueError("scheduled action is not a project-scoped EC2 stop")
    input_str = target.get("input")
    if input_str is not None:
        if not isinstance(input_str, str) or json.loads(input_str) != {"InstanceIds": [INSTANCE_ID]}:
            raise ValueError("scheduled stop must target only the existing PAPER instance")


def main(argv: list[str]) -> int:
    try:
        if len(argv) != 3 or argv[1] not in ("request", "state", "host-state", "final"):
            raise ValueError("invalid validation mode")
        mode, path = argv[1], Path(argv[2])
        max_size = 30 * 1024 * 1024 if mode in ("host-state", "final") else 4096
        if path.is_symlink() or not path.is_file() or path.stat().st_size > max_size:
            raise ValueError("invalid input file")
        source = path.read_text(encoding="utf-8")
        if mode == "request":
            validate_request(json.loads(source))
        elif mode == "state":
            validate_state_addresses(source)
        elif mode == "host-state":
            validate_stopped_host_state(json.loads(source))
        else:
            validate_plan(json.loads(source), tainted=False)
    except (ValueError, TypeError, KeyError, AttributeError, OSError, json.JSONDecodeError):
        print("PAPER exact stopped EC2 recovery BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("Verified exact stopped PAPER EC2 state-recovery scope (no LIVE orders)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
