import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_angel_one_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_angel_one_dashboard.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _ConnectedAngel extends Fake implements HttpCustomerAngelOneRepository {
  int fundLoads = 0;
  int disconnects = 0;
  bool empty = false;

  @override
  Future<Object?> funds() async {
    fundLoads++;
    if (empty) return <String, dynamic>{};
    return <String, dynamic>{
      'net': 5000, 'availablecash': 4000,
      'availableintradaypayin': 100, 'availablelimitmargin': 3000,
      'collateral': 50, 'utiliseddebits': 200,
    };
  }

  @override
  Future<Object?> holdings() async => empty
      ? <Object?>[]
      : <Object?>[
          <String, dynamic>{
            'tradingsymbol': 'RELIANCE-EQ',
            'quantity': 2, 'ltp': 3000, 'averageprice': 2900, 'pnl': 200,
          },
        ];

  @override
  Future<Object?> positions() async => empty
      ? <Object?>[]
      : <Object?>[
          <String, dynamic>{
            'tradingsymbol': 'INFY-EQ', 'netqty': 3, 'ltp': 1400,
          },
        ];

  @override
  Future<Object?> orders() async => empty
      ? <Object?>[]
      : <Object?>[
          <String, dynamic>{
            'tradingsymbol': 'TCS-EQ', 'quantity': 5,
            'status': 'open', 'transactiontype': 'BUY',
          },
          <String, dynamic>{
            'tradingsymbol': 'INFY-EQ', 'quantity': 1,
            'status': 'complete', 'transactiontype': 'SELL',
          },
        ];
}

const _connection = CustomerAngelOneConnection(
  connected: true,
  clientCodeMasked: '***000',
  expiresAt: null,
  orderExecutionEnabled: false,
);

Future<void> _showDashboard(WidgetTester tester, _ConnectedAngel repo,
    Brightness brightness) async {
  await tester.pumpWidget(MaterialApp(
    theme: buildTheme(brightness),
    home: MintTickAngelOneDashboard(
      repository: repo,
      connection: _connection,
      busy: false,
      onRefresh: () async {},
      onDisconnect: () async { repo.disconnects++; },
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  for (final width in <double>[320, 390]) {
    for (final brightness in <Brightness>[Brightness.dark, Brightness.light]) {
      testWidgets('broker panels at ${width.toInt()}dp $brightness', (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = Size(width, 844);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final repo = _ConnectedAngel();
        await _showDashboard(tester, repo, brightness);
        expect(find.text('CONNECTED'), findsOneWidget);
        expect(find.textContaining('***000'), findsWidgets);
        expect(find.byType(AngelOneSymbol), findsOneWidget);
        expect(find.byKey(const ValueKey('angel-open-funds')), findsOneWidget);
        expect(find.byKey(const ValueKey('angel-open-holdings')), findsOneWidget);
        expect(find.byKey(const ValueKey('angel-open-positions')), findsOneWidget);
        expect(find.byKey(const ValueKey('angel-open-orders')), findsOneWidget);
        expect(find.text('₹4,000.00'), findsOneWidget);
        expect(tester.takeException(), isNull);

        await tester.tap(find.byKey(const ValueKey('angel-open-funds')));
        await tester.pumpAndSettle();
        expect(find.text('Funds & Margin'), findsOneWidget);
        expect(find.text('₹5,000.00'), findsOneWidget);
        expect(find.textContaining('not supplied by this broker endpoint'),
            findsOneWidget);
        expect(tester.takeException(), isNull);

        await tester.tap(find.byKey(const ValueKey('angel-back-overview')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('angel-open-holdings')));
        await tester.pumpAndSettle();
        expect(find.text('Portfolio value'), findsOneWidget);
        expect(find.text('₹6,000.00'), findsOneWidget);
        expect(tester.takeException(), isNull);

        await tester.tap(find.byKey(const ValueKey('angel-back-overview')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('angel-open-positions')));
        await tester.pumpAndSettle();
        expect(find.text('Positions'), findsOneWidget);
        expect(tester.takeException(), isNull);

        await tester.tap(find.byKey(const ValueKey('angel-back-overview')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('angel-open-orders')));
        await tester.pumpAndSettle();
        expect(find.text('Orders & Activity'), findsOneWidget);
        expect(find.text('Open Orders'), findsOneWidget);
        await tester.tap(find.text('Activity'));
        await tester.pumpAndSettle();
        expect(find.textContaining('does not currently provide'),
            findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('empty account data never becomes fictional holdings',
      (tester) async {
    final repo = _ConnectedAngel()..empty = true;
    await _showDashboard(tester, repo, Brightness.dark);
    expect(find.text('RELIANCE-EQ'), findsNothing);
    expect(find.text('₹12,64,320.50'), findsNothing);
    expect(find.text('₹4,000.00'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('angel-open-funds')));
    await tester.pumpAndSettle();
    expect(find.text('—'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('disconnect confirms without placing orders', (tester) async {
    final repo = _ConnectedAngel();
    await _showDashboard(tester, repo, Brightness.dark);
    final disconnect = find.byKey(const ValueKey('broker-disconnect'));
    await tester.scrollUntilVisible(disconnect, 250,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(disconnect);
    await tester.pumpAndSettle();
    expect(find.text('Disconnect Angel One?'), findsOneWidget);
    expect(repo.disconnects, 0);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(repo.disconnects, 0);
  });
}
