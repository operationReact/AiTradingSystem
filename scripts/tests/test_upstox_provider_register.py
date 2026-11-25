"""Offline, no-cloud security test for explicit UPSTOX metadata-only registration."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
from upstox_provider_register import (
    expected_request, sanitize, extract_receipt, LEGAL_NAME, EXPECTED_RELEASE, SQL,
    MARKER,
)

def good():
    return {"providerCode": "UPSTOX",
            "legalName": LEGAL_NAME,
            "sourceKind": "EXTERNAL",
            "rightsRows": 0,
            "jobs": 0}

class UpstoxProviderIdentityTest(unittest.TestCase):
    def test_owner_request_is_pinned_and_metadata_only(self):
        v=expected_request()
        self.assertEqual(v["backend_release"], EXPECTED_RELEASE)
        self.assertEqual(v["instance_id"], "i-09c01b470fb39b85a")
        self.assertEqual(v["provider_code"], "UPSTOX")
        self.assertEqual(v["legal_name"], LEGAL_NAME)
        self.assertEqual(v["operation"], "register-upstox-metadata-only-no-rights")
        self.assertIn("OWNER_REQUESTED", v["authorization"])

    def test_static_sql_can_only_insert_upstox_external_identity(self):
        self.assertIn("INSERT INTO golden_data_provider", SQL)
        self.assertIn("'UPSTOX'", SQL)
        self.assertIn("'EXTERNAL'", SQL)
        self.assertIn("ON CONFLICT (provider_code) DO NOTHING", SQL)
        self.assertEqual(SQL.upper().count("INSERT INTO "), 1)
        for table in ("golden_source_rights_receipt",
                      "golden_dataset_backfill_job"):
            self.assertNotIn("INSERT INTO " + table, SQL)
            self.assertIn(table, SQL)
        for verb in ("UPDATE ", "DELETE ", "TRUNCATE ", "DROP ",
                     "ALTER ", "GRANT ", "REVOKE "):
            self.assertNotIn(verb, SQL.upper())

    def test_existing_or_new_metadata_readback_is_idempotent(self):
        self.assertEqual(sanitize(good())["providerCode"], "UPSTOX")
        s=good()
        s["rightsRows"]=3
        s["jobs"]=1
        self.assertEqual(sanitize(s)["rightsRows"], 3)
        self.assertEqual(sanitize(s)["jobs"], 1)

    def test_never_accepts_wrong_identity_or_forged_rights_claim(self):
        for key,value in (
            ("providerCode", "ANGEL_ONE"),
            ("legalName", "Unknown Company"),
            ("sourceKind", "SYNTHETIC_TEST"),
            ("sourceKind", "VERIFIED"),
            ("rightsRows", True),
            ("rightsRows", -1),
            ("jobs", 1.5),
            ("jobs", "0"),
            ("jobs", 100000001),
        ):
            v=good()
            v[key]=value
            with self.assertRaises(ValueError):
                sanitize(v)
        v=good()
        v["dataRightsGranted"]=True
        with self.assertRaises(ValueError):
            sanitize(v)

    def test_remote_receipt_must_be_exact_single_sanitized_record(self):
        line=MARKER + json.dumps(good(), separators=(",", ":"))
        safe=extract_receipt("untrusted Bearer secret\n"+line+"\n")
        self.assertEqual(safe, good())
        self.assertNotIn("secret", json.dumps(safe))
        for invalid in (line+"\n"+line,
                        line.replace('"jobs":0','"jobs":true'),
                        line.replace('"providerCode":"UPSTOX"',
                                     '"providerCode":"OTHER"'),
                        "MINTTICK_UPSTOX_PROVIDER:{\"bearer\":\"secret\"}",
                        "",line * 1000):
            with self.assertRaises((ValueError, json.JSONDecodeError)):
                extract_receipt(invalid)

if __name__=="__main__":
    unittest.main()
