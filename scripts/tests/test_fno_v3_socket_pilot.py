"""Operator-only V3 one-shot handshake proof contract (offline, no AWS)."""
import pathlib
import sys
import unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]/"infra"/"ec2-paper"))
import fno_v3_socket_pilot as p

def sample(state="STOPPED"):
    return {"state":state,"subscriptions":0,"observedInstruments":0,
            "decodedFrames":0,"discardedFrames":0,"freshExchangeTimedQuotes":0,
            "displayRightsVerified":False,"liveTradingEnabled":False}

class V3SocketPilotTest(unittest.TestCase):
    def test_exact_expiry_and_one_host(self):
        self.assertEqual(p.EXPIRY,"2026-11-23")
        self.assertEqual(p.EXACT["instance_id"],p.INSTANCE)
        self.assertEqual(p.EXACT["expected_backend_release"],p.SHA)
        self.assertEqual(p.EXACT["operation"],
            "one-shot-private-upstox-fno-v3-socket-evidence")

    def test_rejects_unverified_quote_rights_and_malformed_status(self):
        self.assertIsNotNone(p.valid_status(sample()))
        for key in ("displayRightsVerified","liveTradingEnabled"):
            obj=sample()
            obj[key]=True
            self.assertIsNone(p.valid_status(obj))
        for invalid in (None, "x", -1, 65, True):
            obj=sample()
            obj["subscriptions"]=invalid
            self.assertIsNone(p.valid_status(obj))

    def test_no_order_routes_or_http_external_api(self):
        text=pathlib.Path(p.__file__).read_text()
        self.assertIn("NoRedirect",text)
        self.assertIn("V3_PRIVATE_STOP:CONFIRMED",text)
        for forbidden in ("/api/customer/trading-rooms","submitOrder",
             "placeOrder", "with-decryption", "api.upstox.com",
             "print(key)", "method=\"PUT\""):
            self.assertNotIn(forbidden,text)
        self.assertEqual(p.PREFIX,
            "/api/admin/market-data/upstox-analytics/fno/live/")

class HostErrorTokenTest(unittest.TestCase):
    def test_surfaces_only_whitelisted_host_token(self):
        self.assertEqual(p.host_error_token("V3_PRIVATE_ERROR:SOURCE_NOT_READY\n"),
                         "SOURCE_NOT_READY")
        self.assertEqual(p.host_error_token("Traceback...\nV3_PRIVATE_ERROR:BUSY_OPERATOR_SOCKET"),
                         "BUSY_OPERATOR_SOCKET")

    def test_ignores_unknown_or_unrelated_stderr(self):
        self.assertIsNone(p.host_error_token(""))
        self.assertIsNone(p.host_error_token(None))
        self.assertIsNone(p.host_error_token("V3_PRIVATE_ERROR:Bearer abc123"))
        self.assertIsNone(p.host_error_token("https://api.upstox.com/x?token=1"))

    def test_runner_prints_token_not_raw_stderr(self):
        text=pathlib.Path(p.__file__).read_text()
        self.assertIn("V3_PRIVATE_HOST_ERROR:",text)
        self.assertIn("host_error_token(response.get(",text)
        self.assertNotIn("StandardErrorContent\")\n            print",text)

if __name__=="__main__":
    unittest.main()
