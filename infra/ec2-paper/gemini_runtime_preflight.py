#!/usr/bin/env python3
"""Read-only Gemini deployment preflight using the existing one-host SSM role.

No model call, secret lookup, auth bypass, deployment or trading operation.
Environment values and the local operator key never leave host memory.
"""
from __future__ import annotations

import base64
import gzip
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

ACCOUNT = "098859530205"
REGION = "ap-south-1"
INSTANCE = "i-09c01b470fb39b85a"
REQUEST = Path("infra/ec2-paper/requests/gemini-runtime-preflight.json")
MARKER = "MINTTICK_GEMINI_PREFLIGHT:"
SHA = re.compile(r"[0-9a-f]{40}")
STATUSES = {"INSPECTED", "INVALID_HOST", "BACKEND_UNAVAILABLE", "INVALID_RESPONSE"}
BOOL_FIELDS = ("geminiKeyEnvironmentPresent", "geminiModelEnvironmentPresent",
               "operatorKeyEnvironmentPresent", "hostSettingsReadable", "providerCallAttempted",
               "runtimeConnectivityVerified")


def expected_request():
    return {"kind": "minttick-gemini-runtime-preflight-v1", "account": ACCOUNT,
            "region": REGION, "instance_id": INSTANCE, "read_only": True,
            "provider_calls": False, "secret_export": False, "orders": False}


def report(status="INSPECTED", **fields):
    result = {"status": status, "deployedSha": None, "selectedProviderEnvironment": "UNKNOWN",
              "hostGeminiKeyPresent": None, "hostSelectedProviderEnvironment": "UNKNOWN",
              "adminHealthHttpStatus": None, **dict.fromkeys(BOOL_FIELDS, False)}
    result.update(fields)
    return validate_report(result)


def validate_report(value):
    keys = {"status", "deployedSha", "selectedProviderEnvironment", "adminHealthHttpStatus",
            "hostGeminiKeyPresent", "hostSelectedProviderEnvironment", *BOOL_FIELDS}
    if not isinstance(value, dict) or set(value) != keys or value["status"] not in STATUSES:
        raise ValueError("INVALID_PREFLIGHT_REPORT")
    if value["deployedSha"] is not None and (not isinstance(value["deployedSha"], str)
                                             or not SHA.fullmatch(value["deployedSha"])):
        raise ValueError("INVALID_DEPLOYED_SHA")
    if value["selectedProviderEnvironment"] not in {"NONE", "GEMINI", "CLAUDE", "UNKNOWN"}:
        raise ValueError("INVALID_PROVIDER")
    if value["hostSelectedProviderEnvironment"] not in {"NONE", "GEMINI", "CLAUDE", "UNKNOWN"}:
        raise ValueError("INVALID_HOST_PROVIDER")
    present = value["hostGeminiKeyPresent"]
    if present is not None and type(present) is not bool:
        raise ValueError("INVALID_HOST_PRESENCE")
    if value["hostSettingsReadable"] != (present is not None):
        raise ValueError("INCONSISTENT_HOST_OBSERVATION")
    http = value["adminHealthHttpStatus"]
    if http is not None and (type(http) is not int or not 100 <= http <= 599):
        raise ValueError("INVALID_HTTP_STATUS")
    if any(type(value[k]) is not bool for k in BOOL_FIELDS):
        raise ValueError("INVALID_BOOLEAN")
    if value["providerCallAttempted"] or value["runtimeConnectivityVerified"]:
        raise ValueError("PREFLIGHT_CANNOT_VERIFY_PROVIDER")
    return value


def summarize_config(config):
    """Allowlisted booleans/enums only; never return arbitrary environment values."""
    if not isinstance(config, dict) or not isinstance(config.get("Env"), list):
        raise ValueError("INVALID_CONTAINER_CONFIG")
    env = {}
    for row in config["Env"]:
        if not isinstance(row, str) or "=" not in row:
            raise ValueError("INVALID_CONTAINER_ENV")
        key, value = row.split("=", 1)
        if key in env:
            raise ValueError("DUPLICATE_CONTAINER_ENV")
        env[key] = value
    image = config.get("Image", "")
    match = re.fullmatch(r"aitradingsystem-paper-backend-arm64:([0-9a-f]{40})", image)
    deployed = match.group(1) if match else None
    if deployed is None or env.get("APP_BUILD_REVISION") != deployed:
        raise ValueError("UNPROVEN_CONTAINER_REVISION")
    provider = env.get("NEWS_ANALYSIS_PROVIDER", "NONE")
    if provider not in {"NONE", "GEMINI", "CLAUDE"}:
        provider = "UNKNOWN"
    return report(deployedSha=deployed, selectedProviderEnvironment=provider,
                  geminiKeyEnvironmentPresent=bool(env.get("GEMINI_API_KEY", "").strip()),
                  geminiModelEnvironmentPresent=bool(env.get("GEMINI_MODEL", "").strip()),
                  operatorKeyEnvironmentPresent=bool(env.get("APP_API_KEY", "").strip()))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def summarize_host_settings(text):
    settings = {}
    for line in text.splitlines():
        # Read only the two exact supported bindings, never execute/source the file.
        if not line.startswith(("GEMINI_API_KEY=", "NEWS_ANALYSIS_PROVIDER=")):
            continue
        key, value = line.split("=", 1)
        if key in settings:
            raise ValueError("DUPLICATE_HOST_SETTING")
        value = value.strip()
        if len(value) >= 2 and value[0] in "\"'" and value[-1] == value[0]:
            value = value[1:-1]
        settings[key] = value
    provider = settings.get("NEWS_ANALYSIS_PROVIDER", "NONE")
    return {"hostSettingsReadable": True,
            "hostGeminiKeyPresent": bool(settings.get("GEMINI_API_KEY", "").strip()),
            "hostSelectedProviderEnvironment": provider if provider in {"NONE", "GEMINI", "CLAUDE"} else "UNKNOWN"}


