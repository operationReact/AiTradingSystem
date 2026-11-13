#!/usr/bin/env python3
"""Idempotent manual GitHub Actions control of one existing PAPER EC2 host.

Never provisions, terminates, reboots, modifies networking or triggers orders.
Concurrent requests are serialized by the GitHub workflow queue. AWS-side
state is re-read before each possible mutation, making repeated clicks safe.
"""
from __future__ import annotations

import datetime
import json
import os
import re
import subprocess
import sys
import time
from zoneinfo import ZoneInfo

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
VPC = "vpc-04712728d857bb782"
SUBNET = "subnet-071ee24263e10fe7c"
PROFILE = f"arn:aws:iam::{ACCOUNT}:instance-profile/aitrading-paper-ec2-ssm"
SCHEDULE = "aitrading-paper-nightly-stop"
STOP_ROLE = f"arn:aws:iam::{ACCOUNT}:role/aitrading-paper-daily-stop"
STOP_ACTION = "arn:aws:scheduler:::aws-sdk:ec2:stopInstances"
TAGS = {"Application": "ai-trading-system", "Environment": "paper",
        "Name": "aitradingsystem-paper-ec2"}
MAX_POLLS = 90
POLL_SECONDS = 8
OPERATIONS = {"status", "start", "stop"}


class ControlError(RuntimeError):
    """Safe-to-display reason a manual control run cannot proceed."""


class AwsError(ControlError):
    def __init__(self, code: str):
        self.code = code
        super().__init__(f"AWS operation failed: {code}")


def aws_json(*args: str) -> dict:
    # All arguments are internal constants; user input is NEVER passed to AWS.
    cmd = ["aws", *args, "--region", REGION, "--output", "json",
           "--no-cli-pager"]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True,
                                timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ControlError("AWS CLI unavailable or timed out") from exc
    if result.returncode != 0:
        # Do not echo raw stderr, environment, credentials or policy documents.
        match = re.search(r"An error occurred \(([A-Za-z0-9_.-]+)\)",
                          result.stderr)
        raise AwsError(match.group(1) if match else "unclassified")
    try:
        output = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise ControlError("AWS returned invalid JSON") from exc
    if not isinstance(output, dict):
        raise ControlError("Unexpected AWS response format")
    return output


def verify_account() -> None:
    identity = aws_json("sts", "get-caller-identity")
    if identity.get("Account") != ACCOUNT:
        raise ControlError("Incorrect AWS account; no action permitted")
    if os.environ.get("AWS_REGION") != REGION:
        raise ControlError("Incorrect AWS region; no action permitted")


def describe() -> str:
    data = aws_json("ec2", "describe-instances", "--instance-ids", INSTANCE)
    reservations = data.get("Reservations", [])
    if (not isinstance(reservations, list) or len(reservations) != 1
        or not isinstance(reservations[0].get("Instances"), list)
        or len(reservations[0]["Instances"]) != 1):
        raise ControlError("Expected exactly one known EC2 instance")
    item = reservations[0]["Instances"][0]
    tags = {x.get("Key"): x.get("Value") for x in item.get("Tags", [])
            if isinstance(x, dict)}
    profile = item.get("IamInstanceProfile", {})
    if (item.get("InstanceId") != INSTANCE
        or item.get("InstanceType") != "t4g.medium"
        or item.get("VpcId") != VPC
        or item.get("SubnetId") != SUBNET
        or item.get("MetadataOptions", {}).get("HttpTokens") != "required"
        or profile.get("Arn") != PROFILE
        or any(tags.get(k) != v for k, v in TAGS.items())):
        raise ControlError("EC2 identity, size, VPC, profile or PAPER tags changed")
    state = item.get("State", {}).get("Name")
    if state not in {"pending", "running", "stopping", "stopped"}:
        raise ControlError(f"Unsafe or unsupported EC2 state: {state}")
    return state


