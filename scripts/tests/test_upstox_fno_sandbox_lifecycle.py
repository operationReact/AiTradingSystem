"""No-network safety and order lifecycle tests for Upstox sandbox-only probe."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "upstox_fno_sandbox_lifecycle.py"
spec = importlib.util.spec_from_file_location("fno_sandbox_probe", SCRIPT)
probe = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = probe
spec.loader.exec_module(probe)


class SandboxLifecycleTests(unittest.TestCase):
    KEY = "NSE_FO|61001"

    def scripted(self, place, modify=None, cancel=None):
        calls = []
        replies = iter((place, modify, cancel))
        def call(method, path, payload):
            calls.append((method, path, payload))
            return next(replies)
        return call, calls

    def test_complete_mocked_lifecycle_calls_sandbox_v3_once_each(self):
        call, calls = self.scripted(
            {"status": "success", "data": {"order_ids": ["123456"]}},
            {"status": "success", "data": {"order_id": "123456"}},
            {"status": "success", "data": {"order_id": "123456"}},
        )
        result = probe.run_cycle(call, self.KEY, 65, "100.05", "0.05")
        self.assertEqual([x[0] for x in calls], ["POST", "PUT", "DELETE"])
        self.assertEqual([x[1] for x in calls], [
            "/v3/order/place", "/v3/order/modify",
            "/v3/order/cancel?order_id=123456"])
        self.assertEqual(calls[0][2]["quantity"], 65)
        self.assertEqual(calls[0][2]["order_type"], "LIMIT")
        self.assertFalse(calls[0][2]["is_amo"])
        self.assertEqual(calls[1][2]["price"], 100.1)
        self.assertEqual(calls[1][2]["order_id"], "123456")
        self.assertEqual(result["environment"], "UPSTOX_SANDBOX_ONLY")
        self.assertTrue(result["placed"] and result["modified"] and result["cancelled"])
        self.assertEqual(len(result["orderFingerprint"]), 12)

    def test_ambiguous_place_ack_never_triggers_modify_or_second_order(self):
        for bad in ({"status": "success", "data": {"order_ids": []}},
                    {"status": "success", "data": {"order_ids": ["a", "b"]}},
                    {"status": "error", "data": {"order_ids": ["123"]}},
                    {"status": "success", "data": {"order_ids": [123]}},
                    None):
            with self.subTest(bad=bad):
                call, calls = self.scripted(bad)
                with self.assertRaises(probe.SandboxError):
                    probe.run_cycle(call, self.KEY, 65, "100.05", "0.05")
                self.assertEqual(len(calls), 1)
                self.assertEqual(calls[0][0], "POST")

    def test_modify_id_mismatch_stops_before_cancel(self):
        call, calls = self.scripted(
            {"status": "success", "data": {"order_ids": ["123456"]}},
            {"status": "success", "data": {"order_id": "other"}})
        with self.assertRaisesRegex(probe.SandboxError, "SANDBOX_MODIFY_ID_MISMATCH"):
            probe.run_cycle(call, self.KEY, 65, "100.05", "0.05")
        self.assertEqual([c[0] for c in calls], ["POST", "PUT"])

    def test_cancel_id_mismatch_reports_uncertainty_without_retry(self):
        call, calls = self.scripted(
            {"status": "success", "data": {"order_ids": ["123456"]}},
            {"status": "success", "data": {"order_id": "123456"}},
            {"status": "success", "data": {"order_id": "other"}})
        with self.assertRaisesRegex(probe.SandboxError, "SANDBOX_CANCEL_ID_MISMATCH"):
            probe.run_cycle(call, self.KEY, 65, "100.05", "0.05")
        self.assertEqual([c[0] for c in calls], ["POST", "PUT", "DELETE"])

    def test_wrong_contract_or_lot_or_price_never_calls_transport(self):
        cases = [
            ("NSE_EQ|INE669E01016", 65, "100.05", "0.05"),
            (self.KEY, True, "100.05", "0.05"),
            (self.KEY, 0, "100.05", "0.05"),
            (self.KEY, 100001, "100.05", "0.05"),
            (self.KEY, 65, "100.03", "0.05"),
            (self.KEY, 65, "not-a-price", "0.05"),
            (self.KEY, 65, "100.05", "0"),
        ]
        for key, qty, price, tick in cases:
            with self.subTest(key=key, quantity=qty, price=price, tick=tick):
                calls = []
                with self.assertRaises(probe.SandboxError):
                    probe.run_cycle(lambda *args: calls.append(args), key, qty, price, tick)
                self.assertEqual(calls, [])

    def test_only_fixed_sandbox_host_and_approved_paths(self):
        self.assertEqual(probe.SANDBOX, "https://sandbox.upstox.com")
        self.assertNotIn("api-hft.upstox.com", probe.SANDBOX)
        with self.assertRaises(probe.SandboxError):
            probe.sandbox_transport("bad\nheader")
        call = probe.sandbox_transport("sandbox-fixture-not-a-real-token")
        with self.assertRaisesRegex(probe.SandboxError, "SANDBOX_ENDPOINT_NOT_ALLOWED"):
            call("POST", "https://api-hft.upstox.com/v3/order/place", {})
        with self.assertRaisesRegex(probe.SandboxError, "SANDBOX_ENDPOINT_NOT_ALLOWED"):
            call("GET", "/v2/user/profile", None)
        with self.assertRaisesRegex(probe.SandboxError, "SANDBOX_ENDPOINT_NOT_ALLOWED"):
            call("DELETE", "/v3/order/cancel?order_id=abc&url=other", None)

    def test_default_cli_performs_no_network_and_explicit_run_needs_token(self):
        out = io.StringIO()
        with patch.dict(os.environ, {"UPSTOX_SANDBOX_TOKEN": ""}), contextlib.redirect_stdout(out):
            self.assertEqual(probe.main([]), 0)
            self.assertEqual(probe.main(["--execute-sandbox"]), 2)
        self.assertIn("SANDBOX_DRY_RUN_ONLY", out.getvalue())
        self.assertIn("SANDBOX_TOKEN_NOT_CONFIGURED", out.getvalue())


if __name__ == "__main__":
    unittest.main()
