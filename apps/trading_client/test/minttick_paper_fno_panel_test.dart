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

CustomerFnoMarket market({String status = 'FRESH_QUOTES', bool fresh = true}) =>
    CustomerFnoMarket.decode({
      'underlyingKey': 'NSE_INDEX|Nifty 50',
      'expiry': '2026-11-20',
      'status': status,
      'snapshotAt': '2026-11-16T04:30:00Z',
      'displayRightsVerified': true,
      'liveOrderEnabled': false,
      'strikes': [
        {'strikePrice': 24000, 'call': leg(fresh: fresh), 'put': null},
      ],
    });

class _FakeRepo implements CustomerPaperFnoRepository {
  _FakeRepo({this.marketResult, this.failure});
  final CustomerFnoMarket? marketResult;
  final ApiFailure? failure;
  final submits = <Map<String, Object?>>[];

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError('${invocation.memberName}');

  @override
  Future<CustomerFnoMarket> market(String roomId, DateTime expiry) async {
    if (failure != null) throw failure!;
    return marketResult!;
  }

  @override
  Future<List<CustomerFnoPosition>> positions(String roomId) async => const [];

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
    throw const ApiTransportFailure('NETWORK_OR_PARSE_FAILURE', null);
  }
}

Future<void> pump(WidgetTester tester, CustomerPaperFnoRepository repo,
    {bool withExpiry = true}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(900, 2400);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(
    child: MintTickPaperFnoPanel(roomId: 'room', repository: repo,
      initialExpiry: withExpiry ? DateTime(2026, 11, 20) : null)))));
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
}
