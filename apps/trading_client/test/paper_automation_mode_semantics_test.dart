import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Manual and Automated are intent sources over the same PAPER risk path', () {
    final text = File('../../docs/product/paper-automation-manual-vs-automated.md').readAsStringSync();
    expect(text, contains('same authoritative PAPER risk/execution path'));
    expect(text, contains('customer reviews'));
    expect(text, contains('explicitly opts into Automated mode'));
    expect(text, contains('Emergency stop always overrides automation'));
    expect(text, contains('does not mean unrestricted trading, LIVE trading, broker submission, or bypassing risk'));
  });
}
