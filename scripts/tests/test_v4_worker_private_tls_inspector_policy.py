"""V86 operator workflow cannot accidentally mutate AWS or leak raw metadata."""
from __future__ import annotations

import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/v4-worker-private-tls-readonly-inspect.yml"
TRUST = ROOT / "infra/aws/readonly-v86-worker-tls-oidc-trust.json"
POLICY = ROOT / "infra/aws/readonly-v86-worker-tls-policy.json"
SCRIPT = ROOT / "scripts/v4_worker_private_tls_posture.py"


class WorkerTlsReadOnlyPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.policy = json.loads(POLICY.read_text(encoding="utf-8"))
        cls.trust = json.loads(TRUST.read_text(encoding="utf-8"))
        cls.script = SCRIPT.read_text(encoding="utf-8")

    def test_github_manual_exact_dev_and_oidc_account_only(self):
        w = self.workflow
        self.assertIn("on:\n  workflow_dispatch:", w)
        self.assertIn("id-token: write", w)
        self.assertIn("contents: read", w)
        self.assertIn("refs/heads/dev", w)
        self.assertIn("vars.AWS_WORKER_TLS_READONLY_ROLE_ARN", w)
        self.assertIn("allowed-account-ids: '816344830716'", w)
        self.assertIn("us-east-1", w)
        self.assertIn("configure-aws-credentials@v6.3.0", w)
        condition = self.trust["Statement"][0]["Condition"]["StringEquals"]
        self.assertEqual(condition["token.actions.githubusercontent.com:sub"],
                         "repo:operationReact/AiTradingSystem:ref:refs/heads/dev")
        self.assertEqual(condition["token.actions.githubusercontent.com:aud"],
                         "sts.amazonaws.com")
        self.assertEqual(self.trust["Statement"][0]["Action"],
                         "sts:AssumeRoleWithWebIdentity")

    def test_iam_permissions_are_metadata_only_and_not_credentials(self):
        rows = self.policy["Statement"]
        actions = {action for row in rows for action in row["Action"]}
        self.assertEqual(actions, {
            "sts:GetCallerIdentity",
            "ecs:DescribeServices",
            "elasticloadbalancing:DescribeLoadBalancers",
            "elasticloadbalancing:DescribeListeners",
            "elasticloadbalancing:DescribeRules",
            "elasticloadbalancing:DescribeTargetGroups",
            "ec2:DescribeSecurityGroups", "ec2:DescribeSubnets",
            "route53:ListHostedZonesByName", "route53:GetHostedZone",
            "route53:ListResourceRecordSets", "acm:DescribeCertificate",
        })
        self.assertTrue(all(row["Effect"] == "Allow" for row in rows))
        self.assertTrue(all("*" not in action for action in actions))
        ecs = next(row for row in rows if "ecs:DescribeServices" in row["Action"])
        self.assertEqual(len(ecs["Resource"]), 3)
        self.assertTrue(all("816344830716:service/ai-trading-system/" in arn
                            for arn in ecs["Resource"]))
        acm = next(row for row in rows if "acm:DescribeCertificate" in row["Action"])
        self.assertEqual(acm["Resource"],
                         "arn:aws:acm:us-east-1:816344830716:certificate/*")

    def test_workflow_collects_only_ephemeral_json_and_uploads_sanitized_report(self):
        w = self.workflow
        for allowed in (
            "aws sts get-caller-identity",
            "elbv2 describe-load-balancers",
            "elbv2 describe-listeners",
            "elbv2 describe-rules",
            "elbv2 describe-target-groups",
            "ec2 describe-security-groups",
            "ec2 describe-subnets",
            "route53 get-hosted-zone",
            "route53 list-hosted-zones-by-name",
            "route53 list-resource-record-sets",
            "acm describe-certificate",
            "ecs describe-services",
            "scripts/v4_worker_private_tls_posture.py",
        ):
            self.assertIn(allowed, w)
        self.assertIn('umask 077', w)
        self.assertIn("trap 'rm -rf \"$temp\"' EXIT", w)
        self.assertIn("if: always()", w)
        self.assertIn("retention-days: 14", w)
        self.assertIn("path: v86-private-worker-tls-posture.json", w)
        for forbidden in (
            "aws ecs update-service", "aws ecs run-task",
            "aws iam create", "aws ecr put-image",
            "aws secretsmanager get-secret-value",
            "aws ssm send-command", "terraform apply",
            "aws elbv2 create", "aws route53 change-resource-record-sets",
            "aws acm request-certificate", "aws ec2 authorize-security-group",
        ):
            self.assertNotIn(forbidden, w.lower())

    def test_evaluator_report_does_not_grant_model_or_order_authority(self):
        src = self.script
        self.assertIn('"worker_running": False', src)
        self.assertIn('"private_tls_handshake_from_java_verified": False', src)
        self.assertIn('"worker_artifacts_or_token_verified": False', src)
        self.assertIn('"live_execution_enabled": False', src)
        self.assertIn('"can_authorize_capital": False', src)
        self.assertIn('"verdict": "BLOCKED"', src)
        self.assertIn('"blocker_codes": sorted(blockers)', src)
        self.assertNotIn("aws secretsmanager", src.lower())
        self.assertNotIn("urllib.request", src)


if __name__ == "__main__":
    unittest.main()
