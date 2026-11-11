"""Synthetic authorization regression tests, no network or AWS."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "infra" / "ec2-paper"))
import owner_personal_history_request as req


class OwnerHistoryRequestTest(unittest.TestCase):
    def test_exact_modes_and_audit_zero_calls(self):
        for path, (mode, payload) in req.APPROVALS.items():
            self.assertEqual(mode, req.approved_mode(path, copy.deepcopy(payload)))
        self.assertEqual("audit-only", req.APPROVALS[req.AUDIT][0])
        self.assertEqual(0, req.APPROVALS[req.AUDIT][1]["max_provider_calls"])

    def test_injected_path_rejected(self):
        for path in ("other.json", req.AUDIT + "\nunsafe", req.AUDIT + " "):
            with self.assertRaises(ValueError):
                req.approved_mode(path, {})
            with self.assertRaises(ValueError):
                req.validate_file(path)

    def test_modified_audit_refused(self):
        correct = req.APPROVALS[req.AUDIT][1]
        cases = [
            {k: v for k, v in correct.items() if k != "audit_only"},
            {**correct, "shell": "curl server"},
            {**correct, "operation": "owner-personal-angelone-daily-history-pilot"},
            {**correct, "max_provider_calls": 20},
            {**correct, "audit_only": False},
        ]
        for case in cases:
            with self.subTest(case=case):
                with self.assertRaises(ValueError):
                    req.approved_mode(req.AUDIT, case)

    def test_strict_field_types(self):
        correct = req.APPROVALS[req.AUDIT][1]
        for case in ({**correct, "audit_only": 1},
                     {**correct, "max_provider_calls": False},
                     {**correct, "private_ec2_only": 1}):
            with self.assertRaises(ValueError):
                req.approved_mode(req.AUDIT, case)

    def test_private_audit_runtime_diagnostics_are_sanitized(self):
        workflow=(Path(__file__).resolve().parents[2] /
                  ".github/workflows/minttick-owner-private-candle-pilot.yml").read_text()
        for marker in ('("TIMER_ACTIVE","is-active")', '("TIMER_ENABLED","is-enabled")',
                       '"LAST_RUN_NEW_CANDLES","newCandles"', '"LAST_RUN_WINDOWS","windowsQueried"',
                       "OWNER_RUNTIME_PROGRESS_STATUS:", "OWNER_RUNTIME_LAST_RUN_STATE:",
                       "OWNER_RUNTIME_SERVICE_RESULT:", "OWNER_RUNTIME_LAST_EVENT:",
                       "OWNER_RUNTIME_EVENTS_SAMPLED:", "safe_runtime = [line for line in lines"):
            with self.subTest(marker=marker):
                self.assertIn(marker, workflow)
        self.assertIn("if test '@@OWNER_MODE@@' = audit-only; then",workflow)
        self.assertIn("PERSONAL_ARCHIVE_API_CALLS: 0",workflow)
        self.assertIn("print(line)",workflow)
        self.assertNotIn("print(journal.stdout)",workflow)
        self.assertNotIn("print(output)",workflow)

    def test_duplicate_keys_rejected(self):
        with self.assertRaises(ValueError):
            req.json.loads('{"audit_only":true,"audit_only":false}',
                           object_pairs_hook=req.strict_pairs)


if __name__ == "__main__":
    unittest.main()
