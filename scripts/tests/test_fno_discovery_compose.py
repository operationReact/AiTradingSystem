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

    def test_independent_last_trade_permissions_reach_backend_only_by_opt_in(self):
        compose = (ROOT / "infra/ec2-paper/paper-compose.yml").read_text()
        for flag in (
            "MINTTICK_UPSTOX_FNO_REST_LAST_PRICE_DISPLAY_RIGHTS_VERIFIED",
            "MINTTICK_UPSTOX_FNO_RETAINED_DISPLAY_RIGHTS_VERIFIED",
        ):
            self.assertIn(flag + ": " + "${" + flag + ":-false}", compose)
            self.assertNotIn(flag + ": " + "${" + flag + ":-true}", compose)
        # A general display grant must never enable LAST/retention implicitly.
        self.assertIn(
            "MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED: "
            "${MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED:-false}",
            compose,
        )

    def test_contract_discovery_does_not_replace_price_and_order_gates(self):
        compose = (ROOT / "infra/ec2-paper/paper-compose.yml").read_text()
        self.assertIn("MINTTICK_UPSTOX_FNO_DISPLAY_RIGHTS_VERIFIED", compose)
        self.assertIn("MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED", compose)


if __name__ == "__main__":
    unittest.main()
