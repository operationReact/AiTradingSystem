"""V89 synthetic no-AWS checks of GitHub inspection role declarations."""
from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from v4_inspection_role_variables_preflight import ACCOUNT, ROLES, evaluate  # noqa: E402

SHA = "a" * 40


def variables():
    return {
        name: f"arn:aws:iam::{ACCOUNT}:role/{role_name}"
        for name, role_name in ROLES.values()
    }


def input_data():
    return {
        "values": variables(),
        "repository": "operationReact/AiTradingSystem",
        "ref": "refs/heads/dev",
        "sha": SHA,
    }


class GitHubReadOnlyRolePreflightTest(unittest.TestCase):
    def assert_blocked(self, data, code):
        verdict = evaluate(**data)
        self.assertEqual(verdict["verdict"], "BLOCKED")
        self.assertIn(code, verdict["blocker_codes"])
        self.assertFalse(verdict["can_authorize_capital"])
        self.assertFalse(verdict["github_role_variable_format_only_verified"])

    def test_only_matching_separate_declared_roles_pass_format_gate(self):
        f = input_data()
        before = copy.deepcopy(f)
        result = evaluate(**f)
        self.assertEqual(f, before)
        self.assertTrue(result["github_role_variable_format_only_verified"])
        self.assertEqual(result["verdict"], "GITHUB_INSPECTION_ROLE_VARIABLE_FORMAT_ONLY")
        for field in (
            "aws_iam_role_existence_verified", "oidc_assume_role_succeeded",
            "aws_iam_permissions_verified", "deployed_paper_backend_verified",
            "private_https_network_verified", "worker_model_artifacts_verified",
            "paper_shadow_soak_accepted", "live_execution_enabled",
            "can_authorize_capital",
        ):
            self.assertFalse(result[field])
        output = json.dumps(result)
        for declared in variables().values():
            self.assertNotIn(declared, output)

    def test_missing_swapped_wrong_account_or_unexpected_roles_rejected(self):
        for phase, (var, _) in ROLES.items():
            f = input_data()
            f["values"][var] = None
            self.assert_blocked(f, f"{phase}_READONLY_ROLE_VARIABLE_MISSING_OR_UNEXPECTED")
            f = input_data()
            f["values"][var] = ""
            self.assert_blocked(f, f"{phase}_READONLY_ROLE_VARIABLE_MISSING_OR_UNEXPECTED")
            f = input_data()
            f["values"][var] = f["values"][var].replace(ACCOUNT, "000000000000")
            self.assert_blocked(f, f"{phase}_READONLY_ROLE_VARIABLE_MISSING_OR_UNEXPECTED")
            f = input_data()
            f["values"][var] = " " + f["values"][var]
            self.assert_blocked(f, f"{phase}_READONLY_ROLE_VARIABLE_MISSING_OR_UNEXPECTED")
            f = input_data()
            f["values"][var] = f["values"][var].replace("readonly-dev", "admin")
            self.assert_blocked(f, f"{phase}_READONLY_ROLE_VARIABLE_MISSING_OR_UNEXPECTED")
        f = input_data()
        a, b = list(ROLES.values())[:2]
        f["values"][a[0]], f["values"][b[0]] = f["values"][b[0]], f["values"][a[0]]
        self.assert_blocked(f, "V73_READONLY_ROLE_VARIABLE_MISSING_OR_UNEXPECTED")

    def test_non_dev_fork_and_invalid_sha_block(self):
        for key, value in (
            ("repository", "someone-else/AiTradingSystem"),
            ("ref", "refs/heads/main"),
            ("ref", "refs/pull/442/merge"),
            ("sha", "a" * 39),
            ("sha", "z" * 40),
        ):
            f = input_data()
            f[key] = value
            self.assert_blocked(f, "NON_DEV_OR_UNTRUSTED_RUN_SOURCE")

    def test_github_only_manual_workflow_never_assumes_aws_role(self):
        workflow = (ROOT / ".github/workflows/v4-inspection-role-variable-preflight.yml").read_text()
        self.assertIn("on:\n  workflow_dispatch:", workflow)
        self.assertIn("permissions:\n  contents: read", workflow)
        for name, _ in ROLES.values():
            self.assertIn("vars." + name, workflow)
        for forbidden in (
            "id-token: write", "configure-aws-credentials", "aws iam",
            "aws sts", "aws ecs", "aws ecr", "terraform apply",
            "GH_TOKEN", "AWS_SECRET_ACCESS_KEY", "secrets.",
        ):
            self.assertNotIn(forbidden, workflow)
        self.assertIn("if: always()", workflow)
        self.assertIn("retention-days: 7", workflow)


if __name__ == "__main__":
    unittest.main()
