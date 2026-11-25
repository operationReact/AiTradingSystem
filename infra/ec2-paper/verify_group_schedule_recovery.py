#!/usr/bin/env python3
"""Fail-closed AWS Scheduler trust repair for a stopped existing PAPER EC2.

12 managed resources already exist, including the stop-only IAM inline policy.
Only ONE in-place change to the dedicated Scheduler role's trust (exact
default schedule-group ARN) and ONE new enabled stop schedule are permitted.
No EC2 start, replacement, state untaint, LIVE broker action or deletion.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ACCOUNT = "098859530205"
INSTANCE = "i-09c01b470fb39b85a"
ROLE = "aitrading-paper-daily-stop"
ROLE_ARN = f"arn:aws:iam::{ACCOUNT}:role/{ROLE}"
GROUP_ARN = f"arn:aws:scheduler:ap-south-1:{ACCOUNT}:schedule-group/default"
OLD_SCHEDULE_ARN = f"arn:aws:scheduler:ap-south-1:{ACCOUNT}:schedule/default/aitrading-paper-nightly-stop"
ROLE_ADDRESS = "aws_iam_role.daily_stop[0]"
SCHEDULE_ADDRESS = "aws_scheduler_schedule.daily_stop[0]"
EXISTING = frozenset({
    "aws_vpc.paper[0]",
    "aws_internet_gateway.paper[0]",
    "aws_subnet.paper[0]",
    "aws_route_table.paper[0]",
    "aws_route_table_association.paper[0]",
    "aws_security_group.paper[0]",
    "aws_iam_role.instance_ssm[0]",
    ROLE_ADDRESS,
    "aws_iam_instance_profile.paper[0]",
    "aws_iam_role_policy_attachment.ssm_core[0]",
    "aws_instance.paper[0]",
    "aws_iam_role_policy.daily_stop[0]",
})
DATA = frozenset({
    "data.aws_caller_identity.current",
    "data.aws_ssm_parameter.al2023_arm64[0]",
})
REQUEST = {
    "kind": "ec2-paper-scheduler-group-trust-repair",
    "account": ACCOUNT,
    "region": "ap-south-1",
    "target": "aitradingsystem-paper-ec2",
    "instance_id": INSTANCE,
    "instance_type": "t4g.medium",
    "daily_stop_hour_ist": 22,
    "daily_stop_enabled": True,
    "operation": "repair-exact-stop-role-trust-and-create-schedule",
    "confirmation": "REPAIR_STOP_ROLE_GROUP_TRUST_EXISTING_PAPER_ONLY",
}


def validate_request(value: object) -> None:
    if not isinstance(value, dict) or value != REQUEST:
        raise ValueError("wrong one-time PAPER scheduler-trust recovery request")


def validate_state(value: str) -> None:
    addresses = [x.strip() for x in value.splitlines() if x.strip()]
    if len(addresses) != len(set(addresses)) or set(addresses) != EXISTING | DATA:
        raise ValueError("remote state must have 12 exact existing managed resources")


def _trust(policy: str, *, updated: bool) -> None:
    if not isinstance(policy, str):
        raise ValueError("unknown or malformed role trust policy")
    obj = json.loads(policy)
    if not isinstance(obj, dict) or len(obj.get("Statement", [])) != 1:
        raise ValueError("trust must have a single narrow statement")
    statement = obj["Statement"][0]
    if (statement.get("Effect") != "Allow"
        or statement.get("Principal") != {"Service": "scheduler.amazonaws.com"}
        or statement.get("Action") != "sts:AssumeRole"):
        raise ValueError("unexpected scheduler service trust")
    c = statement.get("Condition")
    if not isinstance(c, dict):
        raise ValueError("missing trust restrictions")
    expected = (
        {"StringEquals": {"aws:SourceAccount": ACCOUNT, "aws:SourceArn": GROUP_ARN}}
        if updated else
        {"StringEquals": {"aws:SourceAccount": ACCOUNT},
         "ArnEquals": {"aws:SourceArn": OLD_SCHEDULE_ARN}}
    )
    if c != expected:
        raise ValueError("scheduler trust must transition from exact old to exact new group")


def validate_plan(plan: object) -> None:
    if not isinstance(plan, dict) or not isinstance(plan.get("resource_changes"), list):
        raise ValueError("invalid Terraform plan")
    seen: set[str] = set()
    for item in plan["resource_changes"]:
        if not isinstance(item, dict) or not isinstance(item.get("change"), dict):
            raise ValueError("malformed Terraform resource change")
        addr, mode, change = item.get("address"), item.get("mode"), item["change"]
        actions = change.get("actions")
        if mode == "data":
            if addr not in DATA or actions not in (["read"], ["no-op"]):
                raise ValueError("unexpected data source changes")
            continue
        if mode != "managed" or addr not in EXISTING | {SCHEDULE_ADDRESS} or addr in seen:
            raise ValueError("unexpected or duplicate managed resource")
        seen.add(addr)
        if addr == ROLE_ADDRESS:
            if actions != ["update"]:
                raise ValueError("dedicated PAPER stop role must only update in-place")
            before, after = change.get("before"), change.get("after")
            if not isinstance(before, dict) or not isinstance(after, dict):
                raise ValueError("role trust change must be fully known")
            _trust(before.get("assume_role_policy"), updated=False)
            _trust(after.get("assume_role_policy"), updated=True)
            if {k:v for k,v in before.items() if k != "assume_role_policy"} != {
                k:v for k,v in after.items() if k != "assume_role_policy"
            }:
                raise ValueError("only assume_role_policy can change")
        elif addr in EXISTING:
            if actions != ["no-op"]:
                raise ValueError("existing AWS infrastructure must remain untouched")
        else:
            if actions != ["create"] or not isinstance(change.get("after"), dict):
                raise ValueError("only the missing exact nightly scheduler may be created")
            s = change["after"]
            window = s.get("flexible_time_window")
            # AWS provider 5.x can include the optional
            # maximum_window_in_minutes=null on mode=OFF. This field is
            # ignored by Scheduler for OFF; reject any nonzero window or
            # additional settings, so the scheduler remains exact.
            if (not isinstance(window,list) or len(window)!=1
                or not isinstance(window[0],dict)
                or window[0].get("mode")!="OFF"
                or set(window[0]) - {"mode","maximum_window_in_minutes"}
                or window[0].get("maximum_window_in_minutes") not in (None,0)):
                raise ValueError("flexible window is not strictly OFF")
            if (s.get("name") != "aitrading-paper-nightly-stop"
                or s.get("schedule_expression") != "cron(0 22 * * ? *)"
                or s.get("schedule_expression_timezone") != "Asia/Kolkata"
                or s.get("state") != "ENABLED"
                or s.get("group_name") not in (None,"default")):
                raise ValueError("schedule timing, group or activation mismatch")
            targets = s.get("target")
            if not isinstance(targets, list) or len(targets) != 1:
                raise ValueError("exactly one stop target required")
            t = targets[0]
            if (t.get("arn") != "arn:aws:scheduler:::aws-sdk:ec2:stopInstances"
                or t.get("role_arn") != ROLE_ARN
                or not isinstance(t.get("input"), str)
                or json.loads(t["input"]) != {"InstanceIds": [INSTANCE]}):
                raise ValueError("scheduler must only stop exact existing PAPER instance")
    if seen != EXISTING | {SCHEDULE_ADDRESS}:
        raise ValueError("plan must contain one role trust update and one schedule creation")


def main(argv: list[str]) -> int:
    try:
        if len(argv) != 3 or argv[1] not in ("request","state","plan"):
            raise ValueError("unknown mode")
        mode, file = argv[1], Path(argv[2])
        max_bytes = 30*1024*1024 if mode == "plan" else 4096
        if file.is_symlink() or not file.is_file() or file.stat().st_size > max_bytes:
            raise ValueError("invalid runner input")
        raw = file.read_text(encoding="utf-8")
        if mode == "request":
            validate_request(json.loads(raw))
        elif mode == "state":
            validate_state(raw)
        else:
            validate_plan(json.loads(raw))
    except ValueError as exc:
        # All explicit ValueErrors in this gate have constant, nonsecret
        # messages. Surface a static reason to avoid blind paid retries.
        reason = str(exc) if type(exc) is ValueError else "malformed-json"
        print("PAPER Scheduler recovery BLOCKED: "+reason,file=sys.stderr)
        return 2
    except (TypeError,KeyError,AttributeError,OSError):
        print("PAPER Scheduler recovery BLOCKED: malformed-input",file=sys.stderr)
        return 2
    print("Verified exact one-role-trust update and one stop-schedule creation; no EC2 action")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
