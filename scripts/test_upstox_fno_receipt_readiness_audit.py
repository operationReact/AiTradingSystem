"""Offline regression matrix for fail-closed NSE_FO readiness audit."""
import datetime as dt
import unittest

from upstox_fno_receipt_readiness_audit import audit


NOW = dt.datetime(2026, 11, 16, 4, 50, tzinfo=dt.timezone.utc)


def receipt():
    return {
        "rights": {"reviewed": True, "ephemeral_read": True, "private_display": True, "paper_simulation": True},
        "session": {"exchange": "NSE_FO", "state": "OPEN", "verified": True},
        "contracts": [{"exchange": "NSE_FO", "symbol": "NIFTY_TEST_CE", "expiry": "2026-11-17",
                       "lot_size": 65, "exchange_ltt": "2026-11-16T04:49:50+00:00",
                       "bid": 100.0, "ask": 101.0, "source_receipt_id": "test-only"}]
    }


class ReadinessTest(unittest.TestCase):
    def test_fresh_authorized_receipt(self):
        self.assertTrue(audit(receipt(), NOW)["ready"])

    def test_missing_rights(self):
        x = receipt()
        x["rights"]["paper_simulation"] = False
        self.assertEqual(audit(x, NOW)["verified"], 0)

    def test_market_closed_snapshot(self):
        x = receipt()
        x["session"]["state"] = "CLOSED"
        self.assertFalse(audit(x, NOW)["ready"])

    def test_stale_exchange_ltt(self):
        x = receipt()
        x["contracts"][0]["exchange_ltt"] = "2026-11-16T04:40:00Z"
        self.assertIn("QUOTE_STALE_OR_UNKNOWN", audit(x, NOW)["reasons"])

    def test_expired_contract(self):
        x = receipt()
        x["contracts"][0]["expiry"] = "2026-11-13"
        self.assertIn("EXPIRY_OR_LOT_INVALID", audit(x, NOW)["reasons"])

    def test_missing_receipt_id(self):
        x = receipt()
        x["contracts"][0].pop("source_receipt_id")
        self.assertFalse(audit(x, NOW)["ready"])

    def test_missing_contract_list_unknown_not_zero(self):
        self.assertIsNone(audit({}, NOW)["total"])

    def test_future_quote_rejected(self):
        x = receipt()
        x["contracts"][0]["exchange_ltt"] = "2026-11-16T04:51:00Z"
        self.assertFalse(audit(x, NOW)["ready"])


if __name__ == "__main__":
    unittest.main()