def host_settings():
    path = Path("/opt/aitradingsystem-paper/runtime.env")
    try:
        if (path.is_symlink() or not path.is_file() or path.stat().st_uid != 0
                or path.stat().st_mode & 0o077 or path.stat().st_size > 65536):
            return {}
        return summarize_host_settings(path.read_text())
    except (OSError, ValueError, UnicodeError):
        return {}


def admin_health_status(key, opener):
    if not key:
        return None
    request = urllib.request.Request(
        "http://127.0.0.1:8080/api/admin/market-intelligence/health",
        headers={"X-API-Key": key, "Accept": "application/json"}, method="GET")
    try:
        with opener.open(request, timeout=10) as response:
            return response.status  # Deliberately do not read/export a response body.
    except urllib.error.HTTPError as error:
        return error.code
    except (urllib.error.URLError, OSError, TimeoutError):
        return None


def docker(*args):
    process = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=20)
    if process.returncode or len(process.stdout) > 1_000_000:
        raise ValueError("DOCKER_INSPECTION_FAILED")
    return process.stdout


def host():
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        return report("INVALID_HOST")
    try:
        ids = docker("ps", "--filter", "label=com.docker.compose.project=aitradingsystem-paper",
                     "--filter", "label=com.docker.compose.service=backend", "--format", "{{.ID}}").split()
        if len(ids) != 1 or not re.fullmatch(r"[0-9a-f]{12,64}", ids[0]):
            return report("BACKEND_UNAVAILABLE")
        config = json.loads(docker("inspect", "--format", "{{json .Config}}", ids[0]))
        result = summarize_config(config)
        result.update(host_settings())
        key = next((row.split("=", 1)[1] for row in config["Env"] if row.startswith("APP_API_KEY=")), "")
        result["adminHealthHttpStatus"] = admin_health_status(
            key, urllib.request.build_opener(NoRedirect()))
        return validate_report(result)
    except (ValueError, TypeError, OSError, subprocess.TimeoutExpired):
        return report("INVALID_RESPONSE")


def receipt(raw):
    if not isinstance(raw, str) or len(raw) > 2500:
        raise ValueError("INVALID_SSM_OUTPUT")
    lines = raw.splitlines()
    if len(lines) != 1 or not lines[0].startswith(MARKER):
        raise ValueError("MISSING_PREFLIGHT_RECEIPT")
    return validate_report(json.loads(lines[0][len(MARKER):]))


def runner():
    if (REQUEST.is_symlink() or not REQUEST.is_file() or REQUEST.stat().st_size > 1500
            or json.loads(REQUEST.read_text()) != expected_request()):
        raise RuntimeError("GEMINI_PREFLIGHT_REQUEST_NOT_APPROVED")
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from verify_ssm_release import verify_aws
    verify_aws()
    encoded = base64.b64encode(gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(encoded) > 23000:
        raise RuntimeError("GEMINI_PREFLIGHT_PAYLOAD_TOO_LARGE")
    command = "set -eu\nprintf '%s' '" + encoded + "' | base64 -d | gzip -dc | PYTHONDONTWRITEBYTECODE=1 python3 - host\n"

    def aws(*args):
        process = subprocess.run(["aws", *args, "--region", REGION, "--output", "json",
                                  "--no-cli-pager"], capture_output=True, text=True, timeout=45)
        if process.returncode:
            raise RuntimeError("GEMINI_PREFLIGHT_SSM_UNAVAILABLE")
        return json.loads(process.stdout)

    sent = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript",
               "--instance-ids", INSTANCE, "--timeout-seconds", "100",
               "--comment", "MintTick read-only Gemini runtime preflight",
               "--parameters", json.dumps({"commands": [command]}))
    command_id = sent.get("Command", {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", command_id):
        raise RuntimeError("GEMINI_PREFLIGHT_INVALID_COMMAND")
    for _ in range(30):
        time.sleep(3)
        result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                     "--instance-id", INSTANCE)
        if result.get("Status") == "Success":
            safe = receipt(result.get("StandardOutputContent", ""))
            safe["expectedSha"] = os.environ["GITHUB_SHA"]
            safe["revisionMatchesRequest"] = safe["deployedSha"] == safe["expectedSha"]
            Path("gemini-runtime-preflight.json").write_text(json.dumps(safe, indent=2) + "\n")
            print(json.dumps(safe, sort_keys=True), flush=True)
            if safe["status"] != "INSPECTED":
                raise RuntimeError("GEMINI_PREFLIGHT_NOT_INSPECTED")
            return
        if result.get("Status") not in {"Pending", "InProgress", "Delayed"}:
            raise RuntimeError("GEMINI_PREFLIGHT_COMMAND_FAILED")
    raise RuntimeError("GEMINI_PREFLIGHT_TIMEOUT")


if __name__ == "__main__":
    if sys.argv[1:] == ["host"]:
        print(MARKER + json.dumps(host(), sort_keys=True), flush=True)
    elif sys.argv[1:] == ["runner"]:
        runner()
    else:
        raise SystemExit("GEMINI_PREFLIGHT_INVALID_MODE")
