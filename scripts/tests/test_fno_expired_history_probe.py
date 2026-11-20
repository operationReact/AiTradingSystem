"""Offline, source-backed schema acceptance tests: NO broker or AWS calls."""
from datetime import date
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import fno_expired_history_probe as p

NOW = date(2026, 11, 21)
EX = date(2026, 11, 19)
KEY = "NSE_FO|12345|19-11-2026"


def expiries():
    return {"status": "success", "data": ["2026-11-19", "2026-11-12", "2026-11-25"]}


def future():
    return {"status": "success", "data": [{
        "segment": "NSE_FO", "exchange": "NSE", "instrument_type": "FUT",
        "underlying_key": "NSE_INDEX|Nifty 50",
        "expiry": EX.isoformat(), "instrument_key": KEY, "lot_size": 65,
    }]}


def bars():
    return {"status": "success", "data": {"candles": [
        ["2026-11-18T00:00:00+05:30", 100, 105, 98, 103, 1000, 2000],
        ["2026-11-19T00:00:00+05:30", 104, 106, 103, 105, 1500, 1800],
    ]}}


class ExpiredHistoryContractTests(unittest.TestCase):
    def test_approved_request_is_read_only_and_bounded(self):
        contract = p.expected_request()
        self.assertEqual(contract["max_provider_gets"], 3)
        self.assertEqual(contract["environment"], "PAPER")
        self.assertFalse(contract["persist"])
        self.assertFalse(contract["trading_rights_change"])

    def test_genuine_schema_path_three_reads_only(self):
        with patch.object(p, "get_json", side_effect=[expiries(), future(), bars()]) as get:
            self.assertEqual(p.probe("fixture-only", NOW),
                             ("HISTORY_SOURCE_VERIFIED", 2, 1, 2))
            self.assertEqual(get.call_count, 3)
            self.assertIn("/expiries?", get.call_args_list[0].args[0])
            self.assertIn("/future/contract?", get.call_args_list[1].args[0])
            self.assertIn("/historical-candle/", get.call_args_list[2].args[0])
            self.assertIn("%7C", get.call_args_list[2].args[0])

    def test_empty_expiries_stop_after_one_read(self):
        with patch.object(p, "get_json", return_value={"status":"success", "data":[]}) as get:
            self.assertEqual(p.probe("fixture", NOW)[0], "NO_EXPIRED_EXPIRIES")
            get.assert_called_once()

    def test_no_matching_futures_skips_candle_request(self):
        with patch.object(p, "get_json", side_effect=[expiries(), {"status":"success","data":[]}]) as get:
            self.assertEqual(p.probe("fixture", NOW)[0], "NO_MATCHING_FUTURES")
            self.assertEqual(get.call_count, 2)

    def test_plus_plan_and_api_error_codes(self):
        self.assertEqual(p.classify_http(403, b'{"code":"UDAPI1149"}'),
                         "PLUS_ENTITLEMENT_MISSING")
        self.assertEqual(p.classify_http(401, b"other"), "AUTH_REJECTED")
        self.assertEqual(p.classify_http(429, b"other"), "RATE_LIMITED")
        self.assertEqual(p.classify_http(503, b"other"), "PROVIDER_UNAVAILABLE")

    def test_source_expiry_and_identity_checks(self):
        bad = future()
        bad["data"][0]["expiry"] = "2026-11-12"
        self.assertEqual(p.future_key(bad, EX), (None, 0))
        bad["data"][0]["expiry"] = EX.isoformat()
        bad["data"][0]["instrument_key"] = "NSE_EQ|123"
        with self.assertRaises(p.Blocked):
            p.future_key(bad, EX)

    def test_ohlc_and_open_interest_reject_bad_provider_values(self):
        for idx, wrong in ((2, 50), (6, -1), (5, True)):
            broken = bars()
            broken["data"]["candles"][0][idx] = wrong
            with self.subTest(idx=idx), self.assertRaises(p.Blocked):
                p.count_candles(broken, date(2026,11,12), EX)

    def test_no_duplicates_out_of_window_or_fake_success(self):
        duplicate = bars()
        duplicate["data"]["candles"][1] = duplicate["data"]["candles"][0][:]
        with self.assertRaises(p.Blocked):
            p.count_candles(duplicate, date(2026,11,12), EX)
        outside = bars()
        outside["data"]["candles"][0][0] = "2026-11-01T00:00:00+05:30"
        with self.assertRaises(p.Blocked):
            p.count_candles(outside, date(2026,11,12), EX)
        with self.assertRaises(p.Blocked):
            p.expiries({"status": "error", "data": []}, NOW)

    def test_receipt_does_not_claim_rights_or_store_bars(self):
        good = p.receipt("HISTORY_SOURCE_VERIFIED", 2, 1, 2)
        self.assertEqual(p.parse_receipt("private bearer secret\n" + good),
                         ("HISTORY_SOURCE_VERIFIED", 2, 1, 2))
        self.assertIn("stored=false rights=unverified orders=false", good)
        self.assertNotIn("bearer secret", good)
        for invalid in (
            good + "\n" + good,
            good.replace("rights=unverified", "rights=true"),
            good.replace("candles=2", "candles=5000"),
            good.replace("HISTORY_SOURCE_VERIFIED", "ALL_DONE"),
        ):
            self.assertIsNone(p.parse_receipt(invalid))

    def test_blocked_reason_is_not_fake_positive(self):
        for code in ("PLUS_ENTITLEMENT_MISSING", "AUTH_REJECTED", "TOKEN_UNAVAILABLE"):
            line = p.receipt(code)
            self.assertEqual(p.parse_receipt(line)[0], code)
            self.assertIn("stored=false", line)


if __name__ == "__main__":
    unittest.main()
