"""Offline-only tests: checkpoint snapshot contains no market payload."""
from datetime import date, datetime, timezone
from pathlib import Path
import json
import os
import stat
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
from fno_history_backfill import MetadataQueue, RetentionGrant, SOURCE
from fno_history_progress_snapshot import build_snapshot, export_progress

FIXED=datetime(2026,11,21,9,30,tzinfo=timezone.utc)
CONTRACT="NSE_FO|73507|24-04-2025"

class ProgressSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
        self.queue=MetadataQueue(self.root/"checkpoint.sqlite")
    def tearDown(self):
        self.queue.close()
        self.tmp.cleanup()

    def test_empty_queue_has_null_denominator_and_no_fake_percent(self):
        result=build_snapshot(self.queue,FIXED)
        self.assertEqual(result["schema"],"minttick.fno.history.progress.v1")
        self.assertEqual(result["state"],"PREPARATION_ONLY")
        self.assertFalse(result["storageActive"])
        self.assertFalse(result["retentionRightsVerified"])
        self.assertIsNone(result["totalBatches"])
        self.assertIsNone(result["completionPercent"])
        self.assertEqual(sum(result["counts"].values()),0)

    def test_checkpoint_exports_only_safe_metadata_and_is_atomic(self):
        grant=RetentionGrant("verified-fixture-only",SOURCE,True,("day",),
                              date(2027,1,1))
        self.queue.plan(CONTRACT,"day",date(2025,4,20),date(2025,4,24),
                        rights=grant,today=date(2026,11,21))
        batch=self.queue.claim(now=100)
        self.assertIsNotNone(batch)
        self.queue.finish(batch,state="DONE",rows=5,now=101)
        destination=self.root/"progress.json"
        response=export_progress(self.queue,destination,FIXED)
        saved=json.loads(destination.read_text())
        self.assertEqual(saved,response)
        self.assertEqual(saved["totalBatches"],1)
        self.assertEqual(saved["completedBatches"],1)
        self.assertEqual(saved["completionPercent"],100.0)
        self.assertEqual(saved["rowsWritten"],5)
        self.assertEqual(saved["counts"]["DONE"],1)
        self.assertNotIn(CONTRACT,destination.read_text())
        self.assertNotIn("token",destination.read_text().lower())
        self.assertNotIn("ohlcv",destination.read_text().lower())
        self.assertEqual(stat.S_IMODE(destination.stat().st_mode),0o600)
        self.assertFalse(any(p.name.startswith(".fno-progress-") for p in self.root.iterdir()))
        self.assertLess(destination.stat().st_size,4096)

    def test_rejects_symlink_destination(self):
        dest=self.root/"real.json"
        dest.write_text("never-overwrite")
        link=self.root/"link.json"
        link.symlink_to(dest)
        with self.assertRaises(ValueError):
            export_progress(self.queue,link,FIXED)
        self.assertEqual(dest.read_text(),"never-overwrite")

    def test_timezone_required(self):
        with self.assertRaises(ValueError):
            build_snapshot(self.queue,datetime(2026,11,21,9,30))

if __name__=="__main__":
    unittest.main()
