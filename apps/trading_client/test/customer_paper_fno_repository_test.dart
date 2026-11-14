import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';

Map<String, dynamic> leg({String side = 'CE', bool fresh = true}) => {
      'instrumentKey': 'NSE_FO|NIFTY24CE',
      'tradingSymbol': 'NIFTY 24000 CE',
      'side': side,
      'lotSize': 75,
      'tickSize': 0.05,
      'bid': 101.5,
      'ask': 102.0,
      'exchangeAt': '2026-11-16T04:30:00Z',
      'observedAt': '2026-11-16T04:30:01Z',
      'quoteFresh': fresh,
      'paperEligible': fresh,
    };

Map<String, dynamic> market({String status = 'FRESH_QUOTES', bool live = false}) => {
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'expiry': '2026-11-20',
      'source': 'UPSTOX_ANALYTICS',
      'status': status,
      'snapshotAt': '2026-11-16T04:30:00Z',
      'exchangeSpotTimeVerified': false,
      'displayRightsVerified': true,
      'trainingRightsVerified': false,
      'liveOrderEnabled': live,
      'strikes': [
        {'strikePrice': 24000, 'call': leg(), 'put': null},
      ],
    };

Map<String, dynamic> fill() => {
      'id': '3f2c9d1e-0000-4000-8000-000000000001',
      'roomId': 'room',
      'instrumentKey': 'NSE_FO|NIFTY24CE',
      'optionType': 'CE',
      'expiry': '2026-11-20',
      'side': 'BUY',
      'quantity': 75,
      'lotSize': 75,
      'limitPrice': 102,
      'fillPrice': 102,
      'notionalPaise': 765000,
      'estimatedFeesPaise': 120,
      'realizedPnlDeltaPaise': 0,
      'cashAfterPaise': 9234880,
      'exchangeAt': '2026-11-16T04:30:00Z',
      'observedAt': '2026-11-16T04:30:01Z',
      'createdAt': '2026-11-16T04:30:02Z',
    };

void main() {
  test('decodes a fresh market with provider lot size and quote freshness', () {
    final m = CustomerFnoMarket.decode(market());
    expect(m.hasFreshQuotes, isTrue);
    expect(m.strikes.single.call!.lotSize, 75);
    expect(m.strikes.single.call!.quoteFresh, isTrue);
    expect(m.strikes.single.put, isNull);
    expect(m.liveOrderEnabled, isFalse);
  });

  test('rejects unknown market status instead of inventing one', () {
    expect(() => CustomerFnoMarket.decode(market(status: 'OPEN')), throwsFormatException);
  });

  test('rejects any payload that enables LIVE F&O orders', () {
    expect(() => CustomerFnoMarket.decode(market(live: true)), throwsFormatException);
  });

  test('rejects a leg without a valid NSE_FO instrument key or lot size', () {
    final bad = market();
    (bad['strikes'] as List).first['call']['instrumentKey'] = 'NSE_EQ|INE002A01018';
    expect(() => CustomerFnoMarket.decode(bad), throwsFormatException);
    final noLot = market();
    (noLot['strikes'] as List).first['call']['lotSize'] = 0;
    expect(() => CustomerFnoMarket.decode(noLot), throwsFormatException);
  });

  test('decodes a fill and keeps its server-computed values unchanged', () {
    final f = CustomerFnoFill.decode(fill());
    expect(f.optionType, 'CE');
    expect(f.quantity, 75);
    expect(f.cashAfterPaise, 9234880);
  });

  test('a position without a fresh valuation keeps P&L null, never zero', () {
    final p = CustomerFnoPosition.decode({
      'instrumentKey': 'NSE_FO|NIFTY24CE',
      'optionType': 'CE',
      'expiry': '2026-11-20',
      'lotSize': 75,
      'quantity': 75,
      'costBasisPaise': 765000,
      'indicativeUnrealizedPnlPaise': null,
      'priceFresh': false,
    });
    expect(p.indicativeUnrealizedPnlPaise, isNull);
    expect(p.priceFresh, isFalse);
  });

  test('submit validates locally before any request is sent', () async {
    final repo = CustomerPaperFnoRepository(_NoCallApi());
    Future<void> submit({String side = 'BUY', int lots = 1, String key = 'NSE_FO|NIFTY24CE',
        String idem = 'k1'}) =>
      repo.submit(roomId: 'room', idempotencyKey: idem, instrumentKey: key,
        expiry: DateTime(2026, 11, 20), side: side, lots: lots, limitPrice: 102);
    await expectLater(submit(side: 'SHORT'), throwsFormatException);
    await expectLater(submit(lots: 0), throwsFormatException);
    await expectLater(submit(lots: 21), throwsFormatException);
    await expectLater(submit(key: 'NSE_EQ|X'), throwsFormatException);
    await expectLater(submit(idem: ''), throwsFormatException);
  });
}

// Any request reaching the network would be a bug for local validation tests.
class _NoCallApi implements AuthenticatedApiClient {
  @override
  dynamic noSuchMethod(Invocation invocation) => throw StateError('network call not expected');
}
