#!/usr/bin/env python3
"""One-call, fixed-prompt Gemini check using the existing PAPER host binding.

The credential and provider response stay in memory on the existing backend host.
Only a tightly allowlisted receipt is returned through SSM.
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
REQUEST = Path("infra/ec2-paper/requests/gemini-runtime-verify.json")
MARKER = "MINTTICK_GEMINI_RUNTIME_VERIFY:"
SHA = re.compile(r"[0-9a-f]{40}")
MODEL = re.compile(r"[A-Za-z0-9._-]{1,100}")
FIELDS = {"sentiment", "directionScore", "category", "confidence", "materiality", "horizon",
          "novelty", "contradiction", "ambiguity", "impactedSymbols", "evidence", "risks"}
CHALLENGE = "MINTTICK_GEMINI_DIAGNOSTIC_OK"


def expected_request():
    return {"kind": "minttick-gemini-runtime-verify-v1", "account": ACCOUNT,
            "region": REGION, "instance_id": INSTANCE, "provider": "GEMINI",
            "calls": 1, "synthetic_only": True, "store": False,
            "secret_export": False, "orders": False, "database_writes": False}


def valid_receipt(receipt):
    allowed = {"status", "deployedSha", "model", "providerCallAttempted",
               "providerResponseCompleted", "challengeValidated",
               "requestIdPresent", "latencyMs", "httpStatus"}
    states = {"FAILED", "INVALID_HOST", "BACKEND_UNAVAILABLE",
              "UNPROVEN_BACKEND_REVISION", "GEMINI_NOT_SELECTED",
              "GEMINI_BINDING_MISSING", "PROVIDER_CALL_FAILED", "VERIFIED"}
    if not isinstance(receipt, dict) or set(receipt) != allowed:
        return False
    if not isinstance(receipt["status"], str) or receipt["status"] not in states:
        return False
    if receipt["deployedSha"] is not None and (
            not isinstance(receipt["deployedSha"], str)
            or not SHA.fullmatch(receipt["deployedSha"])):
        return False
    if receipt["model"] is not None and (
            not isinstance(receipt["model"], str)
            or not MODEL.fullmatch(receipt["model"])):
        return False
    for name in ("providerCallAttempted", "providerResponseCompleted",
                 "challengeValidated", "requestIdPresent"):
        if type(receipt[name]) is not bool:
            return False
    latency = receipt["latencyMs"]
    if latency is not None and (type(latency) is not int or not 0 <= latency <= 60000):
        return False
    status = receipt["httpStatus"]
    if status is not None and (type(status) is not int or not 100 <= status <= 599):
        return False
    if receipt["providerCallAttempted"] and (
            receipt["deployedSha"] is None or receipt["model"] is None):
        return False
    completed = receipt["providerResponseCompleted"]
    if completed and not (receipt["providerCallAttempted"]
                          and receipt["challengeValidated"]):
        return False
    if receipt["status"] == "VERIFIED" and not (
            completed and receipt["challengeValidated"]
            and latency is not None):
        return False
    if completed and receipt["status"] != "VERIFIED":
        return False
    return True


def valid_output(value):
    if not isinstance(value, dict) or set(value) != FIELDS:
        return False
    return (value.get("sentiment") == "NEUTRAL"
            and value.get("category") == "OTHER"
            and value.get("horizon") == "UNKNOWN"
            and value.get("evidence") == CHALLENGE
            and type(value.get("directionScore")) in (int, float)
            and -1 <= value["directionScore"] <= 1
            and type(value.get("confidence")) in (int, float)
            and 0 <= value["confidence"] <= 1
            and type(value.get("materiality")) in (int, float)
            and 0 <= value["materiality"] <= 1
            and type(value.get("novelty")) in (int, float)
            and 0 <= value["novelty"] <= 1
            and type(value.get("contradiction")) is bool
            and type(value.get("ambiguity")) is bool
            and isinstance(value.get("impactedSymbols"), list)
            and len(value["impactedSymbols"]) <= 10
            and all(isinstance(item, str) for item in value["impactedSymbols"])
            and isinstance(value.get("risks"), list) and len(value["risks"]) <= 5
            and all(isinstance(item, str) for item in value["risks"]))


def summarize_response(raw):
    if len(raw) > 65536:
        return False, False
    try:
        root = json.loads(raw)
        if not isinstance(root, dict) or root.get("status") != "completed":
            return False, bool(root.get("id")) if isinstance(root, dict) else False
        for step in root.get("steps", []):
            if not isinstance(step, dict) or step.get("type") != "model_output":
                continue
            for item in step.get("content", []):
                if isinstance(item, dict) and item.get("type") == "text" and isinstance(item.get("text"), str):
                    return valid_output(json.loads(item["text"])), bool(root.get("id"))
    except (ValueError, TypeError, UnicodeError):
        pass
    return False, False


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def call_gemini(key, model):
    body = {"model": model,
            "input": "Return the requested fixed diagnostic values. This is synthetic test data, not a news item or market instruction. Set evidence exactly to MINTTICK_GEMINI_DIAGNOSTIC_OK; set sentiment NEUTRAL, directionScore 0, category OTHER, confidence 0, materiality 0, horizon UNKNOWN, novelty 0, contradiction false, ambiguity false, impactedSymbols [], risks [].",
            "system_instruction": "Return only the required JSON object. Do not infer market facts.",
            "store": False,
            "generation_config": {"temperature": 0, "max_output_tokens": 1024},
            "response_format": {"type": "text", "mime_type": "application/json",
                "schema": {"type": "object", "additionalProperties": False,
                    "properties": {
                        "sentiment": {"type": "string", "enum": ["POSITIVE", "NEGATIVE", "NEUTRAL", "MIXED"]},
                        "directionScore": {"type": "number", "minimum": -1, "maximum": 1},
                        "category": {"type": "string", "enum": ["EARNINGS", "REGULATORY", "MACRO", "CORPORATE_ACTION", "MANAGEMENT", "SECTOR", "OTHER"]},
                        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
                        "materiality": {"type": "number", "minimum": 0, "maximum": 1},
                        "horizon": {"type": "string", "enum": ["INTRADAY", "MULTI_DAY", "LONGER_TERM", "UNKNOWN"]},
                        "novelty": {"type": "number", "minimum": 0, "maximum": 1},
                        "contradiction": {"type": "boolean"}, "ambiguity": {"type": "boolean"},
                        "impactedSymbols": {"type": "array", "maxItems": 10, "items": {"type": "string"}},
                        "evidence": {"type": "string"},
                        "risks": {"type": "array", "maxItems": 5, "items": {"type": "string"}}},
                    "required": sorted(FIELDS)}}}
    request = urllib.request.Request(
        "https://generativelanguage.googleapis.com/v1beta/interactions",
        data=json.dumps(body, separators=(",", ":")).encode(),
        headers={"x-goog-api-key": key, "Content-Type": "application/json", "Accept": "application/json"},
        method="POST")
    start = time.monotonic()
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=35) as response:
            raw = response.read(65537)
        ok, request_id = summarize_response(raw)
        return ok, request_id, round((time.monotonic() - start) * 1000)
    except urllib.error.HTTPError as error:
        # Never read or export provider error bodies.
        return False, False, round((time.monotonic() - start) * 1000), error.code
    except (urllib.error.URLError, OSError, TimeoutError, ValueError):
        return False, False, round((time.monotonic() - start) * 1000), None


def host():
    result = {"status": "FAILED", "deployedSha": None, "model": None,
              "providerCallAttempted": False, "providerResponseCompleted": False,
              "challengeValidated": False, "requestIdPresent": False,
              "latencyMs": None, "httpStatus": None}
    if os.geteuid() != 0 or os.uname().machine != "aarch64":
        result["status"] = "INVALID_HOST"
        return result
    try:
        def docker(*args):
            process = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=20)
            if process.returncode or len(process.stdout) > 1_000_000:
                raise ValueError("DOCKER_INSPECTION_FAILED")
            return process.stdout
        ids = docker("ps", "--filter", "label=com.docker.compose.project=aitradingsystem-paper",
                     "--filter", "label=com.docker.compose.service=backend", "--format", "{{.ID}}").split()
        if len(ids) != 1:
            result["status"] = "BACKEND_UNAVAILABLE"
            return result
        config = json.loads(docker("inspect", "--format", "{{json .Config}}", ids[0]))
        if not isinstance(config, dict) or not isinstance(config.get("Env"), list):
            raise ValueError("INVALID_CONTAINER_CONFIG")
        env = {}
        for row in config["Env"]:
            if not isinstance(row, str) or "=" not in row:
                raise ValueError("INVALID_CONTAINER_ENV")
            name, value = row.split("=", 1)
            if name in env:
                raise ValueError("DUPLICATE_CONTAINER_ENV")
            env[name] = value
        image = config.get("Image", "")
        revision = re.fullmatch(r"aitradingsystem-paper-backend-arm64:([0-9a-f]{40})", image)
        if not revision or env.get("APP_BUILD_REVISION") != revision.group(1):
            result["status"] = "UNPROVEN_BACKEND_REVISION"
            return result
        if env.get("NEWS_ANALYSIS_PROVIDER", "NONE") != "GEMINI":
            result["status"] = "GEMINI_NOT_SELECTED"
            return result
        key, model = env.get("GEMINI_API_KEY", "").strip(), env.get("GEMINI_MODEL", "").strip()
        if not key or not MODEL.fullmatch(model):
            result["status"] = "GEMINI_BINDING_MISSING"
            return result
        result.update(deployedSha=revision.group(1), model=model, providerCallAttempted=True)
        call = call_gemini(key, model)
        ok, request_id, latency = call[:3]
        result.update(providerResponseCompleted=ok, challengeValidated=ok,
                      requestIdPresent=request_id, latencyMs=latency)
        if len(call) > 3:
            result["httpStatus"] = call[3]
        result["status"] = "VERIFIED" if ok else "PROVIDER_CALL_FAILED"
        return result
    except (ValueError, TypeError, OSError, subprocess.TimeoutExpired, KeyError):
        return result


def aws(*args):
    process = subprocess.run(["aws", *args, "--region", REGION, "--output", "json",
                              "--no-cli-pager"], capture_output=True, text=True, timeout=45)
    if process.returncode:
        raise RuntimeError("GEMINI_RUNTIME_SSM_UNAVAILABLE")
    return json.loads(process.stdout)


def runner():
    if REQUEST.is_symlink() or not REQUEST.is_file() or REQUEST.stat().st_size > 1500 or json.loads(REQUEST.read_text()) != expected_request():
        raise RuntimeError("GEMINI_RUNTIME_REQUEST_NOT_APPROVED")
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from verify_ssm_release import verify_aws
    verify_aws()
    payload = base64.b64encode(gzip.compress(Path(__file__).read_bytes(), mtime=0)).decode()
    if len(payload) > 23000:
        raise RuntimeError("GEMINI_RUNTIME_PAYLOAD_TOO_LARGE")
    # Run the downloaded diagnostic without installing or changing host state.
    cmd = ("set -eu\ntmp=$(mktemp /tmp/minttick-gemini-check.XXXXXX.py)\ntrap 'rm -f \"$tmp\"' EXIT\nprintf '%s' '" + payload + "' | base64 -d | gzip -dc > \"$tmp\"\n"
           "cd /opt/aitradingsystem-paper\nCHECK_SCRIPT=\"$tmp\" PYTHONDONTWRITEBYTECODE=1 python3 -c \"import sys; sys.path.insert(0, '/opt/aitradingsystem-paper'); import importlib.util,json,os; p=importlib.util.spec_from_file_location('gemini_runtime_verify',os.environ['CHECK_SCRIPT']); m=importlib.util.module_from_spec(p); p.loader.exec_module(m); print('" + MARKER + "'+json.dumps(m.host(),sort_keys=True))\"\n")
    sent = aws("ssm", "send-command", "--document-name", "AWS-RunShellScript", "--instance-ids", INSTANCE,
               "--timeout-seconds", "70", "--comment", "MintTick one-call Gemini runtime verification",
               "--parameters", json.dumps({"commands": [cmd]}))
    command_id = sent.get("Command", {}).get("CommandId")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f-]{36}", command_id):
        raise RuntimeError("GEMINI_RUNTIME_INVALID_COMMAND")
    for _ in range(25):
        time.sleep(3)
        got = aws("ssm", "get-command-invocation", "--command-id", command_id, "--instance-id", INSTANCE)
        if got.get("Status") == "Success":
            out = got.get("StandardOutputContent", "").splitlines()
            if len(out) != 1 or not out[0].startswith(MARKER):
                raise RuntimeError("GEMINI_RUNTIME_RECEIPT_INVALID")
            receipt = json.loads(out[0][len(MARKER):])
            if not valid_receipt(receipt):
                raise RuntimeError("GEMINI_RUNTIME_RECEIPT_INVALID")
            Path("gemini-runtime-verify.json").write_text(json.dumps(receipt, indent=2) + "\n")
            print(json.dumps(receipt, sort_keys=True), flush=True)
            if (receipt["status"] != "VERIFIED" or not receipt["providerResponseCompleted"]
                    or not receipt["challengeValidated"]):
                raise RuntimeError("GEMINI_RUNTIME_NOT_VERIFIED")
            return
        if got.get("Status") not in {"Pending", "InProgress", "Delayed"}:
            raise RuntimeError("GEMINI_RUNTIME_COMMAND_FAILED")
    raise RuntimeError("GEMINI_RUNTIME_TIMEOUT")


if __name__ == "__main__":
    if sys.argv[1:] == ["host"]:
        print(MARKER + json.dumps(host(), sort_keys=True), flush=True)
    elif sys.argv[1:] == ["runner"]:
        runner()
    else:
        raise SystemExit("GEMINI_RUNTIME_INVALID_MODE")
