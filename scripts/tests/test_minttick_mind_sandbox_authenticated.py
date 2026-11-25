"""No-network tests for authenticated, journaled Mintick Mind sandbox execution."""
from __future__ import annotations

import contextlib
import datetime as dt
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

SRC = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SRC))
SPEC = importlib.util.spec_from_file_location(
    "minttick_mind_sandbox_authenticated",
    SRC / "minttick_mind_sandbox_authenticated.py")
runtime = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runtime
SPEC.loader.exec_module(runtime)

INTENT = runtime.bridge.SandboxIntent(
    "strategy.STR-181", "NSE_FO|47895",
    dt.date(2026, 12, 1), 65,
    runtime.bridge.Decimal("100.05"), runtime.bridge.Decimal("0.05"))
RESEARCH = {"latestCompletedCloseAt": "2026-11-23T15:30:00+05:30"}
JWT = "test-customer-jwt-no-real-secret.abcdefghijk.abcdefghijklmnop"


class _Response:
    status = 200
    def __init__(self, body=b'{"status":"NO_TRADE"}'):
        self.body = body
    def __enter__(self):
        return self
    def __exit__(self, *args):
        return False
    def read(self, n):
        return self.body[:n]


class _Opener:
    def __init__(self, response):
        self.response = response
        self.calls = []
    def open(self, request, timeout):
        self.calls.append(request)
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


