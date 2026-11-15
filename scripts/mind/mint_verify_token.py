"""Mint a short-lived Cognito ACCESS token for the Mind live-verification customer.

The account edge and backend require token_use == "access" with the app client_id, so an
ID token is never selected. Flow:

1. InitiateAuth (USER_PASSWORD_AUTH) with the verification password.
2. If Cognito returns NEW_PASSWORD_REQUIRED (first login or a forced reset), answer it with
   RespondToAuthChallenge using the session Cognito returned and the new-password secret.
3. Any other challenge (MFA, device, custom) or an authentication error fails closed with a
   sanitized code. MFA and verification requirements are never bypassed.

The access token is written only to the path given. Passwords, sessions, payloads and tokens
are never printed. Errors are reported as a code only.
"""
import json
import re
import sys
import urllib.error
import urllib.request

COGNITO_URL = "https://cognito-idp.ap-south-1.amazonaws.com/"
SUPPORTED_CHALLENGE = "NEW_PASSWORD_REQUIRED"


class AuthError(Exception):
    """Carries a sanitized code only. Never includes credentials, sessions or Cognito messages."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def extract_access_token(payload):
    """Return the AccessToken from a Cognito result. Never falls back to IdToken."""
    result = payload.get("AuthenticationResult") if isinstance(payload, dict) else None
    token = result.get("AccessToken") if isinstance(result, dict) else None
    if not isinstance(token, str) or token.count(".") != 2 or len(token) < 40:
        raise AuthError("NO_ACCESS_TOKEN")
    return token


def _post(action, body, opener=urllib.request.urlopen):
    req = urllib.request.Request(COGNITO_URL, data=json.dumps(body).encode(), method="POST", headers={
        "X-Amz-Target": "AWSCognitoIdentityProviderService." + action,
        "Content-Type": "application/x-amz-json-1.1",
    })
    try:
        with opener(req, timeout=20) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as err:
        # Cognito error bodies carry a __type code. The message is deliberately discarded.
        try:
            code = json.load(err).get("__type", "COGNITO_ERROR")
        except Exception:
            code = "COGNITO_ERROR"
        raise AuthError(_safe_code(code))


def _safe_code(code):
    return code if isinstance(code, str) and code.replace("Exception", "").isalpha() else "COGNITO_ERROR"


def authenticate(client_id, username, password, new_password, post=_post):
    """Return an access token, answering NEW_PASSWORD_REQUIRED when a new password is supplied."""
    if not (client_id and username and password):
        raise AuthError("MISSING_CONFIGURATION")
    first = post("InitiateAuth", {
        "AuthFlow": "USER_PASSWORD_AUTH",
        "ClientId": client_id,
        "AuthParameters": {"USERNAME": username, "PASSWORD": password},
    })
    if "AuthenticationResult" in first:
        return extract_access_token(first)

    challenge = first.get("ChallengeName") if isinstance(first, dict) else None
    if challenge is None:
        raise AuthError("NO_ACCESS_TOKEN")
    if challenge != SUPPORTED_CHALLENGE:
        # Only real Cognito challenge names (uppercase letters and underscores) are echoed.
        safe = challenge if isinstance(challenge, str) and re.fullmatch(r"[A-Z_]{1,40}", challenge) else "UNKNOWN"
        raise AuthError("UNSUPPORTED_CHALLENGE_" + safe)
    if not new_password:
        raise AuthError("NEW_PASSWORD_REQUIRED_NO_NEW_PASSWORD_SECRET")
    session = first.get("Session")
    if not isinstance(session, str) or not session:
        raise AuthError("MISSING_CHALLENGE_SESSION")

    second = post("RespondToAuthChallenge", {
        "ClientId": client_id,
        "ChallengeName": SUPPORTED_CHALLENGE,
        "Session": session,
        "ChallengeResponses": {"USERNAME": username, "NEW_PASSWORD": new_password},
    })
    if "AuthenticationResult" not in second:
        # A further challenge (for example MFA) is not handled and is never bypassed.
        raise AuthError("UNEXPECTED_CHALLENGE_AFTER_PASSWORD_CHANGE")
    return extract_access_token(second)


def _read(path):
    with open(path) as fh:
        return fh.read()


def main(argv):
    """Arguments: client_id username password_file new_password_file out_path.

    new_password_file may be empty when no password change is configured. Files are read
    exactly, without stripping, so the secret reaches Cognito unchanged.
    """
    if len(argv) != 6:
        print("AUTH_FAILED: BAD_ARGUMENTS", file=sys.stderr)
        return 2
    client_id, username, password_file, new_password_file, out_path = argv[1:6]
    try:
        password = _read(password_file)
        new_password = _read(new_password_file) if new_password_file else ""
        token = authenticate(client_id, username, password, new_password)
    except AuthError as err:
        # Code only: no credentials, sessions, payloads or tokens.
        print("AUTH_FAILED: " + err.code, file=sys.stderr)
        return 2
    except Exception:
        print("AUTH_FAILED: TRANSPORT_OR_LOCAL_ERROR", file=sys.stderr)
        return 2
    with open(out_path, "w") as fh:
        fh.write(token)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
