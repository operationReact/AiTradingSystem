"""Offline security contract for the V73 inspection-only GitHub OIDC IAM role."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TRUST = ROOT / "infra/aws/readonly-v73-oidc-trust.json"
POLICY = ROOT / "infra/aws/readonly-v73-inspection-policy.json"

EXPECTED_ACTIONS = {
    "sts:GetCallerIdentity",
    "ecs:DescribeClusters",
    "ecs:DescribeServices",
    "ecs:DescribeTaskDefinition",
    "elasticloadbalancing:DescribeTargetGroups",
    "elasticloadbalancing:DescribeTargetHealth",
}


class ReadonlyOidcContract(unittest.TestCase):
    def test_exact_dev_repo_oidc_audience_and_subject(self):
        trust = json.loads(TRUST.read_text(encoding="utf-8"))
        self.assertEqual(trust["Version"], "2012-10-17")
        self.assertEqual(len(trust["Statement"]), 1)
        statement = trust["Statement"][0]
        self.assertEqual(statement["Effect"], "Allow")
        self.assertEqual(statement["Action"], "sts:AssumeRoleWithWebIdentity")
        self.assertEqual(statement["Principal"], {
            "Federated": "arn:aws:iam::816344830716:oidc-provider/token.actions.githubusercontent.com"
        })
        self.assertEqual(statement["Condition"], {"StringEquals": {
            "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
            "token.actions.githubusercontent.com:sub": "repo:operationReact/AiTradingSystem:ref:refs/heads/dev"
        }})

    def test_only_necessary_read_actions_and_known_resource_scopes(self):
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        self.assertEqual(policy["Version"], "2012-10-17")
        statements = policy["Statement"]
        self.assertEqual(len(statements), 5)
        self.assertTrue(all(item["Effect"] == "Allow" for item in statements))
        actions = set()
        for item in statements:
            self.assertIsInstance(item["Action"], list)
            actions.update(item["Action"])
            self.assertTrue(all("*" not in action for action in item["Action"]))
            self.assertFalse(any(x in item for x in ("NotAction", "NotResource", "Condition")))
        self.assertEqual(actions, EXPECTED_ACTIONS)
        resources = {item["Sid"]: item["Resource"] for item in statements}
        self.assertEqual(resources["ReadOnlyKnownPaperCluster"],
                         "arn:aws:ecs:us-east-1:816344830716:cluster/ai-trading-system")
        self.assertEqual(set(resources["ReadOnlyKnownPaperServices"]), {
            "arn:aws:ecs:us-east-1:816344830716:service/ai-trading-system/ai-trading-system-backend",
            "arn:aws:ecs:us-east-1:816344830716:service/ai-trading-system/ai-trading-system-research-worker",
        })
        # AWS service-authorization table has no resource type for this action.
        self.assertEqual(resources["ReadOnlyBackendTaskDefinition"], "*")
        self.assertEqual(resources["ReadOnlyALBTargetHealth"], "*")
        self.assertEqual(resources["VerifyCallerAccount"], "*")

    def test_workflow_uses_separate_read_only_variable_and_no_aws_mutations(self):
        workflow = (ROOT / ".github/workflows/v4-paper-runtime-inspect.yml").read_text(
            encoding="utf-8")
        self.assertIn("on:\n  workflow_dispatch:", workflow)
        self.assertIn("vars.AWS_READONLY_ROLE_ARN", workflow)
        self.assertNotIn("vars.AWS_ROLE_ARN", workflow)
        self.assertNotIn("aws ecs update-service", workflow)
        self.assertNotIn("aws iam", workflow)
        self.assertNotIn("aws secretsmanager get-secret-value", workflow)


if __name__ == "__main__":
    unittest.main()