class AuthenticatedMindSandboxTests(unittest.TestCase):
    def test_authenticated_get_is_exact_https_customer_route_with_identifying_ua(self):
        opener = _Opener(_Response())
        with patch.object(runtime.urllib.request, "build_opener", return_value=opener):
            self.assertEqual(runtime.get_authenticated_signal(JWT), {"status": "NO_TRADE"})
        self.assertEqual(len(opener.calls), 1)
        request = opener.calls[0]
        self.assertEqual(request.full_url, runtime.MIND_ENDPOINT)
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.get_header("Authorization"), "Bearer " + JWT)
        self.assertEqual(request.get_header("User-agent"), "MintTick-Mind-Sandbox-ReadOnly/1.0")
        self.assertIn("api.minttick.com/api/customer/mind/v1/fno-evaluation", request.full_url)

    def test_token_and_auth_errors_never_expose_provider_response(self):
        for token in ("", "short", "abc\r\nInjected: yes"):
            with self.subTest(token=token):
                with self.assertRaisesRegex(runtime.RuntimeBlocked, "CUSTOMER_TOKEN"):
                    runtime.get_authenticated_signal(token)
        error = urllib.error.HTTPError(
            runtime.MIND_ENDPOINT, 403, "secret-private-response", {}, None)
        opener = _Opener(error)
        with patch.object(runtime.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(runtime.RuntimeBlocked, "^MIND_CUSTOMER_GET_HTTP_REJECTED$") as e:
                runtime.get_authenticated_signal(JWT)
        self.assertNotIn("secret-private", str(e.exception))

    def test_research_signal_identity_ignores_changing_quote_ticks(self):
        a = runtime.signal_fingerprint(RESEARCH, INTENT)
        b = runtime.signal_fingerprint({**RESEARCH, "exchangeAt": "later"}, INTENT)
        self.assertEqual(a, b)
        another = runtime.bridge.SandboxIntent(
            "strategy.STR-182", "NSE_FO|47896",
            INTENT.expiry, INTENT.quantity, INTENT.limit_price, INTENT.tick)
        self.assertNotEqual(a, runtime.signal_fingerprint(RESEARCH, another))

    def test_success_writes_broker_ack_only_and_rejects_second_submission(self):
        with tempfile.TemporaryDirectory() as tmp:
            journal = Path(tmp) / "once.sqlite"
            with patch.object(runtime.ssm, "load_sandbox_token", return_value="dummy"), \
                 patch.object(runtime.sandbox, "sandbox_transport", return_value=object()), \
                 patch.object(runtime.sandbox, "run_cycle", return_value={
                     "placed": True, "modified": True, "cancelled": True,
                 }) as orders:
                digest = runtime.execute_once(RESEARCH, INTENT, journal)
                self.assertEqual(len(digest), 12)
                with sqlite3.connect(journal) as db:
                    self.assertEqual(db.execute(
                        "SELECT status FROM sandbox_attempt").fetchone()[0],
                        "BROKER_ACKS_ONLY")
                    self.assertEqual(db.execute("SELECT COUNT(*) FROM sandbox_attempt").fetchone()[0], 1)
                with self.assertRaisesRegex(runtime.RuntimeBlocked, "ALREADY_CLAIMED"):
                    runtime.execute_once(RESEARCH, INTENT, journal)
                orders.assert_called_once()

    def test_unknown_outcome_remains_locked_without_retry(self):
        with tempfile.TemporaryDirectory() as tmp:
            journal = Path(tmp) / "once.sqlite"
            with patch.object(runtime.ssm, "load_sandbox_token", return_value="dummy"), \
                 patch.object(runtime.sandbox, "sandbox_transport", return_value=object()), \
                 patch.object(runtime.sandbox, "run_cycle",
                    side_effect=runtime.sandbox.SandboxError("SANDBOX_TRANSPORT_OUTCOME_UNKNOWN")) as orders:
                with self.assertRaisesRegex(runtime.RuntimeBlocked, "OUTCOME_UNKNOWN_NO_RETRY"):
                    runtime.execute_once(RESEARCH, INTENT, journal)
                with sqlite3.connect(journal) as db:
                    self.assertEqual(db.execute(
                        "SELECT status FROM sandbox_attempt").fetchone()[0],
                        "OUTCOME_UNKNOWN")
                with self.assertRaisesRegex(runtime.RuntimeBlocked, "ALREADY_CLAIMED"):
                    runtime.execute_once(RESEARCH, INTENT, journal)
                orders.assert_called_once()

    def test_interrupted_claim_is_not_retried(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "interrupted.sqlite"
            digest = runtime.signal_fingerprint(RESEARCH, INTENT)
            with runtime.claim_one_attempt(path, digest, INTENT) as db:
                self.assertEqual(db.execute("SELECT status FROM sandbox_attempt").fetchone()[0], "CLAIMED")
            with self.assertRaisesRegex(runtime.RuntimeBlocked, "ALREADY_CLAIMED"):
                runtime.claim_one_attempt(path, digest, INTENT)

    def test_nonpersistent_and_checkout_journals_are_denied(self):
        for path in (Path("relative.sqlite"), Path.cwd() / "unsafe.sqlite", None):
            with self.subTest(path=path), self.assertRaises(runtime.RuntimeBlocked):
                runtime.claim_one_attempt(path, runtime.signal_fingerprint(RESEARCH, INTENT), INTENT)

    def test_default_no_network_and_explicit_confirmation(self):
        with patch.object(runtime, "get_authenticated_signal") as fetch, \
             patch.object(runtime.ssm, "load_sandbox_token") as token, \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(runtime.main([]), 2)
            self.assertEqual(runtime.main(["--execute-sandbox"]), 2)
            self.assertEqual(runtime.main(["--evaluate", "--journal", "/tmp/ignored.sqlite"]), 2)
            fetch.assert_not_called()
            token.assert_not_called()

    def test_authenticated_read_only_mode_has_no_broker_access(self):
        with patch.dict(os.environ, {runtime.BEARER_ENV: JWT}), \
             patch.object(runtime, "get_authenticated_signal", return_value=RESEARCH) as fetch, \
             patch.object(runtime.bridge, "current_nse_master", return_value=[]) as bod, \
             patch.object(runtime.bridge, "intent_from_research", return_value=INTENT), \
             patch.object(runtime.ssm, "load_sandbox_token") as token, \
             patch.object(runtime.sandbox, "run_cycle") as orders, \
             contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(runtime.main(["--evaluate"]), 0)
            self.assertIn("EVALUATION_ONLY:NO_ORDER", out.getvalue())
            fetch.assert_called_once_with(JWT)
            bod.assert_called_once()
            token.assert_not_called()
            orders.assert_not_called()


if __name__ == "__main__":
    unittest.main()
