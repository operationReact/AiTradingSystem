import importlib
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra/ec2-paper"))
m=importlib.import_module("str176_forward_nse_capture")
DAY=date.fromisoformat(m.EXACT["session_date"])
OBS=datetime(2026,11,24,15,0,tzinfo=timezone.utc)
COUNTS={"indexFutures":3,"indexOptions":25}
class CaptureTest(unittest.TestCase):
    def test_scope_is_bounded_and_not_before_final_eod(self):
        m.require_scope(DAY,OBS)
        for bad in (OBS-timedelta(hours=8),OBS+timedelta(days=3)):
            with self.assertRaises(m.Blocked):m.require_scope(DAY,bad)
        with self.assertRaises(m.Blocked):m.require_scope(DAY-timedelta(days=1),OBS)

    def test_exact_readback_idempotent_and_preserves_first_observation(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"source.sqlite"
            raw=b"synthetic fixture only"
            first=m.persist(path,DAY,"https://example.invalid",raw,OBS,None,COUNTS)
            second=m.persist(path,DAY,"https://example.invalid",raw,
                             OBS+timedelta(minutes=5),None,COUNTS)
            self.assertTrue(first["newArchive"])
            self.assertFalse(second["newArchive"])
            self.assertEqual(first["firstObservedAt"],second["firstObservedAt"])
            self.assertEqual(0,m.validate_report(second)["certificationStoreRowsAdded"])
            self.assertFalse(first["originalPublicationVerified"])
            self.assertFalse(first["orders"])
            with sqlite3.connect(path) as db:
                self.assertEqual([(raw,)],db.execute(
                    "SELECT original_zip FROM original_exchange_eod").fetchall())
            revised=m.persist(path,DAY,"https://example.invalid",b"revised",
                              OBS+timedelta(minutes=10),None,COUNTS)
            self.assertTrue(revised["newArchive"])
            with self.assertRaises(m.Blocked):
                m.validate_report({**revised,"certifiedStrategies":1})

    def test_missing_cohort_and_insecure_directory_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"source.sqlite"
            with self.assertRaises(m.Blocked):
                m.persist(path,DAY,"https://example.invalid",b"x",OBS,None,
                          {"indexFutures":0,"indexOptions":25})
            Path(d).chmod(0o755)
            with self.assertRaises(m.Blocked):
                m.persist(path,DAY,"https://example.invalid",b"x",OBS,None,COUNTS)
            self.assertFalse(path.exists())

if __name__=="__main__":unittest.main()
