#!/usr/bin/env python3
"""Synthetic offline tests: no provider data, AWS, SSM, or broker credentials."""
from datetime import datetime, timezone, date, timedelta
import calendar
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
from fno_private_history_audit import audit, read_only, SOURCE


class PrivateHistoryAuditTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.jpath = Path(self.work.name) / "jobs.sqlite"
        self.cpath = Path(self.work.name) / "cache.sqlite"
        self.journal = sqlite3.connect(self.jpath)
        self.cache = sqlite3.connect(self.cpath)
        self.addCleanup(self.journal.close)
        self.addCleanup(self.cache.close)
        self.journal.execute("""CREATE TABLE fno_backfill_jobs (
            contract_key TEXT, interval TEXT, first_day TEXT, last_day TEXT,
            state TEXT, rows_written INTEGER, attempts INTEGER, reason TEXT)""")
        self.cache.execute("""CREATE TABLE fno_historical_candles (
            source TEXT, contract_key TEXT, interval TEXT, candle_ts TEXT,
            open_price REAL, high_price REAL, low_price REAL,
            close_price REAL, volume INTEGER, open_interest INTEGER,
            batch_hash TEXT)""")
        self.cache.execute("""CREATE TABLE fno_history_receipts (
            batch_hash TEXT, source TEXT, contract_key TEXT, interval TEXT,
            row_count INTEGER, committed_at REAL)""")
        self.key = "NSE_FO|synthetic|30-10-2026"
        self.first = "2026-10-10"
        self.last = "2026-10-30"
        self.digest = hashlib.sha256(
            f"{SOURCE}|{self.key}|day|{self.first}|{self.last}".encode()).hexdigest()
        self.journal.execute("INSERT INTO fno_backfill_jobs VALUES(?,?,?,?,?,?,?,?)",
                             (self.key, "day", self.first, self.last, "DONE", 1, 1, "NONE"))
        self.cache.execute("INSERT INTO fno_history_receipts VALUES (?,?,?,?,?,?)",
                           (self.digest, SOURCE, self.key, "day", 1, 1))
        self.cache.execute("INSERT INTO fno_historical_candles VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                           (SOURCE, self.key, "day", "2026-10-12T00:00:00+05:30",
                            100.0, 103.0, 99.0, 102.0, 10, 0, self.digest))
        self.journal.commit()
        self.cache.commit()

    def run_audit(self):
        return audit(self.journal, self.cache)

    def test_clean_synthetic_candle_and_zero_oi_are_valid(self):
        report = self.run_audit()
        self.assertEqual(report["findingCount"], 0)
        self.assertEqual(report["candles"]["stored"], 1)
        self.assertEqual(report["candles"]["zeroOIValid"], 1)
        self.assertEqual(report["coverage"]["expiryMonths"], 1)
        self.assertEqual(report["jobs"]["states"]["DONE"], 1)
        self.assertEqual(report["result"], "REVIEW")  # pilot must reach 276 jobs, 12 months
        self.assertNotIn(self.key, json.dumps(report))

    def test_rejects_bad_ohlc_and_missing_oi(self):
        self.cache.execute("UPDATE fno_historical_candles SET high_price=98,open_interest=NULL")
        report = self.run_audit()
        self.assertEqual(report["findings"]["inconsistentOHLC"], 1)
        self.assertEqual(report["findings"]["invalidOrMissingOI"], 1)

    def test_rejects_duplicate_business_key(self):
        self.cache.execute("""INSERT INTO fno_historical_candles
            SELECT * FROM fno_historical_candles""")
        report = self.run_audit()
        self.assertEqual(report["findings"]["duplicateCandles"], 1)
        self.assertEqual(report["findings"]["receiptVsCachedRowsMismatch"], 1)

    def test_reconciles_missing_checkpoint_receipt(self):
        self.cache.execute("DELETE FROM fno_history_receipts")
        report = self.run_audit()
        self.assertEqual(report["findings"]["doneMissingReceipt"], 1)

    def test_detects_outside_batch_and_orphan_candles(self):
        self.cache.execute("""UPDATE fno_historical_candles
                              SET candle_ts='2026-11-01T00:00:00+05:30'""")
        report = self.run_audit()
        self.assertEqual(report["findings"]["outsideBatchWindow"], 1)
        self.cache.execute("UPDATE fno_historical_candles SET batch_hash='unknown'")
        report = self.run_audit()
        self.assertEqual(report["findings"]["orphanCandleRows"], 1)

    def test_multiple_daily_timestamps_same_contract_are_rejected(self):
        self.cache.execute("""INSERT INTO fno_historical_candles
            SELECT source, contract_key, interval, '2026-10-12T15:30:00+05:30',
                   open_price, high_price, low_price, close_price,
                   volume, open_interest, batch_hash FROM fno_historical_candles""")
        self.cache.execute("UPDATE fno_history_receipts SET row_count=2")
        self.journal.execute("UPDATE fno_backfill_jobs SET rows_written=2")
        report = self.run_audit()
        self.assertEqual(report["findings"]["duplicateDailyDates"], 1)
        self.assertEqual(report["coverage"]["months"][0]["duplicateDailyDates"], 1)
        self.assertEqual(report["findings"].get("duplicateCandles", 0), 0)

    def test_duplicate_daily_identical_payload_is_classified(self):
        self.cache.execute("""INSERT INTO fno_historical_candles
            SELECT source,contract_key,interval,'2026-10-12T15:30:00+05:30',
                   open_price,high_price,low_price,close_price,volume,
                   open_interest,batch_hash FROM fno_historical_candles""")
        data = self.run_audit()
        m = data["coverage"]["months"][0]
        self.assertEqual(m["duplicateIdenticalPayload"], 1)
        self.assertEqual(m["duplicateConflictingPayload"], 0)
        self.assertEqual(m["duplicateSameInstant"], 0)
        self.assertEqual(m["duplicateDailyDates"], 1)
        self.assertEqual(sum(x["count"] for x in m["timestampPatterns"]), 2)

    def test_duplicate_daily_conflicting_payload_is_classified(self):
        self.cache.execute("""INSERT INTO fno_historical_candles
            SELECT source,contract_key,interval,'2026-10-12T15:30:00+05:30',
                   open_price,high_price,low_price,close_price+1,volume,
                   open_interest,batch_hash FROM fno_historical_candles""")
        m = self.run_audit()["coverage"]["months"][0]
        self.assertEqual(m["duplicateIdenticalPayload"], 0)
        self.assertEqual(m["duplicateConflictingPayload"], 1)

    def test_enforces_exact_pilot_21_day_expiry_window(self):
        self.journal.execute(
            "UPDATE fno_backfill_jobs SET first_day='2026-10-09'")
        report = self.run_audit()
        self.assertEqual(report["findings"]["unexpectedPilotWindow"], 1)

    def test_research_quarantine_separates_only_provider_duplicate_month(self):
        self.journal.execute("DELETE FROM fno_backfill_jobs")
        self.cache.execute("DELETE FROM fno_historical_candles")
        self.cache.execute("DELETE FROM fno_history_receipts")
        for year,month in [(2025,11),(2025,12)]+[(2026,m) for m in range(1,11)]:
            expiry=date(year,month,calendar.monthrange(year,month)[1])
            first=expiry-timedelta(days=20)
            for n in range(23):
                key=f"NSE_FO|SYN{n:03}|{expiry:%d-%m-%Y}"
                digest=hashlib.sha256(
                    f"{SOURCE}|{key}|day|{first}|{expiry}".encode()).hexdigest()
                timestamps=[f"{expiry.isoformat()}T00:00:00+05:30"]
                if month==12:
                    timestamps=[
                        f"{(expiry-timedelta(days=d)).isoformat()}T00:00:00+05:30"
                        for d in range(14)]
                    timestamps += [
                        f"{(expiry-timedelta(days=d)).isoformat()}T09:15:00+05:30"
                        for d in range(14) if (n,d)!=(22,13)]
                count=len(timestamps)
                self.journal.execute("INSERT INTO fno_backfill_jobs VALUES(?,?,?,?,?,?,?,?)",
                    (key,"day",first.isoformat(),expiry.isoformat(),"DONE",count,1,"NONE"))
                self.cache.execute("INSERT INTO fno_history_receipts VALUES(?,?,?,?,?,?)",
                    (digest,SOURCE,key,"day",count,1))
                self.cache.executemany("INSERT INTO fno_historical_candles VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    [(SOURCE,key,"day",stamp,100.,103.,99.,102.,10,1,digest)
                     for stamp in timestamps])
        report=self.run_audit()
        self.assertEqual(report["result"],"REVIEW")
        self.assertEqual(report["findingCount"],321)
        self.assertEqual(report["quarantine"]["status"],"ISOLATED")
        self.assertEqual(report["quarantine"]["eligibleExpiryMonths"],11)
        self.assertEqual(report["quarantine"]["eligibleContracts"],253)
        self.assertEqual(report["quarantine"]["excludedCandles"],643)
        self.cache.execute("UPDATE fno_historical_candles SET open_interest=-1 "
                           "WHERE contract_key LIKE '%30-11-2025'")
        report=self.run_audit()
        self.assertEqual(report["quarantine"]["status"],"BLOCKED")

    def test_read_only_connection_rejects_writes(self):
        self.cache.commit()
        with read_only(self.cpath) as connection:
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("UPDATE fno_historical_candles SET volume=0")

    def test_schema_fails_closed(self):
        self.cache.execute("DROP TABLE fno_history_receipts")
        with self.assertRaisesRegex(ValueError, "AUDIT_SCHEMA_UNSUPPORTED"):
            self.run_audit()


if __name__ == "__main__":
    unittest.main()
