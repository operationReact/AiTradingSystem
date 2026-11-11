"""Offline contract checks: never contact AWS, EC2, brokers or GitHub."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/minttick-owner-private-candle-pilot.yml"


class OwnerHistorySsmTimeoutContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = WORKFLOW.read_text()

    def test_audit_and_download_have_bounded_explicit_ssm_execution(self):
        src = self.source
        self.assertIn("execution_timeout = '180' if mode == 'audit-only' else '660'", src)
        self.assertIn("'executionTimeout':[execution_timeout]", src)
        self.assertIn("delivery_timeout=120", src)
        self.assertIn('--timeout-seconds "$delivery_timeout"', src)
        self.assertNotIn("--timeout-seconds 720", src)

    def test_polling_outlives_ssm_timeouts_and_has_finite_limit(self):
        src = self.source
        # Audit < 300 seconds max SSM; 96 checks * 5s = 480s.
        # Download < 780 seconds max SSM; 180 checks * 5s = 900s.
        self.assertIn("poll_attempts=96", src)
        self.assertIn('poll_attempts=180', src)
        self.assertIn('seq 1 "$poll_attempts"', src)

    def test_offline_pull_request_tests_do_not_wait_for_ec2_host_lock(self):
        src = self.source
        self.assertNotIn("\\nconcurrency:\\n  group: aitrading-paper-ec2-manual-lifecycle", src)
        self.assertIn("  execute:\\n    # Only host mutations share the EC2 lane.", src)
        self.assertIn("      group: aitrading-paper-ec2-manual-lifecycle", src)

    def test_terminal_error_is_sanitized_without_logging_ssm_payload(self):
        src = self.source
        for marker in ("Delivery Timed Out", "Execution Timed Out",
                       "Undeliverable", "Terminated", "OWNER_SSM_TERMINAL: ",
                       "OWNER_AUDIT_INCOMPLETE: MISSING_VERIFIED_MARKERS"):
            self.assertIn(marker, src)
        self.assertIn("max_provider_calls", (ROOT / "infra/ec2-paper/"
                                              "owner_personal_history_request.py").read_text())


if __name__ == "__main__":
    unittest.main()
