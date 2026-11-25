import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

Map<String, dynamic> profile() => {
  'contractVersion': 'customer.market.company-profile.v1',
  'instrumentId': 101,
  'tradingSymbol': 'APLAPOLLO-EQ',
  'companyName': 'APL Apollo Tubes Limited',
  'exchange': 'NSE',
  'securityType': 'EQ',
  'industry': 'Capital Goods',
  'sizeSegment': 'MID',
  'indexMemberships': [
    {'indexCode': 'MIDCAP150', 'source': 'NSE_MIDCAP150_CSV',
      'sourceAsOf': '2026-11-20'},
  ],
};

void main() {
  test('parses dated verified company classification', () {
    final result = CustomerCompanyProfile.fromJson(profile());
    expect(result.instrumentId, 101);
    expect(result.tradingSymbol, 'APLAPOLLO-EQ');
    expect(result.industry, 'Capital Goods');
    expect(result.sizeSegment, 'MID');
    expect(result.indexMemberships.single.source, 'NSE_MIDCAP150_CSV');
    expect(result.indexMemberships.single.sourceAsOf.year, 2026);
  });

  test('missing official industry leaves value absent', () {
    final data = profile()..['industry'] = null;
    data['sizeSegment'] = null;
    data['indexMemberships'] = [];
    final result = CustomerCompanyProfile.fromJson(data);
    expect(result.industry, isNull);
    expect(result.sizeSegment, isNull);
    expect(result.indexMemberships, isEmpty);
  });

  test('invalid identity and unsupported data contracts are rejected', () {
    expect(() => CustomerCompanyProfile.fromJson(
      profile()..['instrumentId'] = 0), throwsFormatException);
    expect(() => CustomerCompanyProfile.fromJson(
      profile()..['contractVersion'] = 'v2'), throwsFormatException);
    expect(() => CustomerCompanyProfile.fromJson(
      profile()..['sizeSegment'] = 'NANO'), throwsFormatException);
    expect(() => CustomerCompanyProfile.fromJson(
      profile()..['indexMemberships'] = [
        {'indexCode': 'MIDCAP150', 'source': '', 'sourceAsOf': 'fake'}]),
      throwsFormatException);
  });
}
