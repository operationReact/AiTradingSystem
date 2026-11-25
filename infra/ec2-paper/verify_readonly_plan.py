"""Reject a non-PAPER, cross-project or destructive Terraform review plan.

Review-only. The GitHub OIDC role has NO AWS create/write permissions and
cannot mutate Terraform state (S3 GetObject only, -lock=false).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ACCOUNT = "098859530205"
EXPECTED = {
    "kind": "ec2-paper-plan",
    "account": ACCOUNT,
    "region": "ap-south-1",
    "target": "aitradingsystem-paper-ec2",
    "operation": "plan-only",
    "confirmation": "REVIEW_PAPER_EC2_PLAN_NO_APPLY",
}
ADDRESSES = frozenset({
    "aws_vpc.paper[0]",
    "aws_internet_gateway.paper[0]",
    "aws_subnet.paper[0]",
    "aws_route_table.paper[0]",
    "aws_route_table_association.paper[0]",
    "aws_security_group.paper[0]",
    "aws_iam_role.instance_ssm[0]",
    "aws_iam_role_policy_attachment.ssm_core[0]",
    "aws_iam_instance_profile.paper[0]",
    "aws_instance.paper[0]",
    "aws_iam_role.daily_stop[0]",
    "aws_iam_role_policy.daily_stop[0]",
    "aws_scheduler_schedule.daily_stop[0]",
})
MAX_BYTES = 20 * 1024 * 1024


def verify_request(payload: object) -> None:
    if not isinstance(payload, dict) or payload != EXPECTED:
        raise ValueError("unapproved EC2 PAPER Terraform plan request")


def verify_plan(payload: object) -> None:
    if not isinstance(payload, dict):
        raise ValueError("Terraform plan JSON not an object")
    changes = payload.get("resource_changes")
    if not isinstance(changes, list) or len(changes) != len(ADDRESSES):
        raise ValueError("unexpected EC2 PAPER Terraform resource count")
    seen: dict[str, dict] = {}
    for item in changes:
        if not isinstance(item, dict):
            raise ValueError("unrecognized Terraform resource")
        addr, mode = item.get("address"), item.get("mode")
        change = item.get("change")
        if addr not in ADDRESSES or addr in seen or mode != "managed":
            raise ValueError("unauthorized Terraform resource address")
        if not isinstance(change, dict) or change.get("actions") != ["create"]:
            raise ValueError("Terraform plan contains non-creation/destructive change")
        after = change.get("after")
        if not isinstance(after, dict):
            raise ValueError("missing Terraform after-state")
        seen[addr] = after
    if set(seen) != ADDRESSES:
        raise ValueError("incomplete Terraform PAPER resource graph")
    ec2 = seen["aws_instance.paper[0]"]
    if (
        ec2.get("instance_type") != "t4g.medium"
        or ec2.get("instance_initiated_shutdown_behavior") != "stop"
        or ec2.get("disable_api_termination") is not True
        or ec2.get("associate_public_ip_address") is not True
    ):
        raise ValueError("EC2 budget or termination configuration mismatch")
    metadata = ec2.get("metadata_options")
    if not isinstance(metadata, list) or len(metadata) != 1 or metadata[0].get("http_tokens") != "required":
        raise ValueError("EC2 IMDSv2 posture mismatch")
    disks = ec2.get("root_block_device")
    if not isinstance(disks, list) or len(disks) != 1:
        raise ValueError("missing protected EBS disk")
    disk = disks[0]
    if not (disk.get("encrypted") is True and disk.get("volume_type") == "gp3"
            and 30 <= disk.get("volume_size", 0) <= 50
            and disk.get("delete_on_termination") is False):
        raise ValueError("EBS encryption, price or persistence mismatch")
    stop = seen["aws_scheduler_schedule.daily_stop[0]"]
    if stop.get("schedule_expression_timezone") != "Asia/Kolkata":
        raise ValueError("unexpected EC2 stop timezone")
    if stop.get("schedule_expression") != "cron(0 22 * * ? *)" or stop.get("state") != "ENABLED":
        raise ValueError("EC2 stop-only schedule not enabled at 22:00 IST")
    rules = seen["aws_security_group.paper[0]"].get("ingress")
    if rules not in ([], None):
        raise ValueError("public/private inbound EC2 security group rule present")


def main(args: list[str]) -> int:
    try:
        if len(args) != 3 or args[1] not in ("request", "plan"):
            raise ValueError("invalid mode")
        path = Path(args[2])
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
            raise ValueError("missing, symlinked or oversized review artifact")
        obj = json.loads(path.read_text(encoding="utf-8"))
        if args[1] == "request":
            verify_request(obj)
        else:
            verify_plan(obj)
    except (OSError, ValueError, TypeError, AttributeError, json.JSONDecodeError):
        print("EC2 PAPER read-only Terraform review BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("EC2 PAPER read-only Terraform review verified; NO APPLY AUTHORIZED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
