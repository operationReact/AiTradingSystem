"""V82: dedicated backend role and disabled research transport wire-up contract."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "infra/terraform/main.tf"
PREP = ROOT / "infra/terraform/research-worker-java-secret-prep.tf"
WORKER = ROOT / "infra/terraform/research-worker-scaffold.tf"
TLS = ROOT / "infra/terraform/research-worker-private-tls.tf"
YAML = ROOT / "backend/src/main/resources/application.yml"


class JavaWorkerSecretPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.prep = PREP.read_text(encoding="utf-8")
        cls.worker = WORKER.read_text(encoding="utf-8")
        cls.tls = TLS.read_text(encoding="utf-8")
        cls.app = YAML.read_text(encoding="utf-8")

    def test_not_enabled_by_default_and_guarded_by_https_prerequisites(self):
        self.assertIn('variable "research_worker_java_secret_prep_enabled"', self.prep)
        self.assertRegex(self.prep, r"default\s*=\s*false")
        self.assertIn(
            "worker_java_secret_prep_count = var.research_worker_java_secret_prep_enabled ? 1 : 0",
            self.prep,
        )
        for field in (
            "var.research_worker_tls_enabled",
            "var.research_worker_scaffold_enabled",
            "var.research_worker_private_endpoints_enabled",
            "var.enable_https",
            'var.aws_region == "us-east-1"',
        ):
            self.assertIn(field, self.prep)
        self.assertIn("worker_tls_fqdn", self.tls)

    def test_backend_uses_separate_secret_reading_execution_role(self):
        self.assertIn('resource "aws_iam_role" "worker_java_backend_execution"', self.prep)
        self.assertIn("aws_iam_role.worker_java_backend_execution[0].id", self.prep)
        self.assertIn("arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy",
                      self.prep)
        self.assertIn(
            "var.research_worker_java_secret_prep_enabled "
            "? aws_iam_role.worker_java_backend_execution[0].arn "
            ": aws_iam_role.ecs_execution.arn",
            self.main,
        )
        self.assertIn("aws_iam_role_policy.worker_java_backend_secrets", self.main)
        self.assertIn(
            "aws_iam_role_policy_attachment.worker_java_backend_execution_managed",
            self.main,
        )
        self.assertNotIn("aws_iam_role.ecs_execution[0]", self.prep)
        self.assertNotIn('resource "aws_iam_role_policy" "worker_java_backend_task"', self.prep)
        # The shared ECS execution role is never granted the research token.
        shared = self.main.split('resource "aws_iam_role_policy" "ecs_secrets"')[1]
        shared = shared.split('resource "aws_iam_role" "ecs_task"')[0]
        self.assertNotIn("research_worker_token_secret_arn", shared)
        frontend = self.main.split('resource "aws_ecs_task_definition" "frontend"')[1]
        frontend = frontend.split('resource "aws_ecs_service" "backend"')[0]
        self.assertIn("execution_role_arn       = aws_iam_role.ecs_execution.arn", frontend)

    def test_same_secret_ref_is_used_by_java_and_python_without_plaintext(self):
        self.assertIn('var.research_worker_token_secret_arn', self.prep)
        self.assertIn(
            '{ name = "AI_RESEARCH_WORKER_TOKEN", valueFrom = var.research_worker_token_secret_arn }',
            self.main,
        )
        self.assertIn(
            '{ name = "AI_RESEARCH_WORKER_TOKEN", valueFrom = var.research_worker_token_secret_arn }',
            self.worker,
        )
        self.assertNotIn("random_password", self.prep)
        self.assertNotIn("secret_string", self.prep)
        self.assertNotIn("aws_secretsmanager_secret_version", self.prep)
        self.assertNotIn('name = "AI_RESEARCH_WORKER_TOKEN", value =', self.main)

    def test_prepared_backend_client_and_readiness_are_always_off(self):
        self.assertIn(
            '{ name = "AI_RESEARCH_WORKER_URL", value = "https://${local.worker_tls_fqdn}" }',
            self.main,
        )
        for flag in ("AI_RESEARCH_WORKER_CLIENT_ENABLED", "AI_RESEARCH_WORKER_READINESS_ENABLED"):
            self.assertIn('{ name = "' + flag + '", value = "false" }', self.main)
            self.assertNotIn('{ name = "' + flag + '", value = "true" }', self.main)
        self.assertIn("AI_RESEARCH_WORKER_CLIENT_ENABLED:false", self.app)
        self.assertIn("AI_RESEARCH_WORKER_READINESS_ENABLED:false", self.app)
        self.assertIn("desired_count                     = 0", self.worker)
        self.assertIn("enable_execute_command            = false", self.worker)


if __name__ == "__main__":
    unittest.main()
