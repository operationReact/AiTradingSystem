"""No-AWS regression checks for separately approved EC2 PAPER locked provisioning."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TF = ROOT / "infra/ec2-paper"
spec = importlib.util.spec_from_file_location("locked_apply_gate", TF / "verify_locked_apply.py")
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def sample_plan() -> dict:
    changes = []
    for address in sorted(gate.gate.ADDRESSES):
        if address == "aws_instance.paper[0]":
            after = {
                "instance_type": "t4g.medium",
                "instance_initiated_shutdown_behavior": "stop",
                "disable_api_termination": True,
                "associate_public_ip_address": True,
                "metadata_options": [{"http_tokens": "required"}],
                "root_block_device": [{
                    "encrypted": True,
                    "volume_type": "gp3",
                    "volume_size": 35,
                    "delete_on_termination": False,
                }],
            }
        elif address == "aws_scheduler_schedule.daily_stop[0]":
            after = {
                "schedule_expression_timezone": "Asia/Kolkata",
                "schedule_expression": "cron(0 22 * * ? *)",
                "state": "ENABLED",
            }
        elif address == "aws_security_group.paper[0]":
            after = {"ingress": []}
        else:
            after = {}
        if address in gate.TAGGED_CREATE_RESOURCES:
            after["tags"] = {**gate.REQUIRED_TAGS, "Name": "aitradingsystem-paper-ec2"}
        changes.append({
            "address": address, "mode": "managed",
            "change": {"actions": ["create"], "after": after},
        })
    return {"resource_changes": changes}


class PaperLockedProvisionContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.resources = json.loads(
            (TF / "bootstrap/state-preflight.cfn.json").read_text()
        )["Resources"]
        cls.role = cls.resources["RestrictedGithubProvisionRole"]["Properties"]
        cls.statements = cls.role["Policies"][0]["PolicyDocument"]["Statement"]
        cls.wf = (ROOT / ".github/workflows/ec2-paper-approved-provision.yml").read_text()

    def test_exact_user_approved_scope_and_no_unreviewed_variants(self):
        gate.validate_request(gate.EXPECTED.copy())
        self.assertEqual(gate.EXPECTED["account"], "098859530205")
        self.assertEqual(gate.EXPECTED["region"], "ap-south-1")
        self.assertEqual(gate.EXPECTED["instance_type"], "t4g.medium")
        self.assertEqual(gate.EXPECTED["root_volume_gib"], 35)
        self.assertEqual(gate.EXPECTED["daily_stop_hour_ist"], 22)
        self.assertIs(gate.EXPECTED["daily_stop_enabled"], True)
        self.assertEqual(gate.EXPECTED["operation"], "provision-once")
        for change in (
            {"account": "816344830716"}, {"region": "us-east-1"},
            {"instance_type": "t4g.large"}, {"root_volume_gib": 100},
            {"daily_stop_enabled": False}, {"daily_stop_hour_ist": 9},
            {"operation": "live"}, {"confirmation": ""},
            {"approve_live_orders": True},
        ):
            with self.subTest(change=change), self.assertRaises(ValueError):
                gate.validate_request({**gate.EXPECTED, **change})

    def test_exact_create_plan_and_deletion_rejection(self):
        gate.validate_fresh_plan(sample_plan())
        for field, value in (
            ("instance_type", "t4g.large"),
            ("root_block_device", [{"encrypted": True, "volume_type": "gp3",
                                    "volume_size": 40,
                                    "delete_on_termination": False}]),
        ):
            p = sample_plan()
            inst = next(x for x in p["resource_changes"] if
                        x["address"] == "aws_instance.paper[0]")
            inst["change"]["after"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                gate.validate_fresh_plan(p)
        for value in (["delete"], ["update"], ["delete", "create"]):
            p = sample_plan()
            p["resource_changes"][0]["change"]["actions"] = value
            with self.subTest(actions=value), self.assertRaises(ValueError):
                gate.validate_fresh_plan(p)

    def test_terraform_create_tags_match_restricted_iam_policy(self):
        source = (TF / "main.tf").read_text(encoding="utf-8")
        self.assertIn('Application = "ai-trading-system"', source)
        self.assertIn('Environment = "paper"', source)
        for address in sorted(gate.TAGGED_CREATE_RESOURCES):
            with self.subTest(address=address):
                plan = sample_plan()
                resource = next(x for x in plan["resource_changes"]
                                if x["address"] == address)
                resource["change"]["after"]["tags"].pop("Application")
                with self.assertRaises(ValueError):
                    gate.validate_fresh_plan(plan)
        p = sample_plan()
        instance = next(x for x in p["resource_changes"]
                        if x["address"] == "aws_instance.paper[0]")
        instance["change"]["after"]["tags"]["Environment"] = "live"
        with self.assertRaises(ValueError):
            gate.validate_fresh_plan(p)

    def test_exact_iam_trust_one_paper_state_lock_and_no_admin(self):
        self.assertEqual(set(self.resources), {
            "PaperStateBucket", "PaperStateBucketPolicy",
            "ReadOnlyGithubPreflightRole", "ReadOnlyGithubPlanRole",
            "RestrictedGithubProvisionRole",
        })
        self.assertEqual(self.role["RoleName"], "aitrading-paper-ec2-provision")
        rule = self.role["AssumeRolePolicyDocument"]["Statement"][0]
        self.assertEqual(rule["Action"], "sts:AssumeRoleWithWebIdentity")
        self.assertEqual(rule["Condition"]["StringEquals"], {
            "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
            "token.actions.githubusercontent.com:sub":
            "repo:operationReact@215111824/AiTradingSystem@1433875172:ref:refs/heads/dev",
        })
        allowed = self.statements[:-1]
        self.assertTrue(all(x["Effect"] == "Allow" for x in allowed))
        network = next(x for x in allowed
                       if x["Sid"] == "ConfigureOnlyMumbaiPaperNetwork")
        self.assertIn("ec2:RevokeSecurityGroupEgress", network["Action"])
        self.assertIn("ec2:AuthorizeSecurityGroupEgress", network["Action"])
        self.assertNotIn("ec2:AuthorizeSecurityGroupIngress", network["Action"])
        inventory = next(x for x in allowed if x["Sid"] == "ReadOnlyMumbaiInventory")
        self.assertIn("ec2:DescribeSecurityGroupRules", inventory["Action"])
        own_policy = next(x for x in allowed
                          if x["Sid"] == "ReadOwnProvisioningInlinePolicy")
        self.assertEqual(own_policy["Action"], "iam:GetRolePolicy")
        self.assertEqual(own_policy["Resource"],
                         "arn:aws:iam::098859530205:role/aitrading-paper-ec2-provision")
        self.assertEqual(network["Condition"]["StringEquals"]["aws:RequestedRegion"],
                         "ap-south-1")
        stop_trust_update = next(x for x in allowed
                                 if x["Sid"] == "FixOnlyDedicatedPaperSchedulerTrust")
        self.assertEqual(stop_trust_update["Action"], "iam:UpdateAssumeRolePolicy")
        self.assertEqual(stop_trust_update["Resource"],
                         "arn:aws:iam::098859530205:role/aitrading-paper-daily-stop")
        tf_source = (TF / "main.tf").read_text(encoding="utf-8")
        self.assertIn(":schedule-group/default", tf_source)
        self.assertNotIn(":schedule/default/${local.stop_name}", tf_source)
        instance_modify = next(x for x in allowed
                               if x["Sid"] == "AllowUpdateOnlyExactStoppedPaperInstance")
        self.assertEqual(set(instance_modify["Action"]),
                         {"ec2:ModifyInstanceAttribute", "ec2:DescribeInstanceAttribute"})
        self.assertEqual(instance_modify["Resource"],
                         "arn:aws:ec2:ap-south-1:098859530205:instance/i-09c01b470fb39b85a")
        self.assertEqual(instance_modify["Condition"]["StringEquals"], {
            "aws:RequestedRegion": "ap-south-1",
            "ec2:ResourceTag/Application": "ai-trading-system",
            "ec2:ResourceTag/Environment": "paper",
            "ec2:ResourceTag/Name": "aitradingsystem-paper-ec2",
        })
        vpc_reads = next(x for x in allowed if x["Sid"] == "ReadOnlyMumbaiInventory")
        self.assertIn("ec2:DescribeVpcAttribute", vpc_reads["Action"])
        self.assertEqual(vpc_reads["Condition"]["StringEquals"]["aws:RequestedRegion"],
                         "ap-south-1")
        self.assertEqual(self.statements[-1]["Effect"], "Deny")
        self.assertTrue(all(not act.endswith("*") for stmt in allowed
                            for act in (stmt["Action"] if isinstance(stmt["Action"], list)
                                        else [stmt["Action"]])))
        s3_deletes = [s for s in allowed if "s3:DeleteObject" in
                      (s["Action"] if isinstance(s["Action"], list) else [s["Action"]])]
        self.assertEqual(len(s3_deletes), 1)
        self.assertEqual(s3_deletes[0]["Resource"],
                         "arn:aws:s3:::aitrading-paper-tfstate-098859530205-ap-south-1/"
                         "ai-trading/paper-ec2/terraform.tfstate.tflock")
        for s in allowed:
            acts = s["Action"] if isinstance(s["Action"], list) else [s["Action"]]
            if "iam:CreateRole" in acts or "iam:PassRole" in acts:
                resources = s["Resource"] if isinstance(s["Resource"], list) else [s["Resource"]]
                self.assertTrue(all("/aitrading-paper-" in r for r in resources))
        deny = self.statements[-1]["Action"]
        for action in ("ec2:TerminateInstances", "ec2:AuthorizeSecurityGroupIngress",
                       "ec2:CreateNatGateway", "rds:CreateDBInstance",
                       "scheduler:DeleteSchedule", "s3:DeleteBucket"):
            self.assertIn(action, deny)
        stop_only = next(x for x in allowed if x["Sid"] ==
                         "FailSafeStopOnlyTaggedPaperHostOnApplyError")
        self.assertEqual(stop_only["Action"], "ec2:StopInstances")
        self.assertEqual(stop_only["Resource"],
                         "arn:aws:ec2:ap-south-1:098859530205:instance/*")
        self.assertEqual(stop_only["Condition"]["StringEquals"][
            "ec2:ResourceTag/Name"], "aitradingsystem-paper-ec2")
        self.assertEqual(stop_only["Condition"]["StringEquals"][
            "ec2:ResourceTag/Environment"], "paper")
        self.assertLess(len(json.dumps(self.role["Policies"][0]["PolicyDocument"],
                                       separators=(",", ":"))), 10240)

    def test_workflow_only_exact_request_commit_fresh_locked_plan_and_same_apply(self):
        wf = self.wf
        for required in (
            "branches: [dev]",
            "'infra/ec2-paper/requests/approved-provision.json'",
            "if: github.ref == 'refs/heads/dev'",
            "permissions:\n  contents: read\n  id-token: write",
            "git diff-tree --no-commit-id --name-only -r HEAD",
            "fetch-depth: 2",
            "Stop new PAPER host if today's 22 IST shutdown window has passed",
            "TZ=Asia/Kolkata date +%H",
            "aws ec2 stop-instances --region",
            "verify_locked_apply.py request",
            "verify_locked_apply.py plan",
            "arn:aws:iam::098859530205:role/aitrading-paper-ec2-provision",
            "-backend-config=backend.hcl",
            "-lock=true -lock-timeout=2m -refresh=true",
            "-var=\"instance_type=t4g.medium\"",
            "-var=\"root_volume_gib=35\"",
            "-var=\"daily_stop_enabled=true\"",
            "terraform show -json",
            'terraform apply -input=false -no-color -lock=true -lock-timeout=2m',
            '"$PLAN_WORKDIR/approved.tfplan"',
            "aws scheduler get-schedule",
            "cron(0 22 * * ? *)",
            "State",
            "Asia/Kolkata",
            "if: failure()",
            'test "${DEPLOY_STARTED:-}" != "true"',
            "aws ec2 stop-instances --region",
            "Name=tag:Name,Values=aitradingsystem-paper-ec2",
            "DEPLOY_STARTED=true",
            "if: always()",
        ):
            with self.subTest(required=required):
                self.assertIn(required, wf)
        for forbidden in (
            "workflow_dispatch:",
            "terraform destroy",
            "terraform apply -auto-approve",
            "aws ec2 run-instances",
            "aws ec2 terminate-instances",
            "docker push",
            "TRADING_MODE=LIVE",
            "BROKER_ORDER_EXECUTION_ENABLED=true",
            "aws s3api delete-object",
            "actions/upload-artifact",
            "secrets.",
            "AdministratorAccess",
        ):
            self.assertNotIn(forbidden, wf)
        # Future operator-request commits may exist, but must always have
        # exactly the approved schema and no extra state-changing fields.
        request = TF / "requests/approved-provision.json"
        if request.exists():
            self.assertFalse(request.is_symlink())
            self.assertLessEqual(request.stat().st_size, gate.MAX_REQUEST_BYTES)
            gate.validate_request(json.loads(request.read_text(encoding="utf-8")))

    def test_cli_invalid_payload_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            f = Path(directory) / "request.json"
            f.write_text(json.dumps(gate.EXPECTED), encoding="utf-8")
            self.assertEqual(gate.main(["verify", "request", str(f)]), 0)
            f.write_text('{"operation": "apply"}', encoding="utf-8")
            self.assertEqual(gate.main(["verify", "request", str(f)]), 2)
            f.write_text(json.dumps(sample_plan()), encoding="utf-8")
            self.assertEqual(gate.main(["verify", "plan", str(f)]), 0)
            f.write_text(json.dumps({"resource_changes": []}), encoding="utf-8")
            self.assertEqual(gate.main(["verify", "plan", str(f)]), 2)


if __name__ == "__main__":
    unittest.main()
