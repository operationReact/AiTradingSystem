"""Offline acceptance tests: F&O backfill can never call a real broker/provider."""
from datetime import date, timedelta
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import fno_history_backfill as f

TODAY=date(2026,11,21)
EXPIRED="NSE_FO|73507|24-04-2025"

def grant(*intervals):
    return f.RetentionGrant("verified-license-approval-123",f.SOURCE,True,
                            intervals or ("day",),date(2027,11,21))

class Provider:
    def __init__(self, replies):
        self.replies=list(replies)
        self.calls=[]
    def fetch(self, key,interval,start,end):
        self.calls.append((key,interval,start,end))
        if not self.replies:
            raise AssertionError("Unexpected provider call")
        reply=self.replies.pop(0)
        if isinstance(reply,BaseException):
            raise reply
        return reply

class Sink:
    def __init__(self, result=True):
        self.result=result
        self.calls=[]
        self.keys=set()
    def upsert(self,batch,rows,idempotency_key):
        self.calls.append((batch,rows,idempotency_key))
        if isinstance(self.result,BaseException):
            raise self.result
        if self.result is not True:
            return self.result
        self.keys.add(idempotency_key)
        return True

def candle(day="2025-04-21",oi=200):
    return [day+"T00:00:00+05:30",101,106,100,105,1000,oi]

class BackfillTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.file=Path(self.tmp.name)/"checkpoints.sqlite"
        self.q=f.MetadataQueue(self.file)
    def tearDown(self):
        self.q.close()
        self.tmp.cleanup()
    def plan(self,first=date(2025,4,20),last=date(2025,4,24),
             interval="day",rights=None):
        return self.q.plan(EXPIRED,interval,first,last,rights=rights or grant(interval),today=TODAY)
    def worker(self,provider,sink,rights=None):
        return f.BackfillWorker(self.q,provider,sink,
                                grant() if rights is None else rights,
                                pace=f.Pace(1.5,clock=lambda:10.0,sleep=lambda x:None),
                                today=lambda:TODAY)

    def test_no_permissions_fail_closed_before_planning_or_network(self):
        with self.assertRaises(f.GateClosed):
            self.q.plan(EXPIRED,"day",TODAY,TODAY)
        with self.assertRaises(f.GateClosed):
            self.q.plan(EXPIRED,"day",TODAY,TODAY,
                        rights=f.RetentionGrant("verified-yes",f.SOURCE,False,
                                                ("day",),TODAY),today=TODAY)
        p=Provider([f.ProviderReply(200,[candle()])])
        w=self.worker(p,Sink(),rights=None)
        # Explicit None must fail; constructor defaults none is NOT permission.
        w.rights=None
        with self.assertRaises(f.GateClosed):
            w.run_once()
        self.assertEqual(p.calls,[])
        self.assertEqual(self.q.summary()["total_batches"],0)

    def test_plan_windows_unique_idempotent_and_checkpoint_progress(self):
        added=self.plan(date(2025,1,1),date(2025,3,3))
        self.assertEqual(added,3)
        self.assertEqual(self.plan(date(2025,1,1),date(2025,3,3)),0)
        got=[]
        for _ in range(3):
            batch=self.q.claim(now=100)
            self.assertIsNotNone(batch)
            got.append((batch.first,batch.last))
            self.q.finish(batch,state="EMPTY",now=100)
        self.assertEqual(got,[(date(2025,1,1),date(2025,1,30)),
                              (date(2025,1,31),date(2025,3,1)),
                              (date(2025,3,2),date(2025,3,3))])
        status=self.q.summary()
        self.assertEqual(status["total_batches"],3)
        self.assertEqual(status["completion_pct"],100)
        self.assertEqual(status["rows_written"],0)
        self.assertNotIn(EXPIRED,self.q.status_json())
        self.assertIn('"EMPTY":3',self.q.status_json())

    def test_resume_across_restart_and_stale_leases(self):
        self.plan()
        b=self.q.claim(now=100)
        self.q.close()
        self.q=f.MetadataQueue(self.file)
        self.assertIsNone(self.q.claim(now=100+f.LEASE_SECONDS-1))
        resumed=self.q.claim(now=100+f.LEASE_SECONDS)
        self.assertEqual(resumed.job_id,b.job_id)
        self.assertEqual(resumed.attempts,2)
        self.assertEqual(resumed.dedupe_key,b.dedupe_key)
        self.q.finish(resumed,state="DONE",rows=5,now=200)
        self.assertEqual(self.q.summary()["counts"]["DONE"],1)
        self.assertIsNone(self.q.claim(now=201))

    def test_200_candles_are_validated_then_committed_idempotently(self):
        self.plan()
        p=Provider([f.ProviderReply(200,[candle(),candle("2025-04-22")])])
        sink=Sink()
        self.assertEqual(self.worker(p,sink).run_once(),"DONE")
        self.assertEqual(self.q.summary()["rows_written"],2)
        self.assertEqual(self.q.summary()["completion_pct"],100)
        self.assertEqual(len(sink.calls),1)
        self.assertEqual(len(sink.keys),1)
        self.assertEqual(sink.calls[0][2],sink.calls[0][0].dedupe_key)
        self.assertNotIn(EXPIRED,self.q.status_json())
        self.assertNotIn("101",self.q.status_json())

    def test_no_200_candles_is_not_a_successful_history_batch(self):
        self.plan()
        self.assertEqual(self.worker(Provider([f.ProviderReply(200,[])]),Sink()).run_once(),"EMPTY")
        self.assertEqual(self.q.summary()["counts"]["EMPTY"],1)
        self.assertEqual(self.q.summary()["rows_written"],0)

    def test_429_is_retried_without_marking_completed(self):
        self.plan()
        p=Provider([f.ProviderReply(429,[],retry_after_seconds=70)])
        self.assertEqual(self.worker(p,Sink()).run_once(),"RETRY")
        self.assertEqual(self.q.summary()["counts"]["RETRY"],1)
        self.assertEqual(self.q.summary()["completion_pct"],0)
        self.assertIsNone(self.q.claim(now=f.utcnow()+60))
        self.assertIsNotNone(self.q.claim(now=f.utcnow()+80))

    def test_auth_denial_blocks_without_retry(self):
        self.plan()
        self.assertEqual(self.worker(Provider([f.ProviderReply(403)]),Sink()).run_once(),"BLOCKED")
        self.assertEqual(self.q.summary()["counts"]["BLOCKED"],1)
        self.assertIsNone(self.q.claim(now=f.utcnow()+999999))

    def test_invalid_payload_500_and_network_have_distinct_reasons(self):
        for reply,expected in [(f.ProviderReply(200,[candle(oi=-1)]),"FAILED"),
                               (f.ProviderReply(502),"RETRY"),
                               (TimeoutError("private request"),"RETRY")]:
            with self.subTest(expected=expected,reply=type(reply).__name__):
                self.q.close()
                self.q=f.MetadataQueue(self.file)
                self.q.db.execute("DELETE FROM fno_backfill_jobs")
                self.plan()
                self.assertEqual(self.worker(Provider([reply]),Sink()).run_once(),expected)
                self.assertEqual(self.q.summary()["rows_written"],0)

    def test_sink_failure_does_not_advance_checkpoint(self):
        self.plan()
        p=Provider([f.ProviderReply(200,[candle()])])
        self.assertEqual(self.worker(p,Sink(result=False)).run_once(),"RETRY")
        self.assertEqual(self.q.summary()["counts"]["DONE"],0)
        self.assertEqual(self.q.summary()["rows_written"],0)

    def test_ohlcv_oi_validation_catches_bad_source(self):
        batch=f.Batch(1,EXPIRED,"day",date(2025,4,20),date(2025,4,24),1)
        for bad in ([candle(oi=-2)], [candle(),candle()],
                    [candle("2025-05-01")],
                    [["2025-04-21T00:00:00",101,106,100,105,200,100]],
                    [["2025-04-21T00:00:00+05:30",101,95,100,105,200,100]]):
            with self.subTest(bad=bad),self.assertRaises(ValueError):
                f.validate_candles(bad,batch)
        self.assertEqual(len(f.validate_candles([candle()],batch)),1)

    def test_rejects_second_daily_candle_at_0915_even_when_prices_differ(self):
        batch=f.Batch(1,EXPIRED,"day",date(2025,4,20),date(2025,4,24),1)
        next_candle=["2025-04-21T09:15:00+05:30",110,111,107,109,50,90]
        with self.assertRaisesRegex(ValueError, "INVALID_FNO_DAILY_CANDLE"):
            f.validate_candles([candle(),next_candle],batch)

    def test_rejects_a_single_nonmidnight_or_non_ist_daily_candle(self):
        batch=f.Batch(1,EXPIRED,"day",date(2025,4,20),date(2025,4,24),1)
        for ts in ("2025-04-21T09:15:00+05:30",
                   "2025-04-21T00:00:00+00:00"):
            with self.subTest(ts=ts), self.assertRaisesRegex(
                    ValueError,"INVALID_FNO_DAILY_CANDLE"):
                f.validate_candles([[ts,101,106,100,105,1000,200]],batch)

    def test_intraday_source_timestamps_are_not_subject_to_daily_rule(self):
        batch=f.Batch(1,EXPIRED,"5minute",date(2025,4,20),date(2025,4,24),1)
        rows=[
            ["2025-04-21T09:15:00+05:30",101,106,100,105,1000,200],
            ["2025-04-21T09:20:00+05:30",102,107,101,106,800,210]
        ]
        self.assertEqual(len(f.validate_candles(rows,batch)),2)

    def test_failed_or_expired_grants_do_not_invoke_provider(self):
        self.plan()
        p=Provider([f.ProviderReply(200,[candle()])])
        no=f.RetentionGrant("verified-license",f.SOURCE,True,("day",),date(2025,5,1))
        with self.assertRaises(f.GateClosed):
            self.worker(p,Sink(),rights=no).run_once()
        self.assertEqual(len(p.calls),0)

    def test_request_pace_is_conservative(self):
        now=[10.0]
        slept=[]
        def sleep(sec):
            slept.append(sec);now[0]+=sec
        gate=f.Pace(1.5,clock=lambda:now[0],sleep=sleep)
        gate.wait();gate.wait()
        self.assertEqual(slept,[1.5])
        with self.assertRaises(ValueError):
            f.Pace(0.1)

    def test_no_raw_market_data_or_secrets_in_metadata_database(self):
        self.plan()
        self.assertEqual(self.q.summary()["counts"]["PENDING"],1)
        self.assertNotIn("token",self.q.status_json().lower())
        cols=[x[1] for x in self.q.db.execute("PRAGMA table_info(fno_backfill_jobs)")]
        for forbidden in ("open","high","low","close","volume","oi","bearer","token","price"):
            self.assertNotIn(forbidden,cols)

    def test_must_be_expired_and_not_plan_after_expiry(self):
        with self.assertRaises(ValueError):
            self.q.plan(EXPIRED, "day", date(2025,4,23), date(2025,4,25),
                        rights=grant(), today=TODAY)
        with self.assertRaises(ValueError):
            self.q.plan("NSE_FO|73507|25-11-2026", "day",
                        date(2026,11,20), date(2026,11,20),
                        rights=grant(), today=TODAY)
        self.assertEqual(self.q.summary()["total_batches"], 0)

    def test_bad_contract_and_unsupported_interval_rejected(self):
        with self.assertRaises(ValueError):
            self.q.plan("NSE_EQ|12345","day",TODAY,TODAY,rights=grant(),today=TODAY)
        with self.assertRaises(f.GateClosed):
            self.q.plan(EXPIRED,"week",TODAY,TODAY,rights=grant(),today=TODAY)
        with self.assertRaises(ValueError):
            self.q.plan(EXPIRED,"day",TODAY,TODAY-timedelta(days=1),
                        rights=grant(),today=TODAY)

if __name__ == "__main__":
    unittest.main()
