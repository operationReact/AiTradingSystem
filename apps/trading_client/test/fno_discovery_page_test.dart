import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_fno_discovery_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_fno_discovery_page.dart';

class _NoDiscovery extends Fake implements CustomerFnoDiscoveryRepository {}

class _LiveDiscovery extends Fake implements CustomerFnoDiscoveryRepository {
  @override
  Future<CustomerFnoDiscoveryCapability> capability() async =>
      CustomerFnoDiscoveryCapability.decode({
        'displayRightsVerified': true,
        'feedConfigured': true,
        'sourceReady': true,
        'marketOpen': true,
        'status': 'LIVE_QUOTES',
        'streamState': 'CONNECTED',
        'freshExchangeTimedQuotes': 6,
        'latestFreshExchangeTradeAt': DateTime.now().toUtc().toIso8601String(),
        'contractDiscoveryVerified': true,
      });

  @override
  Future<CustomerFnoExpiries> expiries() async => CustomerFnoExpiries(
        status: 'VERIFIED',
        observedAt: DateTime.now().toUtc(),
        expiries: [DateTime.parse('2026-11-19')],
      );

  @override
  Future<CustomerFnoMarket> market(DateTime expiry) async => _liveMarket([
        _strike(25200, 300, 100),
        _strike(25300, 150, 140),
        _strike(25400, 60, 200),
      ]);
}

class _ClosedDiscovery extends _LiveDiscovery {
  @override
  Future<CustomerFnoMarket> market(DateTime expiry) async => _liveMarket([
        _strike(25200, 300, 100),
        _strike(25300, 150, 140),
        _strike(25400, 60, 200),
      ], status: 'MARKET_CLOSED');
}

Map<String, Object?> _leg(String side, {bool fresh = true, num? ltp = 110}) => {
  'instrumentKey': 'NSE_FO|NIFTY_25300_$side',
  'tradingSymbol': 'NIFTY 25300 $side',
  'side': side,
  'lotSize': 65,
  'tickSize': 0.05,
  'bid': fresh ? 109.5 : null,
  'ask': fresh ? 110.5 : null,
  'exchangeAt': fresh ? '2026-11-17T08:00:00Z' : null,
  'observedAt': fresh ? '2026-11-17T08:00:01Z' : null,
  'quoteFresh': fresh,
  'paperEligible': false,
  'ltp': fresh ? ltp : null,
  'previousClose': fresh ? 100 : null,
  'openInterest': fresh ? 1200 : null,
};

CustomerFnoMarket _market({bool fresh = true}) => CustomerFnoMarket.decode({
  'underlyingKey': 'NSE_INDEX|Nifty 50',
  'expiry': '2026-11-19',
  'source': 'UPSTOX_ANALYTICS',
  'status': fresh ? 'FRESH_QUOTES' : 'MARKET_CLOSED',
  'snapshotAt': '2026-11-17T08:00:01Z',
  'displayRightsVerified': true,
  'liveOrderEnabled': false,
  'strikes': [{
    'strikePrice': 25300,
    'call': _leg('CE', fresh: fresh, ltp: 110),
    'put': _leg('PE', fresh: fresh, ltp: 93),
  }],
});

