#!/usr/bin/env python3
"""V89 remote GitHub-only preflight of read-only inspection role declarations.

Only checks repository Actions VARIABLES (non-secret IAM role ARN strings).
Never contacts AWS, obtains GitHub OIDC tokens, lists IAM, tests actual permissions,
starts ECS or accepts a worker, model, market feed or capital authorization.
"""
from __future__ import annotations

import argparse
import json
import os
import re
from datetime import UTC, datetime
from pathlib import Path

REPO = "operationReact/AiTradingSystem"
REF = "refs/heads/dev"
ACCOUNT = "816344830716"
SHA40 = re.compile(r"[0-9a-f]{40}\Z")
ROLES = {
    "V73": (
        "AWS_READONLY_ROLE_ARN",
        "ai-trading-paper-inspection-readonly-dev",
    ),
    "V75": (
        "AWS_WORKER_READONLY_ROLE_ARN",
        "ai-trading-worker-image-readonly-dev",
    ),
    "V86": (
        "AWS_WORKER_TLS_READONLY_ROLE_ARN",
        "ai-trading-worker-tls-metadata-readonly-dev",
    ),
}
VERSION = "v4-github-operator-readonly-role-format.v1"


def evaluate(values: dict, repository: str, ref: str, sha: str) -> dict:
    blockers: set[str] = set()
    origin_ok = (
        repository == REPO
        and ref == REF
        and isinstance(sha, str)
        and SHA40.fullmatch(sha) is not None
    )
    if not origin_ok:
        blockers.add("NON_DEV_OR_UNTRUSTED_RUN_SOURCE")

    declarations_ok = {}
    supplied = []
    for phase, (name, role_name) in ROLES.items():
        value = values.get(name)
        expected = f"arn:aws:iam::{ACCOUNT}:role/{role_name}"
        ready = type(value) is str and value == expected
        declarations_ok[phase] = ready
        if not ready:
            blockers.add(f"{phase}_READONLY_ROLE_VARIABLE_MISSING_OR_UNEXPECTED")
        else:
            supplied.append(value)
    distinct = len(supplied) == len(ROLES) and len(set(supplied)) == len(ROLES)
    if not distinct:
        blockers.add("ROLE_IDENTITIES_NOT_DISTINCT")

    ready = origin_ok and all(declarations_ok.values()) and distinct
    return {
        "contract_version": VERSION,
        "checked_at_utc": datetime.now(UTC).isoformat(),
        "expected_commit_sha": sha if origin_ok else "invalid",
        "expected_account": ACCOUNT,
        "repository_and_dev_ref_verified": origin_ok,
        "v73_backend_role_variable_exact": declarations_ok["V73"],
        "v75_worker_image_role_variable_exact": declarations_ok["V75"],
        "v86_worker_tls_role_variable_exact": declarations_ok["V86"],
        "separate_inspection_role_declarations": distinct,
        "github_role_variable_format_only_verified": ready,
        "aws_iam_role_existence_verified": False,
        "oidc_assume_role_succeeded": False,
        "aws_iam_permissions_verified": False,
        "deployed_paper_backend_verified": False,
        "private_https_network_verified": False,
        "worker_model_artifacts_verified": False,
        "paper_shadow_soak_accepted": False,
        "live_execution_enabled": False,
        "can_authorize_capital": False,
        "verdict": "GITHUB_INSPECTION_ROLE_VARIABLE_FORMAT_ONLY" if ready else "BLOCKED",
        "blocker_codes": sorted(blockers),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="V89 remote GitHub read-only role preflight")
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        role_values = {
            name: os.environ.get(name)
            for name, _ in ROLES.values()
        }
        report = evaluate(
            role_values,
            repository=os.environ.get("GITHUB_REPOSITORY", ""),
            ref=os.environ.get("GITHUB_REF", ""),
            sha=args.expected_sha,
        )
    except Exception:
        report = {
            "contract_version": VERSION,
            "verdict": "BLOCKED",
            "github_role_variable_format_only_verified": False,
            "aws_iam_role_existence_verified": False,
            "live_execution_enabled": False,
            "can_authorize_capital": False,
            "blocker_codes": ["INVALID_OPERATOR_ROLE_VARIABLE_INPUT"],
        }
    args.output.write_text(
        json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    # Never print role ARN strings or any environment values in logs or outputs.
    print("V89 GitHub read-only role declaration preflight:", report["verdict"])
    print("This is not an AWS IAM/OIDC or runtime acceptance check")
    return 0 if report["github_role_variable_format_only_verified"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
