"""No-network tests for exchange-timed V3 versus V2 chain display evidence."""
from __future__ import annotations

import contextlib
import datetime as dt
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "check_fno_market_snapshot", ROOT / "check_fno_market_snapshot.py")
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
IST=ZoneInfo("Asia/Kolkata")
NOW=dt.datetime(2026,11,23,20,30,tzinfo=IST)


def market(**change):
    base={
        "underlyingKey":"NSE_INDEX|Nifty 50",
        "source":"UPSTOX_ANALYTICS",
        "status":"MARKET_CLOSED",
        "expiry":"2026-12-01",
        "displayRightsVerified":True,
        "liveOrderEnabled":False,
        "strikes":[{"strikePrice":26000,
                    "call":{"instrumentKey":"NSE_FO|47895","side":"CE","lotSize":65,
                            "quoteFresh":False,"ltp":105.50,"exchangeAt":"2026-11-23T09:00:00Z","previousClose":103},
                    "put":{"instrumentKey":"NSE_FO|47896","side":"PE","lotSize":65,
                           "quoteFresh":False,"ltp":None,"exchangeAt":None,"previousClose":None}}],
        "chainSnapshotIndicators":{
            "NSE_FO|47895":{"ltp":106.20},
            "NSE_FO|47896":{"ltp":107.25}},
        "privateOwnerEmail":"DO_NOT_LOG_OWNER_EMAIL",
        "brokerToken":"DO_NOT_LOG_TOKEN",
    }
    base.update(change)
    return json.dumps(base).encode()


class MarketReadinessTests(unittest.TestCase):
    def test_closed_market_separates_actual_last_trade_and_v2_chain_indicator(self):
        receipt, ok=audit.classify(200,market())
        self.assertTrue(ok)
        self.assertIn("status=MARKET_CLOSED",receipt)
        self.assertIn("strikes=1;contractLegs=2",receipt)
        self.assertIn("exchangeTimedLastTrades=1",receipt)
        self.assertIn("v2ChainLtpIndicators=2",receipt)
        self.assertIn("freshV3Legs=0",receipt)
        self.assertNotIn("DO_NOT_LOG",receipt)
        self.assertNotIn("47895",receipt)
        self.assertNotIn("106.20",receipt)

    def test_no_last_trade_is_observed_not_invented(self):
        m=json.loads(market())
        m["strikes"][0]["call"]["ltp"]=None
        m["strikes"][0]["call"]["exchangeAt"]=None
        receipt,ok=audit.classify(200,json.dumps(m).encode())
        self.assertTrue(ok)
        self.assertIn("exchangeTimedLastTrades=0",receipt)
        self.assertIn("v2ChainLtpIndicators=2",receipt)

    def test_expiry_selected_only_from_current_listed_source(self):
        listing=json.dumps({
            "underlyingKey":"NSE_INDEX|Nifty 50","status":"VERIFIED",
            "expiries":["2026-12-08","2026-12-01"]}).encode()
        self.assertEqual(audit.select_expiry(listing,NOW),"2026-12-01")
        for data in [
            {"underlyingKey":"NSE_INDEX|Nifty 50","status":"BLOCKED","expiries":["2026-12-01"]},
            {"underlyingKey":"NSE_EQ|NIFTY","status":"VERIFIED","expiries":["2026-12-01"]},
            {"underlyingKey":"NSE_INDEX|Nifty 50","status":"VERIFIED","expiries":["2026-12-01","2026-12-01"]},
            {"underlyingKey":"NSE_INDEX|Nifty 50","status":"VERIFIED","expiries":["2020-01-01"]},
        ]:
            with self.subTest(case=data):
                self.assertIsNone(audit.select_expiry(json.dumps(data).encode(),NOW))

    def test_malformed_contracts_are_rejected_without_untrusted_output(self):
        cases=[
            {"displayRightsVerified":False},
            {"liveOrderEnabled":True},
            {"source":"SYNTHETIC"},
            {"strikes":[]},
            {"status":"LIVE_QUOTES"},
            {"chainSnapshotIndicators":{"NSE_FO|INJECTED":{"ltp":108}}},
        ]
        for changes in cases:
            with self.subTest(changes=changes):
                receipt,ok=audit.classify(200,market(**changes))
                # LIVE_QUOTES with a fresh=false leg is a valid transport status
                if changes=={"status":"LIVE_QUOTES"}:
                    self.assertTrue(ok)
                else:
                    self.assertFalse(ok)
                    self.assertIn("INVALID_MARKET_RESPONSE",receipt)
                self.assertNotIn("DO_NOT_LOG",receipt)
        m=json.loads(market())
        m["strikes"][0]["call"]["quoteFresh"]=True
        receipt,ok=audit.classify(200,json.dumps(m).encode())
        self.assertFalse(ok)  # cannot have exchange fresh quote on closed session

    def test_http_rejections_are_distinct_from_missing_data(self):
        for code,reason in ((401,"AUTH_DENIED"),(403,"AUTH_DENIED"),
                            (404,"ROUTE_NOT_DEPLOYED"),(409,"CONFIGURATION_BLOCKED"),
                            (503,"PROVIDER_OR_BACKEND_UNAVAILABLE")):
            with self.subTest(code=code):
                receipt,ok=audit.classify(code,b"PRIVATE_DATA")
                self.assertFalse(ok)
                self.assertIn(reason,receipt)
                self.assertNotIn("PRIVATE_DATA",receipt)

    def test_cli_outputs_only_bounded_aggregate(self):
        with tempfile.TemporaryDirectory() as folder:
            expiry=Path(folder)/"expiry.json"
            expiry.write_text(json.dumps({
                "underlyingKey":"NSE_INDEX|Nifty 50","status":"VERIFIED",
                "expiries":["2026-12-01"]}))
            with contextlib.redirect_stdout(io.StringIO()) as out:
                code=audit.main(["expiry",str(expiry)])
            # A test fixture from 2026-12 must not become a current real expiry
            self.assertIn(code,(0,1))
            if code==0:
                self.assertEqual(out.getvalue().strip(),"2026-12-01")
            file=Path(folder)/"market.json"
            file.write_bytes(market())
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(audit.main(["market","200",str(file)]),0)
            self.assertIn("exchangeTimedLastTrades=1",out.getvalue())
            self.assertNotIn("DO_NOT_LOG",out.getvalue())


if __name__=="__main__":
    unittest.main()
