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

    def test_analytics_uses_backend_only_recreation(self):
        activate, workflow = ACTIVATE.read_text(), WORKFLOW.read_text()
        self.assertIn("up -d --no-deps --no-build --force-recreate backend", activate)
        self.assertNotIn('bash "$root/run_paper.sh" "$sha"', activate)
        self.assertNotIn("pg_dump", activate)
        self.assertNotIn("/v3/order/place", activate)
        self.assertIn("stage=backend_precheck", activate)
        self.assertIn("stage=config_check", activate)
        self.assertIn("stage=backend_recreate", activate)
        self.assertIn("stage=backend_health", activate)
        self.assertIn("stage=read_only_probe", activate)
        self.assertIn('assert e["TRADING_MODE"]=="PAPER"', activate)
        self.assertIn("modelTrainingRightsVerified", activate)
        self.assertIn("MINTTICK_UPSTOX_RESULT:ACTIVATED_READ_ONLY", activate)
        self.assertIn("MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED=true", activate)
        self.assertIn("SSM Success is not acceptance", workflow)

    def test_backend_failure_restores_both_private_env_files(self):
        activate, workflow = ACTIVATE.read_text(), WORKFLOW.read_text()
        self.assertIn('"$work/previous.env" "$root/runtime.env"', activate)
        self.assertIn('"$work/previous.compose.env" "$root/.env"', activate)
        self.assertIn("rollback_state=unverified", activate)
        self.assertIn("reason=ROLLBACK_UNVERIFIED", activate)
        self.assertIn('if [ "$rc" -eq 0 ] && [ "$accepted" -ne 1 ]; then exit 9; fi', activate)
        for reason in ("BACKEND_PRECHECK_UNHEALTHY", "POSTGRES_NOT_RUNNING",
                       "BACKEND_CONFIG_INVALID", "BACKEND_RECREATE_FAILED",
                       "BACKEND_RESTART_UNHEALTHY", "ROLLBACK_UNVERIFIED"):
            self.assertIn("fail " + reason if reason != "ROLLBACK_UNVERIFIED"
                          else "rollback_state=unverified", activate)
            self.assertIn("'" + reason + "'", workflow)


if __name__ == "__main__":
    unittest.main()
