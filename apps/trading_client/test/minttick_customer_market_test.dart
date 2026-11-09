import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_market_page.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_stock_detail_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

Map<String, dynamic> _mover(String symbol, String name, String sector, num ltp, num previousClose,
    num changePct, num gapPct) => {
      'tradingSymbol': symbol, 'companyName': name, 'sector': sector, 'ltp': ltp,
      'previousClose': previousClose, 'changePct': changePct, 'gapPct': gapPct,
      'exchangeAt': '2026-11-07T09:29:58Z',
    };

Map<String, dynamic> overviewJson({String indexFreshness = 'LAST_CLOSE'}) => {
      'generatedAt': '2026-11-07T09:30:00Z',
      'source': 'ANGEL_ONE_SMARTAPI_FULL_BULK',
      'breadthScope': 'NIFTY50_CONSTITUENTS',
      'exchangeAsOf': '2026-11-07T09:29:58Z',
      'universe': {'code': 'NIFTY50', 'sourceAsOf': '2026-11-09', 'members': 50,
        'resolved': 50, 'returned': 3},
      'breadth': {'advancers': 2, 'decliners': 1, 'unchanged': 0, 'total': 3},
      'gainers': [
        _mover('ALPHA-EQ', 'Alpha Ltd', 'Banks', 110, 100, 10, 5),
        _mover('GAMMA-EQ', 'Gamma Ltd', 'IT', 101.5, 100, 1.5, 0.2),
      ],
      'losers': [_mover('BETA-EQ', 'Beta Ltd', 'Banks', 90, 100, -10, -1)],
      'gapUp': [_mover('ALPHA-EQ', 'Alpha Ltd', 'Banks', 110, 100, 10, 5)],
      'gapDown': [_mover('BETA-EQ', 'Beta Ltd', 'Banks', 90, 100, -10, -1)],
      'sectors': [
        {'sector': 'IT', 'members': 1, 'advancers': 1, 'decliners': 0, 'avgChangePct': 1.5},
        {'sector': 'Banks', 'members': 2, 'advancers': 1, 'decliners': 1, 'avgChangePct': 0},
      ],
      'indices': [
        {'indexCode': 'NIFTY50', 'displayName': 'NIFTY 50', 'ltp': 24000, 'previousClose': 23980,
          'changePct': 0.08, 'open': 23950, 'high': 24010, 'low': 23940, 'volume': 0,
          'exchangeAt': '2026-11-07T09:29:58Z', 'freshness': indexFreshness},
      ],
    };

