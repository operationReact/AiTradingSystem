"""No-AWS adversarial tests for a single existing tainted PAPER security group."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
script = ROOT / "infra/ec2-paper/verify_sg_tainted_resume.py"
spec = importlib.util.spec_from_file_location("paper_sg_taint",script)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def make_plan(tainted: bool) -> dict:
    changes = []
    for addr in sorted(gate.STATE_MANAGED | gate.CREATE_ONLY):
        if addr in gate.CREATE_ONLY:
            after = {}
            if addr == "aws_instance.paper[0]":
                after = {
                    "instance_type": "t4g.medium",
                    "disable_api_termination": True,
                    "instance_initiated_shutdown_behavior": "stop",
                    "associate_public_ip_address": True,
                    "metadata_options": [{"http_tokens": "required"}],
                    "root_block_device": [{
                        "encrypted": True,
                        "volume_type": "gp3",
                        "volume_size": 35,
                        "delete_on_termination": False,
                    }],
                    "tags": gate.REQUIRED_TAGS.copy(),
                }
            if addr == "aws_scheduler_schedule.daily_stop[0]":
                after = {
                    "schedule_expression_timezone": "Asia/Kolkata",
                    "schedule_expression": "cron(0 22 * * ? *)",
                    "state": "ENABLED",
                }
            change = {"actions": ["create"], "after": after}
        else:
            change = {"actions": ["no-op"], "after": {}}
        item = {"address": addr, "mode": "managed", "change": change}
        if tainted and addr == gate.SG:
            item["change"]["actions"] = ["delete", "create"]
            item["action_reason"] = "replace_because_tainted"
        changes.append(item)
    return {"resource_changes": changes}


class PaperSgTaintRecoveryTest(unittest.TestCase):
    def test_exact_state_ten_existing_and_three_missing(self):
        self.assertEqual(len(gate.STATE_MANAGED), 10)
        self.assertEqual(len(gate.CREATE_ONLY), 3)
        valid="\n".join(sorted(gate.STATE_MANAGED | gate.DATA))
        gate.validate_state_addresses(valid)
        for invalid in (valid+"\naws_instance.paper[0]",
                        valid.replace(gate.SG, "aws_security_group.other"),
                        valid+"\n"+gate.SG):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                gate.validate_state_addresses(invalid)

    def test_pre_taint_is_only_one_security_group_replacement(self):
        gate.validate_plan(make_plan(tainted=True),pre_taint=True)
        for addr in sorted(gate.STATE_MANAGED):
            p=make_plan(tainted=True)
            item=next(x for x in p["resource_changes"] if x["address"]==addr)
            if addr==gate.SG:
                item["action_reason"]="replace_because_cannot_update"
            else:
                item["change"]["actions"]=["update"]
            with self.subTest(address=addr),self.assertRaises(ValueError):
                gate.validate_plan(p,pre_taint=True)

    def test_post_taint_only_three_create(self):
        gate.validate_plan(make_plan(tainted=False),pre_taint=False)
        with self.assertRaises(ValueError):
            gate.validate_plan(make_plan(tainted=True),pre_taint=False)
        for addr in sorted(gate.CREATE_ONLY):
            p=make_plan(tainted=False)
            item=next(x for x in p["resource_changes"] if x["address"]==addr)
            item["change"]["actions"]=["delete","create"]
            with self.subTest(address=addr),self.assertRaises(ValueError):
                gate.validate_plan(p,pre_taint=False)

    def test_budget_and_nightly_stop_are_fixed(self):
        for addr,key,value in (
            ("aws_instance.paper[0]","instance_type","t4g.large"),
            ("aws_instance.paper[0]","metadata_options",[{"http_tokens":"optional"}]),
            ("aws_instance.paper[0]","root_block_device",[{"volume_size":100}]),
            ("aws_instance.paper[0]","tags",{"Application":"other"}),
            ("aws_scheduler_schedule.daily_stop[0]","state","DISABLED"),
            ("aws_scheduler_schedule.daily_stop[0]","schedule_expression","cron(0 23 * * ? *)"),
        ):
            p=make_plan(tainted=False)
            item=next(x for x in p["resource_changes"] if x["address"]==addr)
            item["change"]["after"][key]=value
            with self.subTest(addr=addr,key=key),self.assertRaises(ValueError):
                gate.validate_plan(p,pre_taint=False)

    def test_exact_request_only(self):
        gate.validate_request(gate.REQUEST.copy())
        for key,value in (("account","111122223333"),("region","us-east-1"),
                          ("instance_type","t4g.large"),("operation","destroy"),
                          ("confirmation","")):
            with self.subTest(key=key),self.assertRaises(ValueError):
                gate.validate_request({**gate.REQUEST,key:value})

    def test_workflow_never_applies_a_destructive_plan(self):
        w=(ROOT/".github/workflows/ec2-paper-sg-tainted-resume.yml").read_text()
        for required in (
            "branches: [dev]", "fetch-depth: 2",
            "'infra/ec2-paper/requests/resume-tainted-sg.json'",
            "sg-087969a194d7586ba", "vpc-04712728d857bb782",
            "IpPermissions", "IpPermissionsEgress",
            "verify_sg_tainted_resume.py request",
            "verify_sg_tainted_resume.py state",
            "verify_sg_tainted_resume.py tainted",
            "terraform untaint -lock=true -lock-timeout=2m",
            "verify_sg_tainted_resume.py final",
            "terraform apply -input=false -no-color -lock=true",
            "Stop new PAPER host if today's 22 IST shutdown window has passed",
        ):
            with self.subTest(required=required):
                self.assertIn(required,w)
        self.assertLess(w.index("Verify exact real SG"),w.index("terraform untaint"))
        self.assertLess(w.index("verify_sg_tainted_resume.py tainted"),w.index("terraform untaint"))
        self.assertLess(w.index("terraform untaint"),w.index("verify_sg_tainted_resume.py final"))
        for forbidden in ("terraform destroy", "workflow_dispatch:",
                          "terraform apply -auto-approve","TRADING_MODE=LIVE",
                          "AdministratorAccess"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden,w)

    def test_cli_validation_no_real_aws(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"request.json"
            p.write_text(json.dumps(gate.REQUEST))
            self.assertEqual(gate.main(["check","request",str(p)]),0)
            p.write_text(json.dumps(make_plan(True)))
            self.assertEqual(gate.main(["check","tainted",str(p)]),0)
            p.write_text(json.dumps(make_plan(False)))
            self.assertEqual(gate.main(["check","final",str(p)]),0)
            p.write_text("{}")
            self.assertEqual(gate.main(["check","final",str(p)]),2)


if __name__=="__main__":
    unittest.main()
