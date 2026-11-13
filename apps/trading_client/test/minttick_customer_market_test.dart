import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_market_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_market_page.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_equity_avatar.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_market_components.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_stock_detail_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

Map<String, dynamic> _mover(String symbol, String name, String sector, num ltp, num previousClose,
    num changePct, num gapPct) => {
      'tradingSymbol': symbol, 'companyName': name, 'sector': sector, 'ltp': ltp,
      'previousClose': previousClose, 'changePct': changePct, 'gapPct': gapPct,
      'exchangeAt': '2026-11-07T09:29:58Z',
    };

Map<String, dynamic> overviewJson({String indexFreshness = 'LAST_CLOSE',
    String source = 'ANGEL_ONE_SMARTAPI_FULL_BULK'}) => {
      'generatedAt': '2026-11-07T09:30:00Z',
      'source': source,
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
      'ideas': [
        {'tradingSymbol': 'ALPHA-EQ', 'companyName': 'Alpha Ltd', 'ltp': 110, 'changePct': 10,
          'gapPct': 5, 'signal': 'BREAKOUT', 'exchangeAt': '2026-11-07T09:29:58Z'},
        {'tradingSymbol': 'GAMMA-EQ', 'companyName': 'Gamma Ltd', 'ltp': 101.5, 'changePct': 1.5,
          'gapPct': 0.2, 'signal': 'GAP_UP', 'exchangeAt': '2026-11-07T09:29:58Z'},
      ],
    };

