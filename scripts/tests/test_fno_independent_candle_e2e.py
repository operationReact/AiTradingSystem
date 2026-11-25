"""Independent offline source->SQLite->digest verification for F&O history.

Provider responses are fixed local fixtures; no AWS/Upstox/order calls.
A success is data custody round-trip, NOT an independently proved
point-in-time strategy/backtest, and never raises historical 0/43.
"""
from datetime import date, datetime, timezone
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra"/"ec2-paper"))
import fno_history_backfill as core
import upstox_fno_backfill_adapters as adapters
import fno_independent_candle_e2e as independent

CONTRACT="NSE_FO|73507|24-04-2025"
TODAY=date(2026,11,24)
FIRST=date(2025,4,20)
LAST=date(2025,4,24)

def grant():
    return core.RetentionGrant("verified-local-test-fixture-only",core.SOURCE,True,
                               ("day",),date(2027,1,1))

def candles():
    return [
        ("2025-04-21T00:00:00+05:30",101,106,100,105,1000,200),
        ("2025-04-22T00:00:00+05:30",105,109,104,108,2000,300),
    ]

class IndependentCandleE2ETest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.path=Path(self.tmp.name)/"history.sqlite"
        self.batch=core.Batch(1,CONTRACT,"day",FIRST,LAST,1)
        self.sink=adapters.SQLiteHistoricalCandleSink(
            self.path,grant(),lambda _:True,today=lambda:TODAY)

    def tearDown(self):
        self.sink.close()
        self.tmp.cleanup()

    def store(self):
        return self.sink.upsert(self.batch,candles(),self.batch.dedupe_key)

    def test_original_fetch_to_storage_independent_digest_and_stable_first_capture(self):
        self.assertTrue(self.store())
        receipt=self.sink.conn.execute("""
            SELECT first_captured_at,normalized_sha256
            FROM fno_immutable_candle_receipt
            WHERE batch_hash=?
            """,(self.batch.dedupe_key,)).fetchone()
        self.assertEqual(len(receipt[1]),64)
        self.assertTrue(self.store())
        self.assertEqual(receipt,self.sink.conn.execute("""
            SELECT first_captured_at,normalized_sha256
            FROM fno_immutable_candle_receipt WHERE batch_hash=?
            """,(self.batch.dedupe_key,)).fetchone())
        report=independent.audit(self.path)
        self.assertEqual(report["state"],
                         "STORAGE_ROUND_TRIP_VERIFIED_NOT_STRATEGY_E2E")
        self.assertEqual(report["verifiedCandleRows"],2)
        self.assertEqual(report["verifiedImmutableBatches"],1)
        self.assertEqual(report["replayableOriginalHttpResponseBatches"],0)
        self.assertFalse(report["originalProviderPublicationTimeVerified"])
        self.assertTrue(report["sourceFirstCapturedAtVerified"])
        self.assertFalse(report["historicalFirstKnownAtReconstructed"])
        self.assertFalse(report["independentSourceRightsVerified"])
        self.assertEqual(report["historicalStrategyE2EValidated"],0)
        self.assertFalse(report["brokerOrders"])

    def test_revised_download_with_same_window_is_rejected_without_rewriting_old_data(self):
        self.store()
        changed=list(candles())
        changed[0]=(changed[0][0],101,106,100,104,1000,200)
        with self.assertRaisesRegex(ValueError,"HISTORICAL_PROVIDER_REVISION_DETECTED"):
            self.sink.upsert(self.batch,changed,self.batch.dedupe_key)
        self.assertEqual(independent.audit(self.path)["verifiedCandleRows"],2)

    def test_overlapping_window_cannot_reassign_earlier_evidence_receipt(self):
        self.store()
        overlapping=core.Batch(2,CONTRACT,"day",date(2025,4,21),LAST,1)
        with self.assertRaisesRegex(ValueError,"HISTORICAL_CANDLE_IMMUTABILITY_CONFLICT"):
            self.sink.upsert(overlapping,candles(),overlapping.dedupe_key)
        self.assertEqual(independent.audit(self.path)["verifiedImmutableBatches"],1)

    def test_independent_verifier_rejects_database_tampering(self):
        self.store()
        self.sink.conn.execute("""
            UPDATE fno_historical_candles SET close_price=close_price+1
            WHERE batch_hash=?
            """,(self.batch.dedupe_key,))
        with self.assertRaisesRegex(ValueError,"ORIGINAL_CANDLE_SHA_MISMATCH"):
            independent.audit(self.path)

    def test_first_capture_cannot_be_backdated_to_old_candle_session(self):
        self.store()
        self.sink.conn.execute("""
            UPDATE fno_immutable_candle_receipt
            SET first_captured_at=1600000000
            WHERE batch_hash=?
            """,(self.batch.dedupe_key,))
        with self.assertRaisesRegex(
            ValueError,"SOURCE_CAPTURE_TIME_OR_EXPIRY_INVALID"):
            independent.audit(self.path)

    def test_legacy_receipt_without_content_digest_cannot_be_retroactively_promoted(self):
        self.sink.conn.execute("""
            INSERT INTO fno_history_receipts
            (batch_hash,source,contract_key,interval,row_count,committed_at)
            VALUES(?,?,?,?,?,?)
            """,(self.batch.dedupe_key,core.SOURCE,CONTRACT,"day",2,1600000000))
        with self.assertRaisesRegex(
            ValueError,"UNATTESTED_LEGACY_BATCH_CANNOT_BE_REWRITTEN"):
            self.store()
        self.assertEqual(self.sink.count(),0)
        with self.assertRaisesRegex(
            ValueError,"NO_ELIGIBLE_IMMUTABLE_SOURCE_RECEIPTS"):
            independent.audit(self.path)

    def test_complete_fake_upstox_get_through_worker_to_independent_verifier(self):
        import json
        class Reply:
            status=200
            def __enter__(self):return self
            def __exit__(self,*args):return False
            def read(self,n):
                return json.dumps({"status":"success","data":{
                    "candles":[list(x) for x in candles()]}}).encode()
        class NoNetwork:
            def __init__(self):self.calls=0
            def open(self,request,timeout):
                self.calls+=1
                self_url=request.full_url
                assert self_url.startswith(
                    "https://api.upstox.com/v2/expired-instruments/historical-candle/")
                return Reply()
        opener=NoNetwork()
        g=grant()
        provider=adapters.UpstoxExpiredCandles(
            lambda:"synthetic-no-real-auth-token-"*3,g,lambda _:True,
            today=lambda:TODAY,opener=opener)
        queue=core.MetadataQueue(Path(self.tmp.name)/"journal.sqlite")
        try:
            self.assertEqual(queue.plan(CONTRACT,"day",FIRST,LAST,
                                        rights=g,today=TODAY),1)
            worker=core.BackfillWorker(queue,provider,self.sink,g,
                pace=core.Pace(1.5,clock=lambda:100.0,sleep=lambda _:None),
                today=lambda:TODAY)
            self.assertEqual(worker.run_once(),"DONE")
            self.assertEqual(opener.calls,1)
            self.assertEqual(queue.summary()["rows_written"],2)
            report=independent.audit(self.path)
            self.assertEqual(report["verifiedCandleRows"],2)
            self.assertEqual(report["replayableOriginalHttpResponseBatches"],1)
            self.assertFalse(report["originalProviderPublicationTimeVerified"])
            receipt=self.sink.conn.execute("""
                SELECT original_body,response_sha256,received_at
                FROM fno_original_http_capture
                WHERE batch_hash=?""",(self.batch.dedupe_key,)).fetchone()
            import hashlib
            self.assertEqual(hashlib.sha256(receipt[0]).hexdigest(),receipt[1])
            self.assertLessEqual(receipt[2],self.sink.conn.execute("""
                SELECT first_captured_at FROM fno_immutable_candle_receipt
                WHERE batch_hash=?""",(self.batch.dedupe_key,)).fetchone()[0])
            self.assertEqual(worker.run_once(),"IDLE")
            self.assertEqual(opener.calls,1)
        finally:
            queue.close()

    def test_raw_http_is_immutable_and_independently_rehashed(self):
        import json
        import hashlib
        original=json.dumps({"status":"success","data":{"candles":[
            list(x) for x in candles()]}}).encode()
        observation=core.SourceObservation(original,float(core.utcnow()))
        self.assertTrue(self.sink.upsert_with_observation(
            self.batch,candles(),self.batch.dedupe_key,observation))
        self.assertEqual(independent.audit(self.path)[
            "replayableOriginalHttpResponseBatches"],1)
        # Identical provider bytes are idempotent; first observed stays put.
        before=self.sink.conn.execute("""
            SELECT received_at,response_sha256 FROM fno_original_http_capture
            WHERE batch_hash=?""",(self.batch.dedupe_key,)).fetchone()
        self.assertTrue(self.sink.upsert_with_observation(
            self.batch,candles(),self.batch.dedupe_key,observation))
        after=self.sink.conn.execute("""
            SELECT received_at,response_sha256 FROM fno_original_http_capture
            WHERE batch_hash=?""",(self.batch.dedupe_key,)).fetchone()
        self.assertEqual(before,after)
        with self.assertRaisesRegex(ValueError,"ORIGINAL_HTTP_CAPTURE_CANNOT_BE_DOWNGRADED"):
            self.store()
        revised=json.dumps({"status":"success","data":{"candles":[
            list(x) for x in candles()]}},separators=(",",":")).encode()
        self.assertNotEqual(hashlib.sha256(original).hexdigest(),
                            hashlib.sha256(revised).hexdigest())
        with self.assertRaisesRegex(ValueError,"ORIGINAL_PROVIDER_RESPONSE_REVISION"):
            self.sink.upsert_with_observation(
                self.batch,candles(),self.batch.dedupe_key,
                core.SourceObservation(revised,float(core.utcnow())))
        self.sink.conn.execute("""UPDATE fno_original_http_capture
            SET response_sha256=? WHERE batch_hash=?""",
            ("0"*64,self.batch.dedupe_key))
        with self.assertRaisesRegex(ValueError,"ORIGINAL_HTTP_CAPTURE_MISMATCH"):
            independent.audit(self.path)

    def test_legacy_batches_cannot_acquire_late_original_http_receipts(self):
        import json
        self.store()
        body=json.dumps({"status":"success","data":{"candles":[
            list(x) for x in candles()]}}).encode()
        with self.assertRaisesRegex(ValueError,"CANNOT_RETROFIT_OLD_HTTP_CAPTURE"):
            self.sink.upsert_with_observation(
                self.batch,candles(),self.batch.dedupe_key,
                core.SourceObservation(body,float(core.utcnow())))
        self.assertEqual(independent.audit(self.path)[
            "replayableOriginalHttpResponseBatches"],0)

    def test_forged_http_payload_not_matching_rows_is_rejected(self):
        import json
        corrupted=[list(x) for x in candles()]
        corrupted[1][4]=107
        forged=json.dumps({"status":"success","data":{"candles":corrupted}}).encode()
        with self.assertRaisesRegex(ValueError,"HTTP_CANDLES_DO_NOT_MATCH_SOURCE"):
            self.sink.upsert_with_observation(
                self.batch,candles(),self.batch.dedupe_key,
                core.SourceObservation(forged,float(core.utcnow())))
        self.assertEqual(self.sink.count(),0)

    def test_worker_quarantines_provider_revision_without_marking_done(self):
        class StaticProvider:
            def fetch(self,*args):
                return core.ProviderReply(200,[list(x) for x in candles()])
        class ConflictingSink:
            def upsert(self,*args):
                raise ValueError("HISTORICAL_PROVIDER_REVISION_DETECTED")
        queue=core.MetadataQueue(Path(self.tmp.name)/"blocked.sqlite")
        try:
            g=grant()
            queue.plan(CONTRACT,"day",FIRST,LAST,rights=g,today=TODAY)
            worker=core.BackfillWorker(queue,StaticProvider(),ConflictingSink(),g,
                pace=core.Pace(1.5,clock=lambda:100.0,sleep=lambda _:None),
                today=lambda:TODAY)
            self.assertEqual(worker.run_once(),"BLOCKED")
            self.assertEqual(queue.summary()["counts"]["BLOCKED"],1)
            self.assertEqual(queue.summary()["rows_written"],0)
            self.assertEqual(queue.db.execute(
                "SELECT reason FROM fno_backfill_jobs").fetchone()[0],
                "SINK_PROVENANCE_REJECTED")
            self.assertEqual(worker.run_once(),"IDLE")
        finally:
            queue.close()

    def test_missing_receipts_cannot_be_reported_as_accepted_storage_roundtrip(self):
        with self.assertRaisesRegex(
            ValueError,"NO_ELIGIBLE_IMMUTABLE_SOURCE_RECEIPTS"):
            independent.audit(self.path)

if __name__=="__main__":
    unittest.main()
