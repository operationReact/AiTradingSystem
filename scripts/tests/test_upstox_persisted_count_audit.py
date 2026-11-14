"""Offline security and truthful-progress tests for private UPSTOX SQL metadata audit."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] /
                       "infra" / "ec2-paper"))
from upstox_persisted_count_audit import (
    expected_request, safe_counts, sanitized_receipt, SQL,
    MARKER, COUNTS, DEPLOYED_SHA,
)

def sample(provider="EXTERNAL"):
    return {"providerState": provider, "jobs": 2, "chunks": 5,
            "archivedVerifiedChunks": 3, "retentionReceiptChunks": 1,
            "retryChunks": 1, "failedChunks": 1,
            "rawRetentionRightsMetadata": 2}

class UpstoxPersistedCountAuditTest(unittest.TestCase):
    def test_exact_reviewed_one_host_read_only_request(self):
        request = expected_request()
        self.assertEqual(request["backend_release"], DEPLOYED_SHA)
        self.assertEqual(request["instance_id"], "i-09c01b470fb39b85a")
        self.assertEqual(request["environment"], "PAPER")
        self.assertIn("READ_ONLY", request["authorization"])
        self.assertEqual(set(request), {
            "kind", "operation", "environment", "account", "region",
            "instance_id", "backend_release", "authorization"})

    def test_only_select_query_and_provider_scoped_rights_metadata(self):
        # SQL has no mutations; the production runtime cannot take an
        # interpolated symbol or vendor token from a request file.
        self.assertIn("WHERE provider_code = 'UPSTOX'", SQL)
        self.assertIn("SELECT json_build_object", SQL)
        self.assertIn("r.source_domain = j.source_domain", SQL)
        self.assertIn("r.permitted_use = 'RAW_RETENTION'", SQL)
        self.assertIn("r.valid_to > now()", SQL)
        self.assertNotIn("INSERT ", SQL.upper())
        self.assertNotIn("UPDATE ", SQL.upper())
        self.assertNotIn("DELETE ", SQL.upper())
        self.assertNotIn("DROP ", SQL.upper())

    def test_verified_checkpoint_observation_is_not_legal_entitlement(self):
        counts = safe_counts(sample())
        self.assertEqual(counts["jobs"], 2)
        self.assertEqual(counts["archivedVerifiedChunks"], 3)
        self.assertEqual(counts["retentionReceiptChunks"], 1)
        self.assertEqual(counts["rawRetentionRightsMetadata"], 2)
        self.assertEqual(set(counts), set(COUNTS) | {"providerState"})

    def test_missing_provider_requires_zero_backend_rows_but_means_unknown(self):
        missing = {key: 0 for key in COUNTS}
        missing["providerState"] = "MISSING"
        self.assertEqual(safe_counts(missing)["providerState"], "MISSING")
        missing["jobs"] = 1
        with self.assertRaisesRegex(ValueError, "MISSING_PROVIDER_WITH_DATA"):
            safe_counts(missing)

    def test_rejects_fake_success_or_negative_and_fractional_counts(self):
        for bad in (-1, 1.2, True, None, "5", 100000001):
            s = sample()
            s["jobs"] = bad
            with self.assertRaises(ValueError):
                safe_counts(s)
        for name in ("retentionReceiptChunks", "archivedVerifiedChunks"):
            s = sample()
            s[name] = 6
            with self.assertRaises(ValueError):
                safe_counts(s)
        for unwanted in ("sourceRightsVerified", "paperReady", "rawCandles",
                         "upstoxBearer", "nseUniverseTarget"):
            s = sample()
            s[unwanted] = True
            with self.assertRaises(ValueError):
                safe_counts(s)
        s = sample("APPROVED")
        with self.assertRaises(ValueError):
            safe_counts(s)

    def test_only_exact_single_sanitized_receipt_is_accepted(self):
        sample_line = MARKER + json.dumps(sample(), separators=(",", ":"))
        parsed = sanitized_receipt("untrusted Bearer SECRET\n" + sample_line + "\n")
        self.assertEqual(parsed["jobs"], 2)
        self.assertNotIn("SECRET", json.dumps(parsed))
        for invalid in (
            sample_line + "\n" + sample_line,
            sample_line.replace('"jobs":2', '"jobs":-1'),
            sample_line.replace('"jobs":2', '"jobs":false'),
            sample_line.replace('"providerState":"EXTERNAL"',
                                '"providerState":"SYNTHETIC_SPOOF"'),
            "MINTTICK_UPSTOX_PERSISTED:" + '{"bearer":"secret"}',
            sample_line * 2000,
            "",
        ):
            with self.assertRaises((ValueError, json.JSONDecodeError)):
                sanitized_receipt(invalid)

    def test_missing_rights_metadata_is_zero_not_permission_approval(self):
        s = sample()
        s["rawRetentionRightsMetadata"] = 0
        s["retentionReceiptChunks"] = 0
        self.assertEqual(safe_counts(s)["retentionReceiptChunks"], 0)
        self.assertEqual(safe_counts(s)["archivedVerifiedChunks"], 3)

if __name__ == "__main__":
    unittest.main()
