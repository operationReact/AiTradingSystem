import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_angel_one_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_angel_one_dashboard.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_broker_compact_widgets.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _ConnectedAngel extends Fake implements HttpCustomerAngelOneRepository {
  int fundLoads = 0;
  int disconnects = 0;
  bool empty = false;
  bool fundsFail = false;
  bool holdingsFail = false;

  @override
  Future<Object?> funds() async {
    fundLoads++;
    if (fundsFail) throw StateError('private upstream failure');
    if (empty) return <String, dynamic>{};
    return <String, dynamic>{
      'net': 5000, 'availablecash': 4000,
      'availableintradaypayin': 100, 'availablelimitmargin': 3000,
      'collateral': 50, 'utiliseddebits': 200,
    };
  }

  @override
  Future<Object?> holdings() async {
    if (holdingsFail) throw StateError('provider timeout');
    return empty ? <Object?>[] : <Object?>[
      <String, dynamic>{
        'tradingsymbol': 'RELIANCE-EQ', 'quantity': 2, 'ltp': 3000,
        'averageprice': 2900, 'pnl': 200,
      },
    ];
  }

  @override
  Future<Object?> positions() async => empty ? <Object?>[] : <Object?>[
    <String, dynamic>{
      'tradingsymbol': 'INFY-EQ', 'netqty': 3, 'ltp': 1400,
    },
  ];

  @override
  Future<Object?> orders() async => empty ? <Object?>[] : <Object?>[
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

Future<void> _show(WidgetTester tester, _ConnectedAngel repository,
    Brightness brightness) async {
  await tester.pumpWidget(MaterialApp(
    theme: buildTheme(brightness),
    home: MintTickAngelOneDashboard(
      repository: repository, connection: _connection, busy: false,
      onRefresh: () async {},
      onDisconnect: () async { repository.disconnects++; },
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  for (final width in <double>[320, 390]) {
    for (final brightness in <Brightness>[Brightness.dark, Brightness.light]) {
      testWidgets('Angel One uses unified broker view at ${width.toInt()}dp '
          '$brightness', (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = Size(width, 844);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final repo = _ConnectedAngel();
        await _show(tester, repo, brightness);
        expect(find.text('CONNECTED'), findsOneWidget);
        expect(find.textContaining('***000'), findsOneWidget);
        expect(find.byType(AngelOneSymbol), findsOneWidget);
        expect(find.byType(MintTickBrokerHeader), findsOneWidget);
        expect(find.byType(MintTickBrokerPortfolioHero), findsOneWidget);
        expect(find.byType(MintTickBrokerTabs), findsOneWidget);
        expect(find.byType(MintTickBrokerSection), findsOneWidget);
        expect(find.text('₹6,000.00'), findsWidgets);
        expect(find.text('₹5,800.00'), findsOneWidget);
        expect(find.text('₹200.00'), findsOneWidget);
        for (final tab in ['holdings', 'positions', 'funds', 'orders']) {
          expect(find.byKey(ValueKey('angel-tab-$tab')), findsOneWidget);
        }
        expect(tester.takeException(), isNull);

        await tester.ensureVisible(find.byKey(const ValueKey('angel-tab-funds')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('angel-tab-funds')));
        await tester.pumpAndSettle();
        expect(find.text('Available cash'), findsOneWidget);
        expect(find.text('₹4,000.00'), findsOneWidget);
        expect(find.text('₹5,000.00'), findsOneWidget);
        expect(tester.takeException(), isNull);

        await tester.ensureVisible(find.byKey(const ValueKey('angel-tab-positions')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('angel-tab-positions')));
        await tester.pumpAndSettle();
        expect(find.text('INFY-EQ'), findsOneWidget);

        await tester.ensureVisible(find.byKey(const ValueKey('angel-tab-orders')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('angel-tab-orders')));
        await tester.pumpAndSettle();
        expect(find.text('TCS-EQ'), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('confirmed empty holdings show zero; invalid funds remain unknown',
      (tester) async {
    final repo = _ConnectedAngel()..empty = true;
    await _show(tester, repo, Brightness.dark);
    expect(find.text('₹0.00'), findsWidgets);
    expect(find.text('No holdings reported by Angel One'), findsOneWidget);
    expect(find.byKey(const ValueKey('angel-portfolio-value')), findsOneWidget);
    expect(find.text('₹4,000.00'), findsNothing);
    expect(find.textContaining('funds unavailable'), findsWidgets);
    await tester.tap(find.byKey(const ValueKey('angel-tab-funds')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Unverified broker response'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('failed funds do not erase valid Angel One holdings',
      (tester) async {
    final repo = _ConnectedAngel()..fundsFail = true;
    await _show(tester, repo, Brightness.dark);
    expect(find.text('₹6,000.00'), findsWidgets);
    expect(find.byKey(const ValueKey('angel-retry-funds')), findsOneWidget);
    expect(find.textContaining('private upstream failure'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unavailable holdings do not become a false zero',
      (tester) async {
    final repo = _ConnectedAngel()..holdingsFail = true;
    await _show(tester, repo, Brightness.dark);
    expect(find.text('₹0.00'), findsNothing);
    expect(find.text('—'), findsWidgets);
    expect(find.textContaining('Holdings data unavailable'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('angel-tab-funds')));
    await tester.pumpAndSettle();
    expect(find.text('₹4,000.00'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('account management opens a sheet, disconnect needs confirmation',
      (tester) async {
    final repo = _ConnectedAngel();
    await _show(tester, repo, Brightness.dark);
    expect(find.byKey(const ValueKey('broker-disconnect')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('angel-account-menu')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Account details'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('broker-disconnect')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('broker-disconnect')));
    await tester.pumpAndSettle();
    expect(find.text('Disconnect Angel One?'), findsOneWidget);
    expect(repo.disconnects, 0);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(repo.disconnects, 0);
  });
}
