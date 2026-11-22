"""Offline parser contract for the exact owner-only read-only Upstox F&O probe."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra/ec2-paper"))
from fno_contract_47821_probe import (
    INSTRUMENT,FIELDS,expected_request,read_chain,read_quote,receipt,parse_receipt
)

class FnoContract47821ProbeTest(unittest.TestCase):
    def test_exact_fixed_owner_request(self):
        request=expected_request()
        self.assertEqual(request["instrument"],"NSE_FO|47821")
        self.assertEqual(request["expiry"],"2026-12-01")
        self.assertEqual(request["max_provider_gets"],2)
        self.assertIs(request["orders"],False)
        self.assertIs(request["persist"],False)

    def test_realistic_v3_full_quote_shape(self):
        body={"status":"success","data":{"NSE_FO:DISPLAY":{
            "instrument_token":INSTRUMENT,"last_price":124.5,
            "oi":12000,"prev_close_price":110.0,
            "last_trade_time":"1795257000000"}}}
        state,parts=read_quote(body)
        self.assertEqual(state,"VERIFIED")
        self.assertEqual(parts["ltp"],"YES")
        self.assertEqual(parts["oi"],"YES")
        self.assertEqual(parts["trade"],"YES")
        self.assertEqual(parts["previous"],"YES")
        self.assertNotIn("124.5",receipt(state,"NOT_FOUND",parts))

    def test_chain_greeks_separate_and_identity_matched(self):
        body={"status":"success","data":[
            {"strike_price":25650,
             "call_options":{"instrument_key":INSTRUMENT,
                "market_data":{"ltp":124.5,"oi":1400},
                "option_greeks":{"iv":0.18,"delta":0.51,
                    "gamma":0.003,"theta":-4.4,"vega":5.0}},
             "put_options":{"instrument_key":"NSE_FO|999","market_data":{"ltp":50}}}
        ]}
        state,parts=read_chain(body)
        self.assertEqual(state,"VERIFIED")
        self.assertTrue(all(parts[k]=="YES" for k in
            ("chain_ltp","chain_oi","iv","delta","gamma","theta","vega")))

    def test_no_other_contracts_or_fake_prices(self):
        wrong={"status":"success","data":{"KEY":{
            "instrument_token":"NSE_FO|999","last_price":200}}}
        self.assertEqual(read_quote(wrong)[0],"NOT_FOUND")
        self.assertEqual(read_chain({"status":"success","data":[]})[0],"NOT_FOUND")
        self.assertEqual(read_chain({"status":"success","data":[
            {"call_options":{"instrument_key":INSTRUMENT,
                "market_data":{"ltp":None},"option_greeks":{"iv":None}}}]})[1]["iv"],"NO")

    def test_receipt_is_strict_and_never_contains_market_data(self):
        metrics={k:"NO" for k in FIELDS}
        line=receipt("HTTP_403","NOT_FOUND",metrics)
        self.assertEqual(parse_receipt(line),line)
        self.assertIsNone(parse_receipt(line+"\n"+line))
        self.assertIsNone(parse_receipt(line.replace("orders=FALSE","orders=TRUE")))

if __name__=="__main__":
    unittest.main()
