#!/usr/bin/env python3
"""One fixed, non-stored Gemini API smoke call using the Actions secret.

This checks credential validity from CI only. It is not app-runtime verification.
The key, request body, provider response body and provider error body are never logged.
"""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request

MODEL_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,100}")
CHALLENGE = "MINTTICK_GEMINI_DIAGNOSTIC_OK"
FIELDS = {
    "sentiment", "directionScore", "category", "confidence", "materiality",
    "horizon", "novelty", "contradiction", "ambiguity", "impactedSymbols",
    "evidence", "risks",
}


def request_body(model: str) -> dict:
    return {
        "model": model,
        "input": (
            "Synthetic connectivity diagnostic only. Return sentiment NEUTRAL, "
            "directionScore 0, category OTHER, confidence 0, materiality 0, "
            "horizon UNKNOWN, novelty 0, contradiction false, ambiguity false, "
            "impactedSymbols [], risks [], and evidence exactly "
            + CHALLENGE
            + "."
        ),
        "system_instruction": "Return only the requested JSON object. Do not infer market facts.",
        "store": False,
        "generation_config": {"temperature": 0, "max_output_tokens": 512},
        "response_format": {
            "type": "text",
            "mime_type": "application/json",
            "schema": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "sentiment": {"type": "string", "enum": ["NEUTRAL"]},
                    "directionScore": {"type": "number", "enum": [0]},
                    "category": {"type": "string", "enum": ["OTHER"]},
                    "confidence": {"type": "number", "enum": [0]},
                    "materiality": {"type": "number", "enum": [0]},
                    "horizon": {"type": "string", "enum": ["UNKNOWN"]},
                    "novelty": {"type": "number", "enum": [0]},
                    "contradiction": {"type": "boolean", "enum": [False]},
                    "ambiguity": {"type": "boolean", "enum": [False]},
                    "impactedSymbols": {"type": "array", "maxItems": 0},
                    "evidence": {"type": "string", "enum": [CHALLENGE]},
                    "risks": {"type": "array", "maxItems": 0},
                },
                "required": sorted(FIELDS),
            },
        },
    }


def valid_output(value: object) -> bool:
    return (
        isinstance(value, dict)
        and set(value) == FIELDS
        and value.get("sentiment") == "NEUTRAL"
        and value.get("directionScore") == 0
        and type(value.get("directionScore")) in (int, float)
        and value.get("category") == "OTHER"
        and value.get("confidence") == 0
        and type(value.get("confidence")) in (int, float)
        and value.get("materiality") == 0
        and type(value.get("materiality")) in (int, float)
        and value.get("horizon") == "UNKNOWN"
        and value.get("novelty") == 0
        and type(value.get("novelty")) in (int, float)
        and value.get("contradiction") is False
        and value.get("ambiguity") is False
        and value.get("impactedSymbols") == []
        and value.get("evidence") == CHALLENGE
        and value.get("risks") == []
    )


def summarize(raw: bytes) -> tuple[bool, bool]:
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
                if isinstance(item, dict) and item.get("type") == "text":
                    return valid_output(json.loads(item["text"])), bool(root.get("id"))
    except (ValueError, TypeError, UnicodeError, KeyError):
        pass
    return False, False


def run(api_key: str, model: str = "gemini-2.5-flash") -> dict:
    result = {
        "status": "FAILED",
        "providerCallAttempted": False,
        "providerResponseCompleted": False,
        "challengeValidated": False,
        "requestIdPresent": False,
        "httpStatus": None,
    }
    if not api_key or len(api_key) > 512 or not MODEL_PATTERN.fullmatch(model):
        result["status"] = "SECRET_MISSING" if not api_key else "INVALID_CONFIGURATION"
        return result
    body = json.dumps(request_body(model), separators=(",", ":")).encode()
    req = urllib.request.Request(
        "https://generativelanguage.googleapis.com/v1/interactions",
        data=body,
        headers={
            "x-goog-api-key": api_key,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    result["providerCallAttempted"] = True
    try:
        with urllib.request.build_opener(
            urllib.request.HTTPRedirectHandler()
        ).open(req, timeout=35) as response:
            raw = response.read(65537)
            result["httpStatus"] = response.status
        ok, request_id = summarize(raw)
        result.update(
            status="VERIFIED" if ok else "INVALID_RESPONSE",
            providerResponseCompleted=ok,
            challengeValidated=ok,
            requestIdPresent=request_id,
        )
    except urllib.error.HTTPError as exc:
        result.update(status="PROVIDER_CALL_FAILED", httpStatus=exc.code)
    except (urllib.error.URLError, OSError, TimeoutError, ValueError):
        result["status"] = "PROVIDER_CALL_FAILED"
    return result


if __name__ == "__main__":
    receipt = run(os.environ.get("GEMINI_API_KEY", ""))
    print(json.dumps(receipt, sort_keys=True))
    sys.exit(0 if receipt["status"] == "VERIFIED" else 1)
