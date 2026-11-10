"""Offline, no credentials/cloud/provider tests for EC2 owner-only personal archive."""
import datetime as dt
import decimal
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "infra/ec2-paper/owner_personal_angelone_history.py"
NAMESPACE = {"__name__": "_offline_personal_history", "API_ROOT": "https://invalid.example"}
exec(compile(SOURCE.read_text(), str(SOURCE), "exec"), NAMESPACE)


def f(name):
    return NAMESPACE[name]


def candle(date="2006-11-10"):
    return [date + "T09:15:00+05:30", 101, 115, 90, 105, 1000]


class OwnerPersonalHistoryOfflineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "private"
        self.root.mkdir(mode=0o700)
        (self.root / "pages").mkdir(mode=0o700)
        self.conn = f("init_db")(self.root)
        self.addCleanup(self.conn.close)

    def test_fixed_20year_windows_are_consecutive_with_max_2000(self):
        windows = list(f("windows")())
        self.assertEqual(len(windows), 4)
        self.assertEqual(windows[0], (dt.date(2006,11,10),dt.date(2012,5,1)))
        self.assertEqual(windows[1][0],dt.date(2012,5,2))
        self.assertEqual(windows[2][1],dt.date(2023,4,14))
        self.assertEqual(windows[-1][1],dt.date(2026,11,10))
        self.assertTrue(all((end-start).days+1 <= 2000 for start,end in windows))
        for a,b in zip(windows,windows[1:]):
            self.assertEqual(a[1]+dt.timedelta(days=1),b[0])

    def test_validate_real_shaped_candles_and_no_historical_completeness_claim(self):
        start=dt.date(2006,11,10)
        rows,first,last=f("validate_rows")(
            {"status":True,"data":[candle()]},start,start+dt.timedelta(days=1))
        self.assertEqual(len(rows),1)
        self.assertEqual(first,start)
        self.assertEqual(last,start)
        self.assertEqual(f("validate_rows")(
            {"status":True,"data":[]},start,start)[0],[])

    def test_rejects_duplicate_out_of_order_bad_ohlc_and_outside_window(self):
        start=dt.date(2006,11,10)
        wrong=["2006-11-10T09:15:00+05:30",101,90,100,105,1000]
        for data in [
            [candle(),candle()], [candle("2006-11-11"),candle()],
            [wrong], [candle("2006-11-12")],
            [["2006-11-10T09:15:00",101,115,90,105,1000]],
            [["2006-11-10T09:15:00+05:30",101,115,90,105,-1]],
            [["2006-11-10T09:15:00+05:30",101,115,90,105,1.5]],
        ]:
            with self.subTest(data=data):
                with self.assertRaises(f("PersonalHistoryBlocked")):
                    f("validate_rows")({"status":True,"data":data},
                                       start,start+dt.timedelta(days=1))

    def test_store_is_atomic_hash_checked_root_private_and_resume_safe(self):
        date=dt.date(2006,11,10)
        digest=f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                                 date,date,[candle()],date,date)
        self.assertRegex(digest,r"^[a-f0-9]{64}$")
        archived=list((self.root/"pages").glob("*.json.gz"))
        self.assertEqual(len(archived),1)
        self.assertEqual(archived[0].stat().st_mode & 0o777,0o600)
        p=json.loads(gzip.decompress(archived[0].read_bytes()))
        self.assertEqual(p["rows"],[candle()])
        self.assertFalse(p["historical_listing_identity_verified"])
        self.assertFalse(p["session_completeness_verified"])
        self.assertEqual(
            f("existing_page")(self.conn,self.root,"2885",date,date),1)
        f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                          date,date,[candle()],date,date)
        self.assertEqual(
            self.conn.execute("SELECT count(*) FROM page").fetchone()[0],1)

    def test_corrupt_archived_file_is_rejected_not_re_downloaded_silently(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"TCS-EQ","11536",
                          date,date,[candle()],date,date)
        target=next((self.root/"pages").glob("*.json.gz"))
        target.write_bytes(b"modified")
        with self.assertRaises(f("PersonalHistoryBlocked")):
            f("existing_page")(self.conn,self.root,"11536",date,date)

    def test_empty_session_data_is_saved_but_not_fabricated(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"TCS-EQ","11536",
                          date,date,[],None,None)
        row=self.conn.execute("SELECT row_count, state, earliest, latest FROM page").fetchone()
        self.assertEqual(row,(0,"EMPTY",None,None))

    def test_readonly_private_status_audits_real_archive_without_market_calls(self):
        from contextlib import redirect_stdout
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                          date,date,[candle()],date,date)
        lock=self.root/".exclusive.lock"
        lock.touch(mode=0o600)
        lock.chmod(0o600)
        manifest=self.root/"manifest.sqlite3"
        before=manifest.stat().st_mtime_ns
        report=io.StringIO()
        blocked=lambda *args,**kwargs: (_ for _ in ()).throw(
            AssertionError("audit must never access provider or catalogue"))
        with patch.dict(NAMESPACE,{
                "authenticated_headers":blocked,
                "fetch":blocked,
                "select_catalogue_symbols":blocked,
                "ensure_private_store":blocked,
        }),redirect_stdout(report):
            result=f("audit_private_archive")(
                self.root,expected_owner=os.geteuid())
        self.assertEqual(result,(1,1))
        self.assertEqual(before,manifest.stat().st_mtime_ns)
        self.assertIn("PERSONAL_ARCHIVE_VERIFIED_ROWS: 1",report.getvalue())
        self.assertIn("PERSONAL_ARCHIVE_API_CALLS: 0",report.getvalue())
        self.assertIn("PERSONAL_ARCHIVE_AUDIT_STATUS: VERIFIED",report.getvalue())
        self.assertNotIn("09:15",report.getvalue())
        self.assertNotIn("2885",report.getvalue())

    def test_readonly_status_refuses_missing_lock_before_opening_archive(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"TCS-EQ","11536",
                          date,date,[candle()],date,date)
        with self.assertRaisesRegex(f("PersonalHistoryBlocked"),"UNTRUSTED_AUDIT_FILE"):
            f("audit_private_archive")(self.root,expected_owner=os.geteuid())

    def test_readonly_status_refuses_concurrent_writer_lock(self):
        import fcntl
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                          date,date,[candle()],date,date)
        lock=self.root/".exclusive.lock"
        with lock.open("w+") as writer:
            os.chmod(lock,0o600)
            fcntl.flock(writer.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
            with self.assertRaisesRegex(f("PersonalHistoryBlocked"),"ACTIVE_COLLECTOR"):
                f("audit_private_archive")(
                    self.root,expected_owner=os.geteuid())

    def test_readonly_status_refuses_public_permissions(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"INFY-EQ","1594",
                          date,date,[candle()],date,date)
        lock=self.root/".exclusive.lock"
        lock.touch(mode=0o600)
        lock.chmod(0o600)
        self.root.chmod(0o755)
        with self.assertRaisesRegex(f("PersonalHistoryBlocked"),"UNTRUSTED_AUDIT_DIRECTORY"):
            f("audit_private_archive")(
                self.root,expected_owner=os.geteuid())

    def test_readonly_status_rejects_modified_saved_candles(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"INFY-EQ","1594",
                          date,date,[candle()],date,date)
        lock=self.root/".exclusive.lock"
        lock.touch(mode=0o600)
        lock.chmod(0o600)
        target=next((self.root/"pages").glob("*.json.gz"))
        target.write_bytes(gzip.compress(b'{"rows":"tampered"}'))
        with self.assertRaises(f("PersonalHistoryBlocked")):
            f("audit_private_archive")(
                self.root,expected_owner=os.geteuid())

    def test_fixed_host_wrapper_offers_only_download_and_audit_only_modes(self):
        wrapper=(ROOT/"infra/ec2-paper/run_owner_personal_history.sh").read_text()
        self.assertIn('test "$#" -le 1',wrapper)
        self.assertIn('test "$mode" = download -o "$mode" = audit-only',wrapper)
        self.assertIn("['audit-only'] if mode=='audit-only' else []",wrapper)
        self.assertNotIn("placeOrder",wrapper)

    def test_archive_audit_verifies_saved_real_shaped_candles_without_network(self):
        a=dt.date(2006,11,10)
        b=dt.date(2006,11,11)
        f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                          a,a,[candle()],a,a)
        f("persist_page")(self.conn,self.root,"TCS-EQ","11536",
                          b,b,[],None,None)
        self.assertEqual(f("verify_archive_integrity")(self.conn,self.root),(2,1))

    def test_archive_audit_blocks_tampered_manifest_count(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                          date,date,[candle()],date,date)
        self.conn.execute("UPDATE page SET row_count=2")
        self.conn.commit()
        with self.assertRaisesRegex(f("PersonalHistoryBlocked"),
                                     "ARCHIVE_MANIFEST_MISMATCH"):
            f("verify_archive_integrity")(self.conn,self.root)

    def test_archive_audit_blocks_missing_page_and_modified_candle_bytes(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                          date,date,[candle()],date,date)
        target=next((self.root/"pages").glob("*.json.gz"))
        target.write_bytes(gzip.compress(b'{"data":"synthetic tampering"}'))
        with self.assertRaisesRegex(f("PersonalHistoryBlocked"),
                                     "ARCHIVE_CONTENT_HASH_MISMATCH"):
            f("verify_archive_integrity")(self.conn,self.root)
        target.unlink()
        with self.assertRaisesRegex(f("PersonalHistoryBlocked"),
                                     "ARCHIVE_FILE_NOT_PRIVATE"):
            f("verify_archive_integrity")(self.conn,self.root)

    def test_archive_audit_rejects_nonprivate_permissions(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                          date,date,[candle()],date,date)
        path=next((self.root/"pages").glob("*.json.gz"))
        path.chmod(0o644)
        with self.assertRaisesRegex(f("PersonalHistoryBlocked"),
                                     "ARCHIVE_FILE_NOT_PRIVATE"):
            f("verify_archive_integrity")(self.conn,self.root)

    def test_archive_audit_does_not_claim_vendor_session_completeness(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"INFY-EQ","1594",
                          date,date,[candle()],date,date)
        n,rows=f("verify_archive_integrity")(self.conn,self.root)
        self.assertEqual((n,rows),(1,1))
        file=next((self.root/"pages").glob("*.json.gz"))
        archive=json.loads(gzip.decompress(file.read_bytes()))
        self.assertIs(archive["historical_listing_identity_verified"],False)
        self.assertIs(archive["session_completeness_verified"],False)
        self.assertIs(archive["source_publication_times_verified"],False)

    def test_provider_request_keeps_token_secret_in_memory_not_file(self):
        class DummyResponse:
            status=200
            def __enter__(self): return self
            def __exit__(self,*args): return None
            def read(self,n): return b'{"status":true,"data":[["2006-11-10T09:15:00+05:30",101,115,90,105,1000]]}'
        seen=[]
        def fake(req,timeout):
            seen.append((req.data,req.headers,timeout))
            return DummyResponse()
        with patch.dict(NAMESPACE,{"urlopen":fake}):
            rows,earliest,latest=f("fetch")(
                {"Authorization":"Bearer do-not-log"},"2885",
                dt.date(2006,11,10),dt.date(2006,11,10))
        self.assertEqual(len(rows),1)
        self.assertEqual(earliest,dt.date(2006,11,10))
        self.assertEqual(json.loads(seen[0][0])["interval"],"ONE_DAY")
        self.assertNotIn(b"do-not-log",seen[0][0])
        self.assertEqual(seen[0][2],35)

    def test_provider_rate_limit_halts_without_unbounded_retry(self):
        with patch.dict(NAMESPACE,{"urlopen":lambda *args,**kwargs:
                        (_ for _ in ()).throw(urllib.error.HTTPError(
                            "https://invalid.example",429,"rate",{},None))}):
            with self.assertRaisesRegex(f("PersonalHistoryBlocked"),
                                        "RATE_LIMITED"):
                f("fetch")({},"2885",dt.date(2006,11,10),dt.date(2006,11,10))

    def test_current_nse_catalogue_is_verified_and_no_tokens_fabricated(self):
        class Proc:
            returncode=0
            stdout="RELIANCE-EQ|2885\nTCS-EQ|11536\nINFY-EQ|1594\n"
        with patch("subprocess.run",return_value=Proc()) as mock:
            values=f("select_catalogue_symbols")()
            self.assertEqual(values,[("INFY-EQ","1594"),
                                     ("RELIANCE-EQ","2885"),("TCS-EQ","11536")])
            self.assertIn("instrument_catalogue_state",mock.call_args[0][0][-1])
            self.assertIn("LIKE '%-EQ'",mock.call_args[0][0][-1])
        Proc.stdout="RELIANCE-EQ|2885\nRELIANCE-EQ|9999\n"
        with patch("subprocess.run",return_value=Proc()):
            with self.assertRaisesRegex(f("PersonalHistoryBlocked"),"AMBIGUOUS"):
                f("select_catalogue_symbols")()
        Proc.stdout="RELIANCE-EQ|2885\nTCS-EQ|2885\n"
        with patch("subprocess.run",return_value=Proc()):
            with self.assertRaisesRegex(f("PersonalHistoryBlocked"),"AMBIGUOUS"):
                f("select_catalogue_symbols")()

    def test_first_five_stay_prioritized_when_manifest_empty(self):
        catalogue=[("ABB-EQ","123"),("ASIANPAINT-EQ","456")] + [
            (sym,str(i+1000)) for i,sym in enumerate(f("PILOT"))]
        selected=f("select_next_batch")(self.conn,catalogue)
        self.assertEqual([sym for sym,_ in selected],list(f("PILOT")))

    def test_completed_first_five_advance_to_next_five_alphabetically(self):
        catalogue=[("AARTIIND-EQ","600"),("ABB-EQ","601"),
                   ("AMBUJACEM-EQ","602"),("APOLLOHOSP-EQ","603"),
                   ("ASIANPAINT-EQ","604")] + [
                   (sym,str(i+1000)) for i,sym in enumerate(f("PILOT"))]
        for sym,token in catalogue[-5:]:
            for first,through in f("windows")():
                f("persist_page")(self.conn,self.root,sym,token,
                                  first,through,[],None,None)
        selected=f("select_next_batch")(self.conn,catalogue)
        self.assertEqual([sym for sym,_ in selected],
                         ["AARTIIND-EQ","ABB-EQ","AMBUJACEM-EQ",
                          "APOLLOHOSP-EQ","ASIANPAINT-EQ"])
        self.assertEqual(len(selected),5)

    def test_partial_progress_gets_resumed_before_advance(self):
        catalogue=[("ABB-EQ","601"),("RELIANCE-EQ","2885")]
        dates=list(f("windows")())
        for first,through in dates[:-1]:
            f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                              first,through,[],None,None)
        selected=f("select_next_batch")(self.conn,catalogue)
        self.assertEqual(selected,[("RELIANCE-EQ","2885"),("ABB-EQ","601")])

    def test_failed_historical_range_skips_only_affected_symbol(self):
        now=dt.datetime.now(dt.timezone.utc)
        f("record_window_failure")(self.conn,"2885",
            dt.date(2006,11,10),dt.date(2012,5,1),
            "PROVIDER_REJECTED",now=now)
        current=[("RELIANCE-EQ","2885"),("ABB-EQ","601"),("TCS-EQ","11536")]
        selected=f("select_next_batch")(self.conn,current)
        self.assertNotIn(("RELIANCE-EQ","2885"),selected)
        self.assertEqual(selected,[("TCS-EQ","11536"),("ABB-EQ","601")])
        self.assertFalse(f("provider_cooldown_active")(self.conn,now))

    def test_quarantined_symbol_reenters_after_backoff(self):
        old=dt.datetime.now(dt.timezone.utc)-dt.timedelta(hours=10)
        first,through=list(f("windows")())[0]
        count=f("record_window_failure")(
            self.conn,"2885",first,through,"INVALID_PROVIDER_ROWS",now=old)
        self.assertEqual(count,1)
        selected=f("select_next_batch")(
            self.conn,[("RELIANCE-EQ","2885")])
        self.assertEqual(selected,[("RELIANCE-EQ","2885")])

    def test_exhausted_bad_symbol_never_blocks_other_equities(self):
        now=dt.datetime.now(dt.timezone.utc)
        first,through=list(f("windows")())[0]
        for attempt in range(7):
            count=f("record_window_failure")(
                self.conn,"2885",first,through,"PROVIDER_REJECTED",now=now)
        self.assertEqual(count,5)
        selected=f("select_next_batch")(
            self.conn,[("RELIANCE-EQ","2885"),("ABB-EQ","601")])
        self.assertEqual(selected,[("ABB-EQ","601")])
        failures=self.conn.execute(
            "SELECT attempts FROM page_failure").fetchone()[0]
        self.assertEqual(failures,5)

    def test_429_creates_provider_wide_pause_and_does_not_write_fake_zero_page(self):
        now=dt.datetime.now(dt.timezone.utc)
        start,end=list(f("windows")())[0]
        f("record_window_failure")(self.conn,"2885",start,end,
            "PROVIDER_RATE_LIMITED",now=now)
        self.assertTrue(f("provider_cooldown_active")(self.conn,now))
        self.assertFalse(f("provider_cooldown_active")(
            self.conn,now+dt.timedelta(hours=2)))
        self.assertEqual(
            self.conn.execute("SELECT count(*) FROM page").fetchone()[0],0)
        self.assertEqual(
            self.conn.execute("SELECT reason FROM page_failure").fetchone()[0],
            "PROVIDER_RATE_LIMITED")

    def test_unrecognized_failure_cannot_poison_retry_state(self):
        first,end=list(f("windows")())[0]
        with self.assertRaisesRegex(f("PersonalHistoryBlocked"),"UNRECOGNIZED"):
            f("record_window_failure")(self.conn,"2885",first,end,"random-secret")
        self.assertEqual(
            self.conn.execute("SELECT count(*) FROM page_failure").fetchone()[0],0)

    def test_progress_reports_only_counts_not_market_data_or_client_secrets(self):
        from contextlib import redirect_stdout
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"RELIANCE-EQ","2885",
                          date,date,[candle()],date,date)
        output=io.StringIO()
        with redirect_stdout(output):
            f("print_archive_progress")(self.conn)
        report=output.getvalue()
        self.assertIn("PERSONAL_ROWS_STORED_TOTAL: 1",report)
        self.assertIn("PERSONAL_PAGES_STORED_TOTAL: 1",report)
        self.assertIn("PERSONAL_FAILED_WINDOWS_TOTAL: 0",report)
        self.assertNotIn("09:15",report)
        self.assertNotIn("2885",report)

    def test_invalid_current_nse_catalogue_and_token_collisions_block(self):
        class Proc:
            returncode=0
            stdout="TCS-EQ|secret-value\n"
        with patch("subprocess.run",return_value=Proc()):
            with self.assertRaises(f("PersonalHistoryBlocked")):
                f("select_catalogue_symbols")()
        Proc.stdout="STOCK|1234\n"
        with patch("subprocess.run",return_value=Proc()):
            with self.assertRaises(f("PersonalHistoryBlocked")):
                f("select_catalogue_symbols")()

    def test_code_does_not_use_order_api_or_public_source_distribution(self):
        code=SOURCE.read_text()
        for banned in ("placeOrder", "modifyOrder", "cancelOrder",
                       "boto3", "github.com/api", "S3Client", "openUrl",
                       "redistribute(", "prints(raw)", "print(jwt)"):
            self.assertNotIn(banned,code)
        self.assertIn("PRIVATE_OWNER_PERSONAL_RESEARCH_ONLY",code)
        self.assertIn("PERSONAL_GOLDEN_SOURCE_PUBLISHED: FALSE",code)

    def test_private_root_encryption_configuration_and_no_unverified_extra_volumes(self):
        import re
        tf=(ROOT / "infra/ec2-paper/main.tf").read_text()
        match=re.search(r"root_block_device\s*\{([^{}]+)\}",tf)
        self.assertIsNotNone(match)
        self.assertRegex(match.group(1),r"\bencrypted\s*=\s*true\b")
        self.assertRegex(match.group(1),r"delete_on_termination\s*=\s*false")
        workflow=(ROOT / ".github/workflows/minttick-owner-private-candle-pilot.yml").read_text()
        self.assertIn("ec2 describe-volumes --volume-ids",workflow)
        self.assertIn('test "$count" -eq 1',workflow)
        self.assertIn('test "$encrypted" = "True"',workflow)
        self.assertIn("live AWS encryption status not independently",workflow)
        self.assertIn("ONLY",workflow.upper())

    def test_no_data_exposed_outside_storage_on_resume(self):
        date=dt.date(2006,11,10)
        f("persist_page")(self.conn,self.root,"INFY-EQ","1594",
                          date,date,[candle()],date,date)
        record=self.conn.execute("SELECT source_sha256,archive_relative_path FROM page").fetchone()
        self.assertEqual(len(record[0]),64)
        self.assertNotIn("101",repr(record))
        self.assertTrue((self.root/record[1]).is_file())


if __name__=="__main__":
    unittest.main()
