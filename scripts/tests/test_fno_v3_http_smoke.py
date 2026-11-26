"""Offline safety regression for protected GET-only F&O runtime API probe."""
import pathlib
import sys
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import fno_v3_http_smoke as smoke


class FnoHttpSmokeTest(unittest.TestCase):
    def test_exact_host_and_read_only_contract(self):
        self.assertEqual(smoke.INSTANCE, "i-09c01b470fb39b85a")
        self.assertEqual(smoke.ACCOUNT, "098859530205")
        self.assertEqual(smoke.EXPECTED["operation"], "read-only-fno-v3-http-smoke")
        self.assertEqual(len(smoke.EXPECTED), 5)

    def test_provider_state_must_match_fixed_allowlist(self):
        self.assertEqual(smoke.safe_status("CONNECTED"), "CONNECTED")
        self.assertEqual(smoke.safe_status("STOPPED"), "STOPPED")
        for bad in (None, 10, "", "CONNECTED; secret", "WRONG_STATE"):
            self.assertEqual(smoke.safe_status(bad), "UNAVAILABLE")

    def test_research_probe_distinguishes_fixed_failure_categories(self):
        self.assertEqual(smoke.research_receipt(403, None), "AUTH_DENIED")
        self.assertEqual(smoke.research_receipt(404, None), "ROUTE_NOT_DEPLOYED")
        self.assertEqual(smoke.research_receipt(0, None), "TRANSPORT_UNAVAILABLE")
        self.assertEqual(smoke.research_receipt(503, None), "HTTP_5XX")
        self.assertEqual(smoke.research_receipt(400, None), "HTTP_4XX")
        self.assertEqual(smoke.research_receipt(200, {}), "INVALID_ROWS")

    def test_research_probe_accepts_only_exact_three_safe_strategy_rows(self):
        rows = [
            {"strategyId": "strategy.STR-172", "status": "BLOCKED",
             "reasonCode": "NO_DATA", "contractsResolved": 0,
             "freshQuotes": 0, "historySessions": 0},
            {"strategyId": "strategy.STR-170", "status": "NO_TRADE",
             "reasonCode": "MARKET_CLOSED", "contractsResolved": 1,
             "freshQuotes": 0, "historySessions": 0},
            {"strategyId": "strategy.STR-171", "status": "BLOCKED",
             "reasonCode": "NO_DATA", "contractsResolved": 0,
             "freshQuotes": 0, "historySessions": 0},
        ]
        receipt = smoke.research_receipt(200, rows)
        self.assertTrue(receipt.startswith("STR-170=NO_TRADE:MARKET_CLOSED"))
        self.assertIn("STR-172=BLOCKED:NO_DATA", receipt)
        self.assertEqual(smoke.research_receipt(200, rows[:2]), "INVALID_ROWS")
        rows[2]["reasonCode"] = "Bearer secret"
        self.assertEqual(smoke.research_receipt(200, rows), "INVALID_ROWS")
    def test_external_urls_and_queries_are_never_accepted(self):
        for path in ("https://evil.test", "//evil", "/route?x=1", "route"):
            with self.assertRaises(ValueError):
                smoke.http_get(path)

    def test_unauthorized_customer_api_is_a_real_get_and_not_a_write(self):
        with patch.object(smoke.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = HTTPError(
                "http://127.0.0.1:8080/api/customer/markets/fno/capability",
                401, "Unauthorized", {}, None)
            status, body = smoke.http_get("/api/customer/markets/fno/capability")
            self.assertEqual(status, 401)
            self.assertIsNone(body)
            request = opener.return_value.open.call_args.args[0]
            self.assertEqual(request.get_method(), "GET")
            self.assertEqual(request.full_url,
                "http://127.0.0.1:8080/api/customer/markets/fno/capability")

    def test_no_provider_response_or_secret_is_printed_from_invalid_state(self):
        for s in ("Bearer token", "apiKey", "true; arbitrary HTTP"):
            self.assertEqual(smoke.safe_status(s), "UNAVAILABLE")


if __name__ == "__main__":
    unittest.main()
