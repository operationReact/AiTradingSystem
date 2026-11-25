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
HISTORY_FAILURES = {
    "NOT_OBSERVED", "UNCLASSIFIED_HISTORY_FAILURE", "COLLECTOR_PREREQUISITE_UNTRUSTED",
    "PRIVATE_ARCHIVE_UNAVAILABLE", "TOO_MANY_PAGES", "INVALID_ARCHIVE_IDENTITY",
    "INVALID_ARCHIVE_MANIFEST", "INVALID_ARCHIVE_FILE", "ARCHIVE_CHECKSUM_MISMATCH",
    "ARCHIVE_METADATA_MISMATCH", "INVALID_SESSION_DATE", "OUTSIDE_REQUESTED_RANGE",
    "DUPLICATE_ARCHIVE_SESSION", "INVALID_MONTHLY_SOURCE_ROW", "OUT_OF_ORDER_MONTHLY_SOURCE",
    "INVALID_MONTHLY_CHART_IDENTITY", "MONTHLY_EXPORT_LIMIT", "SNAPSHOT_TOO_LARGE",
    "UNTRUSTED_MONITOR_PATH", "UNTRUSTED_MONITOR_FILE", "INVALID_STATE", "UNTRUSTED_STATE",
    "INSTANCE_IDENTITY_UNAVAILABLE", "WRONG_EC2_INSTANCE", "UNTRUSTED_CHART_PATH",
    "UNTRUSTED_CHART_BACKUP", "UNTRUSTED_MONTHLY_CHART_PATH", "UNTRUSTED_MONTHLY_CHART_BACKUP",
    "INVALID_CHART_ROW", "INVALID_CHART_IDENTITY_OR_LIMIT", "OVERSIZE_CHART",
    "OVERSIZE_MONTHLY_CHART", "INVALID_CHART_TOKEN", "INVALID_MONTHLY_TOKEN",
    "HISTORY_SCHEMA_MISSING", "HISTORY_SQLITE_OPERATIONAL_FAILURE", "FILESYSTEM_DISK_FULL",
    "FILESYSTEM_PERMISSION_DENIED", "FILESYSTEM_FILE_MISSING",
    "HISTORY_CACHE_FILE_MISSING", "PUBLICATION_BUSY", "UNTRUSTED_PUBLICATION_LOCK",
    "PUBLICATION_HANDOVER_BUSY", "PUBLICATION_HANDOVER_UNAVAILABLE",
    "PUBLICATION_TIMER_QUIESCE_FAILED", "PUBLICATION_TIMER_RESTORE_FAILED",
}
COLLECTOR_FILES = {"authProbe": ("probe_owner_angelone_auth.py", 0o600),
                   "collector": ("owner_personal_angelone_history.py", 0o600),
                   "launcher": ("run_owner_personal_history.sh", 0o700)}
FILE_STATUSES = {"NOT_OBSERVED", "OK", "MISSING", "SYMLINK", "WRONG_OWNER", "WRONG_MODE", "UNREADABLE"}
BOOL_FIELDS = ("geminiKeyEnvironmentPresent", "geminiModelEnvironmentPresent",
               "operatorKeyEnvironmentPresent", "hostSettingsReadable", "providerCallAttempted",
               "runtimeConnectivityVerified")


def expected_request():
    return {"kind": "minttick-gemini-runtime-preflight-v1", "account": ACCOUNT,
            "region": REGION, "instance_id": INSTANCE, "read_only": True,
            "provider_calls": False, "secret_export": False, "orders": False}


def report(status="INSPECTED", **fields):
    result = {"status": status, "deployedSha": None, "selectedProviderEnvironment": "UNKNOWN",
              "lastHistoryPrecheckFailure": "NOT_OBSERVED",
              "historyCollectorPrerequisites": dict.fromkeys(COLLECTOR_FILES, "NOT_OBSERVED"),
              "hostGeminiKeyPresent": None, "hostSelectedProviderEnvironment": "UNKNOWN",
              "adminHealthHttpStatus": None, **dict.fromkeys(BOOL_FIELDS, False)}
    result.update(fields)
    return validate_report(result)


