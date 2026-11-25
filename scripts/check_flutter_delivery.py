#!/usr/bin/env python3
"""Flutter delivery-policy and release-evidence consistency check."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


INCOMPLETE = {'GAP', 'UNVERIFIED', 'IN_PROGRESS', 'BLOCKED'}


def validate(root, changed=(), require_complete=False):
    errors = []
    try:
        gate = json.loads((root / 'docs/flutter-delivery-gate.json').read_text())
    except (OSError, ValueError) as exc:
        return [f'Flutter delivery policy is missing or invalid: {exc}']

    if gate.get('target') != 'Flutter' or gate.get('phaseCount') != 22:
        errors.append('The target must remain Flutter across all 22 Notion workstreams.')
    if gate.get('requiredPlatforms') != ['web', 'android', 'ios']:
        errors.append('Web, Android and iOS are all mandatory; no platform may be silently removed.')
    if gate.get('flutterRoot') != 'apps/trading_client' or gate.get('legacyFrontendReferenceOnly') is not True:
        errors.append('Preserve the Flutter client root and legacy React reference-only boundary.')

    delivery_status = gate.get('deliveryStatus')
    if delivery_status not in INCOMPLETE | {'VERIFIED'}:
        errors.append('Flutter delivery status must be an allowed incomplete state or VERIFIED.')

    first = gate.get('firstSlice', {})
    if first.get('id') != 'FC-03':
        errors.append('The canonical first Flutter vertical slice must remain FC-03.')
    platform_states = [first.get(p) for p in ('web', 'android', 'ios')]
    if any(state not in INCOMPLETE | {'VERIFIED'} for state in platform_states):
        errors.append('Each required platform must have an explicit delivery state.')

    release_acceptance = gate.get('releaseAcceptance')
    if release_acceptance not in {
        'BLOCKED_PENDING_THREE_PLATFORM_EVIDENCE',
        'VERIFIED_THREE_PLATFORM_EVIDENCE',
    }:
        errors.append('Release acceptance must remain blocked or explicitly verified from three-platform evidence.')

    legacy = [p for p in changed if p.startswith(('frontend/src/', 'frontend/e2e/'))]
    if legacy:
        errors.append('New React product UI work is stopped by FLUTTER-HARD-GATE: ' + ', '.join(legacy))

    if any(p.startswith('apps/trading_client/') or p.startswith('.github/workflows/flutter-') for p in changed):
        for required in ('pubspec.yaml', 'lib/main.dart', 'web/index.html', 'ios/Runner.xcodeproj/project.pbxproj'):
            if not (root / 'apps/trading_client' / required).is_file():
                errors.append('Flutter foundation is missing ' + required)
        android = root / 'apps/trading_client/android/app'
        if not any((android / name).is_file() for name in ('build.gradle.kts', 'build.gradle')):
            errors.append('Flutter foundation is missing the Android app build file')

        fast_workflow = root / '.github/workflows/flutter-client.yml'
        fast_text = fast_workflow.read_text() if fast_workflow.is_file() else ''
        for command in ('flutter analyze', 'flutter test', 'flutter build web'):
            if command not in fast_text:
                errors.append('Fast Flutter CI must retain mandatory command: ' + command)
        if 'cancel-in-progress: true' not in fast_text:
            errors.append('Fast Flutter CI must cancel superseded PR runs to control Actions cost.')
        if 'timeout-minutes: 5' not in fast_text:
            errors.append('Fast Flutter CI must enforce the five-minute development feedback budget.')
        if '--no-wasm-dry-run' not in fast_text:
            errors.append('Fast Flutter Web build must skip the redundant Wasm dry run.')

        native_workflow = root / '.github/workflows/flutter-connected-runtime.yml'
        native_text = native_workflow.read_text() if native_workflow.is_file() else ''
        for marker in (
            'native-sensitive',
            'phase-completion',
            'release-candidate',
            'manual-investigation',
            'android-runtime:',
            'ios-runtime:',
            'reactivecircus/android-emulator-runner@v2',
            'Connected iOS Simulator journey',
            'integration_test/connected_health_app_test.dart',
        ):
            if marker not in native_text:
                errors.append('Phase-boundary/native-sensitive CI is missing required evidence marker: ' + marker)
        if 'workflow_dispatch:' not in native_text:
            errors.append('Phase-boundary native CI must retain explicit dispatch support.')
        if 'types: [ready_for_review]' in native_text:
            errors.append('Native CI must not run merely because every ordinary PR becomes ready for review.')

    if delivery_status == 'VERIFIED':
        if platform_states != ['VERIFIED', 'VERIFIED', 'VERIFIED']:
            errors.append('VERIFIED delivery requires Web, Android and iOS evidence to all be VERIFIED.')
        if release_acceptance != 'VERIFIED_THREE_PLATFORM_EVIDENCE':
            errors.append('VERIFIED delivery requires VERIFIED_THREE_PLATFORM_EVIDENCE acceptance.')

    if require_complete:
        evidence = gate.get('evidence', {})
        connected = evidence.get('connectedRuntime', {})
        android_packaging = evidence.get('androidPackaging', {})
        safety = gate.get('safety', {})

        if delivery_status != 'VERIFIED' or release_acceptance != 'VERIFIED_THREE_PLATFORM_EVIDENCE':
            errors.append('FLUTTER-HARD-GATE: product deployment remains blocked until delivery is VERIFIED.')
        if connected.get('status') != 'success' or connected.get('platforms') != ['web', 'android', 'ios']:
            errors.append('FLUTTER-HARD-GATE: successful connected Web/Android/iOS runtime evidence is required.')
        if not connected.get('workflowRunId') or not connected.get('headSha'):
            errors.append('FLUTTER-HARD-GATE: connected-runtime run ID and reviewed head SHA are required.')
        if android_packaging.get('status') != 'success' or not android_packaging.get('workflowRunId') or not android_packaging.get('headSha'):
            errors.append('FLUTTER-HARD-GATE: successful Android packaging evidence is required.')
        if safety.get('executionMode') != 'PAPER' or safety.get('liveBrokerSubmissionEnabled') is not False:
            errors.append('FLUTTER-HARD-GATE: this deployment path must remain PAPER-only with LIVE broker submission disabled.')

    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-sha')
    parser.add_argument('--require-complete', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    changed = []
    if args.base_sha:
        if len(args.base_sha) != 40 or any(c not in '0123456789abcdef' for c in args.base_sha):
            parser.error('--base-sha must be a full lowercase commit SHA')
        result = subprocess.run(
            ['git', 'diff', '--name-only', '--diff-filter=ACMRT', args.base_sha + '...HEAD', '--'],
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        )
        changed = result.stdout.splitlines()
    errors = validate(root, changed, args.require_complete)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    if args.require_complete:
        print('Flutter delivery hard gate passed from recorded three-platform runtime, Android packaging, and PAPER-only safety evidence.')
    else:
        print('Flutter delivery policy is consistent. Fast development CI remains separate from phase-boundary runtime acceptance.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
