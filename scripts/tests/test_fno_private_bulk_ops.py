"""Offline tests for exact-host private F&O backfill bootstrap. No AWS/Upstox."""
from pathlib import Path
import base64
import gzip
import hashlib
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"infra"/"ec2-paper"))
import fno_private_bulk_ops as ops

class BootstrapContractTest(unittest.TestCase):
    def test_fixed_owner_scope_and_explicit_not_a_provider_license(self):
        request=ops.expected_request()
        self.assertEqual(request["account"],"098859530205")
        self.assertEqual(request["instance_id"],"i-09c01b470fb39b85a")
        self.assertEqual(request["max_provider_gets_per_run"],600)
        self.assertEqual(request["max_months"],12)
        self.assertEqual(request["private_cache_days"],7)
        self.assertIs(request["provider_licence_verified"],False)
        self.assertIs(request["redistribution"],False)
        self.assertIs(request["live_orders"],False)
        self.assertEqual(request["interval"],"day")
    def test_receipt_accepts_only_fixed_counts_and_status(self):
        line=ops.receipt("STARTED",3,70,12)
        self.assertEqual(ops.parse_receipt(line),("STARTED",3,70,12))
        self.assertIsNone(ops.parse_receipt(line+"\n"+line))
        self.assertIsNone(ops.parse_receipt(line+" token-value"))
        self.assertIsNone(ops.parse_receipt(ops.MARKER+"FAKE|0|0|0"))
        with self.assertRaises(ValueError):
            ops.receipt("STARTED",12,3,4)
    def test_smoke_failure_receipt_is_allowlisted_and_redacted(self):
        self.assertEqual("UPSTOX_TOKEN_UNAVAILABLE",
            ops.smoke_failure("FNO_BULK_ERROR:UPSTOX_TOKEN_UNAVAILABLE\n"))
        self.assertEqual("UNCLASSIFIED_SAFE_FAILURE",
            ops.smoke_failure("FNO_BULK_ERROR:/private/token/value\n"))
        self.assertEqual("UNCLASSIFIED_SAFE_FAILURE",
            ops.smoke_failure("FNO_BULK_ERROR:UPSTOX_TOKEN_UNAVAILABLE\n"
                              "FNO_BULK_ERROR:PRIVATE_DB_ERROR\n"))
        self.assertEqual("UPSTOX_TOKEN_UNAVAILABLE",ops.parse_smoke_failure(
            "FNO_BULK_SMOKE_FAILURE:UPSTOX_TOKEN_UNAVAILABLE\n"))
        self.assertEqual("UNCLASSIFIED_SAFE_FAILURE",ops.parse_smoke_failure(
            "FNO_BULK_SMOKE_FAILURE:/private/token\n"))
        self.assertEqual("SMOKE_NO_VALID_ROWS",ops.smoke_failure(
            "FNO_BULK_SMOKE:NO_VALID_ROWS;complete=0;total=0;rows=0;calls=7\n"))
        self.assertEqual("WORKER_DEPENDENCY_UNAVAILABLE",ops.smoke_failure(
            "", "Traceback (most recent call last):\nModuleNotFoundError: /private/path/token"))
        self.assertEqual("WORKER_SOURCE_INCOMPATIBLE",ops.smoke_failure(
            "", "SyntaxError: /private/path/secret.py"))
        self.assertEqual("WORKER_RUNTIME_EXCEPTION",ops.smoke_failure(
            "", "Traceback (most recent call last):\nRuntimeError: private payload"))
        self.assertEqual("UPSTOX_TOKEN_UNAVAILABLE",ops.smoke_failure(
            "x"*5000+"\nFNO_BULK_ERROR:UPSTOX_TOKEN_UNAVAILABLE\n"))
    def test_runner_pins_exact_transitive_worker_modules(self):
        self.assertEqual(ops.PINNED_MODULES,
            ("fno_history_backfill.py", "upstox_fno_backfill_adapters.py"))
        for name in ops.PINNED_MODULES:
            digest,encoded=ops.package_source(name)
            source=gzip.decompress(base64.b64decode(encoded))
            self.assertEqual(hashlib.sha256(source).hexdigest(),digest)
            self.assertIn("sha256sum -c -",ops.stage_source_command(name,digest,encoded))
            self.assertIn("mv -f",ops.stage_source_command(name,digest,encoded))
        with self.assertRaises(ValueError):
            ops.package_source("../../runtime.env")
        with self.assertRaises(ValueError):
            ops.stage_source_command("../../runtime.env","0"*64,"YWJj")
    def test_single_systemd_unit_is_not_broker_order_service(self):
        unit=ops.unit_text()
        self.assertIn("ExecStart=/usr/bin/python3",unit)
        self.assertIn("fno_private_bulk_worker.py run",unit)
        self.assertIn("Restart=on-failure",unit)
        self.assertIn("ReadWritePaths=/opt/aitradingsystem-paper/fno-history",unit)
        self.assertNotIn("placeOrder",unit)
        self.assertNotIn("ExecStart=/bin/bash",unit)
    def test_filesystem_operations_not_performed_during_import(self):
        self.assertEqual(ops._installed_worker_ok(),False if not ops.WORKER.exists() else True)
        self.assertEqual(ops.MARKER,"MINTTICK_FNO_PRIVATE_BULK_START_V1:")

if __name__=="__main__":
    unittest.main()
