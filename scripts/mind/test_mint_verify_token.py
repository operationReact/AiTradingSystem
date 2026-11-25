import io
import json
import unittest
import urllib.error
from pathlib import Path

import mint_verify_token as m

ACCESS = "eyJhbGciOiJSUzI1NiJ9." + "a" * 40 + ".sig"
ID = "eyJhbGciOiJSUzI1NiJ9." + "b" * 40 + ".sig"
TEMP_PW = "TempPassw0rd-DO-NOT-USE"
NEW_PW = "PermPassw0rd-DO-NOT-USE"
SESSION = "session-token-DO-NOT-LOG"


class Cognito:
    """Scripted Cognito: answers each action from a queue and records the calls."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def __call__(self, action, body):
        self.calls.append((action, body))
        item = self.responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


def auth_error(code):
    return m.AuthError(code)


class AuthenticateTest(unittest.TestCase):
    def test_successful_login_returns_the_access_token_without_a_challenge(self):
        cognito = Cognito({"AuthenticationResult": {"AccessToken": ACCESS, "IdToken": ID}})
        self.assertEqual(m.authenticate("client", "user", TEMP_PW, NEW_PW, post=cognito), ACCESS)
        self.assertEqual([a for a, _ in cognito.calls], ["InitiateAuth"])

    def test_required_password_change_answers_the_challenge_with_the_session(self):
        cognito = Cognito(
            {"ChallengeName": "NEW_PASSWORD_REQUIRED", "Session": SESSION},
            {"AuthenticationResult": {"AccessToken": ACCESS}},
        )
        self.assertEqual(m.authenticate("client", "user", TEMP_PW, NEW_PW, post=cognito), ACCESS)
        action, body = cognito.calls[1]
        self.assertEqual(action, "RespondToAuthChallenge")
        self.assertEqual(body["ChallengeName"], "NEW_PASSWORD_REQUIRED")
        self.assertEqual(body["Session"], SESSION)
        self.assertEqual(body["ChallengeResponses"], {"USERNAME": "user", "NEW_PASSWORD": NEW_PW})

    def test_required_password_change_without_a_new_password_fails_before_any_response(self):
        cognito = Cognito({"ChallengeName": "NEW_PASSWORD_REQUIRED", "Session": SESSION})
        with self.assertRaises(m.AuthError) as ctx:
            m.authenticate("client", "user", TEMP_PW, "", post=cognito)
        self.assertEqual(ctx.exception.code, "NEW_PASSWORD_REQUIRED_NO_NEW_PASSWORD_SECRET")
        self.assertEqual(len(cognito.calls), 1)

    def test_invalid_credentials_surface_only_the_cognito_error_type(self):
        cognito = Cognito(auth_error("NotAuthorizedException"))
        with self.assertRaises(m.AuthError) as ctx:
            m.authenticate("client", "user", "wrong", NEW_PW, post=cognito)
        self.assertEqual(ctx.exception.code, "NotAuthorizedException")
        self.assertEqual(len(cognito.calls), 1)

    def test_missing_configuration_never_calls_cognito(self):
        for args in (("", "user", TEMP_PW), ("client", "", TEMP_PW), ("client", "user", "")):
            cognito = Cognito()
            with self.assertRaises(m.AuthError) as ctx:
                m.authenticate(*args, NEW_PW, post=cognito)
            self.assertEqual(ctx.exception.code, "MISSING_CONFIGURATION")
            self.assertEqual(cognito.calls, [])

    def test_mfa_or_other_challenges_fail_closed_and_are_never_bypassed(self):
        for challenge in ("SMS_MFA", "SOFTWARE_TOKEN_MFA", "MFA_SETUP", "DEVICE_SRP_AUTH"):
            cognito = Cognito({"ChallengeName": challenge, "Session": SESSION})
            with self.assertRaises(m.AuthError) as ctx:
                m.authenticate("client", "user", TEMP_PW, NEW_PW, post=cognito)
            self.assertEqual(ctx.exception.code, "UNSUPPORTED_CHALLENGE_" + challenge)
            self.assertEqual(len(cognito.calls), 1)

    def test_a_second_challenge_after_the_password_change_fails_closed(self):
        cognito = Cognito(
            {"ChallengeName": "NEW_PASSWORD_REQUIRED", "Session": SESSION},
            {"ChallengeName": "SMS_MFA", "Session": SESSION},
        )
        with self.assertRaises(m.AuthError) as ctx:
            m.authenticate("client", "user", TEMP_PW, NEW_PW, post=cognito)
        self.assertEqual(ctx.exception.code, "UNEXPECTED_CHALLENGE_AFTER_PASSWORD_CHANGE")

    def test_a_challenge_without_a_session_fails_closed(self):
        cognito = Cognito({"ChallengeName": "NEW_PASSWORD_REQUIRED"})
        with self.assertRaises(m.AuthError) as ctx:
            m.authenticate("client", "user", TEMP_PW, NEW_PW, post=cognito)
        self.assertEqual(ctx.exception.code, "MISSING_CHALLENGE_SESSION")

    def test_an_empty_result_never_falls_back_to_an_id_token(self):
        cognito = Cognito({"AuthenticationResult": {"IdToken": ID}})
        with self.assertRaises(m.AuthError) as ctx:
            m.authenticate("client", "user", TEMP_PW, NEW_PW, post=cognito)
        self.assertEqual(ctx.exception.code, "NO_ACCESS_TOKEN")


class ErrorSanitizationTest(unittest.TestCase):
    def test_cognito_error_bodies_are_reduced_to_their_type(self):
        body = json.dumps({"__type": "NotAuthorizedException",
                           "message": "Incorrect username or password for " + TEMP_PW}).encode()

        def opener(req, timeout):
            raise urllib.error.HTTPError(req.full_url, 400, "Bad Request", {}, io.BytesIO(body))

        with self.assertRaises(m.AuthError) as ctx:
            m._post("InitiateAuth", {}, opener=opener)
        self.assertEqual(ctx.exception.code, "NotAuthorizedException")
        self.assertNotIn(TEMP_PW, ctx.exception.code)

    def test_unparseable_error_bodies_become_a_generic_code(self):
        def opener(req, timeout):
            raise urllib.error.HTTPError(req.full_url, 500, "Oops", {}, io.BytesIO(b"<html>"))

        with self.assertRaises(m.AuthError) as ctx:
            m._post("InitiateAuth", {}, opener=opener)
        self.assertEqual(ctx.exception.code, "COGNITO_ERROR")

    def test_unusual_challenge_names_are_sanitized(self):
        cognito = Cognito({"ChallengeName": "weird name; " + TEMP_PW, "Session": SESSION})
        with self.assertRaises(m.AuthError) as ctx:
            m.authenticate("client", "user", TEMP_PW, NEW_PW, post=cognito)
        self.assertNotIn(TEMP_PW, ctx.exception.code)
        self.assertNotIn(" ", ctx.exception.code)


class MainTest(unittest.TestCase):
    def test_bad_arguments_exit_without_printing_secrets(self):
        import contextlib
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(m.main(["script", "only-one-arg"]), 2)
        self.assertEqual(err.getvalue().strip(), "AUTH_FAILED: BAD_ARGUMENTS")

    def test_missing_client_configuration_is_reported_as_a_code_only(self):
        import contextlib
        import os
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            pw, new, out = (os.path.join(d, n) for n in ("pw", "new", "out"))
            Path(pw).write_text(TEMP_PW)
            Path(new).write_text(NEW_PW)
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                self.assertEqual(m.main(["script", "", "user", pw, new, out]), 2)
            self.assertEqual(err.getvalue().strip(), "AUTH_FAILED: MISSING_CONFIGURATION")
            self.assertFalse(os.path.exists(out))
            self.assertNotIn(TEMP_PW, err.getvalue())


class WorkflowRegressionTest(unittest.TestCase):
    def test_workflow_uses_the_reviewed_script_with_the_new_password_secret(self):
        text = (Path(__file__).resolve().parents[2] / ".github/workflows/mind-live-verify.yml").read_text()
        self.assertNotIn("IdToken", text)
        self.assertIn("scripts/mind/mint_verify_token.py", text)
        self.assertIn("MINDTICK_VERIFY_NEW_PASSWORD", text)
        self.assertIn("actions/checkout@v4", text)


if __name__ == "__main__":
    unittest.main()
