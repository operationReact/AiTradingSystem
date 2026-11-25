"""Guard that superseded EC2/Lightsail automation is non-executable while tests remain.

Historical YAML is intentionally archived rather than silently discarded.
This suite performs no cloud access or paid operations.
"""
from __future__ import annotations

from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
ACTIVE=ROOT/".github/workflows"
ARCHIVE=ROOT/"docs/operations/archived-actions"

RETIRED=(
    "ec2-paper-approved-provision.yml",
    "ec2-paper-existing-host-finish.yml",
    "ec2-paper-group-trust-repair.yml",
    "ec2-paper-partial-resume.yml",
    "ec2-paper-partial-state-audit.yml",
    "ec2-paper-plan-only.yml",
    "ec2-paper-provision-role-readiness.yml",
    "ec2-paper-readonly-preflight.yml",
    "ec2-paper-sg-tainted-resume.yml",
    "ec2-paper-tainted-vpc-resume.yml",
    "lightsail-paper-bootstrap.yml",
    "lightsail-paper-app-deploy.yml",
    "lightsail-paper-contract.yml",
    "lightsail-paper-connected-smoke.yml",
)


class ArchivedWorkflowContract(unittest.TestCase):
    def test_old_aws_provisioning_actions_are_archived_not_executable(self):
        for name in RETIRED:
            with self.subTest(name=name):
                self.assertFalse((ACTIVE/name).exists(), f"Unexpected executable {name}")
                source=ARCHIVE/name
                self.assertTrue(source.is_file(), f"Missing historical fixture {name}")
                self.assertFalse(source.is_symlink())
                self.assertIn("name:",source.read_text(encoding="utf-8"))

    def test_required_paper_runtime_and_safety_ci_are_retained(self):
        for name in (
            "paper-ec2-manual-control.yml",
            "ec2-paper-ssm-readiness.yml",
            "ec2-paper-ssm-app-deploy.yml",
            "ec2-paper-arm64-images.yml",
            "ec2-paper-cost-policy.yml",
            "paper-connected-db-e2e.yml",
            "dependency-security.yml",
        ):
            with self.subTest(name=name):
                self.assertTrue((ACTIVE/name).is_file())

    def test_real_connected_paper_e2e_kept_without_aws_or_live_trades(self):
        s=(ACTIVE/"paper-connected-db-e2e.yml").read_text(encoding="utf-8")
        for required in (
            "PAPER Java/Postgres/Flutter connected integration smoke",
            "flutter build web --release",
            "docker compose",
            "flyway_schema_history",
            "http://127.0.0.1:8088/api/health",
        ):
            self.assertIn(required,s)
        for forbidden in (
            "aws-actions/configure-aws-credentials",
            "terraform apply",
            "TRADING_MODE=LIVE",
            "aws ec2",
            "aws lightsail",
        ):
            self.assertNotIn(forbidden,s)


if __name__=="__main__":
    unittest.main()
