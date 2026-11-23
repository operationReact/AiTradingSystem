"""Offline test suite for the authenticated, GET-only PAPER readiness audit."""
from __future__ import annotations

import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

src = Path(__file__).with_name("audit_fno_paper_runtime.py")
spec = importlib.util.spec_from_file_location("audit_fno_paper_runtime", src)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

ROOM = "b1469dc1-1c30-42c4-a64b-2de0eaf09873"
TOKEN = "PRIVATE_FIXTURE_TOKEN_MUST_NOT_BE_LOGGED"


def fixtures():
    return {
        module.ROOT: [{
            "id": ROOM, "name": "My private F&O room",
            "marketScope": "FNO", "mode": "PAPER",
            "status": "PAPER_FUNDED", "focus": "INTRADAY",
            "plannedBudgetInr": 20000,
        }],
        module.ROOT + "/" + ROOM + "/paper/fno/capability": {
            "status": "SOURCE_CHECK_REQUIRED", "readOnlyConfigured": True,
            "paperConfigured": True, "liveExecutionAuthorized": False,
            "blockerCodes": []},
        module.ROOT + "/" + ROOM + "/paper/fno/reconciliation": {
            "status": "NO_FILLS", "recordedFills": 0,
            "buyFills": 0, "sellFills": 0, "openContracts": 0,
            "tradingApproved": False},
        module.ROOT + "/" + ROOM + "/paper/fno/wallet-reconciliation": {
            "cashAndRealizedPnlReconciled": True, "optionFills": 0,
            "tradingApproved": False, "recordedCashPaise": 10000000},
        module.ROOT + "/" + ROOM + "/mind/fno/paper-automation": {
            "enabled": False, "liveMoney": False, "lastReason": None},
        module.ROOT + "/" + ROOM + "/mind/fno/paper-automation/history": [],
    }


