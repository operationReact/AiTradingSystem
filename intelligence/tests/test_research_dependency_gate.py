"""V97 synthetic release-image dependency constraints; no Docker or credentials."""
from __future__ import annotations

import contextlib
import io
import unittest
from unittest.mock import patch

from aitrading_intelligence import research_dependency_gate as gate

DECLARED = [
    "catboost == 1.2.10", "hmmlearn==0.3.3", "lightgbm==4.7.0",
    "fastapi>=0.115,<1",
]
INSTALLED = {"catboost": "1.2.10", "hmmlearn": "0.3.3", "lightgbm": "4.7.0"}


class ReleaseDependencyGateTest(unittest.TestCase):
    def test_reviewed_direct_versions_are_accepted(self):
        gate.validate((3, 11), DECLARED, INSTALLED, set(), True)

    def test_wrong_or_unpinned_direct_versions_are_rejected(self):
        for candidate in ("catboost>=1.2.10", "catboost==0.9.0"):
            with self.subTest(candidate=candidate):
                declared = [candidate, *DECLARED[1:]]
                with self.assertRaises(ValueError):
                    gate.validate((3, 11), declared, INSTALLED, set(), True)
        for name in gate.DIRECT_PINS:
            changed = {**INSTALLED, name: "99.99"}
            with self.assertRaises(ValueError):
                gate.validate((3, 11), DECLARED, changed, set(), True)

    def test_broken_pip_graph_wrong_python_and_dev_tool_are_rejected(self):
        for python_version, extra, valid in (
            ((3, 12), set(), True),
            ((3, 10), set(), True),
            ((3, 11), {"pytest"}, True),
            ((3, 11), {"torch"}, True),
            ((3, 11), set(), False),
        ):
            with self.assertRaises(ValueError):
                gate.validate(python_version, DECLARED, INSTALLED, extra, valid)

    def test_worker_runtime_invokes_pip_check_offline(self):
        with (
            patch.object(gate.metadata, "requires", return_value=DECLARED),
            patch.object(gate.metadata, "version", side_effect=lambda name: INSTALLED.get(name)
                         if name in INSTALLED
                         else (_ for _ in ()).throw(gate.metadata.PackageNotFoundError(name))),
            patch.object(gate.subprocess, "run", return_value=type("Completed", (), {
                "returncode": 0
            })()) as run,
            patch.object(gate.sys, "version_info", (3, 11, 0)),
        ):
            gate.verify_release_dependency_posture()
        args, kwargs = run.call_args
        self.assertEqual(args[0][1:], ["-m", "pip", "check"])
        self.assertEqual(kwargs["timeout"], 45)
        self.assertTrue(kwargs["capture_output"])
        self.assertFalse(kwargs["check"])

    def test_main_fail_closed_without_leaking_failure_details(self):
        err = io.StringIO()
        with (
            patch.object(gate, "verify_release_dependency_posture",
                         side_effect=ValueError("private-package-token-and-path")),
            contextlib.redirect_stderr(err),
        ):
            self.assertEqual(gate.main(), 2)
        self.assertIn("BLOCKED (sanitized)", err.getvalue())
        self.assertNotIn("private-package", err.getvalue())


if __name__ == "__main__":
    unittest.main()
