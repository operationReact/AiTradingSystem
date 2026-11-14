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

class _FakeRepo implements CustomerPaperFnoRepository {
  _FakeRepo({this.marketResult, this.markets, this.failure, this.submitFailure,
      this.positionResult = const [], this.positionsFailure});
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
    if (failure != null) throw failure!;
    if (markets != null) {
      final i = _marketCalls < markets!.length ? _marketCalls : markets!.length - 1;
      _marketCalls++;
      return markets![i];
    }
    return marketResult!;
  }

  @override
  Future<List<CustomerFnoPosition>> positions(String roomId) async {
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
    throw submitFailure ?? const ApiTransportFailure('NETWORK_OR_PARSE_FAILURE', null);
  }
}

Future<void> pump(WidgetTester tester, CustomerPaperFnoRepository repo,
    {bool withExpiry = true, DateTime Function()? clock}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(900, 2400);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(
    child: MintTickPaperFnoPanel(roomId: 'room', repository: repo,
      initialExpiry: withExpiry ? DateTime(2026, 11, 20) : null,
      clock: clock ?? DateTime.now)))));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('without an entered expiry no request is made and the user is asked for one', (tester) async {
    final repo = _FakeRepo(failure: const ApiHttpFailure('CONFLICT', null, 409));
    await pump(tester, repo, withExpiry: false);
    expect(find.textContaining('Enter the expiry'), findsOneWidget);
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
    expect(find.text('102.0'), findsOneWidget);
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
    final repo = _FakeRepo(marketResult: market());
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
    final repo = _FakeRepo(markets: [twoLegs(ceFresh: true), twoLegs(ceFresh: false)]);
    await pump(tester, repo);
    await tester.tap(find.byKey(const ValueKey('paper-fno-leg-NSE_FO|NIFTY24CE')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('paper-fno-submit'))).onPressed,
      isNotNull);

    // Refresh through the same Load control; the CE is now stale.
    await tester.tap(find.byKey(const ValueKey('paper-fno-expiry-load')));
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

}
