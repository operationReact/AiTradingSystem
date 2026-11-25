#!/usr/bin/env python3
"""One-time exact-host Angel One authentication probe; no order, quote or WS calls.

Reads only four allowlisted SSM String or SecureString owner parameters on EC2,
validates the registered EIP, performs one SmartAPI login+profile identity check,
and emits fixed categorical outcomes. No tokens, PIN, code, API key or seed are
written to disk, printed, returned to GitHub or transmitted to MintTick clients.
"""
import base64
from datetime import datetime, timezone
import hashlib
import hmac
import ipaddress
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import urllib.error
import urllib.request

REGION = "ap-south-1"
OUTBOUND_IP = "3.108.130.17"
PREFIX = "/minttick/dev/angelone/"
NAMES = {
    PREFIX + "api-key": "api_key",
    PREFIX + "client-code": "client_code",
    PREFIX + "login-pin": "pin",
    PREFIX + "totp-secret": "totp_secret",
}
API_ROOT = "https://apiconnect.angelone.in"


class ProbeFailure(Exception):
    pass


def ssm_params():
    cmd = ["aws", "ssm", "get-parameters", "--region", REGION,
           "--names", *NAMES.keys(), "--with-decryption",
           "--output", "json", "--no-cli-pager"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=12, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ProbeFailure("OWNER_SSM_ACCESS: UNAVAILABLE") from None
    if proc.returncode != 0:
        raise ProbeFailure("OWNER_SSM_ACCESS: UNAVAILABLE")
    try:
        payload = json.loads(proc.stdout)
    except (ValueError, TypeError):
        raise ProbeFailure("OWNER_SSM_ACCESS: INVALID_RESPONSE") from None
    params = payload.get("Parameters") or []
    if payload.get("InvalidParameters") or len(params) != len(NAMES):
        raise ProbeFailure("OWNER_SSM_ACCESS: INCOMPLETE")
    result = {}
    for param in params:
        key = NAMES.get(param.get("Name"))
        value = param.get("Value")
        typ = param.get("Type")
        if key in {"api_key", "client_code", "pin", "totp_secret"}:
            safe_type = {"SecureString":"SECURE_STRING", "String":"STRING"}.get(typ, "OTHER")
            print("OWNER_SSM_TYPE_" + key.upper() + ": " + safe_type, flush=True)
        if not key or key in result or typ not in ("String", "SecureString") or (
            not isinstance(value, str) or not value.strip()):
            raise ProbeFailure("OWNER_SSM_ACCESS: INVALID_PARAMETERS")
        result[key] = value
    if len(result) != len(NAMES):
        raise ProbeFailure("OWNER_SSM_ACCESS: INCOMPLETE")
    print("OWNER_SSM_ACCESS: READY", flush=True)
    return result


def request_json(url, method, headers, data=None):
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=9) as response:
            raw = response.read(32769)
            if len(raw) > 32768:
                raise ProbeFailure("ANGEL_ONE_AUTH: INVALID_RESPONSE")
    except urllib.error.HTTPError as error:
        # Read only bounded provider errorcode for categorical diagnostics,
        # never its message, response body, request headers or tokens.
        try:
            error_body = json.loads(error.read(16384))
        except (ValueError, TypeError, UnicodeError, OSError):
            error_body = None
        code = error_body.get("errorcode") if isinstance(error_body, dict) else None
        if isinstance(code, str) and re.fullmatch(r"(?:AB|AG)[0-9]{4}", code):
            raise ProbeFailure("ANGEL_ONE_AUTH: PROVIDER_CODE_" + code) from None
        if error.code in (401, 403):
            raise ProbeFailure("ANGEL_ONE_AUTH: REJECTED_OR_IP_RESTRICTED") from None
        if error.code == 429:
            raise ProbeFailure("ANGEL_ONE_AUTH: RATE_LIMITED") from None
        # Public HTTP status only. Never print response text, headers or URI.
        if 400 <= error.code <= 599:
            raise ProbeFailure(f"ANGEL_ONE_AUTH: HTTP_{error.code}") from None
        raise ProbeFailure("ANGEL_ONE_AUTH: UNAVAILABLE") from None
    except (urllib.error.URLError, OSError, TimeoutError):
        raise ProbeFailure("ANGEL_ONE_AUTH: NETWORK_UNAVAILABLE") from None
    try:
        result = json.loads(raw)
    except (ValueError, TypeError):
        raise ProbeFailure("ANGEL_ONE_AUTH: INVALID_RESPONSE") from None
    if not isinstance(result, dict) or result.get("status") is not True:
        # Provider's bounded machine-readable code only; never its message.
        code = result.get("errorcode") if isinstance(result, dict) else None
        if isinstance(code, str) and re.fullmatch(r"(?:AB|AG)[0-9]{4}", code):
            raise ProbeFailure("ANGEL_ONE_AUTH: PROVIDER_CODE_" + code) from None
        raise ProbeFailure("ANGEL_ONE_AUTH: REJECTED_OR_IP_RESTRICTED")
    if not isinstance(result.get("data"), dict):
        raise ProbeFailure("ANGEL_ONE_AUTH: INVALID_RESPONSE")
    return result["data"]


