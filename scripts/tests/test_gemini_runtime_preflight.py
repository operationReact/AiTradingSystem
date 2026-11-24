"""Fixtures validate redaction/transport boundaries; never certify live Gemini."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import MagicMock, Mock, patch
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('gemini_preflight', ROOT / 'infra/ec2-paper/gemini_runtime_preflight.py')
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class GeminiRuntimePreflightTest(unittest.TestCase):
    def config(self, *extra):
        return {'Image': 'aitradingsystem-paper-backend-arm64:' + 'a' * 40,
                'Env': ['APP_BUILD_REVISION=' + 'a' * 40,
                        'APP_API_KEY=fixture-private-operator-key', *extra]}

    def test_missing_binding_stays_unverified_and_selected_none(self):
        result = probe.summarize_config(self.config())
        self.assertFalse(result['geminiKeyEnvironmentPresent'])
        self.assertEqual('NONE', result['selectedProviderEnvironment'])
        self.assertFalse(result['runtimeConnectivityVerified'])
        self.assertFalse(result['providerCallAttempted'])

    def test_secret_values_and_arbitrary_provider_never_escape(self):
        result = probe.summarize_config(self.config('GEMINI_API_KEY=fixture-private-gemini-key',
            'GEMINI_MODEL=fixture-private-value', 'NEWS_ANALYSIS_PROVIDER=fixture-secret'))
        self.assertTrue(result['geminiKeyEnvironmentPresent'])
        self.assertTrue(result['geminiModelEnvironmentPresent'])
        self.assertEqual('UNKNOWN', result['selectedProviderEnvironment'])
        self.assertNotIn('fixture', json.dumps(result))

    def test_present_key_does_not_certify_runtime(self):
        result = probe.summarize_config(self.config('GEMINI_API_KEY=fixture', 'NEWS_ANALYSIS_PROVIDER=GEMINI'))
        self.assertEqual('GEMINI', result['selectedProviderEnvironment'])
        self.assertFalse(result['runtimeConnectivityVerified'])

    def test_blank_key_is_absent(self):
        self.assertFalse(probe.summarize_config(self.config('GEMINI_API_KEY=   '))['geminiKeyEnvironmentPresent'])

    def test_revision_mismatch_duplicate_env_and_unknown_image_are_rejected(self):
        for config in (self.config('APP_BUILD_REVISION=' + 'b' * 40),
                       {**self.config(), 'Image': 'arbitrary:latest'},
                       {**self.config(), 'Env': ['APP_BUILD_REVISION=' + 'b' * 40]}):
            with self.assertRaises(ValueError):
                probe.summarize_config(config)

    def test_receipt_is_one_exact_allowlisted_object_without_certification(self):
        valid = probe.report()
        self.assertEqual(valid, probe.receipt(probe.MARKER + json.dumps(valid)))
        for raw in ('', probe.MARKER + json.dumps({**valid, 'secret': 'fixture'}),
                    probe.MARKER + json.dumps({**valid, 'runtimeConnectivityVerified': True}),
                    probe.MARKER + json.dumps({**valid, 'providerCallAttempted': True}),
                    probe.MARKER + json.dumps({**valid, 'adminHealthHttpStatus': True}),
                    probe.MARKER + json.dumps({**valid, 'deployedSha': 'fixture-secret'}),
                    probe.MARKER + json.dumps(valid) + '\nextra output'):
            with self.assertRaises(ValueError):
                probe.receipt(raw)

    def test_admin_http_uses_only_local_get_and_does_not_read_body(self):
        opener = MagicMock()
        response = opener.open.return_value.__enter__.return_value
        response.status = 200
        self.assertEqual(200, probe.admin_health_status('fixture-private-key', opener))
        request = opener.open.call_args.args[0]
        self.assertEqual('GET', request.method)
        self.assertEqual('http://127.0.0.1:8080/api/admin/market-intelligence/health', request.full_url)
        response.read.assert_not_called()

    def test_admin_denial_is_evidence_and_never_an_auth_bypass(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError('http://127.0.0.1', 403, 'denied', {}, io.BytesIO())
        self.assertEqual(403, probe.admin_health_status('fixture', opener))
        opener.open.assert_called_once()
        self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://elsewhere'))

    def test_non_root_or_wrong_architecture_cannot_inspect(self):
        with patch.object(probe.os, 'geteuid', return_value=1000), patch.object(probe, 'docker') as docker:
            self.assertEqual('INVALID_HOST', probe.host()['status'])
            docker.assert_not_called()


if __name__ == '__main__':
    unittest.main()
