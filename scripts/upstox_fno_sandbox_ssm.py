#!/usr/bin/env python3
"""Run the NON-FUNDED Upstox sandbox probe with an exact SSM SecureString.

The token exists only inside this process; it is never echoed, written to
GitHub outputs, inserted into CLI arguments or copied to a GitHub secret.
Do not use this runner for LIVE/PAPER customer authentication.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

import upstox_fno_sandbox_lifecycle as probe

PARAMETER = "/minttick/upstox/sandbox/access-token"
REGION = "ap-south-1"
REPOSITORY = "operationReact/AiTradingSystem"
_REQUIRED_CONTEXT = {
    "GITHUB_REPOSITORY": REPOSITORY,
    "GITHUB_REF": "refs/heads/dev",
    "GITHUB_EVENT_NAME": "workflow_dispatch",
    "SANDBOX_CONFIRMATION": "SANDBOX_ONLY",
}


class SandboxBootstrapError(Exception):
    """Fixed, non-sensitive reason code, never a provider or AWS exception."""


def load_sandbox_token() -> str:
    command = [
        "aws", "ssm", "get-parameter",
        "--region", REGION,
        "--name", PARAMETER,
        "--with-decryption",
        "--query", "Parameter.Value",
        "--output", "text",
        "--no-cli-pager",
    ]
    try:
        response = subprocess.run(
            command, capture_output=True, text=True, timeout=20, check=False
        )
    except (OSError, subprocess.TimeoutExpired) from None:
        raise SandboxBootstrapError("SANDBOX_SSM_UNAVAILABLE") from None
    if response.returncode != 0:
        # Never print AWS stderr; AWS errors may contain unexpected strings.
        raise SandboxBootstrapError("SANDBOX_SSM_UNAVAILABLE")
    token = response.stdout.rstrip("\r\n")
    # Reject whitespace/invalid bearer material and accidental CLI None text.
    if token == "None" or not re.fullmatch(r"[^\s]{20,4096}", token):
        raise SandboxBootstrapError("SANDBOX_SSM_TOKEN_INVALID")
    return token


def main(argv=None) -> int:
    if any(os.environ.get(name) != value for name, value in _REQUIRED_CONTEXT.items()):
        print("SANDBOX_WORKFLOW_CONTEXT_INVALID")
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instrument-key", required=True)
    parser.add_argument("--quantity", required=True, type=int)
    parser.add_argument("--price", required=True)
    parser.add_argument("--tick", required=True)
    args = parser.parse_args(argv)
    try:
        token = load_sandbox_token()
    except SandboxBootstrapError as error:
        print(str(error))
        return 2
    # Never export into a GitHub environment file or runner step output.
    previous = os.environ.get("UPSTOX_SANDBOX_TOKEN")
    os.environ["UPSTOX_SANDBOX_TOKEN"] = token
    try:
        return probe.main([
            "--execute-sandbox",
            "--instrument-key", args.instrument_key,
            "--quantity", str(args.quantity),
            "--price", args.price,
            "--tick", args.tick,
        ])
    finally:
        if previous is None:
            os.environ.pop("UPSTOX_SANDBOX_TOKEN", None)
        else:
            os.environ["UPSTOX_SANDBOX_TOKEN"] = previous


if __name__ == "__main__":
    sys.exit(main())
