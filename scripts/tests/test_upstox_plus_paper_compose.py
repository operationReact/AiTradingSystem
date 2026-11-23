"""Static safety checks for opt-in, read-only Upstox Plus backend flags."""
from pathlib import Path
import unittest

COMPOSE = Path(__file__).resolve().parents[2] / "infra/ec2-paper/paper-compose.yml"

class TestUpstoxPlusPaperCompose(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = COMPOSE.read_text()

    def test_four_new_source_flags_have_safe_defaults(self):
        for flag in (
            "MINTTICK_UPSTOX_FNO_PLUS_FULL_CAPACITY_VERIFIED",
            "MINTTICK_UPSTOX_FNO_PLUS_MULTI_SOCKET_ENABLED",
            "MINTTICK_UPSTOX_FNO_AUTO_EXPAND_WATCHLIST_ENABLED",
        ):
            self.assertIn("      " + flag + ": $" + "{" + flag + ":-false}", self.text)
        flag = "MINTTICK_UPSTOX_FNO_AUTO_EXPAND_UNDERLYINGS"
        self.assertIn("      " + flag + ": $" + "{" + flag + ":-}", self.text)

    def test_live_orders_and_existing_primary_auto_control_remain_off_by_default(self):
        for flag in (
            "MINTTICK_UPSTOX_FNO_LIVE_ORDER_ENABLED",
            "MINTTICK_UPSTOX_FNO_AUTO_MARKET_FEED_ENABLED",
            "MINTTICK_UPSTOX_FNO_V3_FEED_ENABLED",
        ):
            self.assertIn(flag + ": $" + "{" + flag + ":-false}", self.text)
        self.assertIn("TRADING_MODE: PAPER", self.text)
        self.assertIn("127.0.0.1:8080:8080", self.text)

if __name__ == "__main__":
    unittest.main()
