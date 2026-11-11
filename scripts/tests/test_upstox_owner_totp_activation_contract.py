"""Offline contract checks for protected Upstox owner TOTP activation.
This test never reads AWS SSM and never calls a broker API.
"""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "infra/ec2-paper/upstox_owner_totp_activate.sh"
WORKFLOW = ROOT / ".github/workflows/ec2-paper-upstox-owner-totp-activate.yml"


class UpstoxOwnerTotpActivationContractTest(unittest.TestCase):
    def test_script_parses_and_rejects_bogus_release_off_host(self):
        result = subprocess.run(["bash", "-n", str(SCRIPT)],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        result = subprocess.run(["bash", str(SCRIPT), "invalid-release"],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("MINTTICK_UPSTOX_TOTP_RESULT:BAD_RELEASE_SHA",
                      result.stdout)
        self.assertNotIn("secret", result.stdout.lower())

    def test_only_fixed_owner_ssm_params_with_no_stdout(self):
        s = SCRIPT.read_text()
        self.assertIn("'/minttick/upstox/login-pin'", s)
        self.assertIn("'/minttick/upstox/totp-secret'", s)
        self.assertIn("'--with-decryption'", s)
        self.assertIn("capture_output=True", s)
        self.assertIn("'SecureString'", s)
        self.assertIn("MINTTICK_ANGEL_ONE_OWNER_SUB", s)
        self.assertIn("OWNER_OR_OAUTH_NOT_READY", s)
        self.assertIn("SSM_OWNER_SECRETS_UNAVAILABLE", s)
        self.assertIn("MINTTICK_UPSTOX_OWNER_TOTP_ENABLED=true", s)
        self.assertIn("HOST_ACTIVATED_READ_ONLY", s)
        self.assertIn("HOST_ALREADY_ACTIVE_READ_ONLY", s)
        self.assertIn("RESTART_FAILED_ROLLED_BACK", s)
        self.assertIn("FLAG_NOT_ACTIVE_ROLLED_BACK", s)
        self.assertIn("TRADING_MODE: PAPER", s)
        self.assertNotIn("/v3/order/place", s)
        self.assertNotIn("aws iam attach-role-policy", s)
        self.assertNotIn("getParameterByPath", s)

    def test_workflow_requires_one_file_request_and_locked_ec2(self):
        s = WORKFLOW.read_text()
        self.assertIn("branches: [dev]", s)
        self.assertIn("test \"$(git diff-tree --no-commit-id --name-only -r HEAD)\" = \"$request\"", s)
        self.assertIn("verify_ssm_release.verify_aws()", s)
        self.assertIn("expected_release_sha", s)
        self.assertIn("git','merge-base','--is-ancestor", s)
        self.assertIn("3b67b1accf3a870fa28d83b772af83be4b9afe72", s)
        self.assertIn("OWNER_OR_OAUTH_NOT_READY", s)
        self.assertIn("HOST_ALREADY_ACTIVE_READ_ONLY", s)
        self.assertIn("MINTTICK_UPSTOX_TOTP_TRANSPORT", s)
        self.assertNotIn("workflow_dispatch:", s)
        self.assertNotIn("/v3/order/place", s)


if __name__ == "__main__":
    unittest.main()
