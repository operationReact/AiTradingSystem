"""Offline tests: only exact stop-role trust update and one nightly schedule create."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TF = ROOT / "infra/ec2-paper"
spec = importlib.util.spec_from_file_location("paper_group_stop",TF / "verify_group_schedule_recovery.py")
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def trust(updated: bool) -> str:
    cond=({"StringEquals":{"aws:SourceAccount":gate.ACCOUNT,
                            "aws:SourceArn":gate.GROUP_ARN}} if updated else
          {"StringEquals":{"aws:SourceAccount":gate.ACCOUNT},
           "ArnEquals":{"aws:SourceArn":gate.OLD_SCHEDULE_ARN}})
    return json.dumps({"Version":"2012-10-17","Statement":[{
        "Effect":"Allow","Action":"sts:AssumeRole",
        "Principal":{"Service":"scheduler.amazonaws.com"},
        "Condition":cond,
    }]})


def plan() -> dict:
    resources=[]
    for a in sorted(gate.EXISTING | {gate.SCHEDULE_ADDRESS}):
        if a==gate.ROLE_ADDRESS:
            resources.append({
                "address":a,"mode":"managed",
                "change":{"actions":["update"],
                          "before":{"name":gate.ROLE,"assume_role_policy":trust(False)},
                          "after":{"name":gate.ROLE,"assume_role_policy":trust(True)}}})
        elif a==gate.SCHEDULE_ADDRESS:
            resources.append({
                "address":a,"mode":"managed",
                "change":{"actions":["create"],"after":{
                    "name":"aitrading-paper-nightly-stop",
                    "schedule_expression":"cron(0 22 * * ? *)",
                    "schedule_expression_timezone":"Asia/Kolkata",
                    "state":"ENABLED",
                    "flexible_time_window":[{"mode":"OFF","maximum_window_in_minutes":None}],
                    "target":[{
                        "arn":"arn:aws:scheduler:::aws-sdk:ec2:stopInstances",
                        "role_arn":gate.ROLE_ARN,
                        "input":json.dumps({"InstanceIds":[gate.INSTANCE]}),
                    }],
                }}})
        else:
            resources.append({"address":a,"mode":"managed",
                              "change":{"actions":["no-op"],"after":{}}})
    return {"resource_changes":resources}


class StopGroupRecoveryContract(unittest.TestCase):
    def test_exact_single_user_request_and_twelve_existing(self):
        gate.validate_request(gate.REQUEST.copy())
        self.assertEqual(len(gate.EXISTING),12)
        gate.validate_state("\n".join(sorted(gate.EXISTING | gate.DATA)))
        for key,bad in (("account","000000000000"),("region","eu-west-1"),
                        ("instance_id","i-other"),("daily_stop_enabled",False),
                        ("operation","destroy")):
            with self.subTest(key=key),self.assertRaises(ValueError):
                gate.validate_request({**gate.REQUEST,key:bad})

    def test_one_exact_trust_update_and_one_create_accepted(self):
        gate.validate_plan(plan())
        for address in sorted(gate.EXISTING):
            p=plan()
            x=next(v for v in p["resource_changes"] if v["address"]==address)
            x["change"]["actions"]=["delete","create"]
            with self.subTest(address=address),self.assertRaises(ValueError):
                gate.validate_plan(p)

    def test_role_trust_has_exact_source_account_and_group(self):
        for new_trust in (
            trust(False),
            json.dumps({"Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
              "Principal":{"Service":"scheduler.amazonaws.com"}}]}),
            json.dumps({"Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
              "Principal":{"Service":"scheduler.amazonaws.com"},
              "Condition":{"StringEquals":{"aws:SourceAccount":gate.ACCOUNT,
              "aws:SourceArn":"arn:aws:scheduler:ap-south-1:"+gate.ACCOUNT+":schedule-group/*"}}}]}),
        ):
            p=plan()
            role=next(v for v in p["resource_changes"] if v["address"]==gate.ROLE_ADDRESS)
            role["change"]["after"]["assume_role_policy"]=new_trust
            with self.subTest(new_trust=new_trust),self.assertRaises(ValueError):
                gate.validate_plan(p)
        p=plan()
        role=next(v for v in p["resource_changes"] if v["address"]==gate.ROLE_ADDRESS)
        role["change"]["after"]["name"]="different-role"
        with self.assertRaises(ValueError):
            gate.validate_plan(p)

    def test_provider_optional_off_window_is_safe_but_other_windows_rejected(self):
        gate.validate_plan(plan())
        p=plan()
        item=next(x for x in p["resource_changes"] if x["address"]==gate.SCHEDULE_ADDRESS)
        item["change"]["after"]["flexible_time_window"]=[{"mode":"OFF","maximum_window_in_minutes":0}]
        gate.validate_plan(p)
        for window in (
            [{"mode":"FLEXIBLE","maximum_window_in_minutes":1}],
            [{"mode":"OFF","maximum_window_in_minutes":5}],
            [{"mode":"OFF","extra":"override"}],
            [{"mode":"OFF"},{"mode":"OFF"}],
        ):
            p=plan()
            item=next(x for x in p["resource_changes"] if x["address"]==gate.SCHEDULE_ADDRESS)
            item["change"]["after"]["flexible_time_window"]=window
            with self.subTest(window=window),self.assertRaises(ValueError):
                gate.validate_plan(p)

    def test_schedule_cannot_run_ec2_start_or_change_time(self):
        for key,value in (
            ("state","DISABLED"),("schedule_expression","cron(0 23 * * ? *)"),
            ("schedule_expression_timezone","UTC"),
            ("target",[{"arn":"arn:aws:scheduler:::aws-sdk:ec2:startInstances"}]),
        ):
            p=plan()
            s=next(v for v in p["resource_changes"] if v["address"]==gate.SCHEDULE_ADDRESS)
            s["change"]["after"][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):
                gate.validate_plan(p)

    def test_workflow_requires_exact_saved_plan_and_never_starts_or_replaces_host(self):
        workflow=(ROOT/".github/workflows/ec2-paper-group-trust-repair.yml").read_text(encoding="utf-8")
        for item in (
            "branches: [dev]","fetch-depth: 2",
            "'infra/ec2-paper/requests/finish-group-trust-v2.json'",
            "git diff-tree --no-commit-id --name-only -r HEAD",
            "verify_group_schedule_recovery.py request",
            "verify_group_schedule_recovery.py state",
            "verify_group_schedule_recovery.py plan",
            "terraform show -json",
            "terraform apply -input=false -no-color -lock=true",
            "arn:aws:scheduler:ap-south-1:098859530205:schedule-group/default",
            "cron(0 22 * * ? *)","Asia/Kolkata",
            "if: failure()","if: always()",
        ):
            with self.subTest(item=item):
                self.assertIn(item,workflow)
        for bad in ("terraform destroy", "terraform untaint",
                    "aws ec2 start-instances", "aws ec2 run-instances",
                    "terraform apply -auto-approve", "TRADING_MODE=LIVE",
                    "workflow_dispatch:","AdministratorAccess"):
            with self.subTest(bad=bad):
                self.assertNotIn(bad,workflow)

    def test_cli_only_accepts_exact_local_schemas(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"input.json"
            p.write_text(json.dumps(gate.REQUEST))
            self.assertEqual(gate.main(["test","request",str(p)]),0)
            p.write_text("\n".join(sorted(gate.EXISTING | gate.DATA)))
            self.assertEqual(gate.main(["test","state",str(p)]),0)
            p.write_text(json.dumps(plan()))
            self.assertEqual(gate.main(["test","plan",str(p)]),0)
            p.write_text("{}")
            self.assertEqual(gate.main(["test","plan",str(p)]),2)


if __name__=="__main__":
    unittest.main()
