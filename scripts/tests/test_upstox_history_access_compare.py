"""Offline tests for same-token two-GET Upstox historical access comparison."""
from datetime import date
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import upstox_history_access_compare as p

DAY = date(2026, 11, 21)


def normal():
    return {"status": "success", "data": {"candles": [
        ["2026-11-19T00:00:00+05:30", 100, 101, 99, 100, 2500, 0],
        ["2026-11-20T00:00:00+05:30", 102, 103, 101, 102, 2500, 0],
    ]}}


def expired():
    return {"status": "success", "data": ["2026-11-19", "2026-10-29", "2026-11-25"]}


class CompareTests(unittest.TestCase):
    def test_exact_owner_scoped_read_only_request(self):
        req = p.expected_request()
        self.assertEqual(req["max_provider_gets"], 2)
        self.assertFalse(req["persist"])
        self.assertFalse(req["trading_rights_change"])
        self.assertEqual(req["environment"], "PAPER")
        self.assertEqual(req["instance_id"], "i-09c01b470fb39b85a")

    def test_calls_only_two_documented_apis_and_same_token(self):
        with patch.object(p, "get_endpoint",
                          side_effect=[("OK_DATA", normal()), ("OK_DATA", expired())]) as getter:
            self.assertEqual(p.compare("test-only", DAY), ("OK_DATA", 2, "OK_DATA", 2))
            self.assertEqual(getter.call_count, 2)
            self.assertIn("/v3/historical-candle/", getter.call_args_list[0].args[0])
            self.assertIn("/days/1/", getter.call_args_list[0].args[0])
            self.assertIn("NSE_EQ%7CINE848E01016", getter.call_args_list[0].args[0])
            self.assertIn("/v2/expired-instruments/expiries?", getter.call_args_list[1].args[0])
            self.assertEqual([v.args[1] for v in getter.call_args_list],
                             ["test-only", "test-only"])

    def test_standard_access_ok_while_expired_denied(self):
        with patch.object(p, "get_endpoint",
                          side_effect=[("OK_DATA", normal()), ("HTTP_403", None)]):
            self.assertEqual(p.compare("test-only", DAY),
                             ("OK_DATA", 2, "HTTP_403", 0))

    def test_token_or_network_denied_both_paths(self):
        with patch.object(p, "get_endpoint",
                          side_effect=[("HTTP_401", None), ("HTTP_401", None)]):
            self.assertEqual(p.compare("test-only", DAY),
                             ("HTTP_401", 0, "HTTP_401", 0))

    def test_classification_has_no_provider_text(self):
        self.assertEqual(p.status_http(403, b'{"code":"UDAPI1149"}'), "PLUS_REQUIRED")
        self.assertEqual(p.status_http(403, b'Bearer private'), "HTTP_403")
        self.assertEqual(p.status_http(401, b'Bearer private'), "HTTP_401")
        self.assertEqual(p.status_http(429, b'Bearer private'), "HTTP_429")
        self.assertEqual(p.status_http(503, b'Secret payload'), "HTTP_5XX")
        self.assertEqual(p.status_http(418, b'Bearer private'), "HTTP_OTHER")

    def test_user_agent_is_explicit_for_both_endpoints(self):
        headers = []
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def read(self, size): return b'{"status":"success","data":[]}'
        class Opener:
            def open(self, request, timeout):
                headers.append((request.get_header("User-agent"), request.get_header("Authorization")))
                return Response()
        with patch.object(p.urllib.request, "build_opener", return_value=Opener()):
            self.assertEqual(p.get_endpoint("https://api.upstox.com/v2/expired-instruments/expiries?instrument_key=NSE_INDEX%7CNifty%2050", "fake-test-token"), ("OK_DATA", {"status":"success","data":[]}))
            self.assertEqual(p.get_endpoint("https://api.upstox.com/v3/historical-candle/NSE_EQ%7CINE848E01016/days/1/2026-11-20/2026-11-13", "fake-test-token")[0], "OK_DATA")
        self.assertEqual(len(headers), 2)
        self.assertTrue(all(ua == p.UPSTOX_USER_AGENT and bearer == "Bearer fake-test-token" for ua, bearer in headers))
        self.assertNotIn("Python-urllib", p.UPSTOX_USER_AGENT)

    def test_safe_waf_and_token_classification(self):
        self.assertEqual(p.status_http(403, b"Error 1010: browser_signature_banned"), "CLOUDFLARE_1010")
        self.assertEqual(p.status_http(401, b'{"error_code":"UDAPI100050"}'), "TOKEN_INVALID")
        self.assertEqual(p.status_http(403, b'{"error_code":"UDAPI1149"}'), "PLUS_REQUIRED")
        self.assertEqual(p.parse_line(p.safe_line("CLOUDFLARE_1010",0,"HTTP_403",0)),
                         ("CLOUDFLARE_1010",0,"HTTP_403",0))

    def test_normal_candles_validate_values_and_dates(self):
        self.assertEqual(p.count_equity(normal(), date(2026, 11, 13),
                                        date(2026, 11, 20)), ("OK_DATA", 2))
        bad = normal()
        bad["data"]["candles"][0][2] = -99
        self.assertEqual(p.count_equity(bad, date(2026, 11, 13),
                                        date(2026, 11, 20))[0], "PROVIDER_INVALID")
        bad = normal()
        bad["data"]["candles"][0][0] = "2026-10-01T00:00:00+05:30"
        self.assertEqual(p.count_equity(bad, date(2026, 11, 13),
                                        date(2026, 11, 20))[0], "PROVIDER_INVALID")

    def test_empty_provider_history_distinct_from_auth_success(self):
        self.assertEqual(p.count_equity({"status":"success", "data":{"candles":[]}},
                                        date(2026, 11, 13),
                                        date(2026, 11, 20)), ("OK_EMPTY", 0))
        self.assertEqual(p.count_expiries({"status":"success", "data":[]},
                                          DAY), ("OK_EMPTY", 0))

    def test_expiries_validate_and_count_only_expired(self):
        self.assertEqual(p.count_expiries(expired(), DAY), ("OK_DATA", 2))
        self.assertEqual(p.count_expiries({"status":"failure","data":[]}, DAY)[0],
                         "PROVIDER_INVALID")
        self.assertEqual(p.count_expiries({"status":"success","data":["2026-02-30"]},
                                          DAY)[0], "PROVIDER_INVALID")

    def test_safe_receipt_requires_whitelisted_code_and_count(self):
        good = p.safe_line("OK_DATA", 2, "HTTP_403", 0)
        self.assertEqual(p.parse_line("private Bearer data\n" + good),
                         ("OK_DATA", 2, "HTTP_403", 0))
        for invalid in (good+"\n"+good, good.replace("stored=false","stored=true"),
                        good.replace("bars=2","bars=5000"),
                        good.replace("HTTP_403","BREAK_GUARD"),
                        good.replace("standard=OK_DATA","standard=HTTP_401")):
            self.assertIsNone(p.parse_line(invalid))

    def test_source_cannot_claim_persistence_or_trading(self):
        line = p.safe_line("OK_DATA", 2, "PLUS_REQUIRED", 0)
        self.assertIn("stored=false orders=false", line)
        self.assertNotIn("test-only", line)
        self.assertEqual(p.parse_line(line),
                         ("OK_DATA", 2, "PLUS_REQUIRED", 0))


if __name__ == "__main__":
    unittest.main()
