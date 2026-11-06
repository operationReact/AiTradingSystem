import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/data/market_data_repository.dart';
import 'package:trading_client/src/presentation/market_data_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Request {
  const _Request(this.page, this.query, this.exchange, this.type, this.size);
  final int page;
  final String? query;
  final String? exchange;
  final String? type;
  final int size;
}

class _CatalogueFake implements MarketDataRepository {
  final requests = <_Request>[];

  final stocks = List<InstrumentRecord>.generate(38, (index) => InstrumentRecord(
    id: index + 1,
    exchange: index.isEven ? 'NSE' : 'BSE',
    exchangeSegment: 'cm',
    symbolToken: '${index + 101}',
    tradingSymbol: 'STOCK${index + 1}-EQ',
    name: 'Sample Indian Equity ${index + 1}',
    instrumentType: 'EQ',
    expiry: null,
    strike: null,
    lotSize: 1,
    tickSize: 0.05,
  ));

  @override
  Future<InstrumentCatalogueStatus> instrumentStatus() async =>
      InstrumentCatalogueStatus(version: 'v1', activatedAt: DateTime.utc(2026, 11, 6), activeCount: 38);

  @override
  Future<InstrumentSearchPage> searchInstruments({
    String? query,
    String? exchange,
    String? type,
    int page = 0,
    int size = 20,
  }) async {
    requests.add(_Request(page, query, exchange, type, size));
    final filtered = stocks.where((stock) =>
      (exchange == null || stock.exchange == exchange) &&
      (query == null || stock.name.toLowerCase().contains(query.toLowerCase()) ||
        stock.tradingSymbol.toLowerCase().contains(query.toLowerCase()))).toList();
    final from = page * size;
    final content = from >= filtered.length
        ? <InstrumentRecord>[]
        : filtered.skip(from).take(size).toList();
    return InstrumentSearchPage(
      content: content,
      page: page,
      size: size,
      totalElements: filtered.length,
      totalPages: (filtered.length / size).ceil(),
      hasNext: from + content.length < filtered.length,
      catalogueVersion: 'v1',
      activatedAt: DateTime.utc(2026, 11, 6),
    );
  }

  @override
  Future<MarketCandlePage> candles({
    required int instrumentId,
    required String interval,
    required DateTime fromDate,
    required DateTime toDate,
    int page = 0,
    int size = 100,
  }) async => MarketCandlePage(
    items: [
      MarketCandleRecord(
        id: 1,
        instrumentId: instrumentId,
        exchange: 'NSE',
        tradingSymbol: 'STOCK$instrumentId-EQ',
        interval: interval,
        candleTime: DateTime.utc(2026, 11, 5),
        open: 120,
        high: 125,
        low: 118,
        close: 123,
        volume: 10000,
        source: 'TEST_SOURCE',
      ),
    ],
    page: 0,
    size: 100,
    totalItems: 1,
    totalPages: 1,
    hasNext: false,
  );

  @override
  Future<void> syncInstruments() async {}
}

Future<void> _showMarkets(
  WidgetTester tester,
  _CatalogueFake fake, {
  double width = 390,
  Brightness brightness = Brightness.dark,
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = Size(width, 844);
  await tester.pumpWidget(MaterialApp(
    theme: buildTheme(DesignStyle.neonFuturisticAi, brightness),
    home: MarketDataPage(repository: fake),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {});
  tearDown(() {});

  testWidgets('390dp phone uses dense scrollable catalogue with all 25 first-page records', (tester) async {
    addTearDown(() { tester.view.resetPhysicalSize(); tester.view.resetDevicePixelRatio(); });
    final fake = _CatalogueFake();
    await _showMarkets(tester, fake);
    expect(find.byKey(const ValueKey('market-stock-list')), findsOneWidget);
    expect(find.text('Showing 25 of 38 equities'), findsOneWidget);
    expect(fake.requests.first.size, 25);
    expect(fake.requests.first.type, 'EQ');
    expect(find.byKey(const ValueKey('market-instrument-1')), findsOneWidget);
    expect(find.text('No live quotes'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.drag(find.byKey(const ValueKey('market-stock-list')), const Offset(0, -2000));
    await tester.pumpAndSettle();
    expect(fake.requests.any((request) => request.page == 1), isTrue);
    expect(find.text('Showing 38 of 38 equities'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('NSE and BSE filters request server-side matching instruments', (tester) async {
    addTearDown(() { tester.view.resetPhysicalSize(); tester.view.resetDevicePixelRatio(); });
    final fake = _CatalogueFake();
    await _showMarkets(tester, fake);
    await tester.tap(find.byKey(const ValueKey('market-exchange-NSE')));
    await tester.pumpAndSettle();
    expect(fake.requests.last.exchange, 'NSE');
    expect(find.text('Showing 19 of 19 equities'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('market-exchange-BSE')));
    await tester.pumpAndSettle();
    expect(fake.requests.last.exchange, 'BSE');
    expect(tester.takeException(), isNull);
  });

  testWidgets('stock tap opens distinct historical detail without simulated quotes', (tester) async {
    addTearDown(() { tester.view.resetPhysicalSize(); tester.view.resetDevicePixelRatio(); });
    final fake = _CatalogueFake();
    await _showMarkets(tester, fake);
    await tester.tap(find.byKey(const ValueKey('market-instrument-1')));
    await tester.pumpAndSettle();
    expect(find.text('STOCK1-EQ • 1 points'), findsOneWidget);
    expect(find.textContaining('O 120'), findsOneWidget);
    expect(find.textContaining('10000 vol'), findsOneWidget);
    expect(find.text('Historical close · Not a live price'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  for (final width in [320.0, 375.0, 390.0, 411.0]) {
    for (final brightness in [Brightness.dark, Brightness.light]) {
      testWidgets('${width.toInt()}dp ${brightness.name} Markets has no overflow', (tester) async {
        addTearDown(() { tester.view.resetPhysicalSize(); tester.view.resetDevicePixelRatio(); });
        await _showMarkets(tester, _CatalogueFake(), width: width, brightness: brightness);
        expect(find.byKey(const ValueKey('market-stock-list')), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  }
}
