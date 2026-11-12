import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_angel_one_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_upstox_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_room_repository.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_room_draft.dart';
import 'package:trading_client/src/features/trading_rooms/domain/trading_workspace_mode.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_broker_hub_page.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_portfolio.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _Rooms extends Fake implements HttpCustomerRoomRepository {}

class _Angel extends Fake implements HttpCustomerAngelOneRepository {
  @override
  Future<CustomerAngelOneConnection> status() async =>
      const CustomerAngelOneConnection(
        connected: false, clientCodeMasked: null,
        expiresAt: null, orderExecutionEnabled: false);
}

class _AngelLinkedUnreadable extends _Angel {
  @override
  Future<CustomerAngelOneConnection> status() async =>
      const CustomerAngelOneConnection(
        connected: true, clientCodeMasked: '***001',
        expiresAt: null, orderExecutionEnabled: false);

  @override
  Future<Object?> funds() async => throw StateError('broker not reachable');

  @override
  Future<Object?> holdings() async => throw StateError('broker not reachable');

  @override
  Future<Object?> positions() async => throw StateError('broker not reachable');
}

class _Upstox extends Fake implements HttpCustomerUpstoxRepository {
  bool fundsMaintenance = false;
  Object? holdingsResponse;

  @override
  Future<CustomerUpstoxStatus> status() async =>
      const CustomerUpstoxStatus(
        available: true, connected: true, accountMasked: '***123',
        expiresAt: null, orderExecutionEnabled: false);

  @override
  Future<Object?> funds() async {
    if (fundsMaintenance) {
      throw const ApiHttpFailure('UPSTOX_FUNDS_MAINTENANCE', null, 503);
    }
    return {'equity': {'available_margin': 1250.5}};
  }

  @override
  Future<Object?> holdings() async => holdingsResponse ?? [
    {'trading_symbol': 'SBIN', 'quantity': 2},
  ];

  @override
  Future<Object?> positions() async => [];
}

void main() {
  testWidgets('missing Angel One data exposes Upstox verified subtotal, not total',
      (tester) async {
    final mode = TradingWorkspaceMode()..select('LIVE');
    final store = TradingRoomDraftStore();
    addTearDown(mode.dispose);
    addTearDown(store.dispose);
    final upstox = _Upstox()..holdingsResponse = [
      {'trading_symbol': 'SBIN', 'quantity': 2, 'last_price': 120},
    ];
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerPortfolioPage(
        mode: mode, rooms: store, paperRepository: _Rooms(),
        brokerRepository: _AngelLinkedUnreadable(), upstoxRepository: upstox,
        onRooms: () {}, onBroker: () {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('—'), findsWidgets);
    expect(find.textContaining('Verified subtotal ₹240.00'), findsOneWidget);
    expect(find.textContaining('complete total unavailable'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Upstox funds maintenance cannot blank valid holdings value',
      (tester) async {
    final mode = TradingWorkspaceMode()..select('LIVE');
    final store = TradingRoomDraftStore();
    addTearDown(mode.dispose);
    addTearDown(store.dispose);
    final upstox = _Upstox()
      ..fundsMaintenance = true
      ..holdingsResponse = [
        {'trading_symbol': 'SBIN', 'quantity': 2, 'last_price': 120},
      ];
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerPortfolioPage(
        mode: mode, rooms: store, paperRepository: _Rooms(),
        brokerRepository: _Angel(), upstoxRepository: upstox,
        onRooms: () {}, onBroker: () {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('₹240.00'), findsOneWidget);
    expect(find.textContaining('Valuation incomplete'), findsNothing);
    await tester.tap(find.text('Broker accounts').first);
    await tester.pumpAndSettle();
    expect(find.textContaining('12:00 AM'), findsWidgets);
    expect(find.textContaining('Missing account data is not a zero'), findsWidgets);
    expect(tester.takeException(), isNull);
  });

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

  testWidgets('compact Live portfolio fits a 320dp phone and opens real holdings',
      (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(320, 740);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final mode = TradingWorkspaceMode()..select('LIVE');
    final store = TradingRoomDraftStore();
    addTearDown(mode.dispose);
    addTearDown(store.dispose);
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerPortfolioPage(
        mode: mode, rooms: store, paperRepository: _Rooms(),
        brokerRepository: _Angel(), upstoxRepository: _Upstox(),
        onRooms: () {}, onBroker: () {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('portfolio-total-holdings')),
        findsOneWidget);
    expect(tester.takeException(), isNull);
    // The section is intentionally below the initial narrow-phone viewport.
    // Scroll it into the hit-testable area before tapping.
    await tester.dragUntilVisible(
      find.text('All holdings'),
      find.byType(ListView).first,
      const Offset(0, -220),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('All holdings'));
    await tester.pumpAndSettle();
    expect(find.text('SBIN'), findsOneWidget);
    expect(find.textContaining('BROKER REPORTED'), findsWidgets);
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
        paperRepository: _Rooms(),
        brokerRepository: _Angel(),
        upstoxRepository: _Upstox(),
        onRooms: () {},
        onBroker: () {},
      ),
    ));
    await tester.pumpAndSettle();
    // Overview shows real source counts without inventing an aggregate
    // valuation from a quote-less Upstox holding.
    expect(find.byKey(const ValueKey('portfolio-total-holdings')),
        findsOneWidget);
    expect(find.textContaining('Valuation incomplete'), findsOneWidget);
    await tester.tap(find.text('Broker accounts').first);
    await tester.pumpAndSettle();
    expect(find.text('LIVE · UPSTOX'), findsOneWidget);
    expect(find.textContaining('₹1,250.50'), findsOneWidget);
    await tester.tap(find.text('Angel One').last);
    await tester.pumpAndSettle();
    expect(find.text('LIVE · ANGEL ONE'), findsOneWidget);
    expect(find.textContaining('₹1,250.50'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
