"""No-network authentic-source-capture adapter tests; not market E2E."""
from datetime import date,timedelta
from pathlib import Path
import json,sqlite3,sys,unittest

BASE=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(BASE/"infra"/"ec2-paper"))
import str176_private_futures_capture as capture

EXPIRY=date(2026,2,26)
START=EXPIRY-timedelta(days=60)
KEY="NSE_FO|11111|26-02-2026"

def sample():
    first=date(2026,1,12)
    days=[first+timedelta(days=i) for i in range(22)
          if (first+timedelta(days=i)).weekday()<5]
    return json.dumps({"status":"success","data":{"candles":[
        [d.isoformat()+"T00:00:00+05:30",100+i,102+i,99+i,101+i,10000,12000]
        for i,d in enumerate(days)]}},separators=(",",":")).encode()

class FuturesOwnerOriginalCaptureTest(unittest.TestCase):
    def test_verified_original_http_is_immutable_and_idempotent(self):
        db=sqlite3.connect(":memory:",isolation_level=None)
        db.execute(capture.TABLE)
        payload=sample()
        self.assertGreaterEqual(len(capture.original_rows(payload,KEY,START,EXPIRY)),10)
        wrote,n=capture.archive_one(db,KEY,EXPIRY,75,payload,START)
        self.assertTrue(wrote)
        self.assertGreaterEqual(n,10)
        again,count=capture.archive_one(db,KEY,EXPIRY,75,payload,START)
        self.assertFalse(again)
        self.assertEqual(n,count)
        broken=json.loads(payload)
        broken["data"]["candles"][0][4]+=1
        altered=json.dumps(broken).encode()
        with self.assertRaisesRegex(capture.Blocked,"ORIGINAL_IMMUTABLE_SOURCE_CONFLICT"):
            capture.archive_one(db,KEY,EXPIRY,75,altered,START)
        stored=db.execute("SELECT raw_sha256,original_http,verified_candles "
            "FROM (SELECT original_sha256 AS raw_sha256,original_http,verified_candles "
            "FROM original_futures)").fetchone()
        import hashlib
        self.assertEqual(stored[0],hashlib.sha256(payload).hexdigest())
        self.assertEqual(stored[1],payload)
        self.assertEqual(stored[2],n)
        db.close()

    def test_rejects_ambiguous_daily_timestamp(self):
        v=json.loads(sample())
        v["data"]["candles"][2][0]=v["data"]["candles"][1][0]
        with self.assertRaisesRegex(capture.Blocked,"INVALID_UPSTOX_DAILY_SOURCE"):
            capture.original_rows(json.dumps(v).encode(),KEY,START,EXPIRY)

    def test_rejects_invalid_provider_oi_and_future_range(self):
        v=json.loads(sample())
        v["data"]["candles"][1][6]=-3
        with self.assertRaisesRegex(capture.Blocked,"INVALID_UPSTOX_DAILY_SOURCE"):
            capture.original_rows(json.dumps(v).encode(),KEY,START,EXPIRY)
        v=json.loads(sample())
        v["data"]["candles"][0][0]="2025-01-01T00:00:00+05:30"
        with self.assertRaisesRegex(capture.Blocked,"INVALID_UPSTOX_DAILY_SOURCE"):
            capture.original_rows(json.dumps(v).encode(),KEY,START,EXPIRY)

    def test_no_user_secrets_orders_or_e2e_claims_in_request(self):
        self.assertTrue(capture.EXACT["no_orders"])
        self.assertTrue(capture.EXACT["no_market_export"])
        self.assertTrue(capture.EXACT["no_rights_change"])
        self.assertEqual(capture.EXACT["max_provider_gets"],25)
        self.assertEqual(capture.EXACT["max_contract_months"],12)

if __name__=="__main__":
    unittest.main()
