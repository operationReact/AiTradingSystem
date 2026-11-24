"""Unit coverage for the bounded, no-trade Gemini runtime diagnostic."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "gemini_runtime_verify", ROOT / "infra/ec2-paper/gemini_runtime_verify.py")
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)


class GeminiRuntimeVerifyTest(unittest.TestCase):
    def test_request_contract_is_one_synthetic_nonpersistent_call(self):
        request = check.expected_request()
        self.assertEqual(1, request["calls"])
        self.assertTrue(request["synthetic_only"] and request["store"] is False)
        self.assertTrue(request["secret_export"] is False and request["orders"] is False)
        self.assertTrue(request["database_writes"] is False)

    def test_only_complete_expected_structured_challenge_passes(self):
        value = {
            "sentiment": "NEUTRAL", "directionScore": 0, "category": "OTHER",
            "confidence": 0, "materiality": 0, "horizon": "UNKNOWN", "novelty": 0,
            "contradiction": False, "ambiguity": False, "impactedSymbols": [],
            "evidence": check.CHALLENGE, "risks": []}
        raw = json.dumps({"status": "completed", "id": "private-provider-id", "steps": [
            {"type": "model_output", "content": [{"type": "text", "text": json.dumps(value)}]}]})
        self.assertEqual((True, True), check.summarize_response(raw))
        self.assertEqual((False, True), check.summarize_response(raw.replace(check.CHALLENGE, "wrong")))
        self.assertEqual((False, False), check.summarize_response('{"status":"failed"}'))
        self.assertEqual((False, False), check.summarize_response("x" * 65537))

    def test_provider_error_body_is_never_read_or_returned(self):
        from urllib.error import HTTPError
        import io
        error_body = io.BytesIO(b"secret provider detail")
        with patch.object(check.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = HTTPError("https://provider", 429, "busy", {}, error_body)
            response = check.call_gemini("secret-key", "gemini-2.5-flash")
        self.assertFalse(response[0])
        self.assertEqual(429, response[3])
        self.assertEqual(0, error_body.tell())
        self.assertNotIn("secret", json.dumps(response))

    def test_verified_receipt_requires_typed_consistent_runtime_evidence(self):
        receipt = {
            "status": "VERIFIED", "deployedSha": "a" * 40,
            "model": "gemini-2.5-flash", "providerCallAttempted": True,
            "providerResponseCompleted": True, "challengeValidated": True,
            "requestIdPresent": True, "latencyMs": 1200, "httpStatus": None}
        self.assertTrue(check.valid_receipt(receipt))
        self.assertFalse(check.valid_receipt(dict(receipt, status=[])))
        for field, invalid in (
                ("providerResponseCompleted", "true"),
                ("challengeValidated", 1),
                ("requestIdPresent", False),
                ("latencyMs", -1),
                ("deployedSha", None),
                ("model", None)):
            candidate = dict(receipt)
            candidate[field] = invalid
            self.assertFalse(check.valid_receipt(candidate), field)
        failed = dict(receipt, status="PROVIDER_CALL_FAILED",
                      providerResponseCompleted=False, challengeValidated=False,
                      requestIdPresent=False, latencyMs=50, httpStatus=429)
        self.assertTrue(check.valid_receipt(failed))
        failed["providerResponseCompleted"] = "false"
        self.assertFalse(check.valid_receipt(failed))

    def test_no_provider_call_when_provider_not_selected(self):
        config = {"Image": "aitradingsystem-paper-backend-arm64:" + "a" * 40,
                  "Env": ["APP_BUILD_REVISION=" + "a" * 40,
                          "NEWS_ANALYSIS_PROVIDER=NONE", "GEMINI_API_KEY=fixture"]}
        with patch.object(check.os, "geteuid", return_value=0), \
             patch.object(check.os, "uname", return_value=type("U", (), {"machine": "aarch64"})()), \
             patch.object(check.subprocess, "run", side_effect=[
                 type("P", (), {"returncode": 0, "stdout": "abc123456789\n"})(),
                 type("P", (), {"returncode": 0, "stdout": json.dumps(config)})()]), \
             patch.object(check, "call_gemini") as call:
            result = check.host()
        self.assertEqual("GEMINI_NOT_SELECTED", result["status"])
        self.assertFalse(result["providerCallAttempted"])
        call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
