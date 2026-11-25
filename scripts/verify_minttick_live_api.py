#!/usr/bin/env python3
"""Fail closed before publishing an APK configured for a physical device."""
import json
import os
import ssl
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def verify_api(base_url: str) -> None:
    parsed = urlparse(base_url.strip())
    blocked = {"localhost", "127.0.0.1", "0.0.0.0", "10.0.2.2", "::1"}
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/")
            or parsed.hostname.lower() in blocked or parsed.hostname.lower().endswith((".invalid", ".local", ".localhost"))):
        raise ValueError("Live APK requires an HTTPS API origin without local, placeholder or URL components")
    origin = f"https://{parsed.netloc}"
    # TLS certificate chain and hostname validation are enabled by default.
    context = ssl.create_default_context()
    with urlopen(Request(origin + "/actuator/health/readiness",
                         headers={"Accept": "application/json"}), timeout=12, context=context) as response:
        if response.status != 200:
            raise ValueError("Backend readiness endpoint did not return HTTP 200")
        payload = json.load(response)
        if not isinstance(payload, dict) or payload.get("status") != "UP":
            raise ValueError("Backend readiness was not UP")
    # Prove this is a protected route, not a disconnected static hosting origin.
    try:
        with urlopen(Request(origin + "/api/auth/cognito/me",
                             headers={"Accept": "application/json"}), timeout=12, context=context):
            raise ValueError("Unauthenticated Cognito /me endpoint unexpectedly allowed access")
    except HTTPError as exc:
        if exc.code not in (401, 403):
            raise ValueError(f"Cognito /me returned unexpected HTTP {exc.code}") from exc
    print("Verified HTTPS certificate, backend readiness, and protected Cognito route")


if __name__ == "__main__":
    try:
        verify_api(os.environ["API_BASE_URL"])
    except (ValueError, KeyError, HTTPError, URLError, OSError, json.JSONDecodeError) as exc:
        print(f"Live API preflight failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
