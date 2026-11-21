"""Contract tests for the STRICTLY RESEARCH-ONLY 43-strategy audit CLI."""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1]
ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))
import fno_strategy_e2e_audit as audit


class Fno43StrategyResearchAuditTest(unittest.TestCase):
    def test_exact_contiguous_real_manifest_43_and_never_promote_data_readiness(self):
        manifest, items, issues = audit.manifest_items(ROOT)
        self.assertEqual([], issues)
        self.assertEqual(43, len(items))
        self.assertEqual(
            [f"strategy.STR-{i}" for i in range(170, 213)],
            [row["id"] for row in items],
        )
        self.assertEqual(232, manifest["denominators"]["STRATEGY"])
        result = audit.inspect(ROOT, None)
        self.assertEqual([], result["errors"])
        summary = result["summary"]
        self.assertEqual(43, summary["strategies"])
        self.assertEqual(43, summary["implemented"])
        self.assertEqual(0, summary["junitEvidenceComplete"])
        self.assertEqual(0, summary["realDataE2EValidated"])
        self.assertEqual(0, summary["paperTradingValidated"])
        self.assertEqual(0, summary["liveTradingValidated"])
        self.assertTrue(all(not r["paperExecutionReady"]
                            and not r["liveExecutionReady"]
                            and not r["strategyEndToEndReady"]
                            for r in result["strategies"]))
        self.assertTrue(all(r["manifestStatus"] == "DATA_BLOCKED"
                            for r in result["strategies"]))
        self.assertFalse(result["sourceMarketDataFetched"])
        self.assertFalse(result["brokerOrdersPlaced"])

    def test_missing_surefire_is_a_failure_when_user_requests_executed_proof(self):
        result = audit.inspect(ROOT, ROOT / "nonexistent-surefire-reports",
                               require_surefire=True)
        self.assertEqual(43, result["summary"]["strategies"])
        self.assertEqual(43, len(
            [s for s in result["strategies"]
             if "JUNIT_SUREFIRE_EXECUTION_PROOF_MISSING" in s["blockingIssues"]])
        self.assertEqual(0, result["summary"]["junitEvidenceComplete"])

    def test_surefire_needs_genuine_testcases_not_only_test_count(self):
        name = "FnoLongCallV1Test"
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            target = directory / ("TEST-in.aitradingsystem.tradingrooms." + name + ".xml")
            target.write_text(
                '<testsuite name="in.aitradingsystem.tradingrooms.FnoLongCallV1Test" '
                'tests="2" failures="0" errors="0" skipped="0">'
                '<testcase classname="in.aitradingsystem.tradingrooms.FnoLongCallV1Test" name="a"/>'
                '<testcase classname="in.aitradingsystem.tradingrooms.FnoLongCallV1Test" name="b"/>'
                '</testsuite>', encoding="utf-8")
            self.assertEqual((2, 0, 0, 0), audit._suite_for_test(directory, name))
            target.write_text(
                '<testsuite name="in.aitradingsystem.tradingrooms.FnoLongCallV1Test" '
                'tests="2" failures="0" errors="0" skipped="0">'
                '<testcase name="a"/></testsuite>', encoding="utf-8")
            self.assertIsNone(audit._suite_for_test(directory, name))

    def test_order_safety_methods_reject_true_and_missing(self):
        source = "public boolean executable() {return false;} public boolean orderPlaced(){return true;}"
        self.assertEqual("FALSE", audit._method_value(source, "executable"))
        self.assertEqual("TRUE", audit._method_value(source, "orderPlaced"))
        self.assertEqual("MISSING", audit._method_value(source, "liveTradeAuthorized"))

    def test_list_tests_is_exact_43_unique_java_classes(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            exit_code = audit.main(["--repo-root", str(ROOT), "--list-tests"])
        self.assertEqual(0, exit_code)
        tests = output.getvalue().strip().split(",")
        self.assertEqual(43, len(tests))
        self.assertEqual(43, len(set(tests)))
        self.assertIn("FnoSkewBasedHedgeV1Test", tests)
        self.assertIn("FnoFuturesBasisCarryV1Test", tests)

    def test_unrelated_test_suite_cannot_masquerade_as_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            report = path / "TEST-in.aitradingsystem.tradingrooms.FnoLongCallV1Test.xml"
            report.write_text(
                '<testsuite name="in.aitradingsystem.tradingrooms.FnoLongPutV1Test" '
                'tests="1" failures="0" errors="0" skipped="0"><testcase name="x"/></testsuite>',
                encoding="utf-8")
            self.assertIsNone(audit._suite_for_test(path, "FnoLongCallV1Test"))

    def test_no_external_network_and_no_order_path_invocation(self):
        source = (SCRIPT_DIR / "fno_strategy_e2e_audit.py").read_text(encoding="utf-8")
        for banned in ("import requests", "import boto3", "subprocess.run(",
                       "placeOrder(", "submitOrder(", "aws ssm", "get-parameter"):
            self.assertNotIn(banned, source)


if __name__ == "__main__":
    unittest.main()
