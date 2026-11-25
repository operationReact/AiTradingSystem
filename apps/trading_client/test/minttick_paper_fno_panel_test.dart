import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_paper_fno_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_paper_fno_panel.dart';

Map<String, dynamic> leg({bool fresh = true, String side = 'CE'}) => {
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

CustomerFnoMarket market({String status = 'FRESH_QUOTES', bool fresh = true,
    bool rights = true}) =>
    CustomerFnoMarket.decode({
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'expiry': '2026-11-20',
      'status': status,
      'snapshotAt': '2026-11-16T04:30:00Z',
      'displayRightsVerified': rights,
      'liveOrderEnabled': false,
      'strikes': [
        {'strikePrice': 24000, 'call': leg(fresh: fresh), 'put': null},
      ],
    });

CustomerFnoPosition heldPosition() => CustomerFnoPosition.decode({
      'instrumentKey': 'NSE_FO|NIFTY24CE',
      'optionType': 'CE',
      'expiry': '2026-11-20',
      'lotSize': 75,
      'quantity': 75,
      'costBasisPaise': 765000,
      'indicativeUnrealizedPnlPaise': null,
      'priceFresh': false,
    });

CustomerFnoExpiries verifiedExpiries([List<String> dates = const ['2026-11-20']]) =>
    CustomerFnoExpiries.decode({
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'status': 'VERIFIED',
      'observedAt': '2026-11-16T04:00:00Z',
      'expiries': dates,
    });

CustomerFnoExpiries blockedExpiries() => CustomerFnoExpiries.decode({
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'status': 'BLOCKED_SOURCE_NOT_BOUND',
      'observedAt': null,
      'expiries': <String>[],
    });

CustomerFnoReceipt receiptFixture({bool reconciled = true, bool approved = false}) => CustomerFnoReceipt.decode({
      'status': reconciled ? 'RECONCILED' : 'MISMATCH',
      'checkedAt': '2026-11-16T04:31:00Z',
      'recordedFills': 2,
      'buyFills': 1,
      'sellFills': 1,
      'completedRoundTrips': 1,
      'openContracts': 0,
      'computedRealizedPnlPaise': 4500,
      'recordedRealizedPnlPaise': 4500,
      'walletReconciled': reconciled,
      'marketDataVerified': false,
      'providerRightsVerified': false,
      'tradingApproved': approved,
      'blockerCodes': ['PROVIDER_RIGHTS_NOT_VERIFIED'],
    });

CustomerFnoPosition heldCe(int quantity) => CustomerFnoPosition.decode({
      'instrumentKey': 'NSE_FO|NIFTY24CE',
      'optionType': 'CE',
      'expiry': '2026-11-20',
      'lotSize': 75,
      'quantity': quantity,
      'costBasisPaise': 765000,
      'indicativeUnrealizedPnlPaise': null,
      'priceFresh': false,
    });

CustomerFnoFill buyFill({String side = 'BUY'}) => CustomerFnoFill.decode({
      'id': '3f2c9d1e-0000-4000-8000-000000000009',
      'instrumentKey': 'NSE_FO|NIFTY24CE',
      'optionType': 'CE',
      'expiry': '2026-11-20',
      'side': side,
      'quantity': 75,
      'lotSize': 75,
      'fillPrice': 102,
      'notionalPaise': 765000,
      'estimatedFeesPaise': 120,
      'realizedPnlDeltaPaise': side == 'SELL' ? 4500 : 0,
      'cashAfterPaise': 9234880,
      'createdAt': '2026-11-16T04:30:02Z',
    });

class _FakeRepo implements CustomerPaperFnoRepository {
  _FakeRepo({this.marketResult, this.markets, this.failure, this.submitFailure,
      this.positionResult = const [], this.positionsFailure, this.expiriesResult,
      this.ordersResult = const [], this.submitResult, this.receiptResult, this.receiptFails = false,
      this.capabilityResult, this.capabilityFailure});
  final CustomerFnoCapability? capabilityResult;
  final ApiFailure? capabilityFailure;
  int expiryCalls = 0;
  int inventoryCalls = 0;
  int marketCalls = 0;

  @override
  Future<CustomerFnoCapability> capability(String roomId) async {
    if (capabilityFailure != null) throw capabilityFailure!;
    return capabilityResult ?? CustomerFnoCapability.decode({
      'status': 'SOURCE_CHECK_REQUIRED',
      'readOnlyConfigured': true,
      'paperConfigured': true,
      'liveExecutionAuthorized': false,
      'blockerCodes': <String>[],
    });
  }

  final CustomerFnoReceipt? receiptResult;
  final bool receiptFails;
  final CustomerFnoExpiries? expiriesResult;
  final List<CustomerFnoFill> ordersResult;
  final CustomerFnoFill? submitResult;
  final CustomerFnoMarket? marketResult;
  /// Successive responses for refresh tests; the last one repeats.
  final List<CustomerFnoMarket>? markets;
  int _marketCalls = 0;
  final ApiFailure? failure;
  final ApiFailure? submitFailure;
  final List<CustomerFnoPosition> positionResult;
  final ApiFailure? positionsFailure;
  final submits = <Map<String, Object?>>[];

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError('${invocation.memberName}');

  @override
  Future<CustomerFnoMarket> market(String roomId, DateTime expiry) async {
    marketCalls++;
    if (failure != null) throw failure!;
    if (markets != null) {
      final i = _marketCalls < markets!.length ? _marketCalls : markets!.length - 1;
      _marketCalls++;
      return markets![i];
    }
    return marketResult!;
  }

  @override
  Future<CustomerFnoExpiries> expiries(String roomId) async {
    expiryCalls++;
    return expiriesResult ?? verifiedExpiries();
  }

  @override
  Future<List<CustomerFnoFill>> orders(String roomId) async => ordersResult;

  @override
  Future<CustomerFnoReceipt> reconciliation(String roomId) async {
    if (receiptFails) throw const ApiHttpFailure('UNAVAILABLE', null, 503);
    return receiptResult ?? receiptFixture();
  }

  @override
  Future<List<CustomerFnoPosition>> positions(String roomId) async {
    inventoryCalls++;
    if (positionsFailure != null) throw positionsFailure!;
    return positionResult;
  }

  @override
  Future<CustomerFnoFill> submit({
    required String roomId,
    required String idempotencyKey,
    required String instrumentKey,
    required DateTime expiry,
    required String side,
    required int lots,
    required num limitPrice,
  }) async {
    submits.add({'key': idempotencyKey, 'side': side, 'lots': lots, 'price': limitPrice});
    if (submitResult != null) return submitResult!;
    throw submitFailure ?? const ApiTransportFailure('NETWORK_OR_PARSE_FAILURE', null);
  }
}

Future<void> pump(WidgetTester tester, CustomerPaperFnoRepository repo,
    {bool withExpiry = true, DateTime Function()? clock,
     String view = 'trade'}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(900, 2400);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(
    child: MintTickPaperFnoPanel(roomId: 'room', repository: repo,
      initialExpiry: withExpiry ? DateTime(2026, 11, 20) : null,
      view: view,
      clock: clock ?? DateTime.now)))));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('F&O positions tab does not load market data or a buy ticket',
      (tester) async {
    final repo = _FakeRepo(
      positionResult: [heldPosition()], ordersResult: [buyFill()]);
    await pump(tester, repo, view: 'positions');
    expect(find.text('F&O Positions'), findsOneWidget);
    expect(find.textContaining('75 qty'), findsOneWidget);
    expect(find.byKey(const ValueKey('fno-premium-quote-board')), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
    expect(repo.expiryCalls, 0);
    expect(repo.marketCalls, 0);
  });

  testWidgets('F&O orders tab reads only owner-scoped virtual fills',
      (tester) async {
    final repo = _FakeRepo(
      positionResult: [heldPosition()], ordersResult: [buyFill()]);
    await pump(tester, repo, view: 'orders');
    expect(find.text('F&O Orders'), findsOneWidget);
    expect(find.textContaining('BUY 1 lot(s)'), findsOneWidget);
    expect(find.byKey(const ValueKey('fno-premium-quote-board')), findsNothing);
    expect(repo.marketCalls, 0);
    expect(repo.expiryCalls, 0);
  });

  testWidgets('server F&O disabled shows saved private history without market requests',
      (tester) async {
    final repo = _FakeRepo(
      positionResult: [heldPosition()],
      ordersResult: [buyFill()],
      capabilityResult: CustomerFnoCapability.decode({
      'status': 'FNO_PAPER_DISABLED',
      'readOnlyConfigured': false,
      'paperConfigured': false,
      'liveExecutionAuthorized': false,
      'blockerCodes': [
        'FNO_PAPER_DISABLED', 'FNO_V3_FEED_DISABLED',
        'DISPLAY_RIGHTS_NOT_VERIFIED', 'PAPER_USE_RIGHTS_NOT_VERIFIED',
      ],
    }));
    await pump(tester, repo, withExpiry: false);
    expect(find.byKey(const ValueKey('paper-fno-capability-blocked')), findsOneWidget);
    expect(find.textContaining('Real NSE options PAPER orders are disabled'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-real-only')), findsOneWidget);
    expect(find.byKey(const ValueKey('fno-premium-unavailable-overview')),
      findsOneWidget);
    expect(tester.widget<FilledButton>(
      find.byKey(const ValueKey('fno-premium-blocked-call'))).onPressed,
      isNull);
    expect(find.byKey(const ValueKey('synthetic-fno-sandbox')), findsNothing);
    expect(find.textContaining('Synthetic CALL'), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-historical-counts')), findsOneWidget);
    expect(find.textContaining('1 open positions · 1 recent orders'), findsOneWidget);
    expect(find.textContaining('Bookkeeping: RECONCILED'), findsOneWidget);
    expect(find.textContaining('No simulated or broker order can be placed'), findsOneWidget);
    expect(find.text('No virtual F&O orders.'), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
    expect(repo.expiryCalls, 0);
    expect(repo.inventoryCalls, 1);
    expect(repo.marketCalls, 0);
    expect(repo.submits, isEmpty);
    await tester.tap(find.byKey(const ValueKey('paper-fno-capability-retry')));
    await tester.pumpAndSettle();
    expect(repo.expiryCalls, 0);
    expect(repo.inventoryCalls, 2);
  });

  testWidgets('disabled feed never invents zero holdings if history read fails',
      (tester) async {
    final repo = _FakeRepo(
      capabilityResult: CustomerFnoCapability.decode({
        'status': 'FNO_PAPER_DISABLED',
        'readOnlyConfigured': false,
        'paperConfigured': false,
        'liveExecutionAuthorized': false,
        'blockerCodes': ['FNO_PAPER_DISABLED'],
      }),
      positionsFailure: const ApiHttpFailure('UNAVAILABLE', null, 503),
    );
    await pump(tester, repo, withExpiry: false);
    expect(find.textContaining('Saved positions and orders could not be verified'),
        findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-historical-counts')), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
    expect(repo.inventoryCalls, 1);
    expect(repo.marketCalls, 0);
    expect(repo.expiryCalls, 0);
    expect(repo.submits, isEmpty);
  });

  testWidgets('capability HTTP 500 fails closed without probing F&O order routes',
      (tester) async {
    final repo = _FakeRepo(
      capabilityFailure: const ApiHttpFailure('UNAVAILABLE', null, 500),
    );
    await pump(tester, repo);
    expect(find.textContaining('availability could not be verified'), findsOneWidget);
    expect(find.text('No virtual F&O orders.'), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
    expect(repo.marketCalls, 0);
    expect(repo.expiryCalls, 0);
    expect(repo.inventoryCalls, 0);
  });

  testWidgets('configured market with missing PAPER use rights keeps quote ticket blocked',
      (tester) async {
    final repo = _FakeRepo(
      marketResult: market(),
      capabilityResult: CustomerFnoCapability.decode({
        'status': 'PAPER_USE_RIGHTS_NOT_VERIFIED',
        'readOnlyConfigured': true,
        'paperConfigured': false,
        'liveExecutionAuthorized': false,
        'blockerCodes': ['PAPER_USE_RIGHTS_NOT_VERIFIED'],
      }),
    );
    await pump(tester, repo);
    expect(find.byKey(const ValueKey('paper-fno-order-rights-blocked')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(
      find.byKey(const ValueKey('paper-fno-submit'))).onPressed, isNull);
    expect(repo.submits, isEmpty);
  });

  testWidgets('without an entered expiry no request is made and the user is asked for one', (tester) async {
    final repo = _FakeRepo(failure: const ApiHttpFailure('CONFLICT', null, 409));
    await pump(tester, repo, withExpiry: false);
    expect(find.text('Select a verified expiry to see strikes.'), findsOneWidget);
    expect(find.textContaining('display rights'), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
  });

  testWidgets('409 shows the rights blocker and no quotes or ticket', (tester) async {
    await pump(tester, _FakeRepo(failure: const ApiHttpFailure('CONFLICT', null, 409)));
    expect(find.textContaining('display rights are not verified'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-status')), findsNothing);
  });

  testWidgets('a fresh CE quote enables BUY after selection with a tick-valid price', (tester) async {
    final repo = _FakeRepo(marketResult: market());
    await pump(tester, repo);
    expect(find.text('Fresh quotes'), findsOneWidget);
    // The ticket only exists once a contract is selected.
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    final submit = find.byKey(const ValueKey('paper-fno-submit'));
    expect(tester.widget<FilledButton>(submit).onPressed, isNotNull);
    expect(find.text('Ask ₹102.00'), findsOneWidget);
  });

  testWidgets('premium NIFTY options shows only actual quote fields and guarded actions',
      (tester) async {
    await pump(tester, _FakeRepo(marketResult: market()));
    expect(find.byKey(const ValueKey('fno-premium-quote-board')), findsOneWidget);
    expect(find.text('NIFTY 50'), findsOneWidget);
    expect(find.byKey(const ValueKey('fno-index-unavailable')), findsOneWidget);
    expect(find.text('Index LTP unavailable from current F&O API'), findsOneWidget);
    expect(find.text('Ask ₹102.00'), findsOneWidget);
    expect(find.text('Bid ₹101.50'), findsOneWidget);
    expect(find.byKey(const ValueKey('fno-premium-strike-picker')), findsOneWidget);
    expect(find.byKey(const ValueKey('fno-premium-candle-placeholder')), findsOneWidget);
    expect(find.text('Verified option candles unavailable'), findsOneWidget);
    // No empty Greek/Market Insight tiles when the feed lacks these fields.
    expect(find.text('Greeks'), findsNothing);
    expect(find.text('Market Insight'), findsNothing);
    expect(find.text('Delta'), findsNothing);
    expect(find.text('PCR'), findsNothing);
    expect(tester.widget<FilledButton>(
      find.byKey(const ValueKey('fno-premium-buy-call'))).onPressed, isNotNull);
    expect(tester.widget<OutlinedButton>(
      find.byKey(const ValueKey('fno-premium-buy-put'))).onPressed, isNull);
    expect(tester.widget<OutlinedButton>(
      find.byKey(const ValueKey('fno-premium-sell-close'))).onPressed, isNull);
  });

  testWidgets('option quote panel does not leak prices without display permission',
      (tester) async {
    await pump(tester, _FakeRepo(marketResult: market(rights: false)));
    expect(find.textContaining('Ask ₹'), findsNothing);
    expect(find.textContaining('Bid ₹'), findsNothing);
    expect(tester.widget<FilledButton>(
      find.byKey(const ValueKey('fno-premium-buy-call'))).onPressed, isNull);
  });

  testWidgets('option positions display actual percentage return from cost basis',
      (tester) async {
    final p = CustomerFnoPosition.decode({
      'instrumentKey': 'NSE_FO|NIFTY24CE',
      'optionType': 'CE', 'expiry': '2026-11-20',
      'lotSize': 75, 'quantity': 75,
      'costBasisPaise': 765000,
      'indicativeUnrealizedPnlPaise': 76500,
      'priceFresh': true,
    });
    await pump(tester, _FakeRepo(
      marketResult: market(), positionResult: [p]));
    expect(find.byKey(const ValueKey('fno-return-percent-NSE_FO|NIFTY24CE')),
      findsOneWidget);
    expect(find.text('+10.00%'), findsOneWidget);
  });

  testWidgets('premium dashboard shows V3 LTP return OI IV and Greeks only when verified',
      (tester) async {
    final enriched = leg()..addAll({
      'ltp': 125.0, 'previousClose': 100.0, 'openInterest': 5000,
      'iv': 0.18, 'delta': 0.56, 'theta': -2.84,
      'gamma': 0.001, 'vega': 0.09,
    });
    final real = CustomerFnoMarket.decode({
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'expiry': '2026-11-20',
      'status': 'FRESH_QUOTES',
      'snapshotAt': '2026-11-16T04:30:00Z',
      'displayRightsVerified': true,
      'liveOrderEnabled': false,
      'strikes': [
        {'strikePrice': 24000, 'call': enriched, 'put': null},
      ],
    });
    await pump(tester, _FakeRepo(marketResult: real));
    expect(find.text('LTP ₹125.00'), findsOneWidget);
    expect(find.text('+25.00% vs close'), findsOneWidget);
    expect(find.textContaining('OI 5000'), findsOneWidget);
    expect(find.text('0.56'), findsOneWidget);
    expect(find.text('-2.84'), findsOneWidget);
    expect(find.text('18.00%'), findsOneWidget);
    expect(find.text('0.09'), findsOneWidget);
    expect(find.text('Greeks'), findsOneWidget);
    expect(find.text('Market Insight'), findsNothing);
  });

  test('malformed broker metrics cannot produce numeric UI quotes', () {
    final bad = leg()..['ltp'] = double.nan;
    expect(() => CustomerFnoLeg.decode(bad), throwsFormatException);
    final negativeOi = leg()..['openInterest'] = -1;
    expect(() => CustomerFnoLeg.decode(negativeOi), throwsFormatException);
  });

  testWidgets('live options quote refresh preserves listed contract without auto ordering',
      (tester) async {
    final repo = _FakeRepo(marketResult: market());
    await pump(tester, repo);
    final firstReads = repo.marketCalls;
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsOneWidget);
    await tester.pump(const Duration(seconds: 10));
    await tester.pump();
    expect(repo.marketCalls, greaterThan(firstReads));
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsOneWidget);
    expect(repo.submits, isEmpty);
  });

  testWidgets('a stale quote keeps submit disabled even when the leg is selected', (tester) async {
    await pump(tester, _FakeRepo(marketResult: market(fresh: false)));
    expect(find.text('Quote not fresh'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNull);
  });

  testWidgets('a closed market shows the status and never enables orders', (tester) async {
    await pump(tester, _FakeRepo(marketResult: market(status: 'MARKET_CLOSED', fresh: false)));
    expect(find.text('Market closed · orders disabled'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNull);
  });

  testWidgets('submit sends a generated idempotency key and an unknown outcome is not retried', (tester) async {
    final repo = _FakeRepo(marketResult: market());
    await pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('paper-fno-submit')));
    await tester.pumpAndSettle();
    expect(repo.submits, hasLength(1));
    expect(repo.submits.single['side'], 'BUY');
    expect(repo.submits.single['lots'], 1);
    expect((repo.submits.single['key'] as String).startsWith('fno-'), isTrue);
    expect(find.textContaining('outcome is unknown'), findsOneWidget);
  });

  testWidgets('switching BUY to SELL reprices to the server bid and sends that price', (tester) async {
    final repo = _FakeRepo(marketResult: market(), positionResult: [heldCe(75)]);
    await pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(find.byKey(const ValueKey('paper-fno-price'))).controller!.text,
      '102.0');
    await tester.tap(find.text('SELL to close'));
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(find.byKey(const ValueKey('paper-fno-price'))).controller!.text,
      '101.5');
    await tester.tap(find.byKey(const ValueKey('paper-fno-submit')));
    await tester.pumpAndSettle();
    expect(repo.submits.single['side'], 'SELL');
    expect(repo.submits.single['price'], 101.5);
  });

  testWidgets('a 503 on submit is an unknown outcome, never a rejection', (tester) async {
    final repo = _FakeRepo(marketResult: market(),
        submitFailure: const ApiHttpFailure('UNAVAILABLE', null, 503));
    await pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('paper-fno-submit')));
    await tester.pumpAndSettle();
    expect(find.textContaining('outcome is unknown'), findsOneWidget);
    expect(find.textContaining('Nothing was filled'), findsNothing);
  });

  testWidgets('a 409 on submit is a definite rejection', (tester) async {
    final repo = _FakeRepo(marketResult: market(),
        submitFailure: const ApiHttpFailure('CONFLICT', null, 409));
    await pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('paper-fno-submit')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Nothing was filled'), findsOneWidget);
  });

  testWidgets('a refresh that makes the selected CE stale disables BUY even though a PE is fresh', (tester) async {
    CustomerFnoMarket twoLegs({required bool ceFresh}) => CustomerFnoMarket.decode({
          'underlyingKey': 'NSE_INDEX|Nifty 50',
          'expiry': '2026-11-20',
          'status': 'FRESH_QUOTES',
          'snapshotAt': '2026-11-16T04:30:00Z',
          'displayRightsVerified': true,
          'liveOrderEnabled': false,
          'strikes': [
            {
              'strikePrice': 24000,
              'call': leg(fresh: ceFresh),
              'put': {...leg(fresh: true, side: 'PE'),
                'instrumentKey': 'NSE_FO|NIFTY24PE', 'tradingSymbol': 'NIFTY 24000 PE'},
            },
          ],
        });
    var now = DateTime.utc(2026, 11, 16, 4, 30);
    final repo = _FakeRepo(markets: [twoLegs(ceFresh: true), twoLegs(ceFresh: false)]);
    await pump(tester, repo, clock: () => now);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNotNull);

    // Quotes age out after 12s, which reveals the explicit refresh control.
    now = now.add(const Duration(seconds: 13));
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.byKey(const ValueKey('paper-fno-refresh')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-price')), findsNothing);

    // Re-selecting the stale CE must not re-enable BUY.
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNull);
    expect(repo.submits, isEmpty);
  });

  testWidgets('quotes stop being tradable after 12s, and a refresh makes them tradable again', (tester) async {
    var now = DateTime.utc(2026, 11, 16, 4, 30);
    final repo = _FakeRepo(marketResult: market());
    await pump(tester, repo, clock: () => now);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNotNull);

    // 13 seconds later the ticker re-evaluates and fails closed.
    now = now.add(const Duration(seconds: 13));
    await tester.pump(const Duration(seconds: 1));
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNull);
    expect(find.byKey(const ValueKey('paper-fno-stale')), findsOneWidget);

    // Refresh restores eligibility; the user re-selects the contract.
    await tester.tap(find.byKey(const ValueKey('paper-fno-refresh')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-fno-stale')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNotNull);
  });

  testWidgets('a 404 is described as possibly feature-disabled, not as a missing room', (tester) async {
    await pump(tester, _FakeRepo(failure: const ApiHttpFailure('NOT_FOUND', null, 404)));
    expect(find.textContaining('feature may be turned off'), findsOneWidget);
    expect(find.textContaining('is unavailable'), findsNothing);
  });
  testWidgets('held PAPER positions render without entering an expiry', (tester) async {
    final repo = _FakeRepo(positionResult: [heldPosition()],
        failure: const ApiHttpFailure('CONFLICT', null, 409));
    await pump(tester, repo, withExpiry: false);
    expect(find.textContaining('75 qty'), findsOneWidget);
    expect(find.text('No virtual F&O positions.'), findsNothing);
  });

  testWidgets('display-rights 409 hides quotes, not a known virtual position', (tester) async {
    final repo = _FakeRepo(positionResult: [heldPosition()],
        failure: const ApiHttpFailure('CONFLICT', null, 409));
    await pump(tester, repo);
    expect(find.textContaining('display rights are not verified'), findsOneWidget);
    expect(find.textContaining('75 qty'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-submit')), findsNothing);
  });

  testWidgets('failed position read is unknown, not empty positions', (tester) async {
    final repo = _FakeRepo(marketResult: market(),
        positionsFailure: const ApiHttpFailure('UNAVAILABLE', null, 503));
    await pump(tester, repo);
    expect(find.textContaining('Virtual F&O positions unavailable'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-positions-retry')), findsOneWidget);
    expect(find.text('No virtual F&O positions.'), findsNothing);
  });

  testWidgets('HTTP 500 inventory does not become zero orders or enable BUY',
      (tester) async {
    final repo = _FakeRepo(
      marketResult: market(),
      positionsFailure: const ApiHttpFailure('INTERNAL_ERROR', null, 500),
    );
    await pump(tester, repo);
    expect(find.textContaining('Virtual F&O positions unavailable'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-orders-unavailable')), findsOneWidget);
    expect(find.text('No virtual F&O orders.'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(
      find.byKey(const ValueKey('paper-fno-submit'))).onPressed, isNull);
    expect(repo.submits, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets('fresh quotes without display rights never enable a PAPER order', (tester) async {
    final repo = _FakeRepo(marketResult: market(rights: false));
    await pump(tester, repo);
    expect(find.textContaining('Display rights not verified'), findsOneWidget);
    expect(find.textContaining('Bid 101.5'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
        isNull);
    expect(repo.submits, isEmpty);
  });


  testWidgets('a blocked expiry list shows its status and offers no dates or strikes', (tester) async {
    final repo = _FakeRepo(marketResult: market(), expiriesResult: blockedExpiries());
    await pump(tester, repo, withExpiry: false);
    expect(find.byKey(const ValueKey('paper-fno-expiry-blocked')), findsOneWidget);
    expect(find.textContaining('Expiry list not verified (BLOCKED_SOURCE_NOT_BOUND)'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-expiry-dropdown')), findsNothing);
  });

  testWidgets('choosing a verified expiry from the dropdown loads that market', (tester) async {
    final repo = _FakeRepo(marketResult: market(), expiriesResult: verifiedExpiries(['2026-11-20', '2026-11-27']));
    await pump(tester, repo, withExpiry: false);
    await tester.tap(find.byKey(const ValueKey('paper-fno-expiry-dropdown')));
    await tester.pumpAndSettle();
    expect(find.text('Fri, 27 Nov 2026'), findsOneWidget);
    await tester.tap(find.text('Fri, 20 Nov 2026').last);
    await tester.pumpAndSettle();
    expect(find.text('Fresh quotes'), findsOneWidget);
  });

  testWidgets('SELL to close is unavailable when no virtual position is held', (tester) async {
    await pump(tester, _FakeRepo(marketResult: market()));
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    // The SELL segment is disabled, so tapping it leaves the side on BUY.
    await tester.tap(find.text('SELL to close'));
    await tester.pumpAndSettle();
    expect(find.text('Place PAPER BUY'), findsOneWidget);
    expect(find.text('Place PAPER SELL'), findsNothing);
    expect(find.byKey(const ValueKey('paper-fno-no-position')), findsOneWidget);
  });

  testWidgets('SELL is capped at the lots actually held in that contract', (tester) async {
    await pump(tester, _FakeRepo(marketResult: market(), positionResult: [heldCe(75)]));
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('SELL to close'));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNotNull);
    // One held lot caps the stepper at one.
    expect(tester.widget<IconButton>(find.byKey(const ValueKey('paper-fno-lots-plus'))).onPressed,
      isNull);
  });

  testWidgets('virtual F&O orders show each fill with its cash after and realized P&L on sells', (tester) async {
    await pump(tester, _FakeRepo(marketResult: market(), ordersResult: [buyFill(), buyFill(side: 'SELL')]));
    expect(find.byKey(const ValueKey('paper-fno-orders')), findsOneWidget);
    expect(find.textContaining('BUY 1 lot(s)'), findsOneWidget);
    expect(find.descendant(of: find.byKey(const ValueKey('paper-fno-orders')),
      matching: find.textContaining('realized ₹45.00')), findsOneWidget);
  });

  testWidgets('a successful BUY refreshes positions, orders and the room wallet via onFilled', (tester) async {
    var refreshed = 0;
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(900, 2400);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(
      child: MintTickPaperFnoPanel(roomId: 'room', repository: _FakeRepo(
        marketResult: market(), submitResult: buyFill()),
        initialExpiry: DateTime(2026, 11, 20),
        onFilled: () async { refreshed++; })))));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('paper-fno-submit')));
    await tester.pumpAndSettle();
    expect(refreshed, 1);
    expect(find.textContaining('BUY 75 filled'), findsOneWidget);
  });

  testWidgets('the main F&O desk collapses ledger diagnostics until requested', (tester) async {
    await pump(tester, _FakeRepo(marketResult: market(), receiptResult: receiptFixture()));
    expect(find.byKey(const ValueKey('paper-fno-readiness-details')), findsOneWidget);
    expect(find.text('Trading not approved'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-receipt')), findsNothing);
    await tester.tap(find.text('Trading & ledger checks'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-fno-receipt')), findsOneWidget);
    expect(find.textContaining('Approved for trading: no'), findsOneWidget);
  });

  testWidgets('a reconciled ledger is shown as bookkeeping only, never as trading approval', (tester) async {
    await pump(tester, _FakeRepo(marketResult: market(), receiptResult: receiptFixture()),
        view: 'orders');
    expect(find.byKey(const ValueKey('paper-fno-receipt')), findsOneWidget);
    expect(find.textContaining('Ledger reconciled'), findsOneWidget);
    expect(find.textContaining('Approved for trading: no'), findsOneWidget);
    expect(find.textContaining('PROVIDER_RIGHTS_NOT_VERIFIED'), findsOneWidget);
    expect(find.textContaining('does not approve trading'), findsOneWidget);
  });

  testWidgets('a failed bookkeeping read shows unknown readiness and keeps orders visible', (tester) async {
    await pump(tester, _FakeRepo(marketResult: market(), receiptFails: true, ordersResult: [buyFill()]),
        view: 'orders');
    expect(find.byKey(const ValueKey('paper-fno-receipt-unknown')), findsOneWidget);
    expect(find.textContaining('Trading readiness is unknown'), findsOneWidget);
    expect(find.byKey(const ValueKey('paper-fno-orders')), findsOneWidget);
  });
}
