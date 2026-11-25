"""Offline fail-closed tests for exact audited PAPER EC2 partial recovery."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "infra/ec2-paper/verify_resume_apply.py"
spec = importlib.util.spec_from_file_location("paper_resume", SCRIPT)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def sample_plan() -> dict:
    changes = []
    for address in sorted(gate.locked.gate.ADDRESSES):
        if address in gate.EXISTING:
            actions, after = ["no-op"], {}
        else:
            actions, after = ["create"], {}
            if address in gate.locked.TAGGED_CREATE_RESOURCES:
                after["tags"] = gate.locked.REQUIRED_TAGS.copy()
            if address == "aws_instance.paper[0]":
                after.update({
                    "instance_type": "t4g.medium",
                    "disable_api_termination": True,
                    "instance_initiated_shutdown_behavior": "stop",
                    "associate_public_ip_address": True,
                    "metadata_options": [{"http_tokens": "required"}],
                    "root_block_device": [{
                        "volume_size": 35, "volume_type": "gp3",
                        "encrypted": True, "delete_on_termination": False
                    }],
                })
            elif address == "aws_security_group.paper[0]":
                after["ingress"] = []
            elif address == "aws_scheduler_schedule.daily_stop[0]":
                after.update({
                    "schedule_expression_timezone": "Asia/Kolkata",
                    "schedule_expression": "cron(0 22 * * ? *)",
                    "state": "ENABLED",
                })
        changes.append({
            "address": address,
            "mode": "managed",
            "change": {"actions": actions, "after": after},
        })
    return {"resource_changes": changes}


class ExactPaperResume(unittest.TestCase):
    def test_exact_known_remote_state_and_eight_missing(self):
        self.assertEqual(len(gate.EXISTING), 5)
        self.assertEqual(len(gate.EXPECTED_NEW), 8)
        state = "\n".join(sorted(gate.EXISTING | gate.EXPECTED_DATA))
        gate.validate_state_addresses(state)
        for bad in (
            state.replace("aws_vpc.paper[0]", "aws_vpc.unrelated[0]"),
            state + "\naws_instance.paper[0]",
            state.replace("aws_iam_role.daily_stop[0]\n", ""),
        ):
            with self.subTest(state=bad), self.assertRaises(ValueError):
                gate.validate_state_addresses(bad)

    def test_request_is_precise_one_time_recovery_only(self):
        gate.validate_request(gate.REQUEST.copy())
        for key, value in (
            ("region", "us-east-1"),
            ("instance_type", "t4g.large"),
            ("operation", "apply"),
            ("confirmation", "PROVISION_PAPER_EC2_4GB"),
        ):
            with self.subTest(key=key), self.assertRaises(ValueError):
                gate.validate_request({**gate.REQUEST, key: value})

    def test_plan_only_remaining_creates_no_changes(self):
        gate.validate_plan(sample_plan())
        for path in ("aws_vpc.paper[0]", "aws_iam_role.instance_ssm[0]"):
            p = sample_plan()
            x = next(r for r in p["resource_changes"] if r["address"] == path)
            x["change"]["actions"] = ["update"]
            with self.subTest(path=path), self.assertRaises(ValueError):
                gate.validate_plan(p)
        for path in ("aws_instance.paper[0]", "aws_security_group.paper[0]"):
            p = sample_plan()
            x = next(r for r in p["resource_changes"] if r["address"] == path)
            x["change"]["actions"] = ["delete", "create"]
            with self.subTest(path=path), self.assertRaises(ValueError):
                gate.validate_plan(p)

    def test_host_and_stop_security_and_tag_guards(self):
        for path, change in (
            ("aws_instance.paper[0]", ("instance_type", "t4g.large")),
            ("aws_instance.paper[0]", ("metadata_options", [{"http_tokens": "optional"}])),
            ("aws_security_group.paper[0]", ("ingress", [{"cidr_blocks": ["0.0.0.0/0"]}])),
            ("aws_scheduler_schedule.daily_stop[0]", ("state", "DISABLED")),
            ("aws_scheduler_schedule.daily_stop[0]", ("schedule_expression", "cron(0 23 * * ? *)")),
            ("aws_subnet.paper[0]", ("tags", {"Application": "something-else"})),
        ):
            p = sample_plan()
            x = next(r for r in p["resource_changes"] if r["address"] == path)
            x["change"]["after"][change[0]] = change[1]
            with self.subTest(path=path, change=change), self.assertRaises(ValueError):
                gate.validate_plan(p)

    def test_workflow_is_separate_request_only_and_locked(self):
        w = (ROOT / "docs/operations/archived-actions/ec2-paper-partial-resume.yml").read_text(encoding="utf-8")
        for required in (
            "'infra/ec2-paper/requests/resume-provision.json'",
            "fetch-depth: 2",
            "verify_resume_apply.py request",
            "verify_resume_apply.py state",
            "verify_resume_apply.py plan",
            "git diff-tree --no-commit-id --name-only -r HEAD",
            "terraform state list -no-color",
            "terraform plan -input=false -no-color -lock=true",
            "terraform apply -input=false -no-color -lock=true",
            "Stop new PAPER host if today's 22 IST shutdown window has passed",
            "if: failure()",
        ):
            with self.subTest(required=required):
                self.assertIn(required, w)
        for forbidden in ("workflow_dispatch:", "terraform destroy",
                          "terraform apply -auto-approve", "TRADING_MODE=LIVE",
                          "AdministratorAccess", "secrets."):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, w)

    def test_cli_requires_local_regular_files(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "recovery.json"
            p.write_text(json.dumps(gate.REQUEST))
            self.assertEqual(gate.main(["resume", "request", str(p)]), 0)
            p.write_text(json.dumps(sample_plan()))
            self.assertEqual(gate.main(["resume", "plan", str(p)]), 0)
            p.write_text("\n".join(sorted(gate.EXISTING | gate.EXPECTED_DATA)))
            self.assertEqual(gate.main(["resume", "state", str(p)]), 0)
            p.write_text("{}")
            self.assertEqual(gate.main(["resume", "plan", str(p)]), 2)


if __name__ == "__main__":
    unittest.main()
