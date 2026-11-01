import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('mobile PAPER automation completion requires real UI and visual acceptance', () {
    final text = File('../../docs/product/paper-automation-definition-of-done.md').readAsStringSync();
    expect(text, contains('Flutter Home/AI/Portfolio/More expose customer-grade controls'));
    expect(text, contains('canonical Neon-first/Clean-secondary design'));
    expect(text, contains('phone-size visual evidence'));
    expect(text, contains('Android APK packaging is green'));
  });
}