class _CustomerMarketFake implements CustomerMarketRepository, CustomerSnapshotMarketRepository,
    CustomerCandleRepository, CustomerDetailRepository, CustomerWatchlistRepository,
    CustomerOverviewRepository {
  bool failOverview = false;
  String indexFreshness = 'LAST_CLOSE';
  String status = 'MARKET_CLOSED';
  bool failSnapshot = false;
  bool mismatchSymbol = false;
  bool failCandles = false;
  bool failDetail = false;
  bool failWatch = false;
  final Set<int> watchedIds = {};
  List<CustomerCandle> bars = const [];
  final List<String> candleIntervals = [];
  int quoteRequests = 0;
  int snapshotRequests = 0;
  int detailRequests = 0;

  @override
  Future<CustomerWatchlist> watchlist() async {
    if (failWatch) throw StateError('watchlist unavailable');
    return CustomerWatchlist(
      items: [
        for (final id in watchedIds)
          CustomerWatchlistItem(instrumentId: id, exchange: 'NSE', tradingSymbol: 'TEST-EQ'),
      ],
      maxItems: 100,
    );
  }

  @override
  Future<void> add(int instrumentId) async {
    if (failWatch) throw StateError('watchlist unavailable');
    watchedIds.add(instrumentId);
  }

  @override
  Future<void> remove(int instrumentId) async {
    if (failWatch) throw StateError('watchlist unavailable');
    watchedIds.remove(instrumentId);
  }

  @override
  Future<CustomerMarketOverview> overview() async {
    if (failOverview) throw StateError('overview unavailable');
    return CustomerMarketOverview.fromJson(overviewJson(indexFreshness: indexFreshness));
  }

  @override
  Future<CustomerDayDetail> dayDetail(int instrumentId) async {
    detailRequests++;
    if (failDetail) throw StateError('detail unavailable');
    return CustomerDayDetail(
      instrumentId: instrumentId, exchange: 'NSE', tradingSymbol: 'TEST-EQ',
      ltp: 125.55, open: 120, high: 126, low: 119.5, previousClose: 121,
      volume: 987654, exchangeAt: DateTime.utc(2026, 11, 7, 9), fetchedAt: DateTime.utc(2026, 11, 7, 9, 1),
      source: 'ANGEL_ONE_SMARTAPI_FULL_APP',
      averagePrice: 123.4, week52High: 150, week52Low: 100.1,
      totalBuyQuantity: 500, totalSellQuantity: 450,
      bids: const [CustomerDepthLevel(price: 125.5, quantity: 300, orders: 4)],
      asks: const [CustomerDepthLevel(price: 125.6, quantity: 200, orders: 2)],
    );
  }

  @override
  Future<InstrumentCatalogueStatus> catalogueStatus() async =>
      InstrumentCatalogueStatus(version: 'catalogue-v1', activatedAt: DateTime.utc(2026, 11, 7), activeCount: 1);

  @override
  Future<InstrumentSearchPage> search({String? query, String? exchange}) async =>
      InstrumentSearchPage(
        content: [
          const InstrumentRecord(
            id: 101,
            exchange: 'NSE',
            exchangeSegment: 'NSE',
            symbolToken: 'public-broker-token',
            tradingSymbol: 'TEST-EQ',
            name: 'Test Company',
            instrumentType: 'EQ',
            expiry: null,
            strike: null,
            lotSize: 1,
            tickSize: 0.05,
          ),
          if (query == 'ALPHA-EQ')
            const InstrumentRecord(
              id: 102,
              exchange: 'NSE',
              exchangeSegment: 'NSE',
              symbolToken: 'public-alpha-token',
              tradingSymbol: 'ALPHA-EQ',
              name: 'Alpha Ltd',
              instrumentType: 'EQ',
              expiry: null,
              strike: null,
              lotSize: 1,
              tickSize: 0.05,
            ),
        ],
        page: 0,
        size: 20,
        totalElements: query == 'ALPHA-EQ' ? 2 : 1,
        totalPages: 1,
        hasNext: false,
        catalogueVersion: 'catalogue-v1',
        activatedAt: DateTime.utc(2026, 11, 7),
      );

  @override
  Future<CustomerMarketQuote> quote(int instrumentId) async {
    quoteRequests++;
    throw StateError('Markets page must use the batched snapshot, not one-off quotes');
  }

  @override
  Future<CustomerMarketSnapshot> snapshot(List<int> instrumentIds) async {
    snapshotRequests++;
    if (failSnapshot) throw StateError('provider unavailable');
    final live = status == 'LIVE';
    return CustomerMarketSnapshot(
      generatedAt: DateTime.now().toUtc(),
      rows: [
        for (final id in instrumentIds)
          CustomerLiveQuote(
            instrumentId: id,
            exchange: 'NSE',
            tradingSymbol: mismatchSymbol ? 'OTHER-EQ' : 'TEST-EQ',
            status: status,
            ltp: live ? 125.55 : null,
            exchangeAt: live ? DateTime.now().toUtc() : null,
            source: live ? 'ANGEL_ONE_SMARTAPI_WS_V2' : null,
          ),
      ],
    );
  }

  @override
  Future<CustomerCandlePage> candles({
    required int instrumentId,
    required String interval,
    required String fromDate,
    required String toDate,
  }) async {
    candleIntervals.add(interval);
    if (failCandles) throw StateError('candle store unavailable');
    return CustomerCandlePage(instrumentId: instrumentId, interval: interval,
        items: bars, hasNext: false);
  }
}

CustomerCandle _bar(DateTime time, num close) => CustomerCandle(
      candleTime: time, open: close - 1, high: close + 2, low: close - 3,
      close: close, volume: 1000, source: 'ANGEL_ONE_SMARTAPI',
      fetchedAt: DateTime.utc(2026, 11, 7, 10));

