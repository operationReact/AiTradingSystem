"""EC2 PAPER bootstrap security/ownership, without AWS or paid resources."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TF = ROOT / "infra/ec2-paper"
CFN = TF / "bootstrap/state-preflight.cfn.json"
READ = ROOT / ".github/workflows/ec2-paper-readonly-preflight.yml"
BUCKET = "aitrading-paper-tfstate-098859530205-ap-south-1"
ACCOUNT = "098859530205"
GITHUB_SUB = "repo:operationReact@215111824/AiTradingSystem@1433875172:ref:refs/heads/dev"

spec = importlib.util.spec_from_file_location(
    "ec2_paper_request", TF / "verify_preflight_request.py"
)
assert spec and spec.loader
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class EC2PaperPreflightContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cfn = json.loads(CFN.read_text(encoding="utf-8"))
        cls.resources = cls.cfn["Resources"]
        cls.wf = READ.read_text(encoding="utf-8")

    def test_only_state_bucket_policy_and_five_explicit_oidc_roles_are_bootstrapped(self):
        self.assertEqual(set(self.resources), {
            "PaperStateBucket", "PaperStateBucketPolicy",
            "ReadOnlyGithubPreflightRole", "ReadOnlyGithubPlanRole",
            "RestrictedGithubProvisionRole",
            "PaperManualGithubLifecycleRole",
            "PaperReleaseArtifactBucket", "PaperReleaseArtifactBucketPolicy",
            "PaperGithubSsmDeployRole",
        })
        self.assertEqual(self.resources["PaperStateBucket"]["Type"], "AWS::S3::Bucket")
        bucket = self.resources["PaperStateBucket"]
        self.assertEqual(bucket["DeletionPolicy"], "Retain")
        self.assertEqual(bucket["UpdateReplacePolicy"], "Retain")
        properties = bucket["Properties"]
        self.assertEqual(properties["BucketName"], BUCKET)
        self.assertEqual(properties["VersioningConfiguration"]["Status"], "Enabled")
        self.assertEqual(
            properties["BucketEncryption"]["ServerSideEncryptionConfiguration"][0]
            ["ServerSideEncryptionByDefault"]["SSEAlgorithm"], "AES256"
        )
        self.assertEqual(
            set(properties["PublicAccessBlockConfiguration"].values()), {True}
        )
        self.assertEqual(
            properties["OwnershipControls"]["Rules"][0]["ObjectOwnership"],
            "BucketOwnerEnforced"
        )
        policy = self.resources["PaperStateBucketPolicy"]
        self.assertEqual(policy["Properties"]["Bucket"], {"Ref": "PaperStateBucket"})
        deny = policy["Properties"]["PolicyDocument"]["Statement"]
        self.assertEqual(len(deny), 1)
        self.assertEqual(deny[0]["Effect"], "Deny")
        self.assertEqual(deny[0]["Action"], "s3:*")
        self.assertEqual(deny[0]["Condition"]["Bool"]["aws:SecureTransport"], "false")

    def test_exact_dev_account_trust_and_no_paid_api_permissions(self):
        role = self.resources["ReadOnlyGithubPreflightRole"]["Properties"]
        self.assertEqual(role["RoleName"], "aitrading-paper-ec2-preflight")
        self.assertEqual(role["MaxSessionDuration"], 3600)
        trust = role["AssumeRolePolicyDocument"]["Statement"]
        self.assertEqual(len(trust), 1)
        rule = trust[0]
        self.assertEqual(rule["Action"], "sts:AssumeRoleWithWebIdentity")
        self.assertEqual(
            rule["Principal"]["Federated"],
            f"arn:aws:iam::{ACCOUNT}:oidc-provider/token.actions.githubusercontent.com",
        )
        self.assertEqual(rule["Condition"]["StringEquals"], {
            "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
            "token.actions.githubusercontent.com:sub": GITHUB_SUB,
        })
        policies = role["Policies"]
        self.assertEqual(len(policies), 1)
        statements = policies[0]["PolicyDocument"]["Statement"]
        self.assertEqual(len(statements), 2)
        self.assertEqual(statements[0]["Resource"], {
            "Fn::GetAtt": ["PaperStateBucket", "Arn"]
        })
        allowed = [a for s in statements for a in s["Action"]]
        self.assertTrue(all(a.startswith(("s3:Get", "s3:List", "ec2:Describe"))
                            for a in allowed))
        self.assertTrue(all("*" not in a for a in allowed))
        self.assertEqual(statements[1]["Condition"]["StringEquals"][
            "aws:RequestedRegion"], "ap-south-1")
        for denied in ("ec2:RunInstances", "ec2:StopInstances",
                       "ec2:StartInstances", "iam:PassRole", "iam:CreateRole",
                       "s3:PutObject", "s3:DeleteObject", "scheduler:CreateSchedule",
                       "rds:CreateDBInstance"):
            self.assertNotIn(denied, allowed)

    def test_second_oidc_role_only_reads_single_paper_state_and_ec2(self):
        role = self.resources["ReadOnlyGithubPlanRole"]["Properties"]
        self.assertEqual(role["RoleName"], "aitrading-paper-ec2-plan")
        policy = role["AssumeRolePolicyDocument"]["Statement"][0]
        self.assertEqual(
            policy["Condition"]["StringEquals"], {
                "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
                "token.actions.githubusercontent.com:sub": GITHUB_SUB,
            },
        )
        self.assertEqual(
            policy["Principal"]["Federated"],
            f"arn:aws:iam::{ACCOUNT}:oidc-provider/token.actions.githubusercontent.com",
        )
        statements = role["Policies"][0]["PolicyDocument"]["Statement"]
        actions = [
            action for statement in statements
            for action in (statement["Action"] if isinstance(
                statement["Action"], list) else [statement["Action"]])
        ]
        for action in actions:
            self.assertTrue(action.startswith((
                "s3:Get", "s3:List", "ec2:Describe", "ssm:Get",
                "iam:Get", "iam:List", "scheduler:Get",
            )), action)
        self.assertFalse(any("*" in a for a in actions))
        self.assertEqual(
            statements[1]["Resource"],
            f"arn:aws:s3:::{BUCKET}/ai-trading/paper-ec2/terraform.tfstate",
        )
        self.assertNotIn("s3:PutObject", actions)
        self.assertNotIn("s3:DeleteObject", actions)
        self.assertNotIn("ec2:RunInstances", actions)
        self.assertNotIn("iam:PassRole", actions)
        self.assertNotIn("scheduler:CreateSchedule", actions)
        self.assertEqual(
            statements[2]["Condition"]["StringEquals"]["aws:RequestedRegion"],
            "ap-south-1",
        )

    def test_backend_is_separate_from_old_ecs_no_dynamodb_required(self):
        backend = (TF / "backend.hcl").read_text(encoding="utf-8")
        self.assertIn(f'bucket       = "{BUCKET}"', backend)
        self.assertIn('key          = "ai-trading/paper-ec2/terraform.tfstate"',
                      backend)
        self.assertIn('region       = "us-east-1"', backend)
        self.assertIn("AWS provider in versions.tf remains ap-south-1", backend)
        self.assertIn('default     = "ap-south-1"',
                      (TF / "variables.tf").read_text(encoding="utf-8"))
        self.assertIn("use_lockfile = true", backend)
        self.assertIn("encrypt      = true", backend)
        self.assertNotIn("dynamodb_table", backend)
        self.assertNotIn("aws_access_key_id", backend.lower())
        self.assertIn('backend "s3" {}', (TF / "versions.tf").read_text())

    def test_new_oidc_workflow_is_readonly_explicit_request_dev_only(self):
        w = self.wf
        self.assertIn("push:", w)
        self.assertIn("branches: [dev]", w)
        self.assertIn("'infra/ec2-paper/requests/read-only-preflight.json'", w)
        self.assertIn("if: github.ref == 'refs/heads/dev'", w)
        self.assertIn("id-token: write", w)
        self.assertIn("contents: read", w)
        self.assertIn(
            "arn:aws:iam::098859530205:role/aitrading-paper-ec2-preflight", w
        )
        self.assertIn("verify_preflight_request.py", w)
        self.assertIn("STATE_BUCKET_REGION: us-east-1", w)
        self.assertIn("AWS_REGION: ap-south-1", w)
        self.assertIn("get-bucket-location", w)
        self.assertIn("location is not None", w)
        self.assertIn('--region "$STATE_BUCKET_REGION"', w)
        self.assertIn("get-bucket-versioning", w)
        self.assertIn("get-bucket-encryption", w)
        self.assertIn("get-public-access-block", w)
        self.assertIn("describe-instance-type-offerings", w)
        for disallowed in (
            "terraform apply", "aws ec2 run-instances",
            "aws ec2 start-instances", "aws ec2 stop-instances",
            "aws iam create", "aws scheduler create",
            "aws s3api put-object", "workflow_dispatch:",
            "AdministratorAccess", "lightsail create",
            "secrets.", "AWS_SECRET_ACCESS_KEY", "TRADING_MODE=LIVE"
        ):
            self.assertNotIn(disallowed, w)

    def test_exact_preflight_request_rejects_all_other_actions(self):
        approved = validator.EXPECTED.copy()
        self.assertEqual(approved["confirmation"], "READ_ONLY_AWS_PREFLIGHT")
        validator.validate(approved)
        for name, invalid in (
            ("other-account", {"account": "816344830716"}),
            ("other-region", {"region": "us-east-1"}),
            ("another-project", {"target": "alpha-vidhyarthi"}),
            ("run-instance", {"operation": "provision"}),
            ("paid-approval", {"confirmation": "PROVISION_PAPER_EC2_4GB"}),
            ("other-repo", {"kind": "unknown"}),
            ("extra", {"access_key_id": "secret"}),
        ):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validator.validate({**approved, **invalid})
        for invalid in (None, [], "", 7):
            with self.assertRaises(ValueError):
                validator.validate(invalid)

    def test_explicit_read_only_request_is_safe_when_committed(self):
        request = TF / "requests/read-only-preflight.json"
        if request.exists():
            self.assertFalse(request.is_symlink())
            self.assertLessEqual(request.stat().st_size, validator.MAX_BYTES)
            validator.validate(json.loads(request.read_text(encoding="utf-8")))
        with tempfile.TemporaryDirectory() as folder:
            f = Path(folder) / "request.json"
            f.write_text(json.dumps(validator.EXPECTED), encoding="utf-8")
            self.assertEqual(validator.main(["verify", str(f)]), 0)
            f.write_text('{"operation": "provision"}', encoding="utf-8")
            self.assertEqual(validator.main(["verify", str(f)]), 2)


if __name__ == "__main__":
    unittest.main()
