import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

Map<String, dynamic> fixture() => {
  'contractVersion': 'customer.market.universe.v1',
  'generatedAt': '2026-11-11T05:00:00Z',
  'catalogueActivatedAt': '2026-11-10T00:00:00Z',
  'totalActiveCashEquityListings': 6560,
  'exchanges': [
    {'exchange': 'NSE', 'activeListings': 3000},
    {'exchange': 'BSE', 'activeListings': 3560},
  ],
  'verifiedMemberships': [
    {
      'universeCode': 'NIFTY50', 'officialMembers': 50,
      'matchedActiveNseStocks': 50,
      'sourceAsOf': '2026-11-09', 'membershipSource': 'OFFICIAL_CONSTITUENTS',
    },
  ],
  'classificationStates': [
    {'family': 'MARKET_CAP', 'state': 'NOT_FULLY_INGESTED',
     'reason': 'Rank data pending'},
    {'family': 'NSE_SECTOR_INDUSTRY', 'state': 'NOT_FULLY_INGESTED',
     'reason': 'Industry mapping pending'},
  ],
};

void main() {
  test('uncapped NSE and BSE universe remains independent of index sizes', () {
    final parsed = CustomerUniverseFacets.fromJson(fixture());
    expect(parsed.totalListings, 6560);
    expect(parsed.exchanges['BSE'], 3560);
    expect(parsed.indices.single.members, 50);
    expect(parsed.indices.single.matched, 50);
    expect(parsed.classifications['MARKET_CAP'], 'NOT_FULLY_INGESTED');
  });

  test('exchange mismatch rejects unverified totals', () {
    final data = fixture();
    data['totalActiveCashEquityListings'] = 500;
    expect(() => CustomerUniverseFacets.fromJson(data), throwsFormatException);
  });

  test('overcounted constituent matches are rejected', () {
    final data = fixture();
    (data['verifiedMemberships'] as List).first['matchedActiveNseStocks'] = 51;
    expect(() => CustomerUniverseFacets.fromJson(data), throwsFormatException);
  });

  test('missing as-of date and exact classification state stay explicit', () {
    final data = fixture();
    final original = (data['verifiedMemberships'] as List).first
        as Map<String, dynamic>;
    data['verifiedMemberships'] = [
      <String, dynamic>{...original, 'sourceAsOf': null},
    ];
    final parsed = CustomerUniverseFacets.fromJson(data);
    expect(parsed.indices.single.sourceAsOf, isNull);
    expect(parsed.classifications['NSE_SECTOR_INDUSTRY'],
        'NOT_FULLY_INGESTED');
  });
}
