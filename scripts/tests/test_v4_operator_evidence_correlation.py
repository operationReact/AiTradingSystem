"""V87 no-AWS synthetic evidence correlation and adversarial source tests."""
from __future__ import annotations

import copy
import json
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v4_operator_evidence_correlation import evaluate  # noqa: E402

SHA = "b" * 40
NOW = datetime(2026, 11, 5, 12, 0, tzinfo=timezone.utc)


def iso(offset: int) -> str:
    return (NOW + timedelta(minutes=offset)).isoformat()


def run(path: str, run_id: int) -> dict:
    return {
        "id": run_id,
        "repository": {"full_name": "operationReact/AiTradingSystem"},
        "event": "workflow_dispatch",
        "path": path + "@dev",
        "head_branch": "dev",
        "head_sha": SHA,
        "status": "completed",
        "conclusion": "success",
        "created_at": iso(-12),
        "updated_at": iso(-7),
        "token": "DO_NOT_COPY_PRIVATE_GITHUB_TOKEN",
    }


def fixture() -> dict:
    return {
        "backend": {
            "contract_version": "v4-paper-runtime-posture.v1",
            "expected_commit_sha": SHA,
            "checked_at_utc": iso(-9),
            "target_region": "us-east-1",
            "aws_account_verified": True,
            "paper_backend_posture_verified": True,
            "research_opt_ins_disabled": True,
            "backend_service_stable": True,
            "public_paper_health_verified": True,
            "worker_model_identity_attested": False,
            "live_execution_enabled": False,
            "can_authorize_capital": False,
            "verdict": "PAPER_BACKEND_POSTURE_ONLY_RESEARCH_UNVERIFIED",
            "blocker_codes": [],
            "secret_ref": "EXAMPLE-SENSITIVE-SECRET-DO-NOT-REPORT",
        },
        "network": {
            "contract_version": "v4-worker-private-tls-topology.v1",
            "expected_commit_sha": SHA,
            "checked_at_utc": iso(-8),
            "region": "us-east-1",
            "aws_account_verified": True,
            "private_worker_tls_metadata_verified": True,
            "backend_frontend_worker_ecs_separation_verified": True,
            "backend_only_sg_identity_verified": True,
            "private_dns_alias_verified": True,
            "private_tls_handshake_from_java_verified": False,
            "worker_running": False,
            "live_execution_enabled": False,
            "can_authorize_capital": False,
            "verdict": "PRIVATE_TLS_TOPOLOGY_CONFIGURED_WORKER_DORMANT_ONLY",
            "blocker_codes": [],
        },
        "backend_run": run(".github/workflows/v4-paper-runtime-inspect.yml", 151),
        "network_run": run(
            ".github/workflows/v4-worker-private-tls-readonly-inspect.yml", 162
        ),
        "sha": SHA,
        "now": NOW,
    }


