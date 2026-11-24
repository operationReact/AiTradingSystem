"""Offline audit of exact-host V102 read-only SQL, fail-closed receipts and limits.
Fixtures never access a real provider, AWS, PostgreSQL or PAPER/LIVE orders.
"""
from pathlib import Path
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[2]/"infra/ec2-paper/str176_v102_source_probe.py"
SPEC=importlib.util.spec_from_file_location("str176_v102_source_probe",SCRIPT)
probe=importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name]=probe
SPEC.loader.exec_module(probe)

class OriginalV102HostSourceTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
        (self.root/".env").write_text("private-for-fixture-only")
        (self.root/".env").chmod(0o600)
        (self.root/"paper-compose.yml").write_text("services: {}")
    def tearDown(self):
        self.tmp.cleanup()

    def test_source_query_is_actual_V102_readonly_and_bounds_time_rights(self):
        seen=[]
        def runner(args,**kwargs):
            seen.append((args,kwargs))
            return subprocess.CompletedProcess(args,0,"100:21:2\n","")
        self.assertEqual(probe.query_counts(run=runner,root=self.root),(100,21,2))
        args,settings=seen[0]
        self.assertEqual(args[:3],["docker","compose","--env-file"])
        self.assertEqual(args[args.index("exec")+1:args.index("psql")],["-T","postgres"])
        self.assertIn("ON_ERROR_STOP=1",args)
        self.assertEqual(settings["timeout"],30)
        self.assertFalse(settings["check"])
        self.assertTrue(settings["capture_output"])
        sql=args[-1]
        self.assertIn("BEGIN READ ONLY",sql)
        self.assertIn("SET LOCAL TIME ZONE 'Asia/Kolkata'",sql)
        for mandatory in ("fno_verified_completed_ohlcv_oi_session",
                          "p.source_kind = 'EXTERNAL'",
                          "h.permitted_use = 'MODEL_INFERENCE'",
                          "oi.permitted_use = 'MODEL_INFERENCE'",
                          "h.receipt_status = 'VERIFIED'",
                          "oi.receipt_status = 'VERIFIED'",
                          "cal.session_status = 'TRADING'",
                          "s.captured_at <= NOW()",
                          "s.ohlcv_first_available_at <= NOW()",
                          "s.oi_first_available_at <= NOW()",
                          "h.verified_at <= NOW()",
                          "oi.verified_at <= NOW()",
                          "s.retain_until >= CURRENT_DATE",
                          "HAVING COUNT(DISTINCT session_date) >= 7"):
            self.assertIn(mandatory,sql)
        for forbidden in ("UPDATE ","INSERT INTO","DELETE FROM","DROP TABLE",
                          "placeOrder","submitOrder"):
            self.assertNotIn(forbidden,sql.upper() if forbidden.isupper() else sql)

    def test_missing_private_file_and_unsafe_permissions_fail_without_query(self):
        calls=[]
        def runner(*args,**kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args,0,"0:0:0\n","")
        (self.root/".env").chmod(0o644)
        with self.assertRaisesRegex(ValueError,"EXACT_PRIVATE_RUNTIME_MISSING"):
            probe.query_counts(run=runner,root=self.root)
        self.assertFalse(calls)

    def test_error_nonaggregate_and_contradictory_counts_fail_closed(self):
        for code,data in ((1,"0:0:0\n"),(0,"private\n"),(0,"4:8:1\n"),
                          (0,"100:5:1\n"),(0,"100:21:2\nother\n")):
            with self.subTest(code=code,data=data):
                def runner(args,**kwargs):
                    return subprocess.CompletedProcess(args,code,data,"secret stderr")
                with self.assertRaises(ValueError):
                    probe.query_counts(run=runner,root=self.root)

    def test_receipt_has_only_counts_and_never_certifies(self):
        ok=probe.expected_receipt("ELIGIBILITY_MEASURED_NOT_CERTIFIED",(100,21,2))
        self.assertEqual(ok["independentHistoricalCertified"],0)
        self.assertFalse(ok["brokerOrders"])
        self.assertFalse(ok["writes"])
        self.assertNotIn("contractKey",ok)
        line=probe.MARKER+json.dumps(ok)
        self.assertEqual(probe.parse_host_receipt(line),ok)
        self.assertEqual(probe.parse_host_receipt("other\n"+line),ok)
        absent=probe.expected_receipt("SOURCE_READ_UNAVAILABLE")
        self.assertIsNone(absent["v102Rows"])
        self.assertIsNone(absent["rightsJoinedRows"])
        self.assertEqual(probe.parse_host_receipt(probe.MARKER+json.dumps(absent)),absent)
        with self.assertRaises(ValueError):
            probe.parse_host_receipt(line+"\n"+line)
        with self.assertRaises(ValueError):
            probe.parse_host_receipt(line.replace('"writes": false','"writes": true'))
        with self.assertRaises(ValueError):
            probe.parse_host_receipt(line+"a"*2500)
        with self.assertRaises(ValueError):
            probe.expected_receipt("ELIGIBILITY_MEASURED_NOT_CERTIFIED",(1,2,3))

    def test_exact_host_request_never_includes_broker_or_order_permission(self):
        self.assertEqual(probe.EXACT["account"],"098859530205")
        self.assertEqual(probe.EXACT["region"],"ap-south-1")
        self.assertEqual(probe.EXACT["instance_id"],"i-09c01b470fb39b85a")
        self.assertEqual(probe.EXACT["no_order_calls"],True)
        self.assertEqual(probe.EXACT["no_db_or_rights_writes"],True)

if __name__=="__main__":
    unittest.main()
