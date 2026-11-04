"""Source-only V76 invariants: worker cannot start from a normal deploy."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKER = ROOT / "infra/terraform/research-worker-scaffold.tf"


class DormantWorkerTerraformTests(unittest.TestCase):
    def test_disabled_by_default_and_always_scaled_to_zero(self):
        s = WORKER.read_text(encoding="utf-8")
        self.assertIn('variable "research_worker_scaffold_enabled"', s)
        self.assertIn("default     = false", s)
        self.assertRegex(s, r"desired_count\s*=\s*0")
        self.assertIn("count = local.worker_scaffold_count", s)
        self.assertNotIn("ignore_changes = [desired_count]", s)
        self.assertNotIn("assign_public_ip = true", s)
        self.assertIn("assign_public_ip = false", s)

    def test_worker_has_no_trade_or_aws_task_permissions(self):
        s = WORKER.read_text(encoding="utf-8")
        self.assertIn('resource "aws_iam_role" "research_worker_task"', s)
        self.assertNotIn('aws_iam_role_policy_attachment" "research_worker_task', s)
        self.assertNotIn('aws_iam_role_policy" "research_worker_task', s)
        self.assertRegex(s, r"enable_execute_command\s*=\s*false")
        # V77 manages the Java ingress with a standalone VPC SG rule.
        private_rules = (ROOT / "infra/terraform/research-worker-private-endpoints.tf").read_text(encoding="utf-8")
        self.assertIn('resource "aws_vpc_security_group_ingress_rule" "worker_from_java"', private_rules)
        self.assertIn("referenced_security_group_id = aws_security_group.tasks.id", private_rules)
        self.assertIn('name = "AI_RESEARCH_WORKER_TOKEN"', s)
        self.assertIn("valueFrom = var.research_worker_token_secret_arn", s)
        self.assertIn('name = "TRADING_MODE", value = "PAPER"', s)
        self.assertNotIn("TRADING_MODE\", value = \"LIVE", s)

    def test_pins_image_and_aws_account_before_any_worker_creation(self):
        s = WORKER.read_text(encoding="utf-8")
        self.assertIn('image_tag_mutability = "IMMUTABLE"', s)
        self.assertIn('can(regex("^[0-9a-f]{40}$"', s)
        self.assertIn('data.aws_caller_identity.current.account_id == "816344830716"', s)
        self.assertIn('var.aws_region == "us-east-1"', s)
        self.assertIn('arn:aws:secretsmanager:us-east-1:816344830716:secret:', s)


if __name__ == "__main__":
    unittest.main()
