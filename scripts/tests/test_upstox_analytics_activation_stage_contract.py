"""Offline failure-stage and credential-isolation checks for Upstox Analytics activation.
No AWS, broker, mobile, network, EC2 or live order APIs are used.
"""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PAPER = ROOT / "infra/ec2-paper/run_paper.sh"
ACTIVATE = ROOT / "infra/ec2-paper/upstox_analytics_activate.sh"
WORKFLOW = ROOT / ".github/workflows/ec2-paper-upstox-analytics-activate.yml"


class UpstoxAnalyticsActivationContract(unittest.TestCase):
    def test_shell_scripts_parse_without_side_effects(self):
        for path in (PAPER, ACTIVATE):
            with self.subTest(path=path.name):
                result = subprocess.run(["bash", "-n", str(path)],
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_paper_release_emits_bounded_stage_even_on_bad_sha(self):
        result = subprocess.run(["bash", str(PAPER), "invalid-sha"],
                                capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MINTTICK_PAPER_FAILED_STAGE:HOST_PRECHECK", result.stderr)
        self.assertNotIn("POSTGRES_PASSWORD=", result.stderr)

    def test_known_release_stages_are_mapped_and_bounded(self):
        paper, activate, workflow = [p.read_text() for p in (PAPER, ACTIVATE, WORKFLOW)]
        stages = {
            "HOST_PRECHECK": "LOCAL_RELEASE_HOST_PRECHECK",
            "IMAGE_CHECK": "LOCAL_RELEASE_MISSING_IMAGE",
            "EGRESS_CHECK": "LOCAL_RELEASE_EGRESS_IP_DRIFT",
            "NETWORK_IDENTITY": "LOCAL_RELEASE_NETWORK_IDENTITY",
            "COMPOSE_CONFIG": "LOCAL_RELEASE_COMPOSE_CONFIG",
            "DATABASE_BACKUP": "LOCAL_RELEASE_DB_BACKUP",
            "COMPOSE_START": "LOCAL_RELEASE_COMPOSE_START",
            "API_HEALTH": "LOCAL_RELEASE_BACKEND_HEALTH",
            "WEB_HEALTH": "LOCAL_RELEASE_WEB_HEALTH",
            "PROXY_HEALTH": "LOCAL_RELEASE_PROXY_HEALTH",
            "BRIDGE_HEALTH": "LOCAL_RELEASE_LAMBDA_BRIDGE",
            "PORT_CONTRACT": "LOCAL_RELEASE_PORT_CONTRACT",
        }
        for stage, reason in stages.items():
            with self.subTest(stage=stage):
                self.assertIn("stage=" + stage, paper)
                self.assertIn(stage + ") echo '" + reason + "'; return ;;", activate)
                self.assertIn("'" + reason + "'", workflow)
        self.assertIn("row[0]=='ROLLBACK_UNVERIFIED' for row in approved", workflow)
        self.assertIn("MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED=true", activate)
        self.assertIn("MINTTICK_UPSTOX_RESULT:ACTIVATED_READ_ONLY", activate)
        self.assertNotIn("getParameterByPath", activate)
        self.assertNotIn("/v3/order/place", activate)


if __name__ == "__main__":
    unittest.main()
