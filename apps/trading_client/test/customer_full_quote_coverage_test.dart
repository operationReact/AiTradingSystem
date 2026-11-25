import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

Map<String, dynamic> fixture() => {
  'contractVersion': 'customer.market.full-quote-coverage.v1',
  'generatedAt': '2026-11-13T05:00:00Z',
  'totalActiveListings': 6009,
  'storedFullQuotes': 925,
  'withProviderPreviousClose': 825,
  'activeBseCmRecords': 3500,
  'exchanges': [
    {
      'exchange': 'NSE', 'activeListings': 2801, 'storedFullQuotes': 875,
      'withProviderPreviousClose': 800,
      'newestExchangeAt': '2026-11-12T09:59:00Z',
    },
    {
      'exchange': 'BSE', 'activeListings': 3208, 'storedFullQuotes': 50,
      'withProviderPreviousClose': 25,
      'newestExchangeAt': '2026-11-12T09:58:00Z',
    },
  ],
};

void main() {
  test('stored FULL quote coverage spans the complete catalogue, not Nifty 50', () {
    final parsed = CustomerFullQuoteCoverage.fromJson(fixture());
    expect(parsed.totalActiveListings, 6009);
    expect(parsed.storedFullQuotes, 925);
    expect(parsed.exchanges[1].activeListings, 3208);
    expect(parsed.withProviderPreviousClose, 825);
    expect(parsed.activeBseCmRecords, 3500);
  });

  test('missing BSE quotes are reported as zero, not treated as live', () {
    final input = fixture()
      ..['totalActiveListings'] = 2801
      ..['storedFullQuotes'] = 875
      ..['withProviderPreviousClose'] = 800
      ..['activeBseCmRecords'] = 0;
    input['exchanges'] = <Map<String, dynamic>>[
      Map<String, dynamic>.from((input['exchanges'] as List).first as Map),
      {
        'exchange': 'BSE', 'activeListings': 0, 'storedFullQuotes': 0,
        'withProviderPreviousClose': 0, 'newestExchangeAt': null,
      },
    ];
    final parsed = CustomerFullQuoteCoverage.fromJson(input);
    expect(parsed.exchanges[1].storedFullQuotes, 0);
    expect(parsed.exchanges[1].newestExchangeAt, isNull);
  });

  test('wrong catalogue totals are rejected rather than silently displayed', () {
    final input = fixture()..['totalActiveListings'] = 50;
    expect(() => CustomerFullQuoteCoverage.fromJson(input), throwsFormatException);
  });

  test('invalid positive quote overcounts and wrong source timestamps are rejected', () {
    final input = fixture();
    (input['exchanges'] as List)[0] = {
      'exchange': 'NSE', 'activeListings': 2801, 'storedFullQuotes': 3000,
      'withProviderPreviousClose': 800,
    };
    expect(() => CustomerFullQuoteCoverage.fromJson(input), throwsFormatException);
    final badTime = fixture();
    ((badTime['exchanges'] as List)[0] as Map<String, dynamic>)['newestExchangeAt'] = 'unknown';
    expect(() => CustomerFullQuoteCoverage.fromJson(badTime), throwsFormatException);
  });
}
