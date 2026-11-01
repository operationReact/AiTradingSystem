import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation customer story is clear and safety bounded', () {
    final text = File('../../docs/product/paper-automation-user-story.md').readAsStringSync();
    expect(text, contains('choose Manual or Automated execution'));
    expect(text, contains('same server risk controls'));
    expect(text, contains('normal trading language'));
    expect(text, contains('pause trading'));
    expect(text, contains('cannot enable LIVE trading or broker submission'));
    expect(text, contains('Angel One gives read-only account/broker data'));
  });
}
