#!/usr/bin/env python3
"""Strict exact-host PAPER deployment gate and SSM remote execution client.

GitHub dev-only one-file request -> audited AWS identity/schedule/EC2/SSM ->
AWS-RunShellScript command targeting one existing EC2. A new AWS role and
encrypted artifact bucket must be provisioned in CloudFormation before use.
No EC2 start/stop, Terraform apply, SSH or LIVE broker functionality.
"""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
VPC = "vpc-04712728d857bb782"
SUBNET = "subnet-071ee24263e10fe7c"
ROLE = "aitrading-paper-ec2-ssm-deploy"
PROFILE = f"arn:aws:iam::{ACCOUNT}:instance-profile/aitrading-paper-ec2-ssm"
STOP_ROLE = f"arn:aws:iam::{ACCOUNT}:role/aitrading-paper-daily-stop"
STOP_TARGET = "arn:aws:scheduler:::aws-sdk:ec2:stopInstances"
TAGS = {"Application": "ai-trading-system", "Environment": "paper",
        "Name": "aitradingsystem-paper-ec2"}
REQUESTS = {
    "preflight": ("infra/ec2-paper/requests/ssm-preflight.json",
                  "INSPECT_EXISTING_PAPER_HOST_ONLY"),
    "deploy": ("infra/ec2-paper/requests/ssm-release.json",
               "DEPLOY_EXACT_SHA_PAPER_LOCAL_ONLY"),
}


class Rejected(RuntimeError):
    """A deployment precondition was not met."""


def validate_request(action: str, document: object) -> None:
    if action not in REQUESTS or not isinstance(document, dict):
        raise Rejected("unknown SSM request")
    expected = {
        "kind": "ec2-paper-ssm-release-v1",
        "operation": action,
        "account": ACCOUNT,
        "region": REGION,
        "instance_id": INSTANCE,
        "instance_type": "t4g.medium",
        "environment": "PAPER",
        "confirmation": REQUESTS[action][1],
    }
    if document != expected:
        raise Rejected("exact PAPER one-file deployment approval mismatch")


