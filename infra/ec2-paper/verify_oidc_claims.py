#!/usr/bin/env python3
"""Verify only public GitHub OIDC identity claims before AWS STS.

No JWT or request secret is printed, stored, persisted or sent to AWS.
An exact audience/subject match isolates subsequent STS denial to AWS IAM
trust/provider/account restrictions rather than a GitHub branch mismatch.
"""
from __future__ import annotations

import base64
import json
import os
import re
import sys
import urllib.request
from urllib.parse import urlencode

EXPECTED = {
    "iss": "https://token.actions.githubusercontent.com",
    "aud": "sts.amazonaws.com",
    "sub": "repo:operationReact/AiTradingSystem:ref:refs/heads/dev",
    "repository": "operationReact/AiTradingSystem",
    "ref": "refs/heads/dev",
    "event_name": "push",
}


def verify(claims: object) -> None:
    if not isinstance(claims, dict):
        raise ValueError("OIDC claims are not JSON object")
    invalid = [field for field, value in EXPECTED.items()
               if claims.get(field) != value]
    if invalid:
        # Show only the mismatching claim names, not JWT or untrusted values.
        raise ValueError("OIDC claims mismatched: " + ",".join(invalid))


def decode_claims(token: str) -> dict:
    if not isinstance(token, str) or len(token) > 16000:
        raise ValueError("invalid OIDC token length")
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("not a JWT")
    raw = parts[1]
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,12000}", raw):
        raise ValueError("invalid JWT claim encoding")
    claims = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
    if not isinstance(claims, dict):
        raise ValueError("JWT claims are not JSON object")
    return claims


def main() -> int:
    try:
        token_url = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"]
        request_token = os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]
        if not token_url.startswith("https://") or not request_token:
            raise ValueError("GitHub OIDC runtime not enabled")
        separator = "&" if "?" in token_url else "?"
        url = token_url + separator + urlencode({"audience": "sts.amazonaws.com"})
        request = urllib.request.Request(
            url, headers={"Authorization": "Bearer " + request_token})
        with urllib.request.urlopen(request, timeout=20) as response:
            data = json.load(response)
        if not isinstance(data, dict):
            raise ValueError("OIDC response was not object")
        claims = decode_claims(data.get("value"))
        verify(claims)
    except (ValueError, TypeError, KeyError, OSError, json.JSONDecodeError,
            UnicodeDecodeError):
        print("GitHub OIDC claim preflight BLOCKED (sanitized)", file=sys.stderr)
        return 2
    print("GitHub OIDC iss/aud/sub/repository/ref/push all exactly match dev IAM trust")
    print("If AWS STS still denies role assumption, check AWS IAM provider/trust/account policy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
