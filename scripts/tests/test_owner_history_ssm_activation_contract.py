"""Offline contract for the owner-private EC2 scheduler SSM rollout.

No cloud credentials, broker API, persisted owner files or EC2 host required.
"""
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
APPLY = ROOT / "infra/ec2-paper/ssm_apply_release.sh"
INSTALL = ROOT / "infra/ec2-paper/install_owner_history_monitor.sh"
WORKFLOW = ROOT / ".github/workflows/ec2-paper-ssm-app-deploy.yml"
COMPOSE = ROOT / "infra/ec2-paper/paper-compose.yml"
CONTROLLER = ROOT / "backend/src/main/java/in/aitradingsystem/tradingrooms/CustomerHistoricalProgressController.java"
TIMER = ROOT / "infra/ec2-paper/systemd/minttick-owner-history.timer"
SERVICE = ROOT / "infra/ec2-paper/systemd/minttick-owner-history.service"


class SsmHistoryActivationContract(unittest.TestCase):
    def test_both_scripts_bash_syntax_and_closed_install_entrypoint(self):
        for script in (APPLY, INSTALL):
            result = subprocess.run(["bash", "-n", str(script)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        # No loose root command path, user-provided token, shell network download
        # or alternate provider on this installer.
        installer = INSTALL.read_text()
        for must in ('"$#" -eq 2', '"$mode" == prepare',
                     '"$mode" == activate', 'verify_instance_identity()',
                     'publish(status="IDLE"', 'load_state(STATE)',
                     'systemctl enable --now minttick-owner-history.timer',
                     'systemctl is-active --quiet',
                     "0:600", "0:700", "0:644", "OWNER_HISTORY_TIMER: ENABLED"):
            self.assertIn(must, installer)
        for forbid in ("curl ", "wget ", "aws ssm", "aws ec2 start-instances",
                       "pip install", "systemctl start minttick-owner-history.service",
                       "OWNER_HISTORY_BACKFILL_ENABLED=true"):
            self.assertNotIn(forbid, installer)

    def test_release_contains_only_approved_runtime_metadata_and_units(self):
        workflow = WORKFLOW.read_text()
        apply = APPLY.read_text()
        required = (
            "images.tar.gz", "paper-compose.yml", "run_paper.sh",
            "install_owner_history_monitor.sh", "owner_history_scheduler.py",
            "owner_history_progress.py", "minttick-owner-history.service",
            "minttick-owner-history.timer",
        )
        for name in required:
            with self.subTest(name=name):
                self.assertIn(name, workflow)
                self.assertIn(name, apply)
        self.assertIn('bash "$work/install_owner_history_monitor.sh" prepare "$work"', apply)
        self.assertIn('bash "$root/run_paper.sh" "$sha"', apply)
        self.assertIn('bash "$work/install_owner_history_monitor.sh" activate "$work"', apply)
        self.assertLess(
            apply.index('bash "$root/run_paper.sh" "$sha"'),
            apply.index('bash "$work/install_owner_history_monitor.sh" activate "$work"'),
        )
        self.assertIn("stage=history_precheck", apply)
        self.assertIn("stage=history_activation", apply)
        self.assertIn('test "$list" = "$expected"', apply)
        self.assertIn("sha256sum -c -", apply)
        self.assertIn("git diff-tree --no-commit-id --name-only -r HEAD", workflow)
        self.assertIn('test \"$(git diff-tree --no-commit-id --name-only -r HEAD)\" = \"$APPROVAL_FILE\"', workflow)

    def test_api_is_owner_only_and_has_no_raw_archive_mount(self):
        compose = COMPOSE.read_text()
        controller = CONTROLLER.read_text()
        self.assertIn("/opt/aitradingsystem-paper/monitor:/run/minttick-owner-history:ro", compose)
        self.assertNotIn("/var/lib/minttick/owner-personal-angelone-history:", compose)
        self.assertIn("ownerSubject.equals(jwt.getToken().getSubject())", controller)
        self.assertIn("CacheControl.noStore()", controller)
        self.assertNotIn("SELECT * FROM", controller)

    def test_existing_stop_and_broker_backoff_stay_active(self):
        timer = TIMER.read_text()
        service = SERVICE.read_text()
        self.assertIn("Persistent=false", timer)
        self.assertIn("16..20:15:00 Asia/Kolkata", timer)
        self.assertIn("09..20:15:00 Asia/Kolkata", timer)
        self.assertIn("Type=oneshot", service)
        self.assertNotIn("Restart=always", service)
        collector = (ROOT / "infra/ec2-paper/owner_history_scheduler.py").read_text()
        self.assertIn("PAUSE_AFTER_ZERO_BATCHES = 2", collector)
        self.assertIn("PAUSE_HOURS = 24", collector)
        self.assertIn("MAX_RUN_SECONDS = 660", collector)


if __name__ == "__main__":
    unittest.main()