def validate_report(value):
    keys = {"status", "deployedSha", "selectedProviderEnvironment", "adminHealthHttpStatus",
            "hostGeminiKeyPresent", "hostSelectedProviderEnvironment", "lastHistoryPrecheckFailure",
            "historyCollectorPrerequisites", *BOOL_FIELDS}
    if not isinstance(value, dict) or set(value) != keys or value["status"] not in STATUSES:
        raise ValueError("INVALID_PREFLIGHT_REPORT")
    if value["deployedSha"] is not None and (not isinstance(value["deployedSha"], str)
                                             or not SHA.fullmatch(value["deployedSha"])):
        raise ValueError("INVALID_DEPLOYED_SHA")
    if value["selectedProviderEnvironment"] not in {"NONE", "GEMINI", "CLAUDE", "UNKNOWN"}:
        raise ValueError("INVALID_PROVIDER")
    if value["hostSelectedProviderEnvironment"] not in {"NONE", "GEMINI", "CLAUDE", "UNKNOWN"}:
        raise ValueError("INVALID_HOST_PROVIDER")
    if value["lastHistoryPrecheckFailure"] not in HISTORY_FAILURES:
        raise ValueError("INVALID_HISTORY_FAILURE")
    files = value["historyCollectorPrerequisites"]
    if not isinstance(files, dict) or set(files) != set(COLLECTOR_FILES) or any(v not in FILE_STATUSES for v in files.values()):
        raise ValueError("INVALID_COLLECTOR_METADATA")
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


def classify_history_failure(text):
    if "MINTTICK_DEPLOY_FAILED_STAGE:history_precheck" not in text:
        return "NOT_OBSERVED"
    match = re.search(r"(?:ProgressUnavailable|SchedulerBlocked): ([A-Z_]+)(?:\r?\n|$)", text)
    if match:
        return match.group(1) if match.group(1) in HISTORY_FAILURES else "UNCLASSIFIED_HISTORY_FAILURE"
    if re.search(r"sqlite3\.OperationalError: no such (?:table|column):", text):
        return "HISTORY_SCHEMA_MISSING"
    if "sqlite3.OperationalError:" in text:
        return "HISTORY_SQLITE_OPERATIONAL_FAILURE"
    if "[Errno 28] No space left on device" in text:
        return "FILESYSTEM_DISK_FULL"
    if "PermissionError:" in text:
        return "FILESYSTEM_PERMISSION_DENIED"
    if "FileNotFoundError:" in text:
        if ('/opt/aitradingsystem-paper/monitor/' in text
                or re.search(r"FileNotFoundError:[^\n]*['\"][0-9]{1,12}\.json['\"](?:\r?\n|$)", text)):
            return "HISTORY_CACHE_FILE_MISSING"
        return "FILESYSTEM_FILE_MISSING"
    return "UNCLASSIFIED_HISTORY_FAILURE"


def file_status(path, mode, owner=0):
    try:
        if path.is_symlink():
            return "SYMLINK"
        if not path.is_file():
            return "MISSING"
        info = path.stat()
        if info.st_uid != owner:
            return "WRONG_OWNER"
        return "OK" if info.st_mode & 0o7777 == mode else "WRONG_MODE"
    except OSError:
        return "UNREADABLE"


def collector_metadata():
    root = Path("/opt/aitradingsystem-paper")
    return {key: file_status(root / name, mode) for key, (name, mode) in COLLECTOR_FILES.items()}


def history_precheck_failure():
    # Read only recent SSM deployment stderr on this exact host. Never return
    # a log line, path, traceback, archive content or arbitrary exception text.
    root = Path("/var/lib/amazon/ssm") / INSTANCE / "document/orchestration"
    try:
        candidates = list(root.glob("*/awsrunShellScript/*/stderr"))
        if len(candidates) > 4096:
            return "NOT_OBSERVED"
        candidates.sort(key=lambda p: p.stat().st_mtime, reverse=True)
        for path in candidates[:32]:
            if (path.is_symlink() or not path.is_file() or path.stat().st_uid != 0
                    or not path.resolve().is_relative_to(root.resolve())
                    or time.time() - path.stat().st_mtime > 3600):
                continue
            with path.open("rb") as stream:
                stream.seek(max(0, path.stat().st_size - 65536))
                code = classify_history_failure(stream.read(65536).decode("utf-8", errors="replace"))
            if code != "NOT_OBSERVED":
                return code
    except OSError:
        pass
    return "NOT_OBSERVED"


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
        result["lastHistoryPrecheckFailure"] = history_precheck_failure()
        result["historyCollectorPrerequisites"] = collector_metadata()
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
