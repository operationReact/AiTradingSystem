import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_angel_one_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_upstox_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_room_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_workspace_mode.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_broker_hub_page.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_portfolio.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Angel extends Fake implements HttpCustomerAngelOneRepository {
  @override
  Future<CustomerAngelOneConnection> status() async =>
      const CustomerAngelOneConnection(
        connected: false, clientCodeMasked: null,
        expiresAt: null, orderExecutionEnabled: false);
}

class _Upstox extends Fake implements HttpCustomerUpstoxRepository {
  @override
  Future<CustomerUpstoxStatus> status() async =>
      const CustomerUpstoxStatus(
        available: true, connected: true, accountMasked: '***123',
        expiresAt: null, orderExecutionEnabled: false);

  @override
  Future<Object?> funds() async => {
    'equity': {'available_margin': 1250.5},
  };

  @override
  Future<Object?> holdings() async => [
    {'trading_symbol': 'SBIN', 'quantity': 2},
  ];

  @override
  Future<Object?> positions() async => [];
}

void main() {
  testWidgets('broker hub lists Upstox and Angel One on narrow phones',
      (tester) async {
    tester.view.physicalSize = const Size(320, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    var visitedUpstox = false;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickBrokerHubPage(
        angelOne: _Angel(),
        upstox: _Upstox(),
        onAngelOne: () {},
        onUpstox: () => visitedUpstox = true,
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Upstox'), findsOneWidget);
    expect(find.text('Angel One'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('broker-hub-upstox')));
    await tester.pumpAndSettle();
    expect(visitedUpstox, isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Live portfolio reads Upstox but never merges it with Angel One',
      (tester) async {
    final mode = TradingWorkspaceMode()..select('LIVE');
    final store = TradingRoomDraftStore();
    addTearDown(mode.dispose);
    addTearDown(store.dispose);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerPortfolioPage(
        mode: mode,
        rooms: store,
        paperRepository: Fake() as HttpCustomerRoomRepository,
        brokerRepository: _Angel(),
        upstoxRepository: _Upstox(),
        onRooms: () {},
        onBroker: () {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Live · Upstox'), findsOneWidget);
    expect(find.textContaining('₹1250.5'), findsOneWidget);
    expect(find.textContaining('Holdings: 1'), findsOneWidget);
    await tester.tap(find.text('Angel One').first);
    await tester.pumpAndSettle();
    expect(find.text('Live · Angel One'), findsOneWidget);
    expect(find.textContaining('₹1250.5'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
