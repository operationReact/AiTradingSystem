#!/usr/bin/env python3
"""Policy consistency check, deliberately NOT Flutter build/runtime certification."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


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
    if gate.get('deliveryStatus') not in ('GAP', 'IN_PROGRESS', 'BLOCKED'):
        errors.append('No Flutter VERIFIED delivery claim is permitted by this initial correction gate.')
    first = gate.get('firstSlice', {})
    if first.get('id') != 'FC-03' or any(first.get(p) not in ('GAP', 'UNVERIFIED', 'IN_PROGRESS', 'BLOCKED') for p in ('web', 'android', 'ios')):
        errors.append('The three-platform vertical slice has no verified acceptance yet; do not manufacture a pass.')
    if gate.get('releaseAcceptance') != 'BLOCKED_PENDING_THREE_PLATFORM_EVIDENCE':
        errors.append('Release acceptance remains blocked until a reviewed platform-evidence gate replaces this initial lock.')
    legacy = [p for p in changed if p.startswith(('frontend/src/', 'frontend/e2e/'))]
    if legacy:
        errors.append('New React product UI work is stopped by FLUTTER-HARD-GATE: ' + ', '.join(legacy))
    if any(p.startswith('apps/trading_client/') for p in changed):
        for required in ('pubspec.yaml', 'lib/main.dart', 'web/index.html', 'ios/Runner.xcodeproj/project.pbxproj'):
            if not (root / 'apps/trading_client' / required).is_file():
                errors.append('Flutter foundation is missing ' + required)
        android = root / 'apps/trading_client/android/app'
        if not any((android / name).is_file() for name in ('build.gradle.kts', 'build.gradle')):
            errors.append('Flutter foundation is missing the Android app build file')
        workflow = root / '.github/workflows/flutter-client.yml'
        text = workflow.read_text() if workflow.is_file() else ''
        for command in ('flutter analyze', 'flutter test', 'flutter build web', 'flutter build apk', 'flutter build ios'):
            if command not in text:
                errors.append('Flutter foundation must introduce mandatory CI command: ' + command)
    if require_complete:
        errors.append('FLUTTER-HARD-GATE: product deployment is blocked. Flutter web/Android/iOS implementation and runtime acceptance are not verified. React CI cannot release this gate.')
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-sha')
    parser.add_argument('--require-complete', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    changed = []
    if args.base_sha:
        # argv array, not a shell; reject non-commit inputs before constructing a revision range.
        if len(args.base_sha) != 40 or any(c not in '0123456789abcdef' for c in args.base_sha):
            parser.error('--base-sha must be a full lowercase commit SHA')
        result = subprocess.run(['git', 'diff', '--name-only', '--diff-filter=ACMRT', args.base_sha + '...HEAD', '--'], cwd=root, capture_output=True, text=True, check=True)
        changed = result.stdout.splitlines()
    errors = validate(root, changed, args.require_complete)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print('Flutter delivery policy is consistent. Flutter product/platform delivery remains unverified; this is NOT a build or runtime acceptance pass.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
