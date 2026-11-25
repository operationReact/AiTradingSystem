"""Explicit dev-branch request file is the only non-manual launch seam."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "paper_lightsail_request", ROOT / "infra/lightsail/verify_request.py"
)
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def request(kind="bootstrap", op="preflight", confirmation=""):
    return {
        "kind": kind, "target": gate.INSTANCE,
        "operation": op, "confirmation": confirmation,
    }


class LightsailExplicitDevRequests(unittest.TestCase):
    def test_non_billable_preflight_and_exact_charge_permission(self):
        self.assertEqual(gate.validate(request(), "bootstrap"), ("preflight", ""))
        self.assertEqual(
            gate.validate(request(op="provision", confirmation="CREATE_PAPER_4GB"),
                          "bootstrap"),
            ("provision", "CREATE_PAPER_4GB"),
        )

    def test_deploy_requires_exact_explicit_paper_only_ack(self):
        self.assertEqual(
            gate.validate(request("deploy", "deploy", "DEPLOY_PAPER_LOCAL_ONLY"), "deploy"),
            ("deploy", "DEPLOY_PAPER_LOCAL_ONLY"),
        )

    def test_rejects_missing_ambiguous_or_cross_project_authority(self):
        cases = [
            (request("deploy", "deploy", "DEPLOY_PAPER_LOCAL_ONLY"), "bootstrap"),
            (request(op="provision", confirmation=""), "bootstrap"),
            (request(op="provision", confirmation="create_paper_4gb"), "bootstrap"),
            (request(op="preflight", confirmation="CREATE_PAPER_4GB"), "bootstrap"),
            (request("deploy", "deploy", "CREATE_PAPER_4GB"), "deploy"),
            (request("deploy", "provision", "DEPLOY_PAPER_LOCAL_ONLY"), "deploy"),
            (request("bootstrap", "deploy", "DEPLOY_PAPER_LOCAL_ONLY"), "bootstrap"),
            (request(kind="live"), "bootstrap"),
            (dict(request(), target="alpha-vidhyarthi-paper"), "bootstrap"),
            (dict(request(), extra="unauthorized"), "bootstrap"),
            ({k: v for k, v in request().items() if k != "target"}, "bootstrap"),
            (dict(request(), confirmation=None), "bootstrap"),
            ([], "bootstrap"),
            (None, "bootstrap"),
        ]
        for payload, kind in cases:
            with self.subTest(payload=payload, kind=kind), self.assertRaises(ValueError):
                gate.validate(payload, kind)

    def test_only_exact_dev_branch_request_paths_are_workflow_triggers(self):
        for workflow, allowed in (
            ("lightsail-paper-bootstrap.yml", "bootstrap.request.json"),
            ("lightsail-paper-app-deploy.yml", "deploy.request.json"),
        ):
            src = (ROOT / "docs/operations/archived-actions" / workflow).read_text()
            self.assertIn("branches: [dev]", src)
            self.assertIn(f"infra/lightsail/requests/{allowed}", src)
            self.assertIn("--github-output", src)
            self.assertIn("--kind", src)
            self.assertIn("steps.approval.outputs", src)
            self.assertNotIn("\n    branches: [main]", src)
            self.assertNotIn("workflow_run:", src)
            self.assertNotIn("pull_request_target:", src)
    def test_committed_requests_are_valid_when_explicitly_present(self):
        # Once a user intentionally commits a read-only preflight or later
        # approved PAPER request on dev, PR/push CI must stay green without
        # weakening the exact request constraints.
        root = ROOT / "infra/lightsail/requests"
        if not root.exists():
            return
        self.assertFalse(root.is_symlink())
        self.assertTrue(root.is_dir())
        expected = {
            "bootstrap.request.json": "bootstrap",
            "deploy.request.json": "deploy",
        }
        for entry in root.iterdir():
            self.assertIn(entry.name, expected)
            self.assertTrue(entry.is_file())
            self.assertFalse(entry.is_symlink())
            self.assertLessEqual(entry.stat().st_size, gate.MAX_BYTES)
            payload = json.loads(entry.read_text(encoding="utf-8"))
            operation, confirmation = gate.validate(payload, expected[entry.name])
            self.assertEqual(payload["operation"], operation)
            self.assertEqual(payload["confirmation"], confirmation)
            self.assertNotIn("secret", entry.read_text(encoding="utf-8").lower())
            self.assertNotIn("token", entry.read_text(encoding="utf-8").lower())

    def test_preflight_file_can_be_committed_without_invalidating_ci(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            requests_dir = root / "infra/lightsail/requests"
            requests_dir.mkdir(parents=True)
            f = requests_dir / "bootstrap.request.json"
            f.write_text(json.dumps(request()), encoding="utf-8")
            with patch(f"{__name__}.ROOT", root):
                self.test_committed_requests_are_valid_when_explicitly_present()

            # Provision and app deploy are individually bound to their exact
            # confirmations, even when the files exist in the checked-out tree.
            f.write_text(json.dumps(request(
                op="provision", confirmation="CREATE_PAPER_4GB")), encoding="utf-8")
            (requests_dir / "deploy.request.json").write_text(json.dumps(
                request("deploy", "deploy", "DEPLOY_PAPER_LOCAL_ONLY")),
                encoding="utf-8")
            with patch(f"{__name__}.ROOT", root):
                self.test_committed_requests_are_valid_when_explicitly_present()

            f.write_text(json.dumps(request(op="provision", confirmation="")),
                         encoding="utf-8")
            with patch(f"{__name__}.ROOT", root), self.assertRaises(ValueError):
                self.test_committed_requests_are_valid_when_explicitly_present()

            f.unlink()
            (requests_dir / "unreviewed.json").write_text("{}", encoding="utf-8")
            with patch(f"{__name__}.ROOT", root), self.assertRaises(AssertionError):
                self.test_committed_requests_are_valid_when_explicitly_present()

    def test_example_json_contract_requires_no_secrets(self):
        for op in ("preflight", "provision"):
            payload = request(op=op, confirmation="CREATE_PAPER_4GB" if op == "provision" else "")
            encoded = json.dumps(payload)
            self.assertLess(len(encoded), gate.MAX_BYTES)
            self.assertNotIn("secret", encoded.lower())
            self.assertEqual(gate.validate(json.loads(encoded), "bootstrap")[0], op)


if __name__ == "__main__":
    unittest.main()
