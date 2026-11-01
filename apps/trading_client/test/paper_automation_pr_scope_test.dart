import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation slice cannot claim completion from CI alone', () {
    final text = File('../../docs/product/paper-automation-pr-scope.md').readAsStringSync();
    expect(text, contains('actual Flutter screens'));
    expect(text, contains('phone-size visual evidence'));
    expect(text, contains('Android APK packages successfully'));
    expect(text, contains('LIVE broker submission remains disabled'));
  });
}
