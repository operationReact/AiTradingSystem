"""V87 workflow is no-AWS, exact-run, bounded artifact correlation only."""
from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / "scripts/v4_operator_evidence_correlation.py"
WORKFLOW = ROOT / ".github/workflows/v4-paper-private-tls-evidence-correlation.yml"


class OperatorEvidenceSourcePolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = EVAL.read_text(encoding="utf-8")
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_manual_exact_dev_github_artifacts_only(self):
        w = self.workflow
        self.assertIn("on:\n  workflow_dispatch:", w)
        self.assertIn("actions: read", w)
        self.assertIn("contents: read", w)
        self.assertNotIn("id-token: write", w)
        self.assertIn('refs/heads/dev', w)
        self.assertIn("operationReact/AiTradingSystem", w)
        self.assertIn("gh api", w)
        self.assertIn('gh run download "$BACKEND_RUN_ID"', w)
        self.assertIn('gh run download "$NETWORK_RUN_ID"', w)
        self.assertIn("--name v4-paper-runtime-posture-readonly", w)
        self.assertIn("--name v86-private-worker-tls-readonly", w)
        self.assertIn("v4-paper-runtime-posture.json", w)
        self.assertIn("v86-private-worker-tls-posture.json", w)
        self.assertIn("--expected-sha \"$GITHUB_SHA\"", w)
        self.assertIn("retention-days: 14", w)
        self.assertIn('umask 077', w)
        self.assertIn("trap 'rm -rf \"$temp\"' EXIT", w)

    def test_never_read_aws_or_deploy_via_correlation(self):
        w = self.workflow
        for disallowed in (
            "configure-aws-credentials",
            "secrets.AWS_ROLE_ARN",
            "aws sts",
            "aws ecs update-service",
            "aws ecs run-task",
            "aws ecr",
            "aws secretsmanager",
            "terraform apply",
            "gh workflow run",
            "gh pr merge",
            "curl ",
            "id-token: write",
        ):
            self.assertNotIn(disallowed, w.lower())
        self.assertNotIn("aws-actions/", w)
        self.assertNotIn("AI_RESEARCH_WORKER_TOKEN", w)
        self.assertIn("if: always()", w)

    def test_v73_v86_version_sha_and_run_metadata_constrained(self):
        s = self.source
        self.assertIn('"v4-paper-runtime-posture.v1"', s)
        self.assertIn('"v4-worker-private-tls-topology.v1"', s)
        self.assertIn('record.get("event") == "workflow_dispatch"', s)
        self.assertIn('record.get("head_branch") == "dev"', s)
        self.assertIn('record.get("head_sha") == sha', s)
        self.assertIn('record.get("conclusion") == "success"', s)
        self.assertIn('record["path"].split("@", 1)[0] == workflow', s)
        self.assertIn('MAX_AGE = timedelta(minutes=90)', s)
        self.assertIn('MAX_PAIR_SKEW = timedelta(minutes=25)', s)
        self.assertIn('backend.get("research_opt_ins_disabled") is True', s)
        self.assertIn('network.get("worker_running") is False', s)

    def test_never_claim_tls_handshake_models_or_capital(self):
        s = self.source
        for snippet in (
            '"actual_java_tls_handshake_verified": False',
            '"worker_model_or_secret_identity_verified": False',
            '"independent_source_rights_verified": False',
            '"paper_shadow_soak_accepted": False',
            '"broker_reconciliation_accepted": False',
            '"live_execution_enabled": False',
            '"can_authorize_capital": False',
            '"verdict": "BLOCKED"',
            'sorted(blockers)',
        ):
            self.assertIn(snippet, s)
        self.assertNotIn('"can_authorize_capital": True', s)
        self.assertNotIn("urllib.request", s)


if __name__ == "__main__":
    unittest.main()
