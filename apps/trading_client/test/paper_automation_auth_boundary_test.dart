import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation release requires real account-scoped Secure Sign In', () {
    final text = File('../../docs/product/paper-automation-auth-boundary.md').readAsStringSync();
    expect(text, contains('development-only session bootstrap is not acceptable'));
    expect(text, contains('authenticated server session for a persisted user/account'));
    expect(text, contains('logout/session expiry immediately prevents new Manual/Automated trade intents'));
    expect(text, contains('Do not embed a shared API key or development session bootstrap'));
  });
}
