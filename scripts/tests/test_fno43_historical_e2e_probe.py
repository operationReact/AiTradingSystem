"""Synthetic fixtures test a READ-ONLY inventory, never actual market E2E."""
from __future__ import annotations

from datetime import datetime, timezone
import importlib.util
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[2]
PROBE = HERE / "infra/ec2-paper/fno43_historical_e2e_probe.py"
CANON = HERE / "infra/ec2-paper/fno_private_canonical_store.py"
spec = importlib.util.spec_from_file_location("fno43probe", PROBE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
spec2 = importlib.util.spec_from_file_location("fno_canonical_store", CANON)
canonical = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(canonical)
from test_fno_private_canonical_store import make_rows, NOW

class PrivateHistoricalProofGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        os.chmod(self.root, 0o700)

    def persist_fixture(self):
        source = sqlite3.connect(":memory:")
        try:
            source.execute("CREATE TABLE fno_history_receipts(committed_at REAL)")
            source.executemany("INSERT INTO fno_history_receipts VALUES (?)",
                               [(NOW - 3600,)] * 276)
            result = canonical.store(make_rows(), canonical.EXPECTED, source,
                                     root=self.root, now=NOW)
            self.assertEqual(3929, result["total"])
        finally:
            source.close()
        return self.root / canonical.TARGET.name

    def test_no_cache_reports_all_43_blockers_never_proves_history(self):
        report = p.inspect(root=self.root, now=NOW)
        p._checked_report(report)
        self.assertEqual("PRIVATE_CACHE_UNAVAILABLE_OR_UNVERIFIED", report["status"])
        self.assertIsNone(report["canonicalDailyRows"])
        self.assertEqual(0, report["historicalE2EValidated"])
        self.assertFalse(report["backtestingApproved"])
        self.assertEqual(43, len(report["strategies"]))
        self.assertEqual(
            [f"strategy.STR-{n}" for n in range(170, 213)],
            [row["strategyId"] for row in report["strategies"]],
        )
        self.assertTrue(all("ORIGINAL_FIRST_KNOWN_AT_MISSING" in row["missing"]
                            for row in report["strategies"]))
        self.assertTrue(all(row["state"] == "BLOCKED_NOT_HISTORICAL_E2E"
                            for row in report["strategies"]))

    def test_valid_synthetic_canonical_cache_quality_is_not_replay_proof(self):
        self.persist_fixture()
        report = p.inspect(root=self.root, now=NOW)
        p._checked_report(report)
        self.assertEqual("PRIVATE_DAILY_DATA_AUDITED_E2E_BLOCKED", report["status"])
        self.assertEqual("VALIDATED_PRIVATE_DAILY_ONLY", report["cacheIntegrity"])
        self.assertEqual(3929, report["canonicalDailyRows"])
        self.assertEqual(276, report["uniqueContractKeys"])
        self.assertEqual(3608, report["sourceCounts"]["UPSTOX_PRIVATE"])
        self.assertEqual(321, report["sourceCounts"]["NSE_PUBLIC_EOD"])
        self.assertEqual(0, report["historicalE2EValidated"])
        self.assertEqual(0, report["paperE2EValidated"])
        self.assertEqual(0, report["liveE2EValidated"])
        self.assertFalse(report["originalFirstKnownObserved"])
        self.assertFalse(report["independentSourceRightsVerified"])
        self.assertFalse(report["backtestingApproved"])
        self.assertFalse(report["orderRoutesCalled"])
        self.assertEqual(11, sum(x["family"] == "FUTURES"
                                 for x in report["strategies"]))
        self.assertEqual(11, sum(x["family"] == "OPTIONS_DIRECTIONAL"
                                 for x in report["strategies"]))
        self.assertEqual(12, sum(x["family"] == "OPTIONS_VOLATILITY"
                                 for x in report["strategies"]))
        self.assertEqual(9, sum(x["family"] == "OPTIONS_HEDGING"
                                for x in report["strategies"]))

    def test_cache_expires_with_original_receipts_and_never_rolls_forward(self):
        self.persist_fixture()
        at = NOW - 3600 + canonical.LIFETIME
        self.assertEqual("PRIVATE_CACHE_UNAVAILABLE_OR_UNVERIFIED",
                         p.inspect(root=self.root, now=at)["status"])

    def test_modified_ohlc_and_malformed_provenance_are_not_accepted(self):
        path = self.persist_fixture()
        with sqlite3.connect(path) as db:
            key, day = db.execute("SELECT contract_key,session_day FROM daily LIMIT 1").fetchone()
            db.execute("UPDATE daily SET high_price='0.001' "
                       "WHERE contract_key=? AND session_day=?", (key, day))
        self.assertEqual("PRIVATE_CACHE_UNAVAILABLE_OR_UNVERIFIED",
                         p.inspect(root=self.root, now=NOW)["status"])
        with sqlite3.connect(path) as db:
            db.execute("UPDATE daily SET high_price='106', "
                       "candle_ts='2025-01-01T09:15:00+05:30' "
                       "WHERE contract_key=? AND session_day=?", (key, day))
        self.assertEqual("PRIVATE_CACHE_UNAVAILABLE_OR_UNVERIFIED",
                         p.inspect(root=self.root, now=NOW)["status"])

    def test_private_directory_file_symlinks_and_permissions_required(self):
        self.persist_fixture()
        file = self.root / p.FILE
        file.chmod(0o644)
        self.assertEqual("PRIVATE_CACHE_UNAVAILABLE_OR_UNVERIFIED",
                         p.inspect(root=self.root, now=NOW)["status"])
        file.chmod(0o600)
        backup = self.root / "backup.sqlite"
        file.rename(backup)
        file.symlink_to(backup)
        self.assertEqual("PRIVATE_CACHE_UNAVAILABLE_OR_UNVERIFIED",
                         p.inspect(root=self.root, now=NOW)["status"])

    def test_forged_report_or_extra_strategy_cannot_raise_43_e2e_counter(self):
        report = p.inspect(root=self.root, now=NOW)
        report["historicalE2EValidated"] = 43
        with self.assertRaisesRegex(ValueError, "RECEIPT_NOT_TRUSTED"):
            p._checked_report(report)
        report["historicalE2EValidated"] = 0
        report["strategies"][0]["state"] = "VERIFIED"
        with self.assertRaisesRegex(ValueError, "UNVERIFIED_E2E_PROMOTION"):
            p._checked_report(report)
        report["strategies"][0]["state"] = "BLOCKED_NOT_HISTORICAL_E2E"
        report["strategies"][0]["strategyId"] = "strategy.STR-171"
        with self.assertRaisesRegex(ValueError, "RECEIPT_STRATEGIES_NOT_43"):
            p._checked_report(report)

    def test_uses_no_provider_or_order_access_for_host_evidence(self):
        src = PROBE.read_text()
        self.assertIn("PRAGMA query_only=ON", src)
        self.assertIn("?mode=ro", src)
        self.assertIn("SOURCE_RESEARCH_RIGHTS_NOT_ATTESTED", src)
        self.assertIn("ORIGINAL_FIRST_KNOWN_AT_MISSING", src)
        self.assertNotIn("submitOrder", src)
        self.assertNotIn("placeOrder", src)
        self.assertNotIn("--with-decryption", src)
        self.assertNotIn("put-parameter", src)
        self.assertNotIn("backtestingApproved" + chr(34) + ": True", src)

if __name__ == "__main__":
    unittest.main()
