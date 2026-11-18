"""Ensure an explicitly authorized F&O metadata flag reaches Spring Boot.

This is a configuration contract test only; it does not enable that flag.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class FnoDiscoveryComposeTest(unittest.TestCase):
    def test_contract_metadata_permission_is_passed_through_fail_closed(self):
        compose = (ROOT / "infra/ec2-paper/paper-compose.yml").read_text()
        flag = "MINTTICK_UPSTOX_FNO_CONTRACT_DISCOVERY_VERIFIED"
        self.assertIn(flag + ": " + "${" + flag + ":-false}", compose)
        self.assertNotIn(flag + ": " + "${" + flag + ":-true}", compose)

    def test_contract_discovery_does_not_replace_price_and_order_gates(self):
        compose = (ROOT / "infra/ec2-paper/paper-compose.yml").read_text()
        self.assertIn("MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED", compose)
        self.assertIn("MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED", compose)


if __name__ == "__main__":
    unittest.main()