class PaperEvidenceTests(unittest.TestCase):
    def test_no_eligible_verification_room_does_not_call_broker_or_mutate(self):
        calls = []
        def get(path, token):
            calls.append((path, token))
            return [{
                "id": ROOM, "marketScope": "EQUITY",
                "mode": "PAPER", "status": "PAPER_FUNDED", "focus": "INTRADAY",
            }]
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = module.run(TOKEN, get)
        self.assertEqual(result, 0)
        self.assertEqual(calls, [(module.ROOT, TOKEN)])
        self.assertIn("NO_FUNDED_FNO_PAPER_ROOM_IN_VERIFICATION_ACCOUNT", output.getvalue())
        self.assertNotIn(ROOM, output.getvalue())

    def test_only_expected_authenticated_get_routes_and_no_sensitive_logs(self):
        values = fixtures()
        calls = []
        def get(path, token):
            calls.append(path)
            self.assertEqual(token, TOKEN)
            return values[path]
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(module.run(TOKEN, get), 0)
        result = output.getvalue()
        self.assertEqual(len(calls), 6)
        self.assertTrue(all(path.startswith(module.ROOT) for path in calls))
        self.assertIn("DISABLED_BUT_LOCALLY_READY", result)
        self.assertIn("independentStrategyE2E=false", result)
        for secret in (ROOM, "My private F&O room", "20000", "10000000", TOKEN):
            self.assertNotIn(secret, result)

    def test_mismatch_blocks_even_if_worker_is_enabled(self):
        values = fixtures()
        values[module.ROOT + "/" + ROOM + "/mind/fno/paper-automation"]["enabled"] = True
        values[module.ROOT + "/" + ROOM + "/paper/fno/reconciliation"]["status"] = "INCONSISTENT"
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(module.run(TOKEN, lambda path, token: values[path]), 0)
        self.assertIn("ENABLED_BUT_UNSAFE_RUNTIME", output.getvalue())
        self.assertIn("bookStatus=INCONSISTENT", output.getvalue())

    def test_disabled_paper_reports_allowlisted_flag_without_nonexistent_scheduler_get(self):
        values = fixtures()
        cap = module.ROOT + "/" + ROOM + "/paper/fno/capability"
        values[cap]["paperConfigured"] = False
        values[cap]["status"] = "PAPER_USE_RIGHTS_NOT_VERIFIED"
        values[cap]["blockerCodes"] = ["PAPER_USE_RIGHTS_NOT_VERIFIED"]
        requested = []
        def get(path, token):
            requested.append(path)
            return values[path]
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(module.run(TOKEN, get), 0)
        text = output.getvalue()
        self.assertIn("state=PAPER_CONFIGURATION_BLOCKED", text)
        self.assertIn("codes=PAPER_USE_RIGHTS_NOT_VERIFIED", text)
        self.assertIn("bookStatus=NO_FILLS;cashMatched=true", text)
        self.assertNotIn("PRIVATE", text)
        self.assertNotIn(ROOM, text)
        self.assertFalse(any("paper-automation" in path for path in requested))

    def test_no_fill_is_not_counted_as_e2e_and_history_is_only_local_evidence(self):
        values = fixtures()
        history = module.ROOT + "/" + ROOM + "/mind/fno/paper-automation/history"
        values[history] = [{"status":"FILLED_RECONCILED", "liveMoney":False,
                            "instrumentKey":"NSE_FO|SECRET", "walletCashAfterPaise":11}]
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(module.run(TOKEN, lambda path, token: values[path]), 0)
        self.assertIn("paperHistoryEvents=1", output.getvalue())
        self.assertIn("independentStrategyE2E=false", output.getvalue())
        self.assertNotIn("SECRET", output.getvalue())

    def test_invalid_contract_blocks_without_customer_or_provider_body(self):
        values = fixtures()
        prefix = module.ROOT + "/" + ROOM
        cases = [
            (prefix+"/paper/fno/capability", {"liveExecutionAuthorized": True}),
            (prefix+"/paper/fno/wallet-reconciliation", {"tradingApproved": True}),
            (prefix+"/paper/fno/reconciliation", {"recordedFills": -1}),
            (prefix+"/mind/fno/paper-automation", {"liveMoney": True}),
        ]
        for path, changes in cases:
            with self.subTest(path=path):
                copies = fixtures()
                copies[path].update(changes)
                with contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(module.run(TOKEN, lambda p,t: copies[p]), 1)
                self.assertIn("READ_ONLY_PROBE_BLOCKED", output.getvalue())
                self.assertNotIn(ROOM, output.getvalue())

    def test_input_limits_and_room_id_rejection(self):
        with self.assertRaises(module.ReadOnlyProbeFailure):
            module.find_funded_options([{}]*101)
        with self.assertRaises(module.ReadOnlyProbeFailure):
            module.find_funded_options([{"id":"SECRET", "marketScope":"FNO",
                "mode":"PAPER","status":"PAPER_FUNDED","focus":"INTRADAY"}])
        for path in ("http://external", "/api/customer/other", module.ROOT + "//evil"):
            with self.subTest(path=path):
                with self.assertRaises(module.ReadOnlyProbeFailure):
                    module.get_json(path,TOKEN)
        self.assertEqual(module.main([]),2)

    def test_real_get_method_and_headers_are_read_only_and_no_redirect(self):
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self,*args): return False
            def read(self, n): return b"[]"
        class Opener:
            def __init__(self): self.req=None
            def open(self, request, timeout):
                self.req=request
                return Response()
        opener=Opener()
        self.assertEqual(module.get_json(module.ROOT,TOKEN,opener),[])
        self.assertEqual(opener.req.get_method(),"GET")
        self.assertEqual(opener.req.full_url,module.HOST+module.ROOT)
        self.assertEqual(opener.req.get_header("Authorization"),"Bearer "+TOKEN)
        self.assertEqual(opener.req.get_header("User-agent"),"MintTick-Mind-PAPER-Audit/1.0")


if __name__=="__main__":
    unittest.main()
