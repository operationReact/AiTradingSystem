"""Allowlisted SSM preflight failure diagnostics never expose host output."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "infra/ec2-paper/ssm_preflight.sh"
SPEC = importlib.util.spec_from_file_location(
    "paper_ssm_preflight_gate", ROOT / "infra/ec2-paper/verify_ssm_release.py"
)
assert SPEC and SPEC.loader
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class SafeSsmPreflightDiagnostics(unittest.TestCase):
    def test_only_allowlisted_stage_markers_are_returned(self):
        self.assertEqual(
            gate.safe_preflight_failure_stage(
                "MINTTICK_PREFLIGHT_FAILED_STAGE:docker_info"
            ),
            "docker_info",
        )
        for value in (
            "MINTTICK_PREFLIGHT_FAILED_STAGE:token",
            "MINTTICK_PREFLIGHT_FAILED_STAGE:docker_info;secret",
            "raw password=never-print",
        ):
            with self.subTest(value=value):
                self.assertIsNone(gate.safe_preflight_failure_stage(value))

    def test_command_failure_reports_stage_not_raw_instance_output(self):
        responses = iter((
            {"Command": {"CommandId": "12345678-1234-1234-1234-123456789abc"}},
            {
                "Status": "Failed",
                "StandardOutputContent":
                    "MINTTICK_PREFLIGHT_FAILED_STAGE:docker_info\nDB_PASSWORD=private",
                "StandardErrorContent": "private stderr",
            },
        ))
        with patch.object(gate, "aws_json", side_effect=lambda *args: next(responses)):
            with patch.object(gate.time, "sleep"):
                with self.assertRaises(gate.Rejected) as caught:
                    gate.send_command("preflight")
        message = str(caught.exception)
        self.assertIn("docker_info", message)
        self.assertNotIn("DB_PASSWORD", message)
        self.assertNotIn("private", message)

    def test_unknown_stage_does_not_echo_marker_or_host_output(self):
        responses = iter((
            {"Command": {"CommandId": "12345678-1234-1234-1234-123456789abc"}},
            {
                "Status": "Failed",
                "StandardOutputContent":
                    "MINTTICK_PREFLIGHT_FAILED_STAGE:token DB_PASSWORD=private",
                "StandardErrorContent": "private stderr",
            },
        ))
        with patch.object(gate, "aws_json", side_effect=lambda *args: next(responses)):
            with patch.object(gate.time, "sleep"):
                with self.assertRaises(gate.Rejected) as caught:
                    gate.send_command("preflight")
        message = str(caught.exception)
        self.assertNotIn("token", message)
        self.assertNotIn("DB_PASSWORD", message)
        self.assertNotIn("private", message)

    def test_only_fixed_boolean_intake_receipt_is_accepted(self):
        receipt = (
            "STR176_INTAKE enabled=true reviewerKeyPresent=true "
            "privateDirectory=true backendConfigWired=true readonlyMount=true"
        )
        self.assertEqual(gate.safe_preflight_intake_receipt(receipt), receipt)
        self.assertIsNone(
            gate.safe_preflight_intake_receipt(receipt + " private=value")
        )
        self.assertIsNone(
            gate.safe_preflight_intake_receipt(
                "STR176_INTAKE enabled=yes reviewerKeyPresent=true "
                "privateDirectory=true backendConfigWired=true readonlyMount=true"
            )
        )

    def test_successful_preflight_prints_only_boolean_receipt(self):
        from contextlib import redirect_stdout
        from io import StringIO

        receipt = (
            "STR176_INTAKE enabled=false reviewerKeyPresent=false "
            "privateDirectory=false backendConfigWired=false readonlyMount=false"
        )
        responses = iter((
            {"Command": {"CommandId": "12345678-1234-1234-1234-123456789abc"}},
            {
                "Status": "Success",
                "StandardOutputContent": receipt + "\nDB_PASSWORD=private",
                "StandardErrorContent": "private stderr",
            },
        ))
        stream = StringIO()
        with patch.object(gate, "aws_json", side_effect=lambda *args: next(responses)):
            with patch.object(gate.time, "sleep"):
                with redirect_stdout(stream):
                    gate.send_command("preflight")
        output = stream.getvalue()
        self.assertIn(receipt, output)
        self.assertNotIn("DB_PASSWORD", output)
        self.assertNotIn("private stderr", output)

    def test_success_without_safe_intake_receipt_fails_closed(self):
        responses = iter((
            {"Command": {"CommandId": "12345678-1234-1234-1234-123456789abc"}},
            {
                "Status": "Success",
                "StandardOutputContent": "DB_PASSWORD=private",
                "StandardErrorContent": "",
            },
        ))
        with patch.object(gate, "aws_json", side_effect=lambda *args: next(responses)):
            with patch.object(gate.time, "sleep"):
                with self.assertRaises(gate.Rejected) as caught:
                    gate.send_command("preflight")
        self.assertNotIn("private", str(caught.exception))
        self.assertIn("receipt was absent", str(caught.exception))

    def test_remote_preflight_has_safe_stage_trap_and_parses_cleanly(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("MINTTICK_PREFLIGHT_FAILED_STAGE:%s", source)
        self.assertIn("info=os.lstat(directory)", source)
        self.assertNotIn("follow_symlinks=False", source)
        for stage in gate.PREFLIGHT_STAGES:
            if stage != "complete":
                self.assertIn("stage=" + stage, source)
        import subprocess
        result = subprocess.run(
            ["bash", "-n", str(SCRIPT)], capture_output=True, text=True, check=False
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
