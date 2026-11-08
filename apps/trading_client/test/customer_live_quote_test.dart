import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

void main() {
  test('authenticated provider tick has explicit exchange time and source', () {
    final parsed = CustomerLiveQuote.fromJson({
      'instrumentId': 42, 'status': 'LIVE', 'ltp': 1254.55,
      'source': 'ANGEL_ONE_SMARTAPI_WS_V2',
      'exchangeAt': '2026-11-08T09:00:00Z',
    });
    expect(parsed.instrumentId, 42);
    expect(parsed.status, 'LIVE');
    expect(parsed.ltp, 1254.55);
    expect(parsed.exchangeAt!.isUtc, true);
  });

  test('stale, reconnecting and market closed snapshots never contain prices', () {
    for (final state in ['STALE', 'RECONNECTING', 'MARKET_CLOSED',
      'BROKER_CONNECTION_REQUIRED', 'CONNECTING', 'UNAVAILABLE']) {
      final result = CustomerLiveQuote.fromJson({
        'instrumentId': 42, 'status': state, 'ltp': null,
        'source': null, 'exchangeAt': null,
      });
      expect(result.ltp, isNull);
      expect(result.exchangeAt, isNull);
      expect(result.status, state);
      expect(() => CustomerLiveQuote.fromJson({
        'instrumentId': 42, 'status': state, 'ltp': 100,
      }), throwsFormatException);
    }
  });

  test('invented or malformed LIVE samples are rejected', () {
    final valid = {
      'instrumentId': 42, 'status': 'LIVE', 'ltp': 125,
      'exchangeAt': '2026-11-08T09:00:00Z',
      'source': 'ANGEL_ONE_SMARTAPI_WS_V2',
    };
    for (final wrong in [
      {...valid, 'ltp': -1},
      {...valid, 'source': 'SIMULATED'},
      {...valid, 'exchangeAt': 'not-a-time'},
      {...valid, 'instrumentId': -1},
    ]) {
      expect(() => CustomerLiveQuote.fromJson(wrong), throwsFormatException);
    }
  });
}
