"""Offline checks for exact one-call Angel One daily 2006 depth probe."""
import datetime as dt
import pathlib
import re
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
AUTH = ROOT / "infra/ec2-paper/probe_owner_angelone_auth.py"
PROBE = ROOT / "infra/ec2-paper/probe_angelone_2006_depth.py"


class AngelOneDepthProbeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        auth = AUTH.read_text()
        probe = PROBE.read_text()
        assert '\nif __name__ == "__main__":' in auth
        cls.source = auth.split('\nif __name__ == "__main__":', 1)[0] + '\n' + probe
        cls.module = {"__name__": "_offline_probe_test"}
        exec(compile(cls.source, "<synthetic-inmemory-probe>", "exec"), cls.module)

    def test_exact_first_calendar_window_and_budget(self):
        m = self.module
        self.assertEqual(m["START"], dt.date(2006, 11, 10))
        self.assertEqual((m["END"] - m["START"]).days + 1, 2000)
        self.assertEqual(m["MAX_ROWS"], 2000)
        self.assertLessEqual(m["MAX_RESPONSE_BYTES"], 262144)
        self.assertEqual(m["NSE_TOKEN"], "2885")

    def test_only_four_explicit_windows_with_last_bounded_at_2026(self):
        expected = [
            ("2006-11-10", "2012-05-01"),
            ("2012-05-02", "2017-10-22"),
            ("2017-10-23", "2023-04-14"),
            ("2023-04-15", "2026-11-10"),
        ]
        for phase, (start, end) in enumerate(expected, 1):
            with self.subTest(phase=phase):
                ns = {"__name__": "_offline_probe_test"}
                with patch.dict("os.environ", {"MINTTICK_DEPTH_PHASE": str(phase)}):
                    exec(compile(self.source, "<phase-verified>", "exec"), ns)
                self.assertEqual(ns["START"].isoformat(), start)
                self.assertEqual(ns["END"].isoformat(), end)
                self.assertLessEqual((ns["END"] - ns["START"]).days + 1, 2000)
        with patch.dict("os.environ", {"MINTTICK_DEPTH_PHASE": "5"}):
            with self.assertRaises(ValueError):
                exec(compile(self.source, "<invalid-phase>", "exec"),
                     {"__name__": "_offline_probe_test"})

    def test_sanitized_response_validation_and_date_coverage(self):
        row = ["2006-11-10T09:15:00+05:30", 105, 110, 90, 100, 1000]
        state, report = self.module["candle_summary"]({"status": True, "data": [row]})
        self.assertEqual(state, "NONEMPTY")
        self.assertEqual(report[0], 1)
        self.assertEqual(report[1], "2006-11-10")
        self.assertEqual(report[3], {2006: 1})
        self.assertEqual(self.module["candle_summary"]({"status": True, "data": []})[0],
                         "EMPTY_OR_UNSUPPORTED")
        self.assertEqual(self.module["candle_summary"]({"status": False, "data": []})[0],
                         "PROVIDER_REJECTED")

    def test_rejects_corrupted_and_unexpected_historical_payload(self):
        normal = ["2006-11-10T09:15:00+05:30", 105, 110, 90, 100, 1000]
        other = ["2025-11-10T09:15:00+05:30", 105, 110, 90, 100, 1000]
        self.assertEqual(self.module["candle_summary"](
            {"status": True, "data": [other]})[0], "SOURCE_OUT_OF_RANGE")
        self.assertEqual(self.module["candle_summary"](
            {"status": True, "data": [normal, normal]})[0], "INCONSISTENT_CANDLES")
        self.assertEqual(self.module["candle_summary"](
            {"status": True, "data": [normal[:4]]})[0], "INVALID_RESPONSE")
        invalid_price = ["2006-11-10T09:15:00+05:30", 105, 80, 90, 100, 1000]
        self.assertEqual(self.module["candle_summary"](
            {"status": True, "data": [invalid_price]})[0], "INCONSISTENT_CANDLES")

    def test_one_history_request_uses_hardcoded_exchange_interval_and_boundaries(self):
        class FakeResponse:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self, n):
                self.read_limit = n
                return b'{"status":true,"data":[]}'
        fake = FakeResponse()
        with patch.dict(self.module, {"urlopen": lambda req, timeout: fake}):
            state, _ = self.module["history_request"]({"Authorization": "Bearer sample"})
        self.assertEqual(state, "EMPTY_OR_UNSUPPORTED")
        self.assertEqual(fake.read_limit, 262145)

    def test_code_does_not_persist_source_or_place_orders(self):
        p = PROBE.read_text()
        for forbidden in [
            "placeOrder", "modifyOrder", "cancelOrder", "boto3",
            "s3.put", "INSERT INTO", "write_bytes", "write_text",
            "print(jwt)", "print(auth)", "print(params)", "print(content)",
        ]:
            self.assertNotIn(forbidden, p)
        self.assertIn("DEPTH_STORED: FALSE", p)
        self.assertIn("DEPTH_LICENCE_VERIFIED: FALSE", p)
        self.assertIn("return history_request(", p)


if __name__ == "__main__":
    unittest.main()
