import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation delivery excludes production live and store mutations', () {
    final text = File('../../docs/product/paper-automation-delivery-boundary.md').readAsStringSync();
    expect(text, contains('production or cloud deployment'));
    expect(text, contains('Android/iOS signing or store publication'));
    expect(text, contains('LIVE trading activation'));
    expect(text, contains('broker order submission'));
  });
}
