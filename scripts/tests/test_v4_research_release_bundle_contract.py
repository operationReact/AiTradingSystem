"""V80 isolated source-only review of opt-in, no-cloud artifact packaging gates."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
INTELLIGENCE = ROOT / "intelligence"
PYTHON = INTELLIGENCE / "src/aitrading_intelligence/research_release_bundle.py"
RELEASE = INTELLIGENCE / "Dockerfile.research-release"
DEFAULT = INTELLIGENCE / "Dockerfile"


class ModelReleaseImageBoundary(unittest.TestCase):
    def test_local_staging_is_never_committed_or_sent_to_default_docker(self):
        gitignore = (ROOT / ".gitignore").read_text()
        dockerignore = (INTELLIGENCE / ".dockerignore").read_text()
        special = (INTELLIGENCE / "Dockerfile.research-release.dockerignore").read_text()
        self.assertIn("intelligence/.research-release/", gitignore)
        self.assertIn("intelligence/.research-staging-*/", gitignore)
        self.assertIn(".research-release/", dockerignore)
        self.assertIn("!.research-release/**", special)
        self.assertNotIn(".research-release", DEFAULT.read_text())

    def test_release_image_build_must_check_receipt_and_strict_runtime(self):
        docker = RELEASE.read_text()
        self.assertIn("COPY --chown=65532:65532 .research-release/ /opt/research/", docker)
        self.assertIn("research_release_bundle verify", docker)
        self.assertIn("--code-sha", docker)
        self.assertIn("RESEARCH_SOURCE_SHA", docker)
        self.assertIn("AI_RESEARCH_STRICT_BOOT_REQUIRED=true", docker)
        self.assertIn("research_startup_gate && exec uvicorn", docker)
        self.assertIn("USER 65532:65532", docker)
        self.assertNotIn("AI_RESEARCH_WORKER_TOKEN=", docker)

    def test_release_metadata_never_grants_authority(self):
        source = PYTHON.read_text()
        for field in (
            "independent_source_rights_verified",
            "independent_model_custody_verified", "deployment_verified",
            "broker_reconciliation_accepted", "paper_shadow_soak_accepted",
            "live_execution_enabled", "can_authorize_capital",
        ):
            self.assertIn('"' + field + '"', source)
        self.assertIn("inspect_worker_roster", source)
        self.assertIn("_safe_file", source)
        self.assertIn("unexpected release bundle inventory", source)
        self.assertIn("release staging target already exists", source)
        self.assertNotIn("boto3", source)
        self.assertNotIn("aws ecs", source)
        self.assertNotIn("aws ecr", source)


if __name__ == "__main__":
    unittest.main()
