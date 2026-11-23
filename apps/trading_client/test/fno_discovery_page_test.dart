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

class _RetainedDiscovery extends _LiveDiscovery {
  @override
  Future<CustomerFnoExpiries> expiries() async => CustomerFnoExpiries(
        status: 'VERIFIED',
        observedAt: DateTime.now().toUtc(),
        expiries: [DateTime.parse('2026-11-23')],
      );

  @override
  Future<CustomerFnoMarket> market(DateTime expiry) async {
    final exchange = DateTime.now().toUtc().subtract(const Duration(days: 1));
    Map<String, Object?> leg(String side, num price) => {
      'instrumentKey': 'NSE_FO|NIFTY_25300_$side',
      'tradingSymbol': 'NIFTY 25300 $side',
      'side': side,
      'lotSize': 65,
      'tickSize': .05,
      'bid': null,
      'ask': null,
      'exchangeAt': exchange.toIso8601String(),
      'observedAt': exchange.add(const Duration(seconds: 1)).toIso8601String(),
      'quoteFresh': false,
      'paperEligible': false,
      'ltp': price,
      'previousClose': price - 10,
      'openInterest': 1800,
    };
    return CustomerFnoMarket.decode({
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'expiry': '2026-11-23',
      'status': 'MARKET_CLOSED',
      'snapshotAt': exchange.toIso8601String(),
      'displayRightsVerified': true,
      'liveOrderEnabled': false,
      'strikes': [
        {'strikePrice': 25300,
         'call': leg('CE', 110), 'put': leg('PE', 93)}
      ],
    });
  }
}

class _RetainedNoSocketDiscovery extends _RetainedDiscovery {
  @override
  Future<CustomerFnoDiscoveryCapability> capability() async =>
      CustomerFnoDiscoveryCapability.decode({
        'displayRightsVerified': true,
        'contractDiscoveryVerified': true,
        'feedConfigured': false,
        'sourceReady': true,
        'marketOpen': false,
        'status': 'MARKET_CLOSED',
        'streamState': 'UNAVAILABLE',
        'freshExchangeTimedQuotes': 0,
        'latestFreshExchangeTradeAt': null,
      });
}

class _ChainSnapshotDiscovery extends _RetainedNoSocketDiscovery {
  @override
  Future<CustomerFnoMarket> market(DateTime expiry) async =>
      CustomerFnoMarket.decode({
        'underlyingKey': 'NSE_INDEX|Nifty 50',
        'expiry': '2026-11-23',
        // Upstox chain supplies this reference; closed-market ATM uses it
        // only to locate the strike, never as an executable live spot quote.
        'underlyingReferenceSpot': 25300,
        'source': 'UPSTOX_ANALYTICS',
        'status': 'MARKET_CLOSED',
        'snapshotAt': DateTime.now().toUtc().toIso8601String(),
        'displayRightsVerified': true,
        'liveOrderEnabled': false,
        'strikes': [{
          'strikePrice': 25300,
          'call': _leg('CE', fresh: false),
          'put': _leg('PE', fresh: false),
        }],
        'chainSnapshotIndicators': {
          'NSE_FO|NIFTY_25300_CE': {
            'ltp': 121.5,
            'closePrice': 112.0,
            'bid': 121.0,
            'ask': 122.0,
            'openInterest': 5200,
            'previousOpenInterest': 4800,
            'volume': 34000,
            'iv': 0.21,
            'delta': 0.5,
            'gamma': 0.002,
            'theta': -2.5,
            'vega': 3.2,
          },
          'NSE_FO|NIFTY_25300_PE': {
            'ltp': 90.0,
            'closePrice': 95.0,
            'openInterest': 2600,
            'previousOpenInterest': 2500,
            'iv': 0.23,
            'delta': -0.45,
          },
        },
      });
}

/// Missing AUTO feed is not permission to hide a separately authorized
/// source-listed V2 chain, nor permission to treat it as a trade quote.
class _OpenNoV3Discovery extends _ChainSnapshotDiscovery {
  @override
  Future<CustomerFnoDiscoveryCapability> capability() async =>
      CustomerFnoDiscoveryCapability.decode({
        'displayRightsVerified': true,
        'contractDiscoveryVerified': true,
        'feedConfigured': false,
        'sourceReady': true,
        'marketOpen': true,
        'status': 'FNO_V3_FEED_UNAVAILABLE',
        'streamState': 'UNAVAILABLE',
        'freshExchangeTimedQuotes': 0,
        'latestFreshExchangeTradeAt': null,
      });

