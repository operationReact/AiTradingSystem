"""V78 deployment template contract: never start without strict research roster."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
TF = ROOT / "infra/terraform/research-worker-scaffold.tf"
DOCKER = ROOT / "intelligence/Dockerfile"
GATE = ROOT / "intelligence/src/aitrading_intelligence/research_startup_gate.py"


class StrictResearchWorkerBootTemplateTest(unittest.TestCase):
    def test_dormant_worker_must_require_strict_gate(self):
        terraform = TF.read_text(encoding="utf-8")
        self.assertIn('name = "AI_RESEARCH_STRICT_BOOT_REQUIRED", value = "true"', terraform)
        self.assertRegex(terraform, r"desired_count\s*=\s*0")
        self.assertIn('name = "TRADING_MODE", value = "PAPER"', terraform)
        self.assertIn('name = "AI_RESEARCH_WORKER_TOKEN"', terraform)
        self.assertIn('valueFrom = var.research_worker_token_secret_arn', terraform)
        self.assertNotIn("assign_public_ip = true", terraform)

    def test_container_boot_guard_cannot_be_bypassed_by_default_cmd(self):
        docker = DOCKER.read_text(encoding="utf-8")
        self.assertIn("python -m aitrading_intelligence.research_startup_gate && exec uvicorn", docker)
        self.assertIn("USER 65532:65532", docker)
        gate = GATE.read_text(encoding="utf-8")
        self.assertIn("inspect_worker_roster(path)", gate)
        self.assertIn('os.getenv("TRADING_MODE") != "PAPER"', gate)
        self.assertIn('if not 32 <= len(token) <= 4096', gate)
        self.assertIn("return 2", gate)


if __name__ == "__main__":
    unittest.main()
