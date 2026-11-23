"""No-network structural parity tests for strict Flutter F&O market models."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SRC=Path(__file__).resolve().parent
SPEC=importlib.util.spec_from_file_location(
    "check_fno_flutter_market_contract", SRC/"check_fno_flutter_market_contract.py")
mod=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


def response():
    return {
        "underlyingKey":"NSE_INDEX|Nifty 50",
        "expiry":"2026-12-01",
        "source":"UPSTOX_ANALYTICS",
        "status":"MARKET_CLOSED",
        "snapshotAt":"2026-11-23T20:00:00+05:30",
        "displayRightsVerified":True,
        "liveOrderEnabled":False,
        "strikes":[{
            "strikePrice":26000,
            "call":{
                "instrumentKey":"NSE_FO|47895",
                "tradingSymbol":"NIFTY 26000 CE",
                "side":"CE","lotSize":65,"tickSize":0.05,
                "bid":None,"ask":None,"exchangeAt":None,"observedAt":None,
                "quoteFresh":False,"paperEligible":False,
                "ltp":None,"previousClose":None,"openInterest":None,
            },
            "put":{
                "instrumentKey":"NSE_FO|47896",
                "tradingSymbol":"NIFTY 26000 PE",
                "side":"PE","lotSize":65,"tickSize":0.05,
                "bid":None,"ask":None,"exchangeAt":None,"observedAt":None,
                "quoteFresh":False,"paperEligible":False,
                "ltp":None,"previousClose":None,"openInterest":None,
            },
        }],
        "chainSnapshotIndicators":{
            "NSE_FO|47895":{"ltp":103.5,"openInterest":3000,"volume":1000,"iv":0.16,"delta":0.4},
            "NSE_FO|47896":{"ltp":92.5,"openInterest":4000,"volume":1200,"iv":0.19,"delta":-0.5},
        },
        "privateToken":"MUST_NEVER_BE_LOGGED",
    }


class FlutterParityTests(unittest.TestCase):
    def test_full_closed_market_structure_compatible(self):
        self.assertEqual(mod.inspect(json.dumps(response()).encode()), (1,2,2,2))

    def test_large_realistic_listed_universe_compatible(self):
        data=response()
        data["strikes"]=[]
        data["chainSnapshotIndicators"]={}
        for i in range(158):
            strike=25000 + i*50
            row=json.loads(json.dumps(response()["strikes"][0]))
            row["strikePrice"]=strike
            for field,side in (("call","CE"),("put","PE")):
                row[field]["instrumentKey"]=f"NSE_FO|{50000+i*2+(side=='PE')}"
                row[field]["tradingSymbol"]=f"NIFTY {strike} {side}"
                if i<157:
                    data["chainSnapshotIndicators"][row[field]["instrumentKey"]]={"ltp":102.5}
            data["strikes"].append(row)
        self.assertEqual(mod.inspect(json.dumps(data).encode()),(158,316,314,314))

    def test_schema_rejects_one_bad_leg_without_disclosing_value(self):
        bad=[
            ("leg",lambda d:d["strikes"][0]["call"].update(tradingSymbol="X"*65)),
            ("range",lambda d:d["strikes"][0]["call"].update(lotSize=0)),
            ("tick",lambda d:d["strikes"][0]["call"].update(tickSize=0)),
            ("count",lambda d:d["chainSnapshotIndicators"]["NSE_FO|47895"].update(volume=-1)),
            ("iv",lambda d:d["chainSnapshotIndicators"]["NSE_FO|47895"].update(iv=-1)),
            ("key",lambda d:d["chainSnapshotIndicators"].update({"NSE_FO|FAKE":{"ltp":120}})),
        ]
        for tag,mutation in bad:
            with self.subTest(tag=tag):
                d=response()
                mutation(d)
                with self.assertRaises(mod.SchemaBlocked) as raised:
                    mod.inspect(json.dumps(d).encode())
                self.assertNotIn("MUST_NEVER",str(raised.exception))

    def test_auth_flags_and_bad_snapshot_rejected(self):
        for change in ({"liveOrderEnabled":True},
                       {"snapshotAt":"something secret"},
                       {"displayRightsVerified":"yes"},
                       {"strikes":"not-a-list"}):
            with self.subTest(change=change):
                d=response()
                d.update(change)
                with self.assertRaises(mod.SchemaBlocked):
                    mod.inspect(json.dumps(d).encode())

    def test_cli_emits_only_counts_and_fixed_reason(self):
        with tempfile.TemporaryDirectory() as folder:
            file=Path(folder)/"backend.json"
            file.write_text(json.dumps(response()))
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(mod.main([str(file)]),0)
            self.assertIn("FIELD_CONTRACT_COMPATIBLE",printed.getvalue())
            self.assertIn("actualDartExecuted=false",printed.getvalue())
            self.assertNotIn("MUST_NEVER",printed.getvalue())
            d=response()
            d["chainSnapshotIndicators"]["NSE_FO|47895"]["iv"]=-1
            file.write_text(json.dumps(d))
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(mod.main([str(file)]),1)
            self.assertIn("FIELD_CONTRACT_BLOCKED",printed.getvalue())
            self.assertNotIn("MUST_NEVER",printed.getvalue())


if __name__=="__main__":
    unittest.main()