  @override
  Future<CustomerFnoMarket> market(DateTime expiry) async {
    final source = await super.market(expiry);
    return CustomerFnoMarket(
      underlyingKey: source.underlyingKey,
      expiry: source.expiry,
      status: 'NO_FRESH_QUOTES',
      snapshotAt: source.snapshotAt,
      displayRightsVerified: source.displayRightsVerified,
      liveOrderEnabled: false,
      strikes: source.strikes,
      underlyingReferenceSpot: source.underlyingReferenceSpot,
      chainSnapshotIndicators: source.chainSnapshotIndicators,
    );
  }
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
  test('reference spot is decoded for ordering, never treated as live LTP', () {
    final market = CustomerFnoMarket.decode({
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'expiry': '2026-11-23',
      'status': 'MARKET_CLOSED',
      'snapshotAt': '2026-11-20T10:00:00Z',
      'displayRightsVerified': true,
      'liveOrderEnabled': false,
      'underlyingReferenceSpot': 25800.60,
      'strikes': <Object>[],
    });
    expect(market.underlyingReferenceSpot, 25800.60);
    expect(market.hasFreshQuotes, isFalse);
    expect(MintTickFnoDiscoveryFacts.atmStrike(
      market, DateTime.now().toUtc()), isNull);
  });

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

  test('V3 LTP can be live for display without executable depth', () {
    final at = DateTime.now().toUtc().toIso8601String();
    final parsed = CustomerFnoDiscoveryCapability.decode({
      'displayRightsVerified': true,
      'feedConfigured': true,
      'sourceReady': true,
      'marketOpen': true,
      'status': 'LIVE_QUOTES',
      'streamState': 'CONNECTED',
      'freshExchangeTimedQuotes': 0,
      'latestFreshExchangeTradeAt': null,
      'freshDisplayQuotes': 1,
      'latestFreshDisplayTradeAt': at,
      'subscribedInstruments': 64,
    });
    expect(parsed.live, isTrue);
    expect(parsed.freshDisplayObserved, isTrue);
    expect(parsed.executableDepthObserved, isFalse);
    expect(parsed.freshDisplayQuotes, 1);
    expect(parsed.subscribedInstruments, 64);
  });

  test('unverified, absent or overstated LTP coverage fails closed', () {
    final at = DateTime.now().toUtc().toIso8601String();
    Map<String, Object?> source() => {
      'displayRightsVerified': true,
      'feedConfigured': true,
      'sourceReady': true,
      'marketOpen': true,
      'status': 'LIVE_QUOTES',
      'streamState': 'CONNECTED',
      'freshExchangeTimedQuotes': 0,
      'latestFreshExchangeTradeAt': null,
      'freshDisplayQuotes': 1,
      'latestFreshDisplayTradeAt': at,
      'subscribedInstruments': 64,
    };
    expect(() => CustomerFnoDiscoveryCapability.decode(
      source()..['latestFreshDisplayTradeAt'] = null),
      throwsFormatException);
    expect(() => CustomerFnoDiscoveryCapability.decode(
      source()..['subscribedInstruments'] = 0),
      throwsFormatException);
    expect(() => CustomerFnoDiscoveryCapability.decode(
      source()..['displayRightsVerified'] = false),
      throwsFormatException);
    expect(() => CustomerFnoDiscoveryCapability.decode(
      source()..['marketOpen'] = false),
      throwsFormatException);
  });

