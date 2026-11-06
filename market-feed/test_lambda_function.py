import unittest
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

    def test_no_order_mutation_paths(self):
        self.assertEqual({feed.LOGIN_PATH, feed.LTP_PATH, feed.CANDLE_PATH},
           {"/rest/auth/angelbroking/user/v1/loginByPassword",
            "/rest/secure/angelbroking/order/v1/getLtpData",
            "/rest/secure/angelbroking/historical/v1/getCandleData"})
        
if __name__ == "__main__":
    unittest.main()