Future<void> _openExplore(WidgetTester tester) async {
  await tester.ensureVisible(find.text('Explore'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Explore'));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() {
    final view = TestWidgetsFlutterBinding.ensureInitialized().platformDispatcher.views.first;
    view.devicePixelRatio = 1;
    view.physicalSize = const Size(390, 3200);
  });
  tearDown(() {
    final view = TestWidgetsFlutterBinding.ensureInitialized().platformDispatcher.views.first;
    view.resetPhysicalSize();
    view.resetDevicePixelRatio();
  });

  test('candle rows reject a missing source and inverted high/low', () {
    expect(() => CustomerCandle.fromJson({
      'candleTime': '2026-11-06T03:45:00Z', 'open': 10, 'high': 9, 'low': 11,
      'close': 10, 'volume': 5, 'source': 'ANGEL_ONE_SMARTAPI',
      'fetchedAt': '2026-11-07T10:00:00Z',
    }), throwsFormatException);
    expect(() => CustomerCandle.fromJson({
      'candleTime': '2026-11-06T03:45:00Z', 'open': 10, 'high': 12, 'low': 9,
      'close': 10, 'volume': 5, 'fetchedAt': '2026-11-07T10:00:00Z',
    }), throwsFormatException);
  });

  test('day detail rejects inverted OHLC, non-positive prices and identity gaps', () {
    Map<String, dynamic> valid() => {
          'instrumentId': 101, 'exchange': 'NSE', 'tradingSymbol': 'TEST-EQ',
          'ltp': 125.5, 'open': 120, 'high': 126, 'low': 119.5, 'previousClose': 121,
          'volume': 10, 'exchangeAt': '2026-11-07T09:00:00Z',
          'fetchedAt': '2026-11-07T09:00:01Z', 'source': 'ANGEL_ONE_SMARTAPI_FULL_APP',
        };
    expect(CustomerDayDetail.fromJson(valid()).previousClose, 121);
    expect(() => CustomerDayDetail.fromJson({...valid(), 'high': 119}), throwsFormatException);
    expect(() => CustomerDayDetail.fromJson({...valid(), 'open': 0}), throwsFormatException);
    expect(() => CustomerDayDetail.fromJson({...valid(), 'volume': -1}), throwsFormatException);
    expect(() => CustomerDayDetail.fromJson({...valid(), 'exchangeAt': null}), throwsFormatException);
    expect(() => CustomerDayDetail.fromJson({...valid(), 'source': null}), throwsFormatException);
  });

  testWidgets('a failed day-detail request shows a labelled state, never estimated values',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(390, 3200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final fake = _CustomerMarketFake()
      ..status = 'LIVE'
      ..failDetail = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pumpAndSettle();

    expect(find.text('₹125.55'), findsWidgets);
    expect(find.textContaining('Day statistics could not be loaded'), findsOneWidget);
    expect(find.textContaining('vs previous close'), findsNothing);
    expect(find.text('₹121.00'), findsNothing);
    expect(find.byKey(const ValueKey('stock-detail-day-refresh')), findsOneWidget);
  });

  testWidgets('tapping a row opens stock detail with a verified price and stored chart',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(390, 3200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final fake = _CustomerMarketFake()
      ..status = 'LIVE'
      ..bars = [_bar(DateTime.utc(2026, 11, 6, 4), 120), _bar(DateTime.utc(2026, 11, 5, 4), 118)];
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pumpAndSettle();

    expect(find.text('₹125.55'), findsWidgets);
    expect(find.text('Test Company'), findsWidgets);
    expect(find.text('VWAP'), findsOneWidget);
    expect(find.text('₹123.40'), findsOneWidget);
    expect(find.text('52W high'), findsOneWidget);
    expect(find.text('₹150.00'), findsOneWidget);
    expect(find.text('₹100.10'), findsOneWidget);
    expect(find.text('Market depth (Top 5)'), findsOneWidget);
    expect(find.text('125.50'), findsOneWidget);
    expect(find.text('125.60'), findsOneWidget);
    expect(find.text('Buy 52.6%'), findsOneWidget);
    expect(find.byKey(const ValueKey('stock-detail-market-badge')), findsOneWidget);
    expect(find.byKey(const ValueKey('mock-data-badge')), findsNWidgets(3));
    expect(find.text('Mock sector: Sample sector'), findsOneWidget);
    expect(find.byKey(const ValueKey('mock-analyze-trade-room')), findsOneWidget);
    expect(find.text('₹121.00'), findsOneWidget);
    expect(find.text('₹120.00'), findsOneWidget);
    expect(find.text('987654'), findsOneWidget);
    expect(find.text('+4.55 (+3.76%) vs previous close'), findsOneWidget);
    expect(find.textContaining('provider day values'), findsOneWidget);
    expect(fake.detailRequests, 1);
    expect(fake.candleIntervals, ['ONE_DAY']);
    expect(find.textContaining('Stored candles ·'), findsOneWidget);
    expect(
        find.byWidgetPredicate((w) =>
            w is Semantics && w.properties.label == 'Stored candle chart with 2 bars'),
        findsOneWidget);
  });

  testWidgets('stock detail switches timeframe and states when no candles are stored',
      (tester) async {
    final fake = _CustomerMarketFake();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pumpAndSettle();

    expect(find.textContaining('No stored candles for this range yet'), findsOneWidget);
    expect(find.text('No verified price'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('stock-detail-timeframe-1H')));
    await tester.pumpAndSettle();
    expect(fake.candleIntervals, ['ONE_DAY', 'ONE_HOUR']);
  });

  testWidgets('stock detail shows a retry state when stored candles fail', (tester) async {
    final fake = _CustomerMarketFake()..failCandles = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pumpAndSettle();

    expect(find.text('Stored candles could not be loaded. No chart is drawn.'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
  });

  testWidgets('stock detail adds and removes a watchlist item through the backend', (tester) async {
    final fake = _CustomerMarketFake()..watchedIds.add(202);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pumpAndSettle();

    expect(fake.watchedIds, {202});
    await tester.tap(find.byKey(const ValueKey('stock-detail-watchlist-toggle')));
    await tester.pumpAndSettle();
    expect(fake.watchedIds, {202, 101});
    expect(find.text('In watchlist · remove'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('stock-detail-watchlist-toggle')));
    await tester.pumpAndSettle();
    expect(fake.watchedIds, {202});
    expect(find.text('Add to watchlist'), findsOneWidget);
  });

  testWidgets('stock detail disables the watchlist toggle when membership cannot load',
      (tester) async {
    final fake = _CustomerMarketFake()..failWatch = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    await tester.tap(find.byKey(const ValueKey('customer-market-stock-101')));
    await tester.pumpAndSettle();

    expect(find.text('Watchlist · unavailable'), findsOneWidget);
    final button = tester.widget<OutlinedButton>(
        find.byKey(const ValueKey('stock-detail-watchlist-toggle')));
    expect(button.onPressed, isNull);
  });

  test('rejects a broker quote without a valid price and fetched timestamp', () {

    expect(() => CustomerMarketQuote.fromJson({
      'instrumentId': 101,
      'exchange': 'NSE',
      'tradingSymbol': 'TEST-EQ',
      'ltp': 0,
      'fetchedAt': 'not-a-date',
      'source': 'ANGEL_ONE_SMARTAPI',
    }), throwsFormatException);
  });

  test('snapshot rows never carry a price unless verified LIVE', () {
    expect(() => CustomerLiveQuote.fromJson({
      'instrumentId': 101, 'exchange': 'NSE', 'tradingSymbol': 'TEST-EQ',
      'status': 'MARKET_CLOSED', 'ltp': 125.55,
    }), throwsFormatException);
  });

  test('LAST_CLOSE carries a stored last traded price and change, never LIVE', () {
    final row = CustomerLiveQuote.fromJson({
      'instrumentId': 101, 'exchange': 'NSE', 'tradingSymbol': 'TEST-EQ',
      'status': 'LAST_CLOSE', 'ltp': 125.55, 'changePercent': 2.04,
      'exchangeAt': '2026-11-06T10:00:00Z', 'source': 'STORED_LAST_LIVE_TICK',
    });
    expect(row.status, 'LAST_CLOSE');
    expect(row.ltp, 125.55);
    expect(row.changePercent, 2.04);
    expect(() => CustomerLiveQuote.fromJson({
      'instrumentId': 101, 'exchange': 'NSE', 'tradingSymbol': 'TEST-EQ',
      'status': 'LAST_CLOSE', 'ltp': 125.55, 'exchangeAt': '2026-11-06T10:00:00Z',
      'source': 'ANGEL_ONE_SMARTAPI_WS_V2',
    }), throwsFormatException);
  });

    for (final width in [320.0, 390.0]) {
    for (final brightness in [Brightness.dark, Brightness.light]) {
      testWidgets('customer Markets shows closed row without price at ${width.toInt()}dp ${brightness.name}',
          (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = Size(width, 3200);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final fake = _CustomerMarketFake();
        await tester.pumpWidget(MaterialApp(
          theme: buildTheme(brightness),
          home: MintTickCustomerMarketPage(repository: fake),
        ));
        await tester.pumpAndSettle();
        await _openExplore(tester);
        expect(find.byKey(const ValueKey('customer-market-stock-101')), findsOneWidget);
        expect(find.descendant(
          of: find.byKey(const ValueKey('customer-market-stock-101')),
          matching: find.text('—')), findsOneWidget);
        expect(find.textContaining('Market closed'), findsOneWidget);
        expect(fake.quoteRequests, 0);
        expect(fake.snapshotRequests, greaterThanOrEqualTo(1));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      });
    }
  }

  testWidgets('LIVE rows show verified price and a text status label', (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    expect(find.byKey(const ValueKey('customer-market-price-101')), findsOneWidget);
    expect(find.text('₹125.55'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsOneWidget);
    expect(find.textContaining('Live feed connected'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('provider failures show NO FEED and never invent a price', (tester) async {
    final fake = _CustomerMarketFake()..failSnapshot = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    expect(find.textContaining('NO FEED'), findsWidgets);
    expect(find.textContaining('Market feed request failed'), findsOneWidget);
    expect(find.text('₹125.55'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('identity-mismatched row never displays a price', (tester) async {
    final fake = _CustomerMarketFake()
      ..status = 'LIVE'
      ..mismatchSymbol = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    expect(find.text('₹125.55'), findsNothing);
    expect(find.byKey(const ValueKey('customer-market-stock-101')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('Overview, Movers and Sectors show verified Nifty 50 data with text direction labels',
      (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Major indices'), findsOneWidget);
    expect(find.text('NIFTY 50'), findsOneWidget);
    expect(find.text('+0.08%'), findsOneWidget);
    expect(find.text('Advancing 2'), findsOneWidget);
    expect(find.text('Declining 1'), findsOneWidget);
    expect(find.byKey(const ValueKey('customer-market-status-pill')), findsOneWidget);
    expect(find.text('Not available yet'), findsOneWidget);

    await tester.tap(find.text('Movers'));
    await tester.pumpAndSettle();
    expect(find.text('Top gainers'), findsOneWidget);
    expect(find.text('Alpha Ltd'), findsWidgets);
    expect(find.text('Change +10.00%'), findsOneWidget);
    expect(find.text('Change -10.00%'), findsOneWidget);
    expect(find.text('₹125.55'), findsNothing);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('Sectors'));
    await tester.pumpAndSettle();
    expect(find.text('Banks'), findsOneWidget);
    expect(find.text('Avg +1.50%'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('tapping a mover opens its stock detail through the active catalogue',
      (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Movers'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Alpha Ltd').first);
    await tester.pumpAndSettle();
    expect(find.byType(MintTickStockDetailPage), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a stale index hides its value and labels the last tick', (tester) async {
    final fake = _CustomerMarketFake()..indexFreshness = 'STALE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    expect(find.text('NIFTY 50'), findsOneWidget);
    expect(find.text('24000.00'), findsNothing);
    expect(find.text('+0.08%'), findsNothing);
    expect(find.textContaining('Stale · last tick'), findsOneWidget);
    expect(find.text('Value hidden'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a failed overview request shows an error and never stale index values',
      (tester) async {
    final fake = _CustomerMarketFake()..failOverview = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('Nifty 50 overview is unavailable'), findsOneWidget);
    expect(find.text('NIFTY 50'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('app pause clears LIVE prices and resume refreshes them', (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    expect(find.text('₹125.55'), findsOneWidget);

    // Invoke the page's lifecycle observer directly: TestWidgets binding may
    // intentionally suppress platform lifecycle dispatch in widget tests.
    final state = tester.state(find.byType(MintTickCustomerMarketPage));
    (state as WidgetsBindingObserver).didChangeAppLifecycleState(AppLifecycleState.paused);
    await tester.pump();
    expect(find.text('₹125.55'), findsNothing);

    final before = fake.snapshotRequests;
    (state as WidgetsBindingObserver).didChangeAppLifecycleState(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(fake.snapshotRequests, greaterThan(before));
    expect(find.text('₹125.55'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
