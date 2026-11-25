"""Offline adversarial regression for finishing one existing stopped EC2 host.

No AWS access or infrastructure mutation in this suite.
"""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE_FILE = ROOT / "infra/ec2-paper/verify_stopped_host_finish.py"
spec = importlib.util.spec_from_file_location("paper_stopped_finish", GATE_FILE)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def sample_plan(tainted: bool = False) -> dict:
    resources = []
    for address in sorted(gate.EXISTING | gate.REMAINING):
        if address in gate.EXISTING:
            item = {
                "address": address, "mode": "managed",
                "change": {"actions": ["no-op"], "after": {}},
            }
            if tainted and address == gate.INSTANCE_ADDR:
                item["change"]["actions"] = ["delete", "create"]
                item["action_reason"] = "replace_because_tainted"
        else:
            after = {}
            if address == "aws_iam_role_policy.daily_stop[0]":
                after = {
                    "name": "only-stop-aitrading-paper",
                    "role": "aitrading-paper-daily-stop",
                    "policy": json.dumps({
                        "Statement": [{
                            "Effect": "Allow",
                            "Action": "ec2:StopInstances",
                            "Resource": gate.INSTANCE_ARN,
                            "Condition": {"StringEquals": {
                                "ec2:ResourceTag/Application": "ai-trading-system",
                                "ec2:ResourceTag/Environment": "paper",
                            }},
                        }],
                    }),
                }
            elif address == "aws_scheduler_schedule.daily_stop[0]":
                after = {
                    "name": "aitrading-paper-nightly-stop",
                    "schedule_expression": "cron(0 22 * * ? *)",
                    "schedule_expression_timezone": "Asia/Kolkata",
                    "state": "ENABLED",
                    "flexible_time_window": [{"mode": "OFF"}],
                    "target": [{
                        "arn": "arn:aws:scheduler:::aws-sdk:ec2:stopInstances",
                        "role_arn": "arn:aws:iam::098859530205:role/aitrading-paper-daily-stop",
                        "input": '{"InstanceIds":["i-09c01b470fb39b85a"]}',
                    }],
                }
            item = {"address": address, "mode": "managed",
                    "change": {"actions": ["create"], "after": after}}
        resources.append(item)
    return {"resource_changes": resources}


def sample_state() -> dict:
    return {"resources": [{
        "mode": "managed", "type": "aws_instance", "name": "paper",
        "instances": [{
            "index_key": 0,
            "attributes": {"id": gate.INSTANCE_ID, "instance_type": "t4g.medium"},
        }],
    }]}


