#!/usr/bin/env python3
"""Inspect MintTick's exact existing PAPER EC2 public egress; never modifies AWS.

Reports only IPv4 addresses, EIP-association presence, and an explicit stability
assessment. The Lambda-backed owner broker is NOT covered by the EC2 IP.
"""
import ipaddress
import json
import os
import subprocess
import sys
import time

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
VPC = "vpc-04712728d857bb782"
SUBNET = "subnet-071ee24263e10fe7c"


def aws(*args):
    proc = subprocess.run(
        ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"],
        check=False, capture_output=True, text=True, timeout=35,
    )
    if proc.returncode != 0:
        # CLI stderr may contain unexpected private metadata; never echo it.
        raise RuntimeError("READ_ONLY_AWS_QUERY_FAILED")
    try:
        result = json.loads(proc.stdout)
    except (ValueError, TypeError):
        raise RuntimeError("INVALID_AWS_JSON") from None
    if not isinstance(result, dict):
        raise RuntimeError("INVALID_AWS_RESPONSE")
    return result


def public_ipv4(raw):
    if not isinstance(raw, str):
        return None
    try:
        ip = ipaddress.IPv4Address(raw.strip())
    except ipaddress.AddressValueError:
        return None
    return str(ip) if ip.is_global else None


def main():
    if os.environ.get("AWS_REGION") != REGION or os.environ.get("GITHUB_REPOSITORY") != "operationReact/AiTradingSystem":
        raise RuntimeError("UNAUTHORIZED_ENVIRONMENT")
    caller = aws("sts", "get-caller-identity")
    if caller.get("Account") != ACCOUNT:
        raise RuntimeError("INCORRECT_AWS_ACCOUNT")
    details = aws("ec2", "describe-instances", "--instance-ids", INSTANCE)
    reservations = details.get("Reservations") or []
    if len(reservations) != 1 or len(reservations[0].get("Instances") or []) != 1:
        raise RuntimeError("EXACT_INSTANCE_NOT_FOUND")
    node = reservations[0]["Instances"][0]
    if node.get("InstanceId") != INSTANCE or node.get("VpcId") != VPC or node.get("SubnetId") != SUBNET:
        raise RuntimeError("INCORRECT_EC2_IDENTITY")
    if node.get("State", {}).get("Name") != "running":
        raise RuntimeError("EXACT_EC2_IS_NOT_RUNNING")

    ec2_public = public_ipv4(node.get("PublicIpAddress"))
    nic_associations = [
        nic.get("Association", {}) for nic in node.get("NetworkInterfaces", [])
        if isinstance(nic, dict)
    ]
    elastic = any(assoc.get("AllocationId") and public_ipv4(assoc.get("PublicIp"))
                  for assoc in nic_associations)
    print("INSTANCE:", INSTANCE)
    print("EC2_PUBLIC_INTERFACE_IP:", ec2_public or "NONE")
    print("EC2_ELASTIC_IP_ASSOCIATION:", "PRESENT" if elastic else "ABSENT")

    # Execute a fixed read-only HTTPS request on the existing EC2 host via SSM.
    # No IAM role/parameter values, host environment, logs, or tokens are read.
    request = aws(
        "ssm", "send-command",
        "--document-name", "AWS-RunShellScript",
        "--instance-ids", INSTANCE,
        "--comment", "MintTick public egress IP observation only (read-only)",
        "--parameters", json.dumps({"commands": [
            "curl --silent --fail --connect-timeout 4 --max-time 12 https://checkip.amazonaws.com"
        ]}),
    )
    command = request.get("Command", {}).get("CommandId")
    if not isinstance(command, str) or len(command) < 20:
        raise RuntimeError("SSM_COMMAND_NOT_CREATED")
    observed = None
    for _ in range(20):
        time.sleep(2)
        item = aws("ssm", "get-command-invocation",
                   "--command-id", command, "--instance-id", INSTANCE)
        state = item.get("Status")
        if state == "Success":
            observed = public_ipv4(item.get("StandardOutputContent"))
            break
        if state in ("Failed", "Cancelled", "TimedOut", "Cancelling"):
            raise RuntimeError("SSM_PUBLIC_EGRESS_PROBE_FAILED")
    if not observed:
        raise RuntimeError("PUBLIC_EGRESS_UNCONFIRMED")
    print("OBSERVED_EC2_OUTBOUND_IPV4:", observed)
    if ec2_public == observed and elastic:
        label = "EC2_ELASTIC_IP_CONFIRMED"
    elif ec2_public == observed:
        label = "EC2_PUBLIC_IP_NOT_ELASTIC_CAN_CHANGE_ON_RESTART"
    else:
        label = "NAT_OR_OTHER_EGRESS_STABILITY_NOT_CONFIRMED"
    print("EGRESS_IP_STABILITY:", label)
    print("OWNER_SSM_LAMBDA_EGRESS:", "SEPARATE_NO_STATIC_IP_CONFIRMED")
    print("NOTE: Do not register this as a static IP unless EIP/NAT allocation is confirmed.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
        print("EGRESS_INSPECTION_FAILED:", str(exc), file=sys.stderr)
        sys.exit(2)
