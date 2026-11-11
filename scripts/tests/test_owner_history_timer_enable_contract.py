"""Offline exact-host and no-broker-call controls for owner history timer repair."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "infra/ec2-paper/run_history_timer_enable_ssm.py"
spec = importlib.util.spec_from_file_location("timer_enable", SOURCE)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class HistoryTimerEnableContract(unittest.TestCase):
    def test_exact_request_no_orders_and_duplicate_keys_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            original = Path.cwd()
            try:
                os.chdir(temp)
                path = Path(gate.REQUEST)
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps(gate.EXPECTED), encoding="utf-8")
                gate.validate_request(gate.REQUEST.as_posix())
                for key, value in (("instance_id", "i-other"),
                                   ("enable_trading", True),
                                   ("account", "000000000000"),
                                   ("confirmation", "DISABLE_TIMER"),
                                   ("action", "stop")):
                    path.write_text(json.dumps({**gate.EXPECTED, key: value}))
                    with self.subTest(key=key), self.assertRaises(ValueError):
                        gate.validate_request(gate.REQUEST.as_posix())
                path.write_text('{"kind":"a","kind":"b"}')
                with self.assertRaises(ValueError):
                    gate.validate_request(gate.REQUEST.as_posix())
                with self.assertRaises(ValueError):
                    gate.validate_request("another/request.json")
            finally:
                os.chdir(original)

    def test_immutable_nonsecret_timer_bundle_and_shell(self):
        remote = gate.build_command()
        self.assertTrue(remote.startswith("set -eu\\npython3 -c '"), remote[:100])
        self.assertLess(len(remote), 6500)
        self.assertIn("OWNER_TIMER_TRANSPORT: VERIFIED",remote)
        self.assertNotIn("AWS_SECRET_ACCESS_KEY",remote)
        self.assertNotIn("TRADING_MODE=LIVE",remote)
        shell=(ROOT/"infra/ec2-paper/enable_owner_history_timer.sh").read_text()
        timer=(ROOT/"infra/ec2-paper/systemd/minttick-owner-history.timer").read_text()
        self.assertIn("OnCalendar=*-*-* *:0/2:00 Asia/Kolkata",shell)
        self.assertIn("OnCalendar=*-*-* *:0/2:00 Asia/Kolkata",timer)
        for marker in ("OWNER_TIMER_REPAIR: ENABLED",
                       "OWNER_TIMER_REPAIR: ACTIVE",
                       "OWNER_TIMER_REPAIR: NEXT_TICK_PRESENT",
                       "OWNER_TIMER_REPAIR: HISTORICAL_ONLY"):
            self.assertIn(marker,shell)
        for forbidden in ("aws ec2 start-instances","aws ec2 stop-instances",
                          "scheduler update-schedule","placeOrder","TRADING_MODE=LIVE"):
            self.assertNotIn(forbidden,shell)
        self.assertEqual(subprocess.run(
            ["bash","-n",str(ROOT/"infra/ec2-paper/enable_owner_history_timer.sh")],
            capture_output=True).returncode,0)

    def test_workflow_single_file_dev_exact_host_and_no_scheduler_mutation(self):
        workflow=(ROOT/".github/workflows/minttick-owner-history-timer-enable.yml").read_text()
        for required in ("branches: [dev]","fetch-depth: 2",
                         "git diff-tree --no-commit-id --name-only -r HEAD",
                         "infra/ec2-paper/requests/owner-history-enable-timer.json",
                         "repo", "role/aitrading-paper-ec2-ssm-deploy",
                         "run_history_timer_enable_ssm.py activate",
                         "cancel-in-progress: false",
                         "group: aitrading-paper-ec2-manual-lifecycle"):
            self.assertIn(required,workflow)
        for bad in ("aws ec2 start-instances", "aws ec2 stop-instances",
                    "aws scheduler update-schedule","terraform apply","TRADING_MODE=LIVE"):
            self.assertNotIn(bad,workflow)


if __name__ == "__main__":
    unittest.main()
