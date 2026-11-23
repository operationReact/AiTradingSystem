"""Zero-network tests for SSM-only Upstox sandbox token retrieval."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))
SPEC = importlib.util.spec_from_file_location(
    "upstox_fno_sandbox_ssm", SCRIPT_DIR / "upstox_fno_sandbox_ssm.py"
)
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)
CTX = dict(runner._REQUIRED_CONTEXT)
TOKEN = "sandbox-only-fixture-not-a-secret-123"


class SsmSandboxTests(unittest.TestCase):
    def test_exact_ssm_securestring_read_and_no_secret_in_output(self):
        response = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=TOKEN + "\n", stderr=""
        )
        with patch.object(runner.subprocess, "run", return_value=response) as command:
            self.assertEqual(runner.load_sandbox_token(), TOKEN)
        args = command.call_args.args[0]
        self.assertEqual(args[0:3], ["aws", "ssm", "get-parameter"])
        self.assertIn(runner.PARAMETER, args)
        self.assertIn("--with-decryption", args)
        self.assertIn("ap-south-1", args)
        self.assertTrue(command.call_args.kwargs["capture_output"])

    def test_failure_never_prints_aws_stderr_or_token(self):
        response = subprocess.CompletedProcess(
            args=[], returncode=255, stdout=TOKEN, stderr="danger " + TOKEN
        )
        out = io.StringIO()
        with patch.dict(os.environ, CTX), \
             patch.object(runner.subprocess, "run", return_value=response), \
             contextlib.redirect_stdout(out):
            self.assertEqual(runner.main([
                "--instrument-key", "NSE_FO|61001",
                "--quantity", "65", "--price", "100.05", "--tick", "0.05",
            ]), 2)
        self.assertEqual(out.getvalue().strip(), "SANDBOX_SSM_UNAVAILABLE")
        self.assertNotIn(TOKEN, out.getvalue())

    def test_wrong_context_never_calls_aws_or_broker(self):
        for key in CTX:
            bad = dict(CTX)
            bad[key] = "WRONG"
            with self.subTest(key=key), patch.dict(os.environ, bad), \
                 patch.object(runner.subprocess, "run") as aws, \
                 patch.object(runner.probe, "main") as broker:
                self.assertEqual(runner.main([]), 2)
                aws.assert_not_called()
                broker.assert_not_called()

    def test_success_only_passes_token_in_process_and_clears_it(self):
        response = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=TOKEN + "\n", stderr=""
        )
        with patch.dict(os.environ, CTX):
            os.environ.pop("UPSTOX_SANDBOX_TOKEN", None)
            def verify_probe(argv):
                self.assertEqual(os.environ["UPSTOX_SANDBOX_TOKEN"], TOKEN)
                self.assertEqual(argv[0], "--execute-sandbox")
                return 0
            out = io.StringIO()
            with patch.object(runner.subprocess, "run", return_value=response), \
                 patch.object(runner.probe, "main", side_effect=verify_probe), \
                 contextlib.redirect_stdout(out):
                self.assertEqual(runner.main([
                    "--instrument-key", "NSE_FO|61001",
                    "--quantity", "65", "--price", "100.05", "--tick", "0.05",
                ]), 0)
            self.assertNotIn("UPSTOX_SANDBOX_TOKEN", os.environ)
            self.assertNotIn(TOKEN, out.getvalue())

    def test_blank_or_invalid_parameter_denied_before_orders(self):
        for token in ("", "None", "short", "abc\nxyz", "abc def"):
            with self.subTest(token=token), \
                 patch.object(runner.subprocess, "run", return_value=subprocess.CompletedProcess(
                     args=[], returncode=0, stdout=token, stderr=""
                 )), patch.object(runner.probe, "main") as broker:
                with self.assertRaises(runner.SandboxBootstrapError):
                    runner.load_sandbox_token()
                broker.assert_not_called()


if __name__ == "__main__":
    unittest.main()
