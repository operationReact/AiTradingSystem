"""V84 isolated source contract: exact Git commit, no AWS or Docker execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PREP = ROOT / "intelligence/src/aitrading_intelligence/research_image_context.py"
DOCKER = ROOT / "intelligence/Dockerfile.research-release"
BUNDLE = ROOT / "intelligence/src/aitrading_intelligence/research_release_bundle.py"


class ResearchImageSourceContextContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = PREP.read_text(encoding="utf-8")
        cls.docker = DOCKER.read_text(encoding="utf-8")
        cls.bundle = BUNDLE.read_text(encoding="utf-8")

    def test_source_only_from_exact_verified_git_head_archive(self):
        s = self.source
        self.assertIn('"rev-parse", "--verify", "HEAD"', s)
        self.assertIn('"rev-parse", "--show-toplevel"', s)
        self.assertIn('actual != expected_sha', s)
        self.assertIn('"git", "archive", "--format=tar", sha', s)
        self.assertIn('if not member.isfile() or not _allow_source(member.name)', s)
        self.assertIn('MAX_SOURCE_BYTES = 24 * 1024 * 1024', s)
        self.assertIn('MAX_SOURCE_FILES = 500', s)
        self.assertIn('MAX_FILE_BYTES = 4 * 1024 * 1024', s)
        self.assertIn('if not REQUIRED.issubset(names)', s)
        self.assertIn('"README.md"', s)
        self.assertIn('"Dockerfile.research-release.dockerignore"', s)
        self.assertNotIn("git checkout --", s)

    def test_independent_model_sha_and_exact_private_context(self):
        s = self.source
        self.assertIn('model_receipt = verify_bundle(bundle, code_sha)', s)
        self.assertIn('verify_bundle(dest_bundle, code_sha) != model_receipt', s)
        self.assertIn('verify_context(staging, code_sha)', s)
        self.assertIn('if destination.exists() or destination.is_symlink()', s)
        self.assertIn('if repo == destination or repo in destination.parents', s)
        self.assertIn('if _safe_inventory(context) != expected', s)
        self.assertIn('"deployment_verified": False', s)
        self.assertIn('"can_authorize_capital": False', s)
        self.assertIn('staging.chmod(0o700)', s)
        self.assertIn('path.chmod(0o600)', s)
        self.assertIn('release-receipt.json', self.bundle)
        self.assertIn('research_release_bundle verify', self.docker)
        self.assertIn('AI_RESEARCH_STRICT_BOOT_REQUIRED=true', self.docker)
        self.assertNotIn('docker build', s)
        self.assertNotIn('aws ecs ', s)
        self.assertNotIn('aws ecr ', s)

    def test_no_release_authority_claimed(self):
        s = self.source
        self.assertIn('no digital', s.lower() if 'no digital' in s.lower() else
                      'The local checksum evidence is NOT an independently signed provenance claim.')
        self.assertIn('image NOT built or published', s)
        self.assertIn('V84 research image source context BLOCKED (sanitized)', s)
        self.assertNotIn('can_authorize_capital": True', s)
        self.assertNotIn('deployment_verified": True', s)


if __name__ == "__main__":
    unittest.main()
