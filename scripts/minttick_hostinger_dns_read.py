#!/usr/bin/env python3
"""Read-only Hostinger DNS connection verification; never log token or record values."""
import json
import os
import sys
import urllib.error
import urllib.request

URL = "https://developers.hostinger.com/api/dns/v1/zones/minttick.com"
def main():
    token = os.getenv("HOSTINGER_API_TOKEN", "").strip()
    if not token:
        print("BLOCKED: HOSTINGER_API_TOKEN repository secret is absent")
        return 2
    req = urllib.request.Request(
        URL, headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/json",
        }, method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        # Print only the HTTP code: error bodies may reflect identifying values.
        print("BLOCKED: Hostinger DNS read returned HTTP", exc.code)
        return 2
    except (urllib.error.URLError, TimeoutError, ValueError):
        print("BLOCKED: Hostinger DNS read failed without a valid response")
        return 2
    if not isinstance(payload, list):
        print("BLOCKED: Unexpected Hostinger DNS zone schema")
        return 2
    names = set()
    for item in payload:
        if isinstance(item, dict) and isinstance(item.get("name"), str):
            names.add(item["name"].rstrip("."))
    print("PASS: Hostinger token authenticated; minttick.com DNS zone readable")
    print("Record groups:", len(payload))
    for label in ("@", "www", "api"):
        print(f"{label}: {'present' if label in names else 'not found'}")
    print("No records modified; no token, DNS values, or customer information logged")
    return 0

if __name__ == "__main__":
    sys.exit(main())
