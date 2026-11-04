"""V88 offline trust policy static regression checks (no AWS or private keys)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "intelligence/src/aitrading_intelligence/research_signer_trust.py"
WORKFLOW = ROOT / ".github/workflows/v4-external-signer-trust-contract.yml"
PYPROJECT = ROOT / "intelligence/pyproject.toml"


class ExternalSignerTrustPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text()
        cls.workflow = WORKFLOW.read_text()

    def test_independently_pinned_current_active_key_or_block(self):
        for guard in (
            "hmac.compare_digest(fingerprint, expected_policy_sha256)",
            "expected_key_sha256",
            '"active", "revoked"',
            "if key_fp == expected_key_sha256:",
            'selected[0] != "active"',
            "if not selected[1] <= instant < selected[2]:",
            "MAX_POLICY_AGE = timedelta(days=7)",
            "MAX_KEY_WINDOW = timedelta(days=90)",
            "verify_signed_claim(",
        ):
            self.assertIn(guard, self.source)
        self.assertIn("resolved_context in resolved_policy.parents", self.source)
        self.assertIn("contents != _canonical(policy)", self.source)

    def test_never_sign_publish_or_authorize(self):
        for guard in ("model_image_publish_allowed", "research_worker_activation_allowed",
                      "source_rights_verified", "independent_model_custody_verified",
                      "live_execution_enabled", "can_authorize_capital"):
            self.assertIn(guard, self.source)
        self.assertNotIn("Ed25519PrivateKey", self.source)
        self.assertNotIn("generate_private_key", self.source)
        self.assertNotIn("BEGIN PRIVATE KEY", self.source)
        self.assertNotIn("aws ecs update-service", self.source.lower())
        self.assertNotIn("aws ecr put-image", self.source.lower())
        self.assertIn("release = [", PYPROJECT.read_text())

    def test_ci_is_synthetic_read_only_and_not_deployment(self):
        self.assertIn("  pull_request:", self.workflow)
        self.assertIn("    branches: [dev]", self.workflow)
        self.assertIn("permissions:\n  contents: read", self.workflow)
        self.assertIn("python3 -m unittest discover", self.workflow)
        self.assertNotIn("id-token: write", self.workflow)
        for forbidden in (
            "configure-aws-credentials", "aws ecs", "aws ecr", "secretsmanager",
            "docker push", "terraform apply", "Ed25519PrivateKey",
        ):
            self.assertNotIn(forbidden, self.workflow)


if __name__ == "__main__":
    unittest.main()
