#!/usr/bin/env python3
"""Verify a single tainted PAPER VPC and otherwise immutable partial-apply state.

This guard authorizes neither Terraform destroy nor any AWS write. It can only
recognize the precise VPC replacement Terraform proposes because the first
create operation failed after AWS created the VPC. The runner may remove the
taint marker only after the real VPC settings and state were independently
verified, and only then generate a new create-only plan for apply.
"""
from __future__ import annotations

import copy
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("paper_resume_verify", ROOT / "verify_resume_apply.py")
assert spec and spec.loader
resume = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resume)

EXPECTED_REQUEST = {
    **resume.REQUEST,
    "kind": "ec2-paper-taint-reconciliation",
    "operation": "untaint-and-resume-once",
    "confirmation": "RECONCILE_AUDITED_TAINTED_VPC_AND_RESUME_PAPER_4GB",
}
VPC = "aws_vpc.paper[0]"


def validate_request(request: object) -> None:
    if not isinstance(request, dict) or request != EXPECTED_REQUEST:
        raise ValueError("invalid one-time tainted VPC reconciliation request")


def validate_tainted_plan(plan: object) -> None:
    if not isinstance(plan, dict) or not isinstance(plan.get("resource_changes"), list):
        raise ValueError("invalid Terraform pre-reconciliation plan")
    p = copy.deepcopy(plan)
    matches = [item for item in p["resource_changes"]
               if isinstance(item, dict) and item.get("address") == VPC]
    if len(matches) != 1:
        raise ValueError("missing exact VPC in plan")
    item = matches[0]
    change = item.get("change")
    if (item.get("mode") != "managed" or not isinstance(change, dict)
        or change.get("actions") != ["delete", "create"]
        or item.get("action_reason") != "replace_because_tainted"):
        raise ValueError("VPC is not replaced solely due to Terraform taint")
    # Simulate untaint only to verify that every remaining change already
    # passes the locked eight-create gate. Terraform will independently replan
    # after actual untaint and require this exact safe pattern.
    change["actions"] = ["no-op"]
    item.pop("action_reason", None)
    resume.validate_plan(p)


def main(argv: list[str]) -> int:
    try:
        if len(argv) != 3 or argv[1] not in ("request", "plan"):
            raise ValueError("invalid verification mode")
        file = Path(argv[2])
        maximum = (resume.MAX_REQUEST_BYTES if argv[1] == "request"
                   else resume.MAX_PLAN_BYTES)
        if file.is_symlink() or not file.is_file() or file.stat().st_size > maximum:
            raise ValueError("invalid local file")
        data = json.loads(file.read_text(encoding="utf-8"))
        if argv[1] == "request":
            validate_request(data)
        else:
            validate_tainted_plan(data)
    except (OSError, ValueError, KeyError, TypeError, AttributeError,
            json.JSONDecodeError):
        print("PAPER tainted VPC state reconciliation BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("Tainted PAPER VPC replacement is solely a state marker; new AWS writes not yet authorized")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
