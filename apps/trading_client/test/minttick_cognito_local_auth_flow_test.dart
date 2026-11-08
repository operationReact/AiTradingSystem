import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/auth/customer_identity.dart';

void main() {
  test('password flow can only be selected in explicit web DEBUG', () {
    expect(MintTickLocalAuthFlow.usePasswordAuth(
      debugMode: true, web: true, optIn: true), isTrue);
    for (final (debug, web, optIn) in <(bool, bool, bool)>[
      (false, true, true),
      (true, false, true),
      (true, true, false),
      (false, false, true),
    ]) {
      expect(MintTickLocalAuthFlow.usePasswordAuth(
        debugMode: debug, web: web, optIn: optIn), isFalse);
    }
  });

  test('regular test build uses default SRP', () {
    expect(MintTickLocalAuthFlow.enabled, isFalse);
    expect(MintTickLocalAuthFlow.requested, isFalse);
  });
}
