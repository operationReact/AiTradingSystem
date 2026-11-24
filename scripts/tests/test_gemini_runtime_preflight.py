"""Fixtures validate redaction/transport boundaries; never certify live Gemini."""
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, Mock, patch
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('gemini_preflight', ROOT / 'infra/ec2-paper/gemini_runtime_preflight.py')
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class GeminiRuntimePreflightTest(unittest.TestCase):
    def test_installer_file_requirements_distinguish_missing_mode_owner_and_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'collector.py'
            self.assertEqual('MISSING', probe.file_status(path, 0o600, os.geteuid()))
            path.write_text('fixture')
            path.chmod(0o600)
            self.assertEqual('OK', probe.file_status(path, 0o600, os.geteuid()))
            self.assertEqual('WRONG_OWNER', probe.file_status(path, 0o600, os.geteuid() + 1))
            path.chmod(0o700)
            self.assertEqual('WRONG_MODE', probe.file_status(path, 0o600, os.geteuid()))
            self.assertEqual('OK', probe.file_status(path, 0o700, os.geteuid()))
            link = Path(tmp) / 'link'
            link.symlink_to(path)
            self.assertEqual('SYMLINK', probe.file_status(link, 0o700, os.geteuid()))

    def test_history_failure_exports_only_allowlisted_code_from_relevant_stage(self):
        raw = ('Traceback fixture-private-path\n'
               'owner_history_progress.ProgressUnavailable: DUPLICATE_ARCHIVE_SESSION\n'
               'MINTTICK_DEPLOY_FAILED_STAGE:history_precheck\n')
        self.assertEqual('DUPLICATE_ARCHIVE_SESSION', probe.classify_history_failure(raw))
        self.assertEqual('NOT_OBSERVED', probe.classify_history_failure(raw.replace('history_precheck', 'host_activation')))
        self.assertEqual('UNCLASSIFIED_HISTORY_FAILURE', probe.classify_history_failure(
            raw.replace('DUPLICATE_ARCHIVE_SESSION', 'PRIVATE_SECRET_VALUE')))
        self.assertNotIn('fixture', probe.classify_history_failure(raw))

    def test_history_failure_classifies_sqlite_and_filesystem_without_private_details(self):
        cases = {
            'sqlite3.OperationalError: no such table: fixture-private-table': 'HISTORY_SCHEMA_MISSING',
            'sqlite3.OperationalError: fixture-private-message': 'HISTORY_SQLITE_OPERATIONAL_FAILURE',
            'OSError: [Errno 28] No space left on device: fixture-private-path': 'FILESYSTEM_DISK_FULL',
            'PermissionError: fixture-private-path': 'FILESYSTEM_PERMISSION_DENIED',
            'FileNotFoundError: fixture-private-path': 'FILESYSTEM_FILE_MISSING',
        }
        for error, expected in cases.items():
            raw = error + '\nMINTTICK_DEPLOY_FAILED_STAGE:history_precheck\n'
            self.assertEqual(expected, probe.classify_history_failure(raw))
            self.assertNotIn('fixture', probe.classify_history_failure(raw))

    def test_pr_validation_cannot_supersede_shared_host_deployment_queue(self):
        workflow = (ROOT / '.github/workflows/minttick-gemini-runtime-preflight.yml').read_text()
        self.assertNotIn('concurrency:\n  group: aitrading-paper-ec2-manual-lifecycle', workflow)
        self.assertIn('    concurrency:\n      group: aitrading-paper-ec2-manual-lifecycle\n'
                      '      cancel-in-progress: false\n      queue: max', workflow)

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

    def test_host_settings_do_not_execute_or_export_secret_values(self):
        summary = probe.summarize_host_settings('GEMINI_API_KEY="fixture-private-key"\nNEWS_ANALYSIS_PROVIDER=GEMINI\nOTHER=$(do-not-execute)')
        self.assertEqual({'hostSettingsReadable': True, 'hostGeminiKeyPresent': True,
                          'hostSelectedProviderEnvironment': 'GEMINI'}, summary)
        self.assertNotIn('fixture', json.dumps(summary))
        for value in ('', '""', "''", '   '):
            self.assertFalse(probe.summarize_host_settings('GEMINI_API_KEY=' + value)['hostGeminiKeyPresent'])
        with self.assertRaises(ValueError):
            probe.summarize_host_settings('GEMINI_API_KEY=a\nGEMINI_API_KEY=b')

    def test_unreadable_host_settings_are_unknown_instead_of_absent(self):
        self.assertIsNone(probe.report()['hostGeminiKeyPresent'])
        with self.assertRaises(ValueError):
            probe.validate_report({**probe.report(), 'hostGeminiKeyPresent': False})

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
