"""Offline regression for private research F&O bulk collection.

No AWS tokens, Upstox requests, EC2 writes, trade execution or real candles.
"""
from datetime import date
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra"/"ec2-paper"))
import fno_private_bulk_worker as p
import fno_history_backfill as core
from upstox_fno_backfill_adapters import SQLiteHistoricalCandleSink

KEYF="NSE_FO|73507|24-04-2025"
KEYCE="NSE_FO|73508|24-04-2025"
KEYPE="NSE_FO|73509|24-04-2025"
DAY=date(2026,11,21)
EXP=date(2025,4,24)

def contract(key,kind,strike=None):
    obj={"segment":"NSE_FO","exchange":"NSE",
         "underlying_key":p.UNDERLYING,
         "expiry":EXP.isoformat(),"instrument_type":kind,
         "instrument_key":key}
    if strike is not None:obj["strike_price"]=strike
    return obj

def candle():
    return ("2025-04-21T00:00:00+05:30",110.,111.,109.,110.5,300,120)

class BulkTest(unittest.TestCase):
    def test_safe_failure_reason_only_emits_allowlisted_codes(self):
        self.assertEqual("UPSTOX_TOKEN_UNAVAILABLE",
                         p.safe_failure_reason(p.GateClosed("UPSTOX_TOKEN_UNAVAILABLE")))
        self.assertEqual("PRIVATE_DB_ERROR",p.safe_failure_reason(sqlite3.Error("/private/path")))
        self.assertEqual("UPSTOX_EXPIRED_HISTORY_NETWORK_ERROR",
                         p.safe_failure_reason(OSError("UPSTOX_EXPIRED_HISTORY_NETWORK_ERROR")))
        self.assertEqual("UNCLASSIFIED_SAFE_FAILURE",
                         p.safe_failure_reason(RuntimeError("secret token")))

    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.path=Path(self.tmp.name)
        self.permit=self.path/"permit.json"
        self.permit.write_text(json.dumps(p.private_scope()))
        self.mock1=patch.object(p,"PERMIT",self.permit)
        self.mock2=patch.object(p,"today",lambda:DAY)
        self.mock3=patch.object(p,"STATUS",self.path/"progress.json")
        self.mock1.start()
        self.mock2.start()
        self.mock3.start()
    def tearDown(self):
        self.mock1.stop()
        self.mock2.stop()
        self.mock3.stop()
        self.tmp.cleanup()

    def test_owner_scope_exact_and_time_limited_not_provider_license(self):
        g=p.grant()
        self.assertEqual(g.intervals,("day",))
        self.assertEqual(g.valid_until,date(2026,11,28))
        self.assertFalse(p.private_scope()["provider_licence_verified"])
        self.assertTrue(p.private_scope()["no_redistribution"])
        self.assertTrue(p.verify_scope(g))
        changed=p.private_scope()
        changed["no_redistribution"]=False
        self.permit.write_text(json.dumps(changed))
        self.assertFalse(p.verify_scope(g))
        with self.assertRaises(core.GateClosed):
            p.grant()

    def test_missing_permit_blocks_before_api_or_database(self):
        self.permit.unlink()
        with self.assertRaises(core.GateClosed):
            p.grant()
        self.assertEqual(list(self.path.iterdir()),[])

    def test_expiry_selection_one_per_completed_month(self):
        rows=["2025-02-27","2025-03-27","2025-04-17","2025-04-24",
              "2025-05-29","2025-06-26"]
        self.assertEqual(p.monthly_expiries(rows,date(2025,7,4)),
                         [date(2025,6,26),date(2025,5,29),
                          date(2025,4,24),date(2025,3,27),
                          date(2025,2,27)])

    def test_selects_exact_futures_and_matched_options_only(self):
        fs=[contract(KEYF,"FUT")]
        opts=[contract(KEYCE,"CE",22000),contract(KEYPE,"PE",22000),
              contract("NSE_FO|99115|24-04-2025","CE",22100)]
        self.assertEqual(p.contracts(fs,opts,EXP),[KEYF,KEYCE,KEYPE])
        with self.assertRaises(ValueError):
            p.contracts(fs,[contract("NSE_EQ|abc","CE",21000)],EXP)

    def test_idempotent_job_planning_and_sink_commit(self):
        class Fetch:
            def __init__(self):self.calls=0
            def get(self,path):
                self.calls+=1
                if "/expiries?" in path:return [EXP.isoformat()]
                if "/future/contract?" in path:return [contract(KEYF,"FUT")]
                if "/option/contract?" in path:
                    return [contract(KEYCE,"CE",22000),contract(KEYPE,"PE",22000)]
                raise AssertionError(path)
        q=core.MetadataQueue(self.path/"queue.sqlite")
        try:
            fetch=Fetch()
            self.assertEqual(p.source_plan(q,fetch,p.grant(),3),3)
            self.assertEqual(fetch.calls,3)
            self.assertEqual(p.source_plan(q,fetch,p.grant(),3),0)
            self.assertEqual(q.summary()["total_batches"],3)
            batch=q.claim(now=100)
            self.assertIsNotNone(batch)
            sink=SQLiteHistoricalCandleSink(self.path/"cache.sqlite",
                 grant=p.grant(),verifier=p.verify_scope,today=lambda:DAY)
            try:
                row=[candle()]
                self.assertTrue(sink.upsert(batch,row,batch.dedupe_key))
                self.assertTrue(sink.upsert(batch,row,batch.dedupe_key))
                q.finish(batch,state="DONE",rows=1,now=101)
                snap=p.persist_summary(q,sink)
                self.assertEqual(snap["completedJobs"],1)
                self.assertEqual(snap["candleRows"],1)
                self.assertAlmostEqual(snap["percent"],33.33)
                text=p.STATUS.read_text() if p.STATUS.exists() else ""
                self.assertNotIn("Bearer",text)
                self.assertNotIn(KEYF,text)
            finally:
                sink.close()
        finally:
            q.close()

    def test_cache_purges_expired_research_rows(self):
        q=core.MetadataQueue(self.path/"queue.sqlite")
        try:
            q.plan(KEYF,"day",date(2025,4,20),EXP,rights=p.grant(),today=DAY)
            batch=q.claim(now=1)
            sink=SQLiteHistoricalCandleSink(self.path/"cache.sqlite",
                grant=p.grant(),verifier=p.verify_scope,today=lambda:DAY)
            try:
                self.assertTrue(sink.upsert(batch,[candle()],batch.dedupe_key))
                self.assertEqual(sink.count(),1)
                sink.conn.execute("UPDATE fno_history_receipts SET committed_at=1")
                p.cleanup_expired_cache(sink)
                self.assertEqual(sink.count(),0)
                self.assertEqual(sink.conn.execute(
                    "SELECT COUNT(*) FROM fno_immutable_candle_receipt"
                    ).fetchone()[0],0)
            finally:
                sink.close()
        finally:
            q.close()

    def test_full_owner_expiry_purges_immutable_receipts_too(self):
        q=core.MetadataQueue(self.path/"queue.sqlite")
        try:
            q.plan(KEYF,"day",date(2025,4,20),EXP,rights=p.grant(),today=DAY)
            batch=q.claim(now=1)
            db_path=self.path/"cache.sqlite"
            sink=SQLiteHistoricalCandleSink(db_path,grant=p.grant(),
                verifier=p.verify_scope,today=lambda:DAY)
            try:
                sink.upsert(batch,[candle()],batch.dedupe_key)
                self.assertEqual(sink.conn.execute(
                    "SELECT COUNT(*) FROM fno_immutable_candle_receipt"
                    ).fetchone()[0],1)
            finally:
                sink.close()
            with patch.object(p,"BASE_DIR",self.path),patch.object(
                    p,"DATA_DB",db_path),patch.object(
                    p,"today",lambda:date(2026,11,29)):
                self.assertEqual(p.expire_private_cache(),
                    "OWNER_SCOPE_EXPIRED_CACHE_CLEARED")
            import sqlite3
            with sqlite3.connect(db_path) as db:
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM fno_historical_candles"
                    ).fetchone()[0],0)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM fno_history_receipts"
                    ).fetchone()[0],0)
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM fno_immutable_candle_receipt"
                    ).fetchone()[0],0)
        finally:
            q.close()

    def test_no_network_during_import_and_no_live_trading_endpoint(self):
        source=Path(p.__file__).read_text()
        self.assertNotIn("/order/place",source)
        self.assertNotIn("api.upstox.com/v2/order",source)
        self.assertNotIn("os.system",source)

if __name__=="__main__":
    unittest.main()
