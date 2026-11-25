"""Synthetic fixtures only; no broker API access or production evidence."""
import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from upstox_fo_receipt_gate import audit

BASE = {
    "session": {"open": True, "exchange": "NSE_FO", "as_of": "2026-11-16T04:30:10Z"},
    "contracts": [{"instrument_key": "fixture", "exchange": "NSE_FO",
                   "underlying": "NIFTY", "symbol": "NIFTY", "lot_size": 1,
                   "expiry": "2026-11-17", "side": "CE", "receipt_id": "fixture-c"}],
    "rights": [{"instrument_key": "fixture", "ephemeral_read": True,
                "private_display": True, "paper_simulation": True,
                "valid_from": "2026-11-16T00:00:00Z",
                "valid_until": "2026-11-17T00:00:00Z", "receipt_id": "fixture-r"}],
    "quotes": [{"instrument_key": "fixture", "exchange_time": "2026-11-16T04:30:05Z",
                "acquired_at": "2026-11-16T04:30:06Z", "receipt_id": "fixture-q"}]
}

class ReceiptGateTests(unittest.TestCase):
    def test_synthetic_valid(self):
        self.assertEqual(audit(BASE)["verified_unique_contracts"], 1)
    def test_rights_missing(self):
        data = copy.deepcopy(BASE); data["rights"] = []
        self.assertEqual(audit(data)["blocked_reasons"]["RIGHTS_UNVERIFIED"], 1)
    def test_stale_quote(self):
        data = copy.deepcopy(BASE)
        data["quotes"][0]["exchange_time"] = "2026-11-16T04:29:00Z"
        self.assertEqual(audit(data)["verified_unique_contracts"], 0)
    def test_closed_session(self):
        data = copy.deepcopy(BASE); data["session"]["open"] = False
        self.assertEqual(audit(data)["reason"], "SESSION_UNVERIFIED")
    def test_expired_contract(self):
        data = copy.deepcopy(BASE); data["contracts"][0]["expiry"] = "2026-11-15"
        self.assertEqual(audit(data)["blocked_reasons"]["CONTRACT_UNVERIFIED"], 1)
    def test_future_quote(self):
        data = copy.deepcopy(BASE)
        data["quotes"][0]["exchange_time"] = "2026-11-16T04:30:12Z"
        self.assertEqual(audit(data)["verified_unique_contracts"], 0)
    def test_deduplicates(self):
        data = copy.deepcopy(BASE); data["contracts"] *= 2
        self.assertEqual(audit(data)["verified_unique_contracts"], 1)
    def test_missing_receipts(self):
        data = copy.deepcopy(BASE); data.pop("quotes")
        self.assertEqual(audit(data)["reason"], "RECEIPTS_MISSING")

if __name__ == "__main__":
    unittest.main()