class _CustomerMarketFake implements CustomerMarketRepository, CustomerSnapshotMarketRepository,
    CustomerCandleRepository, CustomerDetailRepository, CustomerWatchlistRepository,
    CustomerOverviewRepository {
  bool failOverview = false;
  String indexFreshness = 'LAST_CLOSE';
  bool storedOverview = false;
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
  final List<String?> searchExchanges = [];

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
    return CustomerMarketOverview.fromJson(overviewJson(
      indexFreshness: indexFreshness,
      source: storedOverview ? 'STORED_LAST_OVERVIEW' : 'ANGEL_ONE_SMARTAPI_FULL_BULK',
    ));
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
  Future<InstrumentSearchPage> search({String? query, String? exchange}) async {
    searchExchanges.add(exchange);
    return InstrumentSearchPage(
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
  }

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
    final stored = status == 'LAST_CLOSE';
    return CustomerMarketSnapshot(
      generatedAt: DateTime.now().toUtc(),
      rows: [
        for (final id in instrumentIds)
          CustomerLiveQuote(
            instrumentId: id,
            exchange: 'NSE',
            tradingSymbol: mismatchSymbol ? 'OTHER-EQ' : 'TEST-EQ',
            status: status,
            ltp: live ? 125.55 : stored ? 124.70 : null,
            exchangeAt: live ? DateTime.now().toUtc()
                : stored ? DateTime.utc(2026, 11, 9, 10) : null,
            source: live ? 'ANGEL_ONE_SMARTAPI_WS_V2'
                : stored ? 'STORED_LAST_LIVE_TICK' : null,
            changePercent: stored ? 1.35 : null,
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

class _CoverageMarketFake extends _CustomerMarketFake
    implements CustomerUniverseFacetsRepository, CustomerFullQuoteCoverageRepository {
  @override
  Future<CustomerUniverseFacets> universeFacets() async => CustomerUniverseFacets.fromJson({
    'contractVersion': 'customer.market.universe.v1',
    'generatedAt': '2026-11-13T05:00:00Z',
    'totalActiveCashEquityListings': 2756,
    'exchanges': [
      {'exchange': 'NSE', 'activeListings': 2756},
      {'exchange': 'BSE', 'activeListings': 0},
    ],
    'verifiedMemberships': <dynamic>[],
    'classificationStates': <dynamic>[],
  });

  @override
  Future<CustomerFullQuoteCoverage> fullQuoteCoverage() async =>
      CustomerFullQuoteCoverage.fromJson({
    'contractVersion': 'customer.market.full-quote-coverage.v1',
    'generatedAt': '2026-11-13T05:00:00Z',
    'totalActiveListings': 2756,
    'storedFullQuotes': 620,
    'withProviderPreviousClose': 600,
    'activeBseCmRecords': 175,
    'exchanges': [
      {
        'exchange': 'NSE', 'activeListings': 2756, 'storedFullQuotes': 620,
        'withProviderPreviousClose': 600,
        'newestExchangeAt': '2026-11-12T09:59:00Z',
      },
      {
        'exchange': 'BSE', 'activeListings': 0, 'storedFullQuotes': 0,
        'withProviderPreviousClose': 0, 'newestExchangeAt': null,
      },
    ],
  });
}

Map<String, dynamic> _boardRow(int id, String symbol, String name, num ltp, num prev,
    {String? segment = 'LARGE', String industry = 'Financial Services', int volume = 1000,
    num? high52, num? low52}) => {
      'instrumentId': id, 'tradingSymbol': symbol, 'companyName': name, 'industry': industry,
      'capSegment': segment, 'ltp': ltp, 'previousClose': prev,
      'change': ltp - prev, 'changePct': (ltp - prev) * 100 / prev, 'open': prev,
      'high': ltp > prev ? ltp : prev, 'low': ltp < prev ? ltp : prev, 'gapPct': 0,
      'volume': volume, 'tradedValue': ltp * volume, 'week52High': high52 ?? ltp * 1.5,
      'week52Low': low52 ?? ltp * 0.5, 'exchangeAt': '2026-11-13T09:59:00Z',
    };

Map<String, dynamic> _segment(String code, String label, {bool available = true,
    int members = 100, int adv = 60, int dec = 38, int unch = 2, num? avg = 0.42}) => {
      'segment': code, 'label': label, 'available': available, 'members': members,
      'quoted': available ? adv + dec + unch : 0,
      'breadth': available
          ? {'advancers': adv, 'decliners': dec, 'unchanged': unch, 'total': adv + dec + unch}
          : {'advancers': 0, 'decliners': 0, 'unchanged': 0, 'total': 0},
      'avgChangePct': available ? avg : null, 'medianChangePct': available ? avg : null,
      'membershipAsOf': available && code != 'ALL' ? '2026-11-12' : null,
    };

Map<String, dynamic> boardSummaryJson() => {
      'contractVersion': 'customer.market.board-summary.v1',
      'generatedAt': '2026-11-13T10:30:00Z',
      'source': 'ANGEL_ONE_SMARTAPI_FULL_STORED',
      'market': {'state': 'CLOSED', 'tradeDate': '2026-11-13',
        'opensAt': '2026-11-13T03:45:00Z', 'closesAt': '2026-11-13T10:00:00Z'},
      'freshness': {'state': 'LAST_SESSION', 'sessionDate': '2026-11-13',
        'asOf': '2026-11-13T09:59:00Z', 'excludedOlderSession': 3},
      'listed': 2751,
      'segments': [
        _segment('ALL', 'All NSE stocks', members: 2751, adv: 1204, dec: 1120, unch: 88, avg: -0.12),
        _segment('NIFTY50', 'Nifty 50', members: 50, adv: 9, dec: 40, unch: 1, avg: -0.6),
        _segment('LARGE', 'Large cap'),
        _segment('MID', 'Mid cap', members: 150, adv: 80, dec: 70, unch: 0, avg: 0.9),
        _segment('SMALL', 'Small cap', members: 250, adv: 100, dec: 150, unch: 0, avg: -1.1),
        _segment('MICRO', 'Micro cap', available: false, members: 0),
      ],
      'sectors': [
        {'sector': 'Information Technology', 'quoted': 40, 'advancers': 30, 'decliners': 10,
          'avgChangePct': 1.25, 'leader': 'INFY-EQ', 'leaderChangePct': 3.1},
        {'sector': 'Financial Services', 'quoted': 120, 'advancers': 40, 'decliners': 80,
          'avgChangePct': -0.8, 'leader': 'HDFCBANK-EQ', 'leaderChangePct': 1.2},
      ],
    };

class _BoardMarketFake extends _CoverageMarketFake implements CustomerMarketBoardRepository {
  bool failSummary = false;
  final List<String> boardRequests = [];

  @override
  Future<CustomerBoardSummary> boardSummary() async {
    if (failSummary) throw StateError('board unavailable');
    return CustomerBoardSummary.fromJson(boardSummaryJson());
  }

  @override
  Future<CustomerBoard> board({required String segment, required String sort,
      String? sector, int limit = 20}) async {
    boardRequests.add('$segment/$sort${sector == null ? '' : '/$sector'}');
    final rows = switch (sort) {
      'LOSERS' => [_boardRow(301, 'SHRIRAMFIN-EQ', 'Shriram Finance Ltd.', 968, 1003.8)],
      'HIGH52' || 'LOW52' => <Map<String, dynamic>>[],
      'ACTIVE' => [_boardRow(302, 'HDFCBANK-EQ', 'HDFC Bank Ltd.', 1650, 1640, volume: 9000000)],
      _ => [
          _boardRow(201, 'AADHARHFC-EQ', 'Aadhar Housing Finance Ltd.', 520, 500, segment: 'SMALL'),
          _boardRow(202, 'BHARTIARTL-EQ', 'Bharti Airtel Ltd.', 2119.3, 2096.03),
        ],
    };
    return CustomerBoard.fromJson({
      'contractVersion': 'customer.market.board.v1', 'generatedAt': '2026-11-13T10:30:00Z',
      'source': 'ANGEL_ONE_SMARTAPI_FULL_STORED', 'segment': segment, 'sort': sort,
      'sector': sector, 'available': segment != 'MICRO', 'quoted': 2412,
      'freshness': {'state': 'LAST_SESSION', 'sessionDate': '2026-11-13',
        'asOf': '2026-11-13T09:59:00Z'},
      'rows': segment == 'MICRO' ? <dynamic>[] : rows,
    });
  }
}

Future<void> _openTab(WidgetTester tester, String label) async {
  final tab = find.descendant(of: find.byKey(const ValueKey('customer-market-tabs')),
      matching: find.text(label));
  await tester.ensureVisible(tab);
  await tester.pumpAndSettle();
  await tester.tap(tab);
  await tester.pumpAndSettle();
}

Future<void> _openExplore(WidgetTester tester) => _openTab(tester, 'Stocks');

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

  test('verified FULL detail fallback survives alongside unavailable stocks', () {
    // PR #674 already emits STORED_LAST_DETAIL when market_last_tick is
    // empty. A strict one-source client parser previously rejected the
    // entire batched snapshot, hiding every otherwise valid saved quote.
    final snapshot = CustomerMarketSnapshot.fromJson({
      'generatedAt': '2026-11-10T01:15:00Z',
      'rows': [
        {
          'instrumentId': 101, 'exchange': 'NSE',
          'tradingSymbol': 'RELIANCE-EQ', 'status': 'LAST_CLOSE',
          'ltp': 1324.40, 'exchangeAt': '2026-11-09T10:00:00Z',
          'source': 'STORED_LAST_DETAIL', 'changePercent': 0.56,
        },
        {
          'instrumentId': 102, 'exchange': 'NSE',
          'tradingSymbol': 'TCS-EQ', 'status': 'UNAVAILABLE',
          'ltp': null, 'exchangeAt': null, 'source': null,
        },
      ],
    });
    expect(snapshot.rows, hasLength(2));
    expect(snapshot.rows.first.ltp, 1324.40);
    expect(snapshot.rows.first.status, 'LAST_CLOSE');
    expect(snapshot.rows.first.source, 'STORED_LAST_DETAIL');
    expect(snapshot.rows.first.changePercent, 0.56);
    expect(snapshot.rows.last.ltp, isNull);
    expect(snapshot.rows.last.status, 'UNAVAILABLE');
  });

  test('a purported stored detail must not masquerade as LIVE or an unknown source', () {
    Map<String, dynamic> saved(String status, String source) => {
      'instrumentId': 101, 'exchange': 'NSE',
      'tradingSymbol': 'RELIANCE-EQ', 'status': status,
      'ltp': 1324.40, 'exchangeAt': '2026-11-09T10:00:00Z',
      'source': source,
    };
    expect(CustomerLiveQuote.fromJson(
      saved('LAST_CLOSE', 'STORED_LAST_DETAIL')).status, 'LAST_CLOSE');
    expect(() => CustomerLiveQuote.fromJson(
      saved('LIVE', 'STORED_LAST_DETAIL')), throwsFormatException);
    expect(() => CustomerLiveQuote.fromJson(
      saved('LAST_CLOSE', 'ANGEL_ONE_SMARTAPI_FULL_APP')), throwsFormatException);
    expect(() => CustomerLiveQuote.fromJson(
      saved('LAST_CLOSE', 'STORED_LAST_DETAIL')..['exchangeAt'] = null),
      throwsFormatException);
    expect(() => CustomerLiveQuote.fromJson(
      saved('LAST_CLOSE', 'STORED_LAST_DETAIL')..['ltp'] = -1),
      throwsFormatException);
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
      testWidgets('customer Markets shows unavailable price when no stored snapshot at ${width.toInt()}dp ${brightness.name}',
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
        expect(find.textContaining('No verified last price'), findsOneWidget);
        expect(fake.quoteRequests, 0);
        expect(fake.snapshotRequests, greaterThanOrEqualTo(1));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      });
    }
  }

  testWidgets('Markets lists NSE only; BSE duplicates are never requested', (tester) async {
    final fake = _CustomerMarketFake();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    expect(fake.searchExchanges, isNotEmpty);
    expect(fake.searchExchanges.every((e) => e == 'NSE'), isTrue);
    expect(find.text('BSE'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('stock catalogue uses compact rows instead of repeated cards on narrow screens',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(320, 1000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final fake = _CustomerMarketFake()..status = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    final row = find.byKey(const ValueKey('customer-market-stock-101'));
    expect(row, findsOneWidget);
    expect(tester.getSize(row).height, lessThan(75));
    expect(find.text('₹125.55'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

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

  testWidgets('saved quotes display after feed loss without falsely declaring market closed',
      (tester) async {
    final fake = _CustomerMarketFake()..status = 'LAST_CLOSE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    expect(find.text('₹124.70'), findsOneWidget);
    expect(find.text('1.35%'), findsOneWidget);
    expect(find.textContaining('LAST TRADED'), findsOneWidget);
    expect(find.textContaining('Showing last verified saved prices'), findsOneWidget);
    expect(find.textContaining('Market closed.'), findsNothing);
    expect(find.textContaining('LIVE ·'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('provider failures show NO QUOTE and never invent a price', (tester) async {
    final fake = _CustomerMarketFake()..failSnapshot = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    expect(find.textContaining('NO QUOTE'), findsWidgets);
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

  testWidgets('Markets keeps four fixed tabs', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: _CustomerMarketFake()),
    ));
    await tester.pumpAndSettle();
    final tabs = tester.widget<TabBar>(find.byKey(const ValueKey('customer-market-tabs')));
    expect(tabs.isScrollable, isFalse);
    expect(tabs.tabs, hasLength(4));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('without the whole-market board, Explore, Movers and Sectors fall back to Nifty 50',
      (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE'..indexFreshness = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    expect(find.text('NIFTY 50'), findsOneWidget);
    expect(find.textContaining('(+0.08%)'), findsOneWidget);
    expect(find.text('Market breadth · Nifty 50 only'), findsOneWidget);
    expect(find.text('Advancing 2'), findsOneWidget);
    expect(find.text('Declining 1'), findsOneWidget);
    expect(find.byKey(const ValueKey('customer-market-status-pill')), findsOneWidget);
    expect(find.text('Market ideas'), findsOneWidget);
    expect(find.byKey(const ValueKey('market-idea-ALPHA-EQ')), findsOneWidget);
    expect(find.text('Breakout'), findsOneWidget);
    expect(find.text('Not available yet'), findsNothing);

    await _openTab(tester, 'Movers');
    expect(find.text('Top gainers'), findsOneWidget);
    expect(find.text('Alpha Ltd'), findsWidgets);
    expect(find.text('Change +10.00%'), findsOneWidget);
    expect(find.text('Change -10.00%'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('market-movers-filter-1')));
    await tester.pumpAndSettle();
    expect(find.text('Top losers'), findsOneWidget);
    expect(find.text('Change -10.00%'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('market-movers-filter-2')));
    await tester.pumpAndSettle();
    expect(find.text('Gap up at open'), findsOneWidget);
    expect(find.text('₹125.55'), findsNothing);

    await _openTab(tester, 'Sectors');
    expect(find.text('Banks'), findsOneWidget);
    expect(find.text('Avg +1.50%'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('search page runs an NSE catalogue search and opens the chosen stock',
      (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('market-search-open')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('customer-market-overview-search')), 'ALPHA-EQ');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('overview-search-hit-101')), findsOneWidget);
    expect(find.byKey(const ValueKey('overview-search-hit-102')), findsOneWidget);
    expect(fake.searchExchanges.last, 'NSE');
    await tester.tap(find.byKey(const ValueKey('overview-search-hit-102')));
    await tester.pumpAndSettle();
    expect(find.byType(MintTickStockDetailPage), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('tapping a market idea opens its stock detail through the active catalogue',
      (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE'..indexFreshness = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const ValueKey('market-idea-ALPHA-EQ')));
    await tester.tap(find.byKey(const ValueKey('market-idea-ALPHA-EQ')));
    await tester.pumpAndSettle();
    expect(find.byType(MintTickStockDetailPage), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('tapping a Nifty 50 mover opens its stock detail through the active catalogue',
      (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openTab(tester, 'Movers');
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
    expect(find.text('24,000.00'), findsNothing);
    expect(find.textContaining('+0.08%'), findsNothing);
    expect(find.textContaining('Stale · last tick'), findsOneWidget);
    expect(find.text('Value hidden'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('stored quotes do not claim the exchange is closed or expose current ideas',
      (tester) async {
    final fake = _CustomerMarketFake()
      ..storedOverview = true
      ..indexFreshness = 'LAST_CLOSE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Market feed · Stored snapshot'), findsOneWidget);
    expect(find.textContaining('Last verified snapshot'), findsOneWidget);
    expect(find.textContaining('Stored quote ·'), findsOneWidget);
    expect(find.byKey(const ValueKey('market-session-chip')), findsNothing);
    expect(find.byKey(const ValueKey('market-idea-ALPHA-EQ')), findsNothing);
    expect(find.textContaining('Current market ideas paused'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('stale index blocks current screening opportunities even with idea payload',
      (tester) async {
    final fake = _CustomerMarketFake()..indexFreshness = 'STALE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Market feed · Stale quotes'), findsOneWidget);
    expect(find.byKey(const ValueKey('market-idea-ALPHA-EQ')), findsNothing);
    expect(find.textContaining('Current market ideas paused'), findsOneWidget);
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

  testWidgets('live quote survives failed refresh only as last traded', (tester) async {
    final fake = _CustomerMarketFake()..status = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openExplore(tester);
    expect(find.text('₹125.55'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsOneWidget);

    fake.failSnapshot = true;
    await tester.tap(find.byKey(const ValueKey('customer-market-refresh-prices')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 20));
    expect(find.text('₹125.55'), findsOneWidget);
    expect(find.textContaining('LAST TRADED'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('overview from earlier successful call survives app pause as historical snapshot',
      (tester) async {
    final fake = _CustomerMarketFake()..indexFreshness = 'LIVE';
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.light),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Market feed · Live quotes'), findsOneWidget);
    final state = tester.state(find.byType(MintTickCustomerMarketPage));
    (state as WidgetsBindingObserver).didChangeAppLifecycleState(AppLifecycleState.paused);
    await tester.pump();

    expect(find.text('Market feed · Stored snapshot'), findsOneWidget);
    expect(find.textContaining('Last verified snapshot'), findsOneWidget);
    expect(find.byKey(const ValueKey('market-idea-ALPHA-EQ')), findsNothing);
    expect(find.text('NIFTY 50'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('app pause retains last price without LIVE badge and resume refreshes', (tester) async {
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
    expect(find.text('₹125.55'), findsOneWidget);
    expect(find.textContaining('LAST TRADED'), findsOneWidget);
    expect(find.textContaining('LIVE ·'), findsNothing);

    final before = fake.snapshotRequests;
    (state as WidgetsBindingObserver).didChangeAppLifecycleState(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(fake.snapshotRequests, greaterThan(before));
    expect(find.text('₹125.55'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  // ---------------------------------------------------------------------------
  // Whole-market board
  // ---------------------------------------------------------------------------

  for (final width in [320.0, 390.0]) {
    for (final brightness in [Brightness.dark, Brightness.light]) {
      testWidgets('Explore shows NSE breadth, cap segments, movers and sectors at '
          '${width.toInt()}dp ${brightness.name}', (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = Size(width, 3200);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final fake = _BoardMarketFake()..indexFreshness = 'LAST_CLOSE';
        await tester.pumpWidget(MaterialApp(
          theme: buildTheme(brightness),
          home: MintTickCustomerMarketPage(repository: fake),
        ));
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('market-session-chip')), findsOneWidget);
        expect(find.text('Closed'), findsOneWidget);
        expect(find.byKey(const ValueKey('customer-market-breadth')), findsOneWidget);
        expect(find.text('Advancing 1204'), findsOneWidget);
        expect(find.text('Declining 1120'), findsOneWidget);
        expect(find.textContaining('2,412 of 2,751 NSE stocks'), findsOneWidget);
        for (final code in ['LARGE', 'MID', 'SMALL', 'MICRO']) {
          expect(find.byKey(ValueKey('market-segment-$code')), findsOneWidget);
        }
        expect(find.text('+0.90%'), findsOneWidget); // mid cap average
        expect(find.text('List not imported'), findsOneWidget); // micro cap list pending
        expect(find.text('Top gainers'), findsOneWidget);
        expect(find.byKey(const ValueKey('market-mover-card-201')), findsOneWidget);
        expect(find.byKey(const ValueKey('market-mover-card-301')), findsOneWidget);
        expect(find.textContaining('Value ₹'), findsOneWidget); // most active by value
        expect(find.byKey(const ValueKey('market-sector-Information Technology')), findsOneWidget);
        expect(find.textContaining('not live'), findsWidgets);
        expect(fake.boardRequests, containsAll(['ALL/GAINERS', 'ALL/LOSERS', 'ALL/ACTIVE']));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox.shrink());
      });
    }
  }

  testWidgets('a cap segment tile opens Movers for that segment', (tester) async {
    final fake = _BoardMarketFake();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('market-segment-MID')));
    await tester.pumpAndSettle();
    expect(fake.boardRequests, contains('MID/GAINERS'));
    expect(find.text('Gainers · Mid cap'), findsOneWidget);
    expect(find.byKey(const ValueKey('market-board-row-201')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('market-movers-sort-1')));
    await tester.pumpAndSettle();
    expect(fake.boardRequests.last, 'MID/LOSERS');
    expect(find.byKey(const ValueKey('market-board-row-301')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a segment whose official list is not imported says so instead of guessing',
      (tester) async {
    final fake = _BoardMarketFake();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('market-segment-MICRO')));
    await tester.pumpAndSettle();
    expect(find.textContaining('has not been imported yet'), findsOneWidget);
    expect(find.byType(BoardStockRow), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a board row opens stock detail directly by instrument id', (tester) async {
    final fake = _BoardMarketFake();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    final before = fake.searchExchanges.length;
    await tester.ensureVisible(find.byKey(const ValueKey('market-mover-card-202')));
    await tester.tap(find.byKey(const ValueKey('market-mover-card-202')));
    await tester.pumpAndSettle();
    expect(find.byType(MintTickStockDetailPage), findsOneWidget);
    expect(fake.searchExchanges.length, before, reason: 'no symbol search round trip');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('Sectors tab shows the heat map and opens a sector drill-down', (tester) async {
    final fake = _BoardMarketFake();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    await _openTab(tester, 'Sectors');
    expect(find.text('Sector heat map'), findsOneWidget);
    expect(find.textContaining('1 of 2 sectors up'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('market-sector-Financial Services')).first);
    await tester.pumpAndSettle();
    expect(fake.boardRequests.last, 'ALL/CHANGE/Financial Services');
    expect(find.text('Financial Services'), findsWidgets);
    expect(find.byKey(const ValueKey('market-board-row-201')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('board failure falls back to the Nifty 50 overview with a clear note',
      (tester) async {
    final fake = _BoardMarketFake()..failSummary = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: fake),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining('Whole-market data is unavailable'), findsOneWidget);
    expect(find.text('Market breadth · Nifty 50 only'), findsOneWidget);
    expect(find.byKey(const ValueKey('market-segment-LARGE')), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('coverage sheet reports stored FULL quote coverage for NSE', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerMarketPage(repository: _BoardMarketFake()),
    ));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const ValueKey('market-coverage-info')));
    await tester.tap(find.byKey(const ValueKey('market-coverage-info')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('market-full-quote-coverage')), findsOneWidget);
    expect(find.text('Stored FULL quotes 620/2756 · not live'), findsOneWidget);
    expect(find.textContaining('Micro cap: official list not imported'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  group('board contract', () {
    test('parses summary and rejects breadth that does not reconcile', () {
      final summary = CustomerBoardSummary.fromJson(boardSummaryJson());
      expect(summary.segment('MICRO')!.available, isFalse);
      expect(summary.segment('ALL')!.breadth.total, 2412);
      final broken = boardSummaryJson();
      ((broken['segments'] as List).first as Map)['breadth'] =
          {'advancers': 5, 'decliners': 5, 'unchanged': 0, 'total': 11};
      expect(() => CustomerBoardSummary.fromJson(broken), throwsFormatException);
    });

    test('freshness without an as-of time must be UNAVAILABLE', () {
      expect(() => CustomerBoardFreshness.fromJson({'state': 'LIVE'}), throwsFormatException);
      expect(() => CustomerBoardFreshness.fromJson(
          {'state': 'UNAVAILABLE', 'asOf': '2026-11-13T09:59:00Z'}), throwsFormatException);
      expect(CustomerBoardFreshness.fromJson({'state': 'UNAVAILABLE'}).asOf, isNull);
    });

    test('rows reject unknown segments, non-positive prices and inverted ranges', () {
      expect(() => CustomerBoardRow.fromJson(
          _boardRow(1, 'X-EQ', 'X', 10, 9, segment: 'NANO')), throwsFormatException);
      expect(() => CustomerBoardRow.fromJson(_boardRow(1, 'X-EQ', 'X', 0, 9)),
          throwsFormatException);
      final inverted = _boardRow(1, 'X-EQ', 'X', 10, 9)..['high'] = 5..['low'] = 8;
      expect(() => CustomerBoardRow.fromJson(inverted), throwsFormatException);
      final row = CustomerBoardRow.fromJson(_boardRow(7, 'M&M-EQ', 'Mahindra', 10, 9));
      expect(row.symbol, 'M&M');
      expect(row.toInstrument().id, 7);
    });

    test('Indian number formatting', () {
      expect(formatInr(1234567.8), '12,34,567.80');
      expect(formatInr(999), '999.00');
      expect(formatInr(-24000), '-24,000.00');
      expect(formatValue(123400000), '₹12.34 Cr');
      expect(formatValue(250000), '₹2.50 L');
    });

    test('monogram uses company words, not ticker prefixes', () {
      expect(MintTickEquityAvatar.initials('AADHARHFC-EQ', 'Aadhar Housing Finance Ltd.'), 'AH');
      expect(MintTickEquityAvatar.initials('TCS-EQ', 'Tata Consultancy Services Ltd.'), 'TC');
      expect(MintTickEquityAvatar.initials('XYZ-EQ', ''), 'XY');
    });
  });
}
