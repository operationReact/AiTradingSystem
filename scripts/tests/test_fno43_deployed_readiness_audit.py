"""Offline proof: strict bounded 43-strategy deployed audit and no-order scope."""
import io
import importlib.util
from pathlib import Path
import sys
import unittest
import urllib.error
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "infra/ec2-paper/fno43_deployed_readiness_audit.py"
SPEC = importlib.util.spec_from_file_location("fno43_deployed_readiness_audit", SCRIPT)
mod = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = mod
SPEC.loader.exec_module(mod)


def samples():
    ids = [{"strategyId": f"strategy.STR-{n}"} for n in range(170, 213)]
    readiness = {
        "contractVersion": "minttick.fno43.runtime-source-readiness.v1",
        "manifestStrategies": 43,
        "distinctEvaluatorsObserved": 10,
        "independentlyVerifiedSourceStrategies": 2,
        "historicalDataE2EValidated": 0,
        "paperExecutionValidated": 0,
        "liveExecutionValidated": 0,
        "strategies": ids,
    }
    source = {
        "contractVersion": "minttick.fno43.runtime-source-audit.v1",
        "expectedStrategies": 43,
        "eligibleRightsAttestedEnvelopes": 0,
        "independentMarketE2EValidated": 0,
        "paperLedgerE2EValidated": 0,
        "liveBrokerE2EValidated": 0,
        "brokerOrdersSubmitted": False,
        "strategies": ids,
    }
    return readiness, source


class _Response:
    def __init__(self, raw, status=200):
        self.status = status
        self.raw = raw
    def __enter__(self):
        return self
    def __exit__(self, *args):
        return False
    def read(self, count):
        return self.raw[:count]


class _Opener:
    def __init__(self, response):
        self.response = response
        self.calls = []
    def open(self, request, timeout):
        self.calls.append(request)
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


class Fno43DeployedAuditTest(unittest.TestCase):
    def test_request_exact_account_host_read_only_and_no_customer_auth(self):
        request = mod.expected_request()
        self.assertEqual(request["instance_id"], "i-09c01b470fb39b85a")
        self.assertEqual(request["account"], "098859530205")
        self.assertTrue(all(x.startswith("GET /api/admin/") for x in request["operations"]))
        self.assertTrue(request["no_order_calls"])
        self.assertTrue(request["no_customer_auth"])

    def test_exact_real_evidence_metrics_not_promoted_by_adapter_count(self):
        readiness, source = samples()
        nums = mod.check_payload(readiness, source)
        self.assertEqual(nums["distinct"], 10)
        self.assertEqual(nums["sourceRights"], 2)
        self.assertEqual(nums["historical"], 0)
        self.assertEqual(nums["paper"], 0)
        self.assertEqual(nums["live"], 0)
        self.assertEqual(mod.receipt(mod.sanitize("OBSERVED", nums)),
                         mod.sanitize("OBSERVED", nums))

    def test_reject_missing_duplicated_or_shuffled_43_strategies(self):
        a, b = samples()
        for key, value in (("strategies", a["strategies"][:-1]),
                           ("strategies", list(reversed(a["strategies"]))),
                           ("manifestStrategies", 42)):
            broken = dict(a)
            broken[key] = value
            with self.subTest(key=key, value=str(value)[:30]):
                with self.assertRaises(ValueError):
                    mod.check_payload(broken, b)
        broken = dict(b)
        broken["strategies"] = b["strategies"][:42] + [b["strategies"][41]]
        with self.assertRaises(ValueError):
            mod.check_payload(a, broken)

    def test_reject_order_activity_or_inflated_counts(self):
        a, b = samples()
        for key, value in (("brokerOrdersSubmitted", True),
                           ("independentMarketE2EValidated", 44)):
            bad = dict(b)
            bad[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                mod.check_payload(a, bad)

    def test_receipt_rejects_fake_values_duplicate_and_unbounded_output(self):
        line = mod.sanitize("AUTH_REJECTED")
        self.assertEqual(mod.receipt(line), line)
        self.assertIsNone(mod.receipt(line + "\n" + line))
        self.assertIsNone(mod.receipt(line.replace("orders=false", "orders=true")))
        self.assertIsNone(mod.receipt(line.replace("historical=0", "historical=44")))
        self.assertIsNone(mod.receipt("token=" + "x" * 2600))
        with self.assertRaises(ValueError):
            mod.sanitize("OBSERVED", {"historical": 10})

    def test_api_reads_exact_loopback_admin_get_with_api_key_not_emitted(self):
        opener = _Opener(_Response(b'{"hello":"world"}'))
        status, data = mod._get("readiness", "fake-key", opener)
        self.assertEqual(status, "OBSERVED")
        self.assertEqual(data, {"hello": "world"})
        request = opener.calls[0]
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.full_url,
            "http://127.0.0.1:8080/api/admin/research/fno-43/readiness")
        self.assertEqual(request.get_header("X-api-key"), "fake-key")
        self.assertEqual(request.get_header("User-agent"),
            "MintTick-Fno43-OperatorAudit/1.0")

    def test_unauthorized_and_missing_route_return_fixed_classifications(self):
        for code, expected in ((401, "AUTH_REJECTED"), (403, "AUTH_REJECTED"),
                               (404, "ROUTE_NOT_DEPLOYED"), (503, "BACKEND_ERROR")):
            with self.subTest(code=code):
                opener = _Opener(urllib.error.HTTPError(
                    mod.BASE + "readiness", code, "SECRET PROVIDER RESPONSE", {}, None))
                state, data = mod._get("readiness", "fake-key", opener)
                self.assertEqual((state, data), (expected, None))

    def test_invalid_json_and_large_body_classified_without_content(self):
        for body, expected in ((b'not-json', "INVALID_RESPONSE"),
                               (b'x' * (mod.MAX_RESPONSE + 1), "INVALID_RESPONSE")):
            with self.subTest(length=len(body)):
                state, data = mod._get("source-evidence", "key",
                                       _Opener(_Response(body)))
                self.assertEqual((state, data), (expected, None))


if __name__ == "__main__":
    unittest.main()
