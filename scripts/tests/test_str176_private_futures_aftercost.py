"""Synthetic offline tests; only deployed owner host can prove real market replay."""
import hashlib,json,sqlite3,sys,unittest
from datetime import date,timedelta
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"infra/ec2-paper"))
import str176_private_futures_aftercost as replay

def monthly(month,year=2026):
    expiry=date(year,month,26)
    first=expiry-timedelta(days=40)
    days=[first+timedelta(days=i) for i in range(41)
          if (first+timedelta(days=i)).weekday()<5
          and first+timedelta(days=i)<=expiry]
    bars=[]
    for i,d in enumerate(days):
        price=100+i*3
        bars.append([d.isoformat()+"T00:00:00+05:30",
                     price-1,price+.3,price-1.5,price,
                     30000 if i>=4 else 10000,60000])
    raw=json.dumps({"status":"success","data":{"candles":bars}},
                   separators=(",",":")).encode()
    key="NSE_FO|"+str(80000+month)+"|"+expiry.strftime("%d-%m-%Y")
    return (key,expiry.isoformat(),75,raw,
            hashlib.sha256(raw).hexdigest(),1760000000.,len(bars))

class ActualFuturesHistoricalReplayContractTest(unittest.TestCase):
    def test_seven_session_v1_upward_momentum_and_fixed_cost(self):
        item=monthly(5)
        bars=json.loads(item[3])["data"]["candles"]
        decision=date.fromisoformat(bars[8][0][:10])
        self.assertEqual(replay.signal(bars[0:7],date.fromisoformat(item[1]),
                                       decision),"UP")
        result=replay.replay([monthly(i) for i in range(1,13)])
        self.assertEqual(result["futuresContracts"],12)
        self.assertGreater(result["trainingDecisions"],0)
        self.assertGreater(result["oosDecisions"],0)
        self.assertGreater(result["trainingSimulatedTrades"],0)
        self.assertGreater(result["oosSimulatedTrades"],0)
        self.assertEqual(result["roundTripCostBps"],40)
        self.assertFalse(result["brokerOrders"])
        self.assertFalse(result["sourceDataExported"])
        self.assertEqual(result["independentHistoricalE2ECertified"],0)

    def test_no_last_bar_or_same_bar_execution_is_possible(self):
        item=monthly(5)
        bars=json.loads(item[3])["data"]["candles"]
        result=replay.replay([item])
        self.assertLessEqual(result["trainingDecisions"],
            len(bars)-9)
        self.assertEqual(result["oosDecisions"],0)

    def test_source_revision_digest_mismatch_fails_closed(self):
        item=list(monthly(5))
        item[4]="0"*64
        with self.assertRaisesRegex(replay.InvalidSource,
                                    "CONTRACT_OR_CUSTODY_UNVERIFIED"):
            replay.replay([tuple(item)])

    def test_bad_candle_or_market_time_rejected(self):
        item=list(monthly(5))
        payload=json.loads(item[3])
        payload["data"]["candles"][0][0]="2024-01-01T00:00:00+05:30"
        item[3]=json.dumps(payload).encode()
        item[4]=hashlib.sha256(item[3]).hexdigest()
        with self.assertRaises(replay.InvalidSource):
            replay.replay([tuple(item)])

    def test_no_market_signal_still_not_called_historical_certification(self):
        item=list(monthly(5))
        payload=json.loads(item[3])
        for i,r in enumerate(payload["data"]["candles"]):
            r[1:5]=[100,101,99.5,100]
            r[5]=10000
            r[6]=100000
        item[3]=json.dumps(payload).encode()
        item[4]=hashlib.sha256(item[3]).hexdigest()
        result=replay.replay([tuple(item)])
        self.assertEqual(result["signalCandidates"],0)
        self.assertEqual(result["independentHistoricalE2ECertified"],0)

    def test_three_rights_flags_never_autocertify_or_place_orders(self):
        self.assertEqual(replay.ROUND_TRIP_BPS,40)
        self.assertTrue(replay.SOURCE_REQUEST["no_orders"])
        self.assertTrue(replay.SOURCE_REQUEST["no_rights_change"])
        self.assertTrue(replay.SOURCE_REQUEST["no_market_export"])

if __name__=="__main__":
    unittest.main()