class PaperPrivateTlsCorrelationTest(unittest.TestCase):
    def blocked(self, input_data: dict, code: str):
        verdict = evaluate(**input_data)
        self.assertEqual(verdict["verdict"], "BLOCKED")
        self.assertFalse(verdict["can_authorize_capital"])
        self.assertFalse(verdict["paper_backend_and_dormant_worker_metadata_correlated"])
        self.assertIn(code, verdict["blocker_codes"])

    def test_matching_manual_github_evidence_is_still_dormant_only(self):
        result = evaluate(**fixture())
        self.assertTrue(result["paper_backend_and_dormant_worker_metadata_correlated"])
        self.assertEqual(result["verdict"], "PAPER_BACKEND_DORMANT_PRIVATE_TLS_ONLY")
        for field in (
            "actual_java_tls_handshake_verified",
            "worker_model_or_secret_identity_verified",
            "independent_source_rights_verified",
            "paper_shadow_soak_accepted",
            "broker_reconciliation_accepted",
            "live_execution_enabled",
            "can_authorize_capital",
        ):
            self.assertFalse(result[field])
        serialized = json.dumps(result)
        for forbidden in (
            "DO_NOT_COPY_PRIVATE_GITHUB_TOKEN",
            "EXAMPLE-SENSITIVE-SECRET-DO-NOT-REPORT",
            ".github/workflows/v4-paper-runtime-inspect.yml",
        ):
            self.assertNotIn(forbidden, serialized)

    def test_backend_or_network_blocked_verdict_cannot_join(self):
        f = fixture()
        f["backend"]["paper_backend_posture_verified"] = False
        self.blocked(f, "PAPER_BACKEND_EVIDENCE_UNVERIFIED")
        f = fixture()
        f["network"]["private_worker_tls_metadata_verified"] = False
        self.blocked(f, "DORMANT_PRIVATE_WORKER_TLS_EVIDENCE_UNVERIFIED")
        f = fixture()
        f["network"]["worker_running"] = True
        self.blocked(f, "DORMANT_PRIVATE_WORKER_TLS_EVIDENCE_UNVERIFIED")
        f = fixture()
        f["backend"]["research_opt_ins_disabled"] = False
        self.blocked(f, "PAPER_BACKEND_EVIDENCE_UNVERIFIED")

    def test_any_capital_claim_or_real_tls_proof_breaks_expected_schema(self):
        f = fixture()
        f["backend"]["live_execution_enabled"] = True
        self.blocked(f, "PAPER_BACKEND_EVIDENCE_UNVERIFIED")
        f = fixture()
        f["network"]["can_authorize_capital"] = True
        self.blocked(f, "DORMANT_PRIVATE_WORKER_TLS_EVIDENCE_UNVERIFIED")
        f = fixture()
        f["network"]["private_tls_handshake_from_java_verified"] = True
        self.blocked(f, "DORMANT_PRIVATE_WORKER_TLS_EVIDENCE_UNVERIFIED")

    def test_wrong_commit_branch_event_repo_or_workflow_path_rejected(self):
        cases = (
            ("head_sha", "c" * 40),
            ("head_branch", "main"),
            ("event", "pull_request"),
            ("path", ".github/workflows/deploy-live-aws.yml"),
            ("conclusion", "failure"),
            ("status", "in_progress"),
        )
        for field, value in cases:
            f = fixture()
            f["backend_run"][field] = value
            self.blocked(f, "GITHUB_INSPECTION_RUN_ORIGIN_UNVERIFIED")
        f = fixture()
        f["backend_run"]["repository"]["full_name"] = "attacker/other-repo"
        self.blocked(f, "GITHUB_INSPECTION_RUN_ORIGIN_UNVERIFIED")
        f = fixture()
        f["network_run"]["id"] = f["backend_run"]["id"]
        self.blocked(f, "GITHUB_INSPECTION_RUN_ORIGIN_UNVERIFIED")

    def test_stale_future_or_mismatched_report_timing_is_blocked(self):
        f = fixture()
        f["backend"]["checked_at_utc"] = iso(-120)
        self.blocked(f, "INSPECTION_EVIDENCE_STALE_OR_UNGROUNDED")
        f = fixture()
        f["backend"]["checked_at_utc"] = iso(12)
        self.blocked(f, "INSPECTION_EVIDENCE_STALE_OR_UNGROUNDED")
        f = fixture()
        f["network"]["checked_at_utc"] = "2026-11-05T11:54:00"
        self.blocked(f, "INSPECTION_EVIDENCE_STALE_OR_UNGROUNDED")
        f = fixture()
        f["network_run"]["created_at"] = iso(-65)
        f["network_run"]["updated_at"] = iso(-4)
        f["network"]["checked_at_utc"] = iso(-57)
        self.blocked(f, "BACKEND_AND_TLS_EVIDENCE_TIME_SKEW")

    def test_different_expected_sha_or_report_version_blocks(self):
        f = fixture()
        f["sha"] = "c" * 40
        self.blocked(f, "GITHUB_INSPECTION_RUN_ORIGIN_UNVERIFIED")
        f = fixture()
        f["backend"]["contract_version"] = "legacy-v1"
        self.blocked(f, "PAPER_BACKEND_EVIDENCE_UNVERIFIED")
        f = fixture()
        f["network"]["blocker_codes"] = ["PRIVATE_WORKER_DNS_ALIAS_UNVERIFIED"]
        self.blocked(f, "DORMANT_PRIVATE_WORKER_TLS_EVIDENCE_UNVERIFIED")

    def test_evidence_objects_are_not_modified_by_evaluation(self):
        f = fixture()
        original = copy.deepcopy(f)
        evaluate(**f)
        self.assertEqual(f, original)


if __name__ == "__main__":
    unittest.main()
