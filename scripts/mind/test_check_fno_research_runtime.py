"""No-network unit tests for sanitized customer F&O runtime evidence."""
from __future__ import annotations

import contextlib
from datetime import datetime, timedelta, timezone
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("check_fno_research_runtime", HERE / "check_fno_research_runtime.py")
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
NOW = datetime(2026, 11, 23, 16, 10, tzinfo=timezone.utc)


def payload(**changes):
    default = {
        "contractVersion": "mind.fno-research.v1",
        "evaluatedAt": (NOW - timedelta(seconds=2)).isoformat(),
        "underlyingKey": "NSE_INDEX|Nifty 50",
        "status": "NO_TRADE",
        "signal": "NO_TRADE",
        "reasonCode": "MARKET_CLOSED",
        "strategyId": None,
        "completedCandles": 0,
        "executable": False,
        "orderPlaced": False,
        "instrumentKey": "NSE_FO|DO_NOT_LOG",
        "personalAccountData": "SECRET_SHOULD_NEVER_LOG",
    }
    default.update(changes)
    return json.dumps(default).encode()


class FnoResearchRuntimeTests(unittest.TestCase):
    def test_valid_market_closed_response_is_not_execution_evidence(self):
        text, valid = audit.classify(200, payload(), NOW)
        self.assertTrue(valid)
        self.assertIn("state=NO_TRADE;reason=MARKET_CLOSED", text)
        self.assertIn("independentE2E=false", text)
        self.assertNotIn("NSE_FO", text)
        self.assertNotIn("SECRET", text)

    def test_deployed_route_and_auth_are_distinct(self):
        for code, expected, valid in [
            (401, "AUTH_DENIED", True), (403, "AUTH_DENIED", True),
            (404, "NOT_DEPLOYED", True), (500, "BACKEND_ERROR", False),
            (502, "BACKEND_ERROR", False), (429, "UNEXPECTED_HTTP", False),
        ]:
            with self.subTest(code=code):
                summary, success = audit.classify(code, b"any private body", NOW)
                self.assertEqual(success, valid)
                self.assertIn(expected, summary)
                self.assertNotIn("private body", summary)

    def test_candidate_is_research_only_with_independent_age_check(self):
        p = payload(
            status="RESEARCH_CANDIDATE", signal="BUY_CALL_RESEARCH",
            reasonCode="EMA_CROSSOVER", strategyId="strategy.STR-181",
            completedCandles=35, quoteSource="UPSTOX_V3_EXCHANGE_TIMED",
            candleSource="UPSTOX_ANALYTICS",
            exchangeAt=(NOW - timedelta(seconds=6)).isoformat(),
            observedAt=(NOW - timedelta(seconds=3)).isoformat(),
        )
        summary, valid = audit.classify(200, p, NOW)
        self.assertTrue(valid)
        self.assertIn("state=RESEARCH_CANDIDATE", summary)
        self.assertIn("strategy=STR_181", summary)
        self.assertIn("brokerOrders=false", summary)
        for change in [
            {"strategyId": "strategy.STR-170"},
            {"signal": "BUY_PUT_RESEARCH"},
            {"completedCandles": 34},
            {"exchangeAt": (NOW - timedelta(seconds=16)).isoformat()},
            {"observedAt": (NOW + timedelta(seconds=1)).isoformat()},
            {"quoteSource": "SYNTHETIC"},
        ]:
            with self.subTest(change=change):
                data = json.loads(p)
                data.update(change)
                summary, ok = audit.classify(200, json.dumps(data).encode(), NOW)
                self.assertFalse(ok)
                self.assertIn("CANDIDATE_PROVENANCE_INVALID", summary)

    def test_untrusted_response_never_leaks_values(self):
        changes = [
            {"contractVersion": "wrong"},
            {"underlyingKey": "NSE_FO|secret"},
            {"orderPlaced": True},
            {"executable": True},
            {"status": "FILLED"},
            {"evaluatedAt": (NOW - timedelta(minutes=5)).isoformat()},
            {"completedCandles": -1},
            {"signal": "BUY_CALL_RESEARCH"},
        ]
        for change in changes:
            with self.subTest(change=change):
                text, valid = audit.classify(200, payload(**change), NOW)
                self.assertFalse(valid)
                self.assertNotIn("secret", text)
                self.assertNotIn("DO_NOT_LOG", text)
        summary, ok = audit.classify(200, payload(reasonCode="SECRET_account_name"), NOW)
        self.assertTrue(ok)
        self.assertIn("reason=UNCLASSIFIED", summary)

    def test_cli_reads_only_bounded_file_and_never_prints_raw_response(self):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / "private.json"
            file.write_bytes(payload())
            with contextlib.redirect_stdout(io.StringIO()) as captured:
                result = audit.main(["200", str(file)])
            self.assertEqual(result, 1)  # Test timestamp is deliberately stale now
            self.assertIn("STALE_EVALUATION", captured.getvalue())
            self.assertNotIn("SECRET", captured.getvalue())
            file.write_bytes(b"A"*20001)
            with contextlib.redirect_stdout(io.StringIO()) as captured:
                result = audit.main(["200", str(file)])
            self.assertEqual(result, 1)
            self.assertIn("INVALID_RESPONSE_FILE", captured.getvalue())


if __name__ == "__main__":
    unittest.main()
