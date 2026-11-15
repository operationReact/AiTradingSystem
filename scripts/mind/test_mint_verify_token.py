import unittest

import mint_verify_token as m

ACCESS = "eyJhbGciOiJSUzI1NiJ9." + "a" * 40 + ".sig"
ID = "eyJhbGciOiJSUzI1NiJ9." + "b" * 40 + ".sig"


class MintVerifyTokenTest(unittest.TestCase):
    def test_selects_access_token(self):
        self.assertEqual(m.extract_access_token({"AuthenticationResult": {"AccessToken": ACCESS, "IdToken": ID}}), ACCESS)

    def test_never_falls_back_to_id_token(self):
        with self.assertRaises(ValueError):
            m.extract_access_token({"AuthenticationResult": {"IdToken": ID}})

    def test_rejects_malformed_or_missing_results(self):
        for bad in ({}, {"AuthenticationResult": None}, {"AuthenticationResult": {"AccessToken": "short"}},
                    {"AuthenticationResult": {"AccessToken": ACCESS.replace(".", "", 1)}}, None):
            with self.assertRaises(ValueError, msg=repr(bad)):
                m.extract_access_token(bad)


if __name__ == "__main__":
    unittest.main()


class WorkflowRegressionTest(unittest.TestCase):
    """The live verification workflow must mint an ACCESS token through the reviewed script."""

    def test_workflow_uses_reviewed_script_and_never_id_token(self):
        from pathlib import Path
        text = (Path(__file__).resolve().parents[2] / ".github/workflows/mind-live-verify.yml").read_text()
        self.assertNotIn("IdToken", text)
        self.assertIn("scripts/mind/mint_verify_token.py", text)
        self.assertIn("actions/checkout@v4", text)
