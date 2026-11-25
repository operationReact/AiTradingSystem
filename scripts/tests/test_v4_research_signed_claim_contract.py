"""V85 offline policy: never let source signatures grant trading authority."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
VERIFY = ROOT / "intelligence/src/aitrading_intelligence/research_context_signature.py"
IMAGE_CONTEXT = ROOT / "intelligence/src/aitrading_intelligence/research_image_context.py"
BUNDLE = ROOT / "intelligence/src/aitrading_intelligence/research_release_bundle.py"
PROJECT = ROOT / "intelligence/pyproject.toml"


class SignedResearchSourcePolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = VERIFY.read_text()
        cls.project = PROJECT.read_text()

    def test_only_external_signature_verification_not_private_signing(self):
        s = self.source
        self.assertIn("serialization.load_pem_public_key", s)
        self.assertIn("isinstance(key, Ed25519PublicKey)", s)
        self.assertIn("hmac.compare_digest(fingerprint, expected_key_sha256)", s)
        self.assertIn("key.verify(signature, claim_bytes)", s)
        self.assertIn('if not SHA256.fullmatch(expected_key_sha256)', s)
        self.assertIn("_canonical(expected) + b", s)
        self.assertNotIn("Ed25519PrivateKey", s)
        self.assertNotIn("generate_private_key", s)
        self.assertNotIn("BEGIN PRIVATE KEY", s)
        self.assertNotIn("AWS_SECRET_ACCESS_KEY", s)

    def test_signature_is_bound_to_exact_v84_v80_context_and_sha(self):
        s = self.source
        self.assertIn("evidence = verify_context(context, code_sha)", s)
        self.assertIn('evidence["evidence_fingerprint"]', s)
        self.assertIn('evidence["bundle_receipt_fingerprint"]', s)
        self.assertIn('if claim_bytes != _canonical(expected) + b', s)
        self.assertIn('for path in (claim_path, signature_path, public_pem)', s)
        self.assertIn('if len(signature) != SIG_BYTES', s)
        self.assertIn("expected Git HEAD", IMAGE_CONTEXT.read_text())
        self.assertIn("model_receipt = verify_bundle(bundle, code_sha)", IMAGE_CONTEXT.read_text())
        self.assertIn("AUTHORITY_FALSE", BUNDLE.read_text())

    def test_source_rights_deployment_paper_soak_and_capital_always_false(self):
        s = self.source
        for field in (
            "source_rights_verified",
            "independent_model_custody_verified",
            "deployment_verified",
            "paper_shadow_soak_accepted",
            "broker_reconciliation_accepted",
            "live_execution_enabled",
            "can_authorize_capital",
        ):
            self.assertIn('"' + field + '"', s)
        self.assertIn('**{flag: False for flag in DENIED_AUTHORITIES}', s)
        self.assertNotIn('**{flag: True', s)
        self.assertIn('signature BLOCKED (sanitized)', s)
        self.assertIn('cryptography>=45,<49', self.project)
        self.assertIn('release = [', self.project)
        self.assertNotIn("aws ecr put-image", s)
        self.assertNotIn("aws ecs update-service", s)


if __name__ == "__main__":
    unittest.main()
