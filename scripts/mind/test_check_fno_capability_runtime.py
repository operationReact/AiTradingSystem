"""No-network, no-order evidence tests for protected F&O capability routes."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SRC = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "check_fno_capability_runtime", SRC / "check_fno_capability_runtime.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def ready(**kwargs):
    value = {
        "sourceReady": True,
        "feedConfigured": True,
        "marketOpen": False,
        "displayRightsVerified": False,
        "contractDiscoveryVerified": True,
        "status": "CONTRACTS_LISTED_PRICES_NOT_AUTHORIZED",
        "streamState": "UNAVAILABLE",
        "freshExchangeTimedQuotes": 0,
        "freshDisplayQuotes": 0,
        "subscribedInstruments": 0,
        "instrumentKey": "NSE_FO|SECRET_INSTRUMENT_SHOULD_NOT_APPEAR",
        "accessToken": "SUPERSECRET",
    }
    value.update(kwargs)
    return json.dumps(value).encode()


class FnoCapabilityTests(unittest.TestCase):
    def test_afterhours_blockers_observed_without_order_authorization(self):
        line, ok = probe.capability(200, ready())
        self.assertTrue(ok)
        self.assertIn("sourceReady=true", line)
        self.assertIn("marketOpen=false", line)
        self.assertIn("displayRightsVerified=false", line)
        self.assertIn("status=CONTRACTS_LISTED_PRICES_NOT_AUTHORIZED", line)
        self.assertIn("orders=false;independentE2E=false", line)
        self.assertNotIn("SECRET", line)
        self.assertNotIn("NSE_FO", line)

    def test_capability_rejects_inconsistent_types_and_untrusted_status(self):
        for change in [
            {"sourceReady": "true"},
            {"marketOpen": 1},
            {"displayRightsVerified": None},
            {"freshDisplayQuotes": -1},
            {"subscribedInstruments": 10000000},
            {"status": "MY_BROKER_SECRET_DATA"},
            {"streamState": "bad/token 123"},
        ]:
            with self.subTest(change=change):
                line, ok = probe.capability(200, ready(**change))
                self.assertFalse(ok)
                self.assertIn("INVALID_RESPONSE", line)
                self.assertNotIn("SECRET", line)

    def test_expiry_restriction_is_configuration_not_proof(self):
        line, ok = probe.expiries(409, b'{"private":"NEVER_PRINT"}')
        self.assertTrue(ok)
        self.assertIn("CONFIGURATION_BLOCKED", line)
        self.assertIn("orders=false", line)
        self.assertNotIn("NEVER_PRINT", line)
        line, ok = probe.expiries(200, json.dumps({
            "status": "VERIFIED",
            "underlyingKey": "NSE_INDEX|Nifty 50",
            "expiries": ["2026-12-01","2026-12-08"],
            "privateAccount": "SENSITIVE",
        }).encode())
        self.assertTrue(ok)
        self.assertIn("listed=2", line)
        self.assertNotIn("2026-12", line)
        self.assertNotIn("SENSITIVE", line)

    def test_expiry_invalid_identity_and_duplicate_rejected(self):
        for bad in [
            {"status":"VERIFIED","underlyingKey":"NSE_FO|something","expiries":["2026-12-01"]},
            {"status":"VERIFIED","underlyingKey":"NSE_INDEX|Nifty 50","expiries":[]},
            {"status":"VERIFIED","underlyingKey":"NSE_INDEX|Nifty 50","expiries":["2026-12-01","2026-12-01"]},
            {"status":"FILLED","underlyingKey":"NSE_INDEX|Nifty 50","expiries":["2026-12-01"]},
        ]:
            with self.subTest(data=bad):
                line, ok = probe.expiries(200, json.dumps(bad).encode())
                self.assertFalse(ok)
                self.assertIn("INVALID_RESPONSE", line)

    def test_auth_and_unknown_routes_cannot_promote(self):
        for code in (401,403,404,429,500):
            with self.subTest(code=code):
                for parser in (probe.capability, probe.expiries):
                    line, ok = parser(code, b'SUPERSECRET')
                    self.assertFalse(ok)
                    self.assertNotIn("SUPERSECRET", line)
        self.assertFalse(probe.capability(200,b"not-json")[1])
        self.assertFalse(probe.expiries(200,b"not-json")[1])

    def test_cli_bounded_response_no_private_content_output(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "sensitive.json"
            path.write_bytes(ready())
            with contextlib.redirect_stdout(io.StringIO()) as output:
                status=probe.main(["capability","200",str(path)])
            self.assertEqual(status, 0)
            self.assertNotIn("SUPERSECRET",output.getvalue())
            path.write_bytes(b"R"*8193)
            with contextlib.redirect_stdout(io.StringIO()) as output:
                status=probe.main(["capability","200",str(path)])
            self.assertEqual(status, 1)
            self.assertIn("UNAVAILABLE_RESPONSE",output.getvalue())


if __name__ == "__main__":
    unittest.main()
