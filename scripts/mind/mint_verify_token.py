"""Mint a short-lived Cognito ACCESS token for the Mind live-verification customer.

The account edge and backend require token_use == "access" with the app client_id.
An ID token is rejected by design, so this script never selects IdToken. The token is
written only to the path given, never printed or logged.
"""
import json
import sys
import urllib.request

COGNITO_URL = "https://cognito-idp.ap-south-1.amazonaws.com/"


def extract_access_token(payload):
    """Return the AccessToken from an InitiateAuth response, or raise. Never falls back to IdToken."""
    result = payload.get("AuthenticationResult") if isinstance(payload, dict) else None
    token = result.get("AccessToken") if isinstance(result, dict) else None
    if not isinstance(token, str) or token.count(".") != 2 or len(token) < 40:
        raise ValueError("Cognito did not return an access token")
    return token


def initiate_auth(client_id, username, password, opener=urllib.request.urlopen):
    body = json.dumps({
        "AuthFlow": "USER_PASSWORD_AUTH",
        "ClientId": client_id,
        "AuthParameters": {"USERNAME": username, "PASSWORD": password},
    }).encode()
    req = urllib.request.Request(COGNITO_URL, data=body, method="POST", headers={
        "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth",
        "Content-Type": "application/x-amz-json-1.1",
    })
    with opener(req, timeout=20) as resp:
        return json.load(resp)


def main(argv):
    client_id, username, password_file, out_path = argv[1], argv[2], argv[3], argv[4]
    with open(password_file) as fh:
        password = fh.read()
    token = extract_access_token(initiate_auth(client_id, username, password))
    with open(out_path, "w") as fh:
        fh.write(token)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
