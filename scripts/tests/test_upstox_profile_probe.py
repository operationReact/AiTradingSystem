"""Offline contract for the one-GET account-profile check (no AWS, no provider calls)."""
import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra" / "ec2-paper"))
import upstox_profile_probe as p  # noqa: E402


class UpstoxProfileProbeTest(unittest.TestCase):
    def test_request_file_matches_expected_contract(self):
        request = json.loads((ROOT / p.REQUEST).read_text())
        self.assertEqual(request, p.expected_request())
        self.assertEqual(request["max_provider_gets"], 1)
        self.assertFalse(request["persist"])
        self.assertFalse(request["trading_rights_change"])

    def test_exactly_one_read_only_profile_endpoint(self):
        source = (ROOT / "infra/ec2-paper/upstox_profile_probe.py").read_text()
        self.assertEqual(p.URL, "https://api.upstox.com/v2/user/profile")
        for forbidden in ("placeOrder", "submitOrder", "put-parameter", "UPDATE customer_",
                          "DELETE FROM", "TRADING_MODE=LIVE", "print(token)",
                          "print(result.stdout)", "print(body", "print(data"):
            self.assertNotIn(forbidden, source, forbidden)

    def test_only_exchange_membership_is_reported(self):
        self.assertEqual(p.classify_profile({"status": "success", "data": {"exchanges": ["NSE", "NFO"]}}),
                         "PROFILE_NFO_PRESENT")
        self.assertEqual(p.classify_profile({"status": "success", "data": {"exchanges": ["NSE"]}}),
                         "PROFILE_NFO_ABSENT")

    def test_malformed_provider_bodies_fail_closed(self):
        for body in ({"status": "error"}, {"status": "success", "data": []},
                     {"status": "success", "data": {"exchanges": "NFO"}},
                     {"status": "success", "data": {"exchanges": [1, 2]}}):
            self.assertEqual(p.classify_profile(body), "PROFILE_PROVIDER_INVALID")

    def test_provider_error_code_is_extracted_only_when_well_formed(self):
        self.assertEqual(p.provider_code(b'{"errors":[{"errorCode":"UDAPI10005"}]}'), "UDAPI10005")
        self.assertEqual(p.provider_code(b'{"errorCode":"NOT_A_CODE"}'), "NONE")
        self.assertEqual(p.provider_code(b"<html>403</html>"), "NONE")

    def test_plus_plan_code_is_distinguished(self):
        self.assertEqual(p.classify_http(403, b'{"errors":[{"errorCode":"UDAPI1149"}]}'),
                         "PROFILE_PLUS_REQUIRED")
        self.assertEqual(p.classify_http(403, b'{"errors":[{"errorCode":"UDAPI10005"}]}'),
                         "PROFILE_HTTP_403")

    def test_receipt_round_trips_and_rejects_unknown_fields(self):
        receipt = p.safe_receipt("PROFILE_HTTP_403", "UDAPI10005")
        self.assertEqual(p.parse_receipt(receipt + "\n"), ("PROFILE_HTTP_403", "UDAPI10005"))
        with self.assertRaises(ValueError):
            p.safe_receipt("PROFILE_HTTP_403", "bearer")
        with self.assertRaises(ValueError):
            p.safe_receipt("RAW_BODY")
        self.assertIsNone(p.parse_receipt(receipt.replace("stored=false", "stored=true") + "\n"))

    def test_invalid_token_shapes_never_call_the_provider(self):
        self.assertEqual(p.profile(None), ("TOKEN_UNAVAILABLE", "NONE"))
        self.assertEqual(p.profile("short token"), ("TOKEN_UNAVAILABLE", "NONE"))


if __name__ == "__main__":
    unittest.main()
