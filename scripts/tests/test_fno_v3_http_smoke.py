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
