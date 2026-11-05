"""Offline safety checks for exact one-VPC taint-only Terraform state recovery."""
from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TF = ROOT / "infra/ec2-paper"
spec = importlib.util.spec_from_file_location(
    "taint_reconciliation", TF / "verify_tainted_resume.py"
)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def sample_plan() -> dict:
    resources = []
    for address in sorted(gate.resume.locked.gate.ADDRESSES):
        existing = address in gate.resume.EXISTING
        actions = ["no-op"] if existing else ["create"]
        after = {}
        if address in gate.resume.locked.TAGGED_CREATE_RESOURCES:
            after["tags"] = gate.resume.locked.REQUIRED_TAGS.copy()
        if address == "aws_instance.paper[0]":
            after.update({
                "instance_type": "t4g.medium",
                "disable_api_termination": True,
                "instance_initiated_shutdown_behavior": "stop",
                "associate_public_ip_address": True,
                "metadata_options": [{"http_tokens": "required"}],
                "root_block_device": [{
                    "volume_size": 35, "volume_type": "gp3", "encrypted": True,
                    "delete_on_termination": False,
                }],
            })
        if address == "aws_security_group.paper[0]":
            after["ingress"] = []
        if address == "aws_scheduler_schedule.daily_stop[0]":
            after.update({
                "schedule_expression_timezone": "Asia/Kolkata",
                "schedule_expression": "cron(0 22 * * ? *)",
                "state": "ENABLED",
            })
        item = {"address": address, "mode": "managed",
                "change": {"actions": actions, "after": after}}
        if address == gate.VPC:
            item["change"]["actions"] = ["delete", "create"]
            item["action_reason"] = "replace_because_tainted"
        resources.append(item)
    return {"resource_changes": resources}


class TaintedVpcReconciliationTest(unittest.TestCase):
    def test_exact_single_tainted_vpc_is_recognized(self):
        gate.validate_tainted_plan(sample_plan())

    def test_tainted_reason_is_mandatory(self):
        for actions, reason in (
            (["create"], "replace_because_tainted"),
            (["delete", "create"], "replace_because_cannot_update"),
            (["delete", "create"], None),
            (["delete"], "replace_because_tainted"),
        ):
            plan = sample_plan()
            vpc = next(r for r in plan["resource_changes"]
                       if r["address"] == gate.VPC)
            vpc["change"]["actions"] = actions
            if reason is None:
                vpc.pop("action_reason")
            else:
                vpc["action_reason"] = reason
            with self.subTest(actions=actions, reason=reason):
                with self.assertRaises(ValueError):
                    gate.validate_tainted_plan(plan)

    def test_no_other_resource_may_change(self):
        for address in ("aws_iam_role.daily_stop[0]", "aws_instance.paper[0]",
                        "aws_security_group.paper[0]"):
            plan = sample_plan()
            target = next(r for r in plan["resource_changes"]
                          if r["address"] == address)
            target["change"]["actions"] = ["delete", "create"]
            with self.subTest(address=address), self.assertRaises(ValueError):
                gate.validate_tainted_plan(plan)

    def test_exact_user_approval(self):
        gate.validate_request(gate.EXPECTED_REQUEST.copy())
        for key, value in (("region", "us-east-1"), ("root_volume_gib", 100),
                           ("operation", "destroy"), ("confirmation", ""),
                           ("kind", "ec2-paper-provision")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                gate.validate_request({**gate.EXPECTED_REQUEST, key: value})

    def test_workflow_verifies_live_vpc_before_terraform_untaint(self):
        w = (ROOT / ".github/workflows/ec2-paper-tainted-vpc-resume.yml").read_text()
        required = (
            "branches: [dev]", "fetch-depth: 2",
            "'infra/ec2-paper/requests/resume-tainted-vpc.json'",
            "vpc-04712728d857bb782",
            "10.87.0.0/24", "enableDnsHostnames", "enableDnsSupport",
            "Verify exact existing VPC is healthy before modifying Terraform taint",
            "terraform state list -no-color",
            "verify_resume_apply.py state",
            "verify_tainted_resume.py plan",
            "terraform untaint -lock=true -lock-timeout=2m",
            "verify_resume_apply.py plan",
            "terraform apply -input=false -no-color -lock=true",
            "Stop new PAPER host if today's 22 IST shutdown window has passed",
        )
        for item in required:
            with self.subTest(item=item):
                self.assertIn(item, w)
        self.assertLess(w.index("Verify exact existing VPC is healthy"),
                        w.index("terraform untaint -lock=true"))
        self.assertLess(w.index("verify_tainted_resume.py plan"),
                        w.index("terraform untaint -lock=true"))
        self.assertLess(w.index("terraform untaint -lock=true"),
                        w.index("verify_resume_apply.py plan"))
        for forbidden in ("terraform destroy", "terraform apply -auto-approve",
                          "workflow_dispatch:", "TRADING_MODE=LIVE", "AdministratorAccess"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, w)

    def test_cli_rejects_invalid_files(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "approval.json"
            p.write_text(json.dumps(gate.EXPECTED_REQUEST))
            self.assertEqual(gate.main(["tainted", "request", str(p)]), 0)
            p.write_text(json.dumps(sample_plan()))
            self.assertEqual(gate.main(["tainted", "plan", str(p)]), 0)
            p.write_text("{}")
            self.assertEqual(gate.main(["tainted", "plan", str(p)]), 2)


if __name__ == "__main__":
    unittest.main()
