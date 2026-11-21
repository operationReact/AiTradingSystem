"""Offline tests for read-only NIFTY F&O data download (no provider access)."""
from datetime import date, timedelta
import sys
from pathlib import Path
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra"/"ec2-paper"))
import fno_ephemeral_pilot as p

FUT="NSE_FO|73507|24-04-2025"
CE="NSE_FO|73508|24-04-2025"
PE="NSE_FO|73509|24-04-2025"

def contract(kind,key,exp="2025-04-24",strike=None):
    v={"underlying_key":p.UNDERLYING,"segment":"NSE_FO",
       "expiry":exp,"instrument_type":kind,"instrument_key":key}
    if strike is not None:v["strike_price"]=strike
    return v

def bar(day="2025-04-22"):
    return [day+"T09:15:00+05:30",110.0,112.0,109.0,111.0,1200,230]

class EphemeralTest(unittest.TestCase):
    def test_immutable_approval_is_memory_only(self):
        v=p.expected_request()
        self.assertEqual(v["max_provider_gets"],16)
        self.assertIs(v["persist_market_data"],False)
        self.assertIs(v["publish_market_data"],False)
        self.assertIs(v["trading_rights_change"],False)
        self.assertEqual(v["instance_id"],"i-09c01b470fb39b85a")

    def test_expiry_month_selection_is_bounded(self):
        items=["2025-02-27","2025-03-27","2025-04-17","2025-04-24",
               "2025-05-29","2025-06-26","2025-07-31","2025-08-28",
               "2025-09-25","2025-10-30","2025-11-27"]
        got=p.expiry_samples(items,date(2025,11,21))
        self.assertEqual(got,[date(2025,10,30),date(2025,9,25),date(2025,8,28)])

    def test_contract_selection_restricts_futures_and_complete_option_pairs(self):
        rows=[contract("FUT",FUT),
              contract("CE",CE,strike=22000),
              contract("PE",PE,strike=22000),
              contract("CE","NSE_FO|444|24-04-2025",strike=22100)]
        self.assertEqual(p.select_keys(rows,date(2025,4,24),"FUT"),[FUT])
        self.assertEqual(p.select_keys(rows,date(2025,4,24),"OPT"),[CE,PE])
        with self.assertRaises(p.PilotError):
            p.contract_key(contract("CE","NSE_EQ|123",strike=21000),
                           date(2025,4,24),"CE")

    def test_sample_candle_accepts_oi_rejects_malformed(self):
        self.assertEqual(p.count_bars({"candles":[bar()]},
                         date(2025,4,17),date(2025,4,24)),1)
        with self.assertRaises(p.PilotError):
            p.count_bars({"candles":[bar(),bar()]},
                         date(2025,4,17),date(2025,4,24))
        invalid=bar();invalid[-1]=-1
        with self.assertRaises(p.PilotError):
            p.count_bars({"candles":[invalid]},
                         date(2025,4,17),date(2025,4,24))

    def test_source_workflow_counts_real_fixture_without_storing_rows(self):
        events=[]
        def fetch(url,token,budget):
            budget["count"]+=1
            self.assertEqual(token,"test-not-real")
            events.append(url)
            if "expiries?" in url:
                return ["2025-04-24"]
            if "future/contract?" in url:
                return [contract("FUT",FUT)]
            if "option/contract?" in url:
                return [contract("CE",CE,strike=22000),
                        contract("PE",PE,strike=22000)]
            if "historical-candle/" in url:
                return {"candles":[bar()]}
            self.fail("Unexpected URL")
        result=p.pilot("test-not-real",today=date(2025,5,21),fetch=fetch)
        self.assertEqual(result,("SOURCE_VERIFIED",6,1,1,2,3))
        self.assertEqual(len(events),6)
        self.assertTrue(all("api.upstox.com" in u for u in events))
        self.assertIsNone(p.parse_receipt("garbage"))
        safe=p.receipt(*result)
        self.assertEqual(p.parse_receipt(safe),result)
        self.assertNotIn(FUT,safe)
        self.assertNotIn("110.0",safe)

    def test_rejects_forged_or_duplicated_receipts(self):
        valid=p.receipt("SOURCE_VERIFIED",6,1,1,2,3)
        self.assertIsNone(p.parse_receipt(valid+"\n"+valid))
        self.assertIsNone(p.parse_receipt(valid+"SECRET"))
        self.assertIsNone(p.parse_receipt(valid.replace("|6|","|99|")))
        with self.assertRaises(ValueError):
            p.receipt("SOURCE_VERIFIED",17,1,1,2,3)

if __name__=="__main__":
    unittest.main()
