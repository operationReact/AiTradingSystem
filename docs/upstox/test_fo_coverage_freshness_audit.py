"""Offline regression tests for the read-only F&O receipt assessor."""
import unittest
from datetime import datetime, timezone, timedelta
from fo_coverage_freshness_audit import assess


NOW = datetime(2026, 11, 16, 5, 0, tzinfo=timezone.utc)


def receipt(**changes):
    base = {"source": "UPSTOX", "exchange": "NSE_FO",
            "readRightsVerified": True, "displayRightsVerified": True,
            "sessionState": "OPEN", "sessionVerified": True,
            "contracts": [{"underlying": "NIFTY", "optionType": "CE",
                           "listingVerified": True, "lotSize": 65,
                           "expiry": "2026-11-17",
                           "exchangeLtt": NOW.isoformat()}]}
    base.update(changes)
    return base


class AuditTests(unittest.TestCase):
    def test_fresh_is_not_order_permission(self):
        r = assess(receipt(), NOW)
        self.assertEqual(r["status"], "FRESH")
        self.assertTrue(r["tradeTimeReady"])
        self.assertEqual(r["freshContracts"], 1)

    def test_closed_market_is_unknown_not_live(self):
        r = assess(receipt(sessionState="CLOSED"), NOW)
        self.assertEqual(r["status"], "UNKNOWN")
        self.assertFalse(r["tradeTimeReady"])
        self.assertIsNone(r["freshContracts"])

    def test_rights_unknown_fail_closed(self):
        self.assertEqual(assess(receipt(displayRightsVerified=False), NOW)["reason"],
                         "RIGHTS_UNVERIFIED")

    def test_missing_receipts_distinct_from_unknown(self):
        self.assertEqual(assess(receipt(contracts=[]), NOW)["verifiedContracts"], 0)
        self.assertIsNone(assess(receipt(contracts=None), NOW)["verifiedContracts"])

    def test_old_or_future_quote_not_fresh(self):
        for offset in (-16, 1):
            data = receipt()
            data["contracts"][0]["exchangeLtt"] = (NOW + timedelta(seconds=offset)).isoformat()
            self.assertEqual(assess(data, NOW)["status"], "STALE")

    def test_expired_wrong_lot_and_unverified_listing(self):
        for change in ({"expiry": "2026-11-13"}, {"lotSize": 0},
                       {"listingVerified": False}, {"optionType": "FUT"}):
            data = receipt()
            data["contracts"][0].update(change)
            self.assertEqual(assess(data, NOW)["verifiedContracts"], 0)

    def test_unverified_exchange_time(self):
        data = receipt()
        data["contracts"][0]["exchangeLtt"] = None
        self.assertEqual(assess(data, NOW)["status"], "STALE")


if __name__ == "__main__":
    unittest.main()
