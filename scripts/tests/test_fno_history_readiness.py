"""Offline readiness regression and two-checkpoint restart evidence.

Zero Upstox or AWS requests. All candles are temporary synthetic fixtures.
"""
from datetime import date, datetime, timezone, timedelta
from pathlib import Path
import json
import os
import stat
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import fno_history_readiness as readiness
from fno_history_backfill import (
    Batch, BackfillWorker, MetadataQueue, Pace, ProviderReply,
    RetentionGrant, SOURCE,
)
from fno_history_progress_snapshot import export_progress


class ReadinessTest(unittest.TestCase):
    def test_exact_approved_no_provider_gets_no_storage_request(self):
        request=readiness.expected_request()
        self.assertEqual(request["max_provider_gets"],0)
        self.assertIs(request["persist"],False)
        self.assertIs(request["trading_rights_change"],False)
        self.assertEqual(request["instance_id"],"i-09c01b470fb39b85a")
        self.assertEqual(request["account"],"098859530205")
        self.assertEqual(request["region"],"ap-south-1")

    def test_fixed_receipt_only_and_injection_rejected(self):
        valid=readiness.make_receipt(
            "PASS","NOT_INSTALLED","MISSING","MISSING",
            "NOT_INSTALLED","UNVERIFIED","RUNTIME_NOT_INSTALLED")
        self.assertEqual(len(readiness.parse_receipt(valid)),7)
        self.assertIsNone(readiness.parse_receipt(valid+"\n"+valid))
        self.assertIsNone(readiness.parse_receipt(valid+"SECRET"))
        self.assertIsNone(readiness.parse_receipt(valid.replace(
            "UNVERIFIED","APPROVED")))
        self.assertIsNone(readiness.parse_receipt(valid+"\n"+readiness.MARKER+"bad"))
        with self.assertRaises(ValueError):
            readiness.make_receipt("PASS","PRESENT","MISSING",
                                   "MISSING","STOPPED","VERIFIED","RUNTIME_NOT_INSTALLED")

    def test_zero_side_effect_host_status_without_permission(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)
            status=readiness.inspect_host(
                root=p,journal=p/"checkpoints.sqlite",
                snapshot=p/"progress.json",
                module=p/"nonexistent.py", backup=p/"none.py",
                svc=lambda:"NOT_INSTALLED",enforce_host_identity=False)
            self.assertEqual(status,(
                "PASS","NOT_INSTALLED","MISSING","MISSING",
                "NOT_INSTALLED","UNVERIFIED","RUNTIME_NOT_INSTALLED"))
            self.assertEqual(list(p.iterdir()),[])

    def test_service_active_without_code_is_anomaly_not_ready(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)
            status=readiness.inspect_host(
                root=p,journal=p/"missing.sqlite",
                snapshot=p/"missing.json",
                module=p/"missing.py",backup=p/"missing2.py",
                svc=lambda:"RUNNING",enforce_host_identity=False)
            self.assertEqual(status[-1],"RUNTIME_ANOMALY")
            self.assertEqual(status[-2],"UNVERIFIED")

    def test_snapshot_fresh_stale_and_invalid(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"progress.json"
            now=datetime(2026,11,21,10,0,tzinfo=timezone.utc)
            valid={
                "schema":"minttick.fno.history.progress.v1",
                "source":"upstox-expired-nse-fo",
                "planScope":"CURRENT_JOURNAL_BATCHES_ONLY",
                "retentionRightsVerified":False,
                "storageActive":False,
                "state":"PREPARATION_ONLY",
                "generatedAt": now.isoformat(),
            }
            p.write_text(json.dumps(valid))
            self.assertEqual(readiness.snapshot_status(p,now),"PRESENT_FRESH")
            valid["generatedAt"]=(now-timedelta(minutes=11)).isoformat()
            p.write_text(json.dumps(valid))
            self.assertEqual(readiness.snapshot_status(p,now),"PRESENT_STALE")
            valid["retentionRightsVerified"]=True
            p.write_text(json.dumps(valid))
            self.assertEqual(readiness.snapshot_status(p,now),"INVALID")

    def test_two_consecutive_checkpoints_survive_restart_without_real_provider(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            journal=root/"journal.sqlite"
            snapshot=root/"progress.json"
            rights=RetentionGrant("verified-fake-offline-test-only",SOURCE,True,
                                  ("day",),date(2027,12,31))
            key="NSE_FO|73507|24-04-2025"
            queue=MetadataQueue(journal)
            try:
                # 31 calendar days = two disjoint 30-day windows.
                count=queue.plan(key,"day",date(2025,3,20),date(2025,4,19),
                                 rights=rights,today=date(2026,11,21))
                self.assertEqual(count,2)
                self.assertEqual(queue.summary()["completion_pct"],0)
                done_first=queue.claim(now=100)
                self.assertEqual(done_first.first,date(2025,3,20))
                # Idempotent fixture sink commits only synthetic daily rows.
                dedupe=set()
                def commit(batch):
                    dedupe.add(batch.dedupe_key)
                    return True
                self.assertTrue(commit(done_first))
                queue.finish(done_first,state="DONE",rows=1,now=101)
                self.assertEqual(len(dedupe),1)
                export_progress(queue,snapshot,now=datetime.now(timezone.utc))
                first=json.loads(snapshot.read_text())
                self.assertEqual(first["completedBatches"],1)
                self.assertEqual(first["completionPercent"],50.0)
                self.assertEqual(first["totalBatches"],2)
            finally:
                queue.close()
            # Re-open the durable metadata journal; no duplicate first batch.
            queue=MetadataQueue(journal)
            try:
                second=queue.claim(now=110)
                self.assertIsNotNone(second)
                self.assertNotEqual(second.dedupe_key,done_first.dedupe_key)
                self.assertTrue(commit(second))
                queue.finish(second,state="DONE",rows=1,now=111)
                export_progress(queue,snapshot,now=datetime.now(timezone.utc))
                report=json.loads(snapshot.read_text())
                self.assertEqual(report["completionPercent"],100.0)
                self.assertEqual(report["counts"]["DONE"],2)
                self.assertEqual(report["rowsWritten"],2)
                self.assertEqual(len(dedupe),2)
                self.assertIsNone(queue.claim(now=112))
                self.assertEqual(stat.S_IMODE(snapshot.stat().st_mode),0o600)
                self.assertNotIn(key,snapshot.read_text())
                self.assertNotIn("Bearer",snapshot.read_text())
                self.assertIs(report["retentionRightsVerified"],False)
                self.assertIs(report["storageActive"],False)
            finally:
                queue.close()


if __name__=="__main__":
    unittest.main()
