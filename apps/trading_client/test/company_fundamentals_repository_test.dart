import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

Map<String, dynamic> fixture(String section, Object data) => {
  'contractVersion': 'customer.market.fundamentals.v1',
  'instrumentId': 123, 'tradingSymbol': 'RELIANCE-EQ',
  'isin': 'INE002A01018', 'section': section,
  'provider': 'UPSTOX', 'fetchedAt': '2026-11-24T07:00:00Z',
  'data': data,
};

void main() {
  test('only ISIN backed provider ratios reach client', () {
    final r = CustomerFundamentalsSection.fromJson(fixture('ratios', [
      {'name': 'ROE', 'company_value': '8.94%', 'sector_value': '16.46%'}
    ]));
    expect(r.isin, 'INE002A01018');
    expect(r.instrumentId, 123);
    expect(r.section, 'ratios');
    expect(r.provider, 'UPSTOX');
    expect(r.data, isA<List>());
  });

  test('missing ratios are not silently represented as zeros', () {
    final r = CustomerFundamentalsSection.fromJson(fixture('ratios', []));
    expect(r.data, isEmpty);
  });

  test('rejects invalid ISIN, identity, unknown section and malformed response', () {
    expect(() => CustomerFundamentalsSection.fromJson(
      fixture('profile', {})..['isin'] = 'INVALID'), throwsFormatException);
    expect(() => CustomerFundamentalsSection.fromJson(
      fixture('profile', {})..['instrumentId'] = 0), throwsFormatException);
    expect(() => CustomerFundamentalsSection.fromJson(
      fixture('order', {})), throwsFormatException);
    expect(() => CustomerFundamentalsSection.fromJson(
      fixture('ratios', {'P/E': 20})), throwsFormatException);
  });
}
