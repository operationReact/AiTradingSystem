import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('mobile PAPER automation follows customer and release principles', () {
    final text = File('../../docs/product/paper-automation-product-principles.md').readAsStringSync();
    expect(text, contains('Customer language'));
    expect(text, contains('Explicit opt-in'));
    expect(text, contains('customers can understand what automation is doing'));
    expect(text, contains('Release honesty'));
  });
}