def verify_schedule() -> str:
    data = aws_json("scheduler", "get-schedule", "--name", SCHEDULE,
                    "--group-name", "default")
    target = data.get("Target", {})
    try:
        actual_ids = json.loads(target.get("Input", "null"))
    except (TypeError, ValueError) as exc:
        raise ControlError("Nightly stop target is malformed") from exc
    if (data.get("State") not in ("ENABLED", "DISABLED")
        or data.get("ScheduleExpression") != "cron(0 22 * * ? *)"
        or data.get("ScheduleExpressionTimezone") != "Asia/Kolkata"
        or target.get("Arn") != STOP_ACTION
        or target.get("RoleArn") != STOP_ROLE
        or actual_ids != {"InstanceIds": [INSTANCE]}):
        raise ControlError("10 PM IST stop schedule missing or unsafe")
    state = data["State"]
    print(f"Daily 22:00 Asia/Kolkata stop schedule: {state} and exact-target verified", flush=True)
    return state


def apply(operation: str) -> tuple[str, str]:
    if operation not in OPERATIONS:
        raise ControlError("Choose only status, start, or stop")
    verify_account()
    if operation == "status":
        state = describe()
        try:
            verify_schedule()
        except ControlError as exc:
            print(f"WARNING: stop scheduler unverified ({exc})", flush=True)
        print(f"PAPER EC2 state: {state}; no AWS changes made", flush=True)
        return state, "Read-only status"

    requested = "running" if operation == "start" else "stopped"
    submitted = False
    for attempt in range(MAX_POLLS):
        state = describe()
        print(f"Check {attempt + 1}: instance is {state}", flush=True)
        if state == requested:
            detail = ("Transition complete" if submitted
                      else f"Already {requested}; safe no-op")
            print(detail, flush=True)
            return state, detail

        if submitted:
            # Never reissue an AWS mutation in the same run. In particular
            # don't restart if Scheduler independently stopped the instance.
            if state == ("stopped" if operation == "start" else "running"):
                raise ControlError("Instance moved opposite to requested direction after submission")
        elif state == ("stopped" if operation == "start" else "running"):
            if operation == "start":
                # Verify the exact schedule identity, but allow its owner-approved
                # DISABLED state so a continuous history host can be restarted.
                stop_state = verify_schedule()
                hour = datetime.datetime.now(ZoneInfo("Asia/Kolkata")).hour
                if stop_state == "ENABLED":
                    print("WARNING: nightly 22 IST stop remains ENABLED; continuous history will stop", flush=True)
                    if hour >= 22:
                        print("WARNING: next automatic stop is tomorrow at 22 IST", flush=True)
            api_action = "start-instances" if operation == "start" else "stop-instances"
            try:
                aws_json("ec2", api_action, "--instance-ids", INSTANCE)
                submitted = True
                print(f"Sent exact-instance {operation} request once", flush=True)
            except AwsError as exc:
                if exc.code not in {"IncorrectInstanceState", "IncorrectInstanceStateException"}:
                    raise
                # A console action, Scheduler or another process won the
                # race. Re-read the state before ever taking further action.
                print("Instance transitioned during request; rechecking AWS", flush=True)
        # pending or stopping are transition states; never call Start/Stop
        # prematurely. Queueing prevents two manual workflow mutations
        # concurrently, without overriding the independent 22 IST scheduler.
        if attempt < MAX_POLLS - 1:
            time.sleep(POLL_SECONDS)

    raise ControlError("EC2 transition did not reach its requested state within 12 minutes")


def main(argv: list[str]) -> int:
    action = argv[1] if len(argv) == 2 else ""
    state = "unverified"
    detail = ""
    try:
        state, detail = apply(action)
        code = 0
    except ControlError as exc:
        detail = str(exc)
        print(f"FAILED: {detail}", file=sys.stderr, flush=True)
        code = 2
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as output:
            output.write(
                "### PAPER EC2 manual lifecycle\n\n"
                f"- Operation: `{action}`\n"
                f"- Instance: `{INSTANCE}` ({REGION})\n"
                f"- Verified final state: `{state}`\n"
                f"- Result: {detail}\n"
                "- Daily 22:00 IST stop schedule remains independent of manual controls.\n"
            )
    return code


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