void main() {
  test('NSE option list splits actual CE/PE without synthetic contracts', () {
    final market = _market();
    final calls = MintTickFnoDiscoveryFacts.optionLegs(market, 'CE');
    final puts = MintTickFnoDiscoveryFacts.optionLegs(market, 'PE');
    expect(calls, hasLength(1));
    expect(calls.single.instrumentKey, 'NSE_FO|NIFTY_25300_CE');
    expect(puts.single.side, 'PE');
    expect(MintTickFnoDiscoveryFacts.optionLegs(market, 'FUT'), isEmpty);
  });

  test('All CE + PE reveals every source-listed contract without filtering', () {
    final market = _market();
    final all = MintTickFnoDiscoveryFacts.optionLegs(market, 'ALL');
    expect(all, hasLength(2));
    expect(all.map((l) => l.side).toList(), ['CE', 'PE']);
    expect(all.every((l) => l.instrumentKey.startsWith('NSE_FO|')), isTrue);
    expect(all.every((l) => l.lotSize == 65), isTrue);
  });

  test('All metric cards require genuine 15s exchange-time freshness', () {
    final market = _market();
    final fresh = MintTickFnoDiscoveryFacts.freshLegs(
        market, DateTime.parse('2026-11-17T08:00:08Z'));
    expect(fresh, hasLength(2));
    expect(fresh.first.openInterest, 1200);
    expect(fresh.first.previousClose, 100);
    expect(fresh.first.ltp, 110);
    expect(MintTickFnoDiscoveryFacts.freshLegs(
        market, DateTime.parse('2026-11-17T08:00:17Z')), isEmpty);
    expect(MintTickFnoDiscoveryFacts.freshLegs(
        _market(fresh: false), DateTime.parse('2026-11-17T08:00:08Z')), isEmpty);
  });

  test('OI PCR uses only complete fresh CE/PE source rows', () {
    // Quote fixtures are created after this test starts: evaluate one second
    // later so sub-millisecond construction skew is not mistaken for future data.
    final now = DateTime.now().toUtc().add(const Duration(seconds: 1));
    final complete = _liveMarket([
      _strike(25200, 300, 100),
      _strike(25300, 150, 140),
    ]);
    final total = MintTickFnoDiscoveryFacts.verifiedOiTotals(complete, now);
    expect(total, isNotNull);
    expect(total!.calls, 2400);
    expect(total.puts, 2400);
    expect(total.pcr, 1.0);

    final stale = _liveMarket([{
      'strikePrice': 25300,
      'call': _staleLeg('CE', 150),
      'put': _liveLeg('PE', 140),
    }]);
    expect(MintTickFnoDiscoveryFacts.verifiedOiTotals(stale, now), isNull);

    final missingOi = _liveLeg('PE', 140)..remove('openInterest');
    final incomplete = _liveMarket([{
      'strikePrice': 25300,
      'call': _liveLeg('CE', 150),
      'put': missingOi,
    }]);
    expect(MintTickFnoDiscoveryFacts.verifiedOiTotals(incomplete, now), isNull);

    final partial = _liveMarket([{
      'strikePrice': 25300,
      'call': _liveLeg('CE', 150),
    }]);
    expect(MintTickFnoDiscoveryFacts.verifiedOiTotals(partial, now), isNull);

    final zeroCallOi = _liveMarket([{
      'strikePrice': 25300,
      'call': _liveLeg('CE', 150)..['openInterest'] = 0,
      'put': _liveLeg('PE', 140),
    }]);
    expect(MintTickFnoDiscoveryFacts.verifiedOiTotals(zeroCallOi, now), isNull);

    final closed = _liveMarket([
      _strike(25300, 150, 140),
    ], status: 'MARKET_CLOSED');
    expect(MintTickFnoDiscoveryFacts.verifiedOiTotals(closed, now), isNull);
  });

  test('close-to-close changes only appear for fresh verified V3 quotes', () {
    final market = _market();
    final changes = MintTickFnoDiscoveryFacts.previousCloseMovers(market);
    expect(changes.map((leg) => leg.side).toList(), ['CE','PE']);
    expect(changes.first.priceChangePct, 10);
    expect(MintTickFnoDiscoveryFacts.previousCloseMovers(
        _market(fresh: false)), isEmpty);
  });

  test('a real exchange timestamp expires after 15 seconds', () {
    final leg = CustomerFnoLeg.decode(_leg('CE'));
    expect(MintTickFnoDiscoveryFacts.recentTick(leg,
        DateTime.parse('2026-11-17T08:00:08Z')), isTrue);
    expect(MintTickFnoDiscoveryFacts.recentTick(leg,
        DateTime.parse('2026-11-17T08:00:17Z')), isFalse);
    expect(MintTickFnoDiscoveryFacts.recentTick(leg,
        DateTime.parse('2026-11-17T07:59:59Z')), isFalse);
    final stale = CustomerFnoLeg.decode(_leg('CE', fresh: false));
    expect(MintTickFnoDiscoveryFacts.recentTick(stale,
        DateTime.parse('2026-11-17T08:00:02Z')), isFalse);
  });

  test('contract discovery is decoded separately from live-price display', () {
    final discoveryOnly = CustomerFnoDiscoveryCapability.decode({
      'displayRightsVerified': false,
      'contractDiscoveryVerified': true,
      'feedConfigured': false,
      'sourceReady': true,
      'marketOpen': false,
      'status': 'CONTRACTS_LISTED_PRICES_NOT_AUTHORIZED',
      'streamState': 'UNAVAILABLE',
      'freshExchangeTimedQuotes': 0,
      'latestFreshExchangeTradeAt': null,
    });
    expect(discoveryOnly.contractDiscoveryVerified, isTrue);
    expect(discoveryOnly.displayRightsVerified, isFalse);
    expect(discoveryOnly.live, isFalse);

    // Older payloads without the field keep the display-rights meaning.
    final legacy = CustomerFnoDiscoveryCapability.decode({
      'displayRightsVerified': false,
      'feedConfigured': false,
      'sourceReady': true,
      'marketOpen': false,
      'status': 'DISPLAY_RIGHTS_NOT_VERIFIED',
      'streamState': 'UNAVAILABLE',
      'freshExchangeTimedQuotes': 0,
      'latestFreshExchangeTradeAt': null,
    });
    expect(legacy.contractDiscoveryVerified, isFalse);

    expect(() => CustomerFnoDiscoveryCapability.decode({
      'displayRightsVerified': false,
      'contractDiscoveryVerified': 'yes',
      'feedConfigured': false,
      'sourceReady': true,
      'marketOpen': false,
      'status': 'DISPLAY_RIGHTS_NOT_VERIFIED',
      'streamState': 'UNAVAILABLE',
      'freshExchangeTimedQuotes': 0,
      'latestFreshExchangeTradeAt': null,
    }), throwsFormatException);
  });

  test('source capability requires exchange-timed proof, not socket alone', () {
    final parsed = CustomerFnoDiscoveryCapability.decode({
      'displayRightsVerified': true,
      'feedConfigured': true,
      'sourceReady': true,
      'marketOpen': true,
      'status': 'LIVE_QUOTES',
      'streamState': 'CONNECTED',
      'freshExchangeTimedQuotes': 2,
      'latestFreshExchangeTradeAt': '2026-11-17T08:00:00Z',
    });
    expect(parsed.live, isTrue);
    expect(parsed.freshExchangeTimedQuotes, 2);
    expect(() => CustomerFnoDiscoveryCapability.decode({
      'displayRightsVerified': true,
      'feedConfigured': true,
      'sourceReady': true,
      'marketOpen': true,
      'status': 'LIVE_QUOTES',
      'streamState': 'CONNECTED',
      'freshExchangeTimedQuotes': 0,
      'latestFreshExchangeTradeAt': null,
    }), throwsFormatException);
  });

  testWidgets('empty F&O discover offers room creation, not invented prices',
      (tester) async {
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    var createCount = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms,
          discovery: _NoDiscovery(),
          onOpenRoom: (_) {},
          onCreateRoom: () => createCount++,
        ),
      )),
    ));
    await tester.pump();
    expect(find.text('F&O · Discover'), findsOneWidget);
    expect(find.textContaining('Market discovery works without a room.'), findsOneWidget);
    expect(find.text('Price leaders unavailable'), findsOneWidget);
    expect(find.text('Create F&O room'), findsWidgets);
    final create = find.text('Create F&O room').first;
    await tester.ensureVisible(create);
    await tester.tap(create);
    expect(createCount, 1);
  });

  testWidgets('live chain renders at 360dp with estimated ATM and no overflow',
      (tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms,
          discovery: _LiveDiscovery(),
        ),
      )),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(tester.takeException(), isNull);
    expect(find.text('LIVE'), findsOneWidget);
    expect(find.text('Explore NIFTY options'), findsOneWidget);
    expect(find.byKey(const ValueKey('fno-discovery-quick-chain')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('fno-discovery-tab-chain')));
    await tester.pump();
    expect(find.textContaining('ATM ≈ 25300'), findsOneWidget);
    expect(find.text('ATM'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('fno-discovery-tab-insights')));
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.text('Options positioning'), findsOneWidget);
    expect(find.text('OI PCR'), findsOneWidget);
    expect(find.text('1.00'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('fno-discovery-tab-movers')));
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.text('Top gainers'), findsOneWidget);
  });

  testWidgets('closed market starts with overview and search-first chain',
      (tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms, discovery: _ClosedDiscovery()),
      )),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(tester.takeException(), isNull);
    expect(find.text('Price leaders unavailable'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('fno-discovery-tab-chain')));
    await tester.pump();
    expect(find.textContaining('No strike selected.'), findsOneWidget);
    expect(find.text('25300'), findsNothing);
    await tester.enterText(
        find.byKey(const ValueKey('fno-discovery-strike-search')), '25300');
    await tester.pump();
    expect(find.textContaining('Showing listed strikes near 25300'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  group('ATM estimate and freshness', () {
    test('picks the strike where fresh CE and PE last prices are closest', () {
      final market = _liveMarket([
        _strike(25200, 300, 100),
        _strike(25300, 150, 140),
        _strike(25400, 60, 200),
      ]);
      expect(
          MintTickFnoDiscoveryFacts.atmStrike(market, DateTime.now().toUtc()),
          25300);
    });

    test('withholds ATM when a strike has a stale leg', () {
      final market = _liveMarket([
        {
          'strikePrice': 25300,
          'call': _staleLeg('CE', 150),
          'put': _liveLeg('PE', 140),
        },
      ]);
      expect(
          MintTickFnoDiscoveryFacts.atmStrike(market, DateTime.now().toUtc()),
          isNull);
    });

    test('withholds ATM for a closed market', () {
      final market = _liveMarket([
        _strike(25300, 150, 140),
      ], status: 'MARKET_CLOSED');
      expect(
          MintTickFnoDiscoveryFacts.atmStrike(market, DateTime.now().toUtc()),
          isNull);
      expect(MintTickFnoDiscoveryFacts.freshLegs(market,
          DateTime.now().toUtc()), isEmpty);
    });
  });
}

Map<String, Object?> _liveLeg(String side, num ltp) => {
  'instrumentKey': 'NSE_FO|NIFTY_${ltp}_$side',
  'tradingSymbol': 'NIFTY $ltp $side',
  'side': side,
  'lotSize': 65,
  'tickSize': 0.05,
  'bid': ltp - 0.5,
  'ask': ltp + 0.5,
  'exchangeAt': DateTime.now().toUtc().toIso8601String(),
  'observedAt': DateTime.now().toUtc().toIso8601String(),
  'quoteFresh': true,
  'paperEligible': false,
  'ltp': ltp,
  'previousClose': ltp,
  'openInterest': 1200,
};

Map<String, Object?> _staleLeg(String side, num ltp) =>
    _liveLeg(side, ltp)..['quoteFresh'] = false;

Map<String, Object?> _strike(num strike, num call, num put) => {
  'strikePrice': strike,
  'call': _liveLeg('CE', call),
  'put': _liveLeg('PE', put),
};

CustomerFnoMarket _liveMarket(List<Map<String, Object?>> strikes,
        {String status = 'FRESH_QUOTES'}) =>
    CustomerFnoMarket.decode({
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'expiry': '2026-11-19',
      'source': 'UPSTOX_ANALYTICS',
      'status': status,
      'snapshotAt': DateTime.now().toUtc().toIso8601String(),
      'displayRightsVerified': true,
      'liveOrderEnabled': false,
      'strikes': strikes,
    });
