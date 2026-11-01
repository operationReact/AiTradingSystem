import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation foundation checkpoint keeps real UI and device gates open', () {
    final text = File('../../docs/product/paper-automation-final-foundation-checkpoint.md').readAsStringSync();
    expect(text, contains('production integration remains open'));
    expect(text, contains('actual Flutter UI must be implemented'));
    expect(text, contains('exact-head/visual/APK/device gates must pass'));
    expect(text, contains('LIVE unavailable; broker submission unavailable'));
  });
}
