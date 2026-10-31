import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/data/market_data_repository.dart';
import 'package:trading_client/src/presentation/instruments_page.dart';
import 'package:trading_client/src/presentation/market_data_page.dart';

const _instrument = InstrumentRecord(
  id: 42,
  exchange: 'NSE',
  exchangeSegment: 'nse_cm',
  symbolToken: '1594',
  tradingSymbol: 'INFY-EQ',
  name: 'INFOSYS LIMITED',
  instrumentType: 'EQ',
  expiry: null,
  strike: null,
  lotSize: 1,
  tickSize: 0.05,
);

class _FakeInstrumentRepository implements InstrumentRepository {
  @override
  Future<void> importLatest() async {}

  @override
  Future<InstrumentSearchPage> search({String? query, String? exchange, String? type, int page = 0, int size = 20}) async =>
      InstrumentSearchPage(
        content: const [_instrument],
        page: 0,
        size: size,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
        catalogueVersion: 'v1',
        activatedAt: DateTime.utc(2026, 10, 31),
      );

  @override
  Future<InstrumentCatalogueStatus> status() async =>
      InstrumentCatalogueStatus(version: 'v1', activatedAt: DateTime.utc(2026, 10, 31), activeCount: 1);
}

class _FakeMarketDataRepository implements MarketDataRepository {
  @override
  Future<MarketCandlePage> candles({
    required int instrumentId,
    required String interval,
    required DateTime fromDate,
    required DateTime toDate,
    int page = 0,
    int size = 100,
  }) async =>
      MarketCandlePage(
        items: [
          MarketCandleRecord(
            id: 1,
            instrumentId: instrumentId,
            exchange: 'NSE',
            tradingSymbol: 'INFY-EQ',
            interval: interval,
            candleTime: DateTime.utc(2026, 10, 30, 3, 45),
            open: 1500,
            high: 1520,
            low: 1490,
            close: 1515,
            volume: 12345,
            source: 'ANGEL_ONE',
          ),
        ],
        page: 0,
        size: size,
        totalItems: 1,
        totalPages: 1,
        hasNext: false,
      );

  @override
  Future<InstrumentCatalogueStatus> instrumentStatus() async =>
      InstrumentCatalogueStatus(version: 'v1', activatedAt: DateTime.utc(2026, 10, 31), activeCount: 1);

  @override
  Future<InstrumentSearchPage> searchInstruments({String? query, String? exchange, String? type, int page = 0, int size = 20}) async =>
      InstrumentSearchPage(
        content: const [_instrument],
        page: 0,
        size: size,
        totalElements: 1,
        totalPages: 1,
        hasNext: false,
        catalogueVersion: 'v1',
        activatedAt: DateTime.utc(2026, 10, 31),
      );

  @override
  Future<void> syncInstruments() async {}
}

void main() {
  testWidgets('instruments page renders authoritative catalogue evidence', (tester) async {
    await tester.pumpWidget(MaterialApp(home: InstrumentsPage(repository: _FakeInstrumentRepository())));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('instruments-status')), findsOneWidget);
    expect(find.text('1 instruments'), findsOneWidget);
    expect(find.text('INFY-EQ'), findsOneWidget);
    expect(find.textContaining('INFOSYS LIMITED'), findsOneWidget);
  });

  testWidgets('market data page loads candle evidence only after instrument selection', (tester) async {
    await tester.pumpWidget(MaterialApp(home: MarketDataPage(repository: _FakeMarketDataRepository())));
    await tester.pumpAndSettle();

    expect(find.text('Select an instrument to request candle evidence.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('market-instrument-42')));
    await tester.pumpAndSettle();

    expect(find.text('INFY-EQ • 1 candles'), findsOneWidget);
    expect(find.textContaining('O 1500'), findsOneWidget);
    expect(find.textContaining('12345 vol'), findsOneWidget);
  });
}
