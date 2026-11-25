import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gemini_api_smoke as smoke


def sample_output():
    return {
        "sentiment": "NEUTRAL",
        "directionScore": 0,
        "category": "OTHER",
        "confidence": 0,
        "materiality": 0,
        "horizon": "UNKNOWN",
        "novelty": 0,
        "contradiction": False,
        "ambiguity": False,
        "impactedSymbols": [],
        "evidence": smoke.CHALLENGE,
        "risks": [],
    }


def response_bytes(output=None):
    output = sample_output() if output is None else output
    return json.dumps({
        "status": "completed",
        "id": "synthetic-request-id",
        "steps": [{
            "type": "model_output",
            "content": [{"type": "text", "text": json.dumps(output)}],
        }],
    }).encode()


class GeminiApiSmokeTests(unittest.TestCase):
    def test_request_never_stores_provider_response(self):
        self.assertFalse(smoke.request_body("gemini-2.5-flash")["store"])

    def test_valid_synthetic_challenge_is_accepted(self):
        ok, request_id = smoke.summarize(response_bytes())
        self.assertTrue(ok)
        self.assertTrue(request_id)

    def test_wrong_challenge_is_rejected(self):
        output = sample_output()
        output["evidence"] = "different"
        self.assertEqual(smoke.summarize(response_bytes(output)), (False, True))

    def test_run_uses_current_v1beta_endpoint(self):
        class FakeResponse:
            status = 200
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return None
            def read(self, _limit):
                return response_bytes()

        with patch.object(smoke.urllib.request, "build_opener") as build:
            build.return_value.open.return_value = FakeResponse()
            receipt = smoke.run("synthetic-test-key")
        request = build.return_value.open.call_args.args[0]
        self.assertEqual(
            request.full_url,
            "https://generativelanguage.googleapis.com/v1beta/interactions",
        )
        self.assertEqual(receipt["status"], "VERIFIED")
        self.assertNotIn("synthetic-test-key", json.dumps(receipt))

    def test_absent_key_fails_without_network_call(self):
        with patch.object(smoke.urllib.request, "build_opener") as build:
            receipt = smoke.run("")
        build.assert_not_called()
        self.assertEqual(receipt["status"], "SECRET_MISSING")
        self.assertFalse(receipt["providerCallAttempted"])

    def test_api_error_body_is_not_returned(self):
        error = smoke.urllib.error.HTTPError(
            "https://generativelanguage.googleapis.com/v1/interactions",
            401, "unauthorized", {}, None)
        with patch.object(smoke.urllib.request, "build_opener") as build:
            build.return_value.open.side_effect = error
            receipt = smoke.run("synthetic-test-key")
        self.assertEqual(receipt["status"], "PROVIDER_CALL_FAILED")
        self.assertEqual(receipt["httpStatus"], 401)
        self.assertNotIn("unauthorized", json.dumps(receipt))


if __name__ == "__main__":
    unittest.main()
