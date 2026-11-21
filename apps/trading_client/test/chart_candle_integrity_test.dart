import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

Map<String, dynamic> valid(String instant) => {
  'candleTime': instant,
  'open': 100.0,
  'high': 112.0,
  'low': 98.0,
  'close': 110.0,
  'volume': 12000,
  'source': 'ANGEL_ONE_VERIFIED_ARCHIVE',
  'fetchedAt': '2026-11-21T10:00:00Z',
};

Map<String, dynamic> page(List<Map<String, dynamic>> items) => {
  'instrumentId': 101,
  'interval': 'ONE_DAY',
  'items': items,
  'hasNext': false,
};

void main() {
  test('accepts a sourced, finite, consistent OHLCV candle', () {
    final bar = CustomerCandle.fromJson(valid('2026-11-19T10:00:00Z'));
    expect(bar.open, 100);
    expect(bar.close, 110);
    expect(bar.volume, 12000);
  });

  test('rejects high below open or close, and low above open or close', () {
    for (final fields in [
      <String, Object>{'high': 99},
      <String, Object>{'high': 109},
      <String, Object>{'low': 101},
      <String, Object>{'low': 111},
    ]) {
      expect(
        () => CustomerCandle.fromJson({
          ...valid('2026-11-19T10:00:00Z'), ...fields,
        }),
        throwsFormatException,
      );
    }
  });

  test('rejects unusable volume and blank source', () {
    for (final fields in [
      <String, Object>{'volume': 1.5},
      <String, Object>{'volume': double.infinity},
      <String, Object>{'volume': -1},
      <String, Object>{'source': '    '},
    ]) {
      expect(
        () => CustomerCandle.fromJson({
          ...valid('2026-11-19T10:00:00Z'), ...fields,
        }),
        throwsFormatException,
      );
    }
  });

  test('rejects duplicate timestamps before the chart draws ambiguous bars', () {
    final bar = valid('2026-11-19T10:00:00Z');
    expect(
      () => CustomerCandlePage.fromJson(page([bar, {...bar, 'close': 108}])),
      throwsFormatException,
    );
  });

  test('accepts distinct but out-of-order stored bars for UI sorting', () {
    final result = CustomerCandlePage.fromJson(page([
      valid('2026-11-20T10:00:00Z'),
      valid('2026-11-19T10:00:00Z'),
    ]));
    expect(result.items, hasLength(2));
    expect(result.items.first.candleTime.isAfter(result.items.last.candleTime), isTrue);
  });
}
