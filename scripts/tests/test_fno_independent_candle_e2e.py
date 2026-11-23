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

    def test_missing_receipts_cannot_be_reported_as_accepted_storage_roundtrip(self):
        with self.assertRaisesRegex(
            ValueError,"NO_ELIGIBLE_IMMUTABLE_SOURCE_RECEIPTS"):
            independent.audit(self.path)

if __name__=="__main__":
    unittest.main()
