import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation phase exit requires real auth UI visual and device acceptance', () {
    final text = File('../../docs/product/paper-automation-exit-criteria.md').readAsStringSync();
    expect(text, contains('Secure Sign In/account session'));
    expect(text, contains('actual Flutter customer controls/lifecycle implemented'));
    expect(text, contains('canonical Figma phone evidence accepted'));
    expect(text, contains('backend-connected Android physical-device acceptance'));
  });
}
