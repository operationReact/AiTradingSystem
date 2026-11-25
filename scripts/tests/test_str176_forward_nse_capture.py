import importlib
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra/ec2-paper"))
m=importlib.import_module("str176_forward_nse_capture")
DAY=date(2026,11,25)
OBS=datetime(2026,11,25,15,0,tzinfo=timezone.utc)
COUNTS={"indexFutures":3,"indexOptions":25}
class CaptureTest(unittest.TestCase):
    def test_failure_receipts_never_publish_arbitrary_host_output(self):
        self.assertEqual("SOURCE_FILE_NOT_AVAILABLE",m.failure_receipt(
            m.FAILURE_MARKER+"SOURCE_FILE_NOT_AVAILABLE\n"))
        self.assertEqual("HOST_CAPTURE_NO_SAFE_RECEIPT",m.failure_receipt(
            m.FAILURE_MARKER+"unexpected private value\n"))
        self.assertEqual("HOST_CAPTURE_NO_SAFE_RECEIPT",m.failure_receipt(
            m.FAILURE_MARKER+"SOURCE_HTTP_REJECTED\n"+m.FAILURE_MARKER+"SOURCE_TOO_LARGE"))
        self.assertEqual("SOURCE_CAPTURE_UNEXPECTED_FAILURE",m.safe_reason(
            m.Blocked("untrusted private value")))
        self.assertEqual("PRIVATE_STORAGE_UNAVAILABLE",m.safe_reason(OSError("private path")))

    def test_transport_bundles_reviewed_parser_and_compiles(self):
        source=m.transport()
        compile(source,"<protected-host>","exec")
        self.assertIn(b"fno_personal_nse_archive_probe",source)
        self.assertIn(b"def read_udiff",source)

    def test_archive_url_matches_published_nse_archive(self):
        self.assertEqual(
            "https://nsearchives.nseindia.in/content/fo/BhavCopy_NSE_FO_0_0_0_20261125_F_0000.csv.zip",
            m.archive_url(DAY))

    def test_scope_is_bounded_and_not_before_final_eod(self):
        m.require_scope(DAY,OBS)
        for bad in (OBS-timedelta(hours=8),OBS+timedelta(days=3)):
            with self.assertRaises(m.Blocked):m.require_scope(DAY,bad)
        with self.assertRaises(m.Blocked):m.require_scope(DAY-timedelta(days=2),OBS)

    def test_request_binds_every_control_and_only_accepts_recent_iso_session_date(self):
        request={**m.FIXED_REQUEST,"session_date":DAY.isoformat()}
        self.assertEqual(DAY,m.request_date(request,OBS))
        for invalid in (
            {**request,"session_date":"2026-11-25;id"},
            {**request,"session_date":"2026-11-20"},
            {**request,"no_orders":False},
            {**request,"no_orders":1},
            {**request,"extra":True},
        ):
            with self.assertRaises(m.Blocked):m.request_date(invalid,OBS)
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"request.json"
            path.write_text('{"kind":"one","kind":"two"}')
            with self.assertRaises(m.Blocked):m.load_request(path,OBS)

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
            self.assertEqual(0,m.validate_report(second,DAY)["certificationStoreRowsAdded"])
            self.assertFalse(first["originalPublicationVerified"])
            self.assertFalse(first["orders"])
            with sqlite3.connect(path) as db:
                self.assertEqual([(raw,)],db.execute(
                    "SELECT original_zip FROM original_exchange_eod").fetchall())
            revised=m.persist(path,DAY,"https://example.invalid",b"revised",
                              OBS+timedelta(minutes=10),None,COUNTS)
            self.assertTrue(revised["newArchive"])
            with self.assertRaises(m.Blocked):
                m.validate_report({**revised,"certifiedStrategies":1},DAY)

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