def totp(seed):
    try:
        secret = base64.b32decode(seed.strip().replace(" ", "").upper() + "=" * (-len(seed.strip().replace(" ", "")) % 8))
    except (ValueError, base64.binascii.Error):
        raise ProbeFailure("OWNER_SSM_ACCESS: TOTP_INVALID") from None
    counter = int(datetime.now(timezone.utc).timestamp()) // 30
    digest = hmac.new(secret, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 15
    code = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7fffffff
    return f"{code % 1000000:06d}"


def egress():
    try:
        with urllib.request.urlopen("https://checkip.amazonaws.com", timeout=6) as response:
            ip = str(ipaddress.ip_address(response.read(64).decode().strip()))
    except (OSError, UnicodeError, ValueError, urllib.error.URLError):
        raise ProbeFailure("EC2_EGRESS: UNAVAILABLE") from None
    if ip != OUTBOUND_IP:
        raise ProbeFailure("EC2_EGRESS: MISMATCH")
    print("EC2_EGRESS: MATCH_REGISTERED_EIP", flush=True)


def verified_client_network_headers():
    # Official SmartAPI requires physical client local/public IP and MAC.
    # Determine the MAC of the exact EC2 interface, never invent or log it.
    try:
        output = subprocess.run(
            ["ip", "-o", "-4", "addr", "show"], capture_output=True,
            check=False, text=True, timeout=3)
        if output.returncode != 0:
            raise ValueError("no interface")
        for line in output.stdout.splitlines():
            fields = line.split()
            if len(fields) < 4 or fields[2] != "inet" or not fields[3].startswith("10.87.0.10/"):
                continue
            interface = fields[1].split("@")[0]
            if not re.fullmatch(r"[A-Za-z0-9_.-]{2,32}", interface):
                continue
            mac = (Path("/sys/class/net") / interface / "address").read_text().strip().lower()
            if re.fullmatch(r"(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", mac) and mac != "00:00:00:00:00:00":
                return {
                    "X-ClientPublicIP": OUTBOUND_IP,
                    "X-ClientLocalIP": "10.87.0.10",
                    "X-MACAddress": mac,
                }
    except (OSError, ValueError, subprocess.TimeoutExpired):
        pass
    raise ProbeFailure("INSTANCE_NETWORK_HEADERS: UNAVAILABLE")


def run():
    if not sys.platform.startswith("linux"):
        raise ProbeFailure("INSTANCE_PLATFORM: UNEXPECTED")
    egress()
    network_headers = verified_client_network_headers()
    print("INSTANCE_NETWORK_HEADERS: VERIFIED", flush=True)
    p = ssm_params()
    if (not re.fullmatch(r"[A-Za-z0-9._-]{4,20}", p["client_code"])
            or not re.fullmatch(r"[0-9]{4,8}", p["pin"])):
        raise ProbeFailure("OWNER_SSM_ACCESS: INVALID_PARAMETERS")
    otp = totp(p["totp_secret"])
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "X-PrivateKey": p["api_key"],
        "X-UserType": "USER",
        "X-SourceID": "WEB",
        **network_headers,
    }
    body = json.dumps({
        "clientcode": p["client_code"], "password": p["pin"], "totp": otp
    }, separators=(",", ":")).encode()
    auth = request_json(API_ROOT + "/rest/auth/angelbroking/user/v1/loginByPassword",
                        "POST", headers, body)
    jwt = auth.get("jwtToken")
    feed = auth.get("feedToken")
    if not isinstance(jwt, str) or not jwt or not isinstance(feed, str) or not feed:
        raise ProbeFailure("ANGEL_ONE_AUTH: TOKEN_OR_FEED_MISSING")
    print("ANGEL_ONE_AUTH: TOKEN_AND_FEED_RECEIVED", flush=True)
    profile = request_json(API_ROOT + "/rest/secure/angelbroking/user/v1/getProfile",
                           "GET", {**headers, "Authorization": "Bearer " + jwt})
    verified = profile.get("clientcode", profile.get("clientCode"))
    if not isinstance(verified, str) or verified.strip().upper() != p["client_code"].strip().upper():
        raise ProbeFailure("ANGEL_ONE_AUTH: PROFILE_MISMATCH")
    print("ANGEL_ONE_AUTH: VERIFIED_OWNER_PROFILE", flush=True)
    print("CHECK: NO_ORDER_OR_QUOTE_OR_WEBSOCKET_ATTEMPTED", flush=True)


if __name__ == "__main__":
    try:
        run()
    except ProbeFailure as error:
        print(str(error), flush=True)
        sys.exit(2)
    except Exception:
        print("ANGEL_ONE_AUTH: INTERNAL_FAILURE", flush=True)
        sys.exit(2)
