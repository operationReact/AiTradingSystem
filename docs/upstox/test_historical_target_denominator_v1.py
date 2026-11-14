"""Offline contract tests; no provider reads or production DB access."""
import unittest
from datetime import date
from historical_target_denominator_v1 import Target, Receipt, evaluate, partition_keys


def target(**overrides):
    values = dict(provider="UPSTOX", exchange="NSE_EQ",
                  universe_asof=date(2026, 11, 14), listing_id="listing-1",
                  instrument_id="NSE_EQ|TEST", timeframe="day",
                  start=date(2026, 1, 1), end_exclusive=date(2026, 3, 1),
                  source_domain="historical-candle", rights_receipt_id="rights-1")
    values.update(overrides)
    return Target(**values)


class HistoricalDenominatorTests(unittest.TestCase):
    def test_unknown_universe_is_not_zero_percent(self):
        self.assertEqual(evaluate(None, [], False)["state"], "UNKNOWN_TARGET")

    def test_missing_rights_fail_closed(self):
        self.assertEqual(evaluate([target()], [], False)["target"], None)
        self.assertEqual(evaluate([target(rights_receipt_id=None)], [], True)["state"],
                         "RIGHTS_UNVERIFIED")

    def test_fixed_window_excludes_end_month(self):
        self.assertEqual(len(list(partition_keys(target()))), 2)

    def test_verified_partition_unique_and_source_rights_checked(self):
        r = Receipt("listing-1", "NSE_EQ|TEST", "day", 2026, 1, "VERIFIED",
                    "rights-1", "historical-candle")
        bad = Receipt("listing-1", "NSE_EQ|TEST", "day", 2026, 2, "VERIFIED",
                      "wrong-rights", "historical-candle")
        result = evaluate([target()], [r, r, bad], True)
        self.assertEqual((result["target"], result["verified"], result["missing"]), (2, 1, 1))

    def test_duplicate_target_rejected(self):
        with self.assertRaises(ValueError):
            evaluate([target(), target()], [], True)

    def test_invalid_window_rejected(self):
        with self.assertRaises(ValueError):
            list(partition_keys(target(start=date(2026, 3, 1))))


if __name__ == "__main__":
    unittest.main()
