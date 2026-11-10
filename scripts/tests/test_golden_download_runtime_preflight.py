"""Synthetic offline tests for the credential-free, read-only Golden Source EC2 audit."""
import importlib.util
import pathlib
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
PATH = ROOT / "infra/ec2-paper/golden_download_runtime_preflight.py"
spec = importlib.util.spec_from_file_location("golden_download_runtime_preflight", PATH)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class GoldenRuntimePreflightTest(unittest.TestCase):
    def test_missing_postgres_yields_explicit_unavailability(self):
        with patch.object(audit, "db_container", return_value=None):
            self.assertEqual(audit.inspect(), {"DATABASE":"UNAVAILABLE"})

    def test_registered_rights_and_existing_tables_are_only_counts(self):
        calls = []
        def scalar(container, sql):
            calls.append(sql)
            if "pg_class" in sql:
                return 1
            if "COUNT" in sql.upper() or "count" in sql:
                return 0
            return None
        with patch.object(audit, "db_container", return_value="a"*12), patch.object(
            audit, "scalar", side_effect=scalar
        ):
            result = audit.inspect()
        self.assertEqual(result["DATABASE"], "AVAILABLE")
        self.assertEqual(result["COUNT_APPROVED_RETENTION_PROVIDERS"], "0")
        self.assertEqual(result["COUNT_APPROVED_RETENTION_AND_TRAINING_PROVIDERS"], "0")
        self.assertEqual(result["COUNT_LISTING_IDENTITIES"], "0")
        self.assertEqual(result["COUNT_HISTORICAL_CALENDAR"], "0")
        self.assertEqual(result["COUNT_VENDOR_PAGE_PLAN"], "0")
        self.assertEqual(result["BULK_DOWNLOAD_EXECUTED"], "NO")
        self.assertEqual(result["LEGAL_AGREEMENT_INDEPENDENTLY_VERIFIED"], "NOT_DETERMINED")
        self.assertTrue(all(q.lstrip().upper().startswith("SELECT") for q in calls))

    def test_missing_schema_is_not_called_or_assumed_empty(self):
        with patch.object(audit, "db_container", return_value="b"*12), patch.object(
            audit, "scalar", side_effect=lambda container,sql:
                0 if "pg_class" in sql else 123
        ) as scalar:
            result=audit.inspect()
        self.assertEqual(result["TABLE_SOURCE_RIGHTS"], "MISSING_OR_UNAVAILABLE")
        self.assertEqual(result["COUNT_APPROVED_RETENTION_PROVIDERS"], "UNAVAILABLE")
        self.assertEqual(result["COUNT_SOURCE_PROVIDERS"], "UNAVAILABLE")
        self.assertEqual(result["BULK_DOWNLOAD_EXECUTED"], "NO")
        self.assertEqual(scalar.call_count,len(audit.TABLES))

    def test_rejects_sql_mutations_even_in_internal_code(self):
        with self.assertRaisesRegex(ValueError,"read-only"):
            audit.scalar("a"*12,"UPDATE golden_data_provider SET x=1")
        with self.assertRaisesRegex(ValueError,"read-only"):
            audit.scalar("a"*12,"SELECT * FROM x; DELETE FROM y")

    def test_strict_db_container_id_and_numeric_count(self):
        with patch.object(audit,"command",return_value="untrusted"):
            self.assertIsNone(audit.db_container())
        with patch.object(audit,"command",return_value="42"):
            self.assertEqual(audit.scalar("a"*12,"SELECT count(*) FROM x"),42)
        with patch.object(audit,"command",return_value="secret=42"):
            self.assertIsNone(audit.scalar("a"*12,"SELECT count(*) FROM x"))

    def test_source_code_has_no_provider_or_storage_calls(self):
        source=PATH.read_text()
        for forbidden in ("getCandleData", "loginByPassword", "aws s3",
                          "boto3", "put_object", "INSERT INTO", "DELETE FROM",
                          "placeOrder", "GetObject", "SSM_ACCESS_KEY", "print(params)"):
            self.assertNotIn(forbidden, source)
        self.assertIn('BULK_DOWNLOAD_EXECUTED"] = "NO"',source)


if __name__ == "__main__":
    unittest.main()
