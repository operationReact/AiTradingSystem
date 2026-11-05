"""V90 safety checks for ephemeral CI-only research image release Docker smoke.

This unittest does not require Docker, ML dependencies or AWS credentials.
The separate V90 GitHub Actions job runs the actual Docker build/HTTP probe.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "intelligence/tests"))
import ci_research_release_container_smoke as smoke  # noqa: E402


class SyntheticResearchImageContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = (ROOT / ".github/workflows/v4-synthetic-research-container-smoke.yml").read_text()
        cls.source = (ROOT / "intelligence/tests/ci_research_release_container_smoke.py").read_text()
        cls.dockerfile = (ROOT / "intelligence/Dockerfile.research-release").read_text()

    def test_runner_binds_exact_committed_source_to_both_real_models(self):
        for item in (
            'git rev-parse HEAD', "--code-sha \"$GITHUB_SHA\"",
            "research_image_context verify", "docker build --no-cache",
            "RESEARCH_SOURCE_SHA=$GITHUB_SHA", "--file",
            "ci_research_release_container_smoke.py prepare",
            "MODEL_ID", "SHADOW_ID",
        ):
            self.assertIn(item, self.workflow + self.source)
        self.assertIn("train_catboost_challenger(", self.source)
        self.assertIn("train_lightgbm_challenger(", self.source)
        self.assertIn("stage_bundle(manifest, bundle, sha)", self.source)
        self.assertIn("prepare_context(repo, bundle, context, sha)", self.source)
        self.assertIn("research_release_bundle verify", self.dockerfile)

    def test_no_registry_aws_token_or_networked_runtime(self):
        s = self.workflow
        self.assertIn("on:\n  pull_request:", s)
        self.assertIn("permissions:\n  contents: read", s)
        for permitted_safety in (
            "--network none", "--cap-drop ALL", "--security-opt no-new-privileges",
            "--read-only", "AI_RESEARCH_WORKER_TOKEN=$synthetic_token",
            "docker rm -f", "docker image rm", "rm -rf -- \"$work\"",
            "trap cleanup EXIT", "--tmpfs /tmp:rw,noexec,nosuid,nodev",
            "V78 worker bootstrap BLOCKED (sanitized)",
            "test \"$denial_rc\" -eq 2",
        ):
            self.assertIn(permitted_safety, s)
        for forbidden in (
            "id-token: write", "aws-actions/configure-aws-credentials",
            "aws ecs", "aws ecr", "aws secretsmanager", "docker login",
            "docker push", "aws iam", "terraform apply", "actions/upload-artifact",
            "secrets.", "workflow_run:", "--publish", "--network host",
        ):
            self.assertNotIn(forbidden, s.lower())
        self.assertNotIn("Ed25519PrivateKey", self.source)

    def test_local_probe_rejects_unauthenticated_calls_and_authority(self):
        script = smoke.LOCAL_PROBE
        for required in (
            "http://127.0.0.1:8090", "X-Research-Worker-Token",
            'token="wrong"', '== 401', '== 200', "VerifiedWorkerRoster",
            "inspect_worker_roster(MANIFEST)", "load_allowlisted_model",
            "CATBOOST", "LIGHTGBM", "BoundedInferenceResponse",
            "can_authorize_capital is False", "model_promotion_allowed is False",
        ):
            self.assertIn(required, script)
        self.assertNotIn("api.angelbroking", script)
        self.assertNotIn("https://", script)
        self.assertNotIn("print(TOKEN)", script)

    def test_probe_invokes_only_named_container_python_stdin(self):
        fake = SimpleNamespace(returncode=0, stdout=smoke.PROBE_SUCCESS + "\n", stderr="")
        with patch.object(smoke.subprocess, "run", return_value=fake) as run:
            smoke.run_probe("a" * 64)
        args, opts = run.call_args
        self.assertEqual(
            args[0], ["docker", "exec", "-i", "a" * 64, "python", "-"]
        )
        self.assertTrue(opts["capture_output"])
        self.assertTrue(opts["text"])
        self.assertEqual(opts["input"], smoke.LOCAL_PROBE)
        self.assertLessEqual(opts["timeout"], 90)

        with self.assertRaises(ValueError):
            smoke.run_probe("v90-name;docker-push-not-allowed")
        with patch.object(smoke.subprocess, "run", return_value=SimpleNamespace(
            returncode=0, stdout="unexpected", stderr="PRIVATE TOKEN",
        )):
            with self.assertRaises(ValueError) as context:
                smoke.run_probe("b" * 64)
        self.assertNotIn("PRIVATE TOKEN", str(context.exception))

    def test_no_phantom_production_approval(self):
        self.assertNotIn("production_signing_key", self.source)
        self.assertNotIn("import boto3", self.source)
        self.assertNotIn("can_authorize_capital=True", self.source)
        self.assertIn("release and capital NOT authorized", self.source)
        self.assertIn("V90 disposable research image smoke BLOCKED", self.source)


if __name__ == "__main__":
    unittest.main()
