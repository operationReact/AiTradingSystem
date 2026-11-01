import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation CI contract requires Flutter visual and APK evidence', () {
    final text = File('../../docs/product/paper-automation-ci-gates.md').readAsStringSync();
    expect(text, contains('Flutter analyze and full unit/widget tests'));
    expect(text, contains('visual golden regression'));
    expect(text, contains('Android APK packaging/delivery gate'));
    expect(text, contains('CI green by itself does not satisfy phone visual or device acceptance'));
  });
}
