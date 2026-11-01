import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Android PAPER automation acceptance requires real backend device evidence', () {
    final text = File('../../docs/product/paper-automation-android-acceptance.md').readAsStringSync();
    expect(text, contains('phone-reachable HTTPS PAPER backend'));
    expect(text, contains('Secure Sign In/account-scoped session works on device'));
    expect(text, contains('PAPER + Manual/Automated preference state'));
    expect(text, contains('no LIVE broker order submission is possible'));
    expect(text, contains('CI APK using a non-routable placeholder endpoint is packaging evidence only'));
  });
}
