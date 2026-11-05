"""Offline no-AWS tests for exact-structure read-only EC2 PAPER plan acceptance."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "infra/ec2-paper/verify_readonly_plan.py"
spec = importlib.util.spec_from_file_location("paper_plan", MODULE)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def valid_plan():
    return {
        "resource_changes": [
            {"address": addr, "mode": "managed",
             "change": {"actions": ["create"], "after": (
                 {
                     "instance_type": "t4g.medium",
                     "instance_initiated_shutdown_behavior": "stop",
                     "disable_api_termination": True,
                     "associate_public_ip_address": True,
                     "metadata_options": [{"http_tokens": "required"}],
                     "root_block_device": [{
                         "encrypted": True, "volume_type": "gp3",
                         "volume_size": 35,
                         "delete_on_termination": False
                     }],
                 } if addr == "aws_instance.paper[0]" else {
                     "schedule_expression_timezone": "Asia/Kolkata",
                     "schedule_expression": "cron(0 22 * * ? *)",
                     "state": "ENABLED",
                 } if addr == "aws_scheduler_schedule.daily_stop[0]" else {
                     "ingress": [],
                 } if addr == "aws_security_group.paper[0]" else {}
             )}}
            for addr in sorted(gate.ADDRESSES)
        ]
    }


class EC2PaperReadOnlyPlanTests(unittest.TestCase):
    def test_exact_4gb_ist_stop_private_new_resources_accepted(self):
        gate.verify_plan(valid_plan())
        gate.verify_request(gate.EXPECTED.copy())

    def test_any_destructive_or_extra_resource_is_rejected(self):
        def changed(**kwargs):
            p = valid_plan()
            p["resource_changes"][0].update(kwargs)
            return p
        for fake in (
            changed(address="aws_db_instance.postgres[0]"),
            changed(mode="data"),
            changed(change={"actions": ["delete"], "after": {}}),
            changed(change={"actions": ["update"], "after": {}}),
            changed(change={"actions": ["delete", "create"], "after": {}}),
        ):
            with self.assertRaises(ValueError):
                gate.verify_plan(fake)
        p = valid_plan()
        p["resource_changes"].append(p["resource_changes"][0])
        with self.assertRaises(ValueError):
            gate.verify_plan(p)

    def test_reject_different_size_public_ports_bad_disk_or_stop_schedule(self):
        base = valid_plan()
        def get_entry(plan, address):
            return next(item["change"]["after"] for item in plan[
                "resource_changes"] if item["address"] == address)
        changes = (
            ("instance_type", "t4g.large"),
            ("instance_initiated_shutdown_behavior", "terminate"),
            ("disable_api_termination", False),
            ("associate_public_ip_address", False),
            ("metadata_options", [{"http_tokens": "optional"}]),
            ("root_block_device", [{
                "encrypted": False, "volume_type": "gp3", "volume_size": 35,
                "delete_on_termination": False
            }]),
            ("root_block_device", [{
                "encrypted": True, "volume_type": "gp3", "volume_size": 60,
                "delete_on_termination": False
            }]),
        )
        for field, value in changes:
            p = valid_plan()
            get_entry(p, "aws_instance.paper[0]")[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                gate.verify_plan(p)

        for field, value in (
            ("schedule_expression_timezone", "UTC"),
            ("schedule_expression", "cron(0 9 * * ? *)"),
            ("state", "DISABLED"),
        ):
            p = valid_plan()
            get_entry(p, "aws_scheduler_schedule.daily_stop[0]")[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                gate.verify_plan(p)

        p = valid_plan()
        get_entry(p, "aws_security_group.paper[0]")["ingress"] = [
            {"from_port": 22, "to_port": 22, "cidr_blocks": ["0.0.0.0/0"]}
        ]
        with self.assertRaises(ValueError):
            gate.verify_plan(p)

    def test_plan_request_rejects_provision_and_wrong_account(self):
        for override in (
            {"account": "816344830716"},
            {"region": "us-east-1"},
            {"target": "alpha-vidhyarthi"},
            {"operation": "apply"},
            {"confirmation": "PROVISION_PAPER_EC2_4GB"},
            {"any_extra_field": "oops"},
        ):
            with self.assertRaises(ValueError):
                gate.verify_request({**gate.EXPECTED, **override})

    def test_cli_denies_invalid_input_and_accepts_valid_review(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            path.write_text(json.dumps(valid_plan()), encoding="utf-8")
            self.assertEqual(gate.main(["review", "plan", str(path)]), 0)
            path.write_text(json.dumps({"unexpected": "data"}), encoding="utf-8")
            self.assertEqual(gate.main(["review", "plan", str(path)]), 2)
            path.write_text(json.dumps(gate.EXPECTED), encoding="utf-8")
            self.assertEqual(gate.main(["review", "request", str(path)]), 0)
            self.assertEqual(gate.main(["review", "plan", str(path)]), 2)

    def test_actions_job_cannot_write_state_or_apply(self):
        wf = (ROOT / ".github/workflows/ec2-paper-plan-only.yml").read_text()
        for expected in (
            "push:", "branches: [dev]", "id-token: write",
            "'infra/ec2-paper/requests/plan-only.json'",
            "arn:aws:iam::098859530205:role/aitrading-paper-ec2-plan",
            "verify_readonly_plan.py request",
            "verify_readonly_plan.py plan",
            "-backend-config=backend.hcl", "terraform plan",
            "-lock=false", "-refresh=false",
            "approve_paid_provision=PROVISION_PAPER_EC2_4GB",
            "terraform show -json",
        ):
            self.assertIn(expected, wf)
        for forbidden in (
            "terraform apply", "aws ec2 run-instances",
            "aws iam create", "aws s3api put-object",
            "docker push", "secrets.", "TRADING_MODE=LIVE",
            "workflow_dispatch:"
        ):
            self.assertNotIn(forbidden, wf)
        self.assertFalse(
            (ROOT / "infra/ec2-paper/requests/plan-only.json").exists()
        )


if __name__ == "__main__":
    unittest.main()
