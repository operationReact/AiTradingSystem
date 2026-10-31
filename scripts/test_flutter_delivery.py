import copy
import json
from pathlib import Path
import tempfile
import unittest
from check_flutter_delivery import validate


class FlutterDeliveryPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'docs').mkdir()
        self.policy = json.loads((Path(__file__).resolve().parents[1] / 'docs/flutter-delivery-gate.json').read_text())
        self.write(self.policy)

    def write(self, policy):
        (self.root / 'docs/flutter-delivery-gate.json').write_text(json.dumps(policy))

    def test_backend_only_work_can_proceed_with_recorded_flutter_delivery_evidence(self):
        self.assertEqual([], validate(self.root, ['backend/src/main/java/Example.java']))

    def test_new_react_ui_cannot_satisfy_or_bypass_the_flutter_authority(self):
        self.assertTrue(validate(self.root, ['frontend/src/pages/Research.jsx']))
        self.assertTrue(validate(self.root, ['frontend/e2e/pretend-flutter.spec.mjs']))

    def test_missing_platform_or_inconsistent_verified_claim_is_rejected(self):
        mutations = (
            lambda p: p.update(requiredPlatforms=['web', 'android']),
            lambda p: p['firstSlice'].update(ios='UNVERIFIED'),
            lambda p: p['firstSlice'].update(ios='PASS'),
            lambda p: p.update(releaseAcceptance='BLOCKED_PENDING_THREE_PLATFORM_EVIDENCE'),
        )
        for mutate in mutations:
            policy = copy.deepcopy(self.policy)
            mutate(policy)
            self.write(policy)
            self.assertTrue(validate(self.root))

    def test_release_passes_only_with_recorded_three_platform_and_paper_safety_evidence(self):
        self.assertEqual([], validate(self.root, require_complete=True))

        mutations = (
            lambda p: p['evidence']['connectedRuntime'].update(status='failure'),
            lambda p: p['evidence']['connectedRuntime'].update(workflowRunId=None),
            lambda p: p['evidence']['androidPackaging'].update(status='failure'),
            lambda p: p['safety'].update(executionMode='LIVE'),
            lambda p: p['safety'].update(liveBrokerSubmissionEnabled=True),
        )
        for mutate in mutations:
            policy = copy.deepcopy(self.policy)
            mutate(policy)
            self.write(policy)
            self.assertTrue(validate(self.root, require_complete=True))

    def test_flutter_foundation_cannot_omit_platform_projects_or_phase_native_gate(self):
        errors = validate(self.root, ['apps/trading_client/lib/main.dart'])
        self.assertTrue(any('ios/' in error for error in errors))
        self.assertTrue(any('Phase-boundary/native-sensitive CI' in error for error in errors))


if __name__ == '__main__':
    unittest.main()
