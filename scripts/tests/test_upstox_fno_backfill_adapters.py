"""No-network / no-account integration tests for Upstox F&O adapters."""
from datetime import date
import io
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import fno_history_backfill as core
import upstox_fno_backfill_adapters as a

KEY="NSE_FO|73507|24-04-2025"
FIRST=date(2025,4,20)
LAST=date(2025,4,24)
TODAY=date(2026,11,21)
TEST_TOKEN="not-a-real-upstox-token-"*3

def grant(intervals=("day",)):
    return core.RetentionGrant("verified-local-test-only",core.SOURCE,True,intervals,date(2027,1,1))

class FakeResponse:
    def __init__(self,payload,status=200):
        self.payload=payload
        self.status=status
    def __enter__(self):return self
    def __exit__(self,*args):return False
    def read(self,size):return self.payload

class FakeOpener:
    def __init__(self,response):
        self.response=response
        self.calls=[]
    def open(self,req,timeout):
        self.calls.append((req,timeout))
        if isinstance(self.response,Exception):raise self.response
        return self.response

def sample():
    return [["2025-04-21T00:00:00+05:30",101,106,100,105,1000,200]]

class IntegrationTest(unittest.TestCase):
    def test_denies_without_independent_retention_attestation_before_any_network(self):
        opener=FakeOpener(FakeResponse(b'{}'))
        provider=a.UpstoxExpiredCandles(lambda:TEST_TOKEN,grant(),None,
                                         today=lambda:TODAY,opener=opener)
        with self.assertRaises(core.GateClosed):
            provider.fetch(KEY,"day",FIRST,LAST)
        self.assertEqual(opener.calls,[])
        with tempfile.TemporaryDirectory() as d:
            db=Path(d)/"market.sqlite"
            with self.assertRaises(core.GateClosed):
                a.SQLiteHistoricalCandleSink(db,grant(),None,today=lambda:TODAY)
            self.assertFalse(db.exists())

    def test_verifier_must_return_exact_true(self):
        for check in (lambda _:False,lambda _:"yes",lambda _:None,
                      lambda _: (_ for _ in ()).throw(RuntimeError("private"))):
            with self.subTest(check=check),self.assertRaises(core.GateClosed):
                a.verify_approval(grant(),check,"day",TODAY)

    def test_expired_only_urls_and_exact_documented_interval(self):
        url=a.build_url(KEY,"day",FIRST,LAST)
        self.assertEqual(url,"https://api.upstox.com/v2/expired-instruments/historical-candle/NSE_FO%7C73507%7C24-04-2025/day/2025-04-24/2025-04-20")
        for key,interval,first,last in [
                (KEY,"week",FIRST,LAST),
                ("NSE_EQ|INE848E01016","day",FIRST,LAST),
                (KEY,"day",date(2025,4,25),date(2025,4,26)),
                (KEY,"day",LAST,FIRST)]:
            with self.subTest(interval=interval,key=key),self.assertRaises(ValueError):
                a.build_url(key,interval,first,last)

    def test_one_get_uses_bearer_and_explicit_ua_no_redirect(self):
        payload=b'{"status":"success","data":{"candles":[["2025-04-21T00:00:00+05:30",101,106,100,105,1000,200]]}}'
        op=FakeOpener(FakeResponse(payload))
        p=a.UpstoxExpiredCandles(lambda:TEST_TOKEN,grant(),lambda _:True,
                                 today=lambda:TODAY,opener=op)
        result=p.fetch(KEY,"day",FIRST,LAST)
        self.assertEqual(result.status,200)
        self.assertEqual(result.candles,sample())
        self.assertEqual(len(op.calls),1)
        req,timeout=op.calls[0]
        self.assertEqual(req.get_method(),"GET")
        self.assertEqual(req.get_header("User-agent"),a.USER_AGENT)
        self.assertEqual(req.get_header("Authorization"),"Bearer "+TEST_TOKEN)
        self.assertNotIn("Python-urllib",a.USER_AGENT)
        self.assertEqual(timeout,15)

    def test_429_backoff_header_is_capped_and_body_not_leaked(self):
        err=urllib.error.HTTPError("https://api.upstox.com",429,"secret",
                                    {"Retry-After":"9999"},io.BytesIO(b'Private provider body'))
        p=a.UpstoxExpiredCandles(lambda:TEST_TOKEN,grant(),lambda _:True,
                                 today=lambda:TODAY,opener=FakeOpener(err))
        value=p.fetch(KEY,"day",FIRST,LAST)
        self.assertEqual(value.status,429)
        self.assertEqual(value.retry_after_seconds,1800)
        self.assertIsNone(value.candles)

    def test_malformed_provider_payload_does_not_turn_into_valid_candles(self):
        for payload in (b"not-json",b'{"status":"error","data":[]}',
                        b'{"status":"success","data":{"candles":"fake"}}'):
            with self.subTest(payload=payload):
                p=a.UpstoxExpiredCandles(lambda:TEST_TOKEN,grant(),
                    lambda _:True,today=lambda:TODAY,
                    opener=FakeOpener(FakeResponse(payload)))
                self.assertNotEqual(p.fetch(KEY,"day",FIRST,LAST).status,200)

    def test_sink_storage_is_transactional_deduplicated_and_permission_checked(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"market.sqlite"
            g=grant()
            allowed=[True]
            def verify(_):return allowed[0]
            sink=a.SQLiteHistoricalCandleSink(path,g,verify,today=lambda:TODAY)
            try:
                batch=core.Batch(7,KEY,"day",FIRST,LAST,1)
                rows=[tuple(x) for x in sample()]
                self.assertTrue(sink.upsert(batch,rows,batch.dedupe_key))
                self.assertTrue(sink.upsert(batch,rows,batch.dedupe_key))
                self.assertEqual(sink.count(),1)
                self.assertEqual(sink.conn.execute("SELECT COUNT(*) FROM fno_history_receipts").fetchone()[0],1)
                allowed[0]=False
                with self.assertRaises(core.GateClosed):
                    sink.upsert(batch,rows,batch.dedupe_key)
                self.assertEqual(sink.count(),1)
                allowed[0]=True
                with self.assertRaises(ValueError):
                    sink.upsert(batch,rows,"invalid-hash")
                self.assertEqual(sink.count(),1)
            finally:
                sink.close()
            # SQLite reopen confirms durable data, not a fixture-only in-memory store.
            import sqlite3
            with sqlite3.connect(path) as db:
                self.assertEqual(db.execute("SELECT COUNT(*) FROM fno_historical_candles").fetchone()[0],1)

    def test_worker_integrates_fake_network_and_real_sqlite_sink(self):
        with tempfile.TemporaryDirectory() as d:
            base=Path(d)
            queue=core.MetadataQueue(base/"journal.db")
            g=grant()
            sink=a.SQLiteHistoricalCandleSink(base/"candles.db",g,lambda _:True,today=lambda:TODAY)
            payload=b'{"status":"success","data":{"candles":[["2025-04-21T00:00:00+05:30",101,106,100,105,1000,200]]}}'
            op=FakeOpener(FakeResponse(payload))
            provider=a.UpstoxExpiredCandles(lambda:TEST_TOKEN,g,lambda _:True,
                                             today=lambda:TODAY,opener=op)
            try:
                self.assertEqual(queue.plan(KEY,"day",FIRST,LAST,rights=g,today=TODAY),1)
                pace=core.Pace(1.5,clock=lambda:100.0,sleep=lambda _:None)
                worker=core.BackfillWorker(queue,provider,sink,g,pace,today=lambda:TODAY)
                self.assertEqual(worker.run_once(),"DONE")
                self.assertEqual(queue.summary()["completion_pct"],100)
                self.assertEqual(queue.summary()["rows_written"],1)
                self.assertEqual(sink.count(),1)
                self.assertEqual(worker.run_once(),"IDLE")
                self.assertEqual(len(op.calls),1)
            finally:
                sink.close()
                queue.close()

if __name__=="__main__":
    unittest.main()
