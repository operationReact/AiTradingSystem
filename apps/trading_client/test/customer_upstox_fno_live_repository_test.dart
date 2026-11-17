import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_upstox_fno_live_repository.dart';

Map<String, dynamic> capability({bool ready = false}) => {
  'configured': ready,
  'brokerConnected': ready,
  'nfoPermissionVerified': ready,
  'registeredIpMatches': ready,
  'contractSourceReady': ready,
  'sessionOpen': ready,
  'freshExchangeTimedQuotes': ready ? 1 : 0,
  'eligibleForOrderPreflight': ready,
  'reasons': ready ? <String>[] : <String>['LIVE_ORDER_POLICY_NOT_ACTIVATED'],
};

Map<String, dynamic> receipt({String state = 'UNKNOWN'}) => {
  'id': '12345678-1234-1234-1234-123456789012',
  'roomId': '12345678-1234-1234-1234-123456789013',
  'instrumentKey': 'NSE_FO|12345',
  'expiry': '2026-11-20',
  'side': 'BUY',
  'quantity': 65,
  'limitPrice': 120.0,
  'state': state,
  'orderTag': 'mt1234567890',
  'brokerOrderId': null,
  'outcomeCode': 'UPSTOX_FNO_ACK_UNCERTAIN',
  'createdAt': '2026-11-17T08:00:00Z',
};

void main() {
  test('blocked capability never implies live execution', () {
    final decoded = UpstoxFnoLiveCapability.decode(capability());
    expect(decoded.configured, isFalse);
    expect(decoded.eligibleForOrderPreflight, isFalse);
    expect(decoded.reasons, contains('LIVE_ORDER_POLICY_NOT_ACTIVATED'));
  });

  test('only fully coherent ready capability is accepted', () {
    expect(UpstoxFnoLiveCapability.decode(capability(ready: true))
        .eligibleForOrderPreflight, isTrue);
    final missingIp = capability(ready: true)
      ..['registeredIpMatches'] = false;
    expect(() => UpstoxFnoLiveCapability.decode(missingIp),
        throwsFormatException);
    final fakeFresh = capability(ready: true)
      ..['freshExchangeTimedQuotes'] = 0;
    expect(() => UpstoxFnoLiveCapability.decode(fakeFresh),
        throwsFormatException);
  });

  test('unknown order state remains distinct from a confirmed fill', () {
    final decoded = UpstoxFnoLiveReceipt.decode(receipt());
    expect(decoded.state, 'UNKNOWN');
    expect(decoded.brokerOrderId, isNull);
    expect(decoded.quantity, 65);
  });

  test('malformed live receipt never displays as order', () {
    final malformed = receipt()..['instrumentKey'] = 'SIM_FNO_CE';
    expect(() => UpstoxFnoLiveReceipt.decode(malformed),
        throwsFormatException);
    final badState = receipt(state: 'FILLED_SIMULATED');
    expect(() => UpstoxFnoLiveReceipt.decode(badState),
        throwsFormatException);
  });
}
