"""Offline bounded Upstox quote authorization tests. No network or AWS calls."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import urllib.error
from io import BytesIO

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import upstox_quote_access_probe as q


class QuoteAccessTest(unittest.TestCase):
    def test_request_is_fixed_readonly_single_get(self):
        d=q.expected_request()
        self.assertEqual(d["max_provider_gets"],1)
        self.assertFalse(d["persist"])
        self.assertFalse(d["trading_rights_change"])
        self.assertEqual(d["environment"],"PAPER")
        self.assertEqual(d["instance_id"],"i-09c01b470fb39b85a")
        self.assertIn("/v3/market-quote/ltp?",q.URL)
        self.assertIn("NSE_EQ%7CINE848E01016",q.URL)

    def test_exact_provider_identity_and_finite_quote(self):
        body={"status":"success","data":{"NSE_EQ:NHPC":{
            "instrument_token":q.INSTRUMENT,"last_price":55.5}}}
        self.assertEqual(q.classify_quote(body),"QUOTE_VERIFIED")
        body["data"]["NSE_EQ:NHPC"]["last_price"]=float("nan")
        self.assertEqual(q.classify_quote(body),"QUOTE_PROVIDER_INVALID")
        body["data"]["NSE_EQ:NHPC"]["last_price"]=55.5
        body["data"]["NSE_EQ:NHPC"]["instrument_token"]="NSE_EQ|different"
        self.assertEqual(q.classify_quote(body),"QUOTE_PROVIDER_INVALID")

    def test_empty_quote_is_not_verified(self):
        self.assertEqual(q.classify_quote({"status":"success","data":{}}),"QUOTE_EMPTY")
        self.assertEqual(q.classify_quote({"status":"error","data":{}}),"QUOTE_PROVIDER_INVALID")

    def test_http_errors_are_enum_safe(self):
        self.assertEqual(q.classify_http(401,b"private"),"QUOTE_HTTP_401")
        self.assertEqual(q.classify_http(403,b"private"),"QUOTE_HTTP_403")
        self.assertEqual(q.classify_http(403,b'{"code":"UDAPI1149"}'),"QUOTE_PLUS_REQUIRED")
        self.assertEqual(q.classify_http(429,b"private"),"QUOTE_HTTP_429")
        self.assertEqual(q.classify_http(503,b"private"),"QUOTE_HTTP_5XX")

    def test_explicit_descriptive_user_agent_reaches_http_request(self):
        """Avoid Python-urllib/x.y default signature observed as blocked by WAF."""
        token = "test-token-not-real-" * 3
        request_seen = []
        class DummyResponse:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self, size): return b'{"status":"success","data":{}}'
        class DummyOpener:
            def open(self, request, timeout):
                request_seen.append(request)
                return DummyResponse()
        with patch.object(q.urllib.request, "build_opener", return_value=DummyOpener()):
            self.assertEqual(q.quote(token), "QUOTE_EMPTY")
        self.assertEqual(len(request_seen), 1)
        req = request_seen[0]
        self.assertEqual(req.get_method(), "GET")
        self.assertEqual(req.get_header("User-agent"), q.UPSTOX_USER_AGENT)
        self.assertEqual(req.get_header("Authorization"), "Bearer " + token)
        self.assertNotIn("Python-urllib", req.get_header("User-agent"))

    def test_cloudflare_1010_is_safe_and_separate_from_upstox_rejection(self):
        self.assertEqual(q.classify_http(403, b"Error 1010: browser_signature_banned"), "QUOTE_CLOUDFLARE_1010")
        self.assertEqual(q.classify_http(403, b"error code: 1010"), "QUOTE_CLOUDFLARE_1010")
        self.assertEqual(q.classify_http(401, b'{"error_code":"UDAPI100050"}'), "QUOTE_TOKEN_INVALID")
        self.assertEqual(q.classify_http(403, b"some other blocked request"), "QUOTE_HTTP_403")
        self.assertEqual(q.parse_receipt(q.safe_receipt("QUOTE_CLOUDFLARE_1010")), "QUOTE_CLOUDFLARE_1010")

    def test_token_syntax_blocks_short_inputs_without_network(self):
        self.assertEqual(q.quote("x"),"TOKEN_UNAVAILABLE")
        self.assertEqual(q.quote("bad whitespace token "*4),"TOKEN_UNAVAILABLE")

    def test_no_quotes_leak_into_sanitized_receipt(self):
        line=q.safe_receipt("QUOTE_HTTP_403")
        self.assertIn("stored=false rights=unknown orders=false",line)
        self.assertEqual(q.parse_receipt("Bearer example secret\n"+line),"QUOTE_HTTP_403")
        self.assertNotIn("Bearer",line)
        self.assertNotIn("example",line)

    def test_fail_closed_invalid_or_duplicate_receipt(self):
        correct=q.safe_receipt("QUOTE_VERIFIED")
        for invalid in (correct+"\n"+correct,correct.replace("stored=false","stored=true"),
                        correct.replace("QUOTE_VERIFIED","QUOTE_EVERYTHING_GOOD"),
                        correct*1000):
            self.assertIsNone(q.parse_receipt(invalid))
        with self.assertRaises(ValueError):
            q.safe_receipt("QUOTE_EVERYTHING_GOOD")


if __name__ == "__main__":
    unittest.main()
