import unittest
from types import SimpleNamespace
from unittest.mock import patch
import lambda_function as feed

DUMMY = {"api_key": "not-real", "client_code": "TEST", "pin": "1234",
         "totp_secret": "JBSWY3DPEHPK3PXP"}

class ReadOnlyFeedTests(unittest.TestCase):
    def setUp(self):
        feed._CACHE.update({"secrets": None, "secrets_loaded_at": 0.0, "jwt": None, "jwt_expires": 0.0})

    def test_totp_rfc_example(self):
        # RFC 6238 uses 8 digits; this adapter deliberately uses Angel One's 6-digit standard.
        self.assertEqual(feed._totp("JBSWY3DPEHPK3PXP", 0), "282760")

    def test_unknown_operation_never_reads_secrets(self):
        with patch.object(feed, "_secrets") as secrets:
            result = feed.handler({"operation": "placeOrder"}, None)
        self.assertEqual(result, {"ok": False, "code": "DISALLOWED_OPERATION"})
        secrets.assert_not_called()

    def test_status_does_not_echo_secrets(self):
        with patch.object(feed, "_secrets", return_value=DUMMY):
            response = feed.handler({"operation": "status"}, None)
        self.assertTrue(response["ok"])
        self.assertNotIn("not-real", str(response))
        self.assertNotIn("1234", str(response))

    def test_status_reports_unavailable_ssm_field_names_only(self):
        class StubSSM:
            def get_parameters(self, **kwargs):
                self.assert_not_used = False
                return {"Parameters": [
                    {"Name": feed.NAMES["api_key"], "Type": "SecureString", "Value": "private-key"},
                    {"Name": feed.NAMES["client_code"], "Type": "String", "Value": "private-client"},
                    {"Name": feed.NAMES["pin"], "Type": "SecureString", "Value": "4321"},
                ], "InvalidParameters": [feed.NAMES["totp_secret"]]}
        fake_boto3 = SimpleNamespace(client=lambda *args, **kwargs: StubSSM())
        with patch.dict("sys.modules", {"boto3": fake_boto3}):
            result = feed.handler({"operation": "status"}, None)
        self.assertEqual(result, {
            "ok": False, "code": "CREDENTIALS_NOT_CONFIGURED",
            "unavailableFields": ["client_code", "totp_secret"],
        })
        for private in ("private-key", "private-client", "4321"):
            self.assertNotIn(private, str(result))

    def test_quote_normalizes_verified_broker_price(self):
        with patch.object(feed, "_secrets", return_value=DUMMY), \
             patch.object(feed, "_read", return_value={"ltp": 124.55}):
            result = feed.handler({
                "operation": "quote", "exchange": "NSE",
                "symbolToken": "12345", "tradingSymbol": "TEST-EQ"}, None)
        self.assertTrue(result["ok"])
        self.assertEqual(result["ltp"], "124.55")
        self.assertEqual(result["source"], "ANGEL_ONE_SMARTAPI")
        self.assertNotIn("symbolToken", result)

    def test_fails_closed_on_invalid_instrument_and_price(self):
        with patch.object(feed, "_secrets", return_value=DUMMY), \
             patch.object(feed, "_read") as call:
            bad = feed.handler({"operation": "quote", "exchange": "NFO",
                "symbolToken": "1", "tradingSymbol": "TEST"}, None)
            self.assertEqual(bad["code"], "INVALID_INSTRUMENT")
            call.assert_not_called()
        with patch.object(feed, "_secrets", return_value=DUMMY), \
             patch.object(feed, "_read", return_value={"ltp": "-1"}):
            bad = feed.handler({"operation": "quote", "exchange": "NSE",
                "symbolToken": "1", "tradingSymbol": "TEST"}, None)
            self.assertEqual(bad["code"], "PROVIDER_INVALID_RESPONSE")

    def test_history_date_bounds(self):
        with patch.object(feed, "_secrets", return_value=DUMMY), \
             patch.object(feed, "_read") as call:
            bad = feed.handler({"operation": "candles", "exchange": "NSE",
                "symbolToken": "1", "interval": "ONE_MINUTE",
                "fromDate": "2020-01-01 09:15", "toDate": "2020-12-30 15:30"}, None)
            self.assertEqual(bad["code"], "INVALID_CANDLE_REQUEST")
            call.assert_not_called()

    def test_owner_connect_verifies_ssm_client_with_broker_profile(self):
        with patch.object(feed, "_secrets", return_value=DUMMY), \
             patch.object(feed, "_read_account", return_value={"clientcode": "TEST"}) as read:
            result = feed.handler({"operation": "broker-connect"}, None)
        self.assertEqual(result, {
            "ok": True, "broker": "ANGEL_ONE",
            "clientCodeMasked": "***EST", "orderExecutionEnabled": False,
        })
        read.assert_called_once_with(feed.PROFILE_PATH, DUMMY)
        self.assertNotIn("not-real", str(result))
        self.assertNotIn("JBSWY", str(result))

    def test_owner_connect_rejects_broker_profile_mismatch(self):
        with patch.object(feed, "_secrets", return_value=DUMMY), \
             patch.object(feed, "_read_account", return_value={"clientcode": "OTHER"}):
            result = feed.handler({"operation": "broker-connect"}, None)
        self.assertEqual(result, {"ok": False, "code": "BROKER_ACCOUNT_MISMATCH"})

    def test_account_reads_only_allow_exact_known_broker_paths(self):
        with patch.object(feed, "_secrets", return_value=DUMMY), \
             patch.object(feed, "_read_account", return_value={"availablecash": "5000"}) as read:
            result = feed.handler({"operation": "broker-read", "resource": "funds"}, None)
        self.assertTrue(result["ok"])
        self.assertEqual(result["provider"], "ANGEL_ONE")
        self.assertEqual(result["data"], {"availablecash": "5000"})
        read.assert_called_once_with(feed.ACCOUNT_PATHS["funds"], DUMMY)
        self.assertNotIn("not-real", str(result))
        self.assertNotIn("1234", str(result))

        for resource in ("placeOrder", "modifyOrder", "cancelOrder",
                         "convertPosition", "getTokens", "logout"):
            with patch.object(feed, "_read_account") as read:
                result = feed.handler({"operation": "broker-read", "resource": resource}, None)
            self.assertEqual(result, {"ok": False, "code": "DISALLOWED_OPERATION"})
            read.assert_not_called()

    def test_broker_get_never_follows_unreviewed_endpoint(self):
        with patch.object(feed, "urlopen") as url:
            with self.assertRaises(feed.FeedError):
                feed._get("/rest/secure/angelbroking/order/v1/placeOrder",
                          "not-real-broker-jwt", DUMMY)
        url.assert_not_called()

    def test_no_order_mutation_paths(self):
        self.assertEqual({feed.LOGIN_PATH, feed.LTP_PATH, feed.CANDLE_PATH},
           {"/rest/auth/angelbroking/user/v1/loginByPassword",
            "/rest/secure/angelbroking/order/v1/getLtpData",
            "/rest/secure/angelbroking/historical/v1/getCandleData"})
        
if __name__ == "__main__":
    unittest.main()