class StoppedPaperFinishContract(unittest.TestCase):
    def test_exact_user_request_and_protected_state(self):
        gate.validate_request(gate.REQUEST)
        self.assertEqual(len(gate.EXISTING), 11)
        self.assertEqual(len(gate.REMAINING), 2)
        gate.validate_state_addresses("\n".join(sorted(gate.EXISTING | gate.DATA)))
        for key,value in (
            ("instance_id","i-another"),
            ("instance_type","t4g.large"),
            ("operation","restart"),
            ("daily_stop_enabled",False),
            ("confirmation",""),
        ):
            with self.subTest(key=key),self.assertRaises(ValueError):
                gate.validate_request({**gate.REQUEST,key:value})
        with self.assertRaises(ValueError):
            gate.validate_state_addresses("\n".join(sorted(gate.EXISTING | gate.DATA))+"\naws_instance.paper[1]")

    def test_only_exact_existing_tainted_host_may_be_untainted(self):
        gate.validate_stopped_host_state(sample_state())
        for key,value in (
            ("status","tainted"),("status","ready"),("index_key",1),
        ):
            p=sample_state()
            p["resources"][0]["instances"][0][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):
                gate.validate_stopped_host_state(p)
        p=sample_state()
        p["resources"][0]["instances"][0]["attributes"]["id"]="i-not-approved"
        with self.assertRaises(ValueError):
            gate.validate_stopped_host_state(p)

    def test_final_plan_creates_exactly_two_no_existing_resource_changes(self):
        gate.validate_plan(sample_plan(),tainted=False)
        for addr in sorted(gate.EXISTING):
            p=sample_plan()
            obj=next(x for x in p["resource_changes"] if x["address"]==addr)
            obj["change"]["actions"]=["delete","create"]
            with self.subTest(addr=addr),self.assertRaises(ValueError):
                gate.validate_plan(p,tainted=False)
        p=sample_plan()
        p["resource_changes"].append({
            "address":"aws_instance.paper[1]",
            "mode":"managed", "change":{"actions":["create"],"after":{}}})
        with self.assertRaises(ValueError):
            gate.validate_plan(p,tainted=False)

    def test_stop_policy_and_schedule_cannot_expand(self):
        variations=(
            ("aws_iam_role_policy.daily_stop[0]","role","some-other-role"),
            ("aws_scheduler_schedule.daily_stop[0]","state","DISABLED"),
            ("aws_scheduler_schedule.daily_stop[0]","schedule_expression","cron(0 23 * * ? *)"),
            ("aws_scheduler_schedule.daily_stop[0]","schedule_expression_timezone","UTC"),
            ("aws_scheduler_schedule.daily_stop[0]","target",[{"arn":"arn:aws:scheduler:::aws-sdk:ec2:startInstances"}]),
        )
        for addr,key,value in variations:
            p=sample_plan()
            obj=next(x for x in p["resource_changes"] if x["address"]==addr)
            obj["change"]["after"][key]=value
            with self.subTest(addr=addr,key=key),self.assertRaises(ValueError):
                gate.validate_plan(p,tainted=False)
        p=sample_plan()
        iam=next(x for x in p["resource_changes"] if x["address"]=="aws_iam_role_policy.daily_stop[0]")
        policy=json.loads(iam["change"]["after"]["policy"])
        policy["Statement"][0]["Resource"]="*"
        iam["change"]["after"]["policy"]=json.dumps(policy)
        with self.assertRaises(ValueError):
            gate.validate_plan(p,tainted=False)

    def test_ignore_only_ephemeral_stopped_public_ipv4_drift(self):
        source=(ROOT/"infra/ec2-paper/main.tf").read_text(encoding="utf-8")
        self.assertIn("ignore_changes = [associate_public_ip_address]",source)
        self.assertIn("prevent_destroy = true",source)
        self.assertIn("associate_public_ip_address          = true",source)
        self.assertNotIn("ignore_changes = all",source)

    def test_exact_workflow_safe_recovery_order(self):
        w=(ROOT/"docs/operations/archived-actions/ec2-paper-existing-host-finish.yml").read_text(encoding="utf-8")
        required=(
            "branches: [dev]", "fetch-depth: 2",
            "git diff-tree --no-commit-id --name-only -r HEAD",
            "'infra/ec2-paper/requests/finish-stopped-host-v2.json'",
            "verify_stopped_host_finish.py request",
            "verify_stopped_host_finish.py state",
            "verify_stopped_host_finish.py host-state",
            "terraform state pull",
            "verify_stopped_host_finish.py final",
            "terraform apply -input=false -no-color -lock=true",
            'x.get("State",{}).get("Name")!="stopped"',
            "DeleteOnTermination",
            "cron(0 22 * * ? *)",
            "Asia/Kolkata",
            "if: failure()",
            "if: always()",
        )
        for entry in required:
            with self.subTest(entry=entry):
                self.assertIn(entry,w)
        self.assertLess(w.index("Verify exact instance stopped"),w.index("verify_stopped_host_finish.py host-state"))
        self.assertLess(w.index("verify_stopped_host_finish.py host-state"),w.index("verify_stopped_host_finish.py final"))
        self.assertLess(w.index("verify_stopped_host_finish.py final"),w.index("terraform apply"))
        for forbidden in (
            "workflow_dispatch:", "terraform destroy", "terraform untaint", "aws ec2 run-instances",
            "aws ec2 start-instances", "terraform apply -auto-approve",
            "TRADING_MODE=LIVE", "AdministratorAccess", "actions/upload-artifact",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden,w)

    def test_cli_stops_invalid_or_unrelated_files(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"test.json"
            p.write_text(json.dumps(gate.REQUEST))
            self.assertEqual(gate.main(["finish","request",str(p)]),0)
            p.write_text(json.dumps(sample_state()))
            self.assertEqual(gate.main(["finish","host-state",str(p)]),0)
            p.write_text(json.dumps(sample_plan()))
            self.assertEqual(gate.main(["finish","final",str(p)]),0)
            p.write_text("{}")
            self.assertEqual(gate.main(["finish","final",str(p)]),2)


if __name__ == "__main__":
    unittest.main()
