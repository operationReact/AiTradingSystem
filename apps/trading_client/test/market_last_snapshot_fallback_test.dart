import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';

void main() {
  test('verified live quote becomes last recorded without changing the exchange time', () {
    final tickAt = DateTime.utc(2026, 11, 9, 9, 59);
    final live = CustomerLiveQuote.fromJson({
      'instrumentId': 42, 'exchange': 'NSE', 'tradingSymbol': 'INFY-EQ',
      'status': 'LIVE', 'ltp': 1510.5,
      'exchangeAt': tickAt.toIso8601String(), 'source': 'ANGEL_ONE_SMARTAPI_WS_V2',
    });

    final saved = live.asLastRecorded()!;
    expect(saved.status, 'LAST_CLOSE');
    expect(saved.exchangeAt, tickAt);
    expect(saved.ltp, 1510.5);
    expect(saved.source, 'IN_MEMORY_LAST_VERIFIED_TICK');
    expect(saved.asLastRecorded(), same(saved));
  });

  test('unverified, missing, and reconnecting rows never generate local prices', () {
    for (final status in ['UNAVAILABLE', 'CONNECTING', 'RECONNECTING', 'STALE']) {
      final unavailable = CustomerLiveQuote(
        instrumentId: 42, exchange: 'NSE', tradingSymbol: 'INFY-EQ',
        status: status);
      expect(unavailable.asLastRecorded(), isNull);
    }
    final untrusted = CustomerLiveQuote(
      instrumentId: 42, exchange: 'NSE', tradingSymbol: 'INFY-EQ',
      status: 'LIVE', source: 'UNVERIFIED', ltp: 123,
      exchangeAt: DateTime.utc(2026, 11, 9));
    expect(untrusted.asLastRecorded(), isNull);
  });

  test('overview fallback retains historical numbers but removes live signals', () {
    final tradeAt = DateTime.utc(2026, 11, 9, 9, 59);
    final observedAt = DateTime.utc(2026, 11, 9, 10);
    final previous = CustomerMarketOverview(
      generatedAt: observedAt,
      source: 'ANGEL_ONE_SMARTAPI_FULL_BULK',
      breadthScope: 'NIFTY50_CONSTITUENTS',
      exchangeAsOf: tradeAt,
      universe: CustomerUniverse(
        code: 'NIFTY50', sourceAsOf: DateTime.utc(2026, 11, 9),
        members: 50, resolved: 50, returned: 50),
      breadth: const CustomerBreadth(advancers: 30, decliners: 18,
        unchanged: 2, total: 50),
      gainers: const [], losers: const [], gapUp: const [], gapDown: const [],
      sectors: const [],
      indices: [CustomerIndexRow(
        indexCode: 'NIFTY50', displayName: 'Nifty 50',
        ltp: 24800, previousClose: 24700, changePct: 0.4,
        open: 24750, high: 24900, low: 24600,
        volume: 0, exchangeAt: tradeAt, freshness: 'LIVE')],
      ideas: [CustomerMarketIdea(
        tradingSymbol: 'INFY-EQ', companyName: 'Infosys',
        ltp: 1510.5, changePct: 2.1, gapPct: 1.2,
        signal: 'MOMENTUM', exchangeAt: tradeAt)],
    );

    final saved = previous.asLastRecorded();
    expect(saved.source, 'STORED_LAST_OVERVIEW');
    expect(saved.generatedAt, observedAt);
    expect(saved.exchangeAsOf, tradeAt);
    expect(saved.breadth.total, 50);
    expect(saved.indices.single.ltp, 24800);
    expect(saved.indices.single.exchangeAt, tradeAt);
    expect(saved.indices.single.freshness, 'LAST_CLOSE');
    expect(saved.ideas, isEmpty);
  });
}