def aws_json(*args: str) -> dict:
    command = ["aws", *args, "--region", REGION, "--output", "json",
               "--no-cli-pager"]
    try:
        result = subprocess.run(command, check=False, capture_output=True,
                                text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Rejected("AWS CLI unavailable or AWS request timed out") from exc
    if result.returncode:
        raise Rejected("AWS request rejected; inspect AWS policies and SSM status")
    try:
        parsed = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise Rejected("AWS response was not valid JSON") from exc
    if not isinstance(parsed, dict):
        raise Rejected("Unexpected AWS API response")
    return parsed


def verify_aws() -> None:
    if os.environ.get("AWS_REGION") != REGION:
        raise Rejected("wrong AWS region")
    actor = aws_json("sts", "get-caller-identity")
    arn = actor.get("Arn", "")
    if (actor.get("Account") != ACCOUNT
        or not isinstance(arn, str)
        or not arn.startswith(f"arn:aws:sts::{ACCOUNT}:assumed-role/{ROLE}/")):
        raise Rejected("not using exact dev-only PAPER SSM deployment role")

    data = aws_json("ec2", "describe-instances", "--instance-ids", INSTANCE)
    reservations = data.get("Reservations", [])
    if not isinstance(reservations, list) or len(reservations) != 1:
        raise Rejected("instance inventory not exactly one reservation")
    instances = reservations[0].get("Instances", [])
    if not isinstance(instances, list) or len(instances) != 1:
        raise Rejected("instance inventory not exactly one host")
    inst = instances[0]
    tags = {x.get("Key"): x.get("Value") for x in inst.get("Tags", [])
            if isinstance(x, dict)}
    if (inst.get("InstanceId") != INSTANCE
        or inst.get("InstanceType") not in {"t4g.medium", "m6g.medium", "m7g.medium"}
        or inst.get("VpcId") != VPC or inst.get("SubnetId") != SUBNET
        or inst.get("MetadataOptions", {}).get("HttpTokens") != "required"
        or inst.get("IamInstanceProfile", {}).get("Arn") != PROFILE
        or any(tags.get(k) != v for k, v in TAGS.items())):
        raise Rejected("instance identity, profile, network or paper tags drifted")
    if inst.get("State", {}).get("Name") != "running":
        raise Rejected("EC2 must first be explicitly started via manual GitHub control")

    schedule = aws_json("scheduler", "get-schedule", "--group-name", "default",
                        "--name", "aitrading-paper-nightly-stop")
    target = schedule.get("Target", {})
    try:
        ids = json.loads(target.get("Input", "null"))
    except (ValueError, TypeError) as exc:
        raise Rejected("scheduled stop target not parseable") from exc
    if (schedule.get("State") not in ("ENABLED", "DISABLED")
        or schedule.get("ScheduleExpression") != "cron(0 22 * * ? *)"
        or schedule.get("ScheduleExpressionTimezone") != "Asia/Kolkata"
        or target.get("RoleArn") != STOP_ROLE
        or target.get("Arn") != STOP_TARGET
        or ids != {"InstanceIds": [INSTANCE]}):
        raise Rejected("exact 22 IST EC2 stop schedule identity or state invalid")

    managed = aws_json("ssm", "describe-instance-information", "--filters",
                       f"Key=InstanceIds,Values={INSTANCE}")
    found = managed.get("InstanceInformationList", [])
    if len(found) != 1 or found[0].get("InstanceId") != INSTANCE:
        raise Rejected("SSM managed-node registration absent for exact EC2")
    if found[0].get("PingStatus") != "Online":
        raise Rejected("SSM agent not Online; inspect cloud-init and instance role")
    print("PAPER EC2 running; SSM Online; exact nightly stop schedule verified (state=" + schedule["State"] + ")", flush=True)


def make_command(action: str, sha: str = "", digest: str = "") -> str:
    if action not in REQUESTS:
        raise Rejected("bad SSM action")
    if action == "deploy" and (not re.fullmatch("[0-9a-f]{40}", sha)
                              or not re.fullmatch("[0-9a-f]{64}", digest)):
        raise Rejected("invalid immutable release SHA or artifact digest")
    path = Path(__file__).parent / (
        "ssm_preflight.sh" if action == "preflight" else "ssm_apply_release.sh"
    )
    data = path.read_bytes()
    if len(data) > 12000:
        raise Rejected("remote command too large")
    encoded = base64.b64encode(data).decode("ascii")
    # The runner's exact reviewed source is the only remote script content.
    # User input is never interpolated into a shell command.
    argv = f" {sha} {digest}" if action == "deploy" else ""
    return f"printf '%s' '{encoded}' | base64 -d | bash -s --{argv}"


def send_command(action: str, sha: str = "", digest: str = "") -> None:
    command = make_command(action, sha, digest)
    params = json.dumps({"commands": [command]})
    request = aws_json(
        "ssm", "send-command", "--document-name", "AWS-RunShellScript",
        "--instance-ids", INSTANCE, "--timeout-seconds", "3600",
        "--comment", f"Exact PAPER EC2 {action} / GitHub reviewed dev",
        "--parameters", params,
    )
    command_id = request.get("Command", {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", command_id):
        raise Rejected("SSM did not return a valid CommandId")
    print(f"SSM {action} submitted to exact PAPER EC2 (no SSH)", flush=True)
    # Do not print remote StandardOutput/StandardError: the instance owns local
    # application credentials. Only status is permitted in runner logs.
    max_checks = 180 if action == "deploy" else 45
    for i in range(max_checks):
        time.sleep(8)
        try:
            result = aws_json("ssm", "get-command-invocation",
                              "--command-id", command_id,
                              "--instance-id", INSTANCE)
        except Rejected:
            # Brief eventual-consistency window immediately after SendCommand.
            if i < 5:
                continue
            raise
        status = result.get("Status")
        if status == "Success":
            print(f"SSM {action} completed successfully; no cloud changes outside exact host", flush=True)
            return
        if status in {"Cancelled", "Failed", "TimedOut", "Cancelling"}:
            # Permit only explicitly enumerated stage identifiers from the
            # reviewed deploy script, NEVER raw SSM stdout/stderr (may contain
            # instance secrets, database details, or transient credentials).
            if action == "deploy":
                stages = {
                    "host_precheck", "artifact_download",
                    "artifact_integrity", "archive_validation",
                    "image_load", "host_activation",
                    "history_precheck", "history_activation",
                }
                combined = (str(result.get("StandardOutputContent") or "") +
                            "\n" + str(result.get("StandardErrorContent") or ""))
                # Exact allowlist for the deliberately categorized S3 client
                # error; raw AWS output is never included in run logs.
                download_errors = {
                    "access_denied", "object_missing", "disk_full",
                    "credentials_unavailable", "network_unavailable", "unknown",
                }
                issue = re.search(r"MINTTICK_S3_DOWNLOAD_FAILURE:([a-z_]+)", combined)
                if issue and issue.group(1) in download_errors:
                    raise Rejected(f"SSM artifact download failed: {issue.group(1)}")
                match = re.search(r"MINTTICK_DEPLOY_FAILED_STAGE:([a-z_]+)", combined)
                if match and match.group(1) in stages:
                    raise Rejected(f"SSM deploy failed at safe stage: {match.group(1)}")
            raise Rejected(f"SSM {action} did not pass host validation ({status})")
        if status not in {"Pending", "InProgress", "Delayed"}:
            raise Rejected("SSM returned an unexpected status")
    raise Rejected(f"SSM {action} timed out before connected acceptance")


def main(argv: list[str]) -> int:
    try:
        if len(argv) < 3 or argv[1] not in {"request", "run"}:
            raise Rejected("bad mode")
        if argv[1] == "request":
            if len(argv) != 4:
                raise Rejected("request arguments missing")
            action, path = argv[2], Path(argv[3])
            if (action not in REQUESTS or path.is_symlink() or not path.is_file()
                or path.stat().st_size > 4096
                or path.as_posix() != REQUESTS[action][0]):
                raise Rejected("not an exact approved request file")
            validate_request(action, json.loads(path.read_text(encoding="utf-8")))
        else:
            if len(argv) not in {3,5}:
                raise Rejected("bad SSM run arguments")
            action = argv[2]
            if action not in REQUESTS or (action == "deploy") != (len(argv) == 5):
                raise Rejected("bad SSM run action")
            verify_aws()
            send_command(action, *(argv[3:] if action == "deploy" else []))
    except (Rejected, ValueError, TypeError, OSError) as exc:
        print(f"BLOCKED: {exc if isinstance(exc, Rejected) else 'invalid input'}",
              file=sys.stderr)
        return 2
    print("Exact PAPER SSM operation validated", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