  testWidgets('closed Options market shows retained actual LTP with Last label',
      (tester) async {
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms,
          discovery: _RetainedDiscovery(),
        ),
      )),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('110.00'), findsWidgets);
    expect(find.textContaining('Market closed · prices are not live'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('closed Options without socket still renders authorized last trades',
      (tester) async {
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms,
          discovery: _RetainedNoSocketDiscovery(),
        ),
      )),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('110.00'), findsWidgets);
    expect(find.textContaining('Market closed · prices are not live'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('real Upstox chain snapshot is visible but never a V3 tick',
      (tester) async {
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms,
          discovery: _ChainSnapshotDiscovery(),
        ),
      )),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    final contract = find.byKey(
        const ValueKey('fno-chain-leg-NSE_FO|NIFTY_25300_CE'));
    await tester.ensureVisible(contract);
    await tester.tap(contract);
    await tester.pumpAndSettle();
    expect(find.text('Upstox chain snapshot · read only'), findsWidgets);
    expect(find.text('LTP · chain snapshot'), findsWidgets);
    expect(find.text('₹121.50'), findsWidgets);
    expect(find.text('IV · chain snapshot'), findsWidgets);
    expect(find.text('0.21'), findsWidgets);
    expect(find.text('Chain retrieved'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('authorized V2 chain OI populates Highest OI, never Top traded',
      (tester) async {
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms,
          discovery: _ChainSnapshotDiscovery(),
        ),
      )),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.byKey(const ValueKey('fno-discovery-tab-movers')));
    await tester.pump();
    expect(find.text('Market activity'), findsWidgets);
    expect(find.byKey(const ValueKey('minttick-options-smartlist-highest-oi')),
        findsOneWidget);
    expect(find.textContaining('Source-backed V2 reference'), findsOneWidget);
    expect(find.text('5.2K'), findsWidgets);
    await tester.ensureVisible(find.byKey(
        const ValueKey('minttick-options-smartlist-top-traded')));
    await tester.tap(find.byKey(
        const ValueKey('minttick-options-smartlist-top-traded')));
    await tester.pump();
    expect(find.text('34.0K'), findsWidgets);
    expect(find.text('VOLUME'), findsWidgets);
    await tester.ensureVisible(find.byKey(
        const ValueKey('minttick-options-smartlist-oi-gainers')));
    await tester.tap(find.byKey(
        const ValueKey('minttick-options-smartlist-oi-gainers')));
    await tester.pump();
    expect(find.text('400'), findsWidgets);
    expect(find.text('ΔOI'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('open market without V3 still shows rights-gated V2 reference chain',
      (tester) async {
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms,
          discovery: _OpenNoV3Discovery(),
        ),
      )),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('NIFTY option chain'), findsWidgets);
    expect(find.text('121.50'), findsWidgets);
    // Backend-side contract identity remains read-only and no V3 tick is
    // manufactured by the Flutter client.
    expect(find.textContaining('NO FRESH QUOTES'), findsWidgets);
    expect(tester.takeException(), isNull);
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
    expect(find.byKey(const ValueKey('minttick-options-title')), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-options-index-picker')), findsOneWidget);
    expect(find.textContaining('No verified'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('fno-discovery-tab-trade')));
    await tester.pump();
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
    expect(find.byKey(const ValueKey('minttick-options-title')), findsOneWidget);
    expect(find.text('NIFTY option chain'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('fno-discovery-tab-movers')));
    await tester.pump();
    expect(find.byKey(const ValueKey('minttick-options-ce')), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-options-pe')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('minttick-options-pe')));
    await tester.pump();
    expect(tester.widget<ChoiceChip>(
      find.byKey(const ValueKey('minttick-options-pe'))).selected, isTrue);
    expect(tester.widget<ChoiceChip>(
      find.byKey(const ValueKey('minttick-options-ce'))).selected, isFalse);
    expect(tester.takeException(), isNull);
  });


  testWidgets('320dp chain keeps CE and PE visible without horizontal scrolling',
      (tester) async {
    tester.view.physicalSize = const Size(320, 760);
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
    expect(find.text('NIFTY option chain'), findsWidgets);
    expect(find.text('CALL · CE'), findsOneWidget);
    expect(find.text('PUT · PE'), findsOneWidget);
    expect(find.text('STRIKE'), findsWidgets);
    expect(find.byKey(const ValueKey('fno-chain-mode-price')), findsOneWidget);
    // The metric selector may scroll, but CE/strike/PE quote rows must fit.
    expect(find.byKey(const ValueKey('fno-chain-expand-strikes')), findsNothing);
    expect(tester.takeException(), isNull);
  });


  testWidgets('V2 reference price moves, OI changes and Greeks are visible '
      'without granting executable quotes', (tester) async {
    final rooms = TradingRoomDraftStore();
    addTearDown(rooms.dispose);
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: SingleChildScrollView(
        child: MintTickFnoDiscoveryPage(
          rooms: rooms, discovery: _ChainSnapshotDiscovery()),
      )),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('0.50'), findsOneWidget); // complete-chain OI PCR
    expect(find.text('25300'), findsWidgets); // OI-derived max pain
    expect(find.text('0.22'), findsOneWidget); // ATM IV mean
    expect(find.textContaining('+9.50'), findsWidgets);
    expect(find.textContaining('+8.5%'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('fno-chain-mode-oi')));
    await tester.pump();
    expect(find.textContaining('+400'), findsWidgets);
    expect(find.textContaining('+8.3%'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('fno-chain-mode-greeks')));
    await tester.pump();
    expect(find.text('0.500'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('fno-chain-mode-depth')));
    await tester.pump();
    expect(find.text('121.00'), findsOneWidget);
    expect(find.textContaining('Ask 122.00'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  test('snapshot metrics fail closed on incomplete OI and missing reference',
      () {
    final now = DateTime.now().toUtc();
    final base = _liveMarket([
      _strike(25200, 300, 100),
      _strike(25300, 150, 140),
      _strike(25400, 60, 200),
    ]);
    expect(MintTickFnoTerminalAnalytics.pcr(base), isNull);
    expect(MintTickFnoTerminalAnalytics.estimatedMaxPain(base), isNull);
    expect(MintTickFnoTerminalAnalytics.atmIv(base, null), isNull);
    expect(MintTickFnoDiscoveryFacts.referencePriceMovers(
        base, now, 'CE', positive: true), isEmpty);
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
